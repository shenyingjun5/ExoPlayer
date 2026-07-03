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
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RtpH264Reader}. */
@RunWith(AndroidJUnit4.class)
public final class RtpH264ReaderTest {

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final long RTP_TIMESTAMP_1 = 1_000;
  private static final long RTP_TIMESTAMP_2 = 91_000;

  private FakeExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    extractorOutput =
        new FakeExtractorOutput(
            (id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
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

  private static RtpH264Reader createH264Reader() {
    return new RtpH264Reader(
        new RtpPayloadFormat(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            /* rtpPayloadType= */ 96,
            /* clockRate= */ (int) MEDIA_CLOCK_FREQUENCY,
            /* fmtpParameters= */ ImmutableMap.of(),
            RtpPayloadFormat.RTP_MEDIA_H264));
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
}
