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
import com.google.android.exoplayer2.util.Util;

/** Diagnostics snapshot for H.264 low-latency recovery state. */
public final class RtspH264RecoveryStats {

  public final int trackId;
  public final int rtpSequenceNumber;
  public final long rtpTimestamp;
  public final boolean waitingForIdr;
  public final int corruptedAccessUnitCount;
  public final int droppedUntilIdrCount;
  public final @RtcpFeedbackReason.Reason int reason;
  public final long waitingForIdrDurationMs;
  public final int lastRtpSequence;
  public final long lastRtpTimestamp;
  public final int idrRecoveredCount;

  public RtspH264RecoveryStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      boolean waitingForIdr,
      int corruptedAccessUnitCount,
      int droppedUntilIdrCount,
      @RtcpFeedbackReason.Reason int reason) {
    this(
        trackId,
        rtpSequenceNumber,
        rtpTimestamp,
        waitingForIdr,
        corruptedAccessUnitCount,
        droppedUntilIdrCount,
        reason,
        /* waitingForIdrDurationMs= */ 0,
        rtpSequenceNumber,
        rtpTimestamp,
        /* idrRecoveredCount= */ 0);
  }

  public RtspH264RecoveryStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      boolean waitingForIdr,
      int corruptedAccessUnitCount,
      int droppedUntilIdrCount,
      @RtcpFeedbackReason.Reason int reason,
      long waitingForIdrDurationMs,
      int lastRtpSequence,
      long lastRtpTimestamp,
      int idrRecoveredCount) {
    this.trackId = trackId;
    this.rtpSequenceNumber = rtpSequenceNumber;
    this.rtpTimestamp = rtpTimestamp;
    this.waitingForIdr = waitingForIdr;
    this.corruptedAccessUnitCount = corruptedAccessUnitCount;
    this.droppedUntilIdrCount = droppedUntilIdrCount;
    this.reason = reason;
    this.waitingForIdrDurationMs = waitingForIdrDurationMs;
    this.lastRtpSequence = lastRtpSequence;
    this.lastRtpTimestamp = lastRtpTimestamp;
    this.idrRecoveredCount = idrRecoveredCount;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspH264RecoveryStats)) {
      return false;
    }
    RtspH264RecoveryStats other = (RtspH264RecoveryStats) obj;
    return trackId == other.trackId
        && rtpSequenceNumber == other.rtpSequenceNumber
        && rtpTimestamp == other.rtpTimestamp
        && waitingForIdr == other.waitingForIdr
        && corruptedAccessUnitCount == other.corruptedAccessUnitCount
        && droppedUntilIdrCount == other.droppedUntilIdrCount
        && reason == other.reason
        && waitingForIdrDurationMs == other.waitingForIdrDurationMs
        && lastRtpSequence == other.lastRtpSequence
        && lastRtpTimestamp == other.lastRtpTimestamp
        && idrRecoveredCount == other.idrRecoveredCount;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + rtpSequenceNumber;
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + (waitingForIdr ? 1 : 0);
    result = 31 * result + corruptedAccessUnitCount;
    result = 31 * result + droppedUntilIdrCount;
    result = 31 * result + reason;
    result = 31 * result + (int) (waitingForIdrDurationMs ^ (waitingForIdrDurationMs >>> 32));
    result = 31 * result + lastRtpSequence;
    result = 31 * result + (int) (lastRtpTimestamp ^ (lastRtpTimestamp >>> 32));
    result = 31 * result + idrRecoveredCount;
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspH264RecoveryStats(trackId=%d, rtpSequenceNumber=%d, rtpTimestamp=%d, "
            + "waitingForIdr=%b, corruptedAccessUnitCount=%d, droppedUntilIdrCount=%d, "
            + "reason=%d, waitingForIdrDurationMs=%d, lastRtpSequence=%d, "
            + "lastRtpTimestamp=%d, idrRecoveredCount=%d)",
        trackId,
        rtpSequenceNumber,
        rtpTimestamp,
        waitingForIdr,
        corruptedAccessUnitCount,
        droppedUntilIdrCount,
        reason,
        waitingForIdrDurationMs,
        lastRtpSequence,
        lastRtpTimestamp,
        idrRecoveredCount);
  }
}
