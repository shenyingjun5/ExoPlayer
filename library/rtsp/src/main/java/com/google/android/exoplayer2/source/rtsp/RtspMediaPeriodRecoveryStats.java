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

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Low-frequency signal that the app should perform controlled RTSP media-period recovery. */
public final class RtspMediaPeriodRecoveryStats {

  /** The fork could not safely clear already submitted samples in-place; rebuild is required. */
  public static final int ACTION_REBUILD_REQUIRED = 1;

  /** One of {@link #ACTION_REBUILD_REQUIRED}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({ACTION_REBUILD_REQUIRED})
  public @interface Action {}

  public final int trackId;
  public final @RtspTransportMode.Mode int transportMode;
  public final @RtcpFeedbackReason.Reason int reason;
  public final @Action int action;
  public final long eventElapsedRealtimeMs;
  @Nullable public final String detail;

  public RtspMediaPeriodRecoveryStats(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @RtcpFeedbackReason.Reason int reason,
      @Action int action,
      long eventElapsedRealtimeMs,
      @Nullable String detail) {
    this.trackId = trackId;
    this.transportMode = transportMode;
    this.reason = reason;
    this.action = action;
    this.eventElapsedRealtimeMs = eventElapsedRealtimeMs;
    this.detail = detail;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspMediaPeriodRecoveryStats)) {
      return false;
    }
    RtspMediaPeriodRecoveryStats other = (RtspMediaPeriodRecoveryStats) obj;
    return trackId == other.trackId
        && transportMode == other.transportMode
        && reason == other.reason
        && action == other.action
        && eventElapsedRealtimeMs == other.eventElapsedRealtimeMs
        && Util.areEqual(detail, other.detail);
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + transportMode;
    result = 31 * result + reason;
    result = 31 * result + action;
    result = 31 * result + (int) (eventElapsedRealtimeMs ^ (eventElapsedRealtimeMs >>> 32));
    result = 31 * result + (detail == null ? 0 : detail.hashCode());
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspMediaPeriodRecoveryStats(trackId=%d, transportMode=%d, reason=%d, action=%d, "
            + "eventElapsedRealtimeMs=%d, detail=%s)",
        trackId, transportMode, reason, action, eventElapsedRealtimeMs, detail);
  }
}
