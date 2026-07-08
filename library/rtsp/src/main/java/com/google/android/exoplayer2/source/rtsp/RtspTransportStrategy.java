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

/** Transport strategy for RTP streams in an RTSP session. */
public final class RtspTransportStrategy {

  /** Keeps ExoPlayer's default behavior: UDP first, then TCP fallback when needed. */
  public static final int EXOPLAYER_DEFAULT = 0;
  /** Forces RTP over RTSP TCP interleaving. */
  public static final int FORCE_TCP = 1;
  /** Uses UDP without TCP fallback. Intended for controlled diagnostics and experiments only. */
  public static final int FORCE_UDP = 2;
  /** Uses UDP first and allows TCP fallback. */
  public static final int AUTO_UDP_THEN_TCP = 3;

  /** RTP transport strategy. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({EXOPLAYER_DEFAULT, FORCE_TCP, FORCE_UDP, AUTO_UDP_THEN_TCP})
  public @interface Strategy {}

  private RtspTransportStrategy() {}
}
