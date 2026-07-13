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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.util.MimeTypes;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RtspRtpTrackActivityStats}. */
@RunWith(AndroidJUnit4.class)
public final class RtspRtpTrackActivityStatsTest {

  @Test
  public void valueSemantics_includeEveryField() {
    RtspRtpTrackActivityStats stats = createStats(/* lastSequenceNumber= */ 10);
    RtspRtpTrackActivityStats equalStats = createStats(/* lastSequenceNumber= */ 10);
    RtspRtpTrackActivityStats differentStats = createStats(/* lastSequenceNumber= */ 11);

    assertThat(stats).isEqualTo(equalStats);
    assertThat(stats.hashCode()).isEqualTo(equalStats.hashCode());
    assertThat(stats).isNotEqualTo(differentStats);
    assertThat(stats.toString()).contains("trackId=1");
    assertThat(stats.toString()).contains("lastPacketArrivalElapsedRealtimeMs=1234");
    assertThat(stats.toString()).contains("receivedPacketCount=7");
  }

  private static RtspRtpTrackActivityStats createStats(int lastSequenceNumber) {
    return new RtspRtpTrackActivityStats(
        /* trackId= */ 1,
        MimeTypes.VIDEO_H264,
        RtspTransportMode.TCP_INTERLEAVED,
        /* lastPacketArrivalElapsedRealtimeMs= */ 1234,
        /* receivedPacketCount= */ 7,
        lastSequenceNumber,
        /* lastRtpTimestamp= */ 5678);
  }
}
