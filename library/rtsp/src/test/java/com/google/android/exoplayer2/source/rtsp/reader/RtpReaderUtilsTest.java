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

/** Unit tests for {@link RtpReaderUtils}. */
@RunWith(AndroidJUnit4.class)
public final class RtpReaderUtilsTest {

  @Test
  public void toSampleTimeUs_handlesRtpTimestampWraparound() {
    long timeUs =
        RtpReaderUtils.toSampleTimeUs(
            /* startTimeOffsetUs= */ 0,
            /* rtpTimestamp= */ 89_999,
            /* firstReceivedRtpTimestamp= */ 0xFFFF_FFFFL,
            /* mediaFrequency= */ 90_000);

    assertThat(timeUs)
        .isEqualTo(Util.scaleLargeTimestamp(90_000, C.MICROS_PER_SECOND, 90_000));
  }
}
