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

import static com.google.android.exoplayer2.testutil.TestUtil.buildTestData;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.common.primitives.Bytes;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TransferRtpDataChannel}. */
@RunWith(AndroidJUnit4.class)
public class TransferRtpDataChannelTest {

  private static final long POLL_TIMEOUT_MS = 8000;

  @Test
  public void getInterleavedBinaryDataListener_returnsAnInterleavedBinaryDataListener() {
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);

    assertThat(transferRtpDataChannel.getInterleavedBinaryDataListener())
        .isEqualTo(transferRtpDataChannel);
  }

  @Test
  public void read_withoutReceivingInterleavedData_returnsEndOfInput() {
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    byte[] buffer = new byte[1];

    assertThat(transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length))
        .isEqualTo(C.RESULT_END_OF_INPUT);
  }

  @Test
  public void read_withLargeEnoughBuffer_reads() {
    byte[] randomBytes = buildTestData(20);
    byte[] buffer = new byte[40];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes);

    transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length);

    assertThat(Arrays.copyOfRange(buffer, /* from= */ 0, /* to= */ 20)).isEqualTo(randomBytes);
  }

  @Test
  public void backlogRecoveryDisabled_doesNotFlushQueuedPackets() {
    byte[] bytes1 = buildTestData(4);
    byte[] bytes2 = buildTestData(4);
    byte[] buffer = new byte[8];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);

    transferRtpDataChannel.onInterleavedBinaryDataReceived(bytes1);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(bytes2);

    assertThat(transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length)).isEqualTo(4);
    assertThat(Arrays.copyOfRange(buffer, /* from= */ 0, /* to= */ 4)).isEqualTo(bytes1);
    assertThat(transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length)).isEqualTo(4);
    assertThat(Arrays.copyOfRange(buffer, /* from= */ 0, /* to= */ 4)).isEqualTo(bytes2);
    assertThat(transferRtpDataChannel.getAndClearPendingDiscontinuityReason())
        .isEqualTo(RtcpFeedbackReason.UNKNOWN);
  }

  @Test
  public void backlogRecoveryEnabled_flushesWholeQueueAndSetsQueueReset() {
    CapturingDiagnosticsListener diagnosticsListener = new CapturingDiagnosticsListener();
    TransferRtpDataChannel transferRtpDataChannel =
        new TransferRtpDataChannel(
            /* trackId= */ 3,
            /* pollTimeoutMs= */ 0,
            diagnosticsListener,
            new RtspBacklogRecoveryPolicy.Builder()
                .setEnabled(true)
                .setTcpInterleavedBacklogResetPackets(2)
                .build());
    byte[] buffer = new byte[8];

    transferRtpDataChannel.onInterleavedBinaryDataReceived(buildTestData(4));
    transferRtpDataChannel.onInterleavedBinaryDataReceived(buildTestData(4));

    assertThat(transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length))
        .isEqualTo(C.RESULT_END_OF_INPUT);
    assertThat(transferRtpDataChannel.getAndClearPendingDiscontinuityReason())
        .isEqualTo(RtcpFeedbackReason.QUEUE_RESET);
    assertThat(diagnosticsListener.backlogResetCount).isEqualTo(1);
    assertThat(diagnosticsListener.lastStats.trackId).isEqualTo(3);
    assertThat(diagnosticsListener.lastStats.transportMode)
        .isEqualTo(RtspTransportMode.TCP_INTERLEAVED);
    assertThat(diagnosticsListener.lastStats.droppedPacketCount).isEqualTo(2);
  }

  @Test
  public void read_withSmallBufferEnoughBuffer_readsThreeTimes() {
    byte[] randomBytes = buildTestData(20);
    byte[] buffer = new byte[8];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes);

    transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length);
    assertThat(buffer).isEqualTo(Arrays.copyOfRange(randomBytes, /* from= */ 0, /* to= */ 8));
    transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length);
    assertThat(buffer).isEqualTo(Arrays.copyOfRange(randomBytes, /* from= */ 8, /* to= */ 16));
    transferRtpDataChannel.read(buffer, /* offset= */ 0, /* length= */ 4);
    assertThat(Arrays.copyOfRange(buffer, /* from= */ 0, /* to= */ 4))
        .isEqualTo(Arrays.copyOfRange(randomBytes, /* from= */ 16, /* to= */ 20));
  }

  @Test
  public void read_withSmallBuffer_reads() {
    byte[] randomBytes = buildTestData(40);
    byte[] buffer = new byte[20];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes);

    transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length);
    assertThat(buffer).isEqualTo(Arrays.copyOfRange(randomBytes, /* from= */ 0, /* to= */ 20));

    transferRtpDataChannel.read(buffer, /* offset= */ 0, buffer.length);
    assertThat(buffer).isEqualTo(Arrays.copyOfRange(randomBytes, /* from= */ 20, /* to= */ 40));
  }

  @Test
  public void read_withSmallAndModerateBufferAndSubsequentProducerWrite_reads() {
    byte[] randomBytes1 = buildTestData(40);
    byte[] randomBytes2 = buildTestData(40);
    byte[] smallBuffer = new byte[20];
    byte[] bigBuffer = new byte[40];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes1);

    transferRtpDataChannel.read(smallBuffer, /* offset= */ 0, smallBuffer.length);
    assertThat(smallBuffer)
        .isEqualTo(Arrays.copyOfRange(randomBytes1, /* from= */ 0, /* to= */ 20));

    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes2);

    // Read the remaining 20 bytes in randomBytes1, and 20 bytes from randomBytes2.
    transferRtpDataChannel.read(bigBuffer, /* offset= */ 0, bigBuffer.length);
    assertThat(bigBuffer)
        .isEqualTo(
            Bytes.concat(
                Arrays.copyOfRange(randomBytes1, /* from= */ 20, /* to= */ 40),
                Arrays.copyOfRange(randomBytes2, /* from= */ 0, /* to= */ 20)));

    // Read the remaining 20 bytes in randomBytes2.
    transferRtpDataChannel.read(smallBuffer, /* offset= */ 0, smallBuffer.length);
    assertThat(smallBuffer)
        .isEqualTo(Arrays.copyOfRange(randomBytes2, /* from= */ 20, /* to= */ 40));
  }

  @Test
  public void read_withSmallAndBigBufferWithPartialReadAndSubsequentProducerWrite_reads() {
    byte[] randomBytes1 = buildTestData(40);
    byte[] randomBytes2 = buildTestData(40);
    byte[] smallBuffer = new byte[30];
    byte[] bigBuffer = new byte[30];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes1);

    transferRtpDataChannel.read(smallBuffer, /* offset= */ 0, smallBuffer.length);
    assertThat(smallBuffer)
        .isEqualTo(Arrays.copyOfRange(randomBytes1, /* from= */ 0, /* to= */ 30));

    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes2);

    // Read 30 bytes to big buffer.
    transferRtpDataChannel.read(bigBuffer, /* offset= */ 0, bigBuffer.length);
    assertThat(bigBuffer)
        .isEqualTo(
            Bytes.concat(
                Arrays.copyOfRange(randomBytes1, /* from= */ 30, /* to= */ 40),
                Arrays.copyOfRange(randomBytes1, /* from= */ 0, /* to= */ 20)));

    // Read the remaining 20 bytes to big buffer.
    transferRtpDataChannel.read(bigBuffer, /* offset= */ 0, /* length= */ 20);
    assertThat(Arrays.copyOfRange(bigBuffer, /* from= */ 0, /* to= */ 20))
        .isEqualTo(Arrays.copyOfRange(randomBytes2, /* from= */ 20, /* to= */ 40));
  }

  @Test
  public void read_withSmallAndBigBufferAndSubsequentProducerWrite_reads() {
    byte[] randomBytes1 = buildTestData(40);
    byte[] randomBytes2 = buildTestData(40);
    byte[] smallBuffer = new byte[20];
    byte[] bigBuffer = new byte[70];
    TransferRtpDataChannel transferRtpDataChannel = new TransferRtpDataChannel(POLL_TIMEOUT_MS);
    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes1);

    transferRtpDataChannel.read(smallBuffer, /* offset= */ 0, smallBuffer.length);
    assertThat(smallBuffer)
        .isEqualTo(Arrays.copyOfRange(randomBytes1, /* from= */ 0, /* to= */ 20));

    transferRtpDataChannel.onInterleavedBinaryDataReceived(randomBytes2);

    transferRtpDataChannel.read(bigBuffer, /* offset= */ 0, bigBuffer.length);
    assertThat(Arrays.copyOfRange(bigBuffer, /* from= */ 0, /* to= */ 60))
        .isEqualTo(
            Bytes.concat(
                Arrays.copyOfRange(randomBytes1, /* from= */ 20, /* to= */ 40), randomBytes2));
  }

  private static final class CapturingDiagnosticsListener implements RtspDiagnosticsListener {
    public int backlogResetCount;
    public RtspBacklogRecoveryStats lastStats;

    @Override
    public void onRtspBacklogQueueReset(RtspBacklogRecoveryStats backlogRecoveryStats) {
      backlogResetCount++;
      lastStats = backlogRecoveryStats;
    }
  }
}
