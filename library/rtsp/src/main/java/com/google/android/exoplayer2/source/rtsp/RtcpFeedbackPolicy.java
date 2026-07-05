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
  /** Default sequence gap threshold for automatic key-frame requests. */
  public static final int DEFAULT_SEQUENCE_GAP_REQUEST_THRESHOLD = 8;

  /** Default policy. Does not send RTCP feedback automatically. */
  public static final RtcpFeedbackPolicy DEFAULT = new Builder().build();

  /** Low-latency policy for trusted RTSP live sources that support RTCP PLI/FIR. */
  public static final RtcpFeedbackPolicy LOW_LATENCY_DEFAULT =
      new Builder().setLowLatencyDefaults().build();

  /** Minimum interval between RTCP feedback requests for the same track. */
  public final long minRequestIntervalMs;
  /** Whether Picture Loss Indication feedback may be sent. */
  public final boolean pliEnabled;
  /** Whether Full Intra Request feedback may be used as a fallback. */
  public final boolean firEnabled;
  /** Sender SSRC used in generated RTCP feedback packets. */
  public final int senderSsrc;
  /** Sequence gap threshold at which the RTP queue requests a key frame. */
  public final int sequenceGapRequestThreshold;
  /** Whether a large RTP reordering-queue reset requests a key frame. */
  public final boolean requestKeyFrameOnQueueReset;

  private RtcpFeedbackPolicy(Builder builder) {
    this.minRequestIntervalMs = builder.minRequestIntervalMs;
    this.pliEnabled = builder.pliEnabled;
    this.firEnabled = builder.firEnabled;
    this.senderSsrc = builder.senderSsrc;
    this.sequenceGapRequestThreshold = builder.sequenceGapRequestThreshold;
    this.requestKeyFrameOnQueueReset = builder.requestKeyFrameOnQueueReset;
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
        && firEnabled == other.firEnabled
        && senderSsrc == other.senderSsrc
        && sequenceGapRequestThreshold == other.sequenceGapRequestThreshold
        && requestKeyFrameOnQueueReset == other.requestKeyFrameOnQueueReset;
  }

  @Override
  public int hashCode() {
    int result = (int) (minRequestIntervalMs ^ (minRequestIntervalMs >>> 32));
    result = 31 * result + (pliEnabled ? 1 : 0);
    result = 31 * result + (firEnabled ? 1 : 0);
    result = 31 * result + senderSsrc;
    result = 31 * result + sequenceGapRequestThreshold;
    result = 31 * result + (requestKeyFrameOnQueueReset ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpFeedbackPolicy(minRequestIntervalMs=%d, pliEnabled=%b, firEnabled=%b, "
            + "senderSsrc=%x, sequenceGapRequestThreshold=%d, "
            + "requestKeyFrameOnQueueReset=%b)",
        minRequestIntervalMs,
        pliEnabled,
        firEnabled,
        senderSsrc,
        sequenceGapRequestThreshold,
        requestKeyFrameOnQueueReset);
  }

  /** Builder for {@link RtcpFeedbackPolicy}. */
  public static final class Builder {
    private long minRequestIntervalMs;
    private boolean pliEnabled;
    private boolean firEnabled;
    private int senderSsrc;
    private int sequenceGapRequestThreshold;
    private boolean requestKeyFrameOnQueueReset;

    /** Creates a builder with the default passive RTCP feedback policy. */
    public Builder() {
      minRequestIntervalMs = DEFAULT_MIN_REQUEST_INTERVAL_MS;
      pliEnabled = false;
      firEnabled = false;
      senderSsrc = 0;
      sequenceGapRequestThreshold = 0;
      requestKeyFrameOnQueueReset = false;
    }

    /** Sets the builder to the low-latency preset values. */
    @CanIgnoreReturnValue
    public Builder setLowLatencyDefaults() {
      minRequestIntervalMs = DEFAULT_MIN_REQUEST_INTERVAL_MS;
      pliEnabled = true;
      firEnabled = true;
      sequenceGapRequestThreshold = DEFAULT_SEQUENCE_GAP_REQUEST_THRESHOLD;
      requestKeyFrameOnQueueReset = true;
      return this;
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

    /** Sets the sender SSRC used in generated RTCP feedback packets. */
    @CanIgnoreReturnValue
    public Builder setSenderSsrc(int senderSsrc) {
      this.senderSsrc = senderSsrc;
      return this;
    }

    /**
     * Sets the sequence gap threshold at which the RTP queue requests a key frame.
     *
     * <p>A value of {@code 0} disables automatic sequence-gap requests.
     */
    @CanIgnoreReturnValue
    public Builder setSequenceGapRequestThreshold(int sequenceGapRequestThreshold) {
      checkArgument(sequenceGapRequestThreshold >= 0);
      this.sequenceGapRequestThreshold = sequenceGapRequestThreshold;
      return this;
    }

    /** Sets whether queue resets automatically request a key frame. */
    @CanIgnoreReturnValue
    public Builder setRequestKeyFrameOnQueueReset(boolean requestKeyFrameOnQueueReset) {
      this.requestKeyFrameOnQueueReset = requestKeyFrameOnQueueReset;
      return this;
    }

    /** Builds the policy. */
    public RtcpFeedbackPolicy build() {
      return new RtcpFeedbackPolicy(this);
    }
  }
}
