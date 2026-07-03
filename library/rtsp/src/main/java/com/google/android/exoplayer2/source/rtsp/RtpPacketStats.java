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

/** Diagnostics snapshot for one RTP packet. */
public final class RtpPacketStats {

  public final int trackId;
  public final @RtspTransportMode.Mode int transportMode;
  public final int payloadType;
  public final int sequenceNumber;
  public final long rtpTimestamp;
  public final long arrivalElapsedRealtimeMs;
  public final int ssrc;
  public final boolean marker;

  public RtpPacketStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int payloadType,
      int sequenceNumber,
      long rtpTimestamp,
      long arrivalElapsedRealtimeMs,
      int ssrc,
      boolean marker) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.payloadType = payloadType;
    this.sequenceNumber = sequenceNumber;
    this.rtpTimestamp = rtpTimestamp;
    this.arrivalElapsedRealtimeMs = arrivalElapsedRealtimeMs;
    this.ssrc = ssrc;
    this.marker = marker;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtpPacketStats)) {
      return false;
    }
    RtpPacketStats other = (RtpPacketStats) obj;
    return trackId == other.trackId
        && transportMode == other.transportMode
        && payloadType == other.payloadType
        && sequenceNumber == other.sequenceNumber
        && rtpTimestamp == other.rtpTimestamp
        && arrivalElapsedRealtimeMs == other.arrivalElapsedRealtimeMs
        && ssrc == other.ssrc
        && marker == other.marker;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + transportMode;
    result = 31 * result + payloadType;
    result = 31 * result + sequenceNumber;
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + (int) (arrivalElapsedRealtimeMs ^ (arrivalElapsedRealtimeMs >>> 32));
    result = 31 * result + ssrc;
    result = 31 * result + (marker ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtpPacketStats(trackId=%d, transportMode=%d, payloadType=%d, sequenceNumber=%d, "
            + "rtpTimestamp=%d, arrivalElapsedRealtimeMs=%d, ssrc=%x, marker=%b)",
        trackId,
        transportMode,
        payloadType,
        sequenceNumber,
        rtpTimestamp,
        arrivalElapsedRealtimeMs,
        ssrc,
        marker);
  }
}
