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
import com.google.android.exoplayer2.source.rtsp.RtcpFeedbackReason;
import com.google.android.exoplayer2.source.rtsp.RtcpFeedbackRequester;
import com.google.android.exoplayer2.source.rtsp.RtspH264AccessUnitReadyStats;
import com.google.android.exoplayer2.source.rtsp.RtspDiagnosticsListener;
import com.google.android.exoplayer2.source.rtsp.RtspH264AccessUnitStats;
import com.google.android.exoplayer2.source.rtsp.RtspH264RecoveryStats;
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
  @Nullable private final RtcpFeedbackRequester rtcpFeedbackRequester;
  private final boolean lowLatencyRecoveryEnabled;
  private final boolean accessUnitDiagnosticsEnabled;
  private final boolean rtcpFeedbackRequestsEnabled;
  private final long waitingForIdrTimeoutMs;

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
  private boolean waitingForIdr;
  private long waitingForIdrStartElapsedRealtimeMs;
  private boolean waitingForIdrTimeoutNotified;
  private int corruptedAccessUnitCount;
  private int droppedUntilIdrCount;
  private int idrRecoveredCount;
  private int lastRtpSequence;
  private long lastRtpTimestamp;

  /** Creates an instance. */
  public RtpH264Reader(RtpPayloadFormat payloadFormat) {
    this(payloadFormat, /* rtspDiagnosticsListener= */ null);
  }

  /** Creates an instance. */
  public RtpH264Reader(
      RtpPayloadFormat payloadFormat, @Nullable RtspDiagnosticsListener rtspDiagnosticsListener) {
    this(
        payloadFormat,
        rtspDiagnosticsListener,
        /* rtcpFeedbackRequester= */ null,
        /* lowLatencyRecoveryEnabled= */ false,
        /* accessUnitDiagnosticsEnabled= */ false);
  }

  /** Creates an instance. */
  public RtpH264Reader(
      RtpPayloadFormat payloadFormat,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtcpFeedbackRequester rtcpFeedbackRequester,
      boolean lowLatencyRecoveryEnabled,
      boolean accessUnitDiagnosticsEnabled) {
    this(
        payloadFormat,
        rtspDiagnosticsListener,
        rtcpFeedbackRequester,
        lowLatencyRecoveryEnabled,
        accessUnitDiagnosticsEnabled,
        /* rtcpFeedbackRequestsEnabled= */ rtcpFeedbackRequester != null,
        /* waitingForIdrTimeoutMs= */ 0);
  }

  /** Creates an instance. */
  public RtpH264Reader(
      RtpPayloadFormat payloadFormat,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtcpFeedbackRequester rtcpFeedbackRequester,
      boolean lowLatencyRecoveryEnabled,
      boolean accessUnitDiagnosticsEnabled,
      boolean rtcpFeedbackRequestsEnabled,
      long waitingForIdrTimeoutMs) {
    this.payloadFormat = payloadFormat;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.rtcpFeedbackRequester = rtcpFeedbackRequester;
    this.lowLatencyRecoveryEnabled = lowLatencyRecoveryEnabled;
    this.accessUnitDiagnosticsEnabled = accessUnitDiagnosticsEnabled;
    this.rtcpFeedbackRequestsEnabled = rtcpFeedbackRequestsEnabled;
    this.waitingForIdrTimeoutMs = waitingForIdrTimeoutMs;
    firstDecodableAccessUnitDiagnosticsEnabled = rtspDiagnosticsListener != null;
    fuScratchBuffer = new ParsableByteArray();
    firstReceivedTimestamp = C.TIME_UNSET;
    firstRtpPacketArrivalElapsedRealtimeMs = C.TIME_UNSET;
    previousSequenceNumber = C.INDEX_UNSET;
    previousTimestamp = C.TIME_UNSET;
    isCurrentAccessUnitCorrupted = false;
    isProcessingFragmentationUnit = false;
    boolean shouldTrackParameterSets =
        firstDecodableAccessUnitDiagnosticsEnabled || lowLatencyRecoveryEnabled;
    hasOutputSps = shouldTrackParameterSets && payloadFormat.format.initializationData.size() >= 1;
    hasOutputPps = shouldTrackParameterSets && payloadFormat.format.initializationData.size() >= 2;
    currentAccessUnitFirstSequenceNumber = C.INDEX_UNSET;
    currentAccessUnitRtpTimestamp = C.TIME_UNSET;
    waitingForIdr = false;
    waitingForIdrStartElapsedRealtimeMs = C.TIME_UNSET;
    waitingForIdrTimeoutNotified = false;
    lastRtpSequence = C.INDEX_UNSET;
    lastRtpTimestamp = C.TIME_UNSET;
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
  public void onRtpStreamDiscontinuity(@RtcpFeedbackReason.Reason int reason) {
    lastRtpSequence = previousSequenceNumber;
    lastRtpTimestamp = previousTimestamp;
    if (isCurrentAccessUnitOpen()) {
      markCurrentAccessUnitCorrupted(reason);
    } else {
      enterWaitForIdr(reason);
    }
  }

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {
    lastRtpSequence = sequenceNumber;
    lastRtpTimestamp = timestamp;

    if (previousTimestamp != C.TIME_UNSET && timestamp != previousTimestamp) {
      if (isCurrentAccessUnitOpen()) {
        markCurrentAccessUnitCorrupted(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
      }
      resetReaderStateForNewAccessUnit();
    }

    checkStateNotNull(trackOutput);
    if (!isCurrentAccessUnitCorrupted) {
      if (currentAccessUnitFirstSequenceNumber == C.INDEX_UNSET) {
        currentAccessUnitFirstSequenceNumber = sequenceNumber;
        currentAccessUnitRtpTimestamp = timestamp;
      }
      try {
        int rtpH264PacketMode;
        // RFC6184 Section 5.6, 5.7 and 5.8.
        rtpH264PacketMode = data.getData()[0] & 0x1F;

        if (isProcessingFragmentationUnit && rtpH264PacketMode != RTP_PACKET_TYPE_FU_A) {
          markCurrentAccessUnitCorrupted(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
        }
        if (isCurrentAccessUnitCorrupted) {
          // Fall through to marker handling below so the corrupted access unit can be reset.
        } else if (rtpH264PacketMode > 0 && rtpH264PacketMode < 24) {
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
      } catch (IndexOutOfBoundsException e) {
        handleDepacketizationFailure(e);
      } catch (ParserException e) {
        handleDepacketizationFailure(e);
      }
    }

    if (firstReceivedTimestamp == C.TIME_UNSET) {
      firstReceivedTimestamp = timestamp;
    }
    if (rtpMarker) {
      boolean shouldSubmitAccessUnit = shouldSubmitAccessUnit();
      if (shouldSubmitAccessUnit) {
        long timeUs =
            toSampleTimeUs(
                startTimeOffsetUs, timestamp, firstReceivedTimestamp, MEDIA_CLOCK_FREQUENCY);
        trackOutput.sampleMetadata(
            timeUs,
            bufferFlags,
            fragmentedSampleSizeBytes,
            /* offset= */ 0,
            /* cryptoData= */ null);
        maybeNotifyAccessUnitReady(timeUs);
        maybeNotifyFirstDecodableAccessUnitReady(timeUs);
        if (waitingForIdr && isCurrentAccessUnitDecodableIdr()) {
          exitWaitForIdr(RtcpFeedbackReason.WAITING_FOR_IDR);
        }
        maybeRecordOutputParameterSets();
      } else if (!isCurrentAccessUnitCorrupted && waitingForIdr) {
        notifyAccessUnitDroppedUntilIdr();
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
    waitingForIdr = false;
    waitingForIdrStartElapsedRealtimeMs = C.TIME_UNSET;
    waitingForIdrTimeoutNotified = false;
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
    maybeRecordNalUnitType(nalHeaderType);
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
      maybeRecordNalUnitType(data.getData()[data.getPosition()] & 0x1F);
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
        markCurrentAccessUnitCorrupted(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
        fragmentedSampleSizeBytes = 0;
      }
      isProcessingFragmentationUnit = true;
      maybeRecordNalUnitType(nalHeader & 0x1F);
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
        markCurrentAccessUnitCorrupted(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
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

  private void maybeRecordNalUnitType(int nalUnitType) {
    if (!shouldTrackAccessUnitType()) {
      return;
    }
    if (nalUnitType == NAL_UNIT_TYPE_IDR) {
      currentAccessUnitHasIdr = true;
    } else if (nalUnitType == NAL_UNIT_TYPE_SPS) {
      currentAccessUnitHasSps = true;
    } else if (nalUnitType == NAL_UNIT_TYPE_PPS) {
      currentAccessUnitHasPps = true;
    }
  }

  private void handleDepacketizationFailure(Exception error) throws ParserException {
    if (!lowLatencyRecoveryEnabled) {
      if (error instanceof ParserException) {
        throw (ParserException) error;
      }
      throw ParserException.createForMalformedManifest(/* message= */ null, error);
    }
    markCurrentAccessUnitCorrupted(RtcpFeedbackReason.ACCESS_UNIT_CORRUPTED);
  }

  private boolean shouldSubmitAccessUnit() {
    if (isCurrentAccessUnitCorrupted) {
      return false;
    }
    return !waitingForIdr || isCurrentAccessUnitDecodableIdr();
  }

  private boolean isCurrentAccessUnitDecodableIdr() {
    return currentAccessUnitHasIdr
        && (hasOutputSps || currentAccessUnitHasSps)
        && (hasOutputPps || currentAccessUnitHasPps);
  }

  private void maybeRecordOutputParameterSets() {
    if (!shouldTrackAccessUnitType()) {
      return;
    }
    hasOutputSps |= currentAccessUnitHasSps;
    hasOutputPps |= currentAccessUnitHasPps;
  }

  private boolean isCurrentAccessUnitOpen() {
    return fragmentedSampleSizeBytes > 0
        || isProcessingFragmentationUnit
        || currentAccessUnitFirstSequenceNumber != C.INDEX_UNSET;
  }

  private void markCurrentAccessUnitCorrupted(@RtcpFeedbackReason.Reason int reason) {
    if (isCurrentAccessUnitCorrupted) {
      return;
    }
    isCurrentAccessUnitCorrupted = true;
    corruptedAccessUnitCount++;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onH264AccessUnitCorrupted(createRecoveryStats(reason));
    }
    enterWaitForIdr(reason);
  }

  private void enterWaitForIdr(@RtcpFeedbackReason.Reason int reason) {
    if (!lowLatencyRecoveryEnabled || waitingForIdr) {
      return;
    }
    waitingForIdr = true;
    waitingForIdrStartElapsedRealtimeMs = SystemClock.elapsedRealtime();
    waitingForIdrTimeoutNotified = false;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onH264WaitForIdrStarted(createRecoveryStats(reason));
    }
    if (rtcpFeedbackRequestsEnabled
        && rtcpFeedbackRequester != null
        && reason != RtcpFeedbackReason.SEQUENCE_GAP
        && reason != RtcpFeedbackReason.QUEUE_RESET) {
      rtcpFeedbackRequester.requestKeyFrame(reason);
    }
  }

  private void exitWaitForIdr(@RtcpFeedbackReason.Reason int reason) {
    idrRecoveredCount++;
    waitingForIdr = false;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onH264WaitForIdrEnded(createRecoveryStats(reason));
    }
    waitingForIdrStartElapsedRealtimeMs = C.TIME_UNSET;
    waitingForIdrTimeoutNotified = false;
  }

  private void notifyAccessUnitDroppedUntilIdr() {
    droppedUntilIdrCount++;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onH264AccessUnitDroppedUntilIdr(
          createRecoveryStats(RtcpFeedbackReason.WAITING_FOR_IDR));
      maybeNotifyWaitForIdrTimeout();
    }
  }

  private void maybeNotifyWaitForIdrTimeout() {
    if (waitingForIdrTimeoutMs == 0
        || waitingForIdrTimeoutNotified
        || getWaitingForIdrDurationMs() < waitingForIdrTimeoutMs) {
      return;
    }
    waitingForIdrTimeoutNotified = true;
    checkNotNull(rtspDiagnosticsListener)
        .onH264WaitForIdrTimedOut(createRecoveryStats(RtcpFeedbackReason.WAITING_FOR_IDR));
  }

  private void maybeNotifyAccessUnitReady(long sampleTimeUs) {
    if (rtspDiagnosticsListener == null || !accessUnitDiagnosticsEnabled) {
      return;
    }
    rtspDiagnosticsListener.onH264AccessUnitReady(
        new RtspH264AccessUnitReadyStats(
            trackId,
            currentAccessUnitFirstSequenceNumber,
            currentAccessUnitRtpTimestamp,
            sampleTimeUs,
            currentAccessUnitHasIdr,
            SystemClock.elapsedRealtime()));
  }

  private RtspH264RecoveryStats createRecoveryStats(@RtcpFeedbackReason.Reason int reason) {
    return new RtspH264RecoveryStats(
        trackId,
        currentAccessUnitFirstSequenceNumber,
        currentAccessUnitRtpTimestamp,
        waitingForIdr,
        corruptedAccessUnitCount,
        droppedUntilIdrCount,
        reason,
        getWaitingForIdrDurationMs(),
        lastRtpSequence,
        lastRtpTimestamp,
        idrRecoveredCount);
  }

  private long getWaitingForIdrDurationMs() {
    if (waitingForIdrStartElapsedRealtimeMs == C.TIME_UNSET) {
      return 0;
    }
    return Math.max(0, SystemClock.elapsedRealtime() - waitingForIdrStartElapsedRealtimeMs);
  }

  private void maybeNotifyFirstDecodableAccessUnitReady(long sampleTimeUs) {
    if (!isFirstDecodableAccessUnitDiagnosticsEnabled()) {
      return;
    }
    boolean hasSpsForAccessUnit = hasOutputSps || currentAccessUnitHasSps;
    boolean hasPpsForAccessUnit = hasOutputPps || currentAccessUnitHasPps;
    if (!currentAccessUnitHasIdr || !hasSpsForAccessUnit || !hasPpsForAccessUnit) {
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
            sampleTimeUs,
            hasSpsForAccessUnit,
            hasPpsForAccessUnit,
            NAL_UNIT_TYPE_IDR,
            RtspH264AccessUnitStats.ACCESS_UNIT_TYPE_IDR,
            elapsedFromFirstRtpMs));
  }

  private boolean isFirstDecodableAccessUnitDiagnosticsEnabled() {
    return firstDecodableAccessUnitDiagnosticsEnabled;
  }

  private boolean shouldTrackAccessUnitType() {
    return firstDecodableAccessUnitDiagnosticsEnabled
        || lowLatencyRecoveryEnabled
        || accessUnitDiagnosticsEnabled;
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
