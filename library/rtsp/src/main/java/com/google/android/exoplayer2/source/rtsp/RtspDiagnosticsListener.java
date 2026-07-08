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

/**
 * Listener for low-level RTSP/RTP diagnostics.
 *
 * <p>Callbacks are invoked on the playback thread for RTSP transport events and on the RTP loader
 * thread for packet/reordering events.
 */
public interface RtspDiagnosticsListener {

  /** Called when the transport for one RTP track is ready. */
  default void onTransportReady(
      int trackId, @RtspTransportMode.Mode int transportMode, String transport) {}

  /** Called when RTP transport falls back from UDP to TCP or when fallback is unavailable. */
  default void onTransportFallback(RtspTransportFallbackStats fallbackStats) {}

  /** Called when the first RTP packet for one track is dequeued for processing. */
  default void onFirstRtpPacketReceived(RtpPacketStats packetStats) {}

  /**
   * Called when the first complete H.264 IDR access unit with SPS/PPS available is assembled.
   *
   * <p>This is not a rendered-frame callback. It only means the RTP depacketizer has assembled a
   * decodable key access unit and submitted it to the extractor output.
   */
  default void onFirstDecodableVideoAccessUnitReady(RtspH264AccessUnitStats accessUnitStats) {}

  /** Called when an H.264 access unit is submitted by the RTP payload reader. */
  default void onH264AccessUnitReady(RtspH264AccessUnitReadyStats accessUnitStats) {}

  /**
   * Called when a sample is read from the RTSP {@code SampleQueue}.
   *
   * <p>This is a source queue read event, not a guaranteed MediaCodec input-buffer queued event.
   */
  default void onRtspSampleRead(RtspSampleReadStats sampleReadStats) {}

  /**
   * Called when an RTSP sample is handed to the downstream decoder input path.
   *
   * <p>This callback is emitted from the RTSP {@code SampleStream} read path. It is the closest RTSP
   * source-side handoff point before renderer/decoder consumption, but it is not emitted by
   * {@code MediaCodec}.
   */
  default void onRtspDecoderInputQueued(RtspDecoderInputQueuedStats decoderInputQueuedStats) {}

  /** Called when an H.264 access unit is detected as corrupted and is not submitted. */
  default void onH264AccessUnitCorrupted(RtspH264RecoveryStats recoveryStats) {}

  /** Called when H.264 low-latency recovery starts dropping frames until the next IDR. */
  default void onH264WaitForIdrStarted(RtspH264RecoveryStats recoveryStats) {}

  /** Called when a non-IDR H.264 access unit is dropped while waiting for an IDR. */
  default void onH264AccessUnitDroppedUntilIdr(RtspH264RecoveryStats recoveryStats) {}

  /** Called when waiting for an H.264 IDR exceeds the configured timeout. */
  default void onH264WaitForIdrTimedOut(RtspH264RecoveryStats recoveryStats) {}

  /** Called when H.264 low-latency recovery exits after a complete IDR access unit. */
  default void onH264WaitForIdrEnded(RtspH264RecoveryStats recoveryStats) {}

  /** Called when an RTP packet is parsed. */
  default void onRtpPacketReceived(RtpPacketStats packetStats) {}

  /** Called when an RTP packet is dequeued for payload parsing. */
  default void onRtpPacketDequeued(
      RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {}

  /** Called when an RTP packet is dropped before enqueueing. */
  default void onRtpPacketDropped(RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {}

  /** Called when the RTP reordering queue is reset. */
  default void onRtpReorderingQueueReset(RtpReorderingStats reorderingStats) {}

  /** Called when a low-latency RTSP backlog queue is flushed or reset. */
  default void onRtspBacklogQueueReset(RtspBacklogRecoveryStats backlogRecoveryStats) {}

  /** Called when an RTCP feedback request is blocked by policy. */
  default void onRtcpFeedbackThrottled(RtcpFeedbackRequest request) {}

  /** Called when an RTCP PLI packet is sent. */
  default void onRtcpPliSent(RtcpFeedbackRequest request) {}

  /** Called when an RTCP FIR packet is sent. */
  default void onRtcpFirSent(RtcpFeedbackRequest request) {}

  /** Called when an RTCP feedback request failed to send. */
  default void onRtcpFeedbackSendFailed(RtcpFeedbackRequest request, Exception error) {}
}
