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

import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Policy for low-latency RTSP backlog recovery. */
public final class RtspBacklogRecoveryPolicy {

  /** Disabled policy that preserves ExoPlayer's default RTSP behavior. */
  public static final RtspBacklogRecoveryPolicy DISABLED = new Builder().build();

  /** Low-latency preset for trusted RTSP live sessions. */
  public static final RtspBacklogRecoveryPolicy LOW_LATENCY_DEFAULT =
      new Builder()
          .setEnabled(true)
          .setTcpInterleavedBacklogWarnMs(150)
          .setTcpInterleavedBacklogResetMs(300)
          .setTcpInterleavedBacklogResetPackets(240)
          .setRtpReorderBacklogWarnMs(100)
          .setRtpReorderBacklogResetMs(200)
          .setRtpReorderBacklogResetPackets(240)
          .setWaitForIdrTimeoutMs(800)
          .build();

  /** Alias for {@link #LOW_LATENCY_DEFAULT}. */
  public static final RtspBacklogRecoveryPolicy LOW_LATENCY = LOW_LATENCY_DEFAULT;

  /** Whether low-latency backlog recovery is enabled. */
  public final boolean enabled;
  /** TCP interleaved queue warning age in milliseconds, or {@code 0} to disable. */
  public final long tcpInterleavedBacklogWarnMs;
  /** TCP interleaved queue reset age in milliseconds, or {@code 0} to disable. */
  public final long tcpInterleavedBacklogResetMs;
  /** TCP interleaved queue reset depth in RTP packets, or {@code 0} to disable. */
  public final int tcpInterleavedBacklogResetPackets;
  /** RTP reordering queue warning span in milliseconds, or {@code 0} to disable. */
  public final long rtpReorderBacklogWarnMs;
  /** RTP reordering queue reset span/age in milliseconds, or {@code 0} to disable. */
  public final long rtpReorderBacklogResetMs;
  /** RTP reordering queue reset depth in RTP packets, or {@code 0} to disable. */
  public final int rtpReorderBacklogResetPackets;
  /** Timeout for reporting prolonged wait for an IDR access unit, or {@code 0} to disable. */
  public final long waitForIdrTimeoutMs;
  /** Whether H.264 starts in WAIT_IDR until a complete decodable IDR access unit arrives. */
  public final boolean initialWaitForIdr;
  /** Whether H.264 re-enters WAIT_IDR after seek/reset. */
  public final boolean initialWaitForIdrAfterSeek;
  /** Maximum TCP interleaved queue age in milliseconds, or {@code 0} to disable. */
  public final long maxTcpInterleavedQueueAgeMs;
  /** Maximum TCP interleaved queue depth in RTP packets, or {@code 0} to disable. */
  public final int maxTcpInterleavedQueueDepth;
  /** Maximum RTP reordering queue age in milliseconds, or {@code 0} to disable. */
  public final long maxRtpReorderQueueAgeMs;
  /** Maximum RTP reordering queue span in milliseconds, or {@code 0} to disable. */
  public final long maxRtpReorderQueueSpanMs;
  /** Maximum RTP reordering queue depth in RTP packets, or {@code 0} to disable. */
  public final int maxRtpReorderQueueDepth;

  private RtspBacklogRecoveryPolicy(Builder builder) {
    enabled = builder.enabled;
    tcpInterleavedBacklogWarnMs = builder.tcpInterleavedBacklogWarnMs;
    tcpInterleavedBacklogResetMs = builder.tcpInterleavedBacklogResetMs;
    tcpInterleavedBacklogResetPackets = builder.tcpInterleavedBacklogResetPackets;
    rtpReorderBacklogWarnMs = builder.rtpReorderBacklogWarnMs;
    rtpReorderBacklogResetMs = builder.rtpReorderBacklogResetMs;
    rtpReorderBacklogResetPackets = builder.rtpReorderBacklogResetPackets;
    waitForIdrTimeoutMs = builder.waitForIdrTimeoutMs;
    initialWaitForIdr = builder.initialWaitForIdr;
    initialWaitForIdrAfterSeek = builder.initialWaitForIdrAfterSeek;
    maxTcpInterleavedQueueAgeMs = tcpInterleavedBacklogResetMs;
    maxTcpInterleavedQueueDepth = tcpInterleavedBacklogResetPackets;
    maxRtpReorderQueueAgeMs = rtpReorderBacklogResetMs;
    maxRtpReorderQueueSpanMs = rtpReorderBacklogResetMs;
    maxRtpReorderQueueDepth = rtpReorderBacklogResetPackets;
  }

  /** Returns whether TCP interleaved backlog recovery is enabled. */
  public boolean isTcpInterleavedBacklogRecoveryEnabled() {
    return enabled && (maxTcpInterleavedQueueAgeMs > 0 || maxTcpInterleavedQueueDepth > 0);
  }

  /** Returns whether RTP reordering queue backlog recovery is enabled. */
  public boolean isRtpReorderBacklogRecoveryEnabled() {
    return enabled
        && (maxRtpReorderQueueAgeMs > 0
        || maxRtpReorderQueueSpanMs > 0
        || maxRtpReorderQueueDepth > 0);
  }

  /** Returns whether any backlog recovery behavior is enabled. */
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspBacklogRecoveryPolicy)) {
      return false;
    }
    RtspBacklogRecoveryPolicy other = (RtspBacklogRecoveryPolicy) obj;
    return enabled == other.enabled
        && tcpInterleavedBacklogWarnMs == other.tcpInterleavedBacklogWarnMs
        && tcpInterleavedBacklogResetMs == other.tcpInterleavedBacklogResetMs
        && tcpInterleavedBacklogResetPackets == other.tcpInterleavedBacklogResetPackets
        && rtpReorderBacklogWarnMs == other.rtpReorderBacklogWarnMs
        && rtpReorderBacklogResetMs == other.rtpReorderBacklogResetMs
        && rtpReorderBacklogResetPackets == other.rtpReorderBacklogResetPackets
        && waitForIdrTimeoutMs == other.waitForIdrTimeoutMs
        && initialWaitForIdr == other.initialWaitForIdr
        && initialWaitForIdrAfterSeek == other.initialWaitForIdrAfterSeek;
  }

  @Override
  public int hashCode() {
    int result = enabled ? 1 : 0;
    result = 31 * result + (int) (tcpInterleavedBacklogWarnMs ^ (tcpInterleavedBacklogWarnMs >>> 32));
    result = 31 * result + (int) (tcpInterleavedBacklogResetMs ^ (tcpInterleavedBacklogResetMs >>> 32));
    result = 31 * result + tcpInterleavedBacklogResetPackets;
    result = 31 * result + (int) (rtpReorderBacklogWarnMs ^ (rtpReorderBacklogWarnMs >>> 32));
    result = 31 * result + (int) (rtpReorderBacklogResetMs ^ (rtpReorderBacklogResetMs >>> 32));
    result = 31 * result + rtpReorderBacklogResetPackets;
    result = 31 * result + (int) (waitForIdrTimeoutMs ^ (waitForIdrTimeoutMs >>> 32));
    result = 31 * result + (initialWaitForIdr ? 1 : 0);
    result = 31 * result + (initialWaitForIdrAfterSeek ? 1 : 0);
    return result;
  }

  /** Builder for {@link RtspBacklogRecoveryPolicy}. */
  public static final class Builder {
    private boolean enabled;
    private long tcpInterleavedBacklogWarnMs;
    private long tcpInterleavedBacklogResetMs;
    private int tcpInterleavedBacklogResetPackets;
    private long rtpReorderBacklogWarnMs;
    private long rtpReorderBacklogResetMs;
    private int rtpReorderBacklogResetPackets;
    private long waitForIdrTimeoutMs;
    private boolean initialWaitForIdr;
    private boolean initialWaitForIdrAfterSeek;

    /** Sets whether low-latency backlog recovery is enabled. */
    @CanIgnoreReturnValue
    public Builder setEnabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /** Sets the TCP interleaved backlog warning age in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setTcpInterleavedBacklogWarnMs(long tcpInterleavedBacklogWarnMs) {
      checkArgument(tcpInterleavedBacklogWarnMs >= 0);
      this.tcpInterleavedBacklogWarnMs = tcpInterleavedBacklogWarnMs;
      return this;
    }

    /** Sets the TCP interleaved backlog reset age in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setTcpInterleavedBacklogResetMs(long tcpInterleavedBacklogResetMs) {
      checkArgument(tcpInterleavedBacklogResetMs >= 0);
      this.tcpInterleavedBacklogResetMs = tcpInterleavedBacklogResetMs;
      return this;
    }

    /** Sets the TCP interleaved backlog reset depth in RTP packets. */
    @CanIgnoreReturnValue
    public Builder setTcpInterleavedBacklogResetPackets(int tcpInterleavedBacklogResetPackets) {
      checkArgument(tcpInterleavedBacklogResetPackets >= 0);
      this.tcpInterleavedBacklogResetPackets = tcpInterleavedBacklogResetPackets;
      return this;
    }

    /** Sets the RTP reordering backlog warning span in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setRtpReorderBacklogWarnMs(long rtpReorderBacklogWarnMs) {
      checkArgument(rtpReorderBacklogWarnMs >= 0);
      this.rtpReorderBacklogWarnMs = rtpReorderBacklogWarnMs;
      return this;
    }

    /** Sets the RTP reordering backlog reset span/age in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setRtpReorderBacklogResetMs(long rtpReorderBacklogResetMs) {
      checkArgument(rtpReorderBacklogResetMs >= 0);
      this.rtpReorderBacklogResetMs = rtpReorderBacklogResetMs;
      return this;
    }

    /** Sets the RTP reordering backlog reset depth in RTP packets. */
    @CanIgnoreReturnValue
    public Builder setRtpReorderBacklogResetPackets(int rtpReorderBacklogResetPackets) {
      checkArgument(rtpReorderBacklogResetPackets >= 0);
      this.rtpReorderBacklogResetPackets = rtpReorderBacklogResetPackets;
      return this;
    }

    /** Sets the timeout for reporting prolonged wait for an IDR access unit. */
    @CanIgnoreReturnValue
    public Builder setWaitForIdrTimeoutMs(long waitForIdrTimeoutMs) {
      checkArgument(waitForIdrTimeoutMs >= 0);
      this.waitForIdrTimeoutMs = waitForIdrTimeoutMs;
      return this;
    }

    /** Sets whether H.264 starts in WAIT_IDR until a complete decodable IDR arrives. */
    @CanIgnoreReturnValue
    public Builder setInitialWaitForIdr(boolean initialWaitForIdr) {
      this.initialWaitForIdr = initialWaitForIdr;
      return this;
    }

    /** Sets whether H.264 re-enters WAIT_IDR after seek/reset. */
    @CanIgnoreReturnValue
    public Builder setInitialWaitForIdrAfterSeek(boolean initialWaitForIdrAfterSeek) {
      this.initialWaitForIdrAfterSeek = initialWaitForIdrAfterSeek;
      return this;
    }

    /** Sets the maximum TCP interleaved queue age in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setMaxTcpInterleavedQueueAgeMs(long maxTcpInterleavedQueueAgeMs) {
      checkArgument(maxTcpInterleavedQueueAgeMs >= 0);
      this.tcpInterleavedBacklogResetMs = maxTcpInterleavedQueueAgeMs;
      return this;
    }

    /** Sets the maximum TCP interleaved queue depth in RTP packets. */
    @CanIgnoreReturnValue
    public Builder setMaxTcpInterleavedQueueDepth(int maxTcpInterleavedQueueDepth) {
      checkArgument(maxTcpInterleavedQueueDepth >= 0);
      this.tcpInterleavedBacklogResetPackets = maxTcpInterleavedQueueDepth;
      return this;
    }

    /** Sets the maximum RTP reordering queue age in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setMaxRtpReorderQueueAgeMs(long maxRtpReorderQueueAgeMs) {
      checkArgument(maxRtpReorderQueueAgeMs >= 0);
      this.rtpReorderBacklogResetMs = maxRtpReorderQueueAgeMs;
      return this;
    }

    /** Sets the maximum RTP reordering queue span in milliseconds. */
    @CanIgnoreReturnValue
    public Builder setMaxRtpReorderQueueSpanMs(long maxRtpReorderQueueSpanMs) {
      checkArgument(maxRtpReorderQueueSpanMs >= 0);
      this.rtpReorderBacklogResetMs = maxRtpReorderQueueSpanMs;
      return this;
    }

    /** Sets the maximum RTP reordering queue depth in RTP packets. */
    @CanIgnoreReturnValue
    public Builder setMaxRtpReorderQueueDepth(int maxRtpReorderQueueDepth) {
      checkArgument(maxRtpReorderQueueDepth >= 0);
      this.rtpReorderBacklogResetPackets = maxRtpReorderQueueDepth;
      return this;
    }

    /** Builds the policy. */
    public RtspBacklogRecoveryPolicy build() {
      return new RtspBacklogRecoveryPolicy(this);
    }
  }
}
