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

/** Reason for requesting RTCP key-frame feedback. */
public final class RtcpFeedbackReason {

  /** Reason is not known. */
  public static final int UNKNOWN = 0;
  /** Application code explicitly requested a key frame. */
  public static final int APPLICATION = 1;
  /** First RTP packet did not arrive within the expected time. */
  public static final int FIRST_PACKET_TIMEOUT = 2;
  /** RTP sequence gap suggests packet loss. */
  public static final int SEQUENCE_GAP = 3;
  /** RTP reordering queue reset suggests stream discontinuity. */
  public static final int QUEUE_RESET = 4;
  /** An H.264 access unit was corrupted before it could be submitted. */
  public static final int ACCESS_UNIT_CORRUPTED = 5;
  /** The video reader is waiting for a complete IDR access unit to recover. */
  public static final int WAITING_FOR_IDR = 6;
  /** A low-latency RTSP video sample queue has exceeded its permitted backlog. */
  public static final int SAMPLE_QUEUE_BACKLOG = 7;

  /** One of this class's reason constants. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    UNKNOWN,
    APPLICATION,
    FIRST_PACKET_TIMEOUT,
    SEQUENCE_GAP,
    QUEUE_RESET,
    ACCESS_UNIT_CORRUPTED,
    WAITING_FOR_IDR,
    SAMPLE_QUEUE_BACKLOG
  })
  public @interface Reason {}

  private RtcpFeedbackReason() {}
}
