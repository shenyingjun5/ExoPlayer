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
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.queueDepth = queueDepth;
    this.lastReceivedSequenceNumber = lastReceivedSequenceNumber;
    this.lastDequeuedSequenceNumber = lastDequeuedSequenceNumber;
    this.sequenceGap = sequenceGap;
    this.droppedBeforeEnqueueCount = droppedBeforeEnqueueCount;
    this.duplicatePacketCount = duplicatePacketCount;
    this.resetCount = resetCount;
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
        && resetCount == other.resetCount;
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
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtpReorderingStats(trackId=%d, transportMode=%d, queueDepth=%d, "
            + "lastReceivedSequenceNumber=%d, lastDequeuedSequenceNumber=%d, sequenceGap=%d, "
            + "droppedBeforeEnqueueCount=%d, duplicatePacketCount=%d, resetCount=%d)",
        trackId,
        transportMode,
        queueDepth,
        lastReceivedSequenceNumber,
        lastDequeuedSequenceNumber,
        sequenceGap,
        droppedBeforeEnqueueCount,
        duplicatePacketCount,
        resetCount);
  }
}
