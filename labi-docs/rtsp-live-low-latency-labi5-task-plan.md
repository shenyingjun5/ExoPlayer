# RTSP Live Low-Latency `2.19.1-labi.5` Task Plan

## 结论

`2.19.1-labi.5` 聚焦 Cast-SDK RTSP over TCP interleaved 主链路的接收端防花屏、真实延迟观测和低延迟恢复能力。默认行为必须保持与 `2.19.1-labi.4` 一致：未配置 diagnostics listener、feedback listener 或 low-latency policy 时，不自动请求 key frame，不维护高频逐帧状态，不改变官方 ExoPlayer 2.19.1 播放语义。

本任务包优先只改 `library/rtsp`。`MediaCodecVideoRenderer`、render callback、decoder input 时间戳映射如果必须触达 `library/core`，拆到后续 P1/P2，不混入 P0 RTSP 层 patch。

## 当前 fork API 基线

已发布 `2.19.1-labi.4` 具备：

- `RtspMediaSource.Factory#setRtspDiagnosticsListener(...)`
- `RtspMediaSource.Factory#setRtspPacketDiagnosticsEnabled(...)`
- `RtspMediaSource.Factory#setRtspFeedbackListener(...)`
- `RtspMediaSource.Factory#setRtcpFeedbackPolicy(...)`
- `RtcpFeedbackPolicy.DEFAULT`：默认 passive，不自动发送 RTCP feedback。
- `RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT`：开启 PLI/FIR、sequence gap request、queue reset request，默认 400ms 节流。
- `RtspDiagnosticsListener`：transport、first RTP、first decodable H.264 IDR AU、RTP packet/reorder queue、RTCP PLI/FIR lifecycle。
- `RtspFeedbackListener`：requested/throttled/sent/failed。

主要缺口：

- H.264 timestamp change without marker 只重置 reader state，没有显式 corrupted AU 语义。
- corrupted AU、sequence gap、queue reset 后没有 WAIT_IDR 状态机。
- WAIT_IDR 下没有丢弃非 IDR 直到完整 IDR 的恢复策略。
- AU corrupted / WAIT_IDR enter 没有独立 RTCP feedback reason。
- queue 指标缺 queue age / `rtpQueueMs`。
- render 时间和 decoder input 时间不在 RTSP 层可得。

## P0 实施顺序

1. H.264 AU 完整性守卫。
   - 单 NAL 正常输出。
   - FU-A middle/end missing 时坏 AU 不进入 `SampleQueue`。
   - timestamp 变化但前一 AU 未收到 marker 时，前 AU 标记 corrupted。
   - corrupted IDR / corrupted P 都不提交 `sampleMetadata`。
   - TCP/UDP 共用 `RtpH264Reader`，因此该守卫同时覆盖两种传输。

2. WAIT_IDR 状态机。
   - 状态：`NORMAL` / `WAIT_IDR`。
   - corrupted AU、sequence gap、queue reset 后进入 WAIT_IDR。
   - WAIT_IDR 下丢弃非 IDR AU。
   - 完整 IDR AU 到达后退出 WAIT_IDR，并允许该 IDR 提交给 `SampleQueue`。
   - 本阶段不做 decoder flush；若真机证明 decoder 参考链仍不能恢复，再评估 `library/core` 风险。

3. RTCP PLI/FIR 触发加强。
   - 为 AU corrupted / WAIT_IDR enter 增加 feedback reason。
   - 复用已有 `requestKeyFrame(...)` 和 `RtcpFeedbackPolicy` 节流。
   - 保持默认 `RtcpFeedbackPolicy.DEFAULT` passive，不改变现有业务。

4. AU/queue diagnostics。
   - 增加 corrupted AU、WAIT_IDR enter/exit、dropped-until-IDR 计数回调。
   - 增加 access unit ready 事件，包含 trackId、RTP timestamp、`sampleTimeUs`、是否 IDR、assembled time。
   - queue age / `rtpQueueMs` 放入 P0 后半段，必须保证 listener 为空时不做高频对象分配。

5. 精确端到端延迟 P0 最小闭环。
   - RTSP/H.264 层输出 `rtpTimestamp -> sampleTimeUs`。`sampleTimeUs` 由 `RtpReaderUtils.toSampleTimeUs(...)` 计算，已有 32-bit RTP timestamp wraparound 处理。
   - `RtspDiagnosticsListener.onH264AccessUnitReady(...)` 输出 `rtpSequenceNumber`、`rtpTimestamp`、`sampleTimeUs`、`isIdr`、`assembledElapsedRealtimeMs`。
   - `RtspDiagnosticsListener.onRtspSampleRead(...)` 输出 RTSP `SampleQueue` read 事件和 source queue ahead 口径：`sampleQueueBufferedAheadMs`、`mediaPeriodBufferedAheadMs`。
   - render 侧 P0 不改 `library/core`：Cast-SDK 后续使用 ExoPlayer 现有 `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered(presentationTimeUs, releaseTimeNs, ...)`，按 `presentationTimeUs == sampleTimeUs` 反查 RTP timestamp。
   - `onRtspSampleRead(...)` 是 source queue read 事件，不命名为 `onDecoderInput`，避免误导为 MediaCodec input-buffer queued。

## P1/P2 边界

P1：

- first-decodable timeout 的接线点。
- TCP 延迟积累证据：latest RTP timestamp 滞后、queue age 持续增长、bufferedPosition-currentPosition 建议阈值。
- Media3 1.2.x TCP fallback race/hang focused diff pass。
- 如果 Cast-SDK 集成后证明 `VideoFrameMetadataListener` 的 `presentationTimeUs` 无法稳定关联 render，再单独评估 `library/core` renderer hook。

P2：

- 真正的 `onDecoderInput(...)` 和 fork 内 `onVideoFrameRendered(...)`。这需要 renderer/core 层映射，不能混入 RTSP 层 P0；当前 P0 用 `sampleTimeUs` + 现有 `VideoFrameMetadataListener` 完成端到端 join。
- UDP AUTO fallback 与 5s loss/reorder 滑窗。Cast-SDK 当前默认仍是 FORCE_TCP。

## Cast-SDK 后续接入字段

Cast-SDK 可以用下列事件建立真实窗口统计，暂不需要伪造 `capture_to_render_ms`：

1. 发送端：`capture_wall_ms -> rtpTimestamp`，由 Cast-SDK 发送端诊断提供。
2. ExoPlayer fork RTP：`onRtpPacketReceived(RtpPacketStats)`，字段包括 `trackId`、`sequenceNumber`、`rtpTimestamp`、`arrivalElapsedRealtimeMs`。
3. ExoPlayer fork AU：`onH264AccessUnitReady(RtspH264AccessUnitReadyStats)`，字段包括 `trackId`、`rtpSequenceNumber`、`rtpTimestamp`、`sampleTimeUs`、`assembledElapsedRealtimeMs`。
4. ExoPlayer fork source queue：`onRtspSampleRead(RtspSampleReadStats)`，字段包括 `trackId`、`sampleQueueIndex`、`sampleTimeUs`、`readElapsedRealtimeMs`、`sampleQueueBufferedAheadMs`、`mediaPeriodBufferedAheadMs`。
5. ExoPlayer render：Cast-SDK 调用现有 `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered(presentationTimeUs, releaseTimeNs, ...)`，用 `presentationTimeUs` join `sampleTimeUs`，再 join RTP timestamp 和发送端 capture wall time。

示例事件序列：

```text
sender: capture_wall_ms=100000, rtpTimestamp=4294967040
receiver: onRtpPacketReceived(trackId=0, sequence=10, rtpTimestamp=4294967040, arrivalElapsedRealtimeMs=100120)
receiver: onH264AccessUnitReady(trackId=0, rtpTimestamp=4294967040, sampleTimeUs=0, assembledElapsedRealtimeMs=100125)
receiver: onRtspSampleRead(trackId=0, sampleTimeUs=0, readElapsedRealtimeMs=100140, sampleQueueBufferedAheadMs=34)
receiver: onVideoFrameAboutToBeRendered(presentationTimeUs=0, releaseTimeNs=...)
Cast-SDK: capture_to_render_ms = render_elapsed_ms - capture_wall_ms_aligned_to_elapsed_clock
```

注意：`sampleQueueBufferedAheadMs` / `mediaPeriodBufferedAheadMs` 是 RTSP source queue 口径，不等同于播放器最终 `exoBufferedDurationMs = player.bufferedPosition - player.currentPosition`。后者仍应由 Cast-SDK 在 player 侧读取。

## 测试要求

目标单测：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.reader.RtpH264ReaderTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest
```

完整 RTSP 单测：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest
```

发布前还需：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease
```

## 发布说明草稿

建议版本：

```text
com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.5
```

草稿：

- H.264 RTP depacketizer: drop corrupted access units more defensively when FU-A fragments are missing or timestamp changes before marker.
- RTSP low-latency recovery: add WAIT_IDR recovery state to drop non-IDR access units after corruption until a complete IDR arrives.
- RTCP feedback: add AU-corruption/WAIT_IDR key-frame request reasons while preserving passive defaults.
- Diagnostics: expose WAIT_IDR, corrupted-AU counters, H.264 AU assembled time, and RTP reorder queue age/span for Cast-SDK latency and recovery debug surfaces.
