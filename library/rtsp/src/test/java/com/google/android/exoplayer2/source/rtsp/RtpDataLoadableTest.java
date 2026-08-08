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

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
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
