# RTSP Feedback Final Integration Plan

## 背景

本方案用于收敛 ExoPlayer fork 与 Cast-SDK Android 接收端之间的 RTSP low-latency feedback 集成边界。

当前 fork 已经具备 RTSP/RTP/RTCP diagnostics、RTCP PLI/FIR 发送、RTP sequence gap / queue reset 触发 key-frame request，以及 H.264 `firstDecodableVideoAccessUnitReady` 低延迟 loading 兜底事件。Code review 后确认：能力方向满足 Cast-SDK 需求，但默认策略和高频 diagnostics 开关需要进一步收紧，避免影响 ExoPlayer 2.19.1 原有默认行为和性能。

## Diagnostics 定位

Diagnostics 分为两类：

| 类型 | 定位 | 生产默认 | 典型用途 |
| --- | --- | --- | --- |
| 低频 diagnostics | Cast-SDK 可使用的业务辅助信号 | 开启，前提是 Cast-SDK 显式设置 listener | loading 兜底、RTCP 反馈结果、最近 RTSP 会话状态 |
| 高频 packet diagnostics | Debug / 诊断 / 问题回溯信号 | 关闭 | RTP packet、reorder queue、drop、duplicate、gap、queue depth 细节 |

低频 diagnostics 包括：

- `onTransportReady`
- `onFirstRtpPacketReceived`
- `onFirstDecodableVideoAccessUnitReady`
- `onRtcpFeedbackThrottled`
- `onRtcpPliSent`
- `onRtcpFirSent`
- `onRtcpFeedbackSendFailed`

高频 packet diagnostics 包括：

- `onRtpPacketReceived`
- `onRtpPacketDequeued`
- `onRtpPacketDropped`
- 高频 reorder queue stats 回调

结论：

- `RtspDiagnosticsListener` 不是所有业务都必须依赖的主控制面。
- Cast-SDK 生产链路需要低频 diagnostics，主要用于 loading 兜底和 RTCP feedback 结果记录。
- 高频 packet diagnostics 应仅在 debug 页面、问题上报或专项诊断中显式开启，避免每包对象分配和高频回调压力。

## 默认行为原则

ExoPlayer fork 必须保持官方 ExoPlayer 2.19.1 默认行为：

- 不设置 Cast-SDK listener 时，不触发 callbacks。
- 不设置 low-latency policy 时，不自动发送 RTCP PLI/FIR。
- 默认不因 RTP sequence gap 自动请求 key frame。
- 默认不因 RTP queue reset 自动请求 key frame。
- 默认不创建 per-packet diagnostics stats 对象。

## RTCP Feedback Policy

推荐将 policy 拆成两个明确层级。

### `RtcpFeedbackPolicy.DEFAULT`

默认 policy 必须是被动模式：

- `pliEnabled=false`
- `firEnabled=false`
- `sequenceGapRequestThreshold=0`
- queue reset 不自动请求 key frame
- 保留 `minRequestIntervalMs` 字段，但默认不会触发发送

该模式用于保持原 ExoPlayer 行为。

### Cast-SDK Low-Latency Policy

新增或推荐一个显式 low-latency preset，例如：

```java
RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT
```

或：

```java
new RtcpFeedbackPolicy.Builder()
    .setLowLatencyDefaults()
    .build()
```

建议语义：

- `pliEnabled=true`
- `firEnabled=true`
- `minRequestIntervalMs=400`
- `sequenceGapRequestThreshold=8`
- queue reset 自动请求 key frame

Cast-SDK 必须显式设置该 policy 后，才启用自动弱网恢复。

## Diagnostics 开关方案

需要新增一个独立开关，避免设置 `RtspDiagnosticsListener` 后自动打开所有高频 packet callbacks。

推荐方案：

```java
new RtspMediaSource.Factory()
    .setRtspDiagnosticsListener(listener)
    .setRtcpFeedbackPolicy(RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT)
    .setRtspPacketDiagnosticsEnabled(false);
```

默认值：

- `setRtspPacketDiagnosticsEnabled(false)`

生产建议：

- 设置 `RtspDiagnosticsListener`，只消费低频事件。
- 设置 low-latency RTCP policy。
- 不开启 packet diagnostics。

Debug 建议：

- 在 debug 页面或问题上报开关打开时，设置 `setRtspPacketDiagnosticsEnabled(true)`。
- 记录最近 N 条 packet/reorder stats，避免无限增长。

## H.264 Loading 兜底事件

API：

```java
RtspDiagnosticsListener.onFirstDecodableVideoAccessUnitReady(RtspH264AccessUnitStats stats)
```

触发语义：

- 已组出完整 H.264 access unit。
- access unit 包含 IDR/keyframe。
- SPS/PPS 已可用。
- 每个 H.264 track 只触发一次。
- 事件在 access unit 提交到 extractor output 后发出。

语义边界：

- 这不是 rendered frame。
- 不代表 Surface 已经显示画面。
- 只能作为自家 RTSP live 的 low-latency loading 兜底信号。

Cast-SDK loading 退出优先级：

1. `renderedFirstFrame`
2. `firstDecodableVideoAccessUnitReady`
3. `videoSize` / `PLAYING` 高频启动期 poll

## `requestKeyFrame` 线程语义

Cast-SDK 需要能在以下场景主动请求 IDR：

- 自身启动期首帧超时。
- UI 或 session 恢复时需要重新同步。
- Debug 或业务策略判断当前画面明显落后。

ExoPlayer fork 应提供线程安全的请求入口：

```java
RtspMediaSource.requestKeyFrame(@RtcpFeedbackReason.Reason int reason)
```

要求：

- 允许 Cast-SDK 从任意线程调用。
- 内部串行化到 ExoPlayer 播放线程或 RTSP 控制线程。
- 返回值只表示请求是否被接受或是否存在可请求的 active period。
- 实际 sent / throttled / failed 结果通过 listener 回调确认。

## Cast-SDK 接入要求

Cast-SDK 生产链路应显式启用：

- patched ExoPlayer RTSP artifact。
- `RtspDiagnosticsListener` 低频事件。
- `RtcpFeedbackPolicy.LOW_LATENCY_DEFAULT` 或等价 builder policy。
- `setRtspPacketDiagnosticsEnabled(false)`。
- `requestKeyFrame(RtcpFeedbackReason.APPLICATION)` reflection 入口。

Cast-SDK debug 链路可额外启用：

- `setRtspPacketDiagnosticsEnabled(true)`。
- 最近 RTSP session diagnostics 快照。
- 最近 N 条 RTP packet/reorder stats。
- RTCP feedback sent/throttled/failed 事件列表。

Cast-SDK 不应使用 first RTP 直接清 loading。first RTP 只能证明网络收到了 RTP packet，不能证明 access unit 完整，也不能证明可解码或已渲染。

## ExoPlayer Fork 待改造项

| ID | 任务 | 原因 |
| --- | --- | --- |
| FIP-1 | 将 `RtcpFeedbackPolicy.DEFAULT` 改成被动策略 | 保持 ExoPlayer 2.19.1 默认行为 |
| FIP-2 | 新增 low-latency policy preset 或 builder helper | 给 Cast-SDK 显式启用弱网恢复 |
| FIP-3 | 新增 packet diagnostics 独立开关 | listener 不应自动带来 per-packet 分配和回调压力 |
| FIP-4 | 收紧 H.264 in-band SPS/PPS 完整性判断 | 满足 first decodable event 的严格触发条件 |
| FIP-5 | 收紧 `requestKeyFrame` 线程语义 | 支持 Cast-SDK 通过 reflection 从任意线程调用 |
| FIP-6 | 修正 TCP interleaved sent/failed 诊断顺序 | 避免异步写失败时出现先 sent 后 failed |

## 必补测试

- 默认 `RtspMediaSource.Factory` 不会自动发送 RTCP PLI/FIR。
- 默认 policy 下 sequence gap 不触发 key-frame request。
- low-latency policy 下 sequence gap / queue reset 会触发 key-frame request，并受限流约束。
- 设置 diagnostics listener 但未启用 packet diagnostics 时，不触发 per-packet callbacks。
- 启用 packet diagnostics 后，packet/reorder callbacks 正常触发。
- `firstDecodableVideoAccessUnitReady` 在 packet diagnostics 关闭时仍能触发。
- 分片 SPS/PPS 丢包时，不误触发 first decodable access unit event。
- `requestKeyFrame` 非播放线程调用不会并发访问 active media periods。

## 双方文档同步状态

- ExoPlayer fork 文档：本文。
- Cast-SDK 文档：`/Users/shenyingjun/Work/Cast-SDK/docs/plans/2026-07-03-receiver-exoplayer-rtsp-feedback-fork-plan.md`
- Cast-SDK 会话：`019ef522-6dc6-77e0-a3c1-26792035ed71`

同步结论：

- Cast-SDK 接受 ExoPlayer fork 的 RTSP feedback / diagnostics / H.264 first decodable access unit 方向。
- Cast-SDK 要求 `RtcpFeedbackPolicy.DEFAULT` 保持 passive，避免普通 ExoPlayer RTSP 默认路径自动发 PLI/FIR。
- Cast-SDK 自家 RTSP live 会显式启用 low-latency policy。
- Cast-SDK 生产链路启用低频 diagnostics，不默认启用 per-packet diagnostics。
- Cast-SDK debug 或问题上报链路才按 session 显式开启 packet diagnostics，并需要限时或环形缓冲保护。
- Cast-SDK loading 优先级固定为 `renderedFirstFrame` > `firstDecodableVideoAccessUnitReady` > `videoSize/PLAYING` poll。
- Cast-SDK 需要通过 reflection 接入 policy/listener/requestKeyFrame，并在不支持新 API 的 artifact 上安全降级。
