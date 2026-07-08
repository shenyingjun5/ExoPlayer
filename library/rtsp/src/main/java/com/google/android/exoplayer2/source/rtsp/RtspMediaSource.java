/*
 * Copyright 2021 The Android Open Source Project
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
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.net.Uri;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.source.BaseMediaSource;
import com.google.android.exoplayer2.source.ForwardingTimeline;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Util;
import com.google.common.base.Ascii;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import javax.net.SocketFactory;

/**
 * An Rtsp {@link MediaSource}
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class RtspMediaSource extends BaseMediaSource {

  static {
    ExoPlayerLibraryInfo.registerModule("goog.exo.rtsp");
  }

  /** The default value for {@link Factory#setTimeoutMs}. */
  public static final long DEFAULT_TIMEOUT_MS = 8000;

  /**
   * Factory for {@link RtspMediaSource}
   *
   * <p>This factory doesn't support the following methods from {@link MediaSourceFactory}:
   *
   * <ul>
   *   <li>{@link #setDrmSessionManagerProvider(DrmSessionManagerProvider)}
   *   <li>{@link #setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy)}
   * </ul>
   */
  @SuppressWarnings("deprecation") // Implement deprecated type for backwards compatibility.
  public static final class Factory implements MediaSourceFactory {

    private long timeoutMs;
    private String userAgent;
    private SocketFactory socketFactory;
    private @RtspTransportStrategy.Strategy int transportStrategy;
    private boolean debugLoggingEnabled;
    @Nullable private RtspDiagnosticsListener rtspDiagnosticsListener;
    @Nullable private RtspFeedbackListener rtspFeedbackListener;
    private RtcpFeedbackPolicy rtcpFeedbackPolicy;
    private boolean rtspPacketDiagnosticsEnabled;

    public Factory() {
      timeoutMs = DEFAULT_TIMEOUT_MS;
      userAgent = ExoPlayerLibraryInfo.VERSION_SLASHY;
      socketFactory = SocketFactory.getDefault();
      rtcpFeedbackPolicy = RtcpFeedbackPolicy.DEFAULT;
      transportStrategy = RtspTransportStrategy.EXOPLAYER_DEFAULT;
    }

    /**
     * Sets whether to force using TCP as the default RTP transport.
     *
     * <p>The default value is {@code false}, the source will first try streaming RTSP with UDP. If
     * no data is received on the UDP channel (for instance, when streaming behind a NAT) for a
     * while, the source will switch to streaming using TCP. If this value is set to {@code true},
     * the source will always use TCP for streaming.
     *
     * @param forceUseRtpTcp Whether force to use TCP for streaming.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setForceUseRtpTcp(boolean forceUseRtpTcp) {
      this.transportStrategy =
          forceUseRtpTcp ? RtspTransportStrategy.FORCE_TCP : RtspTransportStrategy.EXOPLAYER_DEFAULT;
      return this;
    }

    /**
     * Sets the RTP transport strategy.
     *
     * <p>The default value is {@link RtspTransportStrategy#EXOPLAYER_DEFAULT}, which preserves
     * ExoPlayer's original UDP-first behavior with TCP fallback. This setter is additive and does not
     * change the behavior of {@link #setForceUseRtpTcp(boolean)}.
     *
     * @param transportStrategy The RTP transport strategy.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRtspTransportStrategy(
        @RtspTransportStrategy.Strategy int transportStrategy) {
      this.transportStrategy = transportStrategy;
      return this;
    }

    /**
     * Sets the user agent, the default value is {@link ExoPlayerLibraryInfo#VERSION_SLASHY}.
     *
     * @param userAgent The user agent.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setUserAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /**
     * Sets a socket factory for {@link RtspClient}'s connection, the default value is {@link
     * SocketFactory#getDefault()}.
     *
     * @param socketFactory A socket factory.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setSocketFactory(SocketFactory socketFactory) {
      this.socketFactory = socketFactory;
      return this;
    }

    /**
     * Sets whether to log RTSP messages, the default value is {@code false}.
     *
     * <p>This option presents a privacy risk, since it may expose sensitive information such as
     * user's credentials.
     *
     * @param debugLoggingEnabled Whether to log RTSP messages.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setDebugLoggingEnabled(boolean debugLoggingEnabled) {
      this.debugLoggingEnabled = debugLoggingEnabled;
      return this;
    }

    /**
     * Sets an optional listener for low-level RTSP/RTP diagnostics.
     *
     * <p>The default value is {@code null}. RTSP transport callbacks are invoked on the playback
     * thread. RTP packet callbacks are invoked on the RTP loader thread.
     *
     * @param listener The diagnostics listener, or {@code null} to disable diagnostics callbacks.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRtspDiagnosticsListener(@Nullable RtspDiagnosticsListener listener) {
      this.rtspDiagnosticsListener = listener;
      return this;
    }

    /**
     * Sets whether high-frequency per-packet RTSP/RTP diagnostics are emitted.
     *
     * <p>The default value is {@code false}. Keep this disabled in production unless a short-lived
     * debug or issue-report capture explicitly needs packet-level events.
     *
     * @param enabled Whether to emit high-frequency packet diagnostics.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRtspPacketDiagnosticsEnabled(boolean enabled) {
      this.rtspPacketDiagnosticsEnabled = enabled;
      return this;
    }

    /**
     * Sets an optional listener for RTCP feedback lifecycle events.
     *
     * <p>The default value is {@code null}. This first implementation phase wires the listener
     * through the RTSP stack without sending RTCP feedback packets.
     *
     * @param listener The feedback listener, or {@code null} to disable callbacks.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRtspFeedbackListener(@Nullable RtspFeedbackListener listener) {
      this.rtspFeedbackListener = listener;
      return this;
    }

    /**
     * Sets the policy for RTCP key-frame feedback requests.
     *
     * <p>The default value is {@link RtcpFeedbackPolicy#DEFAULT}.
     *
     * @param policy The RTCP feedback policy.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRtcpFeedbackPolicy(RtcpFeedbackPolicy policy) {
      this.rtcpFeedbackPolicy = checkNotNull(policy);
      return this;
    }

    /**
     * Sets the timeout in milliseconds, the default value is {@link #DEFAULT_TIMEOUT_MS}.
     *
     * <p>A positive number of milliseconds to wait before lack of received RTP packets is treated
     * as the end of input.
     *
     * @param timeoutMs The timeout measured in milliseconds.
     * @return This Factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setTimeoutMs(@IntRange(from = 1) long timeoutMs) {
      checkArgument(timeoutMs > 0);
      this.timeoutMs = timeoutMs;
      return this;
    }

    /** Does nothing. {@link RtspMediaSource} does not support DRM. */
    @Override
    public Factory setDrmSessionManagerProvider(DrmSessionManagerProvider drmSessionManager) {
      return this;
    }

    /** Does nothing. {@link RtspMediaSource} does not support error handling policies. */
    @Override
    public Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      // TODO(internal b/172331505): Implement support.
      return this;
    }

    @Override
    public @C.ContentType int[] getSupportedTypes() {
      return new int[] {C.CONTENT_TYPE_RTSP};
    }

    /**
     * Returns a new {@link RtspMediaSource} using the current parameters.
     *
     * @param mediaItem The {@link MediaItem}.
     * @return The new {@link RtspMediaSource}.
     * @throws NullPointerException if {@link MediaItem#localConfiguration} is {@code null}.
     */
    @Override
    public RtspMediaSource createMediaSource(MediaItem mediaItem) {
      checkNotNull(mediaItem.localConfiguration);
      return new RtspMediaSource(
          mediaItem,
          shouldUseTcp(mediaItem)
              ? new TransferRtpDataChannelFactory(timeoutMs)
              : new UdpDataSourceRtpDataChannelFactory(
                  timeoutMs, /* tcpFallbackEnabled= */ shouldAllowTcpFallback()),
          userAgent,
          socketFactory,
          debugLoggingEnabled,
          rtspDiagnosticsListener,
          rtspFeedbackListener,
          rtcpFeedbackPolicy,
          rtspPacketDiagnosticsEnabled,
          transportStrategy);
    }

    private boolean shouldUseTcp(MediaItem mediaItem) {
      if (transportStrategy == RtspTransportStrategy.FORCE_TCP) {
        return true;
      }
      @Nullable String scheme = checkNotNull(mediaItem.localConfiguration).uri.getScheme();
      return scheme != null && Ascii.equalsIgnoreCase("rtspt", scheme);
    }

    private boolean shouldAllowTcpFallback() {
      return transportStrategy != RtspTransportStrategy.FORCE_UDP;
    }
  }

  /** Thrown when an exception or error is encountered during loading an RTSP stream. */
  public static class RtspPlaybackException extends IOException {
    public RtspPlaybackException(String message) {
      super(message);
    }

    public RtspPlaybackException(Throwable e) {
      super(e);
    }

    public RtspPlaybackException(String message, Throwable e) {
      super(message, e);
    }
  }

  /** Thrown when an RTSP Unsupported Transport error (461) is encountered during RTSP Setup. */
  public static final class RtspUdpUnsupportedTransportException extends RtspPlaybackException {
    public RtspUdpUnsupportedTransportException(String message) {
      super(message);
    }
  }

  private final MediaItem mediaItem;
  private final RtpDataChannel.Factory rtpDataChannelFactory;
  private final String userAgent;
  private final Uri uri;
  private final SocketFactory socketFactory;
  private final boolean debugLoggingEnabled;
  @Nullable private final RtspDiagnosticsListener rtspDiagnosticsListener;
  @Nullable private final RtspFeedbackListener rtspFeedbackListener;
  private final RtcpFeedbackPolicy rtcpFeedbackPolicy;
  private final boolean rtspPacketDiagnosticsEnabled;
  private final @RtspTransportStrategy.Strategy int transportStrategy;
  private final ArrayList<RtspMediaPeriod> activeMediaPeriods;
  private final Object activeMediaPeriodsLock;

  private long timelineDurationUs;
  private boolean timelineIsSeekable;
  private boolean timelineIsLive;
  private boolean timelineIsPlaceholder;

  @VisibleForTesting
  /* package */ RtspMediaSource(
      MediaItem mediaItem,
      RtpDataChannel.Factory rtpDataChannelFactory,
      String userAgent,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled) {
    this(
        mediaItem,
        rtpDataChannelFactory,
        userAgent,
        socketFactory,
        debugLoggingEnabled,
        /* rtspDiagnosticsListener= */ null,
        /* rtspFeedbackListener= */ null,
        RtcpFeedbackPolicy.DEFAULT,
        /* rtspPacketDiagnosticsEnabled= */ false,
        RtspTransportStrategy.EXOPLAYER_DEFAULT);
  }

  @VisibleForTesting
  /* package */ RtspMediaSource(
      MediaItem mediaItem,
      RtpDataChannel.Factory rtpDataChannelFactory,
      String userAgent,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtspFeedbackListener rtspFeedbackListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      boolean rtspPacketDiagnosticsEnabled,
      @RtspTransportStrategy.Strategy int transportStrategy) {
    this.mediaItem = mediaItem;
    this.rtpDataChannelFactory = rtpDataChannelFactory;
    this.userAgent = userAgent;
    this.uri = maybeConvertRtsptUriScheme(checkNotNull(this.mediaItem.localConfiguration).uri);
    this.socketFactory = socketFactory;
    this.debugLoggingEnabled = debugLoggingEnabled;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.rtspFeedbackListener = rtspFeedbackListener;
    this.rtcpFeedbackPolicy = checkNotNull(rtcpFeedbackPolicy);
    this.rtspPacketDiagnosticsEnabled = rtspPacketDiagnosticsEnabled;
    this.transportStrategy = transportStrategy;
    this.activeMediaPeriods = new ArrayList<>();
    this.activeMediaPeriodsLock = new Object();
    this.timelineDurationUs = C.TIME_UNSET;
    this.timelineIsPlaceholder = true;
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    notifySourceInfoRefreshed();
  }

  @Override
  protected void releaseSourceInternal() {
    // Do nothing.
  }

  @Override
  public MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() {
    // Do nothing.
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    RtspMediaPeriod mediaPeriod =
        new RtspMediaPeriod(
        allocator,
        rtpDataChannelFactory,
        uri,
        new RtspMediaPeriod.Listener() {
          @Override
          public void onSourceInfoRefreshed(RtspSessionTiming timing) {
            timelineDurationUs = Util.msToUs(timing.getDurationMs());
            timelineIsSeekable = !timing.isLive();
            timelineIsLive = timing.isLive();
            timelineIsPlaceholder = false;
            notifySourceInfoRefreshed();
          }

          @Override
          public void onSeekingUnsupported() {
            timelineIsSeekable = false;
            notifySourceInfoRefreshed();
          }
        },
        userAgent,
        socketFactory,
        debugLoggingEnabled,
        rtspDiagnosticsListener,
        rtspFeedbackListener,
        rtcpFeedbackPolicy,
        rtspPacketDiagnosticsEnabled);
    synchronized (activeMediaPeriodsLock) {
      activeMediaPeriods.add(mediaPeriod);
    }
    return mediaPeriod;
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    RtspMediaPeriod rtspMediaPeriod = (RtspMediaPeriod) mediaPeriod;
    synchronized (activeMediaPeriodsLock) {
      activeMediaPeriods.remove(rtspMediaPeriod);
    }
    rtspMediaPeriod.release();
  }

  /**
   * Requests an RTCP PLI/FIR key-frame feedback packet on active RTSP periods.
   *
   * @return Whether at least one request was sent.
   */
  public boolean requestKeyFrame(@RtcpFeedbackReason.Reason int reason) {
    ArrayList<RtspMediaPeriod> mediaPeriods;
    synchronized (activeMediaPeriodsLock) {
      mediaPeriods = new ArrayList<>(activeMediaPeriods);
    }
    boolean requested = false;
    for (int i = 0; i < mediaPeriods.size(); i++) {
      requested |= mediaPeriods.get(i).requestKeyFrame(reason);
    }
    return requested;
  }

  // Internal methods.

  private static Uri maybeConvertRtsptUriScheme(Uri uri) {
    @Nullable String scheme = uri.getScheme();
    if (scheme == null || !Ascii.equalsIgnoreCase("rtspt", scheme)) {
      return uri;
    }
    return Uri.parse("rtsp" + uri.toString().substring(5));
  }

  private void notifySourceInfoRefreshed() {
    Timeline timeline =
        new SinglePeriodTimeline(
            timelineDurationUs,
            timelineIsSeekable,
            /* isDynamic= */ false,
            /* useLiveConfiguration= */ timelineIsLive,
            /* manifest= */ null,
            mediaItem);
    if (timelineIsPlaceholder) {
      timeline =
          new ForwardingTimeline(timeline) {
            @Override
            public Window getWindow(
                int windowIndex, Window window, long defaultPositionProjectionUs) {
              super.getWindow(windowIndex, window, defaultPositionProjectionUs);
              window.isPlaceholder = true;
              return window;
            }

            @Override
            public Period getPeriod(int periodIndex, Period period, boolean setIds) {
              super.getPeriod(periodIndex, period, setIds);
              period.isPlaceholder = true;
              return period;
            }
          };
    }
    refreshSourceInfo(timeline);
  }

  @VisibleForTesting
  /* package */ @Nullable RtspDiagnosticsListener getRtspDiagnosticsListener() {
    return rtspDiagnosticsListener;
  }

  @VisibleForTesting
  /* package */ @Nullable RtspFeedbackListener getRtspFeedbackListener() {
    return rtspFeedbackListener;
  }

  @VisibleForTesting
  /* package */ RtcpFeedbackPolicy getRtcpFeedbackPolicy() {
    return rtcpFeedbackPolicy;
  }

  @VisibleForTesting
  /* package */ boolean getRtspPacketDiagnosticsEnabled() {
    return rtspPacketDiagnosticsEnabled;
  }

  @VisibleForTesting
  /* package */ Uri getUri() {
    return uri;
  }

  @VisibleForTesting
  /* package */ RtpDataChannel.Factory getRtpDataChannelFactory() {
    return rtpDataChannelFactory;
  }

  @VisibleForTesting
  /* package */ @RtspTransportStrategy.Strategy int getRtspTransportStrategy() {
    return transportStrategy;
  }
}
