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
import static org.junit.Assert.assertThrows;

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

/** Unit tests for {@link RtpH265Reader}. */
@RunWith(AndroidJUnit4.class)
public final class RtpH265ReaderTest {

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
  public void consume_fragmentationUnitMissingPacket_dropsCorruptedAccessUnit()
      throws ParserException {
    RtpH265Reader h265Reader = createH265Reader();

    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h265Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h265Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ false,
            getBytesFromHexString("6201931122")));
    consume(
        h265Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 3,
            /* marker= */ true,
            getBytesFromHexString("6201533344")));
    consume(
        h265Reader,
        createPacket(
            RTP_TIMESTAMP_2,
            /* sequenceNumber= */ 4,
            /* marker= */ true,
            getBytesFromHexString("26010506")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, getBytesFromHexString("26010506")));
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void consume_aggregationPacket_outputsAggregatedNalUnits() throws ParserException {
    RtpH265Reader h265Reader = createH265Reader();

    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    h265Reader.onReceivingFirstPacket(RTP_TIMESTAMP_1, /* sequenceNumber= */ 1);
    consume(
        h265Reader,
        createPacket(
            RTP_TIMESTAMP_1,
            /* sequenceNumber= */ 1,
            /* marker= */ true,
            getBytesFromHexString("60010003260155000428016677")));

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            Bytes.concat(
                NalUnitUtil.NAL_START_CODE,
                getBytesFromHexString("260155"),
                NalUnitUtil.NAL_START_CODE,
                getBytesFromHexString("28016677")));
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void consume_malformedAggregationPacket_throwsParserException() {
    RtpH265Reader h265Reader = createH265Reader();
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);

    assertThrows(
        ParserException.class,
        () ->
            consume(
                h265Reader,
                createPacket(
                    RTP_TIMESTAMP_1,
                    /* sequenceNumber= */ 1,
                    /* marker= */ true,
                    getBytesFromHexString("60010004260155"))));
  }

  private static RtpH265Reader createH265Reader() {
    return new RtpH265Reader(
        new RtpPayloadFormat(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* rtpPayloadType= */ 96,
            /* clockRate= */ (int) MEDIA_CLOCK_FREQUENCY,
            /* fmtpParameters= */ ImmutableMap.of(),
            RtpPayloadFormat.RTP_MEDIA_H265));
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

  private static void consume(RtpH265Reader h265Reader, RtpPacket rtpPacket)
      throws ParserException {
    h265Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker);
  }
}
