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

/** Describes an RTCP feedback request decision. */
public final class RtcpFeedbackRequest {

  /** RTP track id associated with the request. */
  public final int trackId;
  /** Reason for the request. */
  public final @RtcpFeedbackReason.Reason int reason;
  /** Elapsed realtime in milliseconds when the request was made. */
  public final long requestElapsedRealtimeMs;
  /** Optional detail for diagnostics output. */
  @Nullable public final String detail;

  public RtcpFeedbackRequest(
      int trackId,
      @RtcpFeedbackReason.Reason int reason,
      long requestElapsedRealtimeMs,
      @Nullable String detail) {
    this.trackId = trackId;
    this.reason = reason;
    this.requestElapsedRealtimeMs = requestElapsedRealtimeMs;
    this.detail = detail;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtcpFeedbackRequest)) {
      return false;
    }
    RtcpFeedbackRequest other = (RtcpFeedbackRequest) obj;
    return trackId == other.trackId
        && reason == other.reason
        && requestElapsedRealtimeMs == other.requestElapsedRealtimeMs
        && Util.areEqual(detail, other.detail);
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + reason;
    result = 31 * result + (int) (requestElapsedRealtimeMs ^ (requestElapsedRealtimeMs >>> 32));
    result = 31 * result + (detail == null ? 0 : detail.hashCode());
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpFeedbackRequest(trackId=%d, reason=%d, requestElapsedRealtimeMs=%d, detail=%s)",
        trackId, reason, requestElapsedRealtimeMs, detail);
  }
}
