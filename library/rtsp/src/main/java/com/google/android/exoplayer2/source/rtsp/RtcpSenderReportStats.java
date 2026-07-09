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

/** Diagnostics snapshot for one RTCP Sender Report packet. */
public final class RtcpSenderReportStats {

  /** RTP track id associated with this Sender Report. */
  public final int trackId;
  /** Sender/media SSRC from the RTCP Sender Report, interpreted as unsigned 32-bit. */
  public final long ssrc;
  /** RTP timestamp from the RTCP Sender Report, interpreted as unsigned 32-bit. */
  public final long rtpTimestamp;
  /** Sender NTP timestamp converted to milliseconds. */
  public final long ntpTimeMs;
  /** Raw NTP seconds field, interpreted as unsigned 32-bit. */
  public final long rawNtpSeconds;
  /** Raw NTP fractional seconds field, interpreted as unsigned 32-bit. */
  public final long rawNtpFraction;
  /** Receiver elapsed realtime in milliseconds when this RTCP packet was received. */
  public final long receivedElapsedRealtimeMs;
  /** RTSP lower transport mode used to receive this RTCP packet. */
  public final @RtspTransportMode.Mode int transportMode;
  /** RTP clock rate for the associated track. */
  public final int clockRate;
  /** Sender packet count, interpreted as unsigned 32-bit. */
  public final long packetCount;
  /** Sender octet count, interpreted as unsigned 32-bit. */
  public final long octetCount;

  public RtcpSenderReportStats(
      int trackId,
      long ssrc,
      long rtpTimestamp,
      long ntpTimeMs,
      long rawNtpSeconds,
      long rawNtpFraction,
      long receivedElapsedRealtimeMs,
      @RtspTransportMode.Mode int transportMode,
      int clockRate,
      long packetCount,
      long octetCount) {
    this.trackId = trackId;
    this.ssrc = ssrc;
    this.rtpTimestamp = rtpTimestamp;
    this.ntpTimeMs = ntpTimeMs;
    this.rawNtpSeconds = rawNtpSeconds;
    this.rawNtpFraction = rawNtpFraction;
    this.receivedElapsedRealtimeMs = receivedElapsedRealtimeMs;
    this.transportMode = transportMode;
    this.clockRate = clockRate;
    this.packetCount = packetCount;
    this.octetCount = octetCount;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtcpSenderReportStats)) {
      return false;
    }
    RtcpSenderReportStats other = (RtcpSenderReportStats) obj;
    return trackId == other.trackId
        && ssrc == other.ssrc
        && rtpTimestamp == other.rtpTimestamp
        && ntpTimeMs == other.ntpTimeMs
        && rawNtpSeconds == other.rawNtpSeconds
        && rawNtpFraction == other.rawNtpFraction
        && receivedElapsedRealtimeMs == other.receivedElapsedRealtimeMs
        && transportMode == other.transportMode
        && clockRate == other.clockRate
        && packetCount == other.packetCount
        && octetCount == other.octetCount;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + (int) (ssrc ^ (ssrc >>> 32));
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + (int) (ntpTimeMs ^ (ntpTimeMs >>> 32));
    result = 31 * result + (int) (rawNtpSeconds ^ (rawNtpSeconds >>> 32));
    result = 31 * result + (int) (rawNtpFraction ^ (rawNtpFraction >>> 32));
    result =
        31 * result
            + (int) (receivedElapsedRealtimeMs ^ (receivedElapsedRealtimeMs >>> 32));
    result = 31 * result + transportMode;
    result = 31 * result + clockRate;
    result = 31 * result + (int) (packetCount ^ (packetCount >>> 32));
    result = 31 * result + (int) (octetCount ^ (octetCount >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpSenderReportStats(trackId=%d, ssrc=%x, rtpTimestamp=%d, ntpTimeMs=%d, "
            + "rawNtpSeconds=%d, rawNtpFraction=%d, receivedElapsedRealtimeMs=%d, "
            + "transportMode=%d, clockRate=%d, packetCount=%d, octetCount=%d)",
        trackId,
        ssrc,
        rtpTimestamp,
        ntpTimeMs,
        rawNtpSeconds,
        rawNtpFraction,
        receivedElapsedRealtimeMs,
        transportMode,
        clockRate,
        packetCount,
        octetCount);
  }
}
