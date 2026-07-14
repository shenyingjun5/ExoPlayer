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

import static com.google.android.exoplayer2.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.android.exoplayer2.robolectric.TestPlayerRunHelper.runUntilPlaybackState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.testutil.ExoPlayerTestRunner;
import com.google.android.exoplayer2.testutil.FakeClock;
import com.google.android.exoplayer2.testutil.FakeMediaClockRenderer;
import com.google.android.exoplayer2.testutil.FakeMediaSource;
import com.google.android.exoplayer2.testutil.FakeTimeline;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for explicitly enabled media clock diagnostics. */
@RunWith(AndroidJUnit4.class)
public final class MediaClockDiagnosticsTest {

  @Test
  public void listenerWithZeroInterval_doesNotReceiveSnapshot() {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 1_000);
    List<MediaClockSnapshot> snapshots = new ArrayList<>();
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(fakeClock)
            .setMediaClockDiagnosticsListener(snapshots::add)
            .setMediaClockDiagnosticsIntervalMs(0)
            .build();

    fakeClock.advanceTime(2_000);

    assertThat(snapshots).isEmpty();
    player.release();
  }

  @Test
  public void intervalBelowMinimum_throws() {
    ExoPlayer.Builder builder =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext());

    assertThrows(
        IllegalArgumentException.class,
        () -> builder.setMediaClockDiagnosticsIntervalMs(249));
  }

  @Test
  public void enabledWithoutAudioRenderer_reportsFailClosedAudioFields() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 1_000);
    List<MediaClockSnapshot> snapshots = new ArrayList<>();
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(fakeClock)
            .setMediaClockDiagnosticsListener(snapshots::add)
            .setMediaClockDiagnosticsIntervalMs(500)
            .build();

    fakeClock.advanceTime(500);
    runMainLooperUntil(() -> snapshots.size() == 1);
    MediaClockSnapshot snapshot = snapshots.get(0);

    assertThat(snapshot.clockSource).isEqualTo(MediaClockSnapshot.CLOCK_SOURCE_STANDALONE);
    assertThat(snapshot.audioRendererPresent).isFalse();
    assertThat(snapshot.audioRendererReady).isFalse();
    assertThat(snapshot.audioRendererEnded).isFalse();
    assertThat(snapshot.audioClockPositionUs).isEqualTo(C.TIME_UNSET);
    assertThat(snapshot.audioClockLastAdvancedElapsedRealtimeMs).isEqualTo(C.TIME_UNSET);
    assertThat(snapshot.audioClockStalledForMs).isEqualTo(C.TIME_UNSET);
    player.release();
  }

  @Test
  public void enabledAudioClock_reportsSourceAndStallOnApplicationLooper() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 1_000);
    TestAudioClockRenderer audioRenderer = new TestAudioClockRenderer();
    List<MediaClockSnapshot> snapshots = new ArrayList<>();
    AtomicReference<Looper> callbackLooper = new AtomicReference<>();
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new ExoPlayer.Builder(
                context,
                (eventHandler,
                        videoRendererEventListener,
                        audioRendererEventListener,
                        textRendererOutput,
                        metadataRendererOutput) ->
                    new Renderer[] {audioRenderer})
            .setClock(fakeClock)
            .setMediaClockDiagnosticsListener(
                snapshot -> {
                  callbackLooper.set(Looper.myLooper());
                  snapshots.add(snapshot);
                })
            .setMediaClockDiagnosticsIntervalMs(500)
            .build();
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.AUDIO_FORMAT));
    player.prepare();
    player.play();
    runUntilPlaybackState(player, Player.STATE_READY);

    MediaClockSnapshot first = advanceAndAwaitSnapshot(fakeClock, snapshots);
    assertThat(callbackLooper.get()).isSameInstanceAs(Looper.getMainLooper());
    assertThat(first.clockSource).isEqualTo(MediaClockSnapshot.CLOCK_SOURCE_STANDALONE);
    assertThat(first.audioRendererPresent).isTrue();
    assertThat(first.audioRendererReady).isTrue();
    assertThat(first.audioRendererEnded).isTrue();
    assertThat(first.audioClockPositionUs).isEqualTo(audioRenderer.positionUs);
    assertThat(first.audioClockLastAdvancedElapsedRealtimeMs).isEqualTo(first.elapsedRealtimeMs);
    assertThat(first.audioClockStalledForMs).isEqualTo(0);

    MediaClockSnapshot second = advanceAndAwaitSnapshot(fakeClock, snapshots);
    assertThat(second.audioClockLastAdvancedElapsedRealtimeMs)
        .isEqualTo(first.elapsedRealtimeMs);
    assertThat(second.audioClockStalledForMs).isEqualTo(500);

    audioRenderer.positionUs += 10_000;
    MediaClockSnapshot third = advanceAndAwaitSnapshot(fakeClock, snapshots);
    assertThat(third.audioClockLastAdvancedElapsedRealtimeMs).isEqualTo(third.elapsedRealtimeMs);
    assertThat(third.audioClockStalledForMs).isEqualTo(0);

    player.release();
  }

  private static MediaClockSnapshot advanceAndAwaitSnapshot(
      FakeClock fakeClock, List<MediaClockSnapshot> snapshots) throws TimeoutException {
    int previousSize = snapshots.size();
    fakeClock.advanceTime(500);
    runMainLooperUntil(() -> snapshots.size() > previousSize);
    return snapshots.get(snapshots.size() - 1);
  }

  private static final class TestAudioClockRenderer extends FakeMediaClockRenderer {

    public long positionUs;

    public TestAudioClockRenderer() {
      super(C.TRACK_TYPE_AUDIO);
      positionUs = MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + 10_000_000;
    }

    @Override
    public long getPositionUs() {
      return positionUs;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {}

    @Override
    public PlaybackParameters getPlaybackParameters() {
      return PlaybackParameters.DEFAULT;
    }
  }
}
