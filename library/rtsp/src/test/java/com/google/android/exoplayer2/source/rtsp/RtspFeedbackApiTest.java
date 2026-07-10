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
    assertThat(mediaSource.getRtspBacklogRecoveryPolicy())
        .isEqualTo(RtspBacklogRecoveryPolicy.DISABLED);
    assertThat(mediaSource.getRtspPacketDiagnosticsEnabled()).isFalse();
    assertThat(mediaSource.getRtspTransportStrategy())
        .isEqualTo(RtspTransportStrategy.EXOPLAYER_DEFAULT);
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(UdpDataSourceRtpDataChannelFactory.class);
    assertThat(mediaSource.getRtpDataChannelFactory().createFallbackDataChannelFactory())
        .isInstanceOf(TransferRtpDataChannelFactory.class);
    assertThat(RtcpFeedbackPolicy.DEFAULT.pliEnabled).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.firEnabled).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.sequenceGapRequestThreshold).isEqualTo(0);
    assertThat(RtcpFeedbackPolicy.DEFAULT.requestKeyFrameOnQueueReset).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.feedbackStrategy)
        .isEqualTo(RtcpFeedbackPolicy.RTCP_ONLY);
    assertThat(RtcpFeedbackPolicy.DEFAULT.waitingForIdrTimeoutMs).isEqualTo(0);
    assertThat(RtcpFeedbackPolicy.DEFAULT.isLowLatencyRecoveryEnabled()).isFalse();
    assertThat(RtcpFeedbackPolicy.DEFAULT.canSendRtcpFeedback()).isFalse();
    assertThat(new RtcpFeedbackPolicy.Builder().build()).isEqualTo(RtcpFeedbackPolicy.DEFAULT);
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.isEnabled()).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.isTcpInterleavedBacklogRecoveryEnabled())
        .isFalse();
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.isRtpReorderBacklogRecoveryEnabled()).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.getRtpReorderWaitMs(RtspTransportMode.UDP))
        .isEqualTo(RtspBacklogRecoveryPolicy.DEFAULT_RTP_REORDER_WAIT_MS);
    assertThat(
            RtspBacklogRecoveryPolicy.DISABLED.getRtpReorderWaitMs(
                RtspTransportMode.TCP_INTERLEAVED))
        .isEqualTo(RtspBacklogRecoveryPolicy.DEFAULT_RTP_REORDER_WAIT_MS);
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.isMediaPeriodRecoverySignalEnabled()).isFalse();

    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    assertThat(mediaPeriod.getRtspDiagnosticsListener()).isNull();
    assertThat(mediaPeriod.getRtspFeedbackListener()).isNull();
    assertThat(mediaPeriod.getRtcpFeedbackPolicy()).isEqualTo(RtcpFeedbackPolicy.DEFAULT);
    assertThat(mediaPeriod.getRtspBacklogRecoveryPolicy())
        .isEqualTo(RtspBacklogRecoveryPolicy.DISABLED);
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
    RtspBacklogRecoveryPolicy backlogRecoveryPolicy =
        new RtspBacklogRecoveryPolicy.Builder()
            .setEnabled(true)
            .setTcpInterleavedBacklogResetMs(300)
            .setTcpInterleavedBacklogResetPackets(240)
            .setRtpReorderBacklogResetMs(200)
            .setRtpReorderBacklogResetPackets(240)
            .setTcpInterleavedRtpReorderWaitMs(2)
            .setUdpRtpReorderWaitMs(30)
            .setMediaPeriodRecoverySignalEnabled(true)
            .setWaitForIdrTimeoutMs(800)
            .build();

    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .setRtspFeedbackListener(feedbackListener)
            .setRtcpFeedbackPolicy(feedbackPolicy)
            .setRtspBacklogRecoveryPolicy(backlogRecoveryPolicy)
            .setRtspPacketDiagnosticsEnabled(true)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspDiagnosticsListener()).isSameInstanceAs(diagnosticsListener);
    assertThat(mediaSource.getRtspFeedbackListener()).isSameInstanceAs(feedbackListener);
    assertThat(mediaSource.getRtcpFeedbackPolicy()).isEqualTo(feedbackPolicy);
    assertThat(mediaSource.getRtspBacklogRecoveryPolicy()).isEqualTo(backlogRecoveryPolicy);
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
    assertThat(mediaPeriod.getRtspBacklogRecoveryPolicy()).isEqualTo(backlogRecoveryPolicy);
    assertThat(mediaPeriod.getRtspPacketDiagnosticsEnabled()).isTrue();

    mediaSource.releasePeriod(mediaPeriod);
  }

  @Test
  public void rtspBacklogRecoveryPolicyBuilder_buildsTransportAwarePolicy() {
    RtspBacklogRecoveryPolicy policy =
        new RtspBacklogRecoveryPolicy.Builder()
            .setEnabled(true)
            .setTcpInterleavedRtpReorderWaitMs(1)
            .setUdpRtpReorderWaitMs(25)
            .setMediaPeriodRecoverySignalEnabled(true)
            .build();

    assertThat(policy.getRtpReorderWaitMs(RtspTransportMode.TCP_INTERLEAVED)).isEqualTo(1);
    assertThat(policy.getRtpReorderWaitMs(RtspTransportMode.UDP)).isEqualTo(25);
    assertThat(policy.getRtpReorderWaitMs(RtspTransportMode.UNKNOWN))
        .isEqualTo(RtspBacklogRecoveryPolicy.DEFAULT_RTP_REORDER_WAIT_MS);
    assertThat(policy.isMediaPeriodRecoverySignalEnabled()).isTrue();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.getRtpReorderWaitMs(
            RtspTransportMode.TCP_INTERLEAVED))
        .isEqualTo(2);
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.getRtpReorderWaitMs(RtspTransportMode.UDP))
        .isEqualTo(RtspBacklogRecoveryPolicy.DEFAULT_RTP_REORDER_WAIT_MS);
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.isMediaPeriodRecoverySignalEnabled())
        .isFalse();
  }

  @Test
  public void requestOneShotRtcpFeedback_withoutActivePeriod_returnsFailed() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    RtcpFeedbackResult pliResult =
        mediaSource.requestOneShotRtcpPli(RtcpFeedbackReason.APPLICATION);
    RtcpFeedbackResult firResult =
        mediaSource.requestOneShotRtcpFir(RtcpFeedbackReason.APPLICATION);

    assertThat(pliResult.status).isEqualTo(RtcpFeedbackResult.FAILED);
    assertThat(pliResult.request).isNull();
    assertThat(pliResult.detail).isEqualTo("no active RTSP media period");
    assertThat(firResult.status).isEqualTo(RtcpFeedbackResult.FAILED);
    assertThat(firResult.request).isNull();
  }

  @Test
  public void rtcpFeedbackResult_valueSemantics() {
    RtcpFeedbackRequest request =
        new RtcpFeedbackRequest(
            1,
            RtcpFeedbackType.PLI,
            RtcpFeedbackReason.APPLICATION,
            RtspTransportMode.TCP_INTERLEAVED,
            0x1234,
            0x5678,
            100,
            "test");
    RtcpFeedbackResult result =
        new RtcpFeedbackResult(RtcpFeedbackResult.SCHEDULED, request, 101, "scheduled");

    assertThat(result)
        .isEqualTo(
            new RtcpFeedbackResult(RtcpFeedbackResult.SCHEDULED, request, 101, "scheduled"));
    assertThat(result.hashCode())
        .isEqualTo(
            new RtcpFeedbackResult(RtcpFeedbackResult.SCHEDULED, request, 101, "scheduled")
                .hashCode());
    assertThat(result.toString()).contains("status=1");
  }

  @Test
  public void mediaPeriodRecoveryStats_valueSemantics() {
    RtspMediaPeriodRecoveryStats stats =
        new RtspMediaPeriodRecoveryStats(
            1,
            RtspTransportMode.TCP_INTERLEAVED,
            RtcpFeedbackReason.QUEUE_RESET,
            RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
            123,
            "rtsp_backlog_queue_reset");

    assertThat(stats)
        .isEqualTo(
            new RtspMediaPeriodRecoveryStats(
                1,
                RtspTransportMode.TCP_INTERLEAVED,
                RtcpFeedbackReason.QUEUE_RESET,
                RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
                123,
                "rtsp_backlog_queue_reset"));
    assertThat(stats.hashCode())
        .isEqualTo(
            new RtspMediaPeriodRecoveryStats(
                    1,
                    RtspTransportMode.TCP_INTERLEAVED,
                    RtcpFeedbackReason.QUEUE_RESET,
                    RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
                    123,
                    "rtsp_backlog_queue_reset")
                .hashCode());
    assertThat(stats.toString()).contains("action=1");
  }

  @Test
  public void mediaPeriodRecoverySignal_onlyEmittedForEnabledTcpReset() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtspBacklogRecoveryPolicy policy =
        new RtspBacklogRecoveryPolicy.Builder()
            .setEnabled(true)
            .setMediaPeriodRecoverySignalEnabled(true)
            .build();
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .setRtspBacklogRecoveryPolicy(policy)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));
    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);
    RtspDiagnosticsListener forwardingListener =
        mediaPeriod.getForwardingRtspDiagnosticsListenerForTesting();

    assertThat(forwardingListener).isNotNull();
    forwardingListener.onRtspBacklogQueueReset(
        new RtspBacklogRecoveryStats(
            1,
            RtspTransportMode.TCP_INTERLEAVED,
            RtcpFeedbackReason.QUEUE_RESET,
            12,
            12,
            301,
            301,
            999));
    forwardingListener.onRtspBacklogQueueReset(
        new RtspBacklogRecoveryStats(
            1,
            RtspTransportMode.UDP,
            RtcpFeedbackReason.QUEUE_RESET,
            12,
            12,
            301,
            301,
            999));

    assertThat(diagnosticsListener.mediaPeriodRecoveryStats).hasSize(1);
    RtspMediaPeriodRecoveryStats recoveryStats =
        diagnosticsListener.mediaPeriodRecoveryStats.get(0);
    assertThat(recoveryStats.trackId).isEqualTo(1);
    assertThat(recoveryStats.transportMode).isEqualTo(RtspTransportMode.TCP_INTERLEAVED);
    assertThat(recoveryStats.action)
        .isEqualTo(RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED);

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
  public void factorySetForceUseRtpTcp_preservesLegacyTcpBehavior() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspTransportStrategy()).isEqualTo(RtspTransportStrategy.FORCE_TCP);
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(TransferRtpDataChannelFactory.class);
  }

  @Test
  public void factorySetForceUseRtpTcpFalse_resetsToExoPlayerDefault() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setForceUseRtpTcp(false)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspTransportStrategy())
        .isEqualTo(RtspTransportStrategy.EXOPLAYER_DEFAULT);
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(UdpDataSourceRtpDataChannelFactory.class);
    assertThat(mediaSource.getRtpDataChannelFactory().createFallbackDataChannelFactory())
        .isInstanceOf(TransferRtpDataChannelFactory.class);
  }

  @Test
  public void factorySetRtspTransportStrategyForceUdp_disablesTcpFallback() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspTransportStrategy(RtspTransportStrategy.FORCE_UDP)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspTransportStrategy()).isEqualTo(RtspTransportStrategy.FORCE_UDP);
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(UdpDataSourceRtpDataChannelFactory.class);
    assertThat(mediaSource.getRtpDataChannelFactory().createFallbackDataChannelFactory()).isNull();
  }

  @Test
  public void factorySetRtspTransportStrategyAutoUdpThenTcp_keepsTcpFallback() {
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspTransportStrategy(RtspTransportStrategy.AUTO_UDP_THEN_TCP)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));

    assertThat(mediaSource.getRtspTransportStrategy())
        .isEqualTo(RtspTransportStrategy.AUTO_UDP_THEN_TCP);
    assertThat(mediaSource.getRtpDataChannelFactory())
        .isInstanceOf(UdpDataSourceRtpDataChannelFactory.class);
    assertThat(mediaSource.getRtpDataChannelFactory().createFallbackDataChannelFactory())
        .isInstanceOf(TransferRtpDataChannelFactory.class);
  }

  @Test
  public void rtcpFeedbackPolicyBuilder_buildsExpectedValue() {
    RtcpFeedbackPolicy policy =
        new RtcpFeedbackPolicy.Builder()
            .setMinRequestIntervalMs(123)
            .setPliEnabled(false)
            .setFirEnabled(true)
            .setFeedbackStrategy(RtcpFeedbackPolicy.EXTERNAL_ONLY)
            .setWaitingForIdrTimeoutMs(321)
            .build();

    assertThat(policy.minRequestIntervalMs).isEqualTo(123);
    assertThat(policy.pliEnabled).isFalse();
    assertThat(policy.firEnabled).isTrue();
    assertThat(policy.feedbackStrategy).isEqualTo(RtcpFeedbackPolicy.EXTERNAL_ONLY);
    assertThat(policy.waitingForIdrTimeoutMs).isEqualTo(321);
    assertThat(policy.isLowLatencyRecoveryEnabled()).isTrue();
    assertThat(policy.canSendRtcpFeedback()).isFalse();
    assertThat(policy)
        .isEqualTo(
            new RtcpFeedbackPolicy.Builder()
                .setMinRequestIntervalMs(123)
                .setPliEnabled(false)
                .setFirEnabled(true)
                .setFeedbackStrategy(RtcpFeedbackPolicy.EXTERNAL_ONLY)
                .setWaitingForIdrTimeoutMs(321)
                .build());
    assertThat(policy.toString()).contains("minRequestIntervalMs=123");
    assertThat(policy.toString()).contains("feedbackStrategy=1");
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
    assertThat(policy.feedbackStrategy).isEqualTo(RtcpFeedbackPolicy.BOTH);
    assertThat(policy.waitingForIdrTimeoutMs)
        .isEqualTo(RtcpFeedbackPolicy.DEFAULT_WAITING_FOR_IDR_TIMEOUT_MS);
    assertThat(policy.isLowLatencyRecoveryEnabled()).isTrue();
    assertThat(policy.canSendRtcpFeedback()).isTrue();
    assertThat(new RtcpFeedbackPolicy.Builder().setLowLatencyDefaults().build()).isEqualTo(policy);
  }

  @Test
  public void rtspBacklogRecoveryPolicyBuilder_buildsCastSdkBridgeShape() {
    RtspBacklogRecoveryPolicy policy =
        new RtspBacklogRecoveryPolicy.Builder()
            .setEnabled(true)
            .setTcpInterleavedBacklogWarnMs(150)
            .setTcpInterleavedBacklogResetMs(300)
            .setTcpInterleavedBacklogResetPackets(240)
            .setRtpReorderBacklogWarnMs(100)
            .setRtpReorderBacklogResetMs(200)
            .setRtpReorderBacklogResetPackets(240)
            .setTcpInterleavedRtpReorderWaitMs(2)
            .setUdpRtpReorderWaitMs(30)
            .setMediaPeriodRecoverySignalEnabled(true)
            .setWaitForIdrTimeoutMs(800)
            .setInitialWaitForIdr(true)
            .setInitialWaitForIdrAfterSeek(true)
            .build();

    assertThat(policy.enabled).isTrue();
    assertThat(policy.tcpInterleavedBacklogWarnMs).isEqualTo(150);
    assertThat(policy.tcpInterleavedBacklogResetMs).isEqualTo(300);
    assertThat(policy.tcpInterleavedBacklogResetPackets).isEqualTo(240);
    assertThat(policy.rtpReorderBacklogWarnMs).isEqualTo(100);
    assertThat(policy.rtpReorderBacklogResetMs).isEqualTo(200);
    assertThat(policy.rtpReorderBacklogResetPackets).isEqualTo(240);
    assertThat(policy.tcpInterleavedRtpReorderWaitMs).isEqualTo(2);
    assertThat(policy.udpRtpReorderWaitMs).isEqualTo(30);
    assertThat(policy.mediaPeriodRecoverySignalEnabled).isTrue();
    assertThat(policy.waitForIdrTimeoutMs).isEqualTo(800);
    assertThat(policy.initialWaitForIdr).isTrue();
    assertThat(policy.initialWaitForIdrAfterSeek).isTrue();
    assertThat(policy.isTcpInterleavedBacklogRecoveryEnabled()).isTrue();
    assertThat(policy.isRtpReorderBacklogRecoveryEnabled()).isTrue();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.initialWaitForIdr).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.initialWaitForIdrAfterSeek).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY.isMediaPeriodRecoverySignalEnabled())
        .isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY_DEFAULT.initialWaitForIdr).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY_DEFAULT.initialWaitForIdrAfterSeek).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY_DEFAULT.isMediaPeriodRecoverySignalEnabled())
        .isFalse();
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.initialWaitForIdr).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.DISABLED.initialWaitForIdrAfterSeek).isFalse();
    assertThat(RtspBacklogRecoveryPolicy.LOW_LATENCY)
        .isEqualTo(
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setTcpInterleavedBacklogWarnMs(150)
                .setTcpInterleavedBacklogResetMs(300)
                .setTcpInterleavedBacklogResetPackets(240)
                .setRtpReorderBacklogWarnMs(100)
                .setRtpReorderBacklogResetMs(200)
                .setRtpReorderBacklogResetPackets(240)
                .setTcpInterleavedRtpReorderWaitMs(2)
                .setUdpRtpReorderWaitMs(30)
                .setWaitForIdrTimeoutMs(800)
                .build());
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
            /* rtpTimestamp= */ 1234,
            /* readElapsedRealtimeMs= */ 888,
            /* sampleQueueBufferedAheadMs= */ 120,
            /* mediaPeriodBufferedAheadMs= */ 180);
    RtspDecoderInputQueuedStats decoderInputQueuedStats =
        new RtspDecoderInputQueuedStats(
            /* trackId= */ 1,
            /* sampleQueueIndex= */ 0,
            /* sampleTimeUs= */ 4567,
            /* rtpTimestamp= */ 1234,
            /* queuedElapsedRealtimeMs= */ 889);
    RtspH264RecoveryStats recoveryStats =
        new RtspH264RecoveryStats(
            /* trackId= */ 1,
            /* rtpSequenceNumber= */ 13,
            /* rtpTimestamp= */ 2234,
            /* waitingForIdr= */ true,
            /* corruptedAccessUnitCount= */ 2,
            /* droppedUntilIdrCount= */ 3,
            RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED,
            /* waitingForIdrDurationMs= */ 44,
            /* lastRtpSequence= */ 14,
            /* lastRtpTimestamp= */ 3234,
            /* idrRecoveredCount= */ 1);
    RtspTransportFallbackStats transportFallbackStats =
        new RtspTransportFallbackStats(
            /* trackId= */ 1,
            RtspTransportFallbackReason.UDP_NO_SAMPLE,
            RtspTransportMode.UDP,
            RtspTransportMode.TCP_INTERLEAVED,
            /* fallbackElapsedRealtimeMs= */ 123);
    RtcpSenderReportStats senderReportStats =
        new RtcpSenderReportStats(
            /* trackId= */ 1,
            /* ssrc= */ 0x12345678L,
            /* rtpTimestamp= */ 0xFFFF_FFFEL,
            /* ntpTimeMs= */ 2500,
            /* rawNtpSeconds= */ 2,
            /* rawNtpFraction= */ 0x8000_0000L,
            /* receivedElapsedRealtimeMs= */ 456,
            RtspTransportMode.TCP_INTERLEAVED,
            /* clockRate= */ 90_000,
            /* packetCount= */ 3,
            /* octetCount= */ 4);

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
    assertThat(sampleReadStats)
        .isEqualTo(new RtspSampleReadStats(1, 0, 4567, 1234, 888, 120, 180));
    assertThat(new RtspSampleReadStats(1, 0, 4567, 888, 120, 180).rtpTimestamp)
        .isEqualTo(C.TIME_UNSET);
    assertThat(decoderInputQueuedStats)
        .isEqualTo(new RtspDecoderInputQueuedStats(1, 0, 4567, 1234, 889));
    assertThat(recoveryStats)
        .isEqualTo(
            new RtspH264RecoveryStats(
                1, 13, 2234, true, 2, 3, RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED, 44, 14,
                3234, 1));
    assertThat(transportFallbackStats)
        .isEqualTo(
            new RtspTransportFallbackStats(
                1,
                RtspTransportFallbackReason.UDP_NO_SAMPLE,
                RtspTransportMode.UDP,
                RtspTransportMode.TCP_INTERLEAVED,
                123));
    assertThat(senderReportStats)
        .isEqualTo(
            new RtcpSenderReportStats(
                1,
                0x12345678L,
                0xFFFF_FFFEL,
                2500,
                2,
                0x8000_0000L,
                456,
                RtspTransportMode.TCP_INTERLEAVED,
                90_000,
                3,
                4));
    assertThat(packetStats.toString()).contains("sequenceNumber=10");
    assertThat(reorderingStats.toString()).contains("queueDepth=2");
    assertThat(feedbackRequest.toString()).contains("detail=gap");
    assertThat(accessUnitStats.toString()).contains("accessUnitType=IDR");
    assertThat(accessUnitReadyStats.toString()).contains("isIdr=true");
    assertThat(sampleReadStats.toString()).contains("rtpTimestamp=1234");
    assertThat(sampleReadStats.toString()).contains("sampleQueueBufferedAheadMs=120");
    assertThat(decoderInputQueuedStats.toString()).contains("queuedElapsedRealtimeMs=889");
    assertThat(recoveryStats.toString()).contains("waitingForIdr=true");
    assertThat(recoveryStats.toString()).contains("waitingForIdrDurationMs=44");
    assertThat(transportFallbackStats.toString()).contains("reason=2");
    assertThat(senderReportStats.toString()).contains("rtpTimestamp=4294967294");
  }

  @Test
  public void h264AccessUnitReadyDiagnostics_recordsRtpTimestampForSampleJoin() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .setRtspPacketDiagnosticsEnabled(true)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));
    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    mediaPeriod.onH264AccessUnitReadyForDiagnostics(
        new RtspH264AccessUnitReadyStats(
            /* trackId= */ 1,
            /* rtpSequenceNumber= */ 10,
            /* rtpTimestamp= */ 1234,
            /* sampleTimeUs= */ 4567,
            /* isIdr= */ true,
            /* assembledElapsedRealtimeMs= */ 777));

    assertThat(diagnosticsListener.accessUnitReadyStats).hasSize(1);
    assertThat(mediaPeriod.removeSampleRtpTimestampForDiagnostics(1, 4567)).isEqualTo(1234);
    assertThat(mediaPeriod.removeSampleRtpTimestampForDiagnostics(1, 4567))
        .isEqualTo(C.TIME_UNSET);

    mediaSource.releasePeriod(mediaPeriod);
  }

  @Test
  public void h264AccessUnitReadyDiagnostics_packetDiagnosticsDisabledDoesNotRecordJoinState() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .setRtspPacketDiagnosticsEnabled(false)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));
    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    mediaPeriod.onH264AccessUnitReadyForDiagnostics(
        new RtspH264AccessUnitReadyStats(1, 10, 1234, 4567, /* isIdr= */ true, 777));

    assertThat(diagnosticsListener.accessUnitReadyStats).hasSize(1);
    assertThat(mediaPeriod.removeSampleRtpTimestampForDiagnostics(1, 4567))
        .isEqualTo(C.TIME_UNSET);

    mediaSource.releasePeriod(mediaPeriod);
  }

  @Test
  public void transportFallbackDiagnostics_dispatchesLowFrequencyStats() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtspMediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setRtspDiagnosticsListener(diagnosticsListener)
            .createMediaSource(MediaItem.fromUri("rtsp://127.0.0.1/test"));
    RtspMediaPeriod mediaPeriod =
        (RtspMediaPeriod)
            mediaSource.createPeriod(
                new MediaPeriodId(/* periodUid= */ new Object()),
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                /* startPositionUs= */ 0);

    mediaPeriod.onTransportFallbackForDiagnostics(
        RtspTransportFallbackReason.UDP_UNSUPPORTED,
        /* trackId= */ C.INDEX_UNSET,
        RtspTransportMode.UDP,
        RtspTransportMode.TCP_INTERLEAVED);

    assertThat(diagnosticsListener.transportFallbackStats).hasSize(1);
    RtspTransportFallbackStats fallbackStats = diagnosticsListener.transportFallbackStats.get(0);
    assertThat(fallbackStats.reason).isEqualTo(RtspTransportFallbackReason.UDP_UNSUPPORTED);
    assertThat(fallbackStats.fromTransportMode).isEqualTo(RtspTransportMode.UDP);
    assertThat(fallbackStats.toTransportMode).isEqualTo(RtspTransportMode.TCP_INTERLEAVED);
    assertThat(fallbackStats.fallbackElapsedRealtimeMs).isAtLeast(0);

    mediaSource.releasePeriod(mediaPeriod);
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
    diagnosticsListener.onTransportFallback(
        new RtspTransportFallbackStats(
            1,
            RtspTransportFallbackReason.UDP_NO_SAMPLE,
            RtspTransportMode.UDP,
            RtspTransportMode.TCP_INTERLEAVED,
            123));
    diagnosticsListener.onFirstRtpPacketReceived(packetStats);
    diagnosticsListener.onFirstDecodableVideoAccessUnitReady(
        new RtspH264AccessUnitStats(
            1, 10, 1234, true, true, 5, RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR, 88));
    diagnosticsListener.onH264AccessUnitReady(
        new RtspH264AccessUnitReadyStats(1, 10, 1234, true, 777));
    diagnosticsListener.onRtspSampleRead(new RtspSampleReadStats(1, 0, 1234, 777, 10, 20));
    diagnosticsListener.onRtspDecoderInputQueued(
        new RtspDecoderInputQueuedStats(1, 0, 1234, 5678, 777));
    RtspH264RecoveryStats recoveryStats =
        new RtspH264RecoveryStats(
            1, 10, 1234, true, 1, 1, RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
    diagnosticsListener.onH264AccessUnitCorrupted(recoveryStats);
    diagnosticsListener.onH264WaitForIdrStarted(recoveryStats);
    diagnosticsListener.onH264AccessUnitDroppedUntilIdr(recoveryStats);
    diagnosticsListener.onH264WaitForIdrTimedOut(recoveryStats);
    diagnosticsListener.onH264WaitForIdrEnded(recoveryStats);
    diagnosticsListener.onRtpPacketReceived(packetStats);
    diagnosticsListener.onRtpPacketDequeued(packetStats, reorderingStats);
    diagnosticsListener.onRtpPacketDropped(packetStats, reorderingStats);
    diagnosticsListener.onRtpReorderingQueueReset(reorderingStats);
    diagnosticsListener.onRtspMediaPeriodRecoveryRequired(
        new RtspMediaPeriodRecoveryStats(
            1,
            RtspTransportMode.TCP_INTERLEAVED,
            RtcpFeedbackReason.QUEUE_RESET,
            RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
            999,
            "test"));
    diagnosticsListener.onRtcpSenderReport(
        new RtcpSenderReportStats(1, 0x12345678L, 1234, 2500, 2, 0, 999,
            RtspTransportMode.UDP, 90_000, 3, 4));
    feedbackListener.onRtcpFeedbackRequested(feedbackRequest);
    feedbackListener.onRtcpFeedbackThrottled(feedbackRequest);
    feedbackListener.onRtcpFeedbackSent(feedbackRequest);
    feedbackListener.onRtcpFeedbackSendFailed(feedbackRequest, new Exception("test"));
  }

  private static final class CapturingDiagnosticsListener implements RtspDiagnosticsListener {
    public final java.util.ArrayList<RtspH264AccessUnitReadyStats> accessUnitReadyStats =
        new java.util.ArrayList<>();
    public final java.util.ArrayList<RtspTransportFallbackStats> transportFallbackStats =
        new java.util.ArrayList<>();
    public final java.util.ArrayList<RtspMediaPeriodRecoveryStats> mediaPeriodRecoveryStats =
        new java.util.ArrayList<>();

    @Override
    public void onTransportFallback(RtspTransportFallbackStats fallbackStats) {
      transportFallbackStats.add(fallbackStats);
    }

    @Override
    public void onH264AccessUnitReady(RtspH264AccessUnitReadyStats accessUnitStats) {
      accessUnitReadyStats.add(accessUnitStats);
    }

    @Override
    public void onRtspMediaPeriodRecoveryRequired(
        RtspMediaPeriodRecoveryStats mediaPeriodRecoveryStats) {
      this.mediaPeriodRecoveryStats.add(mediaPeriodRecoveryStats);
    }
  }
}
