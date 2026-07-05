# RTSP Feedback Implementation Requirements

## 目标

本需求文档用于指导 ExoPlayer fork 的 RTSP 模块改造。目标是在保持 ExoPlayer 2.19.1 和 Android 4.4+ 支持的前提下，为 Cast-SDK Android 接收端提供低延迟 live 必需的 RTSP/RTP/RTCP 可观测性和反馈能力。

本阶段不修改 Cast-SDK 仓库，只在 ExoPlayer fork 中完成 RTSP 模块增强、单元测试和可发布 artifact 的准备。

## 当前实现状态

截至 2026-07-03，ExoPlayer fork 内已完成：

- `RtspMediaSource.Factory` 可选 diagnostics/feedback/policy API。
- `RtspDiagnosticsListener`、`RtspFeedbackListener`、`RtcpFeedbackPolicy`、`RtcpFeedbackRequest`、`RtcpFeedbackReason`、`RtcpFeedbackType`、`RtspTransportMode`、`RtpPacketStats`、`RtpReorderingStats`。
- RTP packet 与 reorder queue diagnostics。
- TCP interleaved RTCP binary frame send。
- UDP RTCP feedback send。
- RTCP PLI/FIR packet builder。
- `RtspMediaPeriod.requestKeyFrame(reason)` 内部请求链路。
- RTP sequence gap 和 queue reset 自动触发 key-frame request。
- Media3 P0 RTSP payload reader backport 与低风险 P1 parser/factory hardening。
- RTSP 302、OPTIONS Public、keepalive timeout、invalid SDP media、encoded `@` user-info 互操作修复。
- `:library-rtsp:testDebugUnitTest`、目标 RTCP 单测和 `:library-rtsp:assembleRelease` 已通过。

仍未完成：

- 首帧超时自动请求 key frame。
- decoder recover 或明显落后时自动请求 key frame。
- TCP fallback race/hang 等剩余 P1 互操作回迁；302、OPTIONS Public、keepalive timeout、invalid SDP media、encoded `@` user-info 已完成。
- patched Maven artifact 本地和 GitHub Pages 远端发布已完成；Cast-SDK 接入验证未完成。

## 背景

Cast-SDK 发送端已经具备：

- macOS AVFoundation / ScreenCaptureKit 采集。
- VideoToolbox H.264 编码。
- Rust RTSP/RTP publisher。
- RTP over TCP interleaved 和 UDP RTP。
- RTCP PLI/FIR 解析。
- 收到 PLI/FIR 后强制下一帧 IDR。
- 低延迟模式和平滑模式。

当前接收端 ExoPlayer 2.19.1 的问题：

- `RtspMediaSource.Factory` 没有暴露 diagnostics listener。
- RTSP 模块没有对外暴露 RTP sequence、timestamp、arrival time、queue depth、gap、drop、late、reset。
- TCP interleaved 下没有公开发送 RTCP binary frame 的能力。
- UDP RTCP channel 没有暴露 feedback 发送能力。
- 接收端无法通过标准 RTCP PLI/FIR 主动请求发送端 IDR。
- 接收端只能靠播放器状态和超时推测问题，无法形成低延迟恢复闭环。

## 不做范围

- 不迁移完整 Media3。
- 不引入 Media3 package 到 Android 4.4 主链路。
- 不整目录覆盖 Media3 RTSP 源码。
- 不改播放器 UI、Session、Transformer、IMA、Cast、下载等无关模块。
- 不自研完整 `MediaCodec` pipeline。
- 不修改 Cast-SDK 仓库。
- 不破坏现有 ExoPlayer RTSP 使用方式。

## 兼容性要求

- 保持 `minSdkVersion=16`。
- 不能引入 Android API 23+ 强依赖；如必须使用高 API，必须加运行时 guard。
- 保持现有 `com.google.android.exoplayer2.*` package。
- API 增强必须是可选能力，不影响现有官方调用路径。
- 默认行为应与 ExoPlayer 2.19.1 原行为一致。

## 重点源码

必须优先改造：

- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaSource.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriod.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspClient.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMessageChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpDataLoadable.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpExtractor.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpPacketReorderingQueue.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/TransferRtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannel.java`

只有在必要时才评估：

- `library/core/src/main/java/com/google/android/exoplayer2/video/MediaCodecVideoRenderer.java`
- `library/core/src/main/java/com/google/android/exoplayer2/mediacodec/MediaCodecRenderer.java`
- `library/core/src/main/java/com/google/android/exoplayer2/DefaultLoadControl.java`

## Public API 需求

在 `RtspMediaSource.Factory` 增加可选配置能力。

建议 API：

```java
public Factory setRtspDiagnosticsListener(@Nullable RtspDiagnosticsListener listener);
public Factory setRtcpFeedbackPolicy(RtcpFeedbackPolicy policy);
```

可选扩展：

```java
public Factory setRtspFeedbackListener(@Nullable RtspFeedbackListener listener);
```

原则：

- 不改变现有构造函数行为。
- listener 为空时零额外行为或低成本行为。
- callback 线程必须在文档中说明。
- 不能把 Cast-SDK 类型引入 ExoPlayer fork。

## 新增类型建议

建议新增在 `com.google.android.exoplayer2.source.rtsp` package 下：

- `RtspDiagnosticsListener`
- `RtspFeedbackListener`
- `RtcpFeedbackPolicy`
- `RtspTransportMode`
- `RtpPacketStats`
- `RtpReorderingStats`
- `RtcpFeedbackRequest`
- `RtcpFeedbackReason`

这些类型必须保持轻量，不依赖 Android UI 或 Cast-SDK。

## Diagnostics 事件需求

需要至少暴露：

- RTSP transport ready。
- TCP interleaved channel pair。
- UDP RTP/RTCP port pair。
- first RTP packet received。
- first decodable H.264 IDR access unit ready。
- RTP packet parsed。
- RTP sequence gap。
- RTP packet late/drop。
- reorder queue depth change。
- queue reset。
- RTP timestamp wraparound。
- RTCP PLI sent。
- RTCP FIR sent。
- RTCP feedback throttled。
- RTCP send failed。

事件字段至少包括：

- track id。
- media type 或 payload type。
- transport mode。
- RTP sequence number。
- RTP timestamp。
- arrival elapsed realtime。
- queue depth。
- gap size。
- reason。
- throwable message，失败时可选。

H.264 access unit 级别事件：

- API 名称：`RtspDiagnosticsListener.onFirstDecodableVideoAccessUnitReady(RtspH264AccessUnitStats)`。
- 触发条件：完整 access unit 已组出、包含 IDR/keyframe、SPS/PPS 已可用、每个 H.264 track 只触发一次。
- 诊断字段：`trackId`、`rtpSequenceNumber`、`rtpTimestamp`、`hasSps`、`hasPps`、`nalUnitType`、`accessUnitType`、`firstRtpPacketElapsedRealtimeMs`。
- 语义限制：这不是 rendered frame，只能作为自家低延迟 RTSP live loading 的兜底信号。
- Cast-SDK UI 优先级：`renderedFirstFrame`，其次该 access-unit 事件，再其次 `videoSize` / `PLAYING` poll。

## RTCP Feedback 需求

必须支持：

- TCP interleaved RTCP 发送。
- UDP RTCP 发送。
- PLI packet builder。
- FIR packet builder。
- request key frame 内部调用链。
- feedback 发送限流。

建议内部 API：

```java
boolean requestKeyFrame(RtcpFeedbackReason reason);
```

行为：

- 如果 RTCP channel 未 ready，返回 false 并上报 diagnostics。
- 如果命中限流，返回 false 并上报 throttled。
- 如果发送成功，返回 true 并上报 sent。
- PLI 优先；FIR 作为可配置 fallback。

限流默认值：

- 同一 track 默认 `300-500ms` 内最多发送一次 PLI/FIR。
- 具体值放在 `RtcpFeedbackPolicy`。

## TCP interleaved 需求

需要在 `RtspMessageChannel` 增加发送 binary interleaved frame 的能力。

目标格式：

```text
'$' channel length_hi length_lo payload
```

要求：

- 复用已有 sender thread，避免并发写 socket 交错。
- RTSP text message 和 binary interleaved frame 的写入必须串行化。
- 发送失败必须回调 diagnostics。
- 不影响现有 RTSP message send 行为。

## UDP RTCP 需求

需要让 UDP RTP data channel 对应 RTCP channel 可发送 feedback。

要求：

- 保存 RTCP socket/channel。
- 能向 server RTCP 地址发送 PLI/FIR。
- 未拿到 server RTCP 地址时安全失败并上报 diagnostics。
- 不阻塞 RTP read loop。

## RTP Metrics 需求

在 `RtpExtractor` 和 `RtpPacketReorderingQueue` 中增加低成本指标。

必须覆盖：

- first packet。
- last received sequence。
- last dequeued sequence。
- last RTP timestamp。
- queue depth。
- dropped before enqueue。
- duplicate packet。
- sequence gap。
- queue reset reason。
- packet age。

不能为了 diagnostics 引入明显 per-packet 大对象分配；高频事件需要可关闭或采样。

## 低延迟触发建议

本 fork 主要提供底层能力，不直接绑定 Cast-SDK 策略。

可以提供基础 helper：

- first packet timeout 时 request key frame：未完成。
- sequence gap 达到阈值时 request key frame：已完成。
- queue reset 时 request key frame：已完成。

更复杂的策略由 Cast-SDK 接收端通过 listener 和 `requestKeyFrame` 能力决定。

## Media3 Backport 顺序

先按 `labi-docs/media3-backport-review.md` 的 P0 项处理。

优先顺序：

1. H.264/H.265 access unit 跨 RTP 包解析错误。
2. RTP timestamp wraparound。
3. fragmented NAL 缺 RTP packet 的处理。
4. TCP fallback / setup race / hang。
5. RTSP keepalive 使用 server timeout：已完成。
6. SDP / 302 / header extension 容错：已完成主要项。

每个 backport 必须记录来源：

- Media3 release version。
- upstream commit / PR / issue。
- 变更文件。
- 是否依赖 Media3-only API。
- 是否影响 Android 4.4。
- 测试覆盖。

## 测试要求

必须新增或补充 ExoPlayer RTSP 单测。

测试类别：

- RTCP PLI packet builder。
- RTCP FIR packet builder。
- TCP interleaved binary frame send serialization。
- RTSP message 和 binary frame 混合发送不交错。
- UDP RTCP feedback send。
- RTP reorder queue gap/drop/late/reset metrics。
- RTP timestamp wraparound。
- H.264 fragmented NAL missing packet。
- H.264 access unit across multiple RTP packets。
- H.264 first decodable IDR access unit diagnostics: single-packet IDR、FU-A IDR、missing SPS/PPS、non-IDR。
- diagnostics listener 空实现时不改变原行为。

建议先跑：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest
```

当前 ExoPlayer fork 的 RTSP Gradle target 是 `:library-rtsp`，不是 `:exoplayer-rtsp` 或 `:library:rtsp`。

## 交付顺序

第一阶段：API 和 diagnostics 骨架。状态：已完成。

- 新增 listener/policy/stats 类型。
- `RtspMediaSource.Factory` 接入配置。
- `RtspMediaPeriod` / `RtpDataLoadable` 传递 listener。
- 不改变默认行为。

第二阶段：RTCP feedback。状态：已完成。

- PLI/FIR builder。
- TCP interleaved RTCP send。
- UDP RTCP send。
- 限流和 diagnostics。

第三阶段：RTP metrics。状态：已完成。

- `RtpExtractor` packet metrics。
- `RtpPacketReorderingQueue` queue metrics。
- gap/drop/late/reset 上报。

第四阶段：Media3 P0 backport。状态：已完成。

- 按 `media3-backport-review.md` 的 P0 顺序逐个回迁。
- 每个 backport 单独提交，便于回滚。

第五阶段：发布准备。状态：已完成发布。

- 更新版本号策略：已完成，默认发布 `com.zknowai.exoplayer:*:2.19.1-labi.1`。
- 准备 GitHub Actions 构建 AAR：未完成。
- 发布 `2.19.1-labi.1` artifact：本地静态 Maven repo 已生成于 `buildout/labi-maven-repo`，远端已发布到 `https://shenyingjun5.github.io/ExoPlayer/`。

## 验收标准

- Android 4.4 兼容基线不破坏：代码层保持 `minSdkVersion=16`，仍需 Android 4.4 设备验证。
- 原有 RTSP 用法不需要修改即可继续工作：单测通过，仍需回归验证真实流。
- Cast-SDK 能通过 listener 收到 RTP/RTCP diagnostics：ExoPlayer fork 已具备 API，Cast-SDK 接入未完成。
- Cast-SDK 能触发 PLI/FIR，并由发送端收到后强制 IDR：ExoPlayer fork 已具备发送链路，Cast-SDK 联调未完成。
- P0 Media3 RTSP 修复至少完成评估，能回迁的完成回迁：已完成。
- 关键协议行为有单测：已完成。
- 不引入 Media3 整体依赖：已满足。

## 明确禁止

- 禁止整目录复制 Media3 到本 fork。
- 禁止把 Cast-SDK 代码或类型引入 ExoPlayer。
- 禁止移除 Android 4.4 支持。
- 禁止用本地路径作为正式发布方案。
- 禁止在没有测试的情况下合并 RTP/H.264 组帧改动。
