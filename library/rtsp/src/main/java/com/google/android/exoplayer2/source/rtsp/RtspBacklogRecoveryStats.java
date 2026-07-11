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

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;

/** Statistics for a low-latency RTSP backlog recovery event. */
public final class RtspBacklogRecoveryStats {

  public final int trackId;
  public final @RtspTransportMode.Mode int transportMode;
  public final @RtcpFeedbackReason.Reason int reason;
  public final int queueDepth;
  public final int droppedPacketCount;
  public final long oldestPacketAgeMs;
  public final long queueSpanMs;
  public final long resetElapsedRealtimeMs;
  /** Expected sequence number at the reset trigger, or {@link C#INDEX_UNSET}. */
  public final int expectedSequenceNumber;
  /** Actual sequence number at the reset trigger, or {@link C#INDEX_UNSET}. */
  public final int actualSequenceNumber;
  /** Last dequeued sequence number at the reset trigger, or {@link C#INDEX_UNSET}. */
  public final int lastDequeuedSequenceNumber;
  /** Last queued sequence number at the reset trigger, or {@link C#INDEX_UNSET}. */
  public final int lastQueuedSequenceNumber;
  /** Largest observed packet inter-arrival interval since the prior reset. */
  public final long recentPacketInterArrivalMaxMs;
  /** Largest elapsed interval between extractor reads since the prior reset. */
  public final long extractorReadStallMs;
  /** Whether the TCP data channel consumer was in {@code read()} at the reset trigger. */
  public final boolean dataChannelReadInProgress;
  /** Elapsed duration of the in-progress data-channel read, or {@code 0} when idle. */
  public final long dataChannelReadInProgressMs;
  /** Elapsed duration since the data-channel consumer last completed a read. */
  public final long dataChannelConsumerStallMs;
  /** Name of the last data-channel consumer thread, or {@code null} when unavailable. */
  @Nullable public final String dataChannelReaderThreadName;

  public RtspBacklogRecoveryStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @RtcpFeedbackReason.Reason int reason,
      int queueDepth,
      int droppedPacketCount,
      long oldestPacketAgeMs,
      long queueSpanMs,
      long resetElapsedRealtimeMs) {
    this(
        trackId,
        transportMode,
        reason,
        queueDepth,
        droppedPacketCount,
        oldestPacketAgeMs,
        queueSpanMs,
        resetElapsedRealtimeMs,
        C.INDEX_UNSET,
        C.INDEX_UNSET,
        C.INDEX_UNSET,
        C.INDEX_UNSET,
        /* recentPacketInterArrivalMaxMs= */ 0,
        /* extractorReadStallMs= */ 0,
        /* dataChannelReadInProgress= */ false,
        /* dataChannelReadInProgressMs= */ 0,
        /* dataChannelConsumerStallMs= */ C.TIME_UNSET,
        /* dataChannelReaderThreadName= */ null);
  }

  public RtspBacklogRecoveryStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @RtcpFeedbackReason.Reason int reason,
      int queueDepth,
      int droppedPacketCount,
      long oldestPacketAgeMs,
      long queueSpanMs,
      long resetElapsedRealtimeMs,
      int expectedSequenceNumber,
      int actualSequenceNumber,
      int lastDequeuedSequenceNumber,
      int lastQueuedSequenceNumber,
      long recentPacketInterArrivalMaxMs,
      long extractorReadStallMs) {
    this(
        trackId,
        transportMode,
        reason,
        queueDepth,
        droppedPacketCount,
        oldestPacketAgeMs,
        queueSpanMs,
        resetElapsedRealtimeMs,
        expectedSequenceNumber,
        actualSequenceNumber,
        lastDequeuedSequenceNumber,
        lastQueuedSequenceNumber,
        recentPacketInterArrivalMaxMs,
        extractorReadStallMs,
        /* dataChannelReadInProgress= */ false,
        /* dataChannelReadInProgressMs= */ 0,
        /* dataChannelConsumerStallMs= */ C.TIME_UNSET,
        /* dataChannelReaderThreadName= */ null);
  }

  public RtspBacklogRecoveryStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @RtcpFeedbackReason.Reason int reason,
      int queueDepth,
      int droppedPacketCount,
      long oldestPacketAgeMs,
      long queueSpanMs,
      long resetElapsedRealtimeMs,
      int expectedSequenceNumber,
      int actualSequenceNumber,
      int lastDequeuedSequenceNumber,
      int lastQueuedSequenceNumber,
      long recentPacketInterArrivalMaxMs,
      long extractorReadStallMs,
      boolean dataChannelReadInProgress,
      long dataChannelReadInProgressMs,
      long dataChannelConsumerStallMs,
      @Nullable String dataChannelReaderThreadName) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.reason = reason;
    this.queueDepth = queueDepth;
    this.droppedPacketCount = droppedPacketCount;
    this.oldestPacketAgeMs = oldestPacketAgeMs;
    this.queueSpanMs = queueSpanMs;
    this.resetElapsedRealtimeMs = resetElapsedRealtimeMs;
    this.expectedSequenceNumber = expectedSequenceNumber;
    this.actualSequenceNumber = actualSequenceNumber;
    this.lastDequeuedSequenceNumber = lastDequeuedSequenceNumber;
    this.lastQueuedSequenceNumber = lastQueuedSequenceNumber;
    this.recentPacketInterArrivalMaxMs = recentPacketInterArrivalMaxMs;
    this.extractorReadStallMs = extractorReadStallMs;
    this.dataChannelReadInProgress = dataChannelReadInProgress;
    this.dataChannelReadInProgressMs = dataChannelReadInProgressMs;
    this.dataChannelConsumerStallMs = dataChannelConsumerStallMs;
    this.dataChannelReaderThreadName = dataChannelReaderThreadName;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspBacklogRecoveryStats)) {
      return false;
    }
    RtspBacklogRecoveryStats other = (RtspBacklogRecoveryStats) obj;
    return trackId == other.trackId
        && transportMode == other.transportMode
        && reason == other.reason
        && queueDepth == other.queueDepth
        && droppedPacketCount == other.droppedPacketCount
        && oldestPacketAgeMs == other.oldestPacketAgeMs
        && queueSpanMs == other.queueSpanMs
        && resetElapsedRealtimeMs == other.resetElapsedRealtimeMs
        && expectedSequenceNumber == other.expectedSequenceNumber
        && actualSequenceNumber == other.actualSequenceNumber
        && lastDequeuedSequenceNumber == other.lastDequeuedSequenceNumber
        && lastQueuedSequenceNumber == other.lastQueuedSequenceNumber
        && recentPacketInterArrivalMaxMs == other.recentPacketInterArrivalMaxMs
        && extractorReadStallMs == other.extractorReadStallMs
        && dataChannelReadInProgress == other.dataChannelReadInProgress
        && dataChannelReadInProgressMs == other.dataChannelReadInProgressMs
        && dataChannelConsumerStallMs == other.dataChannelConsumerStallMs
        && Util.areEqual(dataChannelReaderThreadName, other.dataChannelReaderThreadName);
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + transportMode;
    result = 31 * result + reason;
    result = 31 * result + queueDepth;
    result = 31 * result + droppedPacketCount;
    result = 31 * result + (int) (oldestPacketAgeMs ^ (oldestPacketAgeMs >>> 32));
    result = 31 * result + (int) (queueSpanMs ^ (queueSpanMs >>> 32));
    result = 31 * result + (int) (resetElapsedRealtimeMs ^ (resetElapsedRealtimeMs >>> 32));
    result = 31 * result + expectedSequenceNumber;
    result = 31 * result + actualSequenceNumber;
    result = 31 * result + lastDequeuedSequenceNumber;
    result = 31 * result + lastQueuedSequenceNumber;
    result = 31 * result + (int) (recentPacketInterArrivalMaxMs ^ (recentPacketInterArrivalMaxMs >>> 32));
    result = 31 * result + (int) (extractorReadStallMs ^ (extractorReadStallMs >>> 32));
    result = 31 * result + (dataChannelReadInProgress ? 1 : 0);
    result = 31 * result + (int) (dataChannelReadInProgressMs ^ (dataChannelReadInProgressMs >>> 32));
    result = 31 * result + (int) (dataChannelConsumerStallMs ^ (dataChannelConsumerStallMs >>> 32));
    result = 31 * result + (dataChannelReaderThreadName == null ? 0 : dataChannelReaderThreadName.hashCode());
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspBacklogRecoveryStats(trackId=%d, transportMode=%d, reason=%d, "
            + "queueDepth=%d, droppedPacketCount=%d, oldestPacketAgeMs=%d, queueSpanMs=%d, "
            + "resetElapsedRealtimeMs=%d, expectedSequenceNumber=%d, actualSequenceNumber=%d, "
            + "lastDequeuedSequenceNumber=%d, lastQueuedSequenceNumber=%d, "
            + "recentPacketInterArrivalMaxMs=%d, extractorReadStallMs=%d, "
            + "dataChannelReadInProgress=%b, dataChannelReadInProgressMs=%d, "
            + "dataChannelConsumerStallMs=%d, dataChannelReaderThreadName=%s)",
        trackId,
        transportMode,
        reason,
        queueDepth,
        droppedPacketCount,
        oldestPacketAgeMs,
        queueSpanMs,
        resetElapsedRealtimeMs,
        expectedSequenceNumber,
        actualSequenceNumber,
        lastDequeuedSequenceNumber,
        lastQueuedSequenceNumber,
        recentPacketInterArrivalMaxMs,
        extractorReadStallMs,
        dataChannelReadInProgress,
        dataChannelReadInProgressMs,
        dataChannelConsumerStallMs,
        dataChannelReaderThreadName);
  }
}
