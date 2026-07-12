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

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** RTCP payload-specific feedback type. */
public final class RtcpFeedbackType {

  /** Feedback type is not known or no feedback packet will be sent. */
  public static final int UNKNOWN = 0;
  /** Picture Loss Indication, RFC4585 Section 6.3.1. */
  public static final int PLI = 1;
  /** Full Intra Request, RFC5104 Section 4.3.1.2. */
  public static final int FIR = 2;
  /** Generic NACK, RFC4585 Section 6.2.1. */
  public static final int NACK = 3;

  /** One of {@link #UNKNOWN}, {@link #PLI}, {@link #FIR}, or {@link #NACK}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({UNKNOWN, PLI, FIR, NACK})
  public @interface Type {}

  private RtcpFeedbackType() {}
}
