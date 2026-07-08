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

/** Diagnostics snapshot for an RTSP RTP transport fallback decision. */
public final class RtspTransportFallbackStats {

  public final int trackId;
  public final @RtspTransportFallbackReason.Reason int reason;
  public final @RtspTransportMode.Mode int fromTransportMode;
  public final @RtspTransportMode.Mode int toTransportMode;
  public final long fallbackElapsedRealtimeMs;

  public RtspTransportFallbackStats(
      int trackId,
      @RtspTransportFallbackReason.Reason int reason,
      @RtspTransportMode.Mode int fromTransportMode,
      @RtspTransportMode.Mode int toTransportMode,
      long fallbackElapsedRealtimeMs) {
    this.trackId = trackId;
    this.reason = reason;
    this.fromTransportMode = fromTransportMode;
    this.toTransportMode = toTransportMode;
    this.fallbackElapsedRealtimeMs = fallbackElapsedRealtimeMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspTransportFallbackStats)) {
      return false;
    }
    RtspTransportFallbackStats other = (RtspTransportFallbackStats) obj;
    return trackId == other.trackId
        && reason == other.reason
        && fromTransportMode == other.fromTransportMode
        && toTransportMode == other.toTransportMode
        && fallbackElapsedRealtimeMs == other.fallbackElapsedRealtimeMs;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + reason;
    result = 31 * result + fromTransportMode;
    result = 31 * result + toTransportMode;
    result = 31 * result + (int) (fallbackElapsedRealtimeMs ^ (fallbackElapsedRealtimeMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspTransportFallbackStats(trackId=%d, reason=%d, fromTransportMode=%d, "
            + "toTransportMode=%d, fallbackElapsedRealtimeMs=%d)",
        trackId, reason, fromTransportMode, toTransportMode, fallbackElapsedRealtimeMs);
  }
}
