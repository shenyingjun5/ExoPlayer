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
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link RtcpSenderReportPacket}. */
@RunWith(AndroidJUnit4.class)
public final class RtcpSenderReportPacketTest {

  private static final byte[] SENDER_REPORT =
      Util.getBytesFromHexString(
          "80C80006"
              + "12345678"
              + "00000002"
              + "80000000"
              + "FFFFFFFE"
              + "00000003"
              + "00000004");

  @Test
  public void parseSenderReports_singleSenderReport_parsesUnsignedFields() {
    ImmutableList<RtcpSenderReportStats> reports =
        RtcpSenderReportPacket.parseSenderReports(
            SENDER_REPORT,
            SENDER_REPORT.length,
            /* trackId= */ 7,
            RtspTransportMode.TCP_INTERLEAVED,
            /* clockRate= */ 90_000,
            /* receivedElapsedRealtimeMs= */ 1234);

    assertThat(reports).hasSize(1);
    RtcpSenderReportStats stats = reports.get(0);
    assertThat(stats.trackId).isEqualTo(7);
    assertThat(stats.ssrc).isEqualTo(0x12345678L);
    assertThat(stats.rawNtpSeconds).isEqualTo(2);
    assertThat(stats.rawNtpFraction).isEqualTo(0x80000000L);
    assertThat(stats.ntpTimeMs).isEqualTo(2500);
    assertThat(stats.rtpTimestamp).isEqualTo(0xFFFFFFFEL);
    assertThat(stats.packetCount).isEqualTo(3);
    assertThat(stats.octetCount).isEqualTo(4);
    assertThat(stats.transportMode).isEqualTo(RtspTransportMode.TCP_INTERLEAVED);
    assertThat(stats.clockRate).isEqualTo(90_000);
    assertThat(stats.receivedElapsedRealtimeMs).isEqualTo(1234);
  }

  @Test
  public void parseSenderReports_compoundRtcp_extractsOnlySenderReports() {
    byte[] receiverReport = Util.getBytesFromHexString("80C9000111111111");
    byte[] psfbPli = Util.getBytesFromHexString("81CE00022222222233333333");
    byte[] compound = Bytes.concat(receiverReport, SENDER_REPORT, psfbPli);

    ImmutableList<RtcpSenderReportStats> reports =
        RtcpSenderReportPacket.parseSenderReports(
            compound,
            compound.length,
            /* trackId= */ 1,
            RtspTransportMode.UDP,
            /* clockRate= */ 48_000,
            /* receivedElapsedRealtimeMs= */ 99);

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).ssrc).isEqualTo(0x12345678L);
    assertThat(reports.get(0).transportMode).isEqualTo(RtspTransportMode.UDP);
    assertThat(reports.get(0).clockRate).isEqualTo(48_000);
  }

  @Test
  public void parseSenderReports_nonSenderReports_returnsEmptyList() {
    byte[] compound =
        Bytes.concat(
            Util.getBytesFromHexString("80C9000111111111"),
            Util.getBytesFromHexString("81CE00022222222233333333"));

    assertThat(
            RtcpSenderReportPacket.parseSenderReports(
                compound,
                compound.length,
                /* trackId= */ 1,
                RtspTransportMode.UDP,
                /* clockRate= */ 90_000,
                /* receivedElapsedRealtimeMs= */ 1))
        .isEmpty();
  }

  @Test
  public void parseSenderReports_malformedLength_returnsEmptyList() {
    byte[] malformedLength = Util.getBytesFromHexString("80C8FFFF12345678");

    assertThat(
            RtcpSenderReportPacket.parseSenderReports(
                malformedLength,
                malformedLength.length,
                /* trackId= */ 1,
                RtspTransportMode.UDP,
                /* clockRate= */ 90_000,
                /* receivedElapsedRealtimeMs= */ 1))
        .isEmpty();
  }

  @Test
  public void parseSenderReports_invalidVersion_returnsEmptyList() {
    byte[] invalidVersion = Util.getBytesFromHexString("40C80006123456780000000280000000");

    assertThat(
            RtcpSenderReportPacket.parseSenderReports(
                invalidVersion,
                invalidVersion.length,
                /* trackId= */ 1,
                RtspTransportMode.UDP,
                /* clockRate= */ 90_000,
                /* receivedElapsedRealtimeMs= */ 1))
        .isEmpty();
  }
}
