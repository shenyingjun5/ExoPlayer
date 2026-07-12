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

import java.nio.ByteBuffer;

/** Builds RTCP payload-specific feedback packets. */
/* package */ final class RtcpFeedbackPacket {

  private static final int VERSION = 2;
  private static final int PAYLOAD_TYPE_PSFB = 206;
  private static final int PAYLOAD_TYPE_RTPFB = 205;
  private static final int FMT_PLI = 1;
  private static final int FMT_FIR = 4;
  private static final int PLI_LENGTH_WORDS_MINUS_ONE = 2;
  private static final int FIR_LENGTH_WORDS_MINUS_ONE = 4;

  /** Builds a Picture Loss Indication packet. */
  public static byte[] buildPli(int senderSsrc, int mediaSsrc) {
    ByteBuffer packet = ByteBuffer.allocate(12);
    writeCommonFeedbackHeader(packet, FMT_PLI, PLI_LENGTH_WORDS_MINUS_ONE, senderSsrc, mediaSsrc);
    return packet.array();
  }

  /** Builds a Full Intra Request packet containing one FCI entry. */
  public static byte[] buildFir(int senderSsrc, int mediaSsrc, int sequenceNumber) {
    ByteBuffer packet = ByteBuffer.allocate(20);
    // For FIR, the media source SSRC in the common feedback header is set to zero. The target SSRC
    // is carried in the FCI entry.
    writeCommonFeedbackHeader(
        packet, FMT_FIR, FIR_LENGTH_WORDS_MINUS_ONE, senderSsrc, /* mediaSsrc= */ 0);
    packet.putInt(mediaSsrc);
    packet.put((byte) (sequenceNumber & 0xFF));
    packet.put((byte) 0);
    packet.put((byte) 0);
    packet.put((byte) 0);
    return packet.array();
  }

  /** Builds one RFC4585 Generic NACK RTPFB/FMT=1 packet. */
  public static byte[] buildGenericNack(int senderSsrc, int mediaSsrc, int pid, int blp) {
    ByteBuffer packet = ByteBuffer.allocate(16);
    packet.put((byte) ((VERSION << 6) | 1));
    packet.put((byte) PAYLOAD_TYPE_RTPFB);
    packet.putShort((short) 3);
    packet.putInt(senderSsrc);
    packet.putInt(mediaSsrc);
    packet.putShort((short) pid);
    packet.putShort((short) blp);
    return packet.array();
  }

  private static void writeCommonFeedbackHeader(
      ByteBuffer packet, int fmt, int lengthWordsMinusOne, int senderSsrc, int mediaSsrc) {
    packet.put((byte) ((VERSION << 6) | (fmt & 0x1F)));
    packet.put((byte) PAYLOAD_TYPE_PSFB);
    packet.putShort((short) lengthWordsMinusOne);
    packet.putInt(senderSsrc);
    packet.putInt(mediaSsrc);
  }

  private RtcpFeedbackPacket() {}
}
