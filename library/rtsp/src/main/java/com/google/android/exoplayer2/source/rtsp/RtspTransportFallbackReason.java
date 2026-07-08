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

/** Reason for falling back from UDP RTP to TCP interleaved RTP. */
public final class RtspTransportFallbackReason {

  /** Reason is not known. */
  public static final int UNKNOWN = 0;
  /** The RTSP server rejected UDP transport during SETUP. */
  public static final int UDP_UNSUPPORTED = 1;
  /** UDP transport produced no samples before load completion. */
  public static final int UDP_NO_SAMPLE = 2;
  /** TCP fallback was requested, but the current transport strategy disallows it. */
  public static final int TCP_FALLBACK_UNAVAILABLE = 3;

  /** One of this class's reason constants. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({UNKNOWN, UDP_UNSUPPORTED, UDP_NO_SAMPLE, TCP_FALLBACK_UNAVAILABLE})
  public @interface Reason {}

  private RtspTransportFallbackReason() {}
}
