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

import androidx.annotation.IntDef;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Status of an RTSP sample-time to RTP-timestamp diagnostics lookup. */
public final class RtspSampleRtpTimestampMappingStatus {

  /** The RTP timestamp was joined to the sample timestamp. */
  public static final int MAPPED = 0;
  /** No matching access-unit mapping is available. */
  public static final int NOT_FOUND = 1;
  /** A recovery reset cleared stale mappings before the sample was read. */
  public static final int CLEARED_FOR_RECOVERY = 2;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({MAPPED, NOT_FOUND, CLEARED_FOR_RECOVERY})
  public @interface Status {}

  private RtspSampleRtpTimestampMappingStatus() {}
}
