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

import static com.google.android.exoplayer2.util.Assertions.checkArgument;

import com.google.android.exoplayer2.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Policy for RTCP key-frame feedback requests. */
public final class RtcpFeedbackPolicy {

  /** Default minimum interval between RTCP feedback requests for the same track. */
  public static final long DEFAULT_MIN_REQUEST_INTERVAL_MS = 400;

  /** Default policy. */
  public static final RtcpFeedbackPolicy DEFAULT =
      new Builder().setMinRequestIntervalMs(DEFAULT_MIN_REQUEST_INTERVAL_MS).build();

  /** Minimum interval between RTCP feedback requests for the same track. */
  public final long minRequestIntervalMs;
  /** Whether Picture Loss Indication feedback may be sent. */
  public final boolean pliEnabled;
  /** Whether Full Intra Request feedback may be used as a fallback. */
  public final boolean firEnabled;

  private RtcpFeedbackPolicy(Builder builder) {
    this.minRequestIntervalMs = builder.minRequestIntervalMs;
    this.pliEnabled = builder.pliEnabled;
    this.firEnabled = builder.firEnabled;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtcpFeedbackPolicy)) {
      return false;
    }
    RtcpFeedbackPolicy other = (RtcpFeedbackPolicy) obj;
    return minRequestIntervalMs == other.minRequestIntervalMs
        && pliEnabled == other.pliEnabled
        && firEnabled == other.firEnabled;
  }

  @Override
  public int hashCode() {
    int result = (int) (minRequestIntervalMs ^ (minRequestIntervalMs >>> 32));
    result = 31 * result + (pliEnabled ? 1 : 0);
    result = 31 * result + (firEnabled ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpFeedbackPolicy(minRequestIntervalMs=%d, pliEnabled=%b, firEnabled=%b)",
        minRequestIntervalMs, pliEnabled, firEnabled);
  }

  /** Builder for {@link RtcpFeedbackPolicy}. */
  public static final class Builder {
    private long minRequestIntervalMs;
    private boolean pliEnabled;
    private boolean firEnabled;

    /** Creates a builder with the default RTCP feedback policy. */
    public Builder() {
      minRequestIntervalMs = DEFAULT_MIN_REQUEST_INTERVAL_MS;
      pliEnabled = true;
      firEnabled = true;
    }

    /** Sets the minimum interval between feedback requests for the same track. */
    @CanIgnoreReturnValue
    public Builder setMinRequestIntervalMs(long minRequestIntervalMs) {
      checkArgument(minRequestIntervalMs >= 0);
      this.minRequestIntervalMs = minRequestIntervalMs;
      return this;
    }

    /** Sets whether Picture Loss Indication feedback may be sent. */
    @CanIgnoreReturnValue
    public Builder setPliEnabled(boolean pliEnabled) {
      this.pliEnabled = pliEnabled;
      return this;
    }

    /** Sets whether Full Intra Request feedback may be used as a fallback. */
    @CanIgnoreReturnValue
    public Builder setFirEnabled(boolean firEnabled) {
      this.firEnabled = firEnabled;
      return this;
    }

    /** Builds the policy. */
    public RtcpFeedbackPolicy build() {
      return new RtcpFeedbackPolicy(this);
    }
  }
}
