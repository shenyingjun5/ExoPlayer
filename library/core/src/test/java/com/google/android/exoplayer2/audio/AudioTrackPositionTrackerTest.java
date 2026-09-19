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
package com.google.android.exoplayer2.audio;

import static com.google.android.exoplayer2.util.Util.sampleCountToDurationUs;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.AudioTrack;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * Unit tests for {@link AudioTrackPositionTracker}.
 *
 * <p>These tests cover the playback head position handling backported from Media3:
 *
 * <ul>
 *   <li>An unexpected decrease or reset of the AudioTrack playback head position must not be
 *       mistaken for a 32-bit unsigned integer wrap-around.
 *   <li>An extrapolated position must not run ahead of the frames that were actually written, which
 *       is what happens when the timestamp is extrapolated during an audio underrun.
 * </ul>
 *
 * <p>The tracker smooths the playback head samples against {@link System#nanoTime()}. The test
 * runtime does not virtualise that clock, so a reported position carries a sub-millisecond jitter
 * around the exact frame duration. The assertions below therefore rely on the order-of-magnitude gap
 * between a correct position and a position that jumped by 2^32 frames, which is roughly 27 hours of
 * audio, rather than on exact values.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 30)
public class AudioTrackPositionTrackerTest {

  private static final int SAMPLE_RATE = 44100;
  private static final int CHANNEL_COUNT_STEREO = 2;
  private static final int BYTES_PER_FRAME_16_BIT = 2;
  private static final int OUTPUT_PCM_FRAME_SIZE = CHANNEL_COUNT_STEREO * BYTES_PER_FRAME_16_BIT;
  /** One second of stereo 16-bit audio, used as the AudioTrack buffer size. */
  private static final int BUFFER_SIZE = SAMPLE_RATE * OUTPUT_PCM_FRAME_SIZE;
  private static final long TIME_TO_ADVANCE_MS = 1000L;
  /**
   * A written frame count that exceeds 2^32 frames, so that a mistaken 2^32 frame jump is reported
   * instead of being clamped away by the written-frames limit.
   */
  private static final long LARGE_WRITTEN_FRAMES = 5_000_000_000L;
  /**
   * Half of the 2^32 frame wrap distance. A position beyond this can only come from accounting for
   * a wrap-around, and is far enough above the clock jitter to be a stable assertion bound.
   */
  private static final long HALF_WRAP_POSITION_US = sampleCountToDurationUs(1L << 31, SAMPLE_RATE);

  private final AtomicInteger playbackHeadPositionFrames = new AtomicInteger();
  private AudioTrackPositionTracker audioTrackPositionTracker;

  @Before
  public void setUp() {
    AudioTrack audioTrack = mock(AudioTrack.class);
    when(audioTrack.getSampleRate()).thenReturn(SAMPLE_RATE);
    when(audioTrack.getPlayState()).thenReturn(AudioTrack.PLAYSTATE_PLAYING);
    when(audioTrack.getPlaybackHeadPosition())
        .thenAnswer(invocation -> playbackHeadPositionFrames.get());
    audioTrackPositionTracker =
        new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        /* isPassthrough= */ false,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        BUFFER_SIZE);
    audioTrackPositionTracker.start();
  }

  @Test
  public void getCurrentPositionUs_withPlaybackHeadPositionWrapAround_returnsWrappedValue() {
    // Sample a head position two frames before the wrap point, then wrap past it.
    samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ -2);

    long positionUs = samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ 2);

    // Two frames past the wrap point is still more than 2^31 frames of audio, so a genuine
    // wrap-around must keep being reported as such.
    assertThat(positionUs).isGreaterThan(HALF_WRAP_POSITION_US);
  }

  @Test
  public void getCurrentPositionUs_withUnexpectedPositionDecrease_doesNotReturnWrappedValue() {
    samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ 2 * SAMPLE_RATE);

    long positionUs =
        samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ SAMPLE_RATE);

    // The position must stay near the sampled playback head (one second), not jump to ~27 hours.
    assertThat(positionUs).isLessThan(HALF_WRAP_POSITION_US);
    assertThat(positionUs).isLessThan(Util.msToUs(5000));
  }

  @Test
  public void getCurrentPositionUs_withResidualPositionResettingToZero_doesNotReturnWrappedValue() {
    // Simulates a residual head position from a prior track on a direct/passthrough stream.
    samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ 24_576);

    long positionUs =
        samplePlaybackHeadPositionAndReadPosition(/* playbackHeadPositionFrames= */ 0);

    assertThat(positionUs).isLessThan(HALF_WRAP_POSITION_US);
    assertThat(positionUs).isLessThan(Util.msToUs(5000));
  }

  @Test
  public void getCurrentPositionUs_withPositionBeyondWrittenFrames_isClampedToWrittenFrames() {
    long writtenFrames = SAMPLE_RATE / 2;
    long expectedPositionUs = sampleCountToDurationUs(writtenFrames, SAMPLE_RATE);

    // The playback head is two seconds ahead of the frames that were actually written.
    long positionUs =
        samplePlaybackHeadPositionAndReadPosition(
            /* playbackHeadPositionFrames= */ 2 * SAMPLE_RATE, writtenFrames);

    assertThat(positionUs).isEqualTo(expectedPositionUs);

    // The clamped position must not creep back up on the next read.
    assertThat(
            samplePlaybackHeadPositionAndReadPosition(
                /* playbackHeadPositionFrames= */ 2 * SAMPLE_RATE, writtenFrames))
        .isEqualTo(expectedPositionUs);
  }

  /** Samples the given playback head position and returns the position the tracker reports. */
  private long samplePlaybackHeadPositionAndReadPosition(int playbackHeadPositionFrames) {
    return samplePlaybackHeadPositionAndReadPosition(
        playbackHeadPositionFrames, LARGE_WRITTEN_FRAMES);
  }

  private long samplePlaybackHeadPositionAndReadPosition(
      int playbackHeadPositionFrames, long writtenFrames) {
    // The tracker only samples the playback head position after
    // RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS of elapsed real time and after
    // MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US of system time. Advancing the clock satisfies the
    // former, and resetting the playback speed discards the smoothing state so that the latter is
    // satisfied regardless of how the test runtime advances System.nanoTime().
    ShadowSystemClock.advanceBy(Duration.ofMillis(TIME_TO_ADVANCE_MS));
    this.playbackHeadPositionFrames.set(playbackHeadPositionFrames);
    audioTrackPositionTracker.setAudioTrackPlaybackSpeed(/* audioTrackPlaybackSpeed= */ 1f);
    return audioTrackPositionTracker.getCurrentPositionUs(
        /* sourceEnded= */ false, writtenFrames);
  }
}
