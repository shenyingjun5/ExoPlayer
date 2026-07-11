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

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.os.SystemClock;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import java.util.TreeSet;

/**
 * Orders RTP packets by their sequence numbers to correct the possible alternation in packet
 * ordering, introduced by UDP transport.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class RtpPacketReorderingQueue {
  /** The maximum sequence number discontinuity allowed without resetting the re-ordering buffer. */
  @VisibleForTesting /* package */ static final int MAX_SEQUENCE_LEAP_ALLOWED = 1000;

  private static final int SEQUENCE_NUMBER_MODULUS = RtpPacket.MAX_SEQUENCE_NUMBER + 1;

  /** Queue size threshold for resetting the queue. 5000 packets equate about 7MB in buffer size. */
  private static final int QUEUE_SIZE_THRESHOLD_FOR_RESET = 5000;

  // Use set to eliminate duplicating packets.
  @GuardedBy("this")
  private final TreeSet<RtpPacketContainer> packetQueue;
  private final int trackId;
  private final @RtspTransportMode.Mode int transportMode;
  @Nullable private final RtspDiagnosticsListener rtspDiagnosticsListener;
  @Nullable private final RtcpFeedbackRequester rtcpFeedbackRequester;
  private final int sequenceGapRequestThreshold;
  private final boolean requestKeyFrameOnQueueReset;
  private final RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy;
  private final boolean collectBacklogResetDiagnostics;

  @GuardedBy("this")
  private int lastReceivedSequenceNumber;

  @GuardedBy("this")
  private int lastDequeuedSequenceNumber;

  @GuardedBy("this")
  private boolean started;

  @GuardedBy("this")
  private int droppedBeforeEnqueueCount;

  @GuardedBy("this")
  private int duplicatePacketCount;

  @GuardedBy("this")
  private int resetCount;

  private volatile @RtcpFeedbackReason.Reason int lastOfferDiscontinuityReason;

  @GuardedBy("this")
  private long lastReceivedTimestampMs;

  @GuardedBy("this")
  private long recentPacketInterArrivalMaxMs;

  @GuardedBy("this")
  private long extractorReadStallMs;

  /** Creates an instance. */
  public RtpPacketReorderingQueue() {
    this(
        /* trackId= */ C.INDEX_UNSET,
        RtspTransportMode.UNKNOWN,
        /* rtspDiagnosticsListener= */ null,
        /* rtcpFeedbackRequester= */ null,
        /* sequenceGapRequestThreshold= */ 0,
        /* requestKeyFrameOnQueueReset= */ false,
        RtspBacklogRecoveryPolicy.DISABLED);
  }

  /** Creates an instance. */
  public RtpPacketReorderingQueue(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtcpFeedbackRequester rtcpFeedbackRequester,
      int sequenceGapRequestThreshold,
      boolean requestKeyFrameOnQueueReset) {
    this(
        trackId,
        transportMode,
        rtspDiagnosticsListener,
        rtcpFeedbackRequester,
        sequenceGapRequestThreshold,
        requestKeyFrameOnQueueReset,
        RtspBacklogRecoveryPolicy.DISABLED);
  }

  /** Creates an instance. */
  public RtpPacketReorderingQueue(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtcpFeedbackRequester rtcpFeedbackRequester,
      int sequenceGapRequestThreshold,
      boolean requestKeyFrameOnQueueReset,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.rtcpFeedbackRequester = rtcpFeedbackRequester;
    this.sequenceGapRequestThreshold = sequenceGapRequestThreshold;
    this.requestKeyFrameOnQueueReset = requestKeyFrameOnQueueReset;
    this.rtspBacklogRecoveryPolicy = rtspBacklogRecoveryPolicy;
    collectBacklogResetDiagnostics =
        rtspDiagnosticsListener != null && rtspBacklogRecoveryPolicy.isRtpReorderBacklogRecoveryEnabled();
    packetQueue =
        new TreeSet<>(
            (packetContainer1, packetContainer2) ->
                calculateSequenceNumberShift(
                    packetContainer1.packet.sequenceNumber,
                    packetContainer2.packet.sequenceNumber));

    reset(/* notifyDiagnostics= */ false);
  }

  public synchronized void reset() {
    reset(/* notifyDiagnostics= */ true);
  }

  private synchronized void reset(boolean notifyDiagnostics) {
    packetQueue.clear();
    lastOfferDiscontinuityReason = RtcpFeedbackReason.UNKNOWN;
    lastReceivedTimestampMs = C.TIME_UNSET;
    recentPacketInterArrivalMaxMs = 0;
    extractorReadStallMs = 0;
    started = false;
    lastDequeuedSequenceNumber = C.INDEX_UNSET;
    lastReceivedSequenceNumber = C.INDEX_UNSET;
    if (notifyDiagnostics) {
      resetCount++;
      if (rtspDiagnosticsListener != null) {
        rtspDiagnosticsListener.onRtpReorderingQueueReset(createStats(/* sequenceGap= */ 0));
      }
    }
  }

  /**
   * Offer one packet to the reordering queue.
   *
   * <p>A packet will not be added to the queue, if a logically preceding packet has already been
   * dequeued.
   *
   * <p>If a packet creates a shift in sequence number that is at least {@link
   * #MAX_SEQUENCE_LEAP_ALLOWED} compared to the last offered packet, the queue is emptied and then
   * the packet is added.
   *
   * @param packet The packet to add.
   * @param receivedTimestampMs The timestamp in milliseconds, at which the packet was received.
   * @return Returns {@code false} if the packet was dropped because it was outside the expected
   *     range of accepted packets, otherwise {@code true} (on duplicated packets, this method
   *     returns {@code true}).
   */
  public synchronized boolean offer(RtpPacket packet, long receivedTimestampMs) {
    return offer(packet, receivedTimestampMs, /* extractorReadStallMs= */ 0);
  }

  /** Offers one packet with a low-frequency extractor-read stall sample for reset diagnostics. */
  public synchronized boolean offer(
      RtpPacket packet, long receivedTimestampMs, long extractorReadStallMs) {
    lastOfferDiscontinuityReason = RtcpFeedbackReason.UNKNOWN;
    if (collectBacklogResetDiagnostics) {
      this.extractorReadStallMs = Math.max(this.extractorReadStallMs, extractorReadStallMs);
    }
    if (packetQueue.size() >= QUEUE_SIZE_THRESHOLD_FOR_RESET) {
      throw new IllegalStateException(
          "Queue size limit of " + QUEUE_SIZE_THRESHOLD_FOR_RESET + " reached.");
    }

    int packetSequenceNumber = packet.sequenceNumber;
    if (!started) {
      reset(/* notifyDiagnostics= */ false);
      lastDequeuedSequenceNumber = RtpPacket.getPreviousSequenceNumber(packetSequenceNumber);
      started = true;
      addToQueue(new RtpPacketContainer(packet, receivedTimestampMs));
      return true;
    }

    int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(lastReceivedSequenceNumber);
    // A positive shift means the packet succeeds the last received packet.
    int sequenceNumberShift =
        calculateSequenceNumberShift(packetSequenceNumber, expectedSequenceNumber);
    if (abs(sequenceNumberShift) < MAX_SEQUENCE_LEAP_ALLOWED) {
      if (sequenceNumberShift >= sequenceGapRequestThreshold && sequenceGapRequestThreshold > 0) {
        lastOfferDiscontinuityReason = RtcpFeedbackReason.SEQUENCE_GAP;
        if (rtcpFeedbackRequester != null) {
          rtcpFeedbackRequester.requestKeyFrame(RtcpFeedbackReason.SEQUENCE_GAP);
        }
      }
      if (calculateSequenceNumberShift(packetSequenceNumber, lastDequeuedSequenceNumber) > 0) {
        // Add the packet in the queue only if a succeeding packet has not been dequeued already.
        RtpPacketContainer packetContainer = new RtpPacketContainer(packet, receivedTimestampMs);
        addToQueue(packetContainer);
        maybeResetQueueForBacklog(packetContainer);
        return true;
      }
    } else {
      // Discard all previous received packets and start subsequent receiving from here.
      resetCount++;
      lastDequeuedSequenceNumber = RtpPacket.getPreviousSequenceNumber(packetSequenceNumber);
      packetQueue.clear();
      addToQueue(new RtpPacketContainer(packet, receivedTimestampMs));
      if (rtspDiagnosticsListener != null) {
        rtspDiagnosticsListener.onRtpReorderingQueueReset(createStats(sequenceNumberShift));
      }
      if (requestKeyFrameOnQueueReset) {
        lastOfferDiscontinuityReason = RtcpFeedbackReason.QUEUE_RESET;
        if (rtcpFeedbackRequester != null) {
          rtcpFeedbackRequester.requestKeyFrame(RtcpFeedbackReason.QUEUE_RESET);
        }
      }
      return true;
    }
    droppedBeforeEnqueueCount++;
    return false;
  }

  public @RtcpFeedbackReason.Reason int getLastOfferDiscontinuityReason() {
    return lastOfferDiscontinuityReason;
  }

  /**
   * Polls an {@link RtpPacket} from the queue.
   *
   * @param cutoffTimestampMs A cutoff timestamp in milliseconds used to determine if the head of
   *     the queue should be dequeued, even if it's not the next packet in sequence.
   * @return Returns a packet if the packet at the queue head is the next packet in sequence; or its
   *     {@link #offer received} timestamp is before {@code cutoffTimestampMs}. Otherwise {@code
   *     null}.
   */
  @Nullable
  public synchronized RtpPacket poll(long cutoffTimestampMs) {
    if (packetQueue.isEmpty()) {
      return null;
    }

    RtpPacketContainer packetContainer = packetQueue.first();
    int packetSequenceNumber = packetContainer.packet.sequenceNumber;

    if (packetSequenceNumber == RtpPacket.getNextSequenceNumber(lastDequeuedSequenceNumber)
        || cutoffTimestampMs >= packetContainer.receivedTimestampMs) {
      packetQueue.pollFirst();
      lastDequeuedSequenceNumber = packetSequenceNumber;
      return packetContainer.packet;
    }

    return null;
  }

  // Internals.

  public synchronized RtpReorderingStats createStats(int sequenceGap) {
    return new RtpReorderingStats(
        trackId,
        transportMode,
        packetQueue.size(),
        lastReceivedSequenceNumber,
        lastDequeuedSequenceNumber,
        sequenceGap,
        droppedBeforeEnqueueCount,
        duplicatePacketCount,
        resetCount,
        getOldestPacketAgeMs(),
        getQueueSpanMs());
  }

  private synchronized void addToQueue(RtpPacketContainer packet) {
    if (collectBacklogResetDiagnostics && lastReceivedTimestampMs != C.TIME_UNSET) {
      recentPacketInterArrivalMaxMs =
          Math.max(
              recentPacketInterArrivalMaxMs,
              Math.max(0, packet.receivedTimestampMs - lastReceivedTimestampMs));
    }
    lastReceivedSequenceNumber = packet.packet.sequenceNumber;
    lastReceivedTimestampMs = packet.receivedTimestampMs;
    if (!packetQueue.add(packet)) {
      duplicatePacketCount++;
    }
  }

  private void maybeResetQueueForBacklog(RtpPacketContainer latestPacket) {
    if (!rtspBacklogRecoveryPolicy.isRtpReorderBacklogRecoveryEnabled()) {
      return;
    }
    int queueDepth = packetQueue.size();
    long oldestPacketAgeMs = getOldestPacketAgeMs();
    long queueSpanMs = getQueueSpanMs();
    if (!shouldResetQueueForBacklog(queueDepth, oldestPacketAgeMs, queueSpanMs)) {
      return;
    }
    resetCount++;
    int droppedPacketCount = Math.max(0, queueDepth - 1);
    int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(lastDequeuedSequenceNumber);
    int lastDequeuedSequenceNumberAtReset = lastDequeuedSequenceNumber;
    int lastQueuedSequenceNumberAtReset = lastReceivedSequenceNumber;
    packetQueue.clear();
    lastDequeuedSequenceNumber =
        RtpPacket.getPreviousSequenceNumber(latestPacket.packet.sequenceNumber);
    packetQueue.add(latestPacket);
    lastReceivedSequenceNumber = latestPacket.packet.sequenceNumber;
    lastReceivedTimestampMs = latestPacket.receivedTimestampMs;
    lastOfferDiscontinuityReason = RtcpFeedbackReason.QUEUE_RESET;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onRtpReorderingQueueReset(createStats(/* sequenceGap= */ 0));
      rtspDiagnosticsListener.onRtspBacklogQueueReset(
          new RtspBacklogRecoveryStats(
              trackId,
              transportMode,
              RtcpFeedbackReason.QUEUE_RESET,
              queueDepth,
              droppedPacketCount,
              oldestPacketAgeMs,
              queueSpanMs,
              SystemClock.elapsedRealtime(),
              expectedSequenceNumber,
              latestPacket.packet.sequenceNumber,
              lastDequeuedSequenceNumberAtReset,
              lastQueuedSequenceNumberAtReset,
              recentPacketInterArrivalMaxMs,
              extractorReadStallMs));
    }
    recentPacketInterArrivalMaxMs = 0;
    extractorReadStallMs = 0;
    if (requestKeyFrameOnQueueReset && rtcpFeedbackRequester != null) {
      rtcpFeedbackRequester.requestKeyFrame(RtcpFeedbackReason.QUEUE_RESET);
    }
  }

  private boolean shouldResetQueueForBacklog(
      int queueDepth, long oldestPacketAgeMs, long queueSpanMs) {
    return (rtspBacklogRecoveryPolicy.maxRtpReorderQueueDepth > 0
            && queueDepth >= rtspBacklogRecoveryPolicy.maxRtpReorderQueueDepth)
        || (rtspBacklogRecoveryPolicy.maxRtpReorderQueueAgeMs > 0
            && oldestPacketAgeMs >= rtspBacklogRecoveryPolicy.maxRtpReorderQueueAgeMs)
        || (rtspBacklogRecoveryPolicy.maxRtpReorderQueueSpanMs > 0
            && queueSpanMs >= rtspBacklogRecoveryPolicy.maxRtpReorderQueueSpanMs);
  }

  private long getOldestPacketAgeMs() {
    return packetQueue.isEmpty()
        ? 0
        : Math.max(0, SystemClock.elapsedRealtime() - packetQueue.first().receivedTimestampMs);
  }

  private long getQueueSpanMs() {
    return packetQueue.isEmpty() || lastReceivedTimestampMs == C.TIME_UNSET
        ? 0
        : Math.max(0, lastReceivedTimestampMs - packetQueue.first().receivedTimestampMs);
  }

  private static final class RtpPacketContainer {
    public final RtpPacket packet;
    public final long receivedTimestampMs;

    /** Creates an instance. */
    public RtpPacketContainer(RtpPacket packet, long receivedTimestampMs) {
      this.packet = packet;
      this.receivedTimestampMs = receivedTimestampMs;
    }
  }

  /**
   * Calculates the sequence number shift, accounting for wrapping around.
   *
   * @param sequenceNumber The currently received sequence number.
   * @param previousSequenceNumber The previous sequence number to compare against.
   * @return The shift in the sequence numbers. A positive shift indicates that {@code
   *     sequenceNumber} is logically after {@code previousSequenceNumber}, whereas a negative shift
   *     means that {@code sequenceNumber} is logically before {@code previousSequenceNumber}.
   */
  private static int calculateSequenceNumberShift(int sequenceNumber, int previousSequenceNumber) {
    int sequenceShift = sequenceNumber - previousSequenceNumber;
    if (abs(sequenceShift) > MAX_SEQUENCE_LEAP_ALLOWED) {
      int shift =
          min(sequenceNumber, previousSequenceNumber)
              - max(sequenceNumber, previousSequenceNumber)
              + SEQUENCE_NUMBER_MODULUS;
      // Check whether this is actually an wrap-over. For example, it is a wrap around if receiving
      // 65500 (prevSequenceNumber) after 1 (sequenceNumber); but it is not when prevSequenceNumber
      // is 30000.
      if (shift < MAX_SEQUENCE_LEAP_ALLOWED) {
        return sequenceNumber < previousSequenceNumber
            ? /* receiving 65000 (curr) then 1 (prev) */ shift
            : /* receiving 1 (curr) then 65500 (prev) */ -shift;
      }
    }
    return sequenceShift;
  }
}
