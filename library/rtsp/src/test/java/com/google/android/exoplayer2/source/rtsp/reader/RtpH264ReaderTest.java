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
package com.google.android.exoplayer2.source.rtsp.reader;

import static com.google.android.exoplayer2.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.rtsp.RtpPacket;
import com.google.android.exoplayer2.source.rtsp.RtpPayloadFormat;
import com.google.android.exoplayer2.source.rtsp.RtcpFeedbackReason;
import com.google.android.exoplayer2.source.rtsp.RtcpFeedbackRequester;
import com.google.android.exoplayer2.source.rtsp.RtspDiagnosticsListener;
import com.google.android.exoplayer2.source.rtsp.RtspH264AccessUnitStats;
import com.google.android.exoplayer2.source.rtsp.RtspH264AccessUnitReadyStats;
import com.google.android.exoplayer2.source.rtsp.RtspH264RecoveryStats;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Bytes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link RtpH264Reader}. */
@RunWith(AndroidJUnit4.class)
public final class RtpH264ReaderTest {

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final long RTP_TIMESTAMP_1 = 1_000;
  private static final long RTP_TIMESTAMP_2 = 91_000;
  private static final long FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS = 1234;

  private FakeExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    extractorOutput =
        new FakeExtractorOutput(
            (id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
  }

  @Test
  public void consume_singlePacketIdrWithSpsPps_reportsFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ true, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 7);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 10,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 10,
            /* marker= */ true,
            getBytesFromHexString("650506")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 11,
            /* marker= */ true,
            getBytesFromHexString("650708")));

    assertThat(diagnosticsListener.accessUnitStats).hasSize(1);
    assertThat(diagnosticsListener.accessUnitReadyStats).hasSize(2);
    assertThat(diagnosticsListener.accessUnitReadyStats.get(0).isIdr).isTrue();
    assertThat(diagnosticsListener.accessUnitReadyStats.get(0).sampleTimeUs).isEqualTo(0);
    assertThat(diagnosticsListener.accessUnitReadyStats.get(1).sampleTimeUs)
        .isEqualTo(C.MICROS_PER_SECOND);
    RtspH264AccessUnitStats stats = diagnosticsListener.accessUnitStats.get(0);
    assertThat(stats.trackId).isEqualTo(7);
    assertThat(stats.rtpSequenceNumber).isEqualTo(10);
    assertThat(stats.rtpTimestamp).isEqualTo(RTP_TIMESTAMP_1);
    assertThat(stats.sampleTimeUs).isEqualTo(0);
    assertThat(stats.hasSps).isTrue();
    assertThat(stats.hasPps).isTrue();
    assertThat(stats.nalUnitType).isEqualTo(5);
    assertThat(stats.accessUnitType).isEqualTo(RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR);
    assertThat(stats.firstRtpPacketElapsedRealtimeMs).isAtLeast(0);
  }

  @Test
  public void consume_accessUnitReadyStats_handlesRtpTimestampWraparound() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ true, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 7);
    consume(
        h264Reader,
        createPacket(
            0xFFFF_FF00L,
            /* sequenceNumber= */ 10,
            /* marker= */ true,
            getBytesFromHexString("650506")));
    consume(
        h264Reader,
        createPacket(
            100,
            /* sequenceNumber= */ 11,
            /* marker= */ true,
            getBytesFromHexString("650708")));

    assertThat(diagnosticsListener.accessUnitReadyStats).hasSize(2);
    assertThat(diagnosticsListener.accessUnitReadyStats.get(0).sampleTimeUs).isEqualTo(0);
    assertThat(diagnosticsListener.accessUnitReadyStats.get(1).sampleTimeUs)
        .isEqualTo(RtpReaderUtils.toSampleTimeUs(0, 100, 0xFFFF_FF00L, 90_000));
  }

  @Test
  public void consume_accessUnitDiagnosticsDisabled_doesNotReportAccessUnitReady()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            /* rtcpFeedbackRequester= */ null,
            /* lowLatencyRecoveryEnabled= */ false,
            /* accessUnitDiagnosticsEnabled= */ false);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 7);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 10,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 10,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    assertThat(diagnosticsListener.accessUnitStats).hasSize(1);
    assertThat(diagnosticsListener.accessUnitReadyStats).isEmpty();
  }

  @Test
  public void consume_fragmentedIdrWithSpsPps_reportsFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ true, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 3);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 20,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 20,
            /* marker= */ false,
            getBytesFromHexString("7C851122")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 21,
            /* marker= */ true,
            getBytesFromHexString("7C453344")));

    assertThat(diagnosticsListener.accessUnitStats).hasSize(1);
    assertThat(diagnosticsListener.accessUnitReadyStats).hasSize(1);
    RtspH264AccessUnitStats stats = diagnosticsListener.accessUnitStats.get(0);
    assertThat(stats.trackId).isEqualTo(3);
    assertThat(stats.rtpSequenceNumber).isEqualTo(20);
    assertThat(stats.rtpTimestamp).isEqualTo(RTP_TIMESTAMP_1);
    assertThat(stats.nalUnitType).isEqualTo(5);
    assertThat(stats.accessUnitType).isEqualTo(RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR);
  }

  @Test
  public void consume_idrWithoutSpsPps_doesNotReportFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ false, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 1,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    assertThat(diagnosticsListener.accessUnitStats).isEmpty();
  }

  @Test
  public void consume_idrAfterCompleteInBandSpsPps_reportsFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ false, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 1);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 1,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("6742001E")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("68CE06E2")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2 + 90_000,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    assertThat(diagnosticsListener.accessUnitStats).hasSize(1);
    RtspH264AccessUnitStats stats = diagnosticsListener.accessUnitStats.get(0);
    assertThat(stats.rtpSequenceNumber).isEqualTo(3);
    assertThat(stats.hasSps).isTrue();
    assertThat(stats.hasPps).isTrue();
  }

  @Test
  public void consume_idrAfterCorruptedFragmentedSps_doesNotReportFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ false, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 1);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 1,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("7C871122")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("7C473344")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 4,
            /* marker= */ true,
            getBytesFromHexString("68CE06E2")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2 + 90_000,
            /* sequenceNumber= */ 5,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    assertThat(diagnosticsListener.accessUnitStats).isEmpty();
  }

  @Test
  public void consume_nonIdrWithSpsPps_doesNotReportFirstDecodableAccessUnit()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(/* hasInitializationData= */ true, diagnosticsListener);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(
        RTP_TIMESTAMP_1,
        /* sequenceNumber= */ 1,
        FIRST_RTP_ARRIVAL_ELAPSED_REALTIME_MS);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("410102")));

    assertThat(diagnosticsListener.accessUnitStats).isEmpty();
  }

  @Test
  public void consume_multipleNalPacketsInSameAccessUnit_outputsOneSample()
      throws ParserException {
    RtpH264Reader h264Reader = createH264Reader();

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("410102")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("410304")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            Bytes.concat(
                NalUnitUtil.NAL_START_CODE,
                getBytesFromHexString("410102"),
                NalUnitUtil.NAL_START_CODE,
                getBytesFromHexString("410304")));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
  }

  @Test
  public void consume_fragmentationUnitMissingPacket_dropsCorruptedAccessUnit()
      throws ParserException {
    RtpH264Reader h264Reader = createH264Reader();

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("7C851122")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("7C453344")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 4,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, getBytesFromHexString("650506")));
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void consume_singleNalUnit_outputsSample() throws ParserException {
    RtpH264Reader h264Reader = createH264Reader();

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("410102")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, getBytesFromHexString("410102")));
  }

  @Test
  public void consume_timestampChangesWithoutMarker_entersWaitIdrAndOutputsNextIdr()
      throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("410102")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(diagnosticsListener.corruptedStats).hasSize(1);
    assertThat(diagnosticsListener.waitStartedStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats).hasSize(1);
    assertThat(feedbackRequester.reasons).containsExactly(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  @Test
  public void consume_corruptedP_dropsNonIdrUntilCompleteIdr() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("7C811122")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("7C413344")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 4,
            /* marker= */ true,
            getBytesFromHexString("410506")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2 + 90_000,
            /* sequenceNumber= */ 5,
            /* marker= */ true,
            getBytesFromHexString("650708")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(diagnosticsListener.corruptedStats).hasSize(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats).hasSize(1);
    assertThat(feedbackRequester.reasons).containsExactly(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  @Test
  public void consume_corruptedIdr_dropsUntilNextCompleteIdr() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("7C851122")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("7C453344")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 4,
            /* marker= */ true,
            getBytesFromHexString("410506")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2 + 90_000,
            /* sequenceNumber= */ 5,
            /* marker= */ true,
            getBytesFromHexString("650708")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(diagnosticsListener.accessUnitStats).hasSize(1);
    assertThat(diagnosticsListener.accessUnitStats.get(0).rtpSequenceNumber).isEqualTo(5);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
  }

  @Test
  public void consume_malformedFu_dropsNonIdrUntilCompleteIdr() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("7C")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("410506")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2 + 90_000,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("650708")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(diagnosticsListener.corruptedStats).hasSize(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats).hasSize(1);
    assertThat(feedbackRequester.reasons).containsExactly(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  @Test
  public void onRtpStreamDiscontinuity_entersWaitIdr() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    h264Reader.onRtpStreamDiscontinuity(RtcpFeedbackReason.SEQUENCE_GAP);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("410102")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(diagnosticsListener.waitStartedStats).hasSize(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
    assertThat(feedbackRequester.reasons).isEmpty();
  }

  @Test
  public void externalOnlyWaitIdr_reportsEventsButDoesNotRequestRtcp() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true,
            /* accessUnitDiagnosticsEnabled= */ true,
            /* rtcpFeedbackRequestsEnabled= */ false,
            /* waitingForIdrTimeoutMs= */ 0);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    h264Reader.onRtpStreamDiscontinuity(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("410102")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("650506")));

    assertThat(diagnosticsListener.waitStartedStats).hasSize(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats.get(0).idrRecoveredCount).isEqualTo(1);
    assertThat(feedbackRequester.reasons).isEmpty();
  }

  @Test
  public void bothWaitIdr_reportsEventsAndRequestsRtcp() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true,
            /* accessUnitDiagnosticsEnabled= */ true,
            /* rtcpFeedbackRequestsEnabled= */ true,
            /* waitingForIdrTimeoutMs= */ 0);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    h264Reader.onRtpStreamDiscontinuity(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);

    assertThat(diagnosticsListener.waitStartedStats).hasSize(1);
    assertThat(feedbackRequester.reasons)
        .containsExactly(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  @Test
  public void waitIdrTimeout_reportsOnceWithMonotonicDuration() throws Exception {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ true,
            diagnosticsListener,
            /* rtcpFeedbackRequester= */ null,
            /* lowLatencyRecoveryEnabled= */ true,
            /* accessUnitDiagnosticsEnabled= */ true,
            /* rtcpFeedbackRequestsEnabled= */ false,
            /* waitingForIdrTimeoutMs= */ 1);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    h264Reader.onRtpStreamDiscontinuity(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
    ShadowSystemClock.advanceBy(Duration.ofMillis(5));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("410102")));
    long firstDropDuration = diagnosticsListener.droppedUntilIdrStats.get(0).waitingForIdrDurationMs;
    ShadowSystemClock.advanceBy(Duration.ofMillis(5));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("410304")));

    assertThat(diagnosticsListener.timeoutStats).hasSize(1);
    assertThat(diagnosticsListener.timeoutStats.get(0).waitingForIdrDurationMs).isAtLeast(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(2);
    assertThat(diagnosticsListener.droppedUntilIdrStats.get(1).waitingForIdrDurationMs)
        .isAtLeast(firstDropDuration);
  }

  @Test
  public void waitIdr_idrWithoutSpsPps_keepsDroppingUntilDecodableIdr() throws ParserException {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    CapturingFeedbackRequester feedbackRequester = new CapturingFeedbackRequester();
    RtpH264Reader h264Reader =
        createH264Reader(
            /* hasInitializationData= */ false,
            diagnosticsListener,
            feedbackRequester,
            /* lowLatencyRecoveryEnabled= */ true);

    h264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h264Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    h264Reader.onRtpStreamDiscontinuity(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("650506")));
    consume(
        h264Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 2,
            /* marker= */ true,
            getBytesFromHexString("1800046742001E000468CE06E20003650708")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(diagnosticsListener.droppedUntilIdrStats).hasSize(1);
    assertThat(diagnosticsListener.waitEndedStats).hasSize(1);
    assertThat(feedbackRequester.reasons).containsExactly(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  private static RtpH264Reader createH264Reader() {
    return createH264Reader(/* hasInitializationData= */ false, /* diagnosticsListener= */ null);
  }

  private static RtpH264Reader createH264Reader(
      boolean hasInitializationData, RtspDiagnosticsListener diagnosticsListener) {
    return createH264Reader(
        hasInitializationData,
        diagnosticsListener,
        /* rtcpFeedbackRequester= */ null,
        /* lowLatencyRecoveryEnabled= */ false,
        /* accessUnitDiagnosticsEnabled= */ true);
  }

  private static RtpH264Reader createH264Reader(
      boolean hasInitializationData,
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackRequester rtcpFeedbackRequester,
      boolean lowLatencyRecoveryEnabled) {
    return createH264Reader(
        hasInitializationData,
        diagnosticsListener,
        rtcpFeedbackRequester,
        lowLatencyRecoveryEnabled,
        /* accessUnitDiagnosticsEnabled= */ true);
  }

  private static RtpH264Reader createH264Reader(
      boolean hasInitializationData,
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackRequester rtcpFeedbackRequester,
      boolean lowLatencyRecoveryEnabled,
      boolean accessUnitDiagnosticsEnabled) {
    return createH264Reader(
        hasInitializationData,
        diagnosticsListener,
        rtcpFeedbackRequester,
        lowLatencyRecoveryEnabled,
        accessUnitDiagnosticsEnabled,
        /* rtcpFeedbackRequestsEnabled= */ rtcpFeedbackRequester != null,
        /* waitingForIdrTimeoutMs= */ 0);
  }

  private static RtpH264Reader createH264Reader(
      boolean hasInitializationData,
      RtspDiagnosticsListener diagnosticsListener,
      RtcpFeedbackRequester rtcpFeedbackRequester,
      boolean lowLatencyRecoveryEnabled,
      boolean accessUnitDiagnosticsEnabled,
      boolean rtcpFeedbackRequestsEnabled,
      long waitingForIdrTimeoutMs) {
    return new RtpH264Reader(
        new RtpPayloadFormat(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setInitializationData(
                    hasInitializationData
                        ? ImmutableList.of(
                            getBytesFromHexString("6742001E"), getBytesFromHexString("68CE06E2"))
                        : ImmutableList.of())
                .build(),
            /* rtpPayloadType= */ 96,
            /* clockRate= */ (int) MEDIA_CLOCK_FREQUENCY,
            /* fmtpParameters= */ ImmutableMap.of(),
            RtpPayloadFormat.RTP_MEDIA_H264),
        diagnosticsListener,
        rtcpFeedbackRequester,
        lowLatencyRecoveryEnabled,
        accessUnitDiagnosticsEnabled,
        rtcpFeedbackRequestsEnabled,
        waitingForIdrTimeoutMs);
  }

  private static RtpPacket createPacket(
      long timestamp, int sequenceNumber, boolean marker, byte[] payloadData) {
    return new RtpPacket.Builder()
        .setTimestamp(timestamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(marker)
        .setPayloadData(payloadData)
        .build();
  }

  private static void consume(RtpH264Reader h264Reader, RtpPacket rtpPacket)
      throws ParserException {
    h264Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker);
  }

  private static final class CapturingDiagnosticsListener implements RtspDiagnosticsListener {

    public final List<RtspH264AccessUnitStats> accessUnitStats = new ArrayList<>();
    public final List<RtspH264AccessUnitReadyStats> accessUnitReadyStats = new ArrayList<>();
    public final List<RtspH264RecoveryStats> corruptedStats = new ArrayList<>();
    public final List<RtspH264RecoveryStats> waitStartedStats = new ArrayList<>();
    public final List<RtspH264RecoveryStats> droppedUntilIdrStats = new ArrayList<>();
    public final List<RtspH264RecoveryStats> timeoutStats = new ArrayList<>();
    public final List<RtspH264RecoveryStats> waitEndedStats = new ArrayList<>();

    @Override
    public void onFirstDecodableVideoAccessUnitReady(
        RtspH264AccessUnitStats accessUnitStats) {
      this.accessUnitStats.add(accessUnitStats);
    }

    @Override
    public void onH264AccessUnitReady(RtspH264AccessUnitReadyStats accessUnitStats) {
      accessUnitReadyStats.add(accessUnitStats);
    }

    @Override
    public void onH264AccessUnitCorrupted(RtspH264RecoveryStats recoveryStats) {
      corruptedStats.add(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrStarted(RtspH264RecoveryStats recoveryStats) {
      waitStartedStats.add(recoveryStats);
    }

    @Override
    public void onH264AccessUnitDroppedUntilIdr(RtspH264RecoveryStats recoveryStats) {
      droppedUntilIdrStats.add(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrTimedOut(RtspH264RecoveryStats recoveryStats) {
      timeoutStats.add(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrEnded(RtspH264RecoveryStats recoveryStats) {
      waitEndedStats.add(recoveryStats);
    }
  }

  private static final class CapturingFeedbackRequester implements RtcpFeedbackRequester {

    public final List<Integer> reasons = new ArrayList<>();

    @Override
    public boolean requestKeyFrame(@RtcpFeedbackReason.Reason int reason) {
      reasons.add(reason);
      return true;
    }
  }
}
