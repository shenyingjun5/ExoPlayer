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

  /** Called when the first RTP packet for one track is dequeued for processing. */
  default void onFirstRtpPacketReceived(RtpPacketStats packetStats) {}

  /** Called when an RTP packet is parsed. */
  default void onRtpPacketReceived(RtpPacketStats packetStats) {}

  /** Called when an RTP packet is dequeued for payload parsing. */
  default void onRtpPacketDequeued(
      RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {}

  /** Called when an RTP packet is dropped before enqueueing. */
  default void onRtpPacketDropped(RtpPacketStats packetStats, RtpReorderingStats reorderingStats) {}

  /** Called when the RTP reordering queue is reset. */
  default void onRtpReorderingQueueReset(RtpReorderingStats reorderingStats) {}

  /** Called when an RTCP feedback request is blocked by policy. */
  default void onRtcpFeedbackThrottled(RtcpFeedbackRequest request) {}

  /** Called when an RTCP PLI packet is sent. */
  default void onRtcpPliSent(RtcpFeedbackRequest request) {}

  /** Called when an RTCP FIR packet is sent. */
  default void onRtcpFirSent(RtcpFeedbackRequest request) {}

  /** Called when an RTCP feedback request failed to send. */
  default void onRtcpFeedbackSendFailed(RtcpFeedbackRequest request, Exception error) {}
}
