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

/** Diagnostics snapshot for an H.264 access unit assembled from RTP packets. */
public final class RtspH264AccessUnitStats {

  /** Human-readable type for IDR access units. */
  public static final String ACCESS_UNIT_TYPE_IDR = "IDR";

  public final int trackId;
  public final int rtpSequenceNumber;
  public final long rtpTimestamp;
  public final long sampleTimeUs;
  public final boolean hasSps;
  public final boolean hasPps;
  public final int nalUnitType;
  public final String accessUnitType;
  public final long firstRtpPacketElapsedRealtimeMs;

  public RtspH264AccessUnitStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      boolean hasSps,
      boolean hasPps,
      int nalUnitType,
      String accessUnitType,
      long firstRtpPacketElapsedRealtimeMs) {
    this(
        trackId,
        rtpSequenceNumber,
        rtpTimestamp,
        /* sampleTimeUs= */ C.TIME_UNSET,
        hasSps,
        hasPps,
        nalUnitType,
        accessUnitType,
        firstRtpPacketElapsedRealtimeMs);
  }

  public RtspH264AccessUnitStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      long sampleTimeUs,
      boolean hasSps,
      boolean hasPps,
      int nalUnitType,
      String accessUnitType,
      long firstRtpPacketElapsedRealtimeMs) {
    this.trackId = trackId;
    this.rtpSequenceNumber = rtpSequenceNumber;
    this.rtpTimestamp = rtpTimestamp;
    this.sampleTimeUs = sampleTimeUs;
    this.hasSps = hasSps;
    this.hasPps = hasPps;
    this.nalUnitType = nalUnitType;
    this.accessUnitType = accessUnitType;
    this.firstRtpPacketElapsedRealtimeMs = firstRtpPacketElapsedRealtimeMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspH264AccessUnitStats)) {
      return false;
    }
    RtspH264AccessUnitStats other = (RtspH264AccessUnitStats) obj;
    return trackId == other.trackId
        && rtpSequenceNumber == other.rtpSequenceNumber
        && rtpTimestamp == other.rtpTimestamp
        && sampleTimeUs == other.sampleTimeUs
        && hasSps == other.hasSps
        && hasPps == other.hasPps
        && nalUnitType == other.nalUnitType
        && accessUnitType.equals(other.accessUnitType)
        && firstRtpPacketElapsedRealtimeMs == other.firstRtpPacketElapsedRealtimeMs;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + rtpSequenceNumber;
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + (int) (sampleTimeUs ^ (sampleTimeUs >>> 32));
    result = 31 * result + (hasSps ? 1 : 0);
    result = 31 * result + (hasPps ? 1 : 0);
    result = 31 * result + nalUnitType;
    result = 31 * result + accessUnitType.hashCode();
    result =
        31 * result
            + (int)
                (firstRtpPacketElapsedRealtimeMs ^ (firstRtpPacketElapsedRealtimeMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspH264AccessUnitStats(trackId=%d, rtpSequenceNumber=%d, rtpTimestamp=%d, "
            + "sampleTimeUs=%d, hasSps=%b, hasPps=%b, nalUnitType=%d, accessUnitType=%s, "
            + "firstRtpPacketElapsedRealtimeMs=%d)",
        trackId,
        rtpSequenceNumber,
        rtpTimestamp,
        sampleTimeUs,
        hasSps,
        hasPps,
        nalUnitType,
        accessUnitType,
        firstRtpPacketElapsedRealtimeMs);
  }
}
