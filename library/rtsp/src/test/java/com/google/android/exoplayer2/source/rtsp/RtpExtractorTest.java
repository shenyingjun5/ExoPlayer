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
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RtpExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class RtpExtractorTest {

  @Test
  public void read_withListenerAndPacketDiagnosticsDisabled_emitsOnlyLowFrequencyFirstPacket()
      throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(diagnosticsListener, /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.init(createExtractorOutput());
    extractor.read(
        new FakeExtractorInput.Builder().setData(createRtpPacketBytes()).build(),
        new PositionHolder());

    assertThat(diagnosticsListener.firstPacketCount).isEqualTo(1);
    assertThat(diagnosticsListener.packetReceivedCount).isEqualTo(0);
    assertThat(diagnosticsListener.packetDequeuedCount).isEqualTo(0);
    assertThat(diagnosticsListener.packetDroppedCount).isEqualTo(0);
    assertThat(diagnosticsListener.rtpTrackActivityStats).isEmpty();
  }

  @Test
  public void read_lowLatencyVideoAndPacketDiagnosticsDisabled_emitsTrackActivity()
      throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            new RtspBacklogRecoveryPolicy.Builder().setEnabled(true).build(),
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.init(createExtractorOutput());
    extractor.read(
        new FakeExtractorInput.Builder().setData(createRtpPacketBytes()).build(),
        new PositionHolder());

    assertThat(diagnosticsListener.packetReceivedCount).isEqualTo(0);
    assertThat(diagnosticsListener.rtpTrackActivityStats).hasSize(1);
    RtspRtpTrackActivityStats activityStats = diagnosticsListener.rtpTrackActivityStats.get(0);
    assertThat(activityStats.trackId).isEqualTo(1);
    assertThat(activityStats.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(activityStats.transportMode).isEqualTo(RtspTransportMode.TCP_INTERLEAVED);
    assertThat(activityStats.receivedPacketCount).isEqualTo(1);
    assertThat(activityStats.lastSequenceNumber).isEqualTo(10);
    assertThat(activityStats.lastRtpTimestamp).isEqualTo(1000);
  }

  @Test
  public void rtpTrackActivity_throttlesCallbacksAndKeepsPrimitivePacketCount() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setRtpActivityNotificationIntervalMs(500)
                .build(),
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.maybeNotifyRtpTrackActivityForTesting(
        createRtpPacket(/* sequenceNumber= */ 10, /* timestamp= */ 1000),
        /* packetArrivalTimeMs= */ 1_000);
    extractor.maybeNotifyRtpTrackActivityForTesting(
        createRtpPacket(/* sequenceNumber= */ 11, /* timestamp= */ 2000),
        /* packetArrivalTimeMs= */ 1_250);
    extractor.maybeNotifyRtpTrackActivityForTesting(
        createRtpPacket(/* sequenceNumber= */ 12, /* timestamp= */ 3000),
        /* packetArrivalTimeMs= */ 1_500);

    assertThat(diagnosticsListener.rtpTrackActivityStats).hasSize(2);
    assertThat(diagnosticsListener.rtpTrackActivityStats.get(0).receivedPacketCount).isEqualTo(1);
    RtspRtpTrackActivityStats latestStats = diagnosticsListener.rtpTrackActivityStats.get(1);
    assertThat(latestStats.receivedPacketCount).isEqualTo(3);
    assertThat(latestStats.lastPacketArrivalElapsedRealtimeMs).isEqualTo(1_500);
    assertThat(latestStats.lastSequenceNumber).isEqualTo(12);
  }

  @Test
  public void rtpTrackActivity_audioTrackDoesNotRefreshVideoActivity() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractorForMimeType(
            diagnosticsListener,
            new RtspBacklogRecoveryPolicy.Builder().setEnabled(true).build(),
            MimeTypes.AUDIO_ALAW);

    extractor.maybeNotifyRtpTrackActivityForTesting(
        createRtpPacket(/* sequenceNumber= */ 10, /* timestamp= */ 1000),
        /* packetArrivalTimeMs= */ 1_000);

    assertThat(diagnosticsListener.rtpTrackActivityStats).isEmpty();
  }

  @Test
  public void rtpTrackActivity_explicitZeroIntervalDisablesCallbacks() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setRtpActivityNotificationIntervalMs(0)
                .build(),
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.maybeNotifyRtpTrackActivityForTesting(
        createRtpPacket(/* sequenceNumber= */ 10, /* timestamp= */ 1000),
        /* packetArrivalTimeMs= */ 1_000);

    assertThat(diagnosticsListener.rtpTrackActivityStats).isEmpty();
  }

  @Test
  public void read_withListenerAndPacketDiagnosticsEnabled_emitsPacketCallbacks()
      throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(diagnosticsListener, /* rtspPacketDiagnosticsEnabled= */ true);

    extractor.init(createExtractorOutput());
    extractor.read(
        new FakeExtractorInput.Builder().setData(createRtpPacketBytes()).build(),
        new PositionHolder());

    assertThat(diagnosticsListener.firstPacketCount).isEqualTo(1);
    assertThat(diagnosticsListener.packetReceivedCount).isEqualTo(1);
    assertThat(diagnosticsListener.packetDequeuedCount).isEqualTo(1);
    assertThat(diagnosticsListener.packetDroppedCount).isEqualTo(0);
  }

  @Test
  public void read_externalOnlySequenceGapNotifiesPayloadReaderDiscontinuity() throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            new RtcpFeedbackPolicy.Builder()
                .setFeedbackStrategy(RtcpFeedbackPolicy.EXTERNAL_ONLY)
                .setSequenceGapRequestThreshold(1)
                .build(),
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.init(createExtractorOutput());
    extractor.read(
        new FakeExtractorInput.Builder()
            .setData(
                createRtpPacketBytes(
                    /* sequenceNumber= */ 10,
                    /* timestamp= */ 1000,
                    /* payloadData= */ new byte[] {0x41, 0x01, 0x02}))
            .build(),
        new PositionHolder());
    extractor.read(
        new FakeExtractorInput.Builder()
            .setData(
                createRtpPacketBytes(
                    /* sequenceNumber= */ 12,
                    /* timestamp= */ 2000,
                    /* payloadData= */ new byte[] {0x41, 0x03, 0x04}))
            .build(),
        new PositionHolder());

    assertThat(diagnosticsListener.waitForIdrStartedCount).isEqualTo(1);
    assertThat(diagnosticsListener.packetReceivedCount).isEqualTo(0);
  }

  @Test
  public void dataChannelQueueResetWithBacklogPolicyEnabled_entersWaitForIdr() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setTcpInterleavedBacklogResetPackets(2)
                .build(),
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.init(createExtractorOutput());
    extractor.onRtpStreamDiscontinuity(RtcpFeedbackReason.QUEUE_RESET);

    assertThat(diagnosticsListener.waitForIdrStartedCount).isEqualTo(1);
  }

  @Test
  public void dataChannelQueueResetWithBacklogPolicyDisabled_doesNotEnterWaitForIdr() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            RtspBacklogRecoveryPolicy.DISABLED,
            /* rtspPacketDiagnosticsEnabled= */ false);

    extractor.init(createExtractorOutput());
    extractor.onRtpStreamDiscontinuity(RtcpFeedbackReason.QUEUE_RESET);

    assertThat(diagnosticsListener.waitForIdrStartedCount).isEqualTo(0);
  }

  @Test
  public void initialWaitForIdrBacklogPolicyEnabled_dropsFirstPFrame() throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpExtractor extractor =
        createExtractor(
            diagnosticsListener,
            RtcpFeedbackPolicy.DEFAULT,
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setInitialWaitForIdr(true)
                .build(),
            /* rtspPacketDiagnosticsEnabled= */ false);
    FakeExtractorOutput extractorOutput = createExtractorOutput();

    extractor.init(extractorOutput);
    extractor.read(
        new FakeExtractorInput.Builder()
            .setData(
                createRtpPacketBytes(
                    /* sequenceNumber= */ 10,
                    /* timestamp= */ 1000,
                    /* payloadData= */ new byte[] {0x41, 0x01, 0x02}))
            .build(),
        new PositionHolder());

    assertThat(diagnosticsListener.waitForIdrStartedCount).isEqualTo(1);
    assertThat(extractorOutput.trackOutputs.get(1).getSampleCount()).isEqualTo(0);
  }

  @Test
  public void defaultBacklogPolicy_usesOriginalRtpReorderWait() {
    RtpExtractor extractor =
        createExtractor(
            new CapturingDiagnosticsListener(),
            RtcpFeedbackPolicy.DEFAULT,
            RtspBacklogRecoveryPolicy.DISABLED,
            RtspTransportMode.TCP_INTERLEAVED,
            /* rtspPacketDiagnosticsEnabled= */ false);

    assertThat(extractor.getRtpReorderWaitMsForTesting())
        .isEqualTo(RtspBacklogRecoveryPolicy.DEFAULT_RTP_REORDER_WAIT_MS);
  }

  @Test
  public void lowLatencyBacklogPolicy_usesTransportAwareRtpReorderWait() {
    RtspBacklogRecoveryPolicy policy =
        new RtspBacklogRecoveryPolicy.Builder()
            .setEnabled(true)
            .setTcpInterleavedRtpReorderWaitMs(1)
            .setUdpRtpReorderWaitMs(25)
            .build();

    RtpExtractor tcpExtractor =
        createExtractor(
            new CapturingDiagnosticsListener(),
            RtcpFeedbackPolicy.DEFAULT,
            policy,
            RtspTransportMode.TCP_INTERLEAVED,
            /* rtspPacketDiagnosticsEnabled= */ false);
    RtpExtractor udpExtractor =
        createExtractor(
            new CapturingDiagnosticsListener(),
            RtcpFeedbackPolicy.DEFAULT,
            policy,
            RtspTransportMode.UDP,
            /* rtspPacketDiagnosticsEnabled= */ false);

    assertThat(tcpExtractor.getRtpReorderWaitMsForTesting()).isEqualTo(1);
    assertThat(udpExtractor.getRtpReorderWaitMsForTesting()).isEqualTo(25);
  }

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener, boolean rtspPacketDiagnosticsEnabled) {
    return createExtractor(
        diagnosticsListener, RtcpFeedbackPolicy.DEFAULT, rtspPacketDiagnosticsEnabled);
  }

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      boolean rtspPacketDiagnosticsEnabled) {
    return createExtractor(
        diagnosticsListener,
        rtcpFeedbackPolicy,
        RtspBacklogRecoveryPolicy.DISABLED,
        RtspTransportMode.TCP_INTERLEAVED,
        rtspPacketDiagnosticsEnabled);
  }

  private static RtpExtractor createExtractorForMimeType(
      RtspDiagnosticsListener diagnosticsListener,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy,
      String sampleMimeType) {
    boolean isAudio = MimeTypes.isAudio(sampleMimeType);
    Format.Builder formatBuilder = new Format.Builder().setSampleMimeType(sampleMimeType);
    if (isAudio) {
      formatBuilder.setSampleRate(8_000).setChannelCount(1);
    }
    return new RtpExtractor(
        new RtpPayloadFormat(
            formatBuilder.build(),
            /* rtpPayloadType= */ isAudio ? 8 : 96,
            /* clockRate= */ isAudio ? 8_000 : 90_000,
            /* fmtpParameters= */ ImmutableMap.of(),
            isAudio ? "PCMA" : RtpPayloadFormat.RTP_MEDIA_H264),
        /* trackId= */ isAudio ? 2 : 1,
        RtspTransportMode.TCP_INTERLEAVED,
        diagnosticsListener,
        /* rtcpFeedbackRequester= */ null,
        RtcpFeedbackPolicy.DEFAULT,
        rtspBacklogRecoveryPolicy,
        /* rtspPacketDiagnosticsEnabled= */ false);
  }

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy,
      boolean rtspPacketDiagnosticsEnabled) {
    return createExtractor(
        diagnosticsListener,
        rtcpFeedbackPolicy,
        rtspBacklogRecoveryPolicy,
        RtspTransportMode.TCP_INTERLEAVED,
        rtspPacketDiagnosticsEnabled);
  }

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy,
      @RtspTransportMode.Mode int transportMode,
      boolean rtspPacketDiagnosticsEnabled) {
    return new RtpExtractor(
        new RtpPayloadFormat(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            /* rtpPayloadType= */ 96,
            /* clockRate= */ 90_000,
            /* fmtpParameters= */ ImmutableMap.of(),
            RtpPayloadFormat.RTP_MEDIA_H264),
        /* trackId= */ 1,
        transportMode,
        diagnosticsListener,
        /* rtcpFeedbackRequester= */ null,
        rtcpFeedbackPolicy,
        rtspBacklogRecoveryPolicy,
        rtspPacketDiagnosticsEnabled);
  }

  private static FakeExtractorOutput createExtractorOutput() {
    return new FakeExtractorOutput(
        (id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
  }

  private static byte[] createRtpPacketBytes() {
    return createRtpPacketBytes(
        /* sequenceNumber= */ 10,
        /* timestamp= */ 1000,
        /* payloadData= */ new byte[] {0x65, 0x01, 0x02});
  }

  private static byte[] createRtpPacketBytes(
      int sequenceNumber, long timestamp, byte[] payloadData) {
    RtpPacket packet = createRtpPacket(sequenceNumber, timestamp, payloadData);
    byte[] packetBytes = new byte[12 + payloadData.length];
    assertThat(packet.writeToBuffer(packetBytes, /* offset= */ 0, packetBytes.length))
        .isEqualTo(packetBytes.length);
    return packetBytes;
  }

  private static RtpPacket createRtpPacket(int sequenceNumber, long timestamp) {
    return createRtpPacket(sequenceNumber, timestamp, new byte[] {0x65, 0x01, 0x02});
  }

  private static RtpPacket createRtpPacket(
      int sequenceNumber, long timestamp, byte[] payloadData) {
    return new RtpPacket.Builder()
        .setMarker(true)
        .setPayloadType((byte) 96)
        .setSequenceNumber(sequenceNumber)
        .setTimestamp(timestamp)
        .setSsrc(0x12345678)
        .setPayloadData(payloadData)
        .build();
  }

  private static final class CapturingDiagnosticsListener implements RtspDiagnosticsListener {
    public int firstPacketCount;
    public int packetReceivedCount;
    public int packetDequeuedCount;
    public int packetDroppedCount;
    public int waitForIdrStartedCount;
    public final java.util.ArrayList<RtspRtpTrackActivityStats> rtpTrackActivityStats =
        new java.util.ArrayList<>();

    @Override
    public void onFirstRtpPacketReceived(RtpPacketStats packetStats) {
      firstPacketCount++;
    }

    @Override
    public void onRtpPacketReceived(RtpPacketStats packetStats) {
      packetReceivedCount++;
    }

    @Override
    public void onRtspRtpTrackActivity(RtspRtpTrackActivityStats activityStats) {
      rtpTrackActivityStats.add(activityStats);
    }

    @Override
    public void onRtpPacketDequeued(
        RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {
      packetDequeuedCount++;
    }

    @Override
    public void onRtpPacketDropped(RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {
      packetDroppedCount++;
    }

    @Override
    public void onH264WaitForIdrStarted(RtspH264RecoveryStats recoveryStats) {
      waitForIdrStartedCount++;
    }
  }
}
