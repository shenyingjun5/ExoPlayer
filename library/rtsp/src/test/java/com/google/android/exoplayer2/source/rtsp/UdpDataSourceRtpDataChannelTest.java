/*
 * Copyright 2021 The Android Open Source Project
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
import com.google.android.exoplayer2.upstream.UdpDataSource;
import com.google.android.exoplayer2.util.Util;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link UdpDataSourceRtpDataChannel}. */
@RunWith(AndroidJUnit4.class)
public class UdpDataSourceRtpDataChannelTest {

  @Test
  public void getInterleavedBinaryDataListener_returnsNull() {
    UdpDataSourceRtpDataChannel udpDataSourceRtpDataChannel =
        new UdpDataSourceRtpDataChannel(UdpDataSource.DEFAULT_SOCKET_TIMEOUT_MILLIS);

    assertThat(udpDataSourceRtpDataChannel.getInterleavedBinaryDataListener()).isNull();
  }

  @Test
  public void sendRtcpPacket_withRemoteRtcpEndpoint_sendsFromRtcpChannel() throws Exception {
    UdpDataSourceRtpDataChannel rtpChannel =
        new UdpDataSourceRtpDataChannel(UdpDataSource.DEFAULT_SOCKET_TIMEOUT_MILLIS);
    UdpDataSourceRtpDataChannel rtcpChannel =
        new UdpDataSourceRtpDataChannel(UdpDataSource.DEFAULT_SOCKET_TIMEOUT_MILLIS);
    DatagramSocket receiver = new DatagramSocket(/* port= */ 0, InetAddress.getByName(null));
    byte[] packet = Util.getBytesFromHexString("81CE00020102030411223344");

    try {
      rtpChannel.open(RtpUtils.getIncomingRtpDataSpec(/* portNumber= */ 0));
      rtcpChannel.open(RtpUtils.getIncomingRtpDataSpec(/* portNumber= */ 0));
      rtpChannel.setRtcpChannel(rtcpChannel);
      rtpChannel.setRemoteRtcpEndpoint("127.0.0.1", receiver.getLocalPort());

      assertThat(rtpChannel.sendRtcpPacket(packet)).isTrue();

      byte[] received = new byte[packet.length];
      DatagramPacket receivedPacket = new DatagramPacket(received, received.length);
      receiver.receive(receivedPacket);
      assertThat(receivedPacket.getLength()).isEqualTo(packet.length);
      assertThat(received).isEqualTo(packet);
      assertThat(receivedPacket.getPort()).isEqualTo(rtcpChannel.getLocalPort());
    } finally {
      rtpChannel.close();
      receiver.close();
    }
  }
}
