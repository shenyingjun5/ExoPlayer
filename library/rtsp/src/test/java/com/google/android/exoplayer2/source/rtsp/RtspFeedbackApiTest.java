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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for RTSP feedback and diagnostics API skeleton. */
@RunWith(AndroidJUnit4.class)
public final class RtspFeedbackApiTest {

  @Test
  public void factoryDefaults_useNullListenersAndDefaultPolicy() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspDiagnosticsListener()).isNull();
    assertThat(mediaSource.getRtspFeedbackListener()).isNull();
    assertThat(mediaSource.getRtcpFeedbackPolicy()).isEqualTo(RtcpFeedbackPolicy.DEFAULT);
    assertThat(mediaSource.getRtspPacketDiagnosticsEnabled()).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.pliEnabled).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.firEnabled).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.sequenceGapRequestThreshold).isEqualTo(0);
    assertThat(RtcpFeedbackPolicy.DEFAULT.requestKeyFrameOnQueueReset).isFalse();
    assertThat(new RtcpFeedbackPolicy.Builder().build()).isEqualTo(RtcpFeedbackPolicy.DEFAULT);

    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    assertThat(mediaPeriod.getRtspDiagnosticsListener()).isNull();
    assertThat(mediaPeriod.getRtspFeedbackListener()).isNull();
    assertThat(mediaPeriod.getRtcpFeedbackPolicy()).isEqualTo(RtcpFeedbackPolicy.DEFAULT);
    assertThat(mediaPeriod.getRtspPacketDiagnosticsEnabled()).isFalse();

    mediaSource.releasePeriod(mediaPeriod);
  }

  @Test
  public void factorySetters_passConfigurationToMediaPeriod() {
    RtspDiagnosticsListener diagnosticsListener = new RtspDiagnosticsListener() {};
    RtspFeedbackListener feedbackListener = new RtspFeedbackListener() {};
    RtcpFeedbackPolicy feedbackPolicy =
        new RtcpFeedbackPolicy.Builder()
            .setMinRequestIntervalMs(250)
            .setPliEnabled(true)
            .setFirEnabled(false)
            .build();

    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .setRtspFeedbackListener(feedbackListener)
            .setRtcpFeedbackPolicy(feedbackPolicy)
            .setRtspPacketDiagnosticsEnabled(true)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspDiagnosticsListener()).isSameInstanceAs(diagnosticsListener);
    assertThat(mediaSource.getRtspFeedbackListener()).isSameInstanceAs(feedbackListener);
    assertThat(mediaSource.getRtcpFeedbackPolicy()).isEqualTo(feedbackPolicy);
    assertThat(mediaSource.getRtspPacketDiagnosticsEnabled()).isTrue();

    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    assertThat(mediaPeriod.getRtspDiagnosticsListener()).isSameInstanceAs(diagnosticsListener);
    assertThat(mediaPeriod.getRtspFeedbackListener()).isSameInstanceAs(feedbackListener);
    assertThat(mediaPeriod.getRtcpFeedbackPolicy()).isEqualTo(feedbackPolicy);
    assertThat(mediaPeriod.getRtspPacketDiagnosticsEnabled()).isTrue();

    mediaSource.releasePeriod(mediaPeriod);
  }

  @Test
  public void factoryWithRtsptUri_forcesTcpAndUsesRtspUriInternally() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri("rtspt://127.0.0.1/test"));

    assertThat(mediaSource.getUri()).isEqualTo(android.net.Uri.parse("rtsp://127.0.0.1/test"));
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(TransferRtpDataChannelFactory.class);
  }

  @Test
  public void rtcpFeedbackPolicyBuilder_buildsExpectedValue() {
    RtcpFeedbackPolicy policy =
        new RtcpFeedbackPolicy.Builder()
            .setMinRequestIntervalMs(123)
            .setPliEnabled(false)
            .setFirEnabled(true)
            .build();

    assertThat(policy.minRequestIntervalMs).isEqualTo(123);
    assertThat(policy.pliEnabled).isFalse();
    assertThat(policy.firEnabled).isTrue();
    assertThat(policy)
        .isEqualTo(
            new RtcpFeedbackPolicy.Builder()
                .setMinRequestIntervalMs(123)
                .setPliEnabled(false)
                .setFirEnabled(true)
                .build());
    assertThat(policy.toString()).contains("minRequestIntervalMs=123");
  }

  @Test
  public void rtcpFeedbackPolicyLowLatencyDefault_enablesAutomaticFeedback() {
    RtcpFeedbackPolicy policy = RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT;

    assertThat(policy.minRequestIntervalMs)
        .isEqualTo(RtcpFeedbackPolicy.DEFAULT_MIN_REQUEST_INTERVAL_MS);
    assertThat(policy.pliEnabled).isTrue();
    assertThat(policy.firEnabled).isTrue();
    assertThat(policy.sequenceGapRequestThreshold)
        .isEqualTo(RtcpFeedbackPolicy.DEFAULT_SEQUENCE_GAP_REQUEST_THRESHOLD);
    assertThat(policy.requestKeyFrameOnQueueReset).isTrue();
    assertThat(new RtcpFeedbackPolicy.Builder().setLowLatencyDefaults().build()).isEqualTo(policy);
  }

  @Test
  public void diagnosticsValueTypes_haveStableEquality() {
    RtpPacketStats packetStats =
        new RtpPacketStats(
            /* trackId= */ 1,
            RtspTransportMode.TCP_INTERLEAVED,
            /* payloadType= */ 96,
            /* sequenceNumber= */ 10,
            /* rtpTimestamp= */ 1234,
            /* arrivalElapsedRealtimeMs= */ 5678,
            /* ssrc= */ 0x12345678,
            /* marker= */ true);
    RtpReorderingStats reorderingStats =
        new RtpReorderingStats(
            /* trackId= */ 1,
            RtspTransportMode.TCP_INTERLEAVED,
            /* queueDepth= */ 2,
            /* lastReceivedSequenceNumber= */ 11,
            /* lastDequeuedSequenceNumber= */ 10,
            /* sequenceGap= */ 1,
            /* droppedBeforeEnqueueCount= */ 3,
            /* duplicatePacketCount= */ 4,
            /* resetCount= */ 5);
    RtcpFeedbackRequest feedbackRequest =
        new RtcpFeedbackRequest(
            /* trackId= */ 1,
            RtcpFeedbackReason.SEQUENCE_GAP,
            /* requestElapsedRealtimeMs= */ 999,
            /* detail= */ "gap");
    RtspH264AccessUnitStats accessUnitStats =
        new RtspH264AccessUnitStats(
            /* trackId= */ 1,
            /* rtpSequenceNumber= */ 12,
            /* rtpTimestamp= */ 1234,
            /* hasSps= */ true,
            /* hasPps= */ true,
            /* nalUnitType= */ 5,
            RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR,
            /* firstRtpPacketElapsedRealtimeMs= */ 88);
    RtspH264AccessUnitReadyStats accessUnitReadyStats =
        new RtspH264AccessUnitReadyStats(
            /* trackId= */ 1,
            /* rtpSequenceNumber= */ 12,
            /* rtpTimestamp= */ 1234,
            /* sampleTimeUs= */ 4567,
            /* isIdr= */ true,
            /* assembledElapsedRealtimeMs= */ 777);
    RtspSampleReadStats sampleReadStats =
        new RtspSampleReadStats(
            /* trackId= */ 1,
            /* sampleQueueIndex= */ 0,
            /* sampleTimeUs= */ 4567,
            /* readElapsedRealtimeMs= */ 888,
            /* sampleQueueBufferedAheadMs= */ 120,
            /* mediaPeriodBufferedAheadMs= */ 180);
    RtspH264RecoveryStats recoveryStats =
        new RtspH264RecoveryStats(
            /* trackId= */ 1,
            /* rtpSequenceNumber= */ 13,
            /* rtpTimestamp= */ 2234,
            /* waitingForIdr= */ true,
            /* corruptedAccessUnitCount= */ 2,
            /* droppedUntilIdrCount= */ 3,
            RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);

    assertThat(packetStats)
        .isEqualTo(
            new RtpPacketStats(
                1, RtspTransportMode.TCP_INTERLEAVED, 96, 10, 1234, 5678, 0x12345678, true));
    assertThat(reorderingStats)
        .isEqualTo(
            new RtpReorderingStats(
                1, RtspTransportMode.TCP_INTERLEAVED, 2, 11, 10, 1, 3, 4, 5));
    assertThat(feedbackRequest)
        .isEqualTo(new RtcpFeedbackRequest(1, RtcpFeedbackReason.SEQUENCE_GAP, 999, "gap"));
    assertThat(accessUnitStats)
        .isEqualTo(
            new RtspH264AccessUnitStats(
                1, 12, 1234, true, true, 5, RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR, 88));
    assertThat(accessUnitReadyStats)
        .isEqualTo(new RtspH264AccessUnitReadyStats(1, 12, 1234, 4567, true, 777));
    assertThat(sampleReadStats).isEqualTo(new RtspSampleReadStats(1, 0, 4567, 888, 120, 180));
    assertThat(recoveryStats)
        .isEqualTo(
            new RtspH264RecoveryStats(
                1, 13, 2234, true, 2, 3, RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED));
    assertThat(packetStats.toString()).contains("sequenceNumber=10");
    assertThat(reorderingStats.toString()).contains("queueDepth=2");
    assertThat(feedbackRequest.toString()).contains("detail=gap");
    assertThat(accessUnitStats.toString()).contains("accessUnitType=IDR");
    assertThat(accessUnitReadyStats.toString()).contains("isIdr=true");
    assertThat(sampleReadStats.toString()).contains("sampleQueueBufferedAheadMs=120");
    assertThat(recoveryStats.toString()).contains("waitingForIdr=true");
  }

  @Test
  public void emptyListeners_allowNoOpCallbacks() {
    RtspDiagnosticsListener diagnosticsListener = new RtspDiagnosticsListener() {};
    RtspFeedbackListener feedbackListener = new RtspFeedbackListener() {};
    RtpPacketStats packetStats =
        new RtpPacketStats(
            1, RtspTransportMode.UDP, 96, 10, 1234, 5678, 0x12345678, /* marker= */ false);
    RtpReorderingStats reorderingStats =
        new RtpReorderingStats(
            1, RtspTransportMode.UDP, 1, 10, 9, 0, 0, 0, 0);
    RtcpFeedbackRequest feedbackRequest =
        new RtcpFeedbackRequest(
            1, RtcpFeedbackReason.APPLICATION, 999, /* detail= */ null);

    diagnosticsListener.onTransportReady(
        /* trackId= */ 1, RtspTransportMode.UDP, "RTP/AVP;unicast;client_port=1000-1001");
    diagnosticsListener.onFirstRtpPacketReceived(packetStats);
    diagnosticsListener.onFirstDecodableVideoAccessUnitReady(
        new RtspH264AccessUnitStats(
            1, 10, 1234, true, true, 5, RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR, 88));
    diagnosticsListener.onH264AccessUnitReady(
        new RtspH264AccessUnitReadyStats(1, 10, 1234, true, 777));
    diagnosticsListener.onRtspSampleRead(new RtspSampleReadStats(1, 0, 1234, 777, 10, 20));
    RtspH264RecoveryStats recoveryStats =
        new RtspH264RecoveryStats(
            1, 10, 1234, true, 1, 1, RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
    diagnosticsListener.onH264AccessUnitCorrupted(recoveryStats);
    diagnosticsListener.onH264WaitForIdrStarted(recoveryStats);
    diagnosticsListener.onH264AccessUnitDroppedUntilIdr(recoveryStats);
    diagnosticsListener.onH264WaitForIdrEnded(recoveryStats);
    diagnosticsListener.onRtpPacketReceived(packetStats);
    diagnosticsListener.onRtpPacketDequeued(packetStats, reorderingStats);
    diagnosticsListener.onRtpPacketDropped(packetStats, reorderingStats);
    diagnosticsListener.onRtpReorderingQueueReset(reorderingStats);
    feedbackListener.onRtcpFeedbackRequested(feedbackRequest);
    feedbackListener.onRtcpFeedbackThrottled(feedbackRequest);
    feedbackListener.onRtcpFeedbackSent(feedbackRequest);
    feedbackListener.onRtcpFeedbackSendFailed(feedbackRequest, new Exception("test"));
  }
}
