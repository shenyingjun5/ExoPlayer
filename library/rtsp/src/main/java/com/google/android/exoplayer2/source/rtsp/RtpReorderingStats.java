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

import com.google.android.exoplayer2.util.Util;

/** Diagnostics snapshot for the RTP packet reordering queue. */
public final class RtpReorderingStats {

  public final int trackId;
  public final @RtspTransportMode.Mode int transportMode;
  public final int queueDepth;
  public final int lastReceivedSequenceNumber;
  public final int lastDequeuedSequenceNumber;
  public final int sequenceGap;
  public final int droppedBeforeEnqueueCount;
  public final int duplicatePacketCount;
  public final int resetCount;
  public final long oldestPacketAgeMs;
  public final long queueSpanMs;
  /** Number of sequence positions committed by dequeue, including confirmed missing positions. */
  public final long expectedPacketCount;
  /** Number of parsed RTP packets offered to the reordering queue. */
  public final long receivedPacketCount;
  /** Number of missing sequence positions confirmed after the reordering deadline. */
  public final long missingPacketCount;
  /** Number of packets received at or behind the already dequeued sequence position. */
  public final long latePacketCount;
  /** Number of confirmed sequence gap events after the reordering deadline. */
  public final long sequenceGapEventCount;
  /** Largest confirmed sequence gap size. */
  public final int maxGapSize;
  /** Expected sequence number for the most recent confirmed sequence gap. */
  public final int lastGapExpectedSequence;
  /** Actual sequence number for the most recent confirmed sequence gap. */
  public final int lastGapActualSequence;

  public RtpReorderingStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int queueDepth,
      int lastReceivedSequenceNumber,
      int lastDequeuedSequenceNumber,
      int sequenceGap,
      int droppedBeforeEnqueueCount,
      int duplicatePacketCount,
      int resetCount) {
    this(
        trackId,
        transportMode,
        queueDepth,
        lastReceivedSequenceNumber,
        lastDequeuedSequenceNumber,
        sequenceGap,
        droppedBeforeEnqueueCount,
        duplicatePacketCount,
        resetCount,
        /* oldestPacketAgeMs= */ 0,
        /* queueSpanMs= */ 0,
        /* expectedPacketCount= */ 0,
        /* receivedPacketCount= */ 0,
        /* missingPacketCount= */ 0,
        /* latePacketCount= */ 0,
        /* sequenceGapEventCount= */ 0,
        /* maxGapSize= */ 0,
        /* lastGapExpectedSequence= */ -1,
        /* lastGapActualSequence= */ -1);
  }

  public RtpReorderingStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int queueDepth,
      int lastReceivedSequenceNumber,
      int lastDequeuedSequenceNumber,
      int sequenceGap,
      int droppedBeforeEnqueueCount,
      int duplicatePacketCount,
      int resetCount,
      long oldestPacketAgeMs,
      long queueSpanMs) {
    this(
        trackId,
        transportMode,
        queueDepth,
        lastReceivedSequenceNumber,
        lastDequeuedSequenceNumber,
        sequenceGap,
        droppedBeforeEnqueueCount,
        duplicatePacketCount,
        resetCount,
        oldestPacketAgeMs,
        queueSpanMs,
        /* expectedPacketCount= */ 0,
        /* receivedPacketCount= */ 0,
        /* missingPacketCount= */ 0,
        /* latePacketCount= */ 0,
        /* sequenceGapEventCount= */ 0,
        /* maxGapSize= */ 0,
        /* lastGapExpectedSequence= */ -1,
        /* lastGapActualSequence= */ -1);
  }

  public RtpReorderingStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int queueDepth,
      int lastReceivedSequenceNumber,
      int lastDequeuedSequenceNumber,
      int sequenceGap,
      int droppedBeforeEnqueueCount,
      int duplicatePacketCount,
      int resetCount,
      long oldestPacketAgeMs,
      long queueSpanMs,
      long expectedPacketCount,
      long receivedPacketCount,
      long missingPacketCount,
      long latePacketCount,
      long sequenceGapEventCount,
      int maxGapSize,
      int lastGapExpectedSequence,
      int lastGapActualSequence) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.queueDepth = queueDepth;
    this.lastReceivedSequenceNumber = lastReceivedSequenceNumber;
    this.lastDequeuedSequenceNumber = lastDequeuedSequenceNumber;
    this.sequenceGap = sequenceGap;
    this.droppedBeforeEnqueueCount = droppedBeforeEnqueueCount;
    this.duplicatePacketCount = duplicatePacketCount;
    this.resetCount = resetCount;
    this.oldestPacketAgeMs = oldestPacketAgeMs;
    this.queueSpanMs = queueSpanMs;
    this.expectedPacketCount = expectedPacketCount;
    this.receivedPacketCount = receivedPacketCount;
    this.missingPacketCount = missingPacketCount;
    this.latePacketCount = latePacketCount;
    this.sequenceGapEventCount = sequenceGapEventCount;
    this.maxGapSize = maxGapSize;
    this.lastGapExpectedSequence = lastGapExpectedSequence;
    this.lastGapActualSequence = lastGapActualSequence;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtpReorderingStats)) {
      return false;
    }
    RtpReorderingStats other = (RtpReorderingStats) obj;
    return trackId == other.trackId
        && transportMode == other.transportMode
        && queueDepth == other.queueDepth
        && lastReceivedSequenceNumber == other.lastReceivedSequenceNumber
        && lastDequeuedSequenceNumber == other.lastDequeuedSequenceNumber
        && sequenceGap == other.sequenceGap
        && droppedBeforeEnqueueCount == other.droppedBeforeEnqueueCount
        && duplicatePacketCount == other.duplicatePacketCount
        && resetCount == other.resetCount
        && oldestPacketAgeMs == other.oldestPacketAgeMs
        && queueSpanMs == other.queueSpanMs
        && expectedPacketCount == other.expectedPacketCount
        && receivedPacketCount == other.receivedPacketCount
        && missingPacketCount == other.missingPacketCount
        && latePacketCount == other.latePacketCount
        && sequenceGapEventCount == other.sequenceGapEventCount
        && maxGapSize == other.maxGapSize
        && lastGapExpectedSequence == other.lastGapExpectedSequence
        && lastGapActualSequence == other.lastGapActualSequence;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + transportMode;
    result = 31 * result + queueDepth;
    result = 31 * result + lastReceivedSequenceNumber;
    result = 31 * result + lastDequeuedSequenceNumber;
    result = 31 * result + sequenceGap;
    result = 31 * result + droppedBeforeEnqueueCount;
    result = 31 * result + duplicatePacketCount;
    result = 31 * result + resetCount;
    result = 31 * result + (int) (oldestPacketAgeMs ^ (oldestPacketAgeMs >>> 32));
    result = 31 * result + (int) (queueSpanMs ^ (queueSpanMs >>> 32));
    result = 31 * result + (int) (expectedPacketCount ^ (expectedPacketCount >>> 32));
    result = 31 * result + (int) (receivedPacketCount ^ (receivedPacketCount >>> 32));
    result = 31 * result + (int) (missingPacketCount ^ (missingPacketCount >>> 32));
    result = 31 * result + (int) (latePacketCount ^ (latePacketCount >>> 32));
    result = 31 * result + (int) (sequenceGapEventCount ^ (sequenceGapEventCount >>> 32));
    result = 31 * result + maxGapSize;
    result = 31 * result + lastGapExpectedSequence;
    result = 31 * result + lastGapActualSequence;
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtpReorderingStats(trackId=%d, transportMode=%d, queueDepth=%d, "
            + "lastReceivedSequenceNumber=%d, lastDequeuedSequenceNumber=%d, sequenceGap=%d, "
            + "droppedBeforeEnqueueCount=%d, duplicatePacketCount=%d, resetCount=%d, "
            + "oldestPacketAgeMs=%d, queueSpanMs=%d, expectedPacketCount=%d, "
            + "receivedPacketCount=%d, missingPacketCount=%d, latePacketCount=%d, "
            + "sequenceGapEventCount=%d, maxGapSize=%d, "
            + "lastGapExpectedSequence=%d, lastGapActualSequence=%d)",
        trackId,
        transportMode,
        queueDepth,
        lastReceivedSequenceNumber,
        lastDequeuedSequenceNumber,
        sequenceGap,
        droppedBeforeEnqueueCount,
        duplicatePacketCount,
        resetCount,
        oldestPacketAgeMs,
        queueSpanMs,
        expectedPacketCount,
        receivedPacketCount,
        missingPacketCount,
        latePacketCount,
        sequenceGapEventCount,
        maxGapSize,
        lastGapExpectedSequence,
        lastGapActualSequence);
  }
}
