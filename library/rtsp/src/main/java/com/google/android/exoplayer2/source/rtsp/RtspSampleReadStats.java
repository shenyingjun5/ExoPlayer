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
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;

/** Diagnostics snapshot for one sample read from an RTSP {@code SampleQueue}. */
public final class RtspSampleReadStats {

  public final int trackId;
  public final int sampleQueueIndex;
  public final long sampleTimeUs;
  public final long rtpTimestamp;
  public final @RtspSampleRtpTimestampMappingStatus.Status int rtpTimestampMappingStatus;
  public final long readElapsedRealtimeMs;
  public final long sampleQueueBufferedAheadMs;
  public final long mediaPeriodBufferedAheadMs;

  public RtspSampleReadStats(
      int trackId,
      int sampleQueueIndex,
      long sampleTimeUs,
      long readElapsedRealtimeMs,
      long sampleQueueBufferedAheadMs,
      long mediaPeriodBufferedAheadMs) {
    this(
        trackId,
        sampleQueueIndex,
        sampleTimeUs,
        /* rtpTimestamp= */ C.TIME_UNSET,
        RtspSampleRtpTimestampMappingStatus.NOT_FOUND,
        readElapsedRealtimeMs,
        sampleQueueBufferedAheadMs,
        mediaPeriodBufferedAheadMs);
  }

  public RtspSampleReadStats(
      int trackId,
      int sampleQueueIndex,
      long sampleTimeUs,
      long rtpTimestamp,
      long readElapsedRealtimeMs,
      long sampleQueueBufferedAheadMs,
      long mediaPeriodBufferedAheadMs) {
    this(
        trackId,
        sampleQueueIndex,
        sampleTimeUs,
        rtpTimestamp,
        rtpTimestamp == C.TIME_UNSET
            ? RtspSampleRtpTimestampMappingStatus.NOT_FOUND
            : RtspSampleRtpTimestampMappingStatus.MAPPED,
        readElapsedRealtimeMs,
        sampleQueueBufferedAheadMs,
        mediaPeriodBufferedAheadMs);
  }

  public RtspSampleReadStats(
      int trackId,
      int sampleQueueIndex,
      long sampleTimeUs,
      long rtpTimestamp,
      @RtspSampleRtpTimestampMappingStatus.Status int rtpTimestampMappingStatus,
      long readElapsedRealtimeMs,
      long sampleQueueBufferedAheadMs,
      long mediaPeriodBufferedAheadMs) {
    this.trackId = trackId;
    this.sampleQueueIndex = sampleQueueIndex;
    this.sampleTimeUs = sampleTimeUs;
    this.rtpTimestamp = rtpTimestamp;
    this.rtpTimestampMappingStatus = rtpTimestampMappingStatus;
    this.readElapsedRealtimeMs = readElapsedRealtimeMs;
    this.sampleQueueBufferedAheadMs = sampleQueueBufferedAheadMs;
    this.mediaPeriodBufferedAheadMs = mediaPeriodBufferedAheadMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RtspSampleReadStats)) {
      return false;
    }
    RtspSampleReadStats other = (RtspSampleReadStats) obj;
    return trackId == other.trackId
        && sampleQueueIndex == other.sampleQueueIndex
        && sampleTimeUs == other.sampleTimeUs
        && rtpTimestamp == other.rtpTimestamp
        && rtpTimestampMappingStatus == other.rtpTimestampMappingStatus
        && readElapsedRealtimeMs == other.readElapsedRealtimeMs
        && sampleQueueBufferedAheadMs == other.sampleQueueBufferedAheadMs
        && mediaPeriodBufferedAheadMs == other.mediaPeriodBufferedAheadMs;
  }

  @Override
  public int hashCode() {
    int result = trackId;
    result = 31 * result + sampleQueueIndex;
    result = 31 * result + (int) (sampleTimeUs ^ (sampleTimeUs >>> 32));
    result = 31 * result + (int) (rtpTimestamp ^ (rtpTimestamp >>> 32));
    result = 31 * result + rtpTimestampMappingStatus;
    result = 31 * result + (int) (readElapsedRealtimeMs ^ (readElapsedRealtimeMs >>> 32));
    result = 31 * result + (int) (sampleQueueBufferedAheadMs ^ (sampleQueueBufferedAheadMs >>> 32));
    result = 31 * result + (int) (mediaPeriodBufferedAheadMs ^ (mediaPeriodBufferedAheadMs >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return Util.formatInvariant(
        "RtspSampleReadStats(trackId=%d, sampleQueueIndex=%d, sampleTimeUs=%d, "
            + "rtpTimestamp=%d, rtpTimestampMappingStatus=%d, readElapsedRealtimeMs=%d, "
            + "sampleQueueBufferedAheadMs=%d, mediaPeriodBufferedAheadMs=%d)",
        trackId,
        sampleQueueIndex,
        sampleTimeUs,
        rtpTimestamp,
        rtpTimestampMappingStatus,
        readElapsedRealtimeMs,
        sampleQueueBufferedAheadMs,
        mediaPeriodBufferedAheadMs);
  }
}
