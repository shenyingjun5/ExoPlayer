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

/** Low-frequency activity snapshot for one video RTP track. */
public final class RtspRtpTrackActivityStats {

  public final int trackId;
  @Nullable public final String sampleMimeType;
  public final @RtspTransportMode.Mode int transportMode;
  public final long lastPacketArrivalElapsedRealtimeMs;
  public final long receivedPacketCount;
  public final int lastSequenceNumber;
  public final long lastRtpTimestamp;

  public RtspRtpTrackActivityStats(
      int trackId,
      @Nullable String sampleMimeType,
      @RtspTransportMode.Mode int transportMode,
      long lastPacketArrivalElapsedRealtimeMs,
      long receivedPacketCount,
      int lastSequenceNumber,
      long lastRtpTimestamp) {
    this.trackId = trackId;
    this.sampleMimeType = sampleMimeType;
    this.transportMode = transportMode;
    this.lastPacketArrivalElapsedRealtimeMs = lastPacketArrivalElapsedRealtimeMs;
    this.receivedPacketCount = receivedPacketCount;
    this.lastSequenceNumber = lastSequenceNumber;
    this.lastRtpTimestamp = lastRtpTimestamp;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspRtpTrackActivityStats)) {
      return false;
    }
    RtspRtpTrackActivityStats other = (RtspRtpTrackActivityStats) obj;
    return trackId == other.trackId
        && Util.areEqual(sampleMimeType, other.sampleMimeType)
        && transportMode == other.transportMode
        && lastPacketArrivalElapsedRealtimeMs == other.lastPacketArrivalElapsedRealtimeMs
        && receivedPacketCount == other.receivedPacketCount
        && lastSequenceNumber == other.lastSequenceNumber
        && lastRtpTimestamp == other.lastRtpTimestamp;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + (sampleMimeType == null ? 0 : sampleMimeType.hashCode());
    result = 31 * result + transportMode;
    result =
        31 * result
            + (int)
                (lastPacketArrivalElapsedRealtimeMs
                    ^ (lastPacketArrivalElapsedRealtimeMs >>> 32));
    result = 31 * result + (int) (receivedPacketCount ^ (receivedPacketCount >>> 32));
    result = 31 * result + lastSequenceNumber;
    result = 31 * result + (int) (lastRtpTimestamp ^ (lastRtpTimestamp >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspRtpTrackActivityStats(trackId=%d, sampleMimeType=%s, transportMode=%d, "
            + "lastPacketArrivalElapsedRealtimeMs=%d, receivedPacketCount=%d, "
            + "lastSequenceNumber=%d, lastRtpTimestamp=%d)",
        trackId,
        sampleMimeType,
        transportMode,
        lastPacketArrivalElapsedRealtimeMs,
        receivedPacketCount,
        lastSequenceNumber,
        lastRtpTimestamp);
  }
}
