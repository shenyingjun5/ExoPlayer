# RTSP Feedback Enhancement Plan

## 背景

Cast-SDK Android 接收端需要在 Android 4.4+ 设备上播放自家发送端的 RTSP live 流。发送端已经支持 TCP interleaved / UDP RTP、RTCP PLI/FIR 解析、收到 PLI/FIR 后强制 IDR 和低延迟/平滑模式。

当前官方 ExoPlayer 2.19.1 的 RTSP 高层 API 没有暴露 RTCP feedback 发送、RTP 队列状态、sequence gap、jitter、backlog 或 drop-until-idr 控制点。AndroidX Media3 仍在持续维护 ExoPlayer 代码，但当前 Media3 release `minSdkVersion=23`，不能直接作为 Android 4.4 主链路。

因此本 fork 基于 ExoPlayer 2.19.1 增强 RTSP 模块，并选择性 backport Media3 中与 RTSP live 相关的修复。

## 目标

- 保持 Android 4.4+ 支持。
- 暴露 RTSP/RTP/RTCP diagnostics。
- 支持接收端通过 RTCP PLI/FIR 请求发送端 IDR。
- 支持低延迟模式下识别落后、丢包、乱序和恢复状态。
- 吸收 Media3 中对 RTSP live 有价值的修复。
- 产出 Cast-SDK 可依赖的 patched artifact。

## 不做范围

- 不迁移完整 Media3。
- 不引入 Media3 UI/Session/Transformer/IMA/Cast/Inspector/Compose。
- 不自研完整播放器 pipeline。
- 不改变 ExoPlayer 2.19.1 对 Android 4.4 的兼容基线。
- 不发布和官方 Google 坐标混淆的 artifact。

## 关键源码区域

优先关注：

- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaSource.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriod.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspClient.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMessageChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpDataLoadable.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpExtractor.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpPacketReorderingQueue.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/TransferRtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannel.java`

必要时评估：

- `library/core/src/main/java/com/google/android/exoplayer2/video/MediaCodecVideoRenderer.java`
- `library/core/src/main/java/com/google/android/exoplayer2/mediacodec/MediaCodecRenderer.java`
- `library/core/src/main/java/com/google/android/exoplayer2/DefaultLoadControl.java`

## 增强设计

## 当前实施状态

截至 2026-07-03，当前分支已包含两个本地提交：

- `dd3af995c7 feat(rtsp): backport Media3 live fixes`
- `93e0c32c7a feat(rtsp): send RTCP feedback requests`

已完成：

- RTSP diagnostics/feedback API 和配置透传。
- RTP packet/reorder queue diagnostics。
- H.264 首个可解码 IDR access unit 诊断事件。
- TCP interleaved 和 UDP RTCP feedback 发送。
- RTCP PLI/FIR packet builder。
- `RtspMediaPeriod.requestKeyFrame(reason)` 到各 RTP track 的内部请求链路。
- RTP sequence gap 和 queue reset 触发 key-frame request。
- Media3 P0/P1 RTSP 小范围 backport。
- `:library-rtsp:testDebugUnitTest`、目标 RTCP 单测和 `:library-rtsp:assembleRelease` 验证通过。

仍未完成：

- 首帧超时、decoder recover 或明显落后时的自动 I 帧请求策略。
- RTSP setup / keepalive / TCP fallback / 302 等剩余 P1 互操作回迁。
- Cast-SDK artifact 拉取验证。
- Cast-SDK 仓库接入 patched artifact 与真机验证。

### Public API

在 `RtspMediaSource.Factory` 增加可选配置：

- `setRtspFeedbackListener(...)`
- `setRtspDiagnosticsListener(...)`
- `setRtcpFeedbackPolicy(...)`

API 必须保持可选，不影响现有官方用法。

### RTCP Feedback

需要支持：

- TCP interleaved RTCP 发送。
- UDP RTCP 发送。
- PLI builder。
- FIR builder。
- `requestKeyFrame(reason)` 内部调用链。
- IDR 请求限流事件透出。

### RTP Diagnostics

需要暴露：

- first RTP packet time。
- first decodable H.264 IDR access unit ready time。
- RTP timestamp。
- sequence number。
- arrival time。
- reorder queue depth。
- sequence gap。
- late/drop/reset 计数。
- timestamp wraparound。
- transport mode: TCP interleaved / UDP。

### H.264 Low-Latency Loading Diagnostic

API：

- `RtspDiagnosticsListener.onFirstDecodableVideoAccessUnitReady(RtspH264AccessUnitStats)`

触发条件：

- RTP/H.264 depacketize 链路已经组出完整 access unit，不是单个 RTP packet 到达。
- access unit 包含 IDR NAL，`accessUnitType=IDR`。
- 已有 SPS/PPS，可配置解码器。
- 每个 H.264 track 只上报首个满足条件的 access unit。

诊断字段：

- `trackId`
- `rtpSequenceNumber`
- `rtpTimestamp`
- `hasSps`
- `hasPps`
- `nalUnitType`
- `accessUnitType`
- `firstRtpPacketElapsedRealtimeMs`

语义边界：

- 该事件不是 rendered frame callback，不代表画面已经进入 Surface。
- 该事件只表示 RTP depacketizer 已组出首个具备解码条件的 H.264 IDR access unit，并已经提交给 extractor output。
- Cast-SDK 接收端 loading 退出优先级应为：`renderedFirstFrame`，其次 `onFirstDecodableVideoAccessUnitReady`，最后 `videoSize` / `PLAYING` poll 兜底。
- 默认无 listener 时不改变 ExoPlayer 2.19.1 播放行为。

### 低延迟恢复

目标能力：

- 首帧超时请求 I 帧：未完成。
- sequence gap 持续出现时请求 I 帧：已完成，基于 `RtcpFeedbackPolicy.sequenceGapRequestThreshold`。
- queue reset 请求 I 帧：已完成。
- decoder recover 或明显落后时请求 I 帧：未完成，需要 Cast-SDK 真机证据或 renderer 层证据后再评估。
- 诊断事件可被 Cast-SDK 接收端 debug state 输出。

## Media3 Backport 策略

Media3 是修复来源，不是直接替代品。

优先 backport：

| 优先级 | 范围 | 判断 |
| --- | --- | --- |
| P0 | RTSP/RTP/H.264/H.265 bugfix | 必须评估并优先回迁。 |
| P0 | access unit 跨 RTP 包、timestamp wrap、fragmented NAL 缺包 | 直接影响花屏、黑屏、长稳和弱网。 |
| P1 | RTSP setup、keepalive、TCP fallback、302、SDP 容错 | 提升互操作性。 |
| P1 | MediaCodecVideoRenderer late input、Surface、首帧相关修复 | 有助于低延迟和黑屏恢复，但必须确认 API guard。 |
| P2 | LoadControl、stuck player、error handling | 可独立移植时再做。 |
| 不回迁 | UI、Session、Compose、Transformer、IMA、Cast、Inspector、下载 | 与本 fork 目标无关。 |

已识别候选：

- Media3 1.10.0：H.264/H.265 同一 access unit 跨多个 RTP 包解析错误，可能导致 visual artifacts/corruption。
- Media3 1.9.1：RTP timestamp wraparound。
- Media3 1.8.0：fragmented NAL unit 缺 RTP packet 的处理。
- Media3 1.8.0：H.265 RTP Aggregation Packet、RTSP 302、SDP 行尾空白容错。
- Media3 1.6.1：`rtspt://` 明确 TCP。
- Media3 1.5.x：RTP header extension crash、URL encoded `@` user info。
- Media3 1.4.x：SDP 空 session info、invalid media description 容错。
- Media3 1.2.x：TCP fallback race/hang、RTSP setup state、keepalive server timeout、OPTIONS public header 容错。

已完成回迁：

- P0：RTP timestamp wraparound。
- P0：H.264/H.265 access-unit 边界和 Fragmentation Unit 丢包/中断处理。
- P0：H.265 RTP Aggregation Packet。
- P1：SDP 行尾空白和空 `i=` 容错。
- P1：RTP header extension skip 和截断校验。
- P1：`rtspt://` scheme 强制 TCP，并在内部转换为 `rtsp://`。
- P1：RTSP 302 `Location` URI 原样重定向。
- P1：OPTIONS `Public` header 自定义方法容忍。
- P1：RTSP `Session` timeout 驱动 keepalive 间隔。
- P1：SDP invalid media description 跳过。
- P1：URL encoded `@` user-info 解析/移除容错。

## 发布方案

推荐 artifact：

```text
com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.N
```

发布流程：

1. 在 `labi-rtsp-feedback-exoplayer-2.19.1` 分支开发。
2. 运行 ExoPlayer RTSP 单测。
3. 运行必要的 Cast-SDK 真机验证。
4. tag：`exoplayer-rtsp-2.19.1-labi.N`。
5. GitHub Actions 构建 AAR。
6. 发布到 GitHub Pages Maven repo。
7. Cast-SDK 通过 Maven 坐标依赖。

正式依赖不使用本地路径，不使用 JitPack 作为主链路。

## 任务清单

| ID | 任务 | 状态 | 验证 |
| --- | --- | --- | --- |
| X1 | 建立 fork 项目文档和 Agent 规则 | 已完成 | 人工检查 `AGENTS.md` 和本文 |
| X2 | 梳理 Media3 RTSP backport 清单 | 已完成 | `labi-docs/media3-backport-review.md` |
| X3 | 定义 RTSP diagnostics/feedback API | 已完成 | `RtspFeedbackApiTest` |
| X4 | 实现 TCP interleaved RTCP 发送 | 已完成 | `RtspMessageChannelTest` |
| X5 | 实现 UDP RTCP 发送 | 已完成 | `UdpDataSourceRtpDataChannelTest` |
| X6 | 实现 PLI/FIR builder 和 `requestKeyFrame(reason)` | 已完成 | `RtcpFeedbackPacketTest` + RTSP 单测 |
| X7 | 暴露 RTP/reorder queue metrics | 已完成 | `RtpPacketReorderingQueueTest` |
| X8 | Backport P0 RTSP 修复 | 已完成 | `:library-rtsp:test` |
| X9 | 发布 `2.19.1-labi.1` artifact | 已发布 | `https://shenyingjun5.github.io/ExoPlayer/` 已可访问 POM/AAR；tag `exoplayer-rtsp-2.19.1-labi.1` 已推送 |
| X10 | Cast-SDK 接入 patched artifact | 未开始 | Cast-SDK 仓库内完成 |
| X11 | Backport P1 RTSP 互操作修复 | 部分完成 | 302/Public/keepalive/invalid SDP/user-info 已完成；TCP fallback race/hang 待评估 |
| X12 | 暴露首个可解码 H.264 IDR access unit 诊断事件 | 已完成 | `RtpH264ReaderTest` + `RtspFeedbackApiTest` |
| X13 | 发布 `2.19.1-labi.2` artifact | 已发布 | `https://shenyingjun5.github.io/ExoPlayer/` 已可访问 POM/AAR；tag `exoplayer-rtsp-2.19.1-labi.2` 已推送 |

## 验收标准

- patched RTSP 模块保持 Android 4.4 可用：代码层保持 `minSdkVersion=16`，仍需真机覆盖。
- Cast-SDK 接收端能收到 diagnostics：ExoPlayer fork API 已具备，Cast-SDK 接入未完成。
- Cast-SDK 接收端能用低延迟 H.264 access unit 事件作为 loading 兜底：ExoPlayer fork API 已具备，Cast-SDK 接入未完成。
- Cast-SDK 接收端能触发 RTCP PLI/FIR：ExoPlayer fork 内部链路已具备，Cast-SDK 接入未完成。
- Cast-SDK 发送端能收到 PLI/FIR 并强制 IDR：需 Cast-SDK 联调验证。
- H.264/H.265 RTP 关键 backport 有单测覆盖：已完成。
- 真机 RTSP live 摄像头和屏幕链路不回退、不黑屏、不明显增加延迟：未完成，发布 artifact 后验证。
