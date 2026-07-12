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

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.os.SystemClock;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.source.rtsp.reader.DefaultRtpPayloadReaderFactory;
import com.google.android.exoplayer2.source.rtsp.reader.RtpPayloadReader;
import com.google.android.exoplayer2.util.ParsableByteArray;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from RTP packets.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class RtpExtractor implements Extractor {

  private final RtpPayloadReader payloadReader;
  private final ParsableByteArray rtpPacketScratchBuffer;
  private final ParsableByteArray rtpPacketDataBuffer;
  private final int trackId;
  private final @RtspTransportMode.Mode int transportMode;
  @Nullable private final RtspDiagnosticsListener rtspDiagnosticsListener;
  @Nullable private final RtcpFeedbackRequester rtcpFeedbackRequester;
  private final RtcpFeedbackPolicy rtcpFeedbackPolicy;
  private final RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy;
  private final long rtpReorderWaitMs;
  private final boolean rtspPacketDiagnosticsEnabled;
  private final boolean payloadReaderDiscontinuityNotificationsEnabled;
  private final Object lock;
  private final RtpPacketReorderingQueue reorderingQueue;

  private @MonotonicNonNull ExtractorOutput output;
  private boolean firstPacketRead;
  private volatile long firstTimestamp;
  private volatile int firstSequenceNumber;
  private volatile int lastSsrc;
  private long lastExtractorReadElapsedRealtimeMs;

  @GuardedBy("lock")
  private boolean isSeekPending;

  @GuardedBy("lock")
  private long nextRtpTimestamp;

  @GuardedBy("lock")
  private long playbackStartTimeUs;

  public RtpExtractor(RtpPayloadFormat payloadFormat, int trackId) {
    this(
        payloadFormat,
        trackId,
        RtspTransportMode.UNKNOWN,
        /* rtspDiagnosticsListener= */ null,
        /* rtcpFeedbackRequester= */ null,
        RtcpFeedbackPolicy.DEFAULT,
        RtspBacklogRecoveryPolicy.DISABLED,
        /* rtspPacketDiagnosticsEnabled= */ false);
  }

  public RtpExtractor(
      RtpPayloadFormat payloadFormat,
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtcpFeedbackRequester rtcpFeedbackRequester,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy,
      boolean rtspPacketDiagnosticsEnabled) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.rtcpFeedbackRequester =
        rtcpFeedbackPolicy.canSendRtcpFeedback() || rtcpFeedbackPolicy.canSendGenericNack()
            ? rtcpFeedbackRequester
            : null;
    this.rtcpFeedbackPolicy = rtcpFeedbackPolicy;
    this.rtspBacklogRecoveryPolicy = rtspBacklogRecoveryPolicy;
    rtpReorderWaitMs = rtspBacklogRecoveryPolicy.getRtpReorderWaitMs(transportMode);
    this.rtspPacketDiagnosticsEnabled = rtspPacketDiagnosticsEnabled;
    payloadReaderDiscontinuityNotificationsEnabled =
        rtcpFeedbackPolicy.sequenceGapRequestThreshold > 0
            || rtcpFeedbackPolicy.requestKeyFrameOnQueueReset
            || rtspBacklogRecoveryPolicy.isEnabled();

    payloadReader =
        checkNotNull(
            new DefaultRtpPayloadReaderFactory(
                    rtspDiagnosticsListener,
                    this.rtcpFeedbackRequester,
                    rtcpFeedbackPolicy,
                    rtspBacklogRecoveryPolicy,
                    rtspPacketDiagnosticsEnabled)
                .createPayloadReader(payloadFormat));
    rtpPacketScratchBuffer = new ParsableByteArray(RtpPacket.MAX_SIZE);
    rtpPacketDataBuffer = new ParsableByteArray();
    lock = new Object();
    reorderingQueue =
        new RtpPacketReorderingQueue(
            trackId,
            transportMode,
            rtspDiagnosticsListener,
            this.rtcpFeedbackRequester,
            rtcpFeedbackPolicy.sequenceGapRequestThreshold,
            rtcpFeedbackPolicy.requestKeyFrameOnQueueReset,
            rtspBacklogRecoveryPolicy,
            rtspPacketDiagnosticsEnabled,
            rtcpFeedbackPolicy);
    firstTimestamp = C.TIME_UNSET;
    firstSequenceNumber = C.INDEX_UNSET;
    lastSsrc = C.INDEX_UNSET;
    lastExtractorReadElapsedRealtimeMs = C.TIME_UNSET;
    nextRtpTimestamp = C.TIME_UNSET;
    playbackStartTimeUs = C.TIME_UNSET;
  }

  /** Sets the timestamp of the first RTP packet to arrive. */
  public void setFirstTimestamp(long firstTimestamp) {
    this.firstTimestamp = firstTimestamp;
  }

  /** Sets the sequence number of the first RTP packet to arrive. */
  public void setFirstSequenceNumber(int firstSequenceNumber) {
    this.firstSequenceNumber = firstSequenceNumber;
  }

  /** Returns whether the first RTP packet is processed. */
  public boolean hasReadFirstRtpPacket() {
    return firstPacketRead;
  }

  /** Returns the latest RTP SSRC seen by this extractor, or {@link C#INDEX_UNSET}. */
  public int getLastSsrc() {
    return lastSsrc;
  }

  /**
   * Signals when performing an RTSP seek that involves RTSP message exchange.
   *
   * <p>{@link #seek} must be called after a successful RTSP seek.
   *
   * <p>After this method in called, the incoming RTP packets are read from the {@link
   * ExtractorInput}, but they are not further processed by the {@link RtpPayloadReader readers}.
   *
   * <p>The user must clear the {@link ExtractorOutput} after calling this method, to ensure no
   * samples are written to {@link ExtractorOutput}.
   */
  public void preSeek() {
    synchronized (lock) {
      isSeekPending = true;
    }
  }

  @Override
  public boolean sniff(ExtractorInput input) {
    throw new UnsupportedOperationException(
        "RTP packets are transmitted in a packet stream do not support sniffing.");
  }

  @Override
  public void init(ExtractorOutput output) {
    payloadReader.createTracks(output, trackId);
    output.endTracks();
    // RTP does not embed duration or seek info.
    output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
    this.output = output;
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    checkNotNull(output); // Asserts init is called.

    // Reads one RTP packet at a time.
    int bytesRead = input.read(rtpPacketScratchBuffer.getData(), 0, RtpPacket.MAX_SIZE);
    if (bytesRead == C.RESULT_END_OF_INPUT) {
      return Extractor.RESULT_END_OF_INPUT;
    } else if (bytesRead == 0) {
      return Extractor.RESULT_CONTINUE;
    }

    rtpPacketScratchBuffer.setPosition(0);
    rtpPacketScratchBuffer.setLimit(bytesRead);
    @Nullable RtpPacket packet = RtpPacket.parse(rtpPacketScratchBuffer);
    if (packet == null) {
      return RESULT_CONTINUE;
    }
    lastSsrc = packet.ssrc;

    long packetArrivalTimeMs = SystemClock.elapsedRealtime();
    long extractorReadStallMs = 0;
    if (rtspDiagnosticsListener != null
        && rtspBacklogRecoveryPolicy.isRtpReorderBacklogRecoveryEnabled()) {
      if (lastExtractorReadElapsedRealtimeMs != C.TIME_UNSET) {
        extractorReadStallMs = Math.max(0, packetArrivalTimeMs - lastExtractorReadElapsedRealtimeMs);
      }
      lastExtractorReadElapsedRealtimeMs = packetArrivalTimeMs;
    }
    long packetCutoffTimeMs = getCutoffTimeMs(packetArrivalTimeMs);
    boolean emitPacketDiagnostics =
        rtspDiagnosticsListener != null && rtspPacketDiagnosticsEnabled;
    @Nullable RtpPacketStats parsedPacketStats =
        emitPacketDiagnostics ? createPacketStats(packet, packetArrivalTimeMs) : null;
    if (emitPacketDiagnostics) {
      rtspDiagnosticsListener.onRtpPacketReceived(checkNotNull(parsedPacketStats));
    }
    if (!reorderingQueue.offer(packet, packetArrivalTimeMs, extractorReadStallMs)) {
      if (emitPacketDiagnostics) {
        rtspDiagnosticsListener.onRtpPacketDropped(
            checkNotNull(parsedPacketStats), reorderingQueue.createStats(/* sequenceGap= */ 0));
      }
      return RESULT_CONTINUE;
    }
    if (payloadReaderDiscontinuityNotificationsEnabled) {
      int discontinuityReason = reorderingQueue.getAndClearPendingDiscontinuityReason();
      if (discontinuityReason != RtcpFeedbackReason.UNKNOWN) {
        onRtpStreamDiscontinuity(discontinuityReason);
      }
    }
    @Nullable RtpPacket dequeuedPacket = reorderingQueue.poll(packetCutoffTimeMs);
    if (dequeuedPacket == null) {
      // No packet is available for reading.
      return RESULT_CONTINUE;
    }
    if (payloadReaderDiscontinuityNotificationsEnabled) {
      int discontinuityReason = reorderingQueue.getAndClearPendingDiscontinuityReason();
      if (discontinuityReason != RtcpFeedbackReason.UNKNOWN) {
        onRtpStreamDiscontinuity(discontinuityReason);
      }
    }
    packet = dequeuedPacket;

    if (!firstPacketRead) {
      // firstTimestamp and firstSequenceNumber are transmitted over RTSP. There is no guarantee
      // that they arrive before the RTP packets. We use whichever comes first.
      if (firstTimestamp == C.TIME_UNSET) {
        firstTimestamp = packet.timestamp;
      }
      if (firstSequenceNumber == C.INDEX_UNSET) {
        firstSequenceNumber = packet.sequenceNumber;
      }
      if (rtspDiagnosticsListener == null) {
        payloadReader.onReceivingFirstPacket(firstTimestamp, firstSequenceNumber);
      } else {
        payloadReader.onReceivingFirstPacket(
            firstTimestamp, firstSequenceNumber, packetArrivalTimeMs);
      }
      firstPacketRead = true;
      if (rtspDiagnosticsListener != null) {
        rtspDiagnosticsListener.onFirstRtpPacketReceived(
            createPacketStats(packet, packetArrivalTimeMs));
      }
    }

    synchronized (lock) {
      // Ignores the incoming packets while seek is pending.
      if (isSeekPending) {
        if (nextRtpTimestamp != C.TIME_UNSET && playbackStartTimeUs != C.TIME_UNSET) {
          reorderingQueue.reset();
          payloadReader.seek(nextRtpTimestamp, playbackStartTimeUs);
          isSeekPending = false;
          nextRtpTimestamp = C.TIME_UNSET;
          playbackStartTimeUs = C.TIME_UNSET;
        }
      } else {
        do {
          // Deplete the reordering queue as much as possible.
          if (emitPacketDiagnostics) {
            rtspDiagnosticsListener.onRtpPacketDequeued(
                createPacketStats(packet, packetArrivalTimeMs),
                reorderingQueue.createStats(/* sequenceGap= */ 0));
          }
          rtpPacketDataBuffer.reset(packet.payloadData);
          payloadReader.consume(
              rtpPacketDataBuffer, packet.timestamp, packet.sequenceNumber, packet.marker);
          packet = reorderingQueue.poll(packetCutoffTimeMs);
        } while (packet != null);
      }
    }
    return RESULT_CONTINUE;
  }

  public void onRtpStreamDiscontinuity(@RtcpFeedbackReason.Reason int reason) {
    if (payloadReaderDiscontinuityNotificationsEnabled) {
      payloadReader.onRtpStreamDiscontinuity(reason);
    }
  }

  @Override
  public void seek(long nextRtpTimestamp, long playbackStartTimeUs) {
    synchronized (lock) {
      if (!isSeekPending) {
        // Sets the isSeekPending flag, in the case preSeek() is not called, when seeking does not
        // require RTSP message exchange. For example, playing back with non-zero start position.
        isSeekPending = true;
      }
      this.nextRtpTimestamp = nextRtpTimestamp;
      this.playbackStartTimeUs = playbackStartTimeUs;
    }
  }

  @Override
  public void release() {
    // Do nothing.
  }

  /**
   * Returns the cutoff time of waiting for an out-of-order packet.
   *
   * <p>Returns the cutoff time to pass to {@link RtpPacketReorderingQueue#poll(long)} based on the
   * given RtpPacket arrival time.
   */
  private long getCutoffTimeMs(long packetArrivalTimeMs) {
    return packetArrivalTimeMs - rtpReorderWaitMs;
  }

  /* package */ long getRtpReorderWaitMsForTesting() {
    return rtpReorderWaitMs;
  }

  private RtpPacketStats createPacketStats(RtpPacket packet, long packetArrivalTimeMs) {
    return new RtpPacketStats(
        trackId,
        transportMode,
        packet.payloadType & 0xFF,
        packet.sequenceNumber,
        packet.timestamp,
        packetArrivalTimeMs,
        packet.ssrc,
        packet.marker);
  }
}
