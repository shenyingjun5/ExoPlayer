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
package com.google.android.exoplayer2.video;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.util.VideoFrameProcessor;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the output-size event gate shared by forced and normally scheduled GPU frames. */
@RunWith(AndroidJUnit4.class)
public final class MediaCodecVideoRendererOutputSizeNotificationTest {

  @Test
  public void pendingAtZero_forcedFirstFrameNotifies() {
    MediaCodecVideoRenderer.OutputSizeNotificationGate gate =
        new MediaCodecVideoRenderer.OutputSizeNotificationGate();
    gate.setPendingFramePresentationTimeUs(0);

    assertThat(
            gate.consumeForRelease(
                0, VideoFrameProcessor.RENDER_OUTPUT_FRAME_IMMEDIATELY))
        .isTrue();
  }

  @Test
  public void pendingAtNonzero_laterScheduledFrameNotifies() {
    MediaCodecVideoRenderer.OutputSizeNotificationGate gate =
        new MediaCodecVideoRenderer.OutputSizeNotificationGate();
    gate.setPendingFramePresentationTimeUs(90_000);

    assertThat(gate.consumeForRelease(57_000, /* releaseTimeNs= */ 1_000_000)).isFalse();
    assertThat(gate.consumeForRelease(90_000, /* releaseTimeNs= */ 2_000_000)).isTrue();
  }

  @Test
  public void nonzeroInitialStreamOffset_comparesVfpFrameTimes() {
    MediaCodecVideoRenderer.OutputSizeNotificationGate gate =
        new MediaCodecVideoRenderer.OutputSizeNotificationGate();
    long initialStreamOffsetUs = 5_000_000;
    gate.setPendingFramePresentationTimeUs(90_000);
    long previousBufferPresentationTimeUs = 5_057_000;
    long dueBufferPresentationTimeUs = 5_090_000;

    assertThat(
            gate.consumeForRelease(
                previousBufferPresentationTimeUs - initialStreamOffsetUs,
                /* releaseTimeNs= */ 1_000_000))
        .isFalse();
    assertThat(
            gate.consumeForRelease(
                dueBufferPresentationTimeUs - initialStreamOffsetUs,
                /* releaseTimeNs= */ 2_000_000))
        .isTrue();
  }

  @Test
  public void droppedFrame_doesNotNotifyOrConsumePendingSize() {
    MediaCodecVideoRenderer.OutputSizeNotificationGate gate =
        new MediaCodecVideoRenderer.OutputSizeNotificationGate();
    gate.setPendingFramePresentationTimeUs(90_000);

    assertThat(gate.consumeForRelease(90_000, VideoFrameProcessor.DROP_OUTPUT_FRAME)).isFalse();
    assertThat(gate.consumeForRelease(120_000, /* releaseTimeNs= */ 2_000_000)).isTrue();
  }

  @Test
  public void consumedSize_doesNotNotifyAgain() {
    MediaCodecVideoRenderer.OutputSizeNotificationGate gate =
        new MediaCodecVideoRenderer.OutputSizeNotificationGate();
    gate.setPendingFramePresentationTimeUs(90_000);

    assertThat(gate.consumeForRelease(90_000, /* releaseTimeNs= */ 2_000_000)).isTrue();
    assertThat(gate.consumeForRelease(120_000, /* releaseTimeNs= */ 3_000_000)).isFalse();
  }
}
