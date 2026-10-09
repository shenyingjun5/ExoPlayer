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
package com.google.android.exoplayer2.source.rtsp.reader;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RtpTimestampAdjuster}. */
@RunWith(AndroidJUnit4.class)
public final class RtpTimestampAdjusterTest {

  @Test
  public void toSampleTimeUs_handlesRtpTimestampWraparound() {
    long timeUs =
        new RtpTimestampAdjuster().toSampleTimeUs(
            /* startTimeOffsetUs= */ 0,
            /* rtpTimestamp= */ 89_999,
            /* firstReceivedRtpTimestamp= */ 0xFFFF_FFFFL,
            /* mediaFrequency= */ 90_000);

    assertThat(timeUs)
        .isEqualTo(Util.scaleLargeTimestamp(90_000, C.MICROS_PER_SECOND, 90_000));
  }
  @Test public void negativePrerollAndCrossingOrigin_remainContinuous() {
    RtpTimestampAdjuster adjuster = new RtpTimestampAdjuster();
    assertThat(adjuster.toSampleTimeUs(0, 47900, 48000, 48000)).isEqualTo(-2083);
    assertThat(adjuster.toSampleTimeUs(0, 48000, 48000, 48000)).isEqualTo(0);
    assertThat(adjuster.toSampleTimeUs(0, 49024, 48000, 48000)).isEqualTo(21333);
  }

  @Test public void twoDaysOfContinuousSamples_crossMultipleCyclesWithoutGoingNegative() {
    RtpTimestampAdjuster adjuster = new RtpTimestampAdjuster();
    long anchor = 0xffff0000L;
    for (int minute = 0; minute <= 48 * 60; minute++) {
      long ticks = minute * 60L * 90000;
      assertThat(adjuster.toSampleTimeUs(0, (anchor + ticks) & 0xffffffffL, anchor, 90000))
          .isEqualTo(minute * 60L * 1000000);
    }
  }

  @Test public void reorderedPresentationTimestamps_doNotAddAClockCycle() {
    RtpTimestampAdjuster adjuster = new RtpTimestampAdjuster();
    assertThat(adjuster.toSampleTimeUs(0, 90000, 0, 90000)).isEqualTo(1000000);
    assertThat(adjuster.toSampleTimeUs(0, 87000, 0, 90000)).isEqualTo(966666);
    assertThat(adjuster.toSampleTimeUs(0, 93000, 0, 90000)).isEqualTo(1033333);
  }

  @Test public void seekResetAndChangedAnchor_startANewClockMapping() {
    RtpTimestampAdjuster adjuster = new RtpTimestampAdjuster();
    adjuster.toSampleTimeUs(0, 1000000000, 0, 90000);
    adjuster.toSampleTimeUs(0, 2000000000, 0, 90000);
    adjuster.toSampleTimeUs(0, 3000000000L, 0, 90000);
    adjuster.reset();
    assertThat(adjuster.toSampleTimeUs(0, 100, 0, 90000)).isEqualTo(1111);
    assertThat(adjuster.toSampleTimeUs(5000000, 48000, 48000, 48000)).isEqualTo(5000000);
  }

}
