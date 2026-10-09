/*
 * Copyright 2022 The Android Open Source Project
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
package com.google.android.exoplayer2.source.rtsp.reader;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;

/** Unwraps one track's RTP clock while retaining signed preroll relative to PLAY/seek timing. */
/* package */ final class RtpTimestampAdjuster {
  private boolean initialized;
  private long previousTimestamp;
  private long accumulatedTicks;
  private long anchorTimestamp;
  private long anchorOffsetUs;

  public void reset() {
    initialized = false;
  }

  public long toSampleTimeUs(long startTimeOffsetUs, long rtpTimestamp,
      long firstReceivedRtpTimestamp, int mediaFrequency) {
    if (!initialized || anchorTimestamp != firstReceivedRtpTimestamp
        || anchorOffsetUs != startTimeOffsetUs) {
      previousTimestamp = firstReceivedRtpTimestamp;
      accumulatedTicks = 0;
      anchorTimestamp = firstReceivedRtpTimestamp;
      anchorOffsetUs = startTimeOffsetUs;
      initialized = true;
    }
    // Consecutive samples are less than half an RTP clock cycle apart. Unwrap each step,
    // not the full session distance: negative preroll/B-frame PTS remain negative, while
    // streams longer than half a cycle (or multiple complete cycles) keep advancing.
    accumulatedTicks += (int) (rtpTimestamp - previousTimestamp);
    previousTimestamp = rtpTimestamp;
    return startTimeOffsetUs + Util.scaleLargeTimestamp(
        accumulatedTicks, C.MICROS_PER_SECOND, mediaFrequency);
  }
}
