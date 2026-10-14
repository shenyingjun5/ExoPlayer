package com.google.android.exoplayer2.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Registered frame timing must not mix a vendor monotonic timestamp with media time. */
public final class FrameInfoTest {
  @Test
  public void defaultUsesSurfaceTimestampAndOffset() {
    FrameInfo frame = new FrameInfo.Builder(1920, 1080).setOffsetToAddUs(42).build();
    assertThat(frame.resolvePresentationTimeUs(123000)).isEqualTo(165);
  }

  @Test
  public void overrideUsesCodecTimeIncludingZeroAndNegativePreroll() {
    for (long ptsUs : new long[] {-33333, 0, 33333, 11566000}) {
      FrameInfo frame = new FrameInfo.Builder(1920, 1080)
          .setPresentationTimeOverrideUs(ptsUs).setOffsetToAddUs(17).build();
      assertThat(frame.resolvePresentationTimeUs(32629592671000L)).isEqualTo(ptsUs + 17);
      assertThat(new FrameInfo.Builder(frame).setWidth(1080).setHeight(1920).build()
          .resolvePresentationTimeUs(0)).isEqualTo(ptsUs + 17);
    }
  }
}
