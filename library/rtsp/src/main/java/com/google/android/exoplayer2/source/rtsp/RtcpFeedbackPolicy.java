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

import androidx.annotation.IntDef;
import com.google.android.exoplayer2.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Policy for RTCP key-frame feedback requests. */
public final class RtcpFeedbackPolicy {

  /** Default minimum interval between RTCP feedback requests for the same track. */
  public static final long DEFAULT_MIN_REQUEST_INTERVAL_MS = 400;
  /** Default sequence gap threshold for automatic key-frame requests. */
  public static final int DEFAULT_SEQUENCE_GAP_REQUEST_THRESHOLD = 8;
  /** Default timeout for reporting a prolonged wait for an IDR access unit. */
  public static final long DEFAULT_WAITING_FOR_IDR_TIMEOUT_MS = 800;

  /** The fork emits recovery events and may send RTCP feedback. */
  public static final int RTCP_ONLY = 0;
  /** The fork emits recovery events but never sends RTCP feedback automatically. */
  public static final int EXTERNAL_ONLY = 1;
  /** The fork emits recovery events and may send RTCP feedback as a fallback. */
  public static final int BOTH = 2;

  /** Feedback strategy for key-frame recovery. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({RTCP_ONLY, EXTERNAL_ONLY, BOTH})
  public @interface FeedbackStrategy {}

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
  /** Whether deadline-bound RTCP Generic NACK may be sent for UDP low-latency recovery. */
  public final boolean genericNackEnabled;
  /** Maximum age of a confirmed gap that remains eligible for Generic NACK. */
  public final long genericNackDeadlineMs;
  /** Maximum missing packets represented by one Generic NACK PID/BLP request. */
  public final int genericNackMaxGapSize;
  /** Maximum Generic NACK sends for one confirmed gap. */
  public final int genericNackMaxRetries;
  /** Sender SSRC used in generated RTCP feedback packets. */
  public final int senderSsrc;
  /** Sequence gap threshold at which the RTP queue requests a key frame. */
  public final int sequenceGapRequestThreshold;
  /** Whether a large RTP reordering-queue reset requests a key frame. */
  public final boolean requestKeyFrameOnQueueReset;
  /** Feedback strategy for low-latency recovery. */
  public final @FeedbackStrategy int feedbackStrategy;
  /** Timeout for reporting a prolonged wait for an IDR access unit, or {@code 0} to disable. */
  public final long waitingForIdrTimeoutMs;

  private RtcpFeedbackPolicy(Builder builder) {
    this.minRequestIntervalMs = builder.minRequestIntervalMs;
    this.pliEnabled = builder.pliEnabled;
    this.firEnabled = builder.firEnabled;
    this.genericNackEnabled = builder.genericNackEnabled;
    this.genericNackDeadlineMs = builder.genericNackDeadlineMs;
    this.genericNackMaxGapSize = builder.genericNackMaxGapSize;
    this.genericNackMaxRetries = builder.genericNackMaxRetries;
    this.senderSsrc = builder.senderSsrc;
    this.sequenceGapRequestThreshold = builder.sequenceGapRequestThreshold;
    this.requestKeyFrameOnQueueReset = builder.requestKeyFrameOnQueueReset;
    this.feedbackStrategy = builder.feedbackStrategy;
    this.waitingForIdrTimeoutMs = builder.waitingForIdrTimeoutMs;
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
        && genericNackEnabled == other.genericNackEnabled
        && genericNackDeadlineMs == other.genericNackDeadlineMs
        && genericNackMaxGapSize == other.genericNackMaxGapSize
        && genericNackMaxRetries == other.genericNackMaxRetries
        && senderSsrc == other.senderSsrc
        && sequenceGapRequestThreshold == other.sequenceGapRequestThreshold
        && requestKeyFrameOnQueueReset == other.requestKeyFrameOnQueueReset
        && feedbackStrategy == other.feedbackStrategy
        && waitingForIdrTimeoutMs == other.waitingForIdrTimeoutMs;
  }

  @Override
  public int hashCode() {
    int result = (int) (minRequestIntervalMs ^ (minRequestIntervalMs >>> 32));
    result = 31 * result + (pliEnabled ? 1 : 0);
    result = 31 * result + (firEnabled ? 1 : 0);
    result = 31 * result + (genericNackEnabled ? 1 : 0);
    result = 31 * result + (int) (genericNackDeadlineMs ^ (genericNackDeadlineMs >>> 32));
    result = 31 * result + genericNackMaxGapSize;
    result = 31 * result + genericNackMaxRetries;
    result = 31 * result + senderSsrc;
    result = 31 * result + sequenceGapRequestThreshold;
    result = 31 * result + (requestKeyFrameOnQueueReset ? 1 : 0);
    result = 31 * result + feedbackStrategy;
    result = 31 * result + (int) (waitingForIdrTimeoutMs ^ (waitingForIdrTimeoutMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtcpFeedbackPolicy(minRequestIntervalMs=%d, pliEnabled=%b, firEnabled=%b, genericNackEnabled=%b, genericNackDeadlineMs=%d, genericNackMaxGapSize=%d, "
            + "senderSsrc=%x, sequenceGapRequestThreshold=%d, "
            + "requestKeyFrameOnQueueReset=%b, feedbackStrategy=%d, "
            + "waitingForIdrTimeoutMs=%d)",
        minRequestIntervalMs,
        pliEnabled,
        firEnabled,
        genericNackEnabled,
        genericNackDeadlineMs,
        genericNackMaxGapSize,
        senderSsrc,
        sequenceGapRequestThreshold,
        requestKeyFrameOnQueueReset,
        feedbackStrategy,
        waitingForIdrTimeoutMs);
  }

  /** Returns whether recovery events and drop-until-IDR behavior should be enabled. */
  public boolean isLowLatencyRecoveryEnabled() {
    return feedbackStrategy == EXTERNAL_ONLY
        || feedbackStrategy == BOTH
        || pliEnabled
        || firEnabled
        || sequenceGapRequestThreshold > 0
        || requestKeyFrameOnQueueReset;
  }

  /** Returns whether the fork may send RTCP feedback packets. */
  public boolean canSendRtcpFeedback() {
    return feedbackStrategy != EXTERNAL_ONLY && (pliEnabled || firEnabled);
  }

  /** Returns whether automatic Generic NACK may be sent. */
  public boolean canSendGenericNack() {
    return feedbackStrategy != EXTERNAL_ONLY && genericNackEnabled;
  }

  /** Builder for {@link RtcpFeedbackPolicy}. */
  public static final class Builder {
    private long minRequestIntervalMs;
    private boolean pliEnabled;
    private boolean firEnabled;
    private boolean genericNackEnabled;
    private long genericNackDeadlineMs;
    private int genericNackMaxGapSize;
    private int genericNackMaxRetries;
    private int senderSsrc;
    private int sequenceGapRequestThreshold;
    private boolean requestKeyFrameOnQueueReset;
    private @FeedbackStrategy int feedbackStrategy;
    private long waitingForIdrTimeoutMs;

    /** Creates a builder with the default passive RTCP feedback policy. */
    public Builder() {
      minRequestIntervalMs = DEFAULT_MIN_REQUEST_INTERVAL_MS;
      pliEnabled = false;
      firEnabled = false;
      genericNackEnabled = false;
      genericNackDeadlineMs = 80;
      genericNackMaxGapSize = 17;
      genericNackMaxRetries = 1;
      senderSsrc = 0;
      sequenceGapRequestThreshold = 0;
      requestKeyFrameOnQueueReset = false;
      feedbackStrategy = RTCP_ONLY;
      waitingForIdrTimeoutMs = 0;
    }

    /** Sets the builder to the low-latency preset values. */
    @CanIgnoreReturnValue
    public Builder setLowLatencyDefaults() {
      minRequestIntervalMs = DEFAULT_MIN_REQUEST_INTERVAL_MS;
      pliEnabled = true;
      firEnabled = true;
      sequenceGapRequestThreshold = DEFAULT_SEQUENCE_GAP_REQUEST_THRESHOLD;
      requestKeyFrameOnQueueReset = true;
      feedbackStrategy = BOTH;
      waitingForIdrTimeoutMs = DEFAULT_WAITING_FOR_IDR_TIMEOUT_MS;
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

    /** Enables deadline-bound UDP Generic NACK. Disabled by default. */
    @CanIgnoreReturnValue
    public Builder setGenericNackEnabled(boolean genericNackEnabled) {
      this.genericNackEnabled = genericNackEnabled;
      return this;
    }

    /** Sets the bounded Generic NACK deadline in the supported 60-100ms range. */
    @CanIgnoreReturnValue
    public Builder setGenericNackDeadlineMs(long genericNackDeadlineMs) {
      checkArgument(genericNackDeadlineMs >= 60 && genericNackDeadlineMs <= 100);
      this.genericNackDeadlineMs = genericNackDeadlineMs;
      return this;
    }

    /** Sets the maximum missing packets represented by one PID/BLP request (1-17). */
    @CanIgnoreReturnValue
    public Builder setGenericNackMaxGapSize(int genericNackMaxGapSize) {
      checkArgument(genericNackMaxGapSize >= 1 && genericNackMaxGapSize <= 17);
      this.genericNackMaxGapSize = genericNackMaxGapSize;
      return this;
    }

    /** Sets the maximum sends for one Generic NACK PID/BLP request (1-2). */
    @CanIgnoreReturnValue
    public Builder setGenericNackMaxRetries(int genericNackMaxRetries) {
      checkArgument(genericNackMaxRetries >= 1 && genericNackMaxRetries <= 2);
      this.genericNackMaxRetries = genericNackMaxRetries;
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

    /** Sets the key-frame feedback strategy. */
    @CanIgnoreReturnValue
    public Builder setFeedbackStrategy(@FeedbackStrategy int feedbackStrategy) {
      this.feedbackStrategy = feedbackStrategy;
      return this;
    }

    /** Sets the timeout for reporting a prolonged wait for an IDR access unit. */
    @CanIgnoreReturnValue
    public Builder setWaitingForIdrTimeoutMs(long waitingForIdrTimeoutMs) {
      checkArgument(waitingForIdrTimeoutMs >= 0);
      this.waitingForIdrTimeoutMs = waitingForIdrTimeoutMs;
      return this;
    }

    /** Builds the policy. */
    public RtcpFeedbackPolicy build() {
      return new RtcpFeedbackPolicy(this);
    }
  }
}
