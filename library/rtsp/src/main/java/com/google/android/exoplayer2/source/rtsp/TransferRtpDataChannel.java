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
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.rtsp.RtspMessageChannel.InterleavedBinaryDataListener;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.util.Util;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An {@link RtpDataChannel} that transfers received data in-memory.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class TransferRtpDataChannel extends BaseDataSource
    implements RtpDataChannel, RtspMessageChannel.InterleavedBinaryDataListener {

  private static final String DEFAULT_TCP_TRANSPORT_FORMAT =
      "RTP/AVP/TCP;unicast;interleaved=%d-%d";

  @Nullable private final LinkedBlockingQueue<byte[]> packetQueue;
  @Nullable private final LinkedBlockingQueue<PacketEnvelope> packetEnvelopeQueue;
  private final boolean collectReadStallSnapshot;
  private final int trackId;
  private final long pollTimeoutMs;
  @Nullable private volatile RtspDiagnosticsListener rtspDiagnosticsListener;
  private final RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy;

  private byte[] unreadData;
  private int channelNumber;
  private long lastPacketArrivalElapsedRealtimeMs;
  private volatile boolean readInProgress;
  private volatile long readStartElapsedRealtimeMs;
  private volatile long lastReadCompletionElapsedRealtimeMs;
  @Nullable private volatile String readerThreadName;
  private volatile boolean clearUnreadDataOnNextRead;
  private volatile @RtcpFeedbackReason.Reason int pendingDiscontinuityReason;

  /**
   * Creates a new instance.
   *
   * @param pollTimeoutMs The number of milliseconds which {@link #read} waits for a packet to be
   *     available. After the time has expired, {@link C#RESULT_END_OF_INPUT} is returned.
   */
  public TransferRtpDataChannel(long pollTimeoutMs) {
    this(
        /* trackId= */ C.INDEX_UNSET,
        pollTimeoutMs,
        /* rtspDiagnosticsListener= */ null,
        RtspBacklogRecoveryPolicy.DISABLED);
  }

  /** Creates a new instance. */
  public TransferRtpDataChannel(
      int trackId,
      long pollTimeoutMs,
      @Nullable RtspDiagnosticsListener rtspDiagnosticsListener,
      RtspBacklogRecoveryPolicy rtspBacklogRecoveryPolicy) {
    super(/* isNetwork= */ true);
    this.trackId = trackId;
    this.pollTimeoutMs = pollTimeoutMs;
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
    this.rtspBacklogRecoveryPolicy = rtspBacklogRecoveryPolicy;
    if (rtspBacklogRecoveryPolicy.isTcpInterleavedBacklogRecoveryEnabled()) {
      packetQueue = null;
      packetEnvelopeQueue = new LinkedBlockingQueue<>();
    } else {
      packetQueue = new LinkedBlockingQueue<>();
      packetEnvelopeQueue = null;
    }
    collectReadStallSnapshot =
        rtspDiagnosticsListener != null
            && rtspBacklogRecoveryPolicy.isTcpInterleavedBacklogRecoveryEnabled();
    unreadData = new byte[0];
    channelNumber = C.INDEX_UNSET;
    lastPacketArrivalElapsedRealtimeMs = C.TIME_UNSET;
    readStartElapsedRealtimeMs = C.TIME_UNSET;
    lastReadCompletionElapsedRealtimeMs = C.TIME_UNSET;
    clearUnreadDataOnNextRead = false;
    pendingDiscontinuityReason = RtcpFeedbackReason.UNKNOWN;
  }

  @Override
  public String getTransport() {
    checkState(channelNumber != C.INDEX_UNSET); // Assert open() is called.
    return Util.formatInvariant(DEFAULT_TCP_TRANSPORT_FORMAT, channelNumber, channelNumber + 1);
  }

  @Override
  public int getLocalPort() {
    return channelNumber;
  }

  @Override
  public boolean needsClosingOnLoadCompletion() {
    // TCP channel is managed by the RTSP mesasge channel and does not need closing from here.
    return false;
  }

  @Override
  public InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
    return this;
  }

  @Override
  public void setRtspDiagnosticsListener(@Nullable RtspDiagnosticsListener rtspDiagnosticsListener) {
    this.rtspDiagnosticsListener = rtspDiagnosticsListener;
  }

  @Override
  public long open(DataSpec dataSpec) {
    this.channelNumber = dataSpec.uri.getPort();
    return C.LENGTH_UNSET;
  }

  @Override
  public void close() {}

  @Nullable
  @Override
  public Uri getUri() {
    return null;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) {
    if (length == 0) {
      return 0;
    }

    if (collectReadStallSnapshot) {
      readInProgress = true;
      readStartElapsedRealtimeMs = SystemClock.elapsedRealtime();
      if (readerThreadName == null) {
        readerThreadName = Thread.currentThread().getName();
      }
    }
    try {
      return readInternal(buffer, offset, length);
    } finally {
      if (collectReadStallSnapshot) {
        lastReadCompletionElapsedRealtimeMs = SystemClock.elapsedRealtime();
        readInProgress = false;
      }
    }
  }

  private int readInternal(byte[] buffer, int offset, int length) {
    if (clearUnreadDataOnNextRead) {
      unreadData = new byte[0];
      clearUnreadDataOnNextRead = false;
    }
    int bytesRead = 0;
    int bytesToRead = min(length, unreadData.length);
    System.arraycopy(unreadData, /* srcPos= */ 0, buffer, offset, bytesToRead);
    bytesRead += bytesToRead;
    unreadData = Arrays.copyOfRange(unreadData, bytesToRead, unreadData.length);

    if (bytesRead == length) {
      return bytesRead;
    }

    @Nullable byte[] data;
    try {
      if (packetEnvelopeQueue == null) {
        data = checkNotNull(packetQueue).poll(pollTimeoutMs, MILLISECONDS);
      } else {
        @Nullable PacketEnvelope packetEnvelope = packetEnvelopeQueue.poll(pollTimeoutMs, MILLISECONDS);
        data = packetEnvelope == null ? null : packetEnvelope.data;
      }
      if (data == null) {
        return C.RESULT_END_OF_INPUT;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return C.RESULT_END_OF_INPUT;
    }

    bytesToRead = min(length - bytesRead, data.length);
    System.arraycopy(data, /* srcPos= */ 0, buffer, offset + bytesRead, bytesToRead);
    if (bytesToRead < data.length) {
      unreadData = Arrays.copyOfRange(data, bytesToRead, data.length);
    }
    return bytesRead + bytesToRead;
  }

  @Override
  public void onInterleavedBinaryDataReceived(byte[] data) {
    if (packetEnvelopeQueue == null) {
      checkNotNull(packetQueue).add(data);
      return;
    }
    onInterleavedBinaryDataReceived(data, SystemClock.elapsedRealtime());
  }

  @VisibleForTesting
  /* package */ void onInterleavedBinaryDataReceived(byte[] data, long arrivalElapsedRealtimeMs) {
    checkState(packetEnvelopeQueue != null);
    packetEnvelopeQueue.add(new PacketEnvelope(data, arrivalElapsedRealtimeMs));
    lastPacketArrivalElapsedRealtimeMs = arrivalElapsedRealtimeMs;
    maybeFlushBacklog(arrivalElapsedRealtimeMs);
  }

  @Override
  public @RtcpFeedbackReason.Reason int getAndClearPendingDiscontinuityReason() {
    int reason = pendingDiscontinuityReason;
    pendingDiscontinuityReason = RtcpFeedbackReason.UNKNOWN;
    return reason;
  }

  private void maybeFlushBacklog(long nowElapsedRealtimeMs) {
    LinkedBlockingQueue<PacketEnvelope> packetEnvelopeQueue = checkNotNull(this.packetEnvelopeQueue);
    int queueDepth = packetEnvelopeQueue.size();
    long oldestPacketAgeMs = getOldestPacketAgeMs(nowElapsedRealtimeMs);
    long queueSpanMs = getQueueSpanMs();
    if (!shouldFlushBacklog(queueDepth, oldestPacketAgeMs)) {
      return;
    }
    int droppedPacketCount = queueDepth;
    packetEnvelopeQueue.clear();
    clearUnreadDataOnNextRead = true;
    pendingDiscontinuityReason = RtcpFeedbackReason.QUEUE_RESET;
    if (rtspDiagnosticsListener != null) {
      rtspDiagnosticsListener.onRtspBacklogQueueReset(
          new RtspBacklogRecoveryStats(
              trackId,
              RtspTransportMode.TCP_INTERLEAVED,
              RtcpFeedbackReason.QUEUE_RESET,
              queueDepth,
              droppedPacketCount,
              oldestPacketAgeMs,
              queueSpanMs,
              nowElapsedRealtimeMs,
              C.INDEX_UNSET,
              C.INDEX_UNSET,
              C.INDEX_UNSET,
              C.INDEX_UNSET,
              /* recentPacketInterArrivalMaxMs= */ 0,
              /* extractorReadStallMs= */ 0,
              readInProgress,
              readInProgress && readStartElapsedRealtimeMs != C.TIME_UNSET
                  ? Math.max(0, nowElapsedRealtimeMs - readStartElapsedRealtimeMs)
                  : 0,
              lastReadCompletionElapsedRealtimeMs == C.TIME_UNSET
                  ? C.TIME_UNSET
                  : Math.max(0, nowElapsedRealtimeMs - lastReadCompletionElapsedRealtimeMs),
              readerThreadName));
    }
  }

  private boolean shouldFlushBacklog(int queueDepth, long oldestPacketAgeMs) {
    return (rtspBacklogRecoveryPolicy.maxTcpInterleavedQueueDepth > 0
            && queueDepth >= rtspBacklogRecoveryPolicy.maxTcpInterleavedQueueDepth)
        || (rtspBacklogRecoveryPolicy.maxTcpInterleavedQueueAgeMs > 0
            && oldestPacketAgeMs >= rtspBacklogRecoveryPolicy.maxTcpInterleavedQueueAgeMs);
  }

  private long getOldestPacketAgeMs(long nowElapsedRealtimeMs) {
    @Nullable PacketEnvelope oldestPacketEnvelope = checkNotNull(packetEnvelopeQueue).peek();
    return oldestPacketEnvelope == null
        ? 0
        : Math.max(0, nowElapsedRealtimeMs - oldestPacketEnvelope.arrivalElapsedRealtimeMs);
  }

  private long getQueueSpanMs() {
    @Nullable PacketEnvelope oldestPacketEnvelope = checkNotNull(packetEnvelopeQueue).peek();
    return oldestPacketEnvelope == null || lastPacketArrivalElapsedRealtimeMs == C.TIME_UNSET
        ? 0
        : Math.max(0, lastPacketArrivalElapsedRealtimeMs - oldestPacketEnvelope.arrivalElapsedRealtimeMs);
  }

  private static final class PacketEnvelope {
    public final byte[] data;
    public final long arrivalElapsedRealtimeMs;

    public PacketEnvelope(byte[] data, long arrivalElapsedRealtimeMs) {
      this.data = data;
      this.arrivalElapsedRealtimeMs = arrivalElapsedRealtimeMs;
    }
  }
}
