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

import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Parses RTCP Sender Report packets from compound RTCP payloads. */
/* package */ final class RtcpSenderReportPacket {

  private static final int VERSION = 2;
  private static final int PAYLOAD_TYPE_SENDER_REPORT = 200;
  private static final int RTCP_COMMON_HEADER_SIZE = 4;
  private static final int SENDER_REPORT_FIXED_PAYLOAD_SIZE = 24;
  private static final long NTP_FRACTION_DENOMINATOR = 0x1_0000_0000L;

  public static ImmutableList<RtcpSenderReportStats> parseSenderReports(
      byte[] packet,
      int packetLength,
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int clockRate,
      long receivedElapsedRealtimeMs) {
    ImmutableList.Builder<RtcpSenderReportStats> reports = ImmutableList.builder();
    int offset = 0;
    while (offset + RTCP_COMMON_HEADER_SIZE <= packetLength) {
      int firstByte = packet[offset] & 0xFF;
      int version = firstByte >> 6;
      boolean padding = (firstByte & 0x20) != 0;
      int payloadType = packet[offset + 1] & 0xFF;
      int lengthWordsMinusOne = readUnsignedShort(packet, offset + 2);
      int blockLength = (lengthWordsMinusOne + 1) * 4;
      if (version != VERSION
          || blockLength < RTCP_COMMON_HEADER_SIZE
          || offset + blockLength > packetLength) {
        break;
      }

      int payloadEnd = offset + blockLength;
      if (padding) {
        int paddingBytes = packet[payloadEnd - 1] & 0xFF;
        if (paddingBytes <= 0 || paddingBytes > blockLength - RTCP_COMMON_HEADER_SIZE) {
          break;
        }
        payloadEnd -= paddingBytes;
      }

      if (payloadType == PAYLOAD_TYPE_SENDER_REPORT
          && payloadEnd - offset >= RTCP_COMMON_HEADER_SIZE + SENDER_REPORT_FIXED_PAYLOAD_SIZE) {
        reports.add(
            parseSenderReport(
                packet, offset, trackId, transportMode, clockRate, receivedElapsedRealtimeMs));
      }
      offset += blockLength;
    }
    return reports.build();
  }

  private static RtcpSenderReportStats parseSenderReport(
      byte[] packet,
      int offset,
      int trackId,
      @RtspTransportMode.Mode int transportMode,
      int clockRate,
      long receivedElapsedRealtimeMs) {
    ByteBuffer buffer =
        ByteBuffer.wrap(packet, offset + RTCP_COMMON_HEADER_SIZE, SENDER_REPORT_FIXED_PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN);
    long ssrc = readUnsignedInt(buffer);
    long rawNtpSeconds = readUnsignedInt(buffer);
    long rawNtpFraction = readUnsignedInt(buffer);
    long rtpTimestamp = readUnsignedInt(buffer);
    long packetCount = readUnsignedInt(buffer);
    long octetCount = readUnsignedInt(buffer);
    long ntpTimeMs =
        rawNtpSeconds * 1000 + (rawNtpFraction * 1000) / NTP_FRACTION_DENOMINATOR;
    return new RtcpSenderReportStats(
        trackId,
        ssrc,
        rtpTimestamp,
        ntpTimeMs,
        rawNtpSeconds,
        rawNtpFraction,
        receivedElapsedRealtimeMs,
        transportMode,
        clockRate,
        packetCount,
        octetCount);
  }

  private static int readUnsignedShort(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
  }

  private static long readUnsignedInt(ByteBuffer buffer) {
    return buffer.getInt() & 0xFFFF_FFFFL;
  }

  private RtcpSenderReportPacket() {}
}
