/*
 * Copyright 2020 The Android Open Source Project
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

import static com.google.android.exoplayer2.source.rtsp.reader.RtpReaderUtils.toSampleTimeUs;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static com.google.android.exoplayer2.util.Util.castNonNull;

import android.os.SystemClock;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.source.rtsp.RtpPacket;
import com.google.android.exoplayer2.source.rtsp.RtpPayloadFormat;
import com.google.android.exoplayer2.source.rtsp.RtspDiagnosticsListener;
import com.google.android.exoplayer2.source.rtsp.RtspH264AccessUnitStats;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Parses an H264 byte stream carried on RTP packets, and extracts H264 Access Units.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class RtpH264Reader implements RtpPayloadReader {
  private static final String TAG = "RtpH264Reader";

  private static final int MEDIA_CLOCK_FREQUENCY = 90_000;

  /** Offset of payload data within a FU type A payload. */
  private static final int FU_PAYLOAD_OFFSET = 2;

  /** Single Time Aggregation Packet type A. */
  private static final int RTP_PACKET_TYPE_STAP_A = 24;
  /** Fragmentation Unit type A. */
  private static final int RTP_PACKET_TYPE_FU_A = 28;

  /** IDR NAL unit type. */
  private static final int NAL_UNIT_TYPE_IDR = 5;
  /** SPS NAL unit type. */
  private static final int NAL_UNIT_TYPE_SPS = 7;
  /** PPS NAL unit type. */
  private static final int NAL_UNIT_TYPE_PPS = 8;

  /** Scratch for Fragmentation Unit RTP packets. */
  private final ParsableByteArray fuScratchBuffer;

  private final ParsableByteArray nalStartCodeArray =
      new ParsableByteArray(NalUnitUtil.NAL_START_CODE);

  private final RtpPayloadFormat payloadFormat;
  @Nullable private final RtspDiagnosticsListener rtspDiagnosticsListener;

  private @MonotonicNonNull TrackOutput trackOutput;
  private @C.BufferFlags int bufferFlags;
  private int trackId;

  private long firstReceivedTimestamp;
  private long firstRtpPacketArrivalElapsedRealtimeMs;
  private int previousSequenceNumber;
  private long previousTimestamp;
  /** The combined size of a sample that is fragmented into multiple RTP packets. */
  private int fragmentedSampleSizeBytes;

  private long startTimeOffsetUs;
  /**
   * If a Fragmentation Unit is lost then following units corresponding to the same NAL unit should
   * be discarded (RFC6184 Section 5.8).
   */
  private boolean isCurrentAccessUnitCorrupted;
  private boolean isProcessingFragmentationUnit;
  private boolean hasOutputSps;
  private boolean hasOutputPps;
  private boolean firstDecodableAccessUnitDiagnosticsEnabled;
  private boolean currentAccessUnitHasIdr;
  private boolean currentAccessUnitHasSps;
  private boolean currentAccessUnitHasPps;
  private int currentAccessUnitFirstSequenceNumber;
  private long currentAccessUnitRtpTimestamp;

  /** Creates an instance. */
  public RtpH264Reader(RtpPayloadFormat payloadFormat) {
    this(payloadFormat, /* rtspDiagnosticsListener= */ null);
  }

  /** Creates an instance. */
  public RtpH264Reader(
      RtpPayloadFormat payloadFormat, @Nullable RtspDiagnosticsListener rtspDiagnosticsListener) {
    this.payloadFormat = payloadFormat;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    fuScratchBuffer = new ParsableByteArray();
    firstReceivedTimestamp = C.TIME_UNSET;
    firstRtpPacketArrivalElapsedRealtimeMs = C.TIME_UNSET;
    previousSequenceNumber = C.INDEX_UNSET;
    previousTimestamp = C.TIME_UNSET;
    isCurrentAccessUnitCorrupted = false;
    isProcessingFragmentationUnit = false;
    firstDecodableAccessUnitDiagnosticsEnabled = rtspDiagnosticsListener != null;
    hasOutputSps =
        firstDecodableAccessUnitDiagnosticsEnabled
            && payloadFormat.format.initializationData.size() >= 1;
    hasOutputPps =
        firstDecodableAccessUnitDiagnosticsEnabled
            && payloadFormat.format.initializationData.size() >= 2;
    currentAccessUnitFirstSequenceNumber = C.INDEX_UNSET;
    currentAccessUnitRtpTimestamp = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    this.trackId = trackId;
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_VIDEO);

    castNonNull(trackOutput).format(payloadFormat.format);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void onReceivingFirstPacket(
      long timestamp, int sequenceNumber, long arrivalElapsedRealtimeMs) {
    firstRtpPacketArrivalElapsedRealtimeMs = arrivalElapsedRealtimeMs;
  }

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {

    if (previousTimestamp != C.TIME_UNSET && timestamp != previousTimestamp) {
      resetReaderStateForNewAccessUnit();
    }

    checkStateNotNull(trackOutput);
    if (!isCurrentAccessUnitCorrupted) {
      if (isFirstDecodableAccessUnitDiagnosticsEnabled()
          && currentAccessUnitFirstSequenceNumber == C.INDEX_UNSET) {
        currentAccessUnitFirstSequenceNumber = sequenceNumber;
        currentAccessUnitRtpTimestamp = timestamp;
      }
      int rtpH264PacketMode;
      try {
        // RFC6184 Section 5.6, 5.7 and 5.8.
        rtpH264PacketMode = data.getData()[0] & 0x1F;
      } catch (IndexOutOfBoundsException e) {
        throw ParserException.createForMalformedManifest(/* message= */ null, e);
      }

      if (isProcessingFragmentationUnit && rtpH264PacketMode != RTP_PACKET_TYPE_FU_A) {
        isCurrentAccessUnitCorrupted = true;
      }
      if (!isCurrentAccessUnitCorrupted) {
        if (rtpH264PacketMode > 0 && rtpH264PacketMode < 24) {
          processSingleNalUnitPacket(data);
        } else if (rtpH264PacketMode == RTP_PACKET_TYPE_STAP_A) {
          processSingleTimeAggregationPacket(data);
        } else if (rtpH264PacketMode == RTP_PACKET_TYPE_FU_A) {
          processFragmentationUnitPacket(data, sequenceNumber);
        } else {
          throw ParserException.createForMalformedManifest(
              Util.formatInvariant(
                  "RTP H264 packetization mode [%d] not supported.", rtpH264PacketMode),
              /* cause= */ null);
        }
      }
    }

    if (firstReceivedTimestamp == C.TIME_UNSET) {
      firstReceivedTimestamp = timestamp;
    }
    if (rtpMarker) {
      if (!isCurrentAccessUnitCorrupted) {
        long timeUs =
            toSampleTimeUs(
                startTimeOffsetUs, timestamp, firstReceivedTimestamp, MEDIA_CLOCK_FREQUENCY);
        trackOutput.sampleMetadata(
            timeUs,
            bufferFlags,
            fragmentedSampleSizeBytes,
            /* offset= */ 0,
            /* cryptoData= */ null);
        maybeNotifyFirstDecodableAccessUnitReady();
      }
      resetReaderStateForNewAccessUnit();
    }

    previousTimestamp = timestamp;
    previousSequenceNumber = sequenceNumber;
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    previousTimestamp = C.TIME_UNSET;
    resetReaderStateForNewAccessUnit();
    startTimeOffsetUs = timeUs;
  }

  // Internal methods.

  /**
   * Processes Single NAL Unit packet (RFC6184 Section 5.6).
   *
   * <p>Outputs the single NAL Unit (with start code prepended) to {@link #trackOutput}. Sets {@link
   * #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleNalUnitPacket(ParsableByteArray data) {
    // Example of a Single Nal Unit packet
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |F|NRI|  Type   |                                               |
    //    +-+-+-+-+-+-+-+-+                                               |
    //    |                                                               |
    //    |               Bytes 2..n of a single NAL unit                 |
    //    |                                                               |
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    int numBytesInData = data.bytesLeft();
    fragmentedSampleSizeBytes += writeStartCode();
    trackOutput.sampleData(data, numBytesInData);
    fragmentedSampleSizeBytes += numBytesInData;

    int nalHeaderType = data.getData()[0] & 0x1F;
    if (isFirstDecodableAccessUnitDiagnosticsEnabled()) {
      recordNalUnitType(nalHeaderType);
    }
    bufferFlags = getBufferFlagsFromNalType(nalHeaderType);
  }

  /**
   * Processes STAP Type A packet (RFC6184 Section 5.7).
   *
   * <p>Outputs the received aggregation units (with start code prepended) to {@link #trackOutput}.
   * Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleTimeAggregationPacket(ParsableByteArray data) {
    //  Example of an STAP-A packet.
    //      0                   1                   2                   3
    //     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                          RTP Header                           |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |STAP-A NAL HDR |         NALU 1 Size           | NALU 1 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 1 Data                           |
    //    :                                                               :
    //    +               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |               | NALU 2 Size                   | NALU 2 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 2 Data                           |
    //    :                                                               :
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    // Skips STAP-A NAL HDR that has the NAL format |F|NRI|Type|, but with Type replaced by the
    // STAP-A type id (RTP_PACKET_TYPE_STAP_A).
    data.readUnsignedByte();

    // Gets all NAL units until the remaining bytes are only enough to store an RTP padding.
    int nalUnitLength;
    while (data.bytesLeft() > 4) {
      nalUnitLength = data.readUnsignedShort();
      fragmentedSampleSizeBytes += writeStartCode();
      if (isFirstDecodableAccessUnitDiagnosticsEnabled()) {
        recordNalUnitType(data.getData()[data.getPosition()] & 0x1F);
      }
      trackOutput.sampleData(data, nalUnitLength);
      fragmentedSampleSizeBytes += nalUnitLength;
    }

    // Treat Aggregated NAL units as non key frames.
    bufferFlags = 0;
  }

  /**
   * Processes Fragmentation Unit Type A packet (RFC6184 Section 5.8).
   *
   * <p>This method will be invoked multiple times to receive a single frame that is broken down
   * into a series of fragmentation units in multiple RTP packets.
   *
   * <p>Outputs the received fragmentation units (with start code prepended) to {@link
   * #trackOutput}. Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processFragmentationUnitPacket(ParsableByteArray data, int packetSequenceNumber) {
    //  FU-A mode packet layout.
    //   0                   1                   2                   3
    //   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  | FU indicator  |   FU header   |                               |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
    //  |                                                               |
    //  |                         FU payload                            |
    //  |                                                               |
    //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |                               :...OPTIONAL RTP padding        |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //
    //     FU Indicator     FU Header
    //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |F|NRI|  Type   |S|E|R|  Type   |
    //  +---------------+---------------+
    //  Indicator: Upper 3 bits are the same as NALU header, Type = 28 (FU-A type).
    //  Header: Start/End/Reserved/Type. Type is same as NALU type.
    int fuIndicator = data.getData()[0];
    int fuHeader = data.getData()[1];
    int nalHeader = (fuIndicator & 0xE0) | (fuHeader & 0x1F);
    boolean isFirstFuPacket = (fuHeader & 0x80) > 0;
    boolean isLastFuPacket = (fuHeader & 0x40) > 0;

    if (isFirstFuPacket) {
      if (isProcessingFragmentationUnit) {
        // Interruption: A new FU started before the previous one finished.
        isCurrentAccessUnitCorrupted = true;
        fragmentedSampleSizeBytes = 0;
      }
      isProcessingFragmentationUnit = true;
      if (isFirstDecodableAccessUnitDiagnosticsEnabled()) {
        recordNalUnitType(nalHeader & 0x1F);
      }
      // Prepends starter code.
      fragmentedSampleSizeBytes += writeStartCode();

      // The bytes needed is 1 (NALU header) + payload size. The original data array has size 2 (FU
      // indicator/header) + payload size. Thus setting the correct header and set position to 1.
      data.getData()[1] = (byte) nalHeader;
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(1);
    } else {
      if (isCurrentAccessUnitCorrupted) {
        return;
      }
      // Check that this packet is in the sequence of the previous packet.
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (packetSequenceNumber != expectedSequenceNumber) {
        isCurrentAccessUnitCorrupted = true;
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d."
                    + " Dropping packet.",
                expectedSequenceNumber, packetSequenceNumber));
        return;
      }

      // Setting position to ignore FU indicator and header.
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(FU_PAYLOAD_OFFSET);
    }

    int fragmentSize = fuScratchBuffer.bytesLeft();
    trackOutput.sampleData(fuScratchBuffer, fragmentSize);
    fragmentedSampleSizeBytes += fragmentSize;

    if (isLastFuPacket) {
      isProcessingFragmentationUnit = false;
      bufferFlags = getBufferFlagsFromNalType(nalHeader & 0x1F);
    }
  }

  private void resetReaderStateForNewAccessUnit() {
    isCurrentAccessUnitCorrupted = false;
    isProcessingFragmentationUnit = false;
    fragmentedSampleSizeBytes = 0;
    bufferFlags = 0;
    currentAccessUnitHasIdr = false;
    currentAccessUnitHasSps = false;
    currentAccessUnitHasPps = false;
    currentAccessUnitFirstSequenceNumber = C.INDEX_UNSET;
    currentAccessUnitRtpTimestamp = C.TIME_UNSET;
  }

  private void recordNalUnitType(int nalUnitType) {
    if (!isFirstDecodableAccessUnitDiagnosticsEnabled()) {
      return;
    }
    if (nalUnitType == NAL_UNIT_TYPE_IDR) {
      currentAccessUnitHasIdr = true;
    } else if (nalUnitType == NAL_UNIT_TYPE_SPS) {
      currentAccessUnitHasSps = true;
      hasOutputSps = true;
    } else if (nalUnitType == NAL_UNIT_TYPE_PPS) {
      currentAccessUnitHasPps = true;
      hasOutputPps = true;
    }
  }

  private void maybeNotifyFirstDecodableAccessUnitReady() {
    if (!isFirstDecodableAccessUnitDiagnosticsEnabled()
        || !currentAccessUnitHasIdr
        || !hasOutputSps
        || !hasOutputPps) {
      return;
    }
    firstDecodableAccessUnitDiagnosticsEnabled = false;
    long elapsedFromFirstRtpMs =
        firstRtpPacketArrivalElapsedRealtimeMs == C.TIME_UNSET
            ? C.TIME_UNSET
            : Math.max(0, SystemClock.elapsedRealtime() - firstRtpPacketArrivalElapsedRealtimeMs);
    rtspDiagnosticsListener.onFirstDecodableVideoAccessUnitReady(
        new RtspH264AccessUnitStats(
            trackId,
            currentAccessUnitFirstSequenceNumber,
            currentAccessUnitRtpTimestamp,
            hasOutputSps || currentAccessUnitHasSps,
            hasOutputPps || currentAccessUnitHasPps,
            NAL_UNIT_TYPE_IDR,
            RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR,
            elapsedFromFirstRtpMs));
  }

  private boolean isFirstDecodableAccessUnitDiagnosticsEnabled() {
    return firstDecodableAccessUnitDiagnosticsEnabled;
  }

  private int writeStartCode() {
    nalStartCodeArray.setPosition(/* position= */ 0);
    int bytesWritten = nalStartCodeArray.bytesLeft();
    checkNotNull(trackOutput).sampleData(nalStartCodeArray, bytesWritten);
    return bytesWritten;
  }

  private static @C.BufferFlags int getBufferFlagsFromNalType(int nalType) {
    return nalType == NAL_UNIT_TYPE_IDR ? C.BUFFER_FLAG_KEY_FRAME : 0;
  }
}
