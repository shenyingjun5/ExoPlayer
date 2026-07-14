/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.android.exoplayer2;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MediaClockSnapshot}. */
@RunWith(AndroidJUnit4.class)
public final class MediaClockSnapshotTest {

  @Test
  public void equalValues_areEqualAndHaveSameHashCode() {
    MediaClockSnapshot first = createSnapshot(/* playbackSpeed= */ 1.25f);
    MediaClockSnapshot second = createSnapshot(/* playbackSpeed= */ 1.25f);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  public void differentPlaybackSpeed_isNotEqual() {
    assertThat(createSnapshot(/* playbackSpeed= */ 1f))
        .isNotEqualTo(createSnapshot(/* playbackSpeed= */ 1.25f));
  }

  @Test
  public void toString_containsDiagnosticsFields() {
    String value = createSnapshot(/* playbackSpeed= */ 1.25f).toString();

    assertThat(value).contains("clockSource=2");
    assertThat(value).contains("audioClockPositionUs=456");
    assertThat(value).contains("audioClockStalledForMs=20");
  }

  private static MediaClockSnapshot createSnapshot(float playbackSpeed) {
    return new MediaClockSnapshot(
        /* elapsedRealtimeMs= */ 100,
        /* mediaClockPositionUs= */ 456,
        MediaClockSnapshot.CLOCK_SOURCE_AUDIO_RENDERER,
        /* audioRendererPresent= */ true,
        /* audioRendererReady= */ true,
        /* audioRendererEnded= */ false,
        /* audioClockPositionUs= */ 456,
        /* audioClockLastAdvancedElapsedRealtimeMs= */ 80,
        /* audioClockStalledForMs= */ 20,
        playbackSpeed);
  }
}
