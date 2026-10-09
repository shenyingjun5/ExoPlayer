/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.rtsp;

import static com.google.android.exoplayer2.source.rtsp.MediaDescription.MEDIA_TYPE_VIDEO;
import static com.google.android.exoplayer2.source.rtsp.MediaDescription.RTP_AVP_PROFILE;
import static com.google.android.exoplayer2.source.rtsp.SessionDescription.ATTR_CONTROL;
import static com.google.android.exoplayer2.source.rtsp.SessionDescription.ATTR_FMTP;
import static com.google.android.exoplayer2.source.rtsp.SessionDescription.ATTR_RTPMAP;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.robolectric.RobolectricUtil;
import com.google.android.exoplayer2.C;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.BindException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RtpDataLoadable}. */
@RunWith(AndroidJUnit4.class)
public final class RtpDataLoadableTest {

  @Test
  public void load_dataChannelFactoryThrowsBeforeChannelCreated_preservesOriginalError()
      throws Exception {
    IOException bindFailure = new IOException(new BindException("port unavailable"));
    RtpDataLoadable loadable =
        new RtpDataLoadable(
            /* trackId= */ 0,
            createH264MediaTrack(),
            (transport, rtpDataChannel) -> {},
            new FakeExtractorOutput(),
            trackId -> {
              throw bindFailure;
            });

    IOException thrown = assertThrows(IOException.class, loadable::load);

    assertThat(thrown).isSameInstanceAs(bindFailure);
    assertThat(thrown).hasCauseThat().isInstanceOf(BindException.class);
  }

  @Test
  public void load_packetAvailableBeforePlay_doesNotChooseIndependentOrigin() throws Exception {
    try (StartupHarness h = new StartupHarness()) {
      h.start();
      assertThat(h.readStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();
      h.loadable.setTimestamp(0);
      h.loadable.setSequenceNumber(1);
      h.loadable.onPlaybackStarted();
      h.finish();
      assertThat(h.failure.get()).isNull();
      assertThat(h.output.trackOutputs.get(0).getSampleTimeUs(0)).isEqualTo(1016655L);
      assertThat(h.output.trackOutputs.get(0).getSampleTimeUs(1)).isEqualTo(1049988L);
    }
  }

  @Test
  public void load_playWithoutRtpInfo_explicitlyAllowsFirstPacketFallback() throws Exception {
    try (StartupHarness h = new StartupHarness()) {
      h.start();
      h.loadable.onPlaybackStarted();
      h.finish();
      assertThat(h.failure.get()).isNull();
      assertThat(h.output.trackOutputs.get(0).getSampleTimeUs(0)).isEqualTo(0);
      assertThat(h.output.trackOutputs.get(0).getSampleTimeUs(1)).isEqualTo(33333L);
    }
  }

  @Test
  public void load_cancelBeforePlay_unblocksWithoutReadingPackets() throws Exception {
    try (StartupHarness h = new StartupHarness()) {
      h.start();
      h.loadable.cancelLoad();
      h.finish();
      assertThat(h.failure.get()).isNull();
      assertThat(h.readStarted.getCount()).isEqualTo(1);
    }
  }

  @Test
  public void load_missingPlayResponse_timesOutWithoutChoosingOrigin() throws Exception {
    try (StartupHarness h = new StartupHarness()) {
      h.start();
      // The loader uses the elapsed-realtime clock; advance Robolectric's clock while it waits.
      org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(
          RtspMediaSource.DEFAULT_TIMEOUT_MS + 1));
      h.thread.join(RtspMediaSource.DEFAULT_TIMEOUT_MS + 2000);
      assertThat(h.thread.isAlive()).isFalse();
      assertThat(h.failure.get()).isInstanceOf(java.net.SocketTimeoutException.class);
      assertThat(h.readStarted.getCount()).isEqualTo(1);
    }
  }

  private static final class StartupHarness implements AutoCloseable {
    final CountDownLatch transportReady = new CountDownLatch(1);
    final CountDownLatch readStarted = new CountDownLatch(1);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final FakeExtractorOutput output = new FakeExtractorOutput();
    final RtpDataLoadable loadable;
    final Thread thread;

    StartupHarness() throws Exception {
      RtpDataChannel channel = mock(RtpDataChannel.class);
      when(channel.getTransport()).thenReturn("RTP/AVP/TCP;unicast;interleaved=0-1");
      when(channel.getInterleavedBinaryDataListener()).thenReturn(data -> {});
      when(channel.needsClosingOnLoadCompletion()).thenReturn(true);
      AtomicInteger reads = new AtomicInteger();
      when(channel.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
        readStarted.countDown();
        int index = reads.getAndIncrement();
        if (index >= 2) return C.RESULT_END_OF_INPUT;
        byte[] bytes = new byte[15];
        new RtpPacket.Builder().setMarker(true).setPayloadType((byte) 96)
            .setSequenceNumber(index + 1).setTimestamp(91499 + 3000 * index)
            .setSsrc(0x12345678).setPayloadData(new byte[] {0x65, 0x01, 0x02}).build()
            .writeToBuffer(bytes, 0, bytes.length);
        System.arraycopy(bytes, 0, invocation.getArgument(0), invocation.getArgument(1), bytes.length);
        return bytes.length;
      });
      loadable = new RtpDataLoadable(0, createH264MediaTrack(),
          (transport, dataChannel) -> transportReady.countDown(), output, id -> channel);
      thread = new Thread(() -> {
        try { loadable.load(); } catch (Throwable e) { failure.set(e); }
      });
    }

    void start() throws Exception {
      thread.start();
      RobolectricUtil.runMainLooperUntil(() -> transportReady.getCount() == 0);
    }

    void finish() throws Exception {
      thread.join(2000);
      assertThat(thread.isAlive()).isFalse();
    }

    @Override public void close() throws Exception {
      loadable.cancelLoad();
      thread.interrupt();
      thread.join(2000);
    }
  }

  private static RtspMediaTrack createH264MediaTrack() {
    MediaDescription mediaDescription =
        new MediaDescription.Builder(
                MEDIA_TYPE_VIDEO, /* port= */ 0, RTP_AVP_PROFILE, /* payloadType= */ 96)
            .setConnection("IN IP4 0.0.0.0")
            .setBitrate(500_000)
            .addAttribute(ATTR_RTPMAP, "96 H264/90000")
            .addAttribute(
                ATTR_FMTP,
                "96 packetization-mode=1;profile-level-id=64001F;"
                    + "sprop-parameter-sets=Z2QAH6zZQPARabIAAAMACAAAAwGcHjBjLA==,aOvjyyLA")
            .addAttribute(ATTR_CONTROL, "track1")
            .build();
    RtspHeaders rtspHeaders =
        new RtspHeaders.Builder()
            .addAll(
                ImmutableList.of(
                    "Accept: application/sdp",
                    "CSeq: 3",
                    "Content-Length: 707",
                    "Transport: RTP/AVP;unicast;client_port=65458-65459\r\n"))
            .build();
    return new RtspMediaTrack(rtspHeaders, mediaDescription, Uri.parse("rtsp://test.com"));
  }
}
