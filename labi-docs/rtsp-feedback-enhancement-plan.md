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
- RTP timestamp。
- sequence number。
- arrival time。
- reorder queue depth。
- sequence gap。
- late/drop/reset 计数。
- timestamp wraparound。
- transport mode: TCP interleaved / UDP。

### 低延迟恢复

目标能力：

- 首帧超时请求 I 帧。
- sequence gap 持续出现时请求 I 帧。
- decoder recover 或明显落后时请求 I 帧。
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
| X3 | 定义 RTSP diagnostics/feedback API | 未开始 | API review |
| X4 | 实现 TCP interleaved RTCP 发送 | 未开始 | 单测 |
| X5 | 实现 UDP RTCP 发送 | 未开始 | 单测 |
| X6 | 实现 PLI/FIR builder 和 `requestKeyFrame(reason)` | 未开始 | fixture 单测 |
| X7 | 暴露 RTP/reorder queue metrics | 未开始 | queue/gap/drop 单测 |
| X8 | Backport P0 RTSP 修复 | 已完成 | `:library-rtsp:test` |
| X9 | 发布 `2.19.1-labi.1` artifact | 未开始 | Maven 拉取验证 |
| X10 | Cast-SDK 接入 patched artifact | 未开始 | Cast-SDK 仓库内完成 |

## 验收标准

- patched RTSP 模块保持 Android 4.4 可用。
- Cast-SDK 接收端能收到 diagnostics。
- Cast-SDK 接收端能触发 RTCP PLI/FIR。
- Cast-SDK 发送端能收到 PLI/FIR 并强制 IDR。
- H.264/H.265 RTP 关键 backport 有单测覆盖。
- 真机 RTSP live 摄像头和屏幕链路不回退、不黑屏、不明显增加延迟。
