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

import androidx.annotation.IntDef;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An immutable low-frequency snapshot of the player's media clock state. */
@Deprecated
public final class MediaClockSnapshot {

  /** No active media clock source is available. */
  public static final int CLOCK_SOURCE_NONE = 0;

  /** The player's standalone clock is active. */
  public static final int CLOCK_SOURCE_STANDALONE = 1;

  /** An audio renderer clock is active. */
  public static final int CLOCK_SOURCE_AUDIO_RENDERER = 2;

  /** A non-audio renderer clock is active. */
  public static final int CLOCK_SOURCE_OTHER_RENDERER = 3;

  /** Identifies the active media clock source. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    CLOCK_SOURCE_NONE,
    CLOCK_SOURCE_STANDALONE,
    CLOCK_SOURCE_AUDIO_RENDERER,
    CLOCK_SOURCE_OTHER_RENDERER
  })
  public @interface ClockSource {}

  /** Snapshot time from {@link android.os.SystemClock#elapsedRealtime()}. */
  public final long elapsedRealtimeMs;

  /** Position of the currently selected player media clock, in microseconds. */
  public final long mediaClockPositionUs;

  /** The currently selected media clock source. */
  public final @ClockSource int clockSource;

  /** Whether an enabled audio renderer is present. */
  public final boolean audioRendererPresent;

  /** Whether the enabled audio renderer reports itself ready. */
  public final boolean audioRendererReady;

  /** Whether the enabled audio renderer reports itself ended. */
  public final boolean audioRendererEnded;

  /** Audio renderer clock position in microseconds, or {@link C#TIME_UNSET}. */
  public final long audioClockPositionUs;

  /** Last snapshot time at which the audio clock position changed, or {@link C#TIME_UNSET}. */
  public final long audioClockLastAdvancedElapsedRealtimeMs;

  /** Time since the audio clock position last changed, or {@link C#TIME_UNSET}. */
  public final long audioClockStalledForMs;

  /** Active playback speed. */
  public final float playbackSpeed;

  /** Creates an immutable media clock diagnostics snapshot. */
  public MediaClockSnapshot(
      long elapsedRealtimeMs,
      long mediaClockPositionUs,
      @ClockSource int clockSource,
      boolean audioRendererPresent,
      boolean audioRendererReady,
      boolean audioRendererEnded,
      long audioClockPositionUs,
      long audioClockLastAdvancedElapsedRealtimeMs,
      long audioClockStalledForMs,
      float playbackSpeed) {
    this.elapsedRealtimeMs = elapsedRealtimeMs;
    this.mediaClockPositionUs = mediaClockPositionUs;
    this.clockSource = clockSource;
    this.audioRendererPresent = audioRendererPresent;
    this.audioRendererReady = audioRendererReady;
    this.audioRendererEnded = audioRendererEnded;
    this.audioClockPositionUs = audioClockPositionUs;
    this.audioClockLastAdvancedElapsedRealtimeMs = audioClockLastAdvancedElapsedRealtimeMs;
    this.audioClockStalledForMs = audioClockStalledForMs;
    this.playbackSpeed = playbackSpeed;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MediaClockSnapshot)) {
      return false;
    }
    MediaClockSnapshot other = (MediaClockSnapshot) obj;
    return elapsedRealtimeMs == other.elapsedRealtimeMs
        && mediaClockPositionUs == other.mediaClockPositionUs
        && clockSource == other.clockSource
        && audioRendererPresent == other.audioRendererPresent
        && audioRendererReady == other.audioRendererReady
        && audioRendererEnded == other.audioRendererEnded
        && audioClockPositionUs == other.audioClockPositionUs
        && audioClockLastAdvancedElapsedRealtimeMs
            == other.audioClockLastAdvancedElapsedRealtimeMs
        && audioClockStalledForMs == other.audioClockStalledForMs
        && Float.compare(playbackSpeed, other.playbackSpeed) == 0;
  }

  @Override
  public int hashCode() {
    int result = (int) (elapsedRealtimeMs ^ (elapsedRealtimeMs >>> 32));
    result = 31 * result + (int) (mediaClockPositionUs ^ (mediaClockPositionUs >>> 32));
    result = 31 * result + clockSource;
    result = 31 * result + (audioRendererPresent ? 1 : 0);
    result = 31 * result + (audioRendererReady ? 1 : 0);
    result = 31 * result + (audioRendererEnded ? 1 : 0);
    result = 31 * result + (int) (audioClockPositionUs ^ (audioClockPositionUs >>> 32));
    result =
        31 * result
            + (int)
                (audioClockLastAdvancedElapsedRealtimeMs
                    ^ (audioClockLastAdvancedElapsedRealtimeMs >>> 32));
    result = 31 * result + (int) (audioClockStalledForMs ^ (audioClockStalledForMs >>> 32));
    result = 31 * result + Float.floatToIntBits(playbackSpeed);
    return result;
  }

  @Override
  public String toString() {
    return "MediaClockSnapshot{"
        + "elapsedRealtimeMs="
        + elapsedRealtimeMs
        + ", mediaClockPositionUs="
        + mediaClockPositionUs
        + ", clockSource="
        + clockSource
        + ", audioRendererPresent="
        + audioRendererPresent
        + ", audioRendererReady="
        + audioRendererReady
        + ", audioRendererEnded="
        + audioRendererEnded
        + ", audioClockPositionUs="
        + audioClockPositionUs
        + ", audioClockLastAdvancedElapsedRealtimeMs="
        + audioClockLastAdvancedElapsedRealtimeMs
        + ", audioClockStalledForMs="
        + audioClockStalledForMs
        + ", playbackSpeed="
        + playbackSpeed
        + '}';
  }
}
