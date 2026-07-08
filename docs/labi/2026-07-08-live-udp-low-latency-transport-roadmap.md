# Live UDP 低延迟传输视觉可靠 Roadmap

## 背景和目标

本文定义自家摄像头和屏幕 RTSP live 的 UDP 低延迟实验路线。核心目标不是在 UDP 上重做 TCP 式字节可靠，而是做到：

```text
UDP 低延迟传输
+ 丢包时不花屏
+ 允许短暂冻结或卡顿
+ 通过独立 live control channel 快速请求 IDR 恢复
```

也就是视觉可靠，不是字节可靠。UDP 不能强行等待所有包重传到齐，否则会重新引入队头阻塞和延迟堆积。

## 不做范围

- 不实现完整可靠 UDP 协议。
- 不做所有 RTP 包无限重传。
- 不把 UDP 作为普通 RTSP 直播流默认传输。
- 不影响第三方 DLNA/RTSP 直播、视频点播、HLS、图片和音乐投屏。
- 不把 App 层做成播放器恢复逻辑入口；协议、传输、恢复和状态同步仍属于 SDK / ExoPlayer fork 边界。
- 不在 release 默认路径暴露 test-only 丢包、乱序、坏 NAL 注入入口。

## 相关文档

- `docs/plans/2026-07-03-sender-live-latency-optimization-roadmap.md`
- `docs/plans/2026-07-03-receiver-exoplayer-rtsp-feedback-fork-plan.md`
- `docs/plans/2026-07-06-live-screen-mirror-transport-plan.md`
- `docs/plans/2026-07-07-live-independent-feedback-channel-roadmap.md`
- `docs/plans/2026-07-05-live-end-to-end-latency-observability-plan.md`
- `docs/receiver-android-architecture.md`
- `docs/sender-architecture.md`

本文是 UDP 低延迟传输的执行 Roadmap。`2026-07-06-live-screen-mirror-transport-plan.md` 保留为 TCP 修复、早期 UDP 方案和历史排障依据；若二者冲突，以本文的“视觉可靠 UDP + 独立控制通道主反馈 + 普通 RTSP 默认不变”为准。

## 总体结论

UDP 可以做，但必须按实验开关逐步灰度。推荐最终链路：

```text
RTSP control: TCP
RTP/RTCP media: UDP port pair
Recovery control: independent Cast-SDK live control channel
RTCP PLI/FIR: standard fallback / comparison path
```

默认策略保持：

```text
普通 RTSP / 第三方直播 / default live mode -> EXOPLAYER_DEFAULT
自家 smooth live -> FORCE_TCP
自家 low_latency + UDP 实验开关 -> AUTO_UDP_THEN_TCP
调试矩阵 -> FORCE_UDP
```

UDP 只有在同时满足下面条件时才能启用：

- 发送端和接收端都是自家 SDK。
- CastExtension capability 明确支持 UDP low-latency live。
- 当前媒体是自家 camera/screen live。
- 当前 playback mode 是 `low_latency`。
- 发送端提供独立 `liveControlUrl/sessionId/token`。
- 用户、测试配置或灰度策略显式打开 UDP 实验开关。

只要任一条件不满足，接收端必须回到 `EXOPLAYER_DEFAULT`，不改变普通 RTSP 行为。Cast-SDK `LegacyExoPlayerAdapter` 曾经无条件调用 `setForceUseRtpTcp(true)`；这是历史稳定性实现，不是目标边界。本轮已在单元级把普通 RTSP 拆到 `EXOPLAYER_DEFAULT + passive/no recovery`，真机普通 RTSP smoke 仍待补。

## 核心原则

### 1. 不做 TCP 式可靠 UDP

UDP 丢包后不等旧包无限补齐。只允许很短的乱序等待窗口，例如 `30ms`，实验档可测 `50ms`。超过窗口直接判定当前 AU 或参考链不可信，进入 `WAIT_IDR`。

### 2. 视觉可靠优先

坏 access unit 不能进入 SampleQueue。为了避免花屏，可以短暂冻结上一帧，等待完整 IDR 恢复。

### 3. 丢包检测和 RTCP 发送解耦

在显式 low-latency recovery policy 下，sequence gap detection 必须始终生效，不能和 `rtcpFeedbackRequester != null` 绑定。默认 policy 仍保持 passive，不启用 WAIT_IDR recovery state machine，不改变普通 RTSP 行为。策略应拆成：

```text
gap / corruption / queue reset -> recovery event
RTCP_ONLY / BOTH -> 可以发 RTCP
EXTERNAL_ONLY -> 只上报，不发 RTCP
```

这保证 `EXTERNAL_ONLY` 下即使不发 RTCP，也能进入 `WAIT_IDR` 并由 Cast-SDK 独立控制通道请求 IDR。

### 4. 独立控制通道是主反馈

UDP 模式下，自家双端优先走 independent live control channel 请求 IDR。RTCP PLI/FIR 只作为标准 fallback 或对照指标。

### 5. 普通 RTSP 隔离

所有 UDP、low-latency buffer、drop-until-idr、packet diagnostics 和高频恢复策略都必须绑定自家 low-latency live 会话。`PlaybackSession` 的 `default` 模式继续使用播放器默认策略；Stop、完成、错误和 release 后恢复 `default`。

## ExoPlayer 当前源码复核结论

基于 `/Users/shenyingjun/Downloads/ExoPlayer` 当前源码复核：

1. ExoPlayer `RtspMediaSource.Factory` 默认 `forceUseRtpTcp=false`。不调用 `setForceUseRtpTcp(true)` 时，`createMediaSource()` 会优先使用 `UdpDataSourceRtpDataChannelFactory`；如果 UDP 没有收到 sample，`RtspMediaPeriod` 会 retry 到 TCP。也就是说，普通 RTSP 的上游默认不是强制 TCP。
2. Cast-SDK `LegacyExoPlayerAdapter` 历史上无条件调用 `setForceUseRtpTcp(true)`，这是 Cast-SDK adapter 的历史稳定性策略，不是 ExoPlayer 默认。本轮已在单元级修正为普通 RTSP `EXOPLAYER_DEFAULT`，`LOW_LATENCY/SMOOTH` 继续保留 `FORCE_TCP` 稳定路径。
3. `RtpExtractor` 当前会在 `EXTERNAL_ONLY` 下把 `rtcpFeedbackRequester` 置空；`RtpPacketReorderingQueue.offer()` 当前只有 `rtcpFeedbackRequester != null` 才设置 `lastOfferDiscontinuityReason = SEQUENCE_GAP` 并触发后续 `payloadReader.onRtpStreamDiscontinuity(...)`。这会导致 `EXTERNAL_ONLY` 下 sequence gap 可能无法进入 H.264 WAIT_IDR，是 ExoPlayer fork P0 必修项。
4. `RtpH264Reader` 当前已经具备部分低延迟恢复能力：`onRtpStreamDiscontinuity`、`markCurrentAccessUnitCorrupted`、`enterWaitForIdr`、`onH264WaitForIdrTimedOut`、完整 IDR 后 `exitWaitForIdr`。但这些能力依赖 discontinuity 能正确传入，所以必须先修第 3 点。
5. `RtpExtractor.getCutoffTimeMs()` 当前固定约 `30ms` 乱序窗口。后续可配置化属于 P1，不应作为 P0 阻塞。
6. 当前已有 `RtspDiagnosticsListener.onTransportReady(trackId, transportMode, transport)`，可以识别 UDP/TCP 实际 transport；但缺少明确的 `fallbackReason`、`udpSetupFailed`、`udpNoPacketTimeout` 等业务可诊断字段。

## 现有代码架构 review

### Cast-SDK 当前问题

基于当前 `Cast-SDK-worktree` 代码复核：

- `receiver/android/sdk/player-exo-legacy/.../LegacyExoPlayerAdapter.java` 当前约 `2990` 行，已经同时承担 ExoPlayer 创建、RTSP transport 选择、fork API 反射、feedback policy 构造、diagnostics 聚合、no-packet timeout、session rebuild、loading 兜底和日志输出。UDP 如果继续塞进这个类，会把 TCP 稳定路径、UDP 实验路径和 diagnostics 热路径耦合在一起。
- `LegacyExoPlayerAdapter.buildRtspMediaSource()` 历史上无条件 `setForceUseRtpTcp(true)`，这和本文目标“普通 RTSP 使用 `EXOPLAYER_DEFAULT`”不一致。本轮已抽出 `LegacyRtspTransportStrategyResolver`，普通 RTSP 不再 force TCP。
- `LegacyExoPlayerAdapter.buildRtcpFeedbackPolicyObject()` 历史上在 `DEFAULT` / `SMOOTH` 下也会设置 PLI/FIR、sequence gap threshold 和 queue reset request；旧测试也按该行为验收。本轮已把普通 `DEFAULT` 迁移为 passive；`SMOOTH` 仍保守 feedback 且继续走 `FORCE_TCP`。
- `RtspPlaybackDiagnostics.java` 当前约 `864` 行，字段和构造器已经很长。继续直接往一个大对象里追加 UDP transport、fallback、loss、recovery、packet trace 字段，会让调用方和测试维护成本升高。
- `LiveFeedbackController.java` 当前约 `764` 行，已经同时承担连接管理、心跳、keyframe request、recovery state/source_unreachable/recovered 上报、限流和 JSON 组包。UDP 后续还要携带 RTP/loss metadata，不宜继续无边界扩展一个类。
- 现有 `/debug/state.rtsp` 和 `/debug/live/latency` 已能输出 RTSP/control channel 摘要，但 UDP packet diagnostics、loss window、fallback reason 还没有独立结构，容易和 TCP interleaved 诊断混在一起。

### ExoPlayer fork 当前问题

基于 `/Users/shenyingjun/Downloads/ExoPlayer` 当前源码复核：

- transport 语义分散在 `RtspMediaSource.Factory.setForceUseRtpTcp()`、`RtspMediaPeriod.retryWithRtpTcp()`、`RtpDataChannel.Factory.createFallbackDataChannelFactory()` 和 `UdpDataSourceRtpDataChannelFactory`。当前只有“force TCP”和“默认 UDP 优先 + fallback TCP”两种 public 语义；真正 `FORCE_UDP` 需要新增 transport strategy，而不是复用 `forceUseRtpTcp=false`。
- recovery 语义分散在 `RtcpFeedbackPolicy`、`RtpExtractor`、`RtpPacketReorderingQueue` 和 `reader/RtpH264Reader`。其中 `RtpPacketReorderingQueue` 当前把 discontinuity reason 和 RTCP requester 绑定，是 `EXTERNAL_ONLY` 下漏恢复的直接原因。
- diagnostics 已有低频 listener 和 packet stats，但高频 packet diagnostics 仍必须保持默认关闭。后续不能为了 UDP 在每包路径加字符串、JSON、主线程回调或异常可冒泡的业务 listener。
- H.264 recovery 基础能力已经存在，不建议新建一条平行 H.264 解包链路；应在现有 reader 内补 AU 完整性守卫和状态机入口，避免维护两套 RTP/H.264 逻辑。

## 目标代码模块架构

### Cast-SDK receiver

目标不是马上拆成新的 Gradle module，而是在现有 module 内先拆 package/class 边界，避免影响包体和依赖图。

建议结构：

```text
receiver/android/sdk/player-api/.../player/
  LiveTransportStrategy.java
  RtspTransportDiagnostics.java
  RtspRtpLossDiagnostics.java
  RtspRecoveryDiagnostics.java
  RtspPlaybackDiagnostics.java

receiver/android/sdk/player-exo-legacy/.../exolegacy/
  LegacyExoPlayerAdapter.java
  rtsp/
    LegacyRtspMediaSourceBuilder.java
    LegacyRtspForkApiBridge.java
    LegacyRtspTransportStrategyResolver.java
    LegacyRtspFeedbackPolicyResolver.java
    LegacyRtspDiagnosticsCollector.java
    LegacyRtspRecoveryStateMachine.java
    LegacyRtspRebuildController.java

receiver/android/sdk/core/.../core/live/
  LiveFeedbackController.java
  LiveControlClient.java
  LiveFeedbackPolicy.java
  LiveFeedbackStateReporter.java
  LiveControlJsonCodec.java
```

职责边界：

- `LegacyRtspTransportStrategyResolver`：只做 CastExtension/capability/mode/runtime flag 到 `LiveTransportStrategy` 的归一化；普通 RTSP、第三方 URL、无 capability 一律输出 `EXOPLAYER_DEFAULT`。
- `LegacyRtspMediaSourceBuilder`：只负责创建 `RtspMediaSource.Factory`，按 strategy 调用 fork API；`FORCE_TCP` 才调用 `setForceUseRtpTcp(true)`，`EXOPLAYER_DEFAULT` 不调用，`FORCE_UDP/AUTO` 只通过新增 fork strategy API 表达。
- `LegacyRtspForkApiBridge`：集中处理反射、API 是否存在、异常降级和 listener 注册；不要把反射散在 adapter 主类里。
- `LegacyRtspFeedbackPolicyResolver`：只决定 `RtcpFeedbackPolicy`，默认普通 RTSP 必须 passive；自家 low-latency 才选择 `EXTERNAL_ONLY/BOTH`。
- `LegacyRtspDiagnosticsCollector`：只聚合 fork callbacks 到 `RtspPlaybackDiagnostics`，不做恢复决策，不触发 control channel，不写逐包日志。
- `LegacyRtspRecoveryStateMachine`：只消费 diagnostics/recovery event，输出 `waiting_for_idr`、`source_unreachable`、`recovered`、`should_rebuild` 等状态。
- `LegacyRtspRebuildController`：只处理 RTP no-packet / TCP 卡死 / UDP fallback 后的 media source rebuild；不要和 WAIT_IDR 请求 IDR 混在一起。
- `LiveFeedbackPolicy`：只决定是否通过独立控制通道发送 keyframe/recovery/source/recovered；限流、去重、优先级集中在这里。
- `LiveControlClient` / `LiveControlJsonCodec`：只做 TCP JSON framing、编码、超时和 ack；后续 length-prefixed JSON 升级不影响恢复策略。

迁移顺序：

1. 先把现有 `LegacyExoPlayerAdapter` 的 RTSP factory/config/diagnostics/recovery 逻辑抽到 `exolegacy/rtsp`，保持行为不变。
2. 再把普通 RTSP strategy 从当前 `FORCE_TCP + conservative feedback` 迁移到 `EXOPLAYER_DEFAULT + passive feedback`，并补回归测试。
3. 最后接入 `FORCE_UDP/AUTO_UDP_THEN_TCP`，只影响自家 low-latency + 实验开关路径。

### ExoPlayer fork

目标是保持 `library/rtsp` 内聚，不触碰 core / renderer / extractor 公共模块，除非单独方案批准。

建议新增或拆分：

```text
library/rtsp/.../source/rtsp/
  RtspTransportStrategy.java
  RtspTransportPolicy.java
  RtspTransportDiagnostics.java
  RtpRecoveryPolicy.java
  RtpRecoveryEvent.java
  RtpPacketReorderingQueue.java
  RtpExtractor.java
  reader/RtpH264Reader.java
```

职责边界：

- `RtspTransportStrategy`：public API，表达 `DEFAULT / FORCE_TCP / FORCE_UDP / AUTO_UDP_THEN_TCP`。保留 `setForceUseRtpTcp(boolean)` 既有行为，新增 additive setter，避免破坏 API 兼容。
- `RtspTransportPolicy`：内部策略，决定是否创建 UDP channel、是否允许 TCP fallback、fallback reason 如何上报。
- `RtpRecoveryPolicy`：内部策略，决定 sequence gap / queue reset / AU corrupted 是否通知 payload reader、是否允许 RTCP 发送、是否启用 WAIT_IDR。
- `RtpRecoveryEvent`：只承载 primitive 字段和稳定 reason，不做日志格式化。
- `RtpPacketReorderingQueue`：只负责排序、gap/reset 检测和轻量 stats；discontinuity reason 必须独立于 `RtcpFeedbackRequester`。
- `RtpExtractor`：只负责 packet parse、reorder、向 payload reader 传 discontinuity、低频/开关控制的 diagnostics 分发；不得把 transport fallback 或业务 recovery 决策塞进去。
- `RtpH264Reader`：只负责 H.264 AU 完整性、WAIT_IDR、坏 AU 不提交 SampleQueue；不关心 Cast-SDK control channel。

隔离要求：

- TCP interleaved 原路径保持可读：`FORCE_TCP` 仍走现有 `TransferRtpDataChannelFactory` 和 RTCP interleaved 发送。
- UDP 路径新增策略不得改变 `setForceUseRtpTcp(true)` 的行为。
- 默认 `RtspMediaSource.Factory` 不注册低延迟 recovery policy，不启用 packet diagnostics，不改变官方默认 UDP-first fallback 行为。
- `FORCE_UDP` 只是在 transport policy 禁用 fallback；不复制一套 RTP parser、reorder queue 或 H.264 reader。

## 方案分层

### L1 Transport 策略

新增四态传输策略：

```text
EXOPLAYER_DEFAULT
FORCE_TCP
FORCE_UDP
AUTO_UDP_THEN_TCP
```

语义：

- `EXOPLAYER_DEFAULT`：不调用 `setForceUseRtpTcp(true)`，保持 ExoPlayer 默认 RTSP transport 行为，普通第三方 RTSP 使用该策略。当前 ExoPlayer 语义是 UDP 优先，并在无样本、UDP 不可用或异常路径上允许 fallback TCP；它不是强制 UDP。
- `FORCE_TCP`：强制 ExoPlayer `setForceUseRtpTcp(true)`，用于自家 low-latency 当前稳定策略、smooth live 或显式兼容回退。
- `FORCE_UDP`：不允许 TCP fallback，只用于测试矩阵和问题复现。现有 ExoPlayer public API 不能直接表达该语义，fork 侧需要新增显式 transport strategy 或禁用 fallback/retry 的能力。
- `AUTO_UDP_THEN_TCP`：先尝试 UDP RTP/RTCP，UDP SETUP、首包或播放中 no-packet 超时后 fallback/rebuild 到 TCP。

UDP 模式：

```text
RTSP control: TCP socket
RTP: UDP port N
RTCP: UDP port N+1
```

需要新增 diagnostics：

- `requestedTransportStrategy`
- `actualTransportMode`
- `transportFallbackReason`
- `udpSetupFailed`
- `udpNoPacketTimeout`
- `serverUdpUnsupported`
- `fallbackToTcpCount`
- `lastTransportSwitchElapsedRealtimeMs`

### L2 RTP 丢包和乱序

ExoPlayer fork 要保证：

- 在显式 low-latency recovery policy 下，16-bit RTP sequence gap 检测必须始终生效；默认 policy 继续 passive，不改变普通 RTSP 行为。
- sequence gap 与 RTCP feedback requester 解耦。
- UDP low-latency 下 `sequenceGapRequestThreshold = 1`。
- 乱序等待窗口默认 `30ms`，实验 `50ms`。
- duplicate、late、queue reset、timestamp wrap 都要有结构化指标。

输出事件：

- `onRtpSequenceGap`
- `onRtpLatePacketDropped`
- `onRtpDuplicatePacketDropped`
- `onRtpReorderWindowExpired`
- `onRtpQueueReset`

### L3 H.264 AU 完整性守卫

目标：坏 AU 绝不能进入 SampleQueue。

必须覆盖：

- FU-A start/middle/end 缺失。
- timestamp 变化但前 AU 没 marker。
- sequence gap。
- marker 缺失以 AU 边界判断为准，重点覆盖 `timestamp change before previous AU marker`，避免在普通路径增加重型逐包重解析。
- timestamp wrap。
- duplicate packet。
- late packet。
- queue reset。

策略：

```text
当前 AU 内丢包:
  当前 AU 标记 corrupted，不提交 SampleQueue

非当前 AU 丢包 / sequence gap:
  参考链不可信，进入 WAIT_IDR

WAIT_IDR 中:
  丢所有非 IDR
  只有完整 IDR + SPS/PPS 可用后恢复
```

注意：如果丢的是完整 P 帧，后续 P 帧本身可能完整，但参考链已经断了。为了不花屏，不能继续喂后续 P 帧，必须等待 IDR。

### L4 WAIT_IDR 恢复

UDP 下 `WAIT_IDR` 是常规恢复机制，不是异常小概率路径。

触发：

- `sequence_gap`
- `corrupted_au`
- `queue_reset`
- `wait_idr_timeout`
- `first_decodable_timeout`
- `udp_loss_rate_high`

动作：

```text
enter WAIT_IDR
-> Cast-SDK LiveFeedbackController 发 keyframe_request
-> ExoPlayer fork 丢弃非 IDR
-> 收到完整 IDR + SPS/PPS 后 exit WAIT_IDR
-> 上报 recovered
```

两个 timeout 必须分清：

- `WAIT_IDR timeout`：仍有 RTP 包，但一直没有完整 IDR。继续请求 IDR、降码率、必要时 rebuild。
- `RTP no-packet timeout`：完全没 RTP 包。判断 UDP 链路断流、server 停止、端口不可达，触发 fallback TCP 或 session rebuild。

### L5 独立控制通道

UDP 模式下默认 feedback strategy：

```text
首选: EXTERNAL_ONLY
对照/灰度: BOTH
兜底: RTCP_ONLY 仅用于第三方或控制通道不可用时
```

`keyframe_request` 必须携带：

- `requestId`
- `reason`
- `lastRtpSequence`
- `lastRtpTimestamp`
- `receiverSendTimeMs`
- `waitingForIdrMs`
- `lossRate5s`
- `transportMode`

发送端 ack 必须携带：

- `accepted/throttled/encoder_not_ready`
- `senderReceiveTimeMs`
- `senderActionTimeMs`
- `nextIdrRtpTimestamp`

限流：

- `LOW_LATENCY`：`300-500ms`
- `SMOOTH`：`800-1000ms`

UDP 下可以比 TCP 更积极，但不能无限请求 IDR，否则 I 帧 burst 会打爆网络。

### L6 发送端配合

发送端必须配合：

- 禁 B 帧。
- IDR 必须带 SPS/PPS。
- 支持立即 `requestKeyFrame()`。
- 支持 startup/recovery short-GOP。
- UDP pacing，避免大 IDR burst。
- MTU 控制，RTP payload 建议 `1200-1400 bytes`，当前优先保持 `1200 bytes`。
- 不依赖 IP fragmentation。
- pending/backlog 有硬上限。
- 弱网下可降码率、降 fps。

IDR pacing：

- 一个大 IDR 不应瞬间打满 UDP socket。
- 可把 IDR RTP packets 摊到 `<= 半帧时间` 内发送，例如 30fps 下 `<= 16ms`。

### L7 指标和验收

必须观测：

- `transportMode`
- `requestedTransportStrategy`
- `transportFallbackReason`
- `rtpQueueMs`
- `rtpQueueDepth`
- `rtpQueueAgeMs`
- `sequenceGapCount`
- `duplicateCount`
- `lateDropCount`
- `queueResetCount`
- `corruptedAuCount`
- `waitIdrCount`
- `waitIdrTimeoutCount`
- `droppedUntilIdrCount`
- `idrRecoveredCount`
- `requestIdrCount`
- `requestIdrAckMs`
- `idrRecoverMs`
- `udpLossRate5s`
- `sampleQueueBufferedAheadMs`
- `captureToRenderP50/P95/P99`
- `visibleCorruptionCount`
- `rebufferCount`

验收标准：

- 花屏：0 容忍。
- 短卡顿：可接受，但必须统计。
- `WAIT_IDR -> recovered`：目标 `200-500ms`。
- UDP 下 `sampleQueueBufferedAheadMs` 明显低于 TCP。
- UDP 下 `captureToRender P95` 明显低于 TCP。
- UDP 不稳定时能 fallback TCP。
- fallback TCP 后普通播放继续稳定，不无限恢复循环。

## Cast-SDK 侧任务

### Receiver SDK

1. 在 `player-api` 增加 live transport strategy 抽象：

```text
LiveTransportStrategy.EXOPLAYER_DEFAULT
LiveTransportStrategy.FORCE_TCP
LiveTransportStrategy.FORCE_UDP
LiveTransportStrategy.AUTO_UDP_THEN_TCP
```

2. 在 CastExtension capability / invite 中增加字段：

- `liveTransportStrategy`
- `supportsUdpLowLatency`
- `udpExperimentEnabled`
- `transportFallbackPolicy`

3. `PlaybackSession` 只在自家 low-latency live prepare 时消费 transport strategy，一次性生效；Stop、完成、错误、release 后恢复默认。

4. `LegacyExoPlayerAdapter` 根据 strategy 设置 ExoPlayer RTSP factory：

- `FORCE_TCP`：`setForceUseRtpTcp(true)`
- `EXOPLAYER_DEFAULT`：不调用 `setForceUseRtpTcp(true)`
- `FORCE_UDP`：不 force TCP，并禁用或拒绝 fallback
- `AUTO_UDP_THEN_TCP`：不 force TCP，启用 UDP 首包/no-packet fallback 诊断

5. `RtspPlaybackDiagnostics` 扩展 transport fields 和 UDP loss fields。

6. `LiveFeedbackController` 的 `keyframe_request` 增加 RTP 和 loss metadata。

7. `/debug/state.rtsp`、`/debug/live/latency` 输出 transport strategy、fallback reason、loss、WAIT_IDR、IDR recovered 指标。

8. 保持普通 RTSP 隔离：没有自家 capability 或不是 `low_latency` 时，策略强制归一为 `EXOPLAYER_DEFAULT/default`。

### Sender SDK

1. 在 sender live contract 增加 UDP strategy / capability 字段。

2. macOS camera/screen backend 发布状态中暴露：

- 是否支持 UDP RTP/RTCP。
- 当前 RTSP server UDP 端口能力。
- 当前 control channel URL。

3. RTSP publisher 确认 UDP payload size、FU-A、marker、SPS/PPS + IDR 语义。

4. 实现 IDR pacing 和 UDP send queue 指标。

5. 接收 live control `keyframe_request` 中的 `lastRtpSequence/lastRtpTimestamp/lossRate`，记录到 trace，回 `nextIdrRtpTimestamp`。

6. 弱网下先降码率，再降 fps，不自动改变用户可见模式；只输出模式建议。

### Demo / CLI / QA

1. Demo 增加低延迟 UDP 实验开关，默认关闭。

2. CLI 增加：

```text
--live-transport force-tcp|force-udp|auto-udp-then-tcp
--udp-experiment
```

3. QA 增加 test-only 故障注入：

- 丢 RTP。
- 乱序。
- duplicate。
- late packet。
- 坏 FU-A。
- UDP no-packet。

4. 报告输出 TCP vs UDP A/B：

- 首帧。
- 稳态延迟。
- WAIT_IDR 恢复。
- fallback。
- 可见花屏。

## 测试方案细化

结论：原有测试不能直接满足 UDP 视觉可靠验收。现有测试能覆盖基础组件，但缺少 UDP low-latency 组合路径、策略隔离和弱网可见结果验证。UDP 必须有单独测试方案，并且普通 RTSP 回归测试要作为 release gate。

### 现有测试覆盖

已有可复用测试：

- ExoPlayer `UdpDataSourceRtpDataChannelTest`：覆盖 UDP RTP/RTCP channel 基础能力。
- ExoPlayer `RtspPlaybackTest`：已有 UDP unsupported fallback / no fallback 行为测试，可扩展成 `AUTO_UDP_THEN_TCP` 和 `FORCE_UDP` 策略测试。
- ExoPlayer `RtpPacketReorderingQueueTest`：已有 sequence gap / queue reset 请求 keyframe 测试，但当前还缺“requester 为 null 仍设置 discontinuity reason”的 `EXTERNAL_ONLY` 测试。
- ExoPlayer `RtpH264ReaderTest`：已有 H.264 reader 和 WAIT_IDR 相关基础测试，可扩展 FU-A 缺片、timestamp change before marker、完整 IDR recovered。
- Cast-SDK `LegacyExoPlayerAdapterTest`：已有 RTSP timeout/feedback 阈值测试，但当前默认 RTSP 仍按 conservative feedback 验收，后续必须改成 passive/default isolation。
- Cast-SDK `LiveFeedbackControllerTest`：已有独立控制通道 request/recovery/source/recovered fake client 测试，可扩展 RTP/loss metadata 和 UDP reason。
- 现有 `qa/reports/receiver/live-latency*`、`live-rtsp-rebuild*` 报告可复用为 TCP baseline，但不能替代 UDP 弱网报告。

### ExoPlayer fork 单测

必须新增或调整：

- `RtpPacketReorderingQueueTest`：
  - `EXTERNAL_ONLY + sequence gap + requester null`：不发 RTCP，但 `lastOfferDiscontinuityReason=SEQUENCE_GAP`。
  - `EXTERNAL_ONLY + queue reset + requester null`：不发 RTCP，但 `lastOfferDiscontinuityReason=QUEUE_RESET`。
  - `RTCP_ONLY/BOTH + sequence gap`：仍发 RTCP，防止解耦后丢旧能力。
- `RtpExtractorTest`：
  - `EXTERNAL_ONLY + sequence gap`：触发 `payloadReader.onRtpStreamDiscontinuity(SEQUENCE_GAP)`。
  - packet diagnostics disabled：不创建 `RtpPacketStats`，不调用 per-packet listener。
  - listener 抛异常隔离：低频 diagnostics 异常不能改变 extractor 读包结果；如果实现选择不 catch，则必须证明 Cast-SDK 不会给普通 RTSP 注册 listener。
- `RtpH264ReaderTest`：
  - FU-A 缺 start / middle / end：坏 AU 不提交 SampleQueue。
  - `timestamp change before previous AU marker`：前 AU 标记 corrupted，并进入 WAIT_IDR。
  - WAIT_IDR 中连续 P 帧：全部 drop，直到完整 IDR + SPS/PPS。
  - complete IDR recovered：触发 `onH264WaitForIdrEnded`，恢复提交后续 AU。
- `RtspMediaSource` / `RtspMediaPeriod` / `RtspPlaybackTest`：
  - `EXOPLAYER_DEFAULT`：保持官方默认 UDP-first + fallback TCP 行为。
  - `FORCE_TCP`：行为等同 `setForceUseRtpTcp(true)`。
  - `FORCE_UDP`：UDP unsupported / no sample 不 fallback TCP，输出明确错误和 diagnostics。
  - `AUTO_UDP_THEN_TCP`：UDP setup failed、server unsupported、first no-sample、playback no-packet 分别 fallback，并上报 reason。

### Cast-SDK JVM 单测

必须新增或调整：

- `LiveTransportStrategy` / resolver：
  - 无 CastExtension capability、第三方 URL、default mode -> `EXOPLAYER_DEFAULT`。
  - 自家 low_latency + capability + experiment enabled + control channel -> `AUTO_UDP_THEN_TCP`。
  - QA override -> `FORCE_UDP`，release 默认不可进入。
  - session stop / complete / error / release 后策略清空。
- `LegacyRtspMediaSourceBuilder` / adapter：
  - `EXOPLAYER_DEFAULT` 不调用 `setForceUseRtpTcp(true)`，不注入 low-latency feedback policy，不开启 packet diagnostics。
  - `FORCE_TCP` 保持当前 TCP 稳定路径。
  - fork 缺少新增 transport strategy API 时，`FORCE_UDP/AUTO` 降级或报 unsupported，不崩溃。
- `LegacyRtspFeedbackPolicyResolver`：
  - 普通 RTSP/default -> passive policy。
  - 自家 low_latency + external control -> `EXTERNAL_ONLY` 或灰度 `BOTH`。
  - smooth -> conservative，但不启用 UDP。
- diagnostics：
  - 低频 diagnostics 可开关；packet diagnostics release 默认 false。
  - diagnostics listener 缺失或异常不影响普通 RTSP prepare 和状态。
  - `RtspTransportDiagnostics`、`RtspRtpLossDiagnostics` 和 `RtspRecoveryDiagnostics` 可序列化到 `/debug/state.rtsp` 摘要。
- `LiveFeedbackController`：
  - `sequence_gap/corrupted_au/wait_idr_timeout/udp_no_packet` reason 映射稳定。
  - `keyframe_request` 携带 `lastRtpSequence/lastRtpTimestamp/lossRate5s/transportMode`。
  - request/recovery/source/recovered 去重限流，不按每包发送。

### QA / 真机 / 弱网

UDP 必须单独建报告目录：

```text
qa/reports/receiver/live-udp-low-latency/<timestamp>/
```

每次报告必须包含：

- sender stdout/stderr 和 control channel trace。
- receiver `/debug/state.rtsp`、`/debug/live/latency`、`/debug/logs`。
- network profile：loss%、jitter、delay、reorder%、短断流时长、限速。
- transport timeline：requested strategy、actual mode、fallback reason、fallback count。
- recovery timeline：gap/corrupted AU、WAIT_IDR start、keyframe_request、ack、idr_emitted、idr_recovered。
- visible result：是否花屏、冻结时长、rebuffer 次数。
- TCP baseline 同条件对比。

必须覆盖矩阵：

| 场景 | FORCE_UDP | AUTO_UDP_THEN_TCP | 期望 |
| --- | --- | --- | --- |
| 0% loss baseline | 必测 | 必测 | 延迟不高于 TCP，不卡死 |
| 1% random loss | 必测 | 必测 | 不花屏，WAIT_IDR 200-500ms 恢复 |
| 3% random loss | 必测 | 必测 | 可冻结，不能花屏；AUTO 可保持或降级 |
| 8% sustained loss 2s | 可测 | 必测 | AUTO fallback TCP 或 rebuild，不无限 IDR |
| 30-80ms jitter | 必测 | 必测 | reorder window 不引入持续排队 |
| 5% reorder | 必测 | 必测 | 小乱序可恢复，超窗 WAIT_IDR |
| UDP no packet | 必测 | 必测 | FORCE_UDP 报错/不可达；AUTO fallback TCP |
| IDR burst | 必测 | 必测 | pacing 生效，pending 不爆 |
| control channel down | 必测 | 必测 | RTCP fallback 可用，不阻塞播放器 |

### 性能门禁

- 普通 RTSP `EXOPLAYER_DEFAULT + diagnostics off` 与改造前对比：首帧、稳态 CPU、内存、buffer、错误率无可测回退。
- 低频 diagnostics on：不能出现主链路性能回退；回调频率受状态变化控制。
- packet diagnostics on：只允许 debug/实验开关，必须记录对象分配、日志量、ring buffer 容量和开启时长。
- `LegacyExoPlayerAdapter` 拆分后，adapter 主类目标降到 `< 2000` 行；新增类单类 `< 800` 行，单函数 `< 300` 行。

## ExoPlayer fork 侧任务

### Review 原则

ExoPlayer fork 会话 review 和实现时必须先确认：

- 所有改动默认不改变官方 `RtspMediaSource.Factory` 的普通 RTSP 行为。
- `RtcpFeedbackPolicy.DEFAULT` 保持 passive，不自动 PLI/FIR，不自动 WAIT_IDR，不自动启用 packet diagnostics。
- 新增 listener、stats、diagnostics 默认 no-op；只有 Cast-SDK 显式 low-latency strategy 才启用。
- `EXOPLAYER_DEFAULT` 下不调用 `setForceUseRtpTcp(true)`，也不注入低延迟 feedback policy。
- `FORCE_TCP`、`FORCE_UDP`、`AUTO_UDP_THEN_TCP` 都必须是 Cast-SDK 显式传入的策略，不由 fork 自己猜业务场景。
- 每包热路径只允许轻量计数和必要状态更新，不允许逐包日志、字符串构造、JSON、磁盘 IO、网络 IO 或阻塞跨线程调用。
- diagnostics / listener / stats 是可观测性能力，不是播放业务依赖；普通 RTSP 不能因为 diagnostics 缺失、关闭或异常而改变 transport 选择、缓冲策略、解码路径或错误状态。
- 高风险改动集中在 `library/rtsp`，不得触碰无关播放器模块；如果必须改 core / extractor / renderer，需要单独方案和回归矩阵。

P0：

1. 增加 transport diagnostics：UDP/TCP、fallback reason、UDP setup failed、UDP no-packet timeout。

2. sequence gap detection 与 RTCP requester 解耦：

```text
gap detection always on
RTCP send controlled by feedback strategy
```

3. UDP low-latency 下 `sequenceGapRequestThreshold=1`。

4. `EXTERNAL_ONLY` 下不发 RTCP，但必须完整触发：

- sequence gap event。
- AU corrupted。
- WAIT_IDR started。
- dropped until IDR。
- WAIT_IDR timeout。
- recovered。

5. H.264 AU 完整性守卫，坏 AU 不进 SampleQueue。

6. 补 RTSP 单测：

- UDP sequence gap。
- EXTERNAL_ONLY 不发 RTCP 但触发 recovery event。
- FU-A 缺 start/end。
- timestamp 变化但前 AU 没 marker。
- complete IDR 恢复。

P1：

1. 可配置 UDP reorder window，默认 `30ms`。

2. RTP no-packet timeout 上报，不直接重连；让 Cast-SDK adapter 决定 fallback/rebuild。

3. 5s loss sliding window。

4. fallback reason 上报到 diagnostics listener。

5. Render 链路 RTP timestamp join，支持 `capture_to_render` 稳态统计。

P2：

1. 轻量 NACK / selective retransmit 实验，只允许在 `30-50ms` deadline 内补当前 AU。

2. 超过 deadline 直接 WAIT_IDR。

3. FEC 或关键帧冗余实验。

4. Media3 对应 patch 可行性评估。

## 任务清单

| ID | 任务 | 归属 | 状态 | 验证方式 | 测试用例 |
| --- | --- | --- | --- | --- | --- |
| U1 | 定义 `LiveTransportStrategy` 和协议字段 | Cast-SDK receiver/sender | 已完成（Cast-SDK schema） | JVM/Kotlin 单测 | Receiver `player-api` 已新增 `LiveTransportStrategy` 和 wire name 单测；CastExtension invite/SCPD、Android sender `ProjectionInviteRequest` 和 SOAP 组参已补齐 |
| U2 | CastExtension capability 增加 UDP low-latency 能力 | Cast-SDK receiver | 部分完成 | JVM 单测 | capability 已暴露 transport strategy schema 和实验策略名；`supportsUdpLowLatency=false`、`udpExperimentEnabled=false`，避免 ExoPlayer fork API 未完成前误报可用能力 |
| U3 | ExoPlayer adapter 四态传输配置 | Cast-SDK receiver | 部分完成 | JVM 单测 + 真机 | default 已不调用 force TCP；`PlaybackSession` 可一次性下发 strategy；`/debug/state` 已输出 `liveTransportStrategy`；LOW_LATENCY/SMOOTH 当前仍降级到 FORCE_TCP；FORCE_UDP/AUTO 真正生效等待 fork 新 API |
| U4 | transport diagnostics 和 fallback reason | Cast-SDK receiver + ExoPlayer fork | 未开始 | JVM + RTSP 单测 | 复用现有 `onTransportReady` 输出 actual mode；新增 UDP setup failed、no-packet、server unsupported、fallback TCP reason |
| U5 | sequence gap detection 与 RTCP requester 解耦 | ExoPlayer fork | 未开始 | RTSP 单测 | EXTERNAL_ONLY 不发 RTCP，但进入 WAIT_IDR |
| U6 | H.264 AU 完整性守卫 | ExoPlayer fork | 未开始 | RTSP 单测 | 坏 FU-A、marker 缺失、timestamp 变化无 marker 不进 SampleQueue |
| U7 | UDP low-latency WAIT_IDR 恢复 | ExoPlayer fork + Cast-SDK receiver | 未开始 | RTSP/JVM 单测 | sequence gap 后 request IDR，完整 IDR 后 recovered |
| U8 | 独立控制通道扩展 RTP/loss metadata | Cast-SDK sender/receiver | 部分完成 | Rust/JVM 单测 | receiver `keyframe_request` 已携带 transportMode、lastRtpSequence、lastRtpTimestamp；lossRate5s、sender ack `nextIdrRtpTimestamp` 待补 |
| U9 | 发送端 UDP IDR pacing | Cast-SDK sender | 未开始 | Rust 单测 + 真机 | 大 IDR 不 burst，packet spacing 可观测 |
| U10 | UDP loss 5s 滑窗和降级策略 | ExoPlayer fork + Cast-SDK receiver | 未开始 | 弱网测试 | >8% 持续 2s fallback TCP |
| U11 | 弱网注入工具和报告 | QA/tools | 未开始 | dry-run + 真机 | 丢包、乱序、限速、短断流 |
| U12 | 真机 TCP/UDP A/B 验收 | QA/device | 未开始 | 10/30 分钟报告 | LK/H8/QZ 低延迟和 smooth 对比 |
| U13 | code review 和包体/性能评估 | 工程 | 部分完成 | review checklist | 本轮 Cast-SDK code review 已修正 capability 不能误报 UDP 已可用；`LegacyExoPlayerAdapter` 仍 >2000 行，diagnostics/recovery/rebuild 继续拆分 |
| U14 | 拆分 `LegacyExoPlayerAdapter` RTSP 职责 | Cast-SDK receiver | 部分完成 | JVM 单测不变 + review | 已抽 `LegacyRtspMediaSourceBuilder`、transport resolver、feedback policy resolver；diagnostics/recovery/rebuild 继续留待下一步拆 |
| U15 | 普通 RTSP strategy 迁移为 `EXOPLAYER_DEFAULT + passive` | Cast-SDK receiver | 已完成（单元级） | JVM 单测 + 普通 RTSP smoke | default 已不 force TCP、不启用 recovery/watchdog、feedback threshold=0；真机普通 RTSP smoke 待跑 |
| U16 | ExoPlayer fork transport strategy API | ExoPlayer fork | 未开始 | RTSP 单测 | 新增 additive API 表达 DEFAULT/FORCE_TCP/FORCE_UDP/AUTO；保留 `setForceUseRtpTcp(true)` 旧语义 |
| U17 | ExoPlayer fork recovery policy 拆分 | ExoPlayer fork | 未开始 | RTSP 单测 | gap/reset discontinuity 与 RTCP requester 解耦；RTCP 发送只由 feedback strategy 控制 |
| U18 | Diagnostics value object 拆分 | Cast-SDK receiver + ExoPlayer fork | 未开始 | JVM/RTSP 单测 | transport/loss/recovery 分开，普通 RTSP diagnostics off 下行为不变 |
| U19 | UDP 专项 QA 报告体系 | QA/tools | 未开始 | dry-run + 真机报告 | 新建 `qa/reports/receiver/live-udp-low-latency/`，输出 network/transport/recovery/visible timeline |
| U20 | 普通 RTSP 性能回归门禁 | QA/工程 | 未开始 | baseline 对比 | diagnostics off/on、packet diagnostics on 三档性能和日志量检查 |

## 给 ExoPlayer fork 会话的 review 任务

请 ExoPlayer 项目“开始RTSP改造”会话基于本地源码 `/Users/shenyingjun/Downloads/ExoPlayer` review 本文，重点输出：

1. 当前 ExoPlayer fork 已经具备哪些能力，哪些任务已完成或部分完成。
2. `EXOPLAYER_DEFAULT / FORCE_TCP / FORCE_UDP / AUTO_UDP_THEN_TCP` 四态设计是否能落在现有 `RtspMediaSource.Factory`、`RtspMediaPeriod`、`RtpDataLoadable` 和 UDP channel 结构上。
3. `sequence gap detection` 与 `rtcpFeedbackRequester` 解耦的实际代码落点，是否会影响普通 RTSP。
4. H.264 AU 完整性守卫和 WAIT_IDR 状态机的最小改动范围。
5. `EXTERNAL_ONLY` 不发 RTCP 但仍完整上报 recovery event 的实现方案。
6. 新增 diagnostics/listener/stats 对每包热路径的性能影响，必须给出避免对象分配、日志洪泛和主线程回调的约束。
7. 需要 Cast-SDK 配合的 API、policy、字段和测试。

Review 结论必须分成：

- 必须改文档。
- ExoPlayer fork 必须做。
- Cast-SDK 必须做。
- 需要真机/弱网验证。
- 明确不做或暂缓的项。

## ExoPlayer fork 会话 review 反馈

2026-07-08 已发送给 ExoPlayer 项目 `开始RTSP改造` 会话，threadId：`019f2677-6eb4-78f1-b61b-9b56dbc0a4fe`。会话只做 review，不改代码。

结论：

- 本文整体方向合理，对当前 ExoPlayer fork 源码的关键判断基本准确。
- `EXTERNAL_ONLY` 下的 P0 问题真实存在：`RtcpFeedbackPolicy.canSendRtcpFeedback()` 为 false 后，`RtpExtractor` 会把 `rtcpFeedbackRequester` 置空；`RtpPacketReorderingQueue` 当前 sequence gap / queue reset 的 discontinuity 设置又和 requester 绑定，导致不发 RTCP 时可能也漏掉 `WAIT_IDR` 入口。
- H.264 侧已有坏 AU 不提交、WAIT_IDR 丢非 IDR、完整 IDR + SPS/PPS 恢复的基础，但依赖 `onRtpStreamDiscontinuity()` 或 depacketize 错误入口。
- 四态 transport 设计可以落地且不破坏兼容，但 `FORCE_UDP` 不能只靠“不调用 `setForceUseRtpTcp(true)`”实现；当前这等价于 ExoPlayer 默认的 UDP 优先 + TCP fallback。

必须吸收进方案的调整：

- `EXOPLAYER_DEFAULT` 明确为 ExoPlayer 默认 transport：UDP 优先，允许 fallback TCP，不等于强制 UDP。
- `FORCE_UDP` 明确需要 ExoPlayer fork 新增禁用 TCP fallback 的能力；现有 public API 没有该语义。
- `sequence gap 检测永远启用` 的表述改为：只在显式 low-latency recovery policy 下始终生效；默认 policy 保持 passive，不能影响普通 RTSP。
- `marker 缺失` 的验收口径细化为 `timestamp change before previous AU marker`，避免为了单独 marker 判断在普通路径增加重型逐包重解析。
- diagnostics release gate 增加主线程高频回调禁止和 listener 异常隔离。

ExoPlayer fork 必做：

- 解耦 gap detection 和 RTCP 发送：`RtpPacketReorderingQueue` 要独立设置 `SEQUENCE_GAP/QUEUE_RESET` discontinuity；RTCP 发送只在 requester 非空且 policy 允许时发生。
- `QUEUE_RESET` 与 requester 同样解耦，避免 `EXTERNAL_ONLY` 下漏掉 recovery 入口。
- 保持 `RtcpFeedbackPolicy.DEFAULT` passive，不为了 UDP 改默认值。
- 增加低频 transport/fallback diagnostics：actual transport、fallback reason、UDP setup failed、UDP no-packet timeout；不得在 RTP hot path 做字符串、JSON 或 IO。
- `FORCE_UDP` 通过 additive API 落地，保留 `setForceUseRtpTcp(boolean)` 既有语义不变。

Cast-SDK 必做：

- 拆掉普通 RTSP 无条件 `setForceUseRtpTcp(true)` 的历史逻辑；普通 RTSP、第三方 live、default mode 必须走 `EXOPLAYER_DEFAULT`。
- 只有自家 low-latency live + capability + 实验开关 + independent control channel 齐全时，才启用 UDP 试验策略。
- `EXTERNAL_ONLY` 下由 Cast-SDK live control channel 消费 `WAIT_IDR/gap/recovered` 事件并发 `keyframe_request`；ExoPlayer 不在该策略下发 RTCP。
- 没有字段或不可信来源统一降级到 `EXOPLAYER_DEFAULT`；session 结束清理状态。

测试和验证补充：

- ExoPlayer 单测覆盖 `EXTERNAL_ONLY + sequence gap`：不发 RTCP，但触发 `onRtpStreamDiscontinuity -> WAIT_IDR`。
- ExoPlayer 单测覆盖 `EXTERNAL_ONLY + queue reset`：不发 RTCP，但进入 recovery。
- ExoPlayer 单测覆盖 `RTCP_ONLY/BOTH`：解耦后仍按 policy 发 RTCP。
- ExoPlayer 单测覆盖 `FORCE_UDP`：UDP unsupported/no-sample 不 fallback TCP。
- ExoPlayer 单测覆盖 `AUTO_UDP_THEN_TCP`：UDP unsupported/no-sample fallback TCP，并上报 reason。
- H.264 单测覆盖 FU-A 缺片、`timestamp change before previous AU marker`、WAIT_IDR 丢非 IDR、完整 IDR + SPS/PPS recovered。
- 真机/弱网覆盖 UDP 丢包、乱序、短断流、IDR burst、fallback TCP 后长稳；`FORCE_UDP` 只允许 QA 使用，不进普通用户路径。

暂缓项：

- P0 不做 NACK/FEC、可配置 reorder window、5s loss window、render 链路复杂统计。
- 先完成 gap/requester 解耦、FORCE_UDP/AUTO 语义、低频 diagnostics 和 H.264 recovery 单测，再进入 P1/P2。

## 分阶段实施

### P0：只做能力开关和 fork 基础修复

目标：

- 普通 RTSP 默认回归 `EXOPLAYER_DEFAULT`，不被低延迟改造影响。
- 自家 low-latency 当前稳定默认仍可保持 `FORCE_TCP`。
- UDP 只在测试开关下可启用。
- Cast-SDK 先完成 RTSP adapter 模块拆分，避免 UDP/TCP/recovery/diagnostics 继续耦合。
- ExoPlayer fork 保证 EXTERNAL_ONLY 下丢包检测和 WAIT_IDR 正常。
- 坏 AU 不进 SampleQueue。

完成标准：

- 普通 RTSP 单测和现有接收端 JVM 单测无回归。
- `LegacyExoPlayerAdapter` 主类不再直接承载 transport strategy、fork reflection、diagnostics aggregation、rebuild state 的全部实现。
- 普通 RTSP/default 单测证明不 force TCP、不启用 low-latency recovery、不启用 packet diagnostics。
- UDP gap/corruption 单测通过。
- 代码 review 确认默认路径未变化。

### P1：打通自家双端 UDP 恢复闭环

目标：

- Cast-SDK strategy/capability/invite/diagnostics 全链路打通。
- 独立控制通道携带 RTP/loss metadata。
- UDP no-packet 可 fallback TCP。
- 发送端 IDR pacing。

完成标准：

- 真机 FORCE_UDP 可播放。
- 注入 1% 丢包不花屏。
- WAIT_IDR 后 200-500ms 恢复。
- fallback TCP 正常。

### P2：弱网策略和质量建议

目标：

- UDP loss 滑窗。
- 降码率/降 fps 建议。
- 30 分钟和 2 小时长稳。

完成标准：

- UDP P95 延迟优于 TCP。
- 花屏 0 容忍。
- 高丢包能降级 TCP，不无限重试。

### P3：可选轻量 NACK/FEC 实验

目标：

- 只在 `30-50ms` deadline 内尝试补当前 AU。
- 不因为补包引入持续排队。

完成标准：

- 对比 WAIT_IDR-only 是否明显减少冻结。
- 如果收益不明显，不进入默认路线。

## 隔离和兼容性要求

必须满足：

- 默认 `PlaybackSession` 仍为 `default`。
- `default` 不启用低延迟 LoadControl，不启用 UDP，不启用 drop-until-idr。
- 普通 RTSP URL 即使是 live，也默认 `EXOPLAYER_DEFAULT`。
- 第三方 DMC 不携带自家 CastExtension capability 时不能启用 UDP。
- UDP 实验失败只影响当前自家 live session。
- session 结束后清理 transport strategy、recovery state 和 diagnostics 当前态。
- ExoPlayer fork `RtcpFeedbackPolicy.DEFAULT` 继续 passive。
- packet diagnostics 生产默认关闭。

## 性能和日志约束

所有新增上报和日志必须满足：

- 普通 RTSP 默认路径不启用 UDP transport diagnostics、packet diagnostics、WAIT_IDR recovery state machine 或 per-packet listener。
- 生产默认只保留低频聚合指标，不逐包写日志。
- 每个 RTP 包热路径不得做 JSON 拼接、字符串格式化、文件 IO、网络 IO、主线程高频回调或跨线程阻塞调用。
- 高频计数只能用轻量 counter / ring buffer；debug trace 必须有开关、容量上限和时间上限。
- `LiveFeedbackController` 只能消费聚合 recovery 事件，不能每包发控制消息。
- ExoPlayer fork 新 listener 默认 no-op；只有 Cast-SDK 显式配置低延迟策略后才注册。
- Cast-SDK 不得给普通 RTSP 注册 diagnostics listener；ExoPlayer fork 新增 diagnostics 分发点不得让 listener 异常改变普通 RTSP 播放状态，必要时只在低频边界做异常隔离并上报 debug 日志。
- 单元测试必须覆盖 `EXOPLAYER_DEFAULT` 下不会调用 `setForceUseRtpTcp(true)`、不会开启 low-latency feedback policy、不会开启 packet diagnostics。
- release gate：关闭 diagnostics 后，普通 RTSP 的 transport、buffer、decoder input、error/retry 行为必须与未改造前一致；打开低频 diagnostics 后不得出现可测的主链路性能回退；packet diagnostics 只能在 debug/实验开关下开启。

## 风险和待确认

- UDP 在家庭网络、老电视和盒子上可能被路由器、防火墙或系统策略影响，必须通过真机矩阵验证。
- UDP 低延迟可能降低延迟，但在高丢包环境会增加冻结和 IDR 请求频率。
- IDR pacing 和更频繁 IDR 请求可能增加码率尖峰，需要 sender 降码率策略配合。
- `FORCE_UDP` 不应面向普通用户，只能用于 QA 和调试。
- ExoPlayer fork 改动触达 H.264 depacketizer 和 SampleQueue 前路径，必须有充分单测和 code review。

## 当前进展摘要

- 2026-07-08：建立本 Roadmap，明确 UDP 路线为视觉可靠，不做字节可靠。
- 2026-07-08：复核初始代码确认 `LegacyExoPlayerAdapter` 曾对所有 RTSP 无条件 `setForceUseRtpTcp(true)`；目标方案调整为普通 RTSP 默认 `EXOPLAYER_DEFAULT`，自家 low-latency 当前稳定策略可显式 `FORCE_TCP`，UDP 仅自家 `low_latency + 实验开关` 启用。
- 2026-07-08：明确 ExoPlayer fork P0 是 sequence gap detection 与 RTCP requester 解耦、AU 完整性守卫和 EXTERNAL_ONLY recovery event。
- 2026-07-08：明确 Cast-SDK P0 是 strategy/capability/diagnostics 隔离，防止影响普通 RTSP 业务直播流。
- 2026-07-08：完成当前代码架构 review，确认 `LegacyExoPlayerAdapter`、`RtspPlaybackDiagnostics` 和 `LiveFeedbackController` 已承担过多职责；UDP 前必须先拆 RTSP transport strategy、fork API bridge、diagnostics collector、recovery state/rebuild、live control policy。
- 2026-07-08：确认现有测试只覆盖基础组件和 TCP baseline，UDP 需要独立单测/弱网/真机报告体系；新增 `live-udp-low-latency` 报告目录和 FORCE_UDP/AUTO 专项矩阵。
- 2026-07-08：Cast-SDK receiver/sender 单元级完成第一步改造：新增 `LiveTransportStrategy`；抽出 `LegacyRtspMediaSourceBuilder`、`LegacyRtspTransportStrategyResolver`、`LegacyRtspFeedbackPolicyResolver`；普通 RTSP `DEFAULT` 改为 `EXOPLAYER_DEFAULT + passive/no recovery`；`PlaybackSession` 已支持一次性 `LiveTransportStrategy` 下发；`/debug/state` 已输出 `liveTransportStrategy`；CastExtension invite/SCPD/capability 和 Android sender SOAP 组参已补齐；capability 当前不误报 UDP 已可用，`LOW_LATENCY/SMOOTH` 继续保留 `FORCE_TCP` 稳定路径；`keyframe_request` 已带 `transportMode/lastRtpSequence/lastRtpTimestamp`；`sh scripts/receiver-android-unit-tests.sh` 通过 792 个 JVM 测试，`:sender:android:sdk-kotlin:testDebugUnitTest` 通过。
