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

/** RTSP lower transport mode used for RTP and RTCP packets. */
public final class RtspTransportMode {

  /** Transport mode is not known yet. */
  public static final int UNKNOWN = 0;
  /** RTP/RTCP over UDP. */
  public static final int UDP = 1;
  /** RTP/RTCP interleaved over the RTSP TCP connection. */
  public static final int TCP_INTERLEAVED = 2;

  /** One of {@link #UNKNOWN}, {@link #UDP}, or {@link #TCP_INTERLEAVED}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({UNKNOWN, UDP, TCP_INTERLEAVED})
  public @interface Mode {}

  private RtspTransportMode() {}
}
