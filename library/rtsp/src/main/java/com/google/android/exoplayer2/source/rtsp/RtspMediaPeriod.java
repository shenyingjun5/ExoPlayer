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

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static com.google.android.exoplayer2.util.Util.usToMs;
import static java.lang.Math.min;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.SampleQueue;
import com.google.android.exoplayer2.source.SampleQueue.UpstreamFormatChangedListener;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SampleStream.ReadDataResult;
import com.google.android.exoplayer2.source.SampleStream.ReadFlags;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.rtsp.RtspClient.PlaybackEventListener;
import com.google.android.exoplayer2.source.rtsp.RtspClient.SessionInfoListener;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource.RtspPlaybackException;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSourceUtil;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.Loader.Loadable;
import com.google.android.exoplayer2.upstream.UdpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.checkerframework.checker.nullness.compatqual.NullableType;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link MediaPeriod} that loads an RTSP stream.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class RtspMediaPeriod implements MediaPeriod {

  /** Listener for information about the period. */
  interface Listener {

    /** Called when the {@link RtspSessionTiming} is available. */
    void onSourceInfoRefreshed(RtspSessionTiming timing);

    /** Called when the RTSP server does not support seeking. */
    default void onSeekingUnsupported() {}
  }

  /** The maximum times to retry if the underlying data channel failed to bind. */
  private static final int PORT_BINDING_MAX_RETRY_COUNT = 3;
  private static final int MAX_SAMPLE_RTP_TIMESTAMP_MAPPINGS = 256;

  private final Allocator allocator;
  private final Handler handler;
  private final InternalListener internalListener;
  private final RtspClient rtspClient;
  private final List<RtspLoaderWrapper> rtspLoaderWrappers;
  private final List<RtpLoadInfo> selectedLoadInfos;
  private final Listener listener;
  private final RtpDataChannel.Factory rtpDataChannelFactory;
  @Nullable private final RtspDiagnosticsListener rtspDiagnosticsListener;
  @Nullable private final RtspDiagnosticsListener forwardingRtspDiagnosticsListener;
  @Nullable private final RtspFeedbackListener rtspFeedbackListener;
  private final RtcpFeedbackPolicy rtcpFeedbackPolicy;
  private final RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy;
  private final boolean rtspPacketDiagnosticsEnabled;
  @Nullable private final Object sampleRtpTimestampMappingsLock;
  @Nullable private final ArrayList<SampleRtpTimestampMapping> sampleRtpTimestampMappings;
  @Nullable private final SparseIntArray sampleRtpTimestampMappingMissingStatuses;

  private @MonotonicNonNull Callback callback;
  private @MonotonicNonNull ImmutableList<TrackGroup> trackGroups;
  @Nullable private IOException preparationError;
  @Nullable private RtspPlaybackException playbackException;

  private long requestedSeekPositionUs;
  private long pendingSeekPositionUs;
  private long pendingSeekPositionUsForTcpRetry;
  private boolean loadingFinished;
  private boolean notifyDiscontinuity;
  private boolean released;
  private boolean prepared;
  private boolean trackSelected;
  private int portBindingRetryCount;
  private boolean isUsingRtpTcp;
  private boolean sampleQueueBacklogRecoverySignaled;
  private int recoveryGeneration;

  /**
   * Creates an RTSP media period.
   *
   * @param allocator An {@link Allocator} from which to obtain media buffer allocations.
   * @param rtpDataChannelFactory A {@link RtpDataChannel.Factory} for {@link RtpDataChannel}.
   * @param uri The RTSP playback {@link Uri}.
   * @param listener A {@link Listener} to receive session information updates.
   * @param userAgent The user agent.
   * @param socketFactory A socket factory for {@link RtspClient}'s connection.
   * @param debugLoggingEnabled Whether to log RTSP messages.
   */
  public RtspMediaPeriod(
      Allocator allocator,
      RtpDataChannel.Factory rtpDataChannelFactory,
      Uri uri,
      Listener listener,
      String userAgent,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled) {
    this(
        allocator,
        rtpDataChannelFactory,
        uri,
        listener,
        userAgent,
        socketFactory,
        debugLoggingEnabled,
        /* rtspDiagnosticsListener= */ null,
        /* rtspFeedbackListener= */ null,
        RtcpFeedbackPolicy.DEFAULT,
        RtspBacklogRecoveryPolicy.DISABLED,
        /* rtspPacketDiagnosticsEnabled= */ false);
  }

  public RtspMediaPeriod(
      Allocator allocator,
      RtpDataChannel.Factory rtpDataChannelFactory,
      Uri uri,
      Listener listener,
      String userAgent,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      @Nullable RtspFeedbackListener rtspFeedbackListener,
      RtcpFeedbackPolicy rtcpFeedbackPolicy,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy,
      boolean rtspPacketDiagnosticsEnabled) {
    this.allocator = allocator;
    this.rtpDataChannelFactory = rtpDataChannelFactory;
    this.listener = listener;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.forwardingRtspDiagnosticsListener =
        rtspDiagnosticsListener == null ? null : new ForwardingRtspDiagnosticsListener();
    this.rtspFeedbackListener = rtspFeedbackListener;
    this.rtcpFeedbackPolicy = checkNotNull(rtcpFeedbackPolicy);
    this.rtspBacklogRecoveryPolicy = checkNotNull(rtspBacklogRecoveryPolicy);
    this.rtspPacketDiagnosticsEnabled = rtspPacketDiagnosticsEnabled;
    if (rtspDiagnosticsListener != null && rtspPacketDiagnosticsEnabled) {
      sampleRtpTimestampMappingsLock = new Object();
      sampleRtpTimestampMappings = new ArrayList<>();
      sampleRtpTimestampMappingMissingStatuses = new SparseIntArray();
    } else {
      sampleRtpTimestampMappingsLock = null;
      sampleRtpTimestampMappings = null;
      sampleRtpTimestampMappingMissingStatuses = null;
    }

    handler = Util.createHandlerForCurrentLooper();
    internalListener = new InternalListener();
    rtspClient =
        new RtspClient(
            /* sessionInfoListener= */ internalListener,
            /* playbackEventListener= */ internalListener,
            /* userAgent= */ userAgent,
            /* uri= */ uri,
            socketFactory,
            debugLoggingEnabled,
            forwardingRtspDiagnosticsListener,
            rtspFeedbackListener,
            rtcpFeedbackPolicy);
    rtspLoaderWrappers = new ArrayList<>();
    selectedLoadInfos = new ArrayList<>();

    pendingSeekPositionUs = C.TIME_UNSET;
    requestedSeekPositionUs = C.TIME_UNSET;
    pendingSeekPositionUsForTcpRetry = C.TIME_UNSET;
  }

  /* package */ @Nullable RtspDiagnosticsListener getRtspDiagnosticsListener() {
    return rtspDiagnosticsListener;
  }

  /* package */ @Nullable RtspDiagnosticsListener getForwardingRtspDiagnosticsListenerForTesting() {
    return forwardingRtspDiagnosticsListener;
  }

  /* package */ @Nullable RtspFeedbackListener getRtspFeedbackListener() {
    return rtspFeedbackListener;
  }

  /* package */ RtcpFeedbackPolicy getRtcpFeedbackPolicy() {
    return rtcpFeedbackPolicy;
  }

  /* package */ RtspBacklogRecoveryPolicy getRtspBacklogRecoveryPolicy() {
    return rtspBacklogRecoveryPolicy;
  }

  /* package */ boolean getRtspPacketDiagnosticsEnabled() {
    return rtspPacketDiagnosticsEnabled;
  }

  /* package */ boolean requestKeyFrame(@RtcpFeedbackReason.Reason int reason) {
    if (Looper.myLooper() != handler.getLooper()) {
      return handler.post(() -> requestKeyFrameInternal(reason));
    }
    return requestKeyFrameInternal(reason);
  }

  /* package */ RtcpFeedbackResult requestRtcpFeedback(
      @RtcpFeedbackType.Type int feedbackType, @RtcpFeedbackReason.Reason int reason) {
    if (Looper.myLooper() != handler.getLooper()) {
      AtomicReference<RtcpFeedbackResult> resultReference = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      boolean posted =
          handler.post(
              () -> {
                try {
                  resultReference.set(requestRtcpFeedbackInternal(feedbackType, reason));
                } finally {
                  latch.countDown();
                }
              });
      if (!posted) {
        return createFeedbackResult(
            RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler unavailable");
      }
      try {
        if (!latch.await(1000, TimeUnit.MILLISECONDS)) {
          return createFeedbackResult(
              RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler timeout");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return createFeedbackResult(
            RtcpFeedbackResult.FAILED, /* request= */ null, "interrupted");
      }
      @Nullable RtcpFeedbackResult result = resultReference.get();
      return result != null
          ? result
          : createFeedbackResult(
              RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler failed");
    }
    return requestRtcpFeedbackInternal(feedbackType, reason);
  }

  private boolean requestKeyFrameInternal(@RtcpFeedbackReason.Reason int reason) {
    boolean requested = false;
    List<RtpLoadInfo> loadInfos =
        selectedLoadInfos.isEmpty() ? new ArrayList<>() : selectedLoadInfos;
    if (selectedLoadInfos.isEmpty()) {
      for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
        loadInfos.add(rtspLoaderWrappers.get(i).loadInfo);
      }
    }
    for (int i = 0; i < loadInfos.size(); i++) {
      requested |= loadInfos.get(i).requestKeyFrame(reason);
    }
    return requested;
  }

  private RtcpFeedbackResult requestRtcpFeedbackInternal(
      @RtcpFeedbackType.Type int feedbackType, @RtcpFeedbackReason.Reason int reason) {
    RtcpFeedbackResult result =
        createFeedbackResult(
            RtcpFeedbackResult.FAILED, /* request= */ null, "no active RTSP RTP track");
    List<RtpLoadInfo> loadInfos =
        selectedLoadInfos.isEmpty() ? new ArrayList<>() : selectedLoadInfos;
    if (selectedLoadInfos.isEmpty()) {
      for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
        loadInfos.add(rtspLoaderWrappers.get(i).loadInfo);
      }
    }
    for (int i = 0; i < loadInfos.size(); i++) {
      RtcpFeedbackResult trackResult = loadInfos.get(i).requestRtcpFeedback(feedbackType, reason);
      if (trackResult.status == RtcpFeedbackResult.SCHEDULED) {
        return trackResult;
      }
      if (result.status == RtcpFeedbackResult.FAILED
          || trackResult.status == RtcpFeedbackResult.THROTTLED) {
        result = trackResult;
      }
    }
    return result;
  }

  private static RtcpFeedbackResult createFeedbackResult(
      @RtcpFeedbackResult.Status int status,
      @Nullable RtcpFeedbackRequest request,
      @Nullable String detail) {
    return new RtcpFeedbackResult(status, request, SystemClock.elapsedRealtime(), detail);
  }

  /** Releases the {@link RtspMediaPeriod}. */
  public void release() {
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      rtspLoaderWrappers.get(i).release();
    }
    Util.closeQuietly(rtspClient);
    released = true;
  }

  @Override
  public void prepare(Callback callback, long positionUs) {
    this.callback = callback;

    try {
      rtspClient.start();
    } catch (IOException e) {
      preparationError = e;
      Util.closeQuietly(rtspClient);
    }
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    if (preparationError != null) {
      throw preparationError;
    }
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    checkState(prepared);
    return new TrackGroupArray(checkNotNull(trackGroups).toArray(new TrackGroup[0]));
  }

  @Override
  public ImmutableList<StreamKey> getStreamKeys(List<ExoTrackSelection> trackSelections) {
    return ImmutableList.of();
  }

  @Override
  public long selectTracks(
      @NullableType ExoTrackSelection[] selections,
      boolean[] mayRetainStreamFlags,
      @NullableType SampleStream[] streams,
      boolean[] streamResetFlags,
      long positionUs) {

    // Deselect old tracks.
    // Input array streams contains the streams selected in the previous track selection.
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
        streams[i] = null;
      }
    }

    // Select new tracks.
    selectedLoadInfos.clear();
    for (int i = 0; i < selections.length; i++) {
      TrackSelection selection = selections[i];
      if (selection == null) {
        continue;
      }

      TrackGroup trackGroup = selection.getTrackGroup();
      int trackGroupIndex = checkNotNull(trackGroups).indexOf(trackGroup);
      selectedLoadInfos.add(checkNotNull(rtspLoaderWrappers.get(trackGroupIndex)).loadInfo);

      // Find the sampleStreamWrapper that contains this track group.
      if (trackGroups.contains(trackGroup)) {
        if (streams[i] == null) {
          streams[i] = new SampleStreamImpl(trackGroupIndex);
          // Update flag for newly created SampleStream.
          streamResetFlags[i] = true;
        }
      }
    }

    // Cancel non-selected loadables.
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtspLoaderWrapper loadControl = rtspLoaderWrappers.get(i);
      if (!selectedLoadInfos.contains(loadControl.loadInfo)) {
        loadControl.cancelLoad();
      }
    }

    trackSelected = true;
    if (positionUs != 0) {
      // Track selection is performed only once in RTSP streams.
      requestedSeekPositionUs = positionUs;
      pendingSeekPositionUs = positionUs;
      pendingSeekPositionUsForTcpRetry = positionUs;
    }
    maybeSetupTracks();
    return positionUs;
  }

  @Override
  public void discardBuffer(long positionUs, boolean toKeyframe) {
    if (isSeekPending()) {
      return;
    }

    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtspLoaderWrapper loaderWrapper = rtspLoaderWrappers.get(i);
      if (!loaderWrapper.canceled) {
        loaderWrapper.sampleQueue.discardTo(positionUs, toKeyframe, /* stopAtReadPosition= */ true);
      }
    }
  }

  @Override
  public long readDiscontinuity() {
    // Discontinuity only happens in RTSP when seeking an unexpectedly un-seekable RTSP server (a
    // server that doesn't include the required RTP-Info header in its PLAY responses). This only
    // applies to seeks made before receiving the first RTSP PLAY response. The playback can only
    // start from time zero in this case.
    if (notifyDiscontinuity) {
      notifyDiscontinuity = false;
      return 0;
    }
    return C.TIME_UNSET;
  }

  @Override
  public long seekToUs(long positionUs) {
    // Handles all RTSP seeking cases:
    // 1. Seek before the first RTP/UDP packet is received. The seek position is cached to be used
    //    after retrying playback with RTP/TCP.
    // 2a. Normal RTSP seek: if no additional seek is requested after the first seek. Request RTSP
    //   PAUSE and then PLAY at the seek position.
    // 2b. If additional seek is requested after the first seek, records the new seek position,
    //   2b.1. If RTSP PLAY (for the first seek) is already sent, the new seek position is used to
    //     initiate another seek upon receiving PLAY response by invoking this method again.
    //   2b.2. If RTSP PLAY (for the first seek) has not been sent, the new seek position will be
    //     used in the following PLAY request.

    // TODO(internal: b/213153670) Handle dropped seek position.
    if (getBufferedPositionUs() == 0 && !isUsingRtpTcp) {
      // Stores the seek position for later, if no RTP packet is received when using UDP.
      pendingSeekPositionUsForTcpRetry = positionUs;
      return positionUs;
    }

    discardBuffer(positionUs, /* toKeyframe= */ false);
    requestedSeekPositionUs = positionUs;

    if (isSeekPending()) {
      switch (rtspClient.getState()) {
        case RtspClient.RTSP_STATE_READY:
          // PLAY request is sent, yet to receive the response. requestedSeekPositionUs stores the
          // new position to do another seek upon receiving the PLAY response.
          return positionUs;
        case RtspClient.RTSP_STATE_PLAYING:
          // Pending PAUSE response, updates client with the newest seek position for the following
          // PLAY request.
          pendingSeekPositionUs = positionUs;
          rtspClient.seekToUs(pendingSeekPositionUs);
          return positionUs;
        case RtspClient.RTSP_STATE_UNINITIALIZED:
        case RtspClient.RTSP_STATE_INIT:
        default:
          // Never happens.
          throw new IllegalStateException();
      }
    }

    if (seekInsideBufferUs(positionUs)) {
      return positionUs;
    }

    pendingSeekPositionUs = positionUs;

    if (loadingFinished) {
      for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
        rtspLoaderWrappers.get(i).resumeLoad();
      }

      if (isUsingRtpTcp) {
        rtspClient.startPlayback(/* offsetMs= */ usToMs(positionUs));
      } else {
        rtspClient.seekToUs(positionUs);
      }

    } else {
      rtspClient.seekToUs(positionUs);
    }

    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      rtspLoaderWrappers.get(i).seekTo(positionUs);
    }
    return positionUs;
  }

  @Override
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    return positionUs;
  }

  @Override
  public long getBufferedPositionUs() {
    if (loadingFinished || rtspLoaderWrappers.isEmpty()) {
      return C.TIME_END_OF_SOURCE;
    }

    if (requestedSeekPositionUs != C.TIME_UNSET) {
      return requestedSeekPositionUs;
    }

    boolean allLoaderWrappersAreCanceled = true;
    long bufferedPositionUs = Long.MAX_VALUE;
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtspLoaderWrapper loaderWrapper = rtspLoaderWrappers.get(i);
      if (!loaderWrapper.canceled) {
        bufferedPositionUs = min(bufferedPositionUs, loaderWrapper.getBufferedPositionUs());
        allLoaderWrappersAreCanceled = false;
      }
    }

    return allLoaderWrappersAreCanceled || bufferedPositionUs == Long.MIN_VALUE
        ? 0
        : bufferedPositionUs;
  }

  @Override
  public long getNextLoadPositionUs() {
    return getBufferedPositionUs();
  }

  @Override
  public boolean continueLoading(long positionUs) {
    return isLoading();
  }

  @Override
  public boolean isLoading() {
    return !loadingFinished;
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    // Do nothing.
  }

  // SampleStream methods.

  /* package */ boolean isReady(int trackGroupIndex) {
    return !suppressRead() && rtspLoaderWrappers.get(trackGroupIndex).isSampleQueueReady();
  }

  @ReadDataResult
  /* package */ int readData(
      int sampleQueueIndex,
      FormatHolder formatHolder,
      DecoderInputBuffer buffer,
      @ReadFlags int readFlags) {
    if (suppressRead()) {
      return C.RESULT_NOTHING_READ;
    }
    RtspLoaderWrapper loaderWrapper = rtspLoaderWrappers.get(sampleQueueIndex);
    @ReadDataResult int result = loaderWrapper.read(formatHolder, buffer, readFlags);
    if (result == C.RESULT_BUFFER_READ
        && !buffer.isEndOfStream()
        && (readFlags & SampleStream.FLAG_PEEK) == 0
        && (readFlags & SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0
        && rtspDiagnosticsListener != null
        && rtspPacketDiagnosticsEnabled) {
      long readElapsedRealtimeMs = SystemClock.elapsedRealtime();
      SampleRtpTimestampLookupResult mappingResult =
          lookupSampleRtpTimestampForDiagnostics(loaderWrapper.loadInfo.trackId, buffer.timeUs);
      long sampleQueueBufferedAheadMs =
          getBufferedAheadMs(loaderWrapper.getBufferedPositionUs(), buffer.timeUs);
      long mediaPeriodBufferedAheadMs = getBufferedAheadMs(getBufferedPositionUs(), buffer.timeUs);
      rtspDiagnosticsListener.onRtspSampleRead(
          new RtspSampleReadStats(
              loaderWrapper.loadInfo.trackId,
              sampleQueueIndex,
              loaderWrapper.loadInfo.mediaTrack.payloadFormat.format.sampleMimeType,
              buffer.timeUs,
              mappingResult.rtpTimestamp,
              mappingResult.status,
              readElapsedRealtimeMs,
              sampleQueueBufferedAheadMs,
              mediaPeriodBufferedAheadMs));
      rtspDiagnosticsListener.onRtspDecoderInputQueued(
          new RtspDecoderInputQueuedStats(
              loaderWrapper.loadInfo.trackId,
              sampleQueueIndex,
              buffer.timeUs,
              mappingResult.rtpTimestamp,
              mappingResult.status,
              readElapsedRealtimeMs));
      maybeNotifySampleQueueBacklogRecoveryRequired(
          loaderWrapper.loadInfo.trackId,
          loaderWrapper.loadInfo.transportMode,
          loaderWrapper.loadInfo.mediaTrack.payloadFormat.format.sampleMimeType,
          sampleQueueBufferedAheadMs,
          mediaPeriodBufferedAheadMs);
    }
    return result;
  }

  /* package */ int skipData(int sampleQueueIndex, long positionUs) {
    if (suppressRead()) {
      return C.RESULT_NOTHING_READ;
    }
    return rtspLoaderWrappers.get(sampleQueueIndex).skipData(positionUs);
  }

  private static long getBufferedAheadMs(long bufferedPositionUs, long sampleTimeUs) {
    if (bufferedPositionUs == C.TIME_END_OF_SOURCE || bufferedPositionUs == Long.MIN_VALUE) {
      return C.TIME_UNSET;
    }
    return usToMs(Math.max(0, bufferedPositionUs - sampleTimeUs));
  }

  /* package */ void maybeNotifySampleQueueBacklogRecoveryRequired(
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      @Nullable String sampleMimeType,
      long sampleQueueBufferedAheadMs,
      long mediaPeriodBufferedAheadMs) {
    if (sampleQueueBacklogRecoverySignaled
        || rtspDiagnosticsListener == null
        || !rtspPacketDiagnosticsEnabled
        || !rtspBacklogRecoveryPolicy.isSampleQueueBacklogRecoverySignalEnabled()
        || sampleQueueBufferedAheadMs == C.TIME_UNSET
        || sampleQueueBufferedAheadMs < rtspBacklogRecoveryPolicy.sampleQueueBacklogRecoveryThresholdMs
        || !MimeTypes.isVideo(sampleMimeType)) {
      return;
    }
    sampleQueueBacklogRecoverySignaled = true;
    recoveryGeneration++;
    clearSampleRtpTimestampMappingsForRecovery(trackId);
    checkNotNull(rtspDiagnosticsListener)
        .onRtspMediaPeriodRecoveryRequired(
            new RtspMediaPeriodRecoveryStats(
                trackId,
                transportMode,
                RtcpFeedbackReason.SAMPLE_QUEUE_BACKLOG,
                RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
                SystemClock.elapsedRealtime(),
                recoveryGeneration,
                sampleQueueBufferedAheadMs,
                mediaPeriodBufferedAheadMs,
                "sample_queue_backlog"));
  }

  /* package */ void onH264AccessUnitReadyForDiagnostics(
      RtspH264AccessUnitReadyStats accessUnitStats) {
    if (rtspPacketDiagnosticsEnabled) {
      recordSampleRtpTimestampForDiagnostics(
          accessUnitStats.trackId, accessUnitStats.sampleTimeUs, accessUnitStats.rtpTimestamp);
    }
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onH264AccessUnitReady(accessUnitStats);
    }
  }

  /* package */ void recordSampleRtpTimestampForDiagnostics(
      int trackId, long sampleTimeUs, long rtpTimestamp) {
    if (!rtspPacketDiagnosticsEnabled
        || sampleTimeUs == C.TIME_UNSET
        || sampleRtpTimestampMappingsLock == null) {
      return;
    }
    synchronized (sampleRtpTimestampMappingsLock) {
      ArrayList<SampleRtpTimestampMapping> mappings = checkNotNull(sampleRtpTimestampMappings);
      mappings.add(
          new SampleRtpTimestampMapping(trackId, sampleTimeUs, rtpTimestamp));
      while (mappings.size() > MAX_SAMPLE_RTP_TIMESTAMP_MAPPINGS) {
        mappings.remove(0);
      }
      checkNotNull(sampleRtpTimestampMappingMissingStatuses).delete(trackId);
    }
  }

  /* package */ long removeSampleRtpTimestampForDiagnostics(int trackId, long sampleTimeUs) {
    return lookupSampleRtpTimestampForDiagnostics(trackId, sampleTimeUs).rtpTimestamp;
  }

  /* package */ SampleRtpTimestampLookupResult lookupSampleRtpTimestampForDiagnostics(
      int trackId, long sampleTimeUs) {
    if (!rtspPacketDiagnosticsEnabled
        || sampleTimeUs == C.TIME_UNSET
        || sampleRtpTimestampMappingsLock == null) {
      return new SampleRtpTimestampLookupResult(
          C.TIME_UNSET, RtspSampleRtpTimestampMappingStatus.NOT_FOUND);
    }
    synchronized (checkNotNull(sampleRtpTimestampMappingsLock)) {
      ArrayList<SampleRtpTimestampMapping> mappings = checkNotNull(sampleRtpTimestampMappings);
      for (int i = mappings.size() - 1; i >= 0; i--) {
        SampleRtpTimestampMapping mapping = mappings.get(i);
        if (mapping.trackId == trackId && mapping.sampleTimeUs == sampleTimeUs) {
          mappings.remove(i);
          return new SampleRtpTimestampLookupResult(
              mapping.rtpTimestamp, RtspSampleRtpTimestampMappingStatus.MAPPED);
        }
      }
      return new SampleRtpTimestampLookupResult(
          C.TIME_UNSET,
          checkNotNull(sampleRtpTimestampMappingMissingStatuses)
              .get(trackId, RtspSampleRtpTimestampMappingStatus.NOT_FOUND));
    }
  }

  private void clearSampleRtpTimestampMappingsForRecovery(int trackId) {
    if (!rtspPacketDiagnosticsEnabled || sampleRtpTimestampMappingsLock == null) {
      return;
    }
    synchronized (sampleRtpTimestampMappingsLock) {
      ArrayList<SampleRtpTimestampMapping> mappings = checkNotNull(sampleRtpTimestampMappings);
      for (int i = mappings.size() - 1; i >= 0; i--) {
        if (mappings.get(i).trackId == trackId) {
          mappings.remove(i);
        }
      }
      checkNotNull(sampleRtpTimestampMappingMissingStatuses)
          .put(trackId, RtspSampleRtpTimestampMappingStatus.CLEARED_FOR_RECOVERY);
    }
  }

  private boolean suppressRead() {
    return notifyDiscontinuity;
  }

  // Internal methods.

  @Nullable
  private RtpDataLoadable getLoadableByTrackUri(Uri trackUri) {
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      if (!rtspLoaderWrappers.get(i).canceled) {
        RtpLoadInfo loadInfo = rtspLoaderWrappers.get(i).loadInfo;
        if (loadInfo.getTrackUri().equals(trackUri)) {
          return loadInfo.loadable;
        }
      }
    }
    return null;
  }

  private boolean isSeekPending() {
    return pendingSeekPositionUs != C.TIME_UNSET;
  }

  private void maybeFinishPrepare() {
    if (released || prepared) {
      return;
    }

    // Make sure all sample queues have got format assigned.
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      if (rtspLoaderWrappers.get(i).sampleQueue.getUpstreamFormat() == null) {
        return;
      }
    }

    prepared = true;
    trackGroups = buildTrackGroups(ImmutableList.copyOf(rtspLoaderWrappers));
    checkNotNull(callback).onPrepared(/* mediaPeriod= */ this);
  }

  /**
   * Attempts to seek to the specified position within the sample queues.
   *
   * @param positionUs The seek position in microseconds.
   * @return Whether the in-buffer seek was successful for all loading RTSP tracks.
   */
  private boolean seekInsideBufferUs(long positionUs) {
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      SampleQueue sampleQueue = rtspLoaderWrappers.get(i).sampleQueue;
      if (!sampleQueue.seekTo(positionUs, /* allowTimeBeyondBuffer= */ false)) {
        return false;
      }
    }
    return true;
  }

  private void maybeSetupTracks() {
    boolean transportReady = true;
    for (int i = 0; i < selectedLoadInfos.size(); i++) {
      transportReady &= selectedLoadInfos.get(i).isTransportReady();
    }

    if (transportReady && trackSelected) {
      rtspClient.setupSelectedTracks(selectedLoadInfos);
    }
  }

  private void updateLoadingFinished() {
    loadingFinished = true;
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      loadingFinished &= rtspLoaderWrappers.get(i).canceled;
    }
  }

  @Nullable
  private String getTrackSampleMimeType(int trackId) {
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtpLoadInfo loadInfo = rtspLoaderWrappers.get(i).loadInfo;
      if (loadInfo.trackId == trackId) {
        return loadInfo.mediaTrack.payloadFormat.format.sampleMimeType;
      }
    }
    return null;
  }

  /* package */ static boolean isVideoMimeType(@Nullable String sampleMimeType) {
    return MimeTypes.isVideo(sampleMimeType);
  }

  /* package */ static boolean shouldSignalMediaPeriodRecovery(
      RtspBacklogRecoveryPolicy policy,
      @RtspTransportMode.Mode int transportMode,
      @Nullable String sampleMimeType) {
    return policy.isMediaPeriodRecoverySignalEnabled()
        && transportMode == RtspTransportMode.TCP_INTERLEAVED
        && isVideoMimeType(sampleMimeType);
  }

  private static ImmutableList<TrackGroup> buildTrackGroups(
      ImmutableList<RtspLoaderWrapper> rtspLoaderWrappers) {
    ImmutableList.Builder<TrackGroup> listBuilder = new ImmutableList.Builder<>();
    SampleQueue sampleQueue;
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      sampleQueue = rtspLoaderWrappers.get(i).sampleQueue;
      listBuilder.add(
          new TrackGroup(
              /* id= */ Integer.toString(i), checkNotNull(sampleQueue.getUpstreamFormat())));
    }
    return listBuilder.build();
  }

  private final class ForwardingRtspDiagnosticsListener implements RtspDiagnosticsListener {

    @Override
    public void onTransportReady(
        int trackId, @RtspTransportMode.Mode int transportMode, String transport) {
      checkNotNull(rtspDiagnosticsListener).onTransportReady(trackId, transportMode, transport);
    }

    @Override
    public void onFirstRtpPacketReceived(RtpPacketStats packetStats) {
      checkNotNull(rtspDiagnosticsListener).onFirstRtpPacketReceived(packetStats);
    }

    @Override
    public void onFirstDecodableVideoAccessUnitReady(
        RtspH264AccessUnitStats accessUnitStats) {
      checkNotNull(rtspDiagnosticsListener).onFirstDecodableVideoAccessUnitReady(accessUnitStats);
    }

    @Override
    public void onH264AccessUnitReady(RtspH264AccessUnitReadyStats accessUnitStats) {
      onH264AccessUnitReadyForDiagnostics(accessUnitStats);
    }

    @Override
    public void onRtspSampleRead(RtspSampleReadStats sampleReadStats) {
      checkNotNull(rtspDiagnosticsListener).onRtspSampleRead(sampleReadStats);
    }

    @Override
    public void onRtspDecoderInputQueued(
        RtspDecoderInputQueuedStats decoderInputQueuedStats) {
      checkNotNull(rtspDiagnosticsListener).onRtspDecoderInputQueued(decoderInputQueuedStats);
    }

    @Override
    public void onH264AccessUnitCorrupted(RtspH264RecoveryStats recoveryStats) {
      checkNotNull(rtspDiagnosticsListener).onH264AccessUnitCorrupted(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrStarted(RtspH264RecoveryStats recoveryStats) {
      checkNotNull(rtspDiagnosticsListener).onH264WaitForIdrStarted(recoveryStats);
    }

    @Override
    public void onH264AccessUnitDroppedUntilIdr(RtspH264RecoveryStats recoveryStats) {
      checkNotNull(rtspDiagnosticsListener).onH264AccessUnitDroppedUntilIdr(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrTimedOut(RtspH264RecoveryStats recoveryStats) {
      checkNotNull(rtspDiagnosticsListener).onH264WaitForIdrTimedOut(recoveryStats);
    }

    @Override
    public void onH264WaitForIdrEnded(RtspH264RecoveryStats recoveryStats) {
      checkNotNull(rtspDiagnosticsListener).onH264WaitForIdrEnded(recoveryStats);
    }

    @Override
    public void onRtpPacketReceived(RtpPacketStats packetStats) {
      checkNotNull(rtspDiagnosticsListener).onRtpPacketReceived(packetStats);
    }

    @Override
    public void onRtpPacketDequeued(
        RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {
      checkNotNull(rtspDiagnosticsListener).onRtpPacketDequeued(packetStats, reorderingStats);
    }

    @Override
    public void onRtpPacketDropped(
        RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {
      checkNotNull(rtspDiagnosticsListener).onRtpPacketDropped(packetStats, reorderingStats);
    }

    @Override
    public void onRtpReorderingQueueReset(RtpReorderingStats reorderingStats) {
      clearSampleRtpTimestampMappingsForRecovery(reorderingStats.trackId);
      checkNotNull(rtspDiagnosticsListener).onRtpReorderingQueueReset(reorderingStats);
      maybeNotifyMediaPeriodRecoveryRequired(
          reorderingStats.trackId,
          reorderingStats.transportMode,
          RtcpFeedbackReason.QUEUE_RESET,
          "rtp_reordering_queue_reset");
    }

    @Override
    public void onRtspBacklogQueueReset(RtspBacklogRecoveryStats backlogRecoveryStats) {
      clearSampleRtpTimestampMappingsForRecovery(backlogRecoveryStats.trackId);
      checkNotNull(rtspDiagnosticsListener).onRtspBacklogQueueReset(backlogRecoveryStats);
      maybeNotifyMediaPeriodRecoveryRequired(
          backlogRecoveryStats.trackId,
          backlogRecoveryStats.transportMode,
          backlogRecoveryStats.reason,
          "rtsp_backlog_queue_reset");
    }

    private void maybeNotifyMediaPeriodRecoveryRequired(
        int trackId,
        @RtspTransportMode.Mode int transportMode,
        @RtcpFeedbackReason.Reason int reason,
        String detail) {
      if (!shouldSignalMediaPeriodRecovery(
          rtspBacklogRecoveryPolicy, transportMode, getTrackSampleMimeType(trackId))) {
        return;
      }
      checkNotNull(rtspDiagnosticsListener)
          .onRtspMediaPeriodRecoveryRequired(
              new RtspMediaPeriodRecoveryStats(
                  trackId,
                  transportMode,
                  reason,
                  RtspMediaPeriodRecoveryStats.ACTION_REBUILD_REQUIRED,
                  SystemClock.elapsedRealtime(),
                  ++recoveryGeneration,
                  C.TIME_UNSET,
                  C.TIME_UNSET,
                  detail));
    }

    @Override
    public void onRtspMediaPeriodRecoveryRequired(
        RtspMediaPeriodRecoveryStats mediaPeriodRecoveryStats) {
      checkNotNull(rtspDiagnosticsListener)
          .onRtspMediaPeriodRecoveryRequired(mediaPeriodRecoveryStats);
    }

    @Override
    public void onRtcpFeedbackThrottled(RtcpFeedbackRequest request) {
      checkNotNull(rtspDiagnosticsListener).onRtcpFeedbackThrottled(request);
    }

    @Override
    public void onRtcpPliSent(RtcpFeedbackRequest request) {
      checkNotNull(rtspDiagnosticsListener).onRtcpPliSent(request);
    }

    @Override
    public void onRtcpFirSent(RtcpFeedbackRequest request) {
      checkNotNull(rtspDiagnosticsListener).onRtcpFirSent(request);
    }

    @Override
    public void onRtcpFeedbackSendFailed(RtcpFeedbackRequest request, Exception error) {
      checkNotNull(rtspDiagnosticsListener).onRtcpFeedbackSendFailed(request, error);
    }
  }

  private static final class SampleRtpTimestampMapping {
    public final int trackId;
    public final long sampleTimeUs;
    public final long rtpTimestamp;

    public SampleRtpTimestampMapping(int trackId, long sampleTimeUs, long rtpTimestamp) {
      this.trackId = trackId;
      this.sampleTimeUs = sampleTimeUs;
      this.rtpTimestamp = rtpTimestamp;
    }
  }

  /* package */ static final class SampleRtpTimestampLookupResult {
    public final long rtpTimestamp;
    public final @RtspSampleRtpTimestampMappingStatus.Status int status;

    public SampleRtpTimestampLookupResult(
        long rtpTimestamp, @RtspSampleRtpTimestampMappingStatus.Status int status) {
      this.rtpTimestamp = rtpTimestamp;
      this.status = status;
    }
  }

  private final class InternalListener
      implements ExtractorOutput,
          Loader.Callback<RtpDataLoadable>,
          UpstreamFormatChangedListener,
          SessionInfoListener,
          PlaybackEventListener {

    // ExtractorOutput implementation.

    @Override
    public TrackOutput track(int id, int type) {
      return checkNotNull(rtspLoaderWrappers.get(id)).sampleQueue;
    }

    @Override
    public void endTracks() {
      handler.post(RtspMediaPeriod.this::maybeFinishPrepare);
    }

    @Override
    public void seekMap(SeekMap seekMap) {
      // RTSP does not support seek map.
    }

    // Loadable.Callback implementation.

    @Override
    public void onLoadCompleted(
        RtpDataLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
      if (getBufferedPositionUs() == 0) {
        if (!isUsingRtpTcp) {
          // Retry playback with TCP if no sample has been received so far, and we are not already
          // using TCP. Retrying will setup new loadables, so will not retry with the current
          // loadables.
          retryWithRtpTcp(RtspTransportFallbackReason.UDP_NO_SAMPLE, loadable.trackId);
        }
        return;
      }

      // Cancel the loader wrapper associated with the completed loadable.
      for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
        RtspLoaderWrapper loaderWrapper = rtspLoaderWrappers.get(i);
        if (loaderWrapper.loadInfo.loadable == loadable) {
          loaderWrapper.cancelLoad();
          break;
        }
      }

      rtspClient.signalPlaybackEnded();
    }

    @Override
    public void onLoadCanceled(
        RtpDataLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {}

    @Override
    public Loader.LoadErrorAction onLoadError(
        RtpDataLoadable loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        IOException error,
        int errorCount) {
      if (!prepared) {
        preparationError = error;
      } else {
        if (error.getCause() instanceof BindException) {
          // Allow for retry on RTP port open failure by catching BindException. Two ports are
          // opened for each RTP stream, the first port number is auto assigned by the system, while
          // the second is manually selected. It is thus possible that the second port fails to
          // bind. Failing is more likely when running in a server-side testing environment, it is
          // less likely on real devices.
          if (portBindingRetryCount++ < PORT_BINDING_MAX_RETRY_COUNT) {
            return Loader.RETRY;
          }
        } else {
          playbackException =
              new RtspPlaybackException(
                  /* message= */ loadable.rtspMediaTrack.uri.toString(), error);
        }
      }
      return Loader.DONT_RETRY;
    }

    // SampleQueue.UpstreamFormatChangedListener implementation.

    @Override
    public void onUpstreamFormatChanged(Format format) {
      handler.post(RtspMediaPeriod.this::maybeFinishPrepare);
    }

    // RtspClient.PlaybackEventListener implementation.

    @Override
    public void onRtspSetupCompleted() {
      long offsetMs = 0;
      if (pendingSeekPositionUs != C.TIME_UNSET) {
        offsetMs = Util.usToMs(pendingSeekPositionUs);
      } else if (pendingSeekPositionUsForTcpRetry != C.TIME_UNSET) {
        offsetMs = Util.usToMs(pendingSeekPositionUsForTcpRetry);
      }
      rtspClient.startPlayback(offsetMs);
    }

    @Override
    public void onPlaybackStarted(
        long startPositionUs, ImmutableList<RtspTrackTiming> trackTimingList) {

      // Validate that the trackTimingList contains timings for the selected tracks, and notify the
      // listener.
      ArrayList<String> trackUrisWithTiming = new ArrayList<>(trackTimingList.size());
      for (int i = 0; i < trackTimingList.size(); i++) {
        trackUrisWithTiming.add(checkNotNull(trackTimingList.get(i).uri.getPath()));
      }
      for (int i = 0; i < selectedLoadInfos.size(); i++) {
        RtpLoadInfo loadInfo = selectedLoadInfos.get(i);
        if (!trackUrisWithTiming.contains(loadInfo.getTrackUri().getPath())) {
          listener.onSeekingUnsupported();
          if (isSeekPending()) {
            notifyDiscontinuity = true;
            pendingSeekPositionUs = C.TIME_UNSET;
            requestedSeekPositionUs = C.TIME_UNSET;
            pendingSeekPositionUsForTcpRetry = C.TIME_UNSET;
          }
        }
      }

      for (int i = 0; i < trackTimingList.size(); i++) {
        RtspTrackTiming trackTiming = trackTimingList.get(i);
        @Nullable RtpDataLoadable dataLoadable = getLoadableByTrackUri(trackTiming.uri);
        if (dataLoadable == null) {
          continue;
        }

        dataLoadable.setTimestamp(trackTiming.rtpTimestamp);
        dataLoadable.setSequenceNumber(trackTiming.sequenceNumber);

        if (isSeekPending() && pendingSeekPositionUs == requestedSeekPositionUs) {
          // Seek loadable only when all pending seeks are processed, or SampleQueues will report
          // inconsistent bufferedPosition.
          // Seeks to the start position when the initial seek position is set.
          dataLoadable.seekToUs(startPositionUs, trackTiming.rtpTimestamp);
        }
      }

      if (isSeekPending()) {
        if (pendingSeekPositionUs == requestedSeekPositionUs) {
          // No seek request was made after the current pending seek.
          pendingSeekPositionUs = C.TIME_UNSET;
          requestedSeekPositionUs = C.TIME_UNSET;
        } else {
          // Resets pendingSeekPositionUs to perform a fresh RTSP seek.
          pendingSeekPositionUs = C.TIME_UNSET;
          seekToUs(requestedSeekPositionUs);
        }
      } else if (pendingSeekPositionUsForTcpRetry != C.TIME_UNSET && isUsingRtpTcp) {
        seekToUs(pendingSeekPositionUsForTcpRetry);
        pendingSeekPositionUsForTcpRetry = C.TIME_UNSET;
      }
    }

    @Override
    public void onPlaybackError(RtspPlaybackException error) {
      if (error instanceof RtspMediaSource.RtspUdpUnsupportedTransportException && !isUsingRtpTcp) {
        // Retry playback with TCP if we receive RtspUdpUnsupportedTransportException, and we are
        // not already using TCP. Retrying will setup new loadables.
        retryWithRtpTcp(
            RtspTransportFallbackReason.UDP_UNSUPPORTED, /* trackId= */ C.INDEX_UNSET);
      } else {
        playbackException = error;
      }
    }

    @Override
    public void onSessionTimelineUpdated(
        RtspSessionTiming timing, ImmutableList<RtspMediaTrack> tracks) {
      for (int i = 0; i < tracks.size(); i++) {
        RtspMediaTrack rtspMediaTrack = tracks.get(i);
        RtspLoaderWrapper loaderWrapper =
            new RtspLoaderWrapper(rtspMediaTrack, /* trackId= */ i, rtpDataChannelFactory);
        rtspLoaderWrappers.add(loaderWrapper);
        loaderWrapper.startLoading();
      }

      listener.onSourceInfoRefreshed(timing);
    }

    @Override
    public void onSessionTimelineRequestFailed(String message, @Nullable Throwable cause) {
      preparationError = cause == null ? new IOException(message) : new IOException(message, cause);
    }
  }

  private void retryWithRtpTcp(
      @RtspTransportFallbackReason.Reason int reason, int trackId) {
    @Nullable
    RtpDataChannel.Factory fallbackRtpDataChannelFactory =
        rtpDataChannelFactory.createFallbackDataChannelFactory();
    if (fallbackRtpDataChannelFactory == null) {
      onTransportFallbackForDiagnostics(
          reason, trackId, RtspTransportMode.UDP, RtspTransportMode.UNKNOWN);
      playbackException =
          new RtspPlaybackException("No fallback data channel factory for TCP retry");
      return;
    }

    onTransportFallbackForDiagnostics(
        reason, trackId, RtspTransportMode.UDP, RtspTransportMode.TCP_INTERLEAVED);

    // Retry should only run once.
    isUsingRtpTcp = true;

    rtspClient.retryWithRtpTcp();

    ArrayList<RtspLoaderWrapper> newLoaderWrappers = new ArrayList<>(rtspLoaderWrappers.size());
    ArrayList<RtpLoadInfo> newSelectedLoadInfos = new ArrayList<>(selectedLoadInfos.size());

    // newLoaderWrappers' elements and orders must match those of rtspLoaderWrappers'.
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtspLoaderWrapper loaderWrapper = rtspLoaderWrappers.get(i);

      if (!loaderWrapper.canceled) {
        RtspLoaderWrapper newLoaderWrapper =
            new RtspLoaderWrapper(
                loaderWrapper.loadInfo.mediaTrack, /* trackId= */ i, fallbackRtpDataChannelFactory);
        newLoaderWrappers.add(newLoaderWrapper);
        newLoaderWrapper.startLoading();
        if (selectedLoadInfos.contains(loaderWrapper.loadInfo)) {
          newSelectedLoadInfos.add(newLoaderWrapper.loadInfo);
        }
      } else {
        newLoaderWrappers.add(loaderWrapper);
      }
    }

    // Switch to new LoaderWrappers.
    ImmutableList<RtspLoaderWrapper> oldRtspLoaderWrappers =
        ImmutableList.copyOf(rtspLoaderWrappers);
    rtspLoaderWrappers.clear();
    rtspLoaderWrappers.addAll(newLoaderWrappers);
    selectedLoadInfos.clear();
    selectedLoadInfos.addAll(newSelectedLoadInfos);

    // Cancel old loadable wrappers after switching, so that buffered position is always read from
    // active sample queues.
    for (int i = 0; i < oldRtspLoaderWrappers.size(); i++) {
      oldRtspLoaderWrappers.get(i).cancelLoad();
    }
  }

  /* package */ void onTransportFallbackForDiagnostics(
      @RtspTransportFallbackReason.Reason int reason,
      int trackId,
      @RtspTransportMode.Mode int fromTransportMode,
      @RtspTransportMode.Mode int toTransportMode) {
    if (rtspDiagnosticsListener == null) {
      return;
    }
    rtspDiagnosticsListener.onTransportFallback(
        new RtspTransportFallbackStats(
            trackId, reason, fromTransportMode, toTransportMode, SystemClock.elapsedRealtime()));
  }

  private final class SampleStreamImpl implements SampleStream {
    private final int track;

    public SampleStreamImpl(int track) {
      this.track = track;
    }

    @Override
    public boolean isReady() {
      return RtspMediaPeriod.this.isReady(track);
    }

    @Override
    public void maybeThrowError() throws RtspPlaybackException {
      if (playbackException != null) {
        throw playbackException;
      }
    }

    @Override
    public int readData(
        FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
      return RtspMediaPeriod.this.readData(track, formatHolder, buffer, readFlags);
    }

    @Override
    public int skipData(long positionUs) {
      return RtspMediaPeriod.this.skipData(track, positionUs);
    }
  }

  /** Manages the loading of an RTSP track. */
  private final class RtspLoaderWrapper {
    /** The {@link RtpLoadInfo} of the RTSP track to load. */
    public final RtpLoadInfo loadInfo;

    private final Loader loader;
    private final SampleQueue sampleQueue;
    private boolean canceled;
    private boolean released;

    /**
     * Creates a new instance.
     *
     * <p>Instances must be {@link #release() released} after loadings conclude.
     */
    public RtspLoaderWrapper(
        RtspMediaTrack mediaTrack, int trackId, RtpDataChannel.Factory rtpDataChannelFactory) {
      loadInfo = new RtpLoadInfo(mediaTrack, trackId, rtpDataChannelFactory);
      loader = new Loader("ExoPlayer:RtspMediaPeriod:RtspLoaderWrapper " + trackId);
      sampleQueue = SampleQueue.createWithoutDrm(allocator);
      sampleQueue.setUpstreamFormatChangeListener(internalListener);
    }

    /**
     * Returns the largest buffered position in microseconds; or {@link Long#MIN_VALUE} if no sample
     * has been queued.
     */
    public long getBufferedPositionUs() {
      return sampleQueue.getLargestQueuedTimestampUs();
    }

    /** Starts loading. */
    public void startLoading() {
      loader.startLoading(
          loadInfo.loadable, /* callback= */ internalListener, /* defaultMinRetryCount= */ 0);
    }

    public boolean isSampleQueueReady() {
      return sampleQueue.isReady(/* loadingFinished= */ canceled);
    }

    public @ReadDataResult int read(
        FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
      return sampleQueue.read(formatHolder, buffer, readFlags, /* loadingFinished= */ canceled);
    }

    public int skipData(long positionUs) {
      int skipCount = sampleQueue.getSkipCount(positionUs, /* allowEndOfQueue= */ canceled);
      sampleQueue.skip(skipCount);
      return skipCount;
    }

    /** Cancels loading. */
    public void cancelLoad() {
      if (!canceled) {
        loadInfo.loadable.cancelLoad();
        loadInfo.cancelRtcpLoading();
        canceled = true;

        // Update loadingFinished every time loading is canceled.
        updateLoadingFinished();
      }
    }

    /** Resumes loading after {@linkplain #cancelLoad() loading is canceled}. */
    public void resumeLoad() {
      checkState(canceled);
      canceled = false;
      updateLoadingFinished();
      startLoading();
    }

    /** Resets the {@link Loadable} and {@link SampleQueue} to prepare for an RTSP seek. */
    public void seekTo(long positionUs) {
      if (!canceled) {
        loadInfo.loadable.resetForSeek();
        sampleQueue.reset();
        sampleQueue.setStartTimeUs(positionUs);
      }
    }

    /** Releases the instance. */
    public void release() {
      if (released) {
        return;
      }
      loadInfo.cancelRtcpLoading();
      loader.release();
      sampleQueue.release();
      released = true;
    }
  }

  /** Groups the info needed for loading one RTSP track in RTP. */
  /* package */ final class RtpLoadInfo implements RtcpFeedbackRequester {
    /** The {@link RtspMediaTrack}. */
    public final RtspMediaTrack mediaTrack;

    private final RtpDataLoadable loadable;
    private final int trackId;

    @Nullable private String transport;
    @Nullable private RtpDataChannel rtpDataChannel;
    @Nullable private Loader rtcpLoader;
    @Nullable private RtcpDataLoadable rtcpDataLoadable;
    private @RtspTransportMode.Mode int transportMode;
    private long lastFeedbackRequestElapsedRealtimeMs;
    private int firSequenceNumber;
    private int rtcpInterleavedChannel;

    /** Creates a new instance. */
    public RtpLoadInfo(
        RtspMediaTrack mediaTrack, int trackId, RtpDataChannel.Factory rtpDataChannelFactory) {
      this.mediaTrack = mediaTrack;
      this.trackId = trackId;
      this.transportMode = RtspTransportMode.UNKNOWN;
      this.lastFeedbackRequestElapsedRealtimeMs = C.TIME_UNSET;
      this.rtcpInterleavedChannel = C.INDEX_UNSET;

      // This listener runs on the playback thread, posted by the Loader thread.
      RtpDataLoadable.EventListener transportEventListener =
          (transport, rtpDataChannel) -> {
            RtpLoadInfo.this.transport = transport;
            RtpLoadInfo.this.rtpDataChannel = rtpDataChannel;

            @Nullable
            RtspMessageChannel.InterleavedBinaryDataListener interleavedBinaryDataListener =
                rtpDataChannel.getInterleavedBinaryDataListener();
            RtpLoadInfo.this.transportMode =
                interleavedBinaryDataListener != null
                    ? RtspTransportMode.TCP_INTERLEAVED
                    : RtspTransportMode.UDP;
            if (interleavedBinaryDataListener != null) {
              rtcpInterleavedChannel = rtpDataChannel.getLocalPort() + 1;
              rtspClient.registerInterleavedDataChannel(
                  rtpDataChannel.getLocalPort(), interleavedBinaryDataListener);
              if (rtspDiagnosticsListener != null) {
                rtspClient.registerInterleavedDataChannel(
                    rtcpInterleavedChannel,
                    data ->
                        handleRtcpPacket(
                            data,
                            data.length,
                            RtspTransportMode.TCP_INTERLEAVED,
                            SystemClock.elapsedRealtime()));
              }
              isUsingRtpTcp = true;
            } else if (rtspDiagnosticsListener != null) {
              startUdpRtcpLoading(rtpDataChannel);
            }
            if (rtspDiagnosticsListener != null) {
              rtspDiagnosticsListener.onTransportReady(trackId, transportMode, transport);
            }
            maybeSetupTracks();
          };

      this.loadable =
          new RtpDataLoadable(
              trackId,
              mediaTrack,
              /* eventListener= */ transportEventListener,
              /* output= */ internalListener,
              rtpDataChannelFactory,
              forwardingRtspDiagnosticsListener,
              this,
              rtcpFeedbackPolicy,
              rtspBacklogRecoveryPolicy,
              rtspPacketDiagnosticsEnabled);
    }

    /**
     * Returns whether RTP transport is ready. Call {@link #getTransport()} only after transport is
     * ready.
     */
    public boolean isTransportReady() {
      return transport != null;
    }

    /**
     * Gets the transport string for RTP loading.
     *
     * @throws IllegalStateException When transport for this RTP stream is not set.
     */
    public String getTransport() {
      checkStateNotNull(transport);
      return transport;
    }

    /** Gets the {@link Uri} for the loading RTSP track. */
    public Uri getTrackUri() {
      return loadable.rtspMediaTrack.uri;
    }

    public void setRemoteRtcpEndpoint(String host, int port) {
      if (rtpDataChannel == null) {
        return;
      }
      try {
        rtpDataChannel.setRemoteRtcpEndpoint(host, port);
      } catch (IOException e) {
        notifyRtcpFeedbackSendFailed(
            createFeedbackRequest(
                RtcpFeedbackType.UNKNOWN,
                RtcpFeedbackReason.UNKNOWN,
                SystemClock.elapsedRealtime(),
                "Failed to set remote RTCP endpoint"),
            e);
      }
    }

    public boolean requestKeyFrame(@RtcpFeedbackReason.Reason int reason) {
      if (!isVideoMimeType(mediaTrack.payloadFormat.format.sampleMimeType)) {
        return false;
      }
      if (Looper.myLooper() != handler.getLooper()) {
        return handler.post(() -> requestKeyFrameInternal(reason));
      }
      return requestKeyFrameInternal(reason);
    }

    @Override
    public boolean requestGenericNack(int mediaSsrc, int pid, int blp) {
      if (Looper.myLooper() != handler.getLooper()) {
        return handler.post(() -> requestGenericNackInternal(mediaSsrc, pid, blp));
      }
      return requestGenericNackInternal(mediaSsrc, pid, blp);
    }

    private boolean requestGenericNackInternal(int mediaSsrc, int pid, int blp) {
      if (transportMode != RtspTransportMode.UDP || !rtcpFeedbackPolicy.canSendGenericNack()) {
        return false;
      }
      try {
        return rtpDataChannel != null
            && rtpDataChannel.sendRtcpPacket(
                RtcpFeedbackPacket.buildGenericNack(
                    rtcpFeedbackPolicy.senderSsrc, mediaSsrc, pid, blp));
      } catch (IOException | RuntimeException e) {
        return false;
      }
    }

    private boolean requestKeyFrameInternal(@RtcpFeedbackReason.Reason int reason) {
      return requestRtcpFeedbackInternal(RtcpFeedbackType.UNKNOWN, reason).status
          == RtcpFeedbackResult.SCHEDULED;
    }

    public RtcpFeedbackResult requestRtcpFeedback(
        @RtcpFeedbackType.Type int requestedFeedbackType, @RtcpFeedbackReason.Reason int reason) {
      if (Looper.myLooper() != handler.getLooper()) {
        AtomicReference<RtcpFeedbackResult> resultReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted =
            handler.post(
                () -> {
                  try {
                    resultReference.set(
                        requestRtcpFeedbackInternal(requestedFeedbackType, reason));
                  } finally {
                    latch.countDown();
                  }
                });
        if (!posted) {
          return createFeedbackResult(
              RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler unavailable");
        }
        try {
          if (!latch.await(1000, TimeUnit.MILLISECONDS)) {
            return createFeedbackResult(
                RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler timeout");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return createFeedbackResult(
              RtcpFeedbackResult.FAILED, /* request= */ null, "interrupted");
        }
        @Nullable RtcpFeedbackResult result = resultReference.get();
        return result != null
            ? result
            : createFeedbackResult(
                RtcpFeedbackResult.FAILED, /* request= */ null, "playback handler failed");
      }
      return requestRtcpFeedbackInternal(requestedFeedbackType, reason);
    }

    private RtcpFeedbackResult requestRtcpFeedbackInternal(
        @RtcpFeedbackType.Type int requestedFeedbackType, @RtcpFeedbackReason.Reason int reason) {
      long nowMs = SystemClock.elapsedRealtime();
      @RtcpFeedbackType.Type int feedbackType = getFeedbackType(requestedFeedbackType);
      RtcpFeedbackRequest request =
          createFeedbackRequest(feedbackType, reason, nowMs, /* detail= */ null);
      if (feedbackType == RtcpFeedbackType.UNKNOWN) {
        notifyRtcpFeedbackSendFailed(request, new IllegalStateException("RTCP feedback disabled"));
        return createFeedbackResult(RtcpFeedbackResult.FAILED, request, "RTCP feedback disabled");
      }
      if (lastFeedbackRequestElapsedRealtimeMs != C.TIME_UNSET
          && nowMs - lastFeedbackRequestElapsedRealtimeMs
              < rtcpFeedbackPolicy.minRequestIntervalMs) {
        notifyRtcpFeedbackThrottled(request);
        return createFeedbackResult(RtcpFeedbackResult.THROTTLED, request, "throttled");
      }

      byte[] packet =
          feedbackType == RtcpFeedbackType.PLI
              ? RtcpFeedbackPacket.buildPli(rtcpFeedbackPolicy.senderSsrc, request.mediaSsrc)
              : RtcpFeedbackPacket.buildFir(
                  rtcpFeedbackPolicy.senderSsrc, request.mediaSsrc, firSequenceNumber++);

      if (rtspFeedbackListener != null) {
        rtspFeedbackListener.onRtcpFeedbackRequested(request);
      }
      try {
        boolean sent;
        if (transportMode == RtspTransportMode.TCP_INTERLEAVED) {
          rtspClient.sendInterleavedBinaryData(
              rtcpInterleavedChannel != C.INDEX_UNSET ? rtcpInterleavedChannel : trackId * 2 + 1,
              packet,
              new RtspMessageChannel.InterleavedBinaryDataSendListener() {
                @Override
                public void onSent(int channel, byte[] data) {
                  handler.post(
                      () -> {
                        notifyRtcpFeedbackSent(request);
                      });
                }

                @Override
                public void onSendFailed(int channel, byte[] data, Exception e) {
                  handler.post(() -> notifyRtcpFeedbackSendFailed(request, e));
                }
              });
          lastFeedbackRequestElapsedRealtimeMs = nowMs;
          sent = true;
        } else {
          sent = rtpDataChannel != null && rtpDataChannel.sendRtcpPacket(packet);
        }
        if (!sent) {
          throw new IOException("RTCP feedback channel is not ready");
        }
        if (transportMode != RtspTransportMode.TCP_INTERLEAVED) {
          lastFeedbackRequestElapsedRealtimeMs = nowMs;
          notifyRtcpFeedbackSent(request);
        }
        return createFeedbackResult(RtcpFeedbackResult.SCHEDULED, request, /* detail= */ null);
      } catch (IOException | RuntimeException e) {
        notifyRtcpFeedbackSendFailed(request, e);
        return createFeedbackResult(
            RtcpFeedbackResult.FAILED, request, e.getClass().getSimpleName());
      }
    }

    private @RtcpFeedbackType.Type int getFeedbackType(
        @RtcpFeedbackType.Type int requestedFeedbackType) {
      if (requestedFeedbackType == RtcpFeedbackType.PLI) {
        return rtcpFeedbackPolicy.pliEnabled ? RtcpFeedbackType.PLI : RtcpFeedbackType.UNKNOWN;
      }
      if (requestedFeedbackType == RtcpFeedbackType.FIR) {
        return rtcpFeedbackPolicy.firEnabled ? RtcpFeedbackType.FIR : RtcpFeedbackType.UNKNOWN;
      }
      if (!rtcpFeedbackPolicy.canSendRtcpFeedback()) {
        return RtcpFeedbackType.UNKNOWN;
      }
      if (rtcpFeedbackPolicy.pliEnabled) {
        return RtcpFeedbackType.PLI;
      }
      if (rtcpFeedbackPolicy.firEnabled) {
        return RtcpFeedbackType.FIR;
      }
      return RtcpFeedbackType.UNKNOWN;
    }

    private RtcpFeedbackRequest createFeedbackRequest(
        @RtcpFeedbackType.Type int feedbackType,
        @RtcpFeedbackReason.Reason int reason,
        long requestElapsedRealtimeMs,
        @Nullable String detail) {
      int mediaSsrc = loadable.getLastSsrc();
      if (mediaSsrc == C.INDEX_UNSET) {
        mediaSsrc = 0;
      }
      return new RtcpFeedbackRequest(
          trackId,
          feedbackType,
          reason,
          transportMode,
          rtcpFeedbackPolicy.senderSsrc,
          mediaSsrc,
          requestElapsedRealtimeMs,
          detail);
    }

    private void notifyRtcpFeedbackThrottled(RtcpFeedbackRequest request) {
      if (rtspFeedbackListener != null) {
        rtspFeedbackListener.onRtcpFeedbackThrottled(request);
      }
      if (rtspDiagnosticsListener != null) {
        rtspDiagnosticsListener.onRtcpFeedbackThrottled(request);
      }
    }

    private void notifyRtcpFeedbackSent(RtcpFeedbackRequest request) {
      if (rtspFeedbackListener != null) {
        rtspFeedbackListener.onRtcpFeedbackSent(request);
      }
      if (rtspDiagnosticsListener != null) {
        if (request.feedbackType == RtcpFeedbackType.PLI) {
          rtspDiagnosticsListener.onRtcpPliSent(request);
        } else if (request.feedbackType == RtcpFeedbackType.FIR) {
          rtspDiagnosticsListener.onRtcpFirSent(request);
        }
      }
    }

    private void notifyRtcpFeedbackSendFailed(RtcpFeedbackRequest request, Exception error) {
      if (rtspFeedbackListener != null) {
        rtspFeedbackListener.onRtcpFeedbackSendFailed(request, error);
      }
      if (rtspDiagnosticsListener != null) {
        rtspDiagnosticsListener.onRtcpFeedbackSendFailed(request, error);
      }
    }

    private void startUdpRtcpLoading(RtpDataChannel rtpDataChannel) {
      cancelRtcpLoading();
      rtcpDataLoadable = new RtcpDataLoadable(rtpDataChannel);
      rtcpLoader = new Loader("ExoPlayer:RtspMediaPeriod:RtcpLoader " + trackId);
      rtcpLoader.startLoading(
          rtcpDataLoadable,
          new Loader.Callback<RtcpDataLoadable>() {
            @Override
            public void onLoadCompleted(
                RtcpDataLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {}

            @Override
            public void onLoadCanceled(
                RtcpDataLoadable loadable,
                long elapsedRealtimeMs,
                long loadDurationMs,
                boolean released) {}

            @Override
            public Loader.LoadErrorAction onLoadError(
                RtcpDataLoadable loadable,
                long elapsedRealtimeMs,
                long loadDurationMs,
                IOException error,
                int errorCount) {
              return Loader.DONT_RETRY;
            }
          },
          /* defaultMinRetryCount= */ 0);
    }

    private void cancelRtcpLoading() {
      if (rtcpDataLoadable != null) {
        rtcpDataLoadable.cancelLoad();
      }
      if (rtcpLoader != null) {
        rtcpLoader.release();
      }
      rtcpDataLoadable = null;
      rtcpLoader = null;
    }

    private void handleRtcpPacket(
        byte[] packet,
        int packetLength,
        @RtspTransportMode.Mode int transportMode,
        long receivedElapsedRealtimeMs) {
      if (rtspDiagnosticsListener == null) {
        return;
      }
      ImmutableList<RtcpSenderReportStats> senderReports =
          RtcpSenderReportPacket.parseSenderReports(
              packet,
              packetLength,
              trackId,
              transportMode,
              mediaTrack.payloadFormat.clockRate,
              receivedElapsedRealtimeMs);
      for (int i = 0; i < senderReports.size(); i++) {
        RtcpSenderReportStats stats = senderReports.get(i);
        handler.post(() -> rtspDiagnosticsListener.onRtcpSenderReport(stats));
      }
    }

    private final class RtcpDataLoadable implements Loadable {

      private final RtpDataChannel rtpDataChannel;
      private final byte[] buffer;
      private volatile boolean canceled;

      public RtcpDataLoadable(RtpDataChannel rtpDataChannel) {
        this.rtpDataChannel = rtpDataChannel;
        buffer = new byte[UdpDataSource.DEFAULT_MAX_PACKET_SIZE];
      }

      @Override
      public void cancelLoad() {
        canceled = true;
        DataSourceUtil.closeQuietly(rtpDataChannel);
      }

      @Override
      public void load() throws IOException {
        while (!canceled) {
          int bytesRead = rtpDataChannel.readRtcpPacket(buffer, /* offset= */ 0, buffer.length);
          if (bytesRead == C.RESULT_END_OF_INPUT) {
            continue;
          }
          if (bytesRead > 0) {
            handleRtcpPacket(
                buffer, bytesRead, RtspTransportMode.UDP, SystemClock.elapsedRealtime());
          }
        }
      }
    }
  }
}
