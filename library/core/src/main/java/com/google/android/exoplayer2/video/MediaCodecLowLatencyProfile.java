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

import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * An explicit profile for enabling the standard Android 11 low-latency decoder hint.
 *
 * <p>The profile is disabled by default. When enabled, it applies only on API level 30 or later and
 * only to codec names matching an allowlisted prefix. It never sets vendor-specific codec keys.
 */
public final class MediaCodecLowLatencyProfile {

  /** A profile that does not modify decoder configuration. */
  public static final MediaCodecLowLatencyProfile DISABLED = new Builder().build();

  private final boolean enabled;
  private final ImmutableList<String> codecNamePrefixes;

  private MediaCodecLowLatencyProfile(boolean enabled, ImmutableList<String> codecNamePrefixes) {
    this.enabled = enabled;
    this.codecNamePrefixes = codecNamePrefixes;
  }

  /** Returns whether the profile is explicitly enabled. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Returns whether the standard low-latency hint may be applied to {@code codecName}. */
  public boolean shouldApply(String codecName) {
    return enabled && Util.SDK_INT >= 30 && isCodecNameAllowed(codecName);
  }

  /* package */ boolean isCodecNameAllowed(String codecName) {
    for (int i = 0; i < codecNamePrefixes.size(); i++) {
      if (codecName.startsWith(codecNamePrefixes.get(i))) {
        return true;
      }
    }
    return false;
  }

  /** Builder for {@link MediaCodecLowLatencyProfile}. */
  public static final class Builder {

    private boolean enabled;
    private ImmutableList<String> codecNamePrefixes;

    /** Creates a builder with the profile disabled and no codec allowlist. */
    public Builder() {
      codecNamePrefixes = ImmutableList.of();
    }

    /** Sets whether the profile is enabled. The default value is {@code false}. */
    public Builder setEnabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Sets the allowed codec-name prefixes.
     *
     * <p>An empty allowlist never applies the hint. Prefix matching makes it possible to pin an
     * experimentally validated codec family without applying the hint to every device decoder.
     */
    public Builder setCodecNamePrefixes(List<String> codecNamePrefixes) {
      ImmutableList.Builder<String> prefixes = ImmutableList.builder();
      for (int i = 0; i < codecNamePrefixes.size(); i++) {
        String prefix = Assertions.checkNotNull(codecNamePrefixes.get(i));
        Assertions.checkArgument(!prefix.isEmpty());
        prefixes.add(prefix);
      }
      this.codecNamePrefixes = prefixes.build();
      return this;
    }

    /** Creates the immutable profile. */
    public MediaCodecLowLatencyProfile build() {
      return new MediaCodecLowLatencyProfile(enabled, codecNamePrefixes);
    }
  }
}
