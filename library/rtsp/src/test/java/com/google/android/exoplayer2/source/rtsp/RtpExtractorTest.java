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

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener, boolean rtspPacketDiagnosticsEnabled) {
    return createExtractor(
        diagnosticsListener, RtcpFeedbackPolicy.DEFAULT, rtspPacketDiagnosticsEnabled);
  }

  private static RtpExtractor createExtractor(
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      boolean rtspPacketDiagnosticsEnabled) {
    return new RtpExtractor(
        new RtpPayloadFormat(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            /* rtpPayloadType= */ 96,
            /* clockRate= */ 90_000,
            /* fmtpParameters= */ ImmutableMap.of(),
            RtpPayloadFormat.RTP_MEDIA_H264),
        /* trackId= */ 1,
        RtspTransportMode.TCP_INTERLEAVED,
        diagnosticsListener,
        /* rtcpFeedbackRequester= */ null,
        rtcpFeedbackPolicy,
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

  private static byte[] createRtpPacketBytes(int sequenceNumber, long timestamp, byte[] payloadData) {
    RtpPacket packet =
        new RtpPacket.Builder()
            .setMarker(true)
            .setPayloadType((byte) 96)
            .setSequenceNumber(sequenceNumber)
            .setTimestamp(timestamp)
            .setSsrc(0x12345678)
            .setPayloadData(payloadData)
            .build();
    byte[] packetBytes = new byte[12 + payloadData.length];
    assertThat(packet.writeToBuffer(packetBytes, /* offset= */ 0, packetBytes.length))
        .isEqualTo(packetBytes.length);
    return packetBytes;
  }

  private static final class CapturingDiagnosticsListener implements RtspDiagnosticsListener {
    public int firstPacketCount;
    public int packetReceivedCount;
    public int packetDequeuedCount;
    public int packetDroppedCount;
    public int waitForIdrStartedCount;

    @Override
    public void onFirstRtpPacketReceived(RtpPacketStats packetStats) {
      firstPacketCount++;
    }

    @Override
    public void onRtpPacketReceived(RtpPacketStats packetStats) {
      packetReceivedCount++;
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
