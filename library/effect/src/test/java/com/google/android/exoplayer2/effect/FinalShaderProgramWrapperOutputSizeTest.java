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
package com.google.android.exoplayer2.effect;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.util.Size;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests output-size selection before creating the final presentation shader. */
@RunWith(AndroidJUnit4.class)
public final class FinalShaderProgramWrapperOutputSizeTest {

  @Test
  public void unknownRequestedSize_usesActualEglWindowSize() {
    Size size = FinalShaderProgramWrapper.resolveOutputSize(-1, -1, 1280, 720);

    assertThat(size).isEqualTo(new Size(1280, 720));
  }

  @Test
  public void unknownRequestedSize_withoutPositiveEglSize_remainsUnknown() {
    Size size = FinalShaderProgramWrapper.resolveOutputSize(-1, -1, 0, 0);

    assertThat(size).isEqualTo(Size.UNKNOWN);
  }

  @Test
  public void knownRequestedSize_keepsCallerViewport() {
    Size size = FinalShaderProgramWrapper.resolveOutputSize(800, 600, 1280, 720);

    assertThat(size).isEqualTo(new Size(800, 600));
  }

  @Test
  public void partialRequestedSize_usesWholeEglWindowSize() {
    Size size = FinalShaderProgramWrapper.resolveOutputSize(800, -1, 1280, 720);

    assertThat(size).isEqualTo(new Size(1280, 720));
  }
}
