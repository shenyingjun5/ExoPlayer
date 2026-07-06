# RTSP Live Latency `2.19.1-labi.6` Result

## Scope

`2.19.1-labi.6` tightens the RTSP/H.264 diagnostics join path and the low-latency
`WAIT_IDR` recovery guard used by Cast-SDK receiver live playback.

It preserves the `2.19.1-labi.5` default behavior:

- No diagnostics listener means no diagnostics callbacks.
- `setRtspPacketDiagnosticsEnabled(false)` keeps high-frequency packet/sample/decoder-handoff
  diagnostics disabled.
- `RtcpFeedbackPolicy.DEFAULT` remains passive and does not automatically request key frames.

## End-to-End Join Events

Stable join keys:

1. Primary key: `sampleTimeUs`.
2. Secondary key: `rtpTimestamp`.

Recommended Cast-SDK join flow:

1. Sender reports `capture_wall_ms -> rtpTimestamp`.
2. Exo RTSP reports `onH264AccessUnitReady(...)` with:
   - `rtpTimestamp`
   - `sampleTimeUs`
   - `assembledElapsedRealtimeMs`
3. Exo RTSP reports `onRtspSampleRead(...)` with:
   - `sampleTimeUs`
   - `rtpTimestamp`
   - `readElapsedRealtimeMs`
   - `sampleQueueBufferedAheadMs`
   - `mediaPeriodBufferedAheadMs`
4. Exo RTSP reports `onRtspDecoderInputQueued(...)` with:
   - `sampleTimeUs`
   - `rtpTimestamp`
   - `queuedElapsedRealtimeMs`
5. Cast-SDK keeps using ExoPlayer `VideoFrameMetadataListener` for render:
   - `presentationTimeUs`
   - `releaseTimeNs`

`onRtspDecoderInputQueued(...)` is emitted from the RTSP `SampleStream.readData(...)` path when
the sample is handed to the downstream renderer input path. It is the closest RTSP source-side
handoff point, but it is not emitted by `MediaCodec` itself.

The fork does not add a render metadata event inside RTSP because render metadata is owned by
renderer/core. Cast-SDK should continue to join render with `VideoFrameMetadataListener` using
`presentationTimeUs == sampleTimeUs`.

## Backlog Metric Semantics

`rtpQueueMs`:

- Represents RTP reorder/depacketizer queue span.
- It reflects backlog before H.264 access-unit assembly.
- It can stay `0` on healthy TCP interleaved live playback because packets are dequeued quickly.

`sampleQueueBufferedAheadMs`:

- Represents the selected RTSP `SampleQueue` buffered position minus the sample being read.
- It is a source queue backlog metric for one queue.
- It should become non-zero if RTSP samples accumulate ahead of renderer reads.

`mediaPeriodBufferedAheadMs`:

- Represents `RtspMediaPeriod.getBufferedPositionUs()` minus the sample being read.
- It is a media-period source buffering metric across selected RTSP queues.
- It is not identical to Cast-SDK's player-level `exoBufferedDurationMs`.

`exoBufferedDurationMs` recommendation:

- Cast-SDK should continue reading it from player state, for example
  `player.bufferedPosition - player.currentPosition`.
- Use Exo RTSP source metrics to explain where backlog is introduced, not as a direct replacement
  for player-level buffered duration.

Weak-network / artificial backlog expectation:

- RTP packet reordering or stalled depacketization should raise RTP reorder stats.
- Source queue accumulation should raise `sampleQueueBufferedAheadMs` and/or
  `mediaPeriodBufferedAheadMs`.
- Decoder/render delay may not raise RTSP source metrics; it should be detected from
  `onRtspDecoderInputQueued(...)` to `VideoFrameMetadataListener` deltas.

## IDR Request / Rebuild Recommendations

Cast-SDK should request IDR when:

- `onH264WaitForIdrStarted(...)` fires.
- RTP sequence gap or queue reset grows under `RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT`.
- Startup first-decodable/render timeout fires while RTP is flowing.
- Decoder/render join delay grows but RTSP session is still healthy.

Cast-SDK should rebuild the RTSP session when:

- Repeated IDR requests are throttled or sent but no decodable IDR arrives.
- `WAIT_IDR` lasts beyond the receiver policy timeout.
- RTP/sample source metrics stay healthy but render does not progress for the configured timeout.
- Sender/receiver clock-sync state cannot produce valid capture-to-render join for the configured
  startup window.

The ExoPlayer fork does not automatically rebuild RTSP sessions. Rebuild is an application policy
because it requires player/session lifecycle decisions outside `library/rtsp`.

## `WAIT_IDR` Recovery Tightening

`2.19.1-labi.6` keeps the `2.19.1-labi.5` recovery state machine and tightens it:

- H.264 depacketization failures enter `WAIT_IDR` when low-latency recovery is enabled.
- `WAIT_IDR` drops non-IDR access units.
- `WAIT_IDR` does not recover on an IDR unless SPS/PPS are already available or are present in the
  same submitted access unit.
- Once a complete decodable IDR is submitted, `WAIT_IDR` exits and recovery diagnostics are emitted.

This avoids feeding old or non-decodable H.264 access units into downstream decoder state after RTP
corruption.
