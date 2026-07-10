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

/** Result of a runtime one-shot RTCP feedback request. */
public final class RtcpFeedbackResult {

  /** The RTCP feedback packet was accepted for send or sent synchronously. */
  public static final int SCHEDULED = 1;
  /** The request was rejected by the configured minimum request interval. */
  public static final int THROTTLED = 2;
  /** The request could not be sent. */
  public static final int FAILED = 3;

  /** One of {@link #SCHEDULED}, {@link #THROTTLED}, or {@link #FAILED}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({SCHEDULED, THROTTLED, FAILED})
  public @interface Status {}

  /** Request status. */
  public final @Status int status;
  /** The resolved request, or {@code null} when no track was available. */
  @Nullable public final RtcpFeedbackRequest request;
  /** Elapsed realtime in milliseconds when this result was produced. */
  public final long resultElapsedRealtimeMs;
  /** Optional detail for diagnostics or reflection callers. */
  @Nullable public final String detail;

  public RtcpFeedbackResult(
      @Status int status,
      @Nullable RtcpFeedbackRequest request,
      long resultElapsedRealtimeMs,
      @Nullable String detail) {
    this.status = status;
    this.request = request;
    this.resultElapsedRealtimeMs = resultElapsedRealtimeMs;
    this.detail = detail;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtcpFeedbackResult)) {
      return false;
    }
    RtcpFeedbackResult other = (RtcpFeedbackResult) obj;
    return status == other.status
        && Util.areEqual(request, other.request)
        && resultElapsedRealtimeMs == other.resultElapsedRealtimeMs
        && Util.areEqual(detail, other.detail);
  }

  @Override
  public int hashCode() {
    int result = status;
    result = 31 * result + (request == null ? 0 : request.hashCode());
    result = 31 * result + (int) (resultElapsedRealtimeMs ^ (resultElapsedRealtimeMs >>> 32));
    result = 31 * result + (detail == null ? 0 : detail.hashCode());
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpFeedbackResult(status=%d, request=%s, resultElapsedRealtimeMs=%d, detail=%s)",
        status, request, resultElapsedRealtimeMs, detail);
  }
}
