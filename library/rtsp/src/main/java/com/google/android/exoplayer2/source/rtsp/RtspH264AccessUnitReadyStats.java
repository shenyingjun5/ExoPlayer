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

/** Diagnostics snapshot for a submitted H.264 access unit. */
public final class RtspH264AccessUnitReadyStats {

  public final int trackId;
  public final int rtpSequenceNumber;
  public final long rtpTimestamp;
  public final long sampleTimeUs;
  public final boolean isIdr;
  public final long assembledElapsedRealtimeMs;

  public RtspH264AccessUnitReadyStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      boolean isIdr,
      long assembledElapsedRealtimeMs) {
    this(
        trackId,
        rtpSequenceNumber,
        rtpTimestamp,
        /* sampleTimeUs= */ C.TIME_UNSET,
        isIdr,
        assembledElapsedRealtimeMs);
  }

  public RtspH264AccessUnitReadyStats(
      int trackId,
      int rtpSequenceNumber,
      long rtpTimestamp,
      long sampleTimeUs,
      boolean isIdr,
      long assembledElapsedRealtimeMs) {
    this.trackId = trackId;
    this.rtpSequenceNumber = rtpSequenceNumber;
    this.rtpTimestamp = rtpTimestamp;
    this.sampleTimeUs = sampleTimeUs;
    this.isIdr = isIdr;
    this.assembledElapsedRealtimeMs = assembledElapsedRealtimeMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspH264AccessUnitReadyStats)) {
      return false;
    }
    RtspH264AccessUnitReadyStats other = (RtspH264AccessUnitReadyStats) obj;
    return trackId == other.trackId
        && rtpSequenceNumber == other.rtpSequenceNumber
        && rtpTimestamp == other.rtpTimestamp
        && sampleTimeUs == other.sampleTimeUs
        && isIdr == other.isIdr
        && assembledElapsedRealtimeMs == other.assembledElapsedRealtimeMs;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + rtpSequenceNumber;
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + (int) (sampleTimeUs ^ (sampleTimeUs >>> 32));
    result = 31 * result + (isIdr ? 1 : 0);
    result = 31 * result + (int) (assembledElapsedRealtimeMs ^ (assembledElapsedRealtimeMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspH264AccessUnitReadyStats(trackId=%d, rtpSequenceNumber=%d, rtpTimestamp=%d, "
            + "sampleTimeUs=%d, isIdr=%b, assembledElapsedRealtimeMs=%d)",
        trackId, rtpSequenceNumber, rtpTimestamp, sampleTimeUs, isIdr, assembledElapsedRealtimeMs);
  }
}
