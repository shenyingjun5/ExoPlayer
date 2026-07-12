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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.util.Util;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link RtcpFeedbackPacket}. */
@RunWith(AndroidJUnit4.class)
public final class RtcpFeedbackPacketTest {

  @Test
  public void buildPli_returnsPayloadSpecificFeedbackPacket() {
    byte[] packet =
        RtcpFeedbackPacket.buildPli(/* senderSsrc= */ 0x01020304, /* mediaSsrc= */ 0x11223344);

    assertThat(packet)
        .isEqualTo(Util.getBytesFromHexString("81CE00020102030411223344"));
  }

  @Test
  public void buildFir_returnsPayloadSpecificFeedbackPacket() {
    byte[] packet =
        RtcpFeedbackPacket.buildFir(
            /* senderSsrc= */ 0x01020304,
            /* mediaSsrc= */ 0x11223344,
            /* sequenceNumber= */ 0x7F);

    assertThat(packet)
        .isEqualTo(Util.getBytesFromHexString("84CE00040102030400000000112233447F000000"));
  }

  @Test
  public void buildGenericNack_returnsRtpFeedbackFmtOnePacket() {
    byte[] packet =
        RtcpFeedbackPacket.buildGenericNack(
            /* senderSsrc= */ 0x01020304,
            /* mediaSsrc= */ 0x11223344,
            /* pid= */ 0xFFFE,
            /* blp= */ 0x0005);

    assertThat(packet).isEqualTo(Util.getBytesFromHexString("81CD00030102030411223344FFFE0005"));
  }
}
