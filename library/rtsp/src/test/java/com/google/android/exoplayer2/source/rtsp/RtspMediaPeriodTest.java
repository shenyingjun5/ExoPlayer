/*
 * Copyright 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.robolectric.RobolectricUtil;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.BindException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLooper;

/** Tests the {@link RtspMediaPeriod} using the {@link RtspServer}. */
@RunWith(AndroidJUnit4.class)
public final class RtspMediaPeriodTest {

  private static final long DEFAULT_TIMEOUT_MS = 8000;
  private static final long SEEK_POSITION_US = 500_000;

  private final AtomicReference<TrackGroup> trackGroupAtomicReference = new AtomicReference<>();
  private final MediaPeriod.Callback mediaPeriodCallback =
      new MediaPeriod.Callback() {
        @Override
        public void onPrepared(MediaPeriod mediaPeriod) {
          trackGroupAtomicReference.set(mediaPeriod.getTrackGroups().get(0));
        }

        @Override
        public void onContinueLoadingRequested(MediaPeriod source) {
          source.continueLoading(/* positionUs= */ 0);
        }
      };

  private RtpPacketStreamDump rtpPacketStreamDump;
  private RtspMediaPeriod mediaPeriod;
  private RtspServer rtspServer;

  @Before
  public void setUp() throws IOException {
    rtpPacketStreamDump = RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");
  }

  @After
  public void tearDown() {
    if (mediaPeriod != null) {
      mediaPeriod.release();
      mediaPeriod = null;
    }
    Util.closeQuietly(rtspServer);
  }

  @Test
  public void prepareMediaPeriod_refreshesSourceInfoAndCallsOnPrepared() throws Exception {
    RtpPacketStreamDump rtpPacketStreamDump =
        RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");

    rtspServer =
        new RtspServer(
            new RtspServer.ResponseProvider() {
              @Override
              public RtspResponse getOptionsResponse() {
                return new RtspResponse(
                    /* status= */ 200,
                    new RtspHeaders.Builder().add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE").build());
              }

              @Override
              public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
                return RtspTestUtils.newDescribeResponseWithSdpMessage(
                    "v=0\r\n"
                        + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
                        + "s=Exoplayer test\r\n"
                        + "t=0 0\r\n"
                        // The session is 50.46s long.
                        + "a=range:npt=0-50.46\r\n",
                    ImmutableList.of(rtpPacketStreamDump),
                    requestedUri);
              }
            });

    AtomicBoolean prepareCallbackCalled = new AtomicBoolean();
    AtomicLong refreshedSourceDurationMs = new AtomicLong();

    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> refreshedSourceDurationMs.set(timing.getDurationMs()),
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            prepareCallbackCalled.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            source.continueLoading(/* positionUs= */ 0);
          }
        },
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(prepareCallbackCalled::get);
    assertThat(refreshedSourceDurationMs.get()).isEqualTo(50_460);
  }

  @Test
  public void prepareMediaPeriod_dataChannelBindFailsOnce_retriesAndPrepares() throws Exception {
    RtpPacketStreamDump rtpPacketStreamDump =
        RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");
    rtspServer =
        new RtspServer(
            new RtspServer.ResponseProvider() {
              @Override
              public RtspResponse getOptionsResponse() {
                return new RtspResponse(
                    /* status= */ 200,
                    new RtspHeaders.Builder().add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE").build());
              }

              @Override
              public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
                return RtspTestUtils.newDescribeResponseWithSdpMessage(
                    "v=0\r\n"
                        + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
                        + "s=Exoplayer test\r\n"
                        + "t=0 0\r\n"
                        + "a=range:npt=0-50.46\r\n",
                    ImmutableList.of(rtpPacketStreamDump),
                    requestedUri);
              }
            });
    AtomicInteger dataChannelOpenCount = new AtomicInteger();
    TransferRtpDataChannelFactory delegate = new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS);
    RtpDataChannel.Factory failFirstFactory =
        trackId -> {
          if (dataChannelOpenCount.getAndIncrement() == 0) {
            throw new IOException(new BindException("transient port conflict"));
          }
          return delegate.createAndOpenDataChannel(trackId);
        };
    AtomicBoolean prepareCallbackCalled = new AtomicBoolean();

    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            failFirstFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            prepareCallbackCalled.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            source.continueLoading(/* positionUs= */ 0);
          }
        },
        /* positionUs= */ 0);

    RobolectricUtil.runMainLooperUntil(prepareCallbackCalled::get);
    assertThat(dataChannelOpenCount.get()).isEqualTo(2);
  }

  @Test
  public void prepareMediaPeriod_withWwwAuthentication_refreshesSourceInfoAndCallsOnPrepared()
      throws Exception {
    RtpPacketStreamDump rtpPacketStreamDump =
        RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");

    rtspServer =
        new RtspServer(
            new RtspServer.ResponseProvider() {
              @Override
              public RtspResponse getOptionsResponse() {
                return new RtspResponse(
                    /* status= */ 200,
                    new RtspHeaders.Builder().add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE").build());
              }

              @Override
              public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
                String authorizationHeader = headers.get(RtspHeaders.AUTHORIZATION);
                if (authorizationHeader == null) {
                  return new RtspResponse(
                      /* status= */ 401,
                      new RtspHeaders.Builder()
                          .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                          .add(
                              RtspHeaders.WWW_AUTHENTICATE,
                              "Digest realm=\"RTSP server\","
                                  + " nonce=\"0cdfe9719e7373b7d5bb2913e2115f3f\","
                                  + " opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
                          .add(RtspHeaders.WWW_AUTHENTICATE, "BASIC realm=\"WallyWorld\"")
                          .build());
                }

                if (!authorizationHeader.contains("Digest")) {
                  return new RtspResponse(
                      401,
                      new RtspHeaders.Builder()
                          .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                          .build());
                }

                return RtspTestUtils.newDescribeResponseWithSdpMessage(
                    "v=0\r\n"
                        + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
                        + "s=Exoplayer test\r\n"
                        + "t=0 0\r\n"
                        // The session is 50.46s long.
                        + "a=range:npt=0-50.46\r\n",
                    ImmutableList.of(rtpPacketStreamDump),
                    requestedUri);
              }
            });
    AtomicBoolean prepareCallbackCalled = new AtomicBoolean();
    AtomicLong refreshedSourceDurationMs = new AtomicLong();

    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUriWithUserInfo(
                "username", "password", rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> refreshedSourceDurationMs.set(timing.getDurationMs()),
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            prepareCallbackCalled.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            source.continueLoading(/* positionUs= */ 0);
          }
        },
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(prepareCallbackCalled::get);
    assertThat(refreshedSourceDurationMs.get()).isEqualTo(50_460);
  }

  @Test
  public void seekToUs_withRapidScrubbingAfterSuccessfulPlayResponse_doesNotTriggerTcpFallback()
      throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new FakeRtpDataChannelFactory(),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    long seekPositionUs = 5000000;

    mediaPeriod.seekToUs(0);
    mediaPeriod.seekToUs(seekPositionUs);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(seekPositionUs);
  }

  @Test
  public void seekToUs_duringInitialization_succeedsWithoutException() throws Exception {
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new FakeRtpDataChannelFactory(),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);

    // Perform multiple seeks before PLAY response is received (during RTSP_STATE_INIT).
    mediaPeriod.seekToUs(1000000);
    mediaPeriod.seekToUs(5000000);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(5000000);
  }

  @Test
  public void seekToUs_toEarlierPositionWhileSeekPending_buffersSamplesFromNewPosition()
      throws Exception {
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump = readAacLcStreamDump();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);

    mediaPeriod.seekToUs(10_000_000);
    mediaPeriod.seekToUs(SEEK_POSITION_US);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    // The exact end position depends on how much of the RTP reordering queue has been flushed,
    // which is driven by wall-clock time. Only assert that samples from the new seek position are
    // buffered at all: without resetting the sample queues, every sample is discarded and the
    // buffered position stays 0.
    // The exact end position depends on how much of the RTP reordering queue has been flushed by
    // wall-clock time, so assert that the buffered position advanced past the seek position rather
    // than comparing with a fixed value. A buffered position that stays at the seek position (or at
    // 0) means the samples from the new position were never buffered.
    RobolectricUtil.runMainLooperUntil(
        () -> mediaPeriod.getBufferedPositionUs() > SEEK_POSITION_US);
    assertThat(mediaPeriod.getBufferedPositionUs()).isGreaterThan(SEEK_POSITION_US);
  }

  @Test
  public void
      seekToUs_toEarlierPositionDuringPlayingStateWhilePausePending_buffersSamplesFromNewPosition()
          throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump = readAacLcStreamDump();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);

    // Seek forward then backward while in RTSP_STATE_PLAYING waiting for PAUSE response.
    mediaPeriod.seekToUs(10_000_000);
    mediaPeriod.seekToUs(SEEK_POSITION_US);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    // The exact end position depends on how much of the RTP reordering queue has been flushed by
    // wall-clock time, so assert that the buffered position advanced past the seek position rather
    // than comparing with a fixed value. A buffered position that stays at the seek position (or at
    // 0) means the samples from the new position were never buffered.
    RobolectricUtil.runMainLooperUntil(
        () -> mediaPeriod.getBufferedPositionUs() > SEEK_POSITION_US);
    assertThat(mediaPeriod.getBufferedPositionUs()).isGreaterThan(SEEK_POSITION_US);
  }

  @Test
  public void seekToUs_withinBufferedRange_retainsBufferedPosition() throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump = readAacLcStreamDump();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() > 0);
    long bufferedPositionUs = mediaPeriod.getBufferedPositionUs();

    mediaPeriod.seekToUs(200_000);

    // An in-buffer seek is satisfied synchronously, so it must not pin the buffered position to
    // the seek position.
    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(bufferedPositionUs);
  }

  @Test
  public void seekToUs_duringReadyStateWhilePlayResponseInFlight_requestsNewPlayAtLatestPosition()
      throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump = readAacLcStreamDump();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 10_000_000);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);

    // Seek to 500_000 us while in RTSP_STATE_READY before the client processes the first PLAY
    // response on the main looper.
    getPlayResponseReference.set(false);
    mediaPeriod.seekToUs(500_000);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);

    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    // The exact end position depends on how much of the RTP reordering queue has been flushed by
    // wall-clock time, so assert that the buffered position advanced past the seek position rather
    // than comparing with a fixed value. A buffered position that stays at the seek position (or at
    // 0) means the samples from the new position were never buffered.
    RobolectricUtil.runMainLooperUntil(
        () -> mediaPeriod.getBufferedPositionUs() > SEEK_POSITION_US);
    assertThat(mediaPeriod.getBufferedPositionUs()).isGreaterThan(SEEK_POSITION_US);
  }

  @Test
  public void onLoadCompleted_afterPrepare_doesNotTriggerTcpFallback() throws Exception {
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);

    // Finishes the RTP load without any sample having been received, which is the state that used
    // to be mistaken for "UDP delivers nothing" and switched the session to RTP-over-TCP.
    rtpDataChannelFactory.getDataChannel().completeLoad();
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::hasReturnedEndOfInput);
    Thread.sleep(300);
    ShadowLooper.idleMainLooper();

    assertThat(rtpDataChannelFactory.wasFallbackUsed()).isFalse();
  }

  private static RtpPacketStreamDump readAacLcStreamDump() throws IOException {
    return RtpPacketStreamDump.parse(
        TestUtil.getString(ApplicationProvider.getApplicationContext(), "media/rtsp/aac-dump.json")
            .replace("profile-level-id=1", "profile-level-id=2"));
  }

  private static class TestResponseProvider implements RtspServer.ResponseProvider {
    private static final String SESSION_ID = "00000000";

    private final ImmutableList<RtpPacketStreamDump> rtpPacketStreamDumps;
    @Nullable private final AtomicBoolean getPlayResponseReference;
    private final boolean isWwwAuthenticationMode;

    private TestResponseProvider(
        RtpPacketStreamDump rtpPacketStreamDump,
        @Nullable AtomicBoolean getPlayResponseReference,
        boolean isWwwAuthenticationMode) {
      this.rtpPacketStreamDumps = ImmutableList.of(rtpPacketStreamDump);
      this.getPlayResponseReference = getPlayResponseReference;
      this.isWwwAuthenticationMode = isWwwAuthenticationMode;
    }

    @Override
    public RtspResponse getOptionsResponse() {
      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE")
              .build());
    }

    @Override
    public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
      if (isWwwAuthenticationMode) {
        String authorizationHeader = headers.get(RtspHeaders.AUTHORIZATION);
        if (authorizationHeader == null) {
          return new RtspResponse(
              /* status= */ 401,
              new RtspHeaders.Builder()
                  .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                  .add(
                      RtspHeaders.WWW_AUTHENTICATE,
                      "Digest realm=\"RTSP server\","
                          + " nonce=\"0cdfe9719e7373b7d5bb2913e2115f3f\","
                          + " opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
                  .add(RtspHeaders.WWW_AUTHENTICATE, "BASIC realm=\"WallyWorld\"")
                  .build());
        }

        if (!authorizationHeader.contains("Digest")) {
          return new RtspResponse(
              401,
              new RtspHeaders.Builder()
                  .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                  .build());
        }
      }

      return RtspTestUtils.newDescribeResponseWithSdpMessage(
          "v=0\r\n"
              + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
              + "s=Exoplayer test\r\n"
              + "t=0 0\r\n"
              // The session is 50.46s long.
              + "a=range:npt=0-50.46\r\n",
          rtpPacketStreamDumps,
          requestedUri);
    }

    @Override
    public RtspResponse getSetupResponse(Uri requestedUri, RtspHeaders headers) {
      return new RtspResponse(
          /* status= */ 200, headers.buildUpon().add(RtspHeaders.SESSION, SESSION_ID).build());
    }

    @Override
    public RtspResponse getPlayResponse() {
      if (getPlayResponseReference != null) {
        getPlayResponseReference.set(true);
      }

      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.RTP_INFO, RtspTestUtils.getRtpInfoForDumps(rtpPacketStreamDumps))
              .build());
    }
  }

  private static final class FakeRtpDataChannel implements RtpDataChannel {
    private final LinkedBlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean allPacketsProcessed = new AtomicBoolean();
    private final AtomicBoolean loadCompleted = new AtomicBoolean();
    private final AtomicBoolean returnedEndOfInput = new AtomicBoolean();

    private void completeLoad() {
      loadCompleted.set(true);
    }

    private boolean hasReturnedEndOfInput() {
      return returnedEndOfInput.get();
    }

    private void enqueuePackets(List<String> hexPackets) {
      allPacketsProcessed.set(false);
      for (String hexPacket : hexPackets) {
        packetQueue.add(Util.getBytesFromHexString(hexPacket));
      }
      // Enqueue an empty sentinel packet so read() knows when all preceding packets have been
      // extracted and processed by the background loader thread.
      packetQueue.add(new byte[0]);
    }

    private boolean areAllPacketsProcessed() {
      return allPacketsProcessed.get();
    }

    @Override
    public String getTransport() {
      return "RTP/AVP;unicast;client_port=1234-1235";
    }

    @Override
    public int getLocalPort() {
      return 1234;
    }

    @Override
    public boolean needsClosingOnLoadCompletion() {
      return false;
    }

    @Nullable
    @Override
    public RtspMessageChannel.InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
      return null;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {}

    @Override
    public long open(DataSpec dataSpec) {
      return C.LENGTH_UNSET;
    }

    @Nullable
    @Override
    public Uri getUri() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public int read(byte[] buffer, int offset, int length) {
      try {
        while (!loadCompleted.get()) {
          byte[] packet = packetQueue.poll(50, TimeUnit.MILLISECONDS);
          if (packet == null) {
            continue;
          }
          if (packet.length == 0) {
            if (packetQueue.isEmpty()) {
              allPacketsProcessed.set(true);
            }
            continue;
          }
          int bytesToRead = min(length, packet.length);
          System.arraycopy(packet, /* srcPos= */ 0, buffer, offset, bytesToRead);
          return bytesToRead;
        }
        // Finishes the load, as a UDP socket that stops delivering data would.
        returnedEndOfInput.set(true);
        return C.RESULT_END_OF_INPUT;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        returnedEndOfInput.set(true);
        return C.RESULT_END_OF_INPUT;
      }
    }
  }

  private static final class FakeRtpDataChannelFactory implements RtpDataChannel.Factory {
    private final FakeRtpDataChannel dataChannel = new FakeRtpDataChannel();
    private final AtomicBoolean fallbackUsed = new AtomicBoolean();

    @Override
    public RtpDataChannel createAndOpenDataChannel(int trackId) {
      return dataChannel;
    }

    private FakeRtpDataChannel getDataChannel() {
      return dataChannel;
    }

    private boolean wasFallbackUsed() {
      return fallbackUsed.get();
    }

    @Override
    public RtpDataChannel.Factory createFallbackDataChannelFactory() {
      return trackId -> {
        fallbackUsed.set(true);
        return new FakeRtpDataChannel();
      };
    }
  }
}
