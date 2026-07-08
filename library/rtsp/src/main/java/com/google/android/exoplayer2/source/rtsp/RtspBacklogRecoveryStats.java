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

  public RtspBacklogRecoveryStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @RtcpFeedbackReason.Reason int reason,
      int queueDepth,
      int droppedPacketCount,
      long oldestPacketAgeMs,
      long queueSpanMs,
      long resetElapsedRealtimeMs) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.reason = reason;
    this.queueDepth = queueDepth;
    this.droppedPacketCount = droppedPacketCount;
    this.oldestPacketAgeMs = oldestPacketAgeMs;
    this.queueSpanMs = queueSpanMs;
    this.resetElapsedRealtimeMs = resetElapsedRealtimeMs;
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
        && resetElapsedRealtimeMs == other.resetElapsedRealtimeMs;
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
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspBacklogRecoveryStats(trackId=%d, transportMode=%d, reason=%d, "
            + "queueDepth=%d, droppedPacketCount=%d, oldestPacketAgeMs=%d, queueSpanMs=%d, "
            + "resetElapsedRealtimeMs=%d)",
        trackId,
        transportMode,
        reason,
        queueDepth,
        droppedPacketCount,
        oldestPacketAgeMs,
        queueSpanMs,
        resetElapsedRealtimeMs);
  }
}
