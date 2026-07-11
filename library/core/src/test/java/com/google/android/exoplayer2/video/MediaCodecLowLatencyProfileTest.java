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
package com.google.android.exoplayer2.video;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import org.junit.Test;

/** Unit test for {@link MediaCodecLowLatencyProfile}. */
public final class MediaCodecLowLatencyProfileTest {

  @Test
  public void disabledProfile_doesNotApply() {
    assertThat(MediaCodecLowLatencyProfile.DISABLED.isEnabled()).isFalse();
    assertThat(MediaCodecLowLatencyProfile.DISABLED.shouldApply("c2.qti.avc.decoder")).isFalse();
  }

  @Test
  public void enabledProfileWithoutAllowlist_doesNotApply() {
    MediaCodecLowLatencyProfile profile =
        new MediaCodecLowLatencyProfile.Builder().setEnabled(true).build();

    assertThat(profile.isCodecNameAllowed("c2.qti.avc.decoder")).isFalse();
  }

  @Test
  public void enabledProfileWithAllowlist_appliesOnlyToMatchingCodecFamily() {
    MediaCodecLowLatencyProfile profile =
        new MediaCodecLowLatencyProfile.Builder()
            .setEnabled(true)
            .setCodecNamePrefixes(Arrays.asList("c2.qti.", "OMX.vendor."))
            .build();

    assertThat(profile.isCodecNameAllowed("c2.qti.avc.decoder")).isTrue();
    assertThat(profile.isCodecNameAllowed("OMX.vendor.avc.decoder")).isTrue();
    assertThat(profile.isCodecNameAllowed("c2.android.avc.decoder")).isFalse();
  }
}
