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

/** Diagnostics snapshot for an RTSP sample handed to the downstream decoder input path. */
public final class RtspDecoderInputQueuedStats {

  public final int trackId;
  public final int sampleQueueIndex;
  public final long sampleTimeUs;
  public final long rtpTimestamp;
  public final @RtspSampleRtpTimestampMappingStatus.Status int rtpTimestampMappingStatus;
  public final long queuedElapsedRealtimeMs;

  public RtspDecoderInputQueuedStats(
      int trackId,
      int sampleQueueIndex,
      long sampleTimeUs,
      long rtpTimestamp,
      long queuedElapsedRealtimeMs) {
    this(
        trackId,
        sampleQueueIndex,
        sampleTimeUs,
        rtpTimestamp,
        rtpTimestamp == com.google.android.exoplayer2.C.TIME_UNSET
            ? RtspSampleRtpTimestampMappingStatus.NOT_FOUND
            : RtspSampleRtpTimestampMappingStatus.MAPPED,
        queuedElapsedRealtimeMs);
  }

  public RtspDecoderInputQueuedStats(
      int trackId,
      int sampleQueueIndex,
      long sampleTimeUs,
      long rtpTimestamp,
      @RtspSampleRtpTimestampMappingStatus.Status int rtpTimestampMappingStatus,
      long queuedElapsedRealtimeMs) {
    this.trackId = trackId;
    this.sampleQueueIndex = sampleQueueIndex;
    this.sampleTimeUs = sampleTimeUs;
    this.rtpTimestamp = rtpTimestamp;
    this.rtpTimestampMappingStatus = rtpTimestampMappingStatus;
    this.queuedElapsedRealtimeMs = queuedElapsedRealtimeMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspDecoderInputQueuedStats)) {
      return false;
    }
    RtspDecoderInputQueuedStats other = (RtspDecoderInputQueuedStats) obj;
    return trackId == other.trackId
        && sampleQueueIndex == other.sampleQueueIndex
        && sampleTimeUs == other.sampleTimeUs
        && rtpTimestamp == other.rtpTimestamp
        && rtpTimestampMappingStatus == other.rtpTimestampMappingStatus
        && queuedElapsedRealtimeMs == other.queuedElapsedRealtimeMs;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + sampleQueueIndex;
    result = 31 * result + (int) (sampleTimeUs ^ (sampleTimeUs >>> 32));
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + rtpTimestampMappingStatus;
    result = 31 * result + (int) (queuedElapsedRealtimeMs ^ (queuedElapsedRealtimeMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspDecoderInputQueuedStats(trackId=%d, sampleQueueIndex=%d, sampleTimeUs=%d, "
            + "rtpTimestamp=%d, rtpTimestampMappingStatus=%d, queuedElapsedRealtimeMs=%d)",
        trackId,
        sampleQueueIndex,
        sampleTimeUs,
        rtpTimestamp,
        rtpTimestampMappingStatus,
        queuedElapsedRealtimeMs);
  }
}
