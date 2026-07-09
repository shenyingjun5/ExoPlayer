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

### L4.1 UDP 启动首帧 IDR 门控

结论：只靠发送端补 SPS/PPS 不能保证 UDP 启动不花屏。UDP low-latency 启动阶段必须由 ExoPlayer fork 在 H.264 reader 层先进入 `WAIT_IDR`，直到收到完整 `IDR + SPS/PPS` 才允许首个视频 AU 提交到 SampleQueue。

触发范围必须严格限定：

- 仅自家 `LOW_LATENCY + FORCE_UDP/AUTO_UDP_THEN_TCP` 路径启用。
- 普通 RTSP `EXOPLAYER_DEFAULT + DEFAULT feedback + listener null + packet diagnostics false + RtspBacklogRecoveryPolicy.DISABLED` 不启用。
- `FORCE_TCP` 低延迟稳定路径暂不默认启用 startup initial wait，避免影响 TCP 首帧速度；如后续 TCP 弱网证明也有启动花屏，再通过显式 policy 开关灰度。

最小闭环：

```text
prepare UDP low-latency RTSP media source
-> Cast-SDK 反射配置 ExoPlayer low-latency recovery policy + initialWaitForIdr
-> RtpH264Reader 初始化即 waitingForIdr=true
-> 首包/首 AU 如果是 P 帧或非完整 IDR，直接 drop，不提交 SampleQueue
-> 发送端每个 IDR AU start 前发送 SPS/PPS
-> 收到完整 IDR + SPS/PPS 后提交该 AU，exit WAIT_IDR
-> 后续 sequence gap / corrupted AU / queue reset 继续走现有 WAIT_IDR/drop-until-idr
```

ExoPlayer fork 建议落点：

- 放入现有 `RtspBacklogRecoveryPolicy`，不要放入 `RtcpFeedbackPolicy`，也暂不新增独立 H.264 policy，避免把“是否发 RTCP”和“是否启动期等 IDR”再次耦合。
- policy 字段建议拆成两个默认关闭的 boolean：`initialWaitForIdr` 和 `initialWaitForIdrAfterSeek`。
- Builder 增加 `setInitialWaitForIdr(boolean)` 和 `setInitialWaitForIdrAfterSeek(boolean)`；`DISABLED`、`LOW_LATENCY_DEFAULT`、`LOW_LATENCY` preset 第一阶段都保持 `false`，由 Cast-SDK UDP low-latency bridge 根据 transport strategy 显式打开。
- 由 `DefaultRtpPayloadReaderFactory` 将 policy 传入 `RtpH264Reader`，避免 Cast-SDK 直接依赖 H.264 reader 内部类型。
- `RtpH264Reader` 构造时如果 `initialWaitForIdr=true` 且 `lowLatencyRecoveryEnabled=true`，初始化 `waitingForIdr=true`，并使用 `WAITING_FOR_IDR` 或新增 `INITIAL_WAIT_FOR_IDR` reason 上报低频 diagnostics。
- `seek()` 默认不改变原行为；只有 `initialWaitForIdrAfterSeek=true` 时，seek/reset/re-PLAY 后重新进入 WAIT_IDR，不能让首个 P 帧进入 SampleQueue。
- 不在 RTP hot path 增加逐包日志、JSON、磁盘 IO、网络 IO 或阻塞回调。

Cast-SDK sender 配套：

- 实时发送路径已在每个 `frame.is_keyframe && frame.is_access_unit_start` 前发送 latest SPS/PPS。
- startup short-GOP cache 当前 `startup_gop_frame_count() = 1`，所以现状等价于 cache 首个 IDR 前有 SPS/PPS；但为了未来 short-GOP 多 AU 安全，cache 发送路径也必须改成每个 keyframe AU start 前都补 SPS/PPS。
- 不降低屏幕镜像验收指标，仍按 `1920x1080 @ 30fps / 7000kbps` 验证；SPS/PPS 开销很小，不作为降规格理由。

Cast-SDK receiver 配套：

- 只负责按 `LOW_LATENCY + FORCE_UDP/AUTO_UDP_THEN_TCP` 反射开启 `initialWaitForIdr=true` 和 `initialWaitForIdrAfterSeek=true`。
- 继续通过 `LiveFeedbackController` 消费 `WAIT_IDR started / droppedUntilIdr / timeout / recovered`，并通过独立 live control channel 请求 IDR。
- 不在 App 层做帧级判断，不绕过 ExoPlayer fork 的 H.264 AU 完整性守卫。

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
- 每个 IDR AU start 前必须带 SPS/PPS；包括启动首帧、startup short-GOP cache、周期 IDR、独立控制通道请求出来的恢复 IDR。
- 支持立即 `requestKeyFrame()`。
- 支持 startup/recovery short-GOP。
- UDP pacing，避免大 IDR burst。
- MTU 控制，RTP payload 建议 `1200-1400 bytes`，当前优先保持 `1200 bytes`。
- 不依赖 IP fragmentation。
- pending/backlog 有硬上限。
- 弱网下可降码率、降 fps。

IDR pacing：

- 一个大 IDR 不应瞬间打满 UDP socket。
- 不能通过降低屏幕镜像产品指标规避问题；屏幕镜像 UDP 验收仍按 `1920x1080 @ 30fps / 7000kbps` 执行。
- pacing 的目标是限制瞬时 burst，而不是降低平均码率。实测 1080p 屏幕 IDR 可到 `160-180KB`，按 `1200 bytes` RTP payload 会拆成约 `140-150` 个 UDP 包；如果 16ms 内瞬时发完，峰值会远高于 7Mbps，容易导致接收端拿不到完整 IDR。
- 第一阶段 sender 对 UDP 大 NAL burst 做动态分组 pacing：大 NAL 拆包数达到 `16` 包后，每 `8` 个 RTP 包按目标码率计算 sleep，默认 `pacingTargetBps = bitrateKbps * 1000 * 1.5`，`sleepUs = groupBytes * 8 * 1_000_000 / pacingTargetBps`，并 clamp 到 `1-8ms`。目标是把单个大 IDR 摊到几十毫秒级，优先保证 `WAIT_IDR -> 完整 IDR -> recovered`，后续根据真机矩阵调参。

### L6.1 累积延时治理策略

结论：累积延时不能靠缩短 IDR 请求间隔解决。`requestKeyFrame()` 只能制造新的可恢复点，不能清掉已经进入 RTP reorder queue、H.264 AU assembler、SampleQueue、decoder input 或 TCP socket buffer 的旧数据。低延迟路径必须把“丢旧数据”和“请求 IDR”拆成两个动作。

分层职责：

- 发送端负责不制造新积压：采集/编码前队列保留最新帧，RTSP publisher 队列限制在低延迟预算内，发送 socket 出现 backlog 时丢旧 P 帧并等待下一 IDR，UDP IDR 做 pacing。
- ExoPlayer fork 负责在显式 low-latency backlog/recovery policy 下清内部旧数据：TCP interleaved queue、RTP reorder queue、H.264 AU assembler 必须由播放器内部按策略 flush/reset/drop；这些队列不应只暴露给 Cast-SDK，因为 Cast-SDK 没有足够低层的包/AU 操作能力。普通 RTSP default 不启用该策略。
- Cast-SDK receiver 负责策略判断和业务闭环：根据 `rtpQueueMs/sampleQueueBufferedAheadMs/exoBufferedDurationMs/waitingForIdr/noPacket` 决定是否请求 IDR、是否 rebuild、是否上报上层；不能在普通 RTSP 默认路径启用 aggressive drop。
- 独立控制通道负责低频可靠反馈：只发送 `keyframe_request/recovery_state/source_unreachable/recovered`，不能逐包控制，也不能把每包 diagnostics 发送到上层。

低延迟模式阈值建议：

- `backlog < 500ms`：只观测，不触发恢复。
- `500ms <= backlog < 800ms`：进入 `warn_backlog`，发送端继续按实时队列丢旧 P 帧；接收端不立刻请求 IDR，避免 I 帧风暴。
- `backlog >= 800ms`：进入 `strong_recovery`，ExoPlayer fork 在 low-latency recovery policy 下执行 TCP interleaved queue flush、RTP reorder queue reset、H.264 `drop-until-idr`；Cast-SDK 同时通过独立控制通道请求 IDR。
- `strong_recovery` 持续 `1500ms` 且 `requestKeyFrame` 已尝试至少 `2` 次仍未恢复：Cast-SDK rebuild RTSP media source。
- `RTP no-packet >= 1500ms`：这不是普通延迟积累，而是断流或 TCP 卡死，直接走 no-packet rebuild / source unreachable，不继续只请求 IDR。
- `WAIT_IDR timeout >= 800ms`：说明有包但恢复点未到或不完整，继续按限流请求 IDR；超过 `1500-2000ms` 仍无完整 IDR，rebuild 或 fallback TCP。

发送端还可压缩的空间：

- capture callback 满队列时优先保留最新帧，而不是简单丢新帧。当前 macOS native callback 使用 `try_send`，channel 满时丢弃新来的 `EncodedNal`；低延迟屏幕镜像更适合 `latest-frame` 策略，避免 publisher 慢时持续发送旧帧。
- RTSP publisher 队列预算按模式收紧：低延迟屏幕目标不应超过 `150-300ms`，超过预算优先丢旧 P 帧；如果丢到破坏参考链，进入 `waiting_for_keyframe` 并触发 IDR。
- 发送端应把 `rawFrameDropCount`、`realtimeQueueDurationMs`、`droppedPFrameCount`、`droppedKeyframeCount`、`lastRtpSentAgeMs`、`udpPacingSleepMs` 上报到 Demo/CLI/diagnostics，用于判断延时是在发送端积累还是接收端积累。
- 动态 GOP 当前是 `1s -> 2s -> 1s` 恢复策略。稳定期可以减少常规 IDR 带来的 burst，但一旦收到 receiver backlog/WAIT_IDR 请求，必须马上回到 recovery GOP，并保证下一 IDR 带 SPS/PPS。
- 不通过降低 `1920x1080 @ 30fps / 7000kbps` 作为默认解决方案；弱网降码率/降 fps 只能作为自适应降级策略，并且要有明确 diagnostics 和用户/产品策略。

ExoPlayer fork 必须内部执行的动作：

- 新增显式 low-latency backlog/recovery policy，默认 `DISABLED`。普通 RTSP `EXOPLAYER_DEFAULT + RtcpFeedbackPolicy.DEFAULT + listener null + packet diagnostics false` 不启用 aggressive queue flush/drop。
- TCP interleaved 入口队列必须限长/限龄。`TransferRtpDataChannel` 当前使用无界 `LinkedBlockingQueue<byte[]>`，Cast-SDK 看不到这层积压；低延迟策略触发时不应随机逐包丢旧包后继续播放，而应整段 flush，触发 `QUEUE_RESET -> WAIT_IDR`。
- RTP packet queue 超龄或 queue reset 时，丢弃旧 packet，标记 discontinuity，并通知 H.264 reader 进入 `WAIT_IDR`。
- H.264 AU 发现 sequence gap、FU-A 缺片、timestamp 切换但上一 AU 无 marker 时，当前 AU 不进 SampleQueue，并进入 `drop-until-idr`。
- `drop-until-idr` 期间丢弃所有非 IDR AU；只有完整 IDR 且 SPS/PPS 可用后恢复。
- `RtspMediaPeriod` / SampleQueue 层清理放到第二阶段评估，不进入第一阶段。若后续必须做，只能在自家 low-latency policy 下通过受控 reset / rebuild-like 恢复，不能静默按 age 丢 sample。
- backlog strong 时 Cast-SDK 触发 `strong_recovery`，ExoPlayer policy 内部执行 queue reset / `drop-until-idr`，Cast-SDK 通过独立控制通道请求 IDR；两者近似原子，但不要求播放器 API 同步阻塞。
- 暴露低频聚合 diagnostics：`droppedUntilIdrCount`、`queueAgeMs`、`rtpQueueMs`、`sampleQueueMs`、`waitingForIdrDurationMs`、`idrRecoveredCount`、`lastRtpSequence/timestamp`。普通 RTSP 默认不注册 listener，不启用 packet diagnostics。
- `RtpH264Reader` FU-A sequence 异常的原有 `Log.w` 迁移为 diagnostics 聚合事件或限流事件；不在 RTP hot path 新增逐包日志、字符串格式化、JSON、IO 或阻塞回调。

ExoPlayer fork 侧建议阈值：

- TCP interleaved packet queue：`oldestPacketAgeMs > 150ms` 预警；`>= 300ms` 或 depth 约 `120-240` RTP packets 时 flush old packets，触发 `QUEUE_RESET/WAIT_IDR`。packet count 只能做保护上限，主判断按 oldest age / queue span，因为码率、MTU、帧类型都会改变 packet 数。
- RTP reorder queue：保留当前 `30ms` cutoff；低延迟下 `queueSpanMs > 100ms` 预警，`>= 200ms` reset。普通 RTSP 不启用该 reset 策略。
- H.264 reader：任何坏 AU 或 sequence discontinuity 都进入 `WAIT_IDR`；`WAIT_IDR >= 800ms` 触发 timeout diagnostics；`1500-2000ms` 仍未 recovered 由 Cast-SDK rebuild/fallback。

ExoPlayer fork 代码审查结论：

- 2026-07-08 已安排子 Agent 审查本地 `/Users/shenyingjun/Downloads/ExoPlayer`，只评估不改代码。
- 已确认 fork 现有能力包括：`RtspMediaSource.Factory` 的 diagnostics / feedback / policy / packet diagnostics API，`RtcpFeedbackPolicy.DEFAULT/LOW_LATENCY_DEFAULT/RTCP_ONLY/EXTERNAL_ONLY/BOTH`，`RtpExtractor` 的 `30ms` reorder cutoff，`RtpPacketReorderingQueue` 的 queue stats / sequence gap / reset，`RtpH264Reader` 的坏 AU 检测、`WAIT_IDR/drop-until-idr/recovered`，以及 `RtspMediaPeriod` 的 RTCP PLI/FIR 发送。
- 已确认必须由 fork 内部执行的安全恢复层：TCP interleaved packet queue 受控 flush、RTP reorder queue reset、H.264 AU assembler `WAIT_IDR/drop-until-idr`。SampleQueue 层风险最高，放到第二阶段评估；第一阶段不做通用 sample age 静默丢弃。Cast-SDK 不应逐包决定丢弃，只控制模式、阈值、IDR 请求主通道和 fallback。
- 已确认普通 RTSP 隔离要求：默认 `RtcpFeedbackPolicy.DEFAULT`、listener null、packet diagnostics false；aggressive queue flush、UDP gap threshold=1、drop-until-idr 强恢复只能在自家 low-latency 策略下启用。
- 2026-07-08 已让 ExoPlayer 项目“开始RTSP改造”会话 review 本节。反馈结论：总体方向正确，但 U26 必须改为分层、显式 policy、只在自家 low-latency live 生效；`RTP reorder` 和 `H.264 AU` 边界适合强恢复，`TCP interleaved` 应整段 flush 后进入 `WAIT_IDR`，`SampleQueue` 不应第一阶段静默按 age 丢。

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

#### Camera / Screen Live 验收运行规范

结论：所有 camera/screen 低延迟测试必须显式区分 TCP、FORCE_UDP 和 AUTO_UDP_THEN_TCP，不能用“默认 RTSP live 跑了一次”替代低延迟 UDP/TCP A/B。

公共约束：

- 测试对象必须是自家 live：`cast camera`、`cast screen`，或 macOS Demo 的“投送摄像头 / 投送屏幕”。普通 `cast rtsp --url` 只能算普通 RTSP smoke，不能算自家低延迟 live 验收。
- `playbackMode` 必须是 `low_latency`，接收端 buffer 必须是 `150/500ms`。
- 接收端必须走 `LEGACY_EXO`，不能走 `SYSTEM`。低延迟模式出现 `SYSTEM` route 时，本轮结果判为失败或无效，不能计入 UDP/TCP A/B。
- TCP baseline 必须实际确认 `transportMode=tcp-interleaved`。
- UDP strict 必须实际确认 `transportMode=udp`，且 `liveTransportStrategy=force_udp`。
- UDP preferred / fallback 测试必须实际确认 `liveTransportStrategy=auto_udp_then_tcp`；报告必须写清最终 `transportMode` 是 `udp` 还是 `tcp-interleaved`，以及 `transportFallbackCount` / fallback reason。
- CLI 默认 `--transport` 是 `rtsp-tcp`，所以测 UDP 必须显式带 `--transport rtsp-udp` 或 `--transport rtsp-udp-tcp`。
- macOS Demo UI 当前默认 transport 是“自动 UDP -> TCP”，但报告仍必须记录 UI 选项和接收端实际 `transportMode`；不能只按 UI 选择判断。
- frame trace 只在需要做端到端 trace join 或定位发送端积压时开启；基线性能测试默认关闭高频 trace，必要时单独标记“trace on”。

CLI 标准命令：

```bash
# camera TCP baseline, 720p30
cast-sender-cli cast camera --camera-id default --width 1280 --height 720 --fps 30 --bitrate-kbps 2500 --playback-mode low-latency --transport rtsp-tcp --hold-secs 600 --json

# camera UDP strict, 720p30
cast-sender-cli cast camera --camera-id default --width 1280 --height 720 --fps 30 --bitrate-kbps 2500 --playback-mode low-latency --transport rtsp-udp --hold-secs 600 --json

# camera AUTO UDP -> TCP, 720p30
cast-sender-cli cast camera --camera-id default --width 1280 --height 720 --fps 30 --bitrate-kbps 2500 --playback-mode low-latency --transport rtsp-udp-tcp --hold-secs 600 --json

# screen TCP baseline, 1080p30
cast-sender-cli cast screen --screen-id 0 --width 1920 --height 1080 --fps 30 --bitrate-kbps 7000 --playback-mode low-latency --transport rtsp-tcp --hold-secs 600 --json

# screen UDP strict, 1080p30
cast-sender-cli cast screen --screen-id 0 --width 1920 --height 1080 --fps 30 --bitrate-kbps 7000 --playback-mode low-latency --transport rtsp-udp --hold-secs 600 --json

# screen AUTO UDP -> TCP, 1080p30
cast-sender-cli cast screen --screen-id 0 --width 1920 --height 1080 --fps 30 --bitrate-kbps 7000 --playback-mode low-latency --transport rtsp-udp-tcp --hold-secs 600 --json
```

Demo 标准口径：

- 摄像头：`1280x720 @ 30fps / 2500kbps`，低延迟模式，buffer `150/500ms`。
- 屏幕：`1920x1080 @ 30fps / 7000kbps`，低延迟模式，buffer `150/500ms`。
- TCP baseline：传输选 `TCP`。
- UDP strict：传输选 `UDP`。
- AUTO：传输选 `自动 UDP -> TCP`。
- 每轮必须保存 Demo 状态区里的 `rtspConnected`、`transport`、`capture/pacer/encoded/rtp fps`、`keyframeRequests`、`socketBlocks/socketErrors`。

每轮最小采样点：

- start：开始播放后 10-30 秒。
- 5min：持续播放 5 分钟。
- 10min：持续播放 10 分钟。

每个采样点必须记录：

- sender：`rtspTransport`、`rtpSentFrameCount`、`rtpSentPacketCount`、`socketWriteErrorCount`、`socketWriteBlockCount`、`lastRtpSentAgeMs`、`keyframeRequestCount`、`liveControlKeyframeRequestCount/liveControlIdrEmittedCount`。
- receiver `/debug/state`：`playbackState`、`playerRoute`、`livePlaybackMode`、`liveTransportStrategy`、`hasPresentedFrame`、`videoSize`。
- receiver `/debug/live/latency`：`transportMode`、`packetReceivedCount`、`packetDroppedCount`、`sequenceGap`、`queueResetCount`、`rtpQueueMs`、`sampleQueueMs`、`firstRtpToPlayingMs`、`waitForIdrStartCount/recoveredCount/timeoutCount`、`transportFallbackCount`。
- receiver logs：必须确认没有 `fallback from=LEGACY_EXO to=SYSTEM`；低延迟 recovery 失败时应看到 `fallback-skipped reason=rtsp-live-system-fallback-disabled`。

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
- backlog/recovery diagnostics 只能在状态变化、flush/reset、WAIT_IDR start/end/timeout 等低频边界上报。
- packet diagnostics 仍仅 debug/实验短时开启；RTP hot path 禁止新增逐包日志、JSON、文件 IO、网络 IO 或阻塞跨线程回调。
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
| U2 | CastExtension capability 增加 UDP low-latency 能力 | Cast-SDK receiver | 已完成（动态能力） | JVM 单测 + debug endpoint | capability 根据 ExoPlayer fork API 反射动态暴露；无 fork API 时只暴露 `exoplayer_default/force_tcp` 且 `supportsUdpLowLatency=false`，当前 debug APK 集成 fork 后可暴露 `force_udp/auto_udp_then_tcp` |
| U3 | ExoPlayer adapter 四态传输配置 | Cast-SDK receiver | 已完成（Cast 侧） | JVM 单测 + Gradle 编译/单测 + 真机日志 | `RtspMediaSource.Factory#setRtspTransportStrategy(int)` 已反射接入；default 不 force TCP；`LOW_LATENCY + UdpExperimentEnabled` 可传 `FORCE_UDP/AUTO_UDP_THEN_TCP`；SMOOTH 仍归一到 `FORCE_TCP`；普通 RTSP 真机日志确认 `strategy=exoplayer_default forceTcp=false recovery=false feedback=false` |
| U4 | transport diagnostics 和 fallback reason | Cast-SDK receiver + ExoPlayer fork | 部分完成 | JVM + RTSP smoke | Cast-SDK 已反射接入 `RtspDiagnosticsListener#onTransportFallback(RtspTransportFallbackStats)` 并聚合到日志、`/debug/state.rtsp`、`/debug/live/latency`、live control recovery JSON；真机 TCP-only RTSP smoke 暴露 fork 在 `SETUP 461` 下未触发 fallback callback/重试 |
| U5 | sequence gap detection 与 RTCP requester 解耦 | ExoPlayer fork | 已完成（fork 侧） | RTSP 单测 | 当前 fork 已支持 EXTERNAL_ONLY 不发 RTCP 但仍设置 discontinuity reason，并进入 H.264 WAIT_IDR/recovery；Cast-SDK 文档不再把它作为待修 P0 |
| U6 | H.264 AU 完整性守卫 | ExoPlayer fork | 部分完成 | RTSP 单测 | 坏 FU-A、timestamp 变化无 marker、sequence discontinuity 不应进 SampleQueue；FU-A sequence 异常的原有 `Log.w` 需要迁移为 diagnostics 聚合或限流事件 |
| U7 | UDP low-latency WAIT_IDR 恢复 | ExoPlayer fork + Cast-SDK receiver | 部分完成 | RTSP/JVM 单测 | fork 已具备 sequence gap/discontinuity -> H.264 WAIT_IDR 的基础路径，Cast-SDK 已聚合 H.264 corrupted/WAIT_IDR/dropped/recovered/fallback diagnostics；`LOW_LATENCY + FORCE_UDP/AUTO` 已使用 `EXTERNAL_ONLY + sequenceGapRequestThreshold=1`；仍需补 low-latency backlog/recovery policy 下的受控 queue reset/flush 和弱网 integration |
| U8 | 独立控制通道扩展 RTP/loss metadata | Cast-SDK sender/receiver | 部分完成 | Rust/JVM 单测 | receiver `keyframe_request` 已携带 transportMode、lastRtpSequence、lastRtpTimestamp；lossRate5s、sender ack `nextIdrRtpTimestamp` 待补 |
| U9 | 发送端 UDP IDR pacing | Cast-SDK sender | 部分完成（动态 pacing 正常网络长跑通过） | Rust 单测 + GZ 真机 1080p/7Mbps | `RTP_MAX_PAYLOAD=1200`、`1920x1080@30fps/7000kbps` 产品指标和 GOP 策略保持不变；UDP 单个 NAL 拆包数达到 `16` 包后按 `8` 包一组做动态 pacing，默认按 `bitrateKbps * 1.5` 计算发送目标并 clamp 到 `1-8ms`；GZ camera AUTO 10 分钟正常网络长跑未出现长期 WAIT_IDR、timeout 或 fallback，弱网矩阵待补 |
| U10 | UDP loss 5s 滑窗和降级策略 | ExoPlayer fork + Cast-SDK receiver | 未开始 | 弱网测试 | >8% 持续 2s fallback TCP |
| U11 | 弱网注入工具和报告 | QA/tools | 未开始 | dry-run + 真机 | 丢包、乱序、限速、短断流 |
| U12 | 真机 TCP/UDP A/B 验收 | QA/device | 部分完成（GZ 正常网络三态和 camera 10 分钟通过） | 10/30 分钟报告 | GZ 已完成自家 camera TCP interleaved 基线；线上 Maven `2.19.1-labi.9` 新包下 screen `rtsp-tcp/rtsp-udp/rtsp-udp-tcp` 均可播放，camera `rtsp-udp-tcp` 正常网络进入 `udp` 并播放成功；camera AUTO 10 分钟长跑 `PLAYING -> STOPPED` 正常、`rebufferCount=0`、`queueResetCount=0`、`transportFallbackCount=0`；screen AUTO 能进入 `PLAYING + udp + 1920x1080`，但约 2 分钟 sender 进程终止，receiver no-packet rebuild 后连接 sender RTSP 端口得到 `ECONNREFUSED`；弱网、fallback TCP 和 30 分钟长稳待补 |
| U13 | code review 和包体/性能评估 | 工程 | 部分完成 | review checklist | 本轮 Cast-SDK code review 已修正 capability 动态暴露、普通 RTSP 隔离、fallback diagnostics 聚合；`LegacyExoPlayerAdapter` 仍 >2000 行，diagnostics/recovery/rebuild 继续拆分 |
| U14 | 拆分 `LegacyExoPlayerAdapter` RTSP 职责 | Cast-SDK receiver | 部分完成 | JVM 单测不变 + review | 已抽 `LegacyRtspMediaSourceBuilder`、transport resolver、feedback policy resolver；diagnostics/recovery/rebuild 继续留待下一步拆 |
| U15 | 普通 RTSP strategy 迁移为 `EXOPLAYER_DEFAULT + passive` | Cast-SDK receiver | 已完成（Cast 侧，smoke 暴露 Exo 问题） | JVM 单测 + 普通 RTSP smoke | default 已不 force TCP、不启用 recovery/watchdog、feedback threshold=0；真机普通 RTSP smoke 证明 Cast 侧隔离生效，但 TCP-only `mediamtx` 返回 `SETUP 461` 后 fork 未 fallback TCP，需 ExoPlayer 侧修复后复测 |
| U16 | ExoPlayer fork transport strategy API | ExoPlayer fork | 已完成（API 已推送，需修 fallback） | RTSP 单测 | branch `labi-rtsp-feedback-exoplayer-2.19.1` commit `36eea9b5b60bc88547ebace0d80811740286f4ca` 已提供 additive API 和 AAR；`EXOPLAYER_DEFAULT` 遇 `SETUP 461` 的 TCP fallback 需补真机/单测闭环 |
| U17 | ExoPlayer fork recovery policy 拆分 | ExoPlayer fork + Cast-SDK receiver | 已完成（正常网络真机集成通过） | RTSP/JVM 单测 + GZ 正常网络 | ExoPlayer `2.19.1-labi.9` 已发布 `RtspBacklogRecoveryPolicy`，默认 `DISABLED`；Cast-SDK 已新增 `LegacyRtspRecoveryPolicyBridge`，只在 `LOW_LATENCY` 路径反射启用，旧 artifact 不崩溃；GZ 日志确认 `rtsp low-latency recovery-policy configured=true`；`onRtspBacklogQueueReset` 已聚合到 recovery diagnostics，弱网触发待验证 |
| U18 | Diagnostics value object 拆分 | Cast-SDK receiver + ExoPlayer fork | 未开始 | JVM/RTSP 单测 | transport/loss/recovery 分开，普通 RTSP diagnostics off 下行为不变 |
| U19 | UDP 专项 QA 报告体系 | QA/tools | 部分完成 | dry-run + 真机报告 | 已建立 `qa/reports/receiver/live-udp-low-latency/`，新增 `20260709-labi9-gz/` 保存线上 Maven `2.19.1-labi.9` 的 GZ 三态、普通 RTSP smoke 和 camera AUTO 10 分钟证据；弱网/network profile、screen 长跑和 30 分钟报告待补 |
| U20 | 普通 RTSP 性能回归门禁 | QA/工程 | 部分完成（smoke） | baseline 对比 | GZ 普通 RTSP smoke 证明 Cast-SDK default 隔离生效且系统播放器兜底可播放；Exo `SETUP 461` fallback/retry 闭环和性能三档对比待补 |
| U21 | Rust/macOS sender live invite 下发 UDP transport strategy | Cast-SDK sender | 已完成 | Rust 单测 + GZ 真机复测 | `--transport rtsp-tcp/rtsp-udp/rtsp-udp-tcp/auto` 已映射到 `LiveTransportStrategy`、`UdpExperimentEnabled` 和 `TransportFallbackPolicy`；GZ 日志确认 `requestedStrategy=auto_udp_then_tcp` |
| U22 | Rust RTSP publisher connected UDP socket 发送修复 | Cast-SDK sender | 已完成 | Rust 单测 + GZ 真机复测 | 修复 connected UDP socket 上 `send_to` 触发 `Socket is already connected (os error 56)`，改为 `send`；`udp_rtp_write_uses_connected_socket_send` 通过；GZ screen/camera UDP 均通过 |
| U23 | low-latency RTSP live 控制层隔离修复 | Cast-SDK receiver | 已完成 | receiver JVM + GZ 远程 debug 复测 | 用户反馈接收端黑屏且出现控制条/菜单；代码侧已把 `LOW_LATENCY/AUTO_UDP_THEN_TCP` RTSP live 从 adapter runtime duration 误判 VOD 的路径隔离，并在 live 菜单键路径清理 remote/touch menu 内部状态；`receiver-android-unit-tests.sh` 通过 `794 tests`；GZ 新包 `20260708-172343-edbcc431` 已验证 camera/screen UDP 播放中 `PLAYING + hasPresentedFrame=true + videoSize=1280x720`，MENU 被拦截为 `live_menu_unsupported` toast，没有进入普通进度/倍速菜单 |
| U24 | ExoPlayer fork artifact 对齐 | ExoPlayer fork + Cast-SDK receiver | 已完成 | AAR class 检查 + Cast-SDK APK 构建 + GZ transport-ready 日志 | 远端 Maven `https://shenyingjun5.github.io/ExoPlayer` 已发布 `2.19.1-labi.9`；Cast-SDK 默认 `labiExoPlayerRtspVersion` 已切到 `2.19.1-labi.9`，Gradle 依赖解析确认 `exoplayer-rtsp:2.19.1-labi.9`；接收端 debug APK `20260709-002048-bbc718da` 已安装到 GZ，screen/camera 正常网络日志确认 `transport-ready mode=udp` |
| U25 | sender latest-frame 队列策略和 screen 长稳 | Cast-SDK sender | 未开始 | Rust 单测 + 1080p/7Mbps A/B + screen 10 分钟 | macOS capture callback 当前 channel 满时丢新帧；低延迟屏幕更适合保留最新完整 AU，丢旧 P 帧或旧 AU，避免 publisher 慢时继续发送旧画面；2026-07-09 GZ screen AUTO 长跑发现 sender 前台进程约 2 分钟收到终止，需补 sender 侧长稳日志/退出原因和 screen publisher 生命周期保护 |
| U26 | ExoPlayer 内部 low-latency recovery policy | ExoPlayer fork + Cast-SDK receiver | 已完成（正常网络真机集成通过，弱网待验证） | RTSP 单测 + Cast-SDK 集成 + GZ 正常网络 | ExoPlayer `2.19.1-labi.9` 已提供 `RtspBacklogRecoveryPolicy`、`LOW_LATENCY_DEFAULT/LOW_LATENCY`、`Factory#setRtspBacklogRecoveryPolicy(...)` 和 `onRtspBacklogQueueReset(...)`；Cast-SDK 只在 `LOW_LATENCY` 反射配置该 policy，普通 RTSP default 不启用；GZ 正常网络日志确认 `rtsp low-latency recovery-policy configured=true`，camera 10 分钟长跑出现 `waitForIdrStartCount=5/recoveredCount=5/waitForIdrTimeoutCount=0/queueResetCount=0`；`RtspBacklogRecoveryStats` 已聚合到 queue reset/recovery diagnostics 并触发独立控制通道恢复闭环，弱网触发待验证 |
| U26b | SampleQueue 受控清理评估 | ExoPlayer fork | 第二阶段暂缓 | RTSP 单测 + code review + 真机弱网 | 不做通用 sample age 静默丢弃；如 diagnostics 证明 SampleQueue 已形成不可追 backlog，再评估 low-latency policy 下停止 loader、reset queue、WAIT_IDR、完整 IDR 恢复的受控路径 |
| U26c | H.264 hot-path 日志迁移 | ExoPlayer fork | 未开始 | RTSP 单测 + 日志量检查 | 将 `RtpH264Reader` FU-A sequence 异常的原有 `Log.w` 迁移为 diagnostics 聚合或限流事件；不在 RTP hot path 新增逐包日志、字符串格式化、JSON、IO、阻塞回调 |
| U27 | Cast-SDK backlog 策略状态机收敛 | Cast-SDK receiver | 部分完成 | JVM 单测 + 真机弱网 | `LiveFeedbackController` 已在 `lastBacklogMs >= 800ms`、WAIT_IDR、sequence gap 等聚合事件上走独立控制通道请求 IDR；普通 RTSP default 不注册 recovery。仍需把 `500ms warn`、`1500ms rebuild` 和弱网退避 throttle 收敛成独立状态机并补真机弱网验证 |
| U28 | sender/receiver 累积延时诊断闭环 | Cast-SDK sender/receiver | 未开始 | CLI/Demo debug + 报告 | 同一报告里关联 sender `rawFrameDrop/realtimeQueueDuration/rtpSentAge/udpPacing` 和 receiver `rtpQueue/sampleQueue/exoBuffered/waitIdr`，判断积压发生在哪一端 |
| U29 | UDP startup initial WAIT_IDR 首帧门控 | ExoPlayer fork + Cast-SDK receiver | ExoPlayer artifact 已发布，等待 Cast-SDK 集成复测 | RTSP 单测 + Cast-SDK JVM + GZ UDP 启动复测 | ExoPlayer fork 已发布 `2.19.1-labi.11`，source commit `56a0ea8e6b`，tag `exoplayer-rtsp-2.19.1-labi.11`，gh-pages commit `a9b0c489b1`；`RtspBacklogRecoveryPolicy` 新增默认关闭的 `initialWaitForIdr` / `initialWaitForIdrAfterSeek` 和 Builder setter，`DISABLED` / `LOW_LATENCY_DEFAULT` / `LOW_LATENCY` preset 均保持 `false`；`DefaultRtpPayloadReaderFactory` 已传入 `RtpH264Reader`；H.264 reader 仅在 `lowLatencyRecoveryEnabled + explicit initialWaitForIdr` 下 startup 进入 `WAIT_IDR`，首包 P 帧、缺 SPS/PPS 的 IDR、非完整 IDR 不进 SampleQueue，完整 `SPS/PPS + IDR` 后恢复；`initialWaitForIdrAfterSeek=true` 可独立控制 seek/reset 后重新等待 IDR。验证：targeted RTSP 单测、`:library-rtsp:testDebugUnitTest`、`:library-rtsp:assembleRelease` 均通过；远端 RTSP AAR/POM HTTP 200，RTSP/core/HLS metadata `latest/release=2.19.1-labi.11`，远端 AAR `classes.jar` 经 `javap` 确认包含两个新 setter，未包含 Cast-SDK 类型。Cast-SDK receiver 已改成仅在 `LOW_LATENCY + FORCE_UDP/AUTO_UDP_THEN_TCP` 反射启用，旧 artifact 缺 setter 时返回 `configured=false`；等待 Cast-SDK 切 Maven 并做 GZ UDP 启动复测 |
| U30 | startup short-GOP cache 每个 IDR 前补 SPS/PPS | Cast-SDK sender | 已完成（本地单测通过，真机待复测） | Rust 单测 + GZ UDP 启动复测 | `send_cached_startup_gop_to_client` 已从“整段 cache 前补一次 SPS/PPS”改为“cache 循环中每个 keyframe AU start 前都发 latest SPS/PPS”；新增 `cached_startup_gop_sends_parameter_sets_before_each_keyframe` 覆盖 `SPS/PPS/IDR/SPS/PPS/IDR` RTP 输出顺序；`cargo test -p cast-sender-live-rtsp` 通过 `46 tests`。GZ UDP 启动复测等待 ExoPlayer initial WAIT_IDR artifact |

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

已吸收的历史 review 结论：

- `EXOPLAYER_DEFAULT` 明确为 ExoPlayer 默认 transport：UDP 优先，允许 fallback TCP，不等于强制 UDP。
- `FORCE_UDP` 必须通过 ExoPlayer fork additive API 禁用 TCP fallback，不能只靠不调用 `setForceUseRtpTcp(true)`。
- `EXTERNAL_ONLY + sequence gap/queue reset` 下不发 RTCP 但仍进入 recovery 的能力已经在当前 fork 完成；文档和任务表不再把它作为待修 P0。
- H.264 单测口径保留 `timestamp change before previous AU marker`、FU-A 缺片、WAIT_IDR 丢非 IDR、完整 IDR + SPS/PPS recovered。
- diagnostics release gate 保留：主线程高频回调禁止、listener 异常隔离、RTP hot path 不做日志/JSON/IO。

2026-07-08 针对“累积延时治理”再次 review 的结论：

- 方案方向成立：累积延时不能只靠 `requestKeyFrame()`，必须有“丢旧数据 + 等完整 IDR 恢复”的闭环。
- U26 原表述“fork 在多层直接丢旧数据”过强，已调整为“显式 low-latency backlog/recovery policy 下分层 flush/reset/drop”。
- `TransferRtpDataChannel` TCP interleaved queue 可做限龄/限长，但不能随机逐包丢旧包后继续播放；低延迟策略触发时应整段 flush，触发 `QUEUE_RESET -> WAIT_IDR`。
- `RtpPacketReorderingQueue` 是更合适的 RTP backlog 控制点；建议增加 policy-gated `maxAge/maxSpan/maxDepth` reset。
- `RtpH264Reader` 是最正确的解码恢复边界；主恢复动作应收敛到 `WAIT_IDR/drop-until-idr`。
- `RtspMediaPeriod` / SampleQueue 风险最高，第一阶段不做通用 sample age 静默丢弃；该项放到第二阶段评估，若必须做，只能是显式 low-latency policy 下受控 reset / rebuild-like 恢复。
- `RtpH264Reader` FU-A sequence 异常的原有 `Log.w` 不应保留为 release hot-path 高频输出；后续迁移为 diagnostics 聚合或限流事件，由 Cast-SDK 统一纳入 SDK 日志和 debug 报告。
- 新增策略建议独立于 `RtcpFeedbackPolicy`，例如 `RtspBacklogRecoveryPolicy` 或 `RtpRecoveryPolicy`，默认 `DISABLED`；Cast-SDK 只在自家 low-latency live 会话通过 reflection 打开。
- 阈值保留为 Cast-SDK receiver 状态机口径：`500ms warn / 800ms strong / 1500ms rebuild`；ExoPlayer 默认不使用这些阈值。
- `400ms` keyframe request throttle 可作为自家局域网低延迟默认；弱网下 Cast-SDK 状态机应允许退到 `800-1000ms`，避免 IDR storm。
- TCP interleaved packet count 只能作为保护上限，主判断应按 oldest age / queue span。

测试和验证补充：

- 普通 RTSP default 行为回归：不 force TCP、不启用 low-latency recovery、不启用 packet diagnostics、不启用 backlog recovery policy。
- low-latency recovery policy 打开/关闭 A/B。
- TCP interleaved queue flush 后必须进入 `QUEUE_RESET -> WAIT_IDR`。
- RTP reorder max age/span/depth reset 后必须进入 discontinuity / WAIT_IDR。
- corrupted P frame / bad AU -> drop non-IDR -> decodable IDR recovered。
- 真机/弱网覆盖 UDP 丢包、乱序、短断流、IDR burst、fallback TCP 后长稳；`FORCE_UDP` 只允许 QA 使用，不进普通用户路径。

## 分阶段实施

### P0：只做能力开关和 fork 基础修复

目标：

- 普通 RTSP 默认回归 `EXOPLAYER_DEFAULT`，不被低延迟改造影响。
- 自家 low-latency 当前稳定默认仍可保持 `FORCE_TCP`。
- UDP 只在测试开关下可启用。
- Cast-SDK 先完成 RTSP adapter 模块拆分，避免 UDP/TCP/recovery/diagnostics 继续耦合。
- ExoPlayer fork 保证 EXTERNAL_ONLY 下丢包检测和 WAIT_IDR 正常。
- 坏 AU 不进 SampleQueue。
- 不做 `RtspMediaPeriod` / SampleQueue 通用清理；该项进入第二阶段评估。
- 迁移 H.264 hot-path 异常日志到 diagnostics 聚合或限流事件，避免弱网连续坏包时 release 日志洪泛。

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
- H.264 malformed / FU-A sequence 异常等连续坏包事件不得在 release hot path 高频 `Log.w`；应走 diagnostics 聚合事件或限流事件，由 Cast-SDK 统一纳入 SDK 日志和 debug 报告。
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
- ExoPlayer fork 第一阶段改动只触达 TCP interleaved queue、RTP reorder queue 和 H.264 depacketizer/AU assembler 的 low-latency policy 路径，必须有充分单测和 code review。
- `RtspMediaPeriod` / SampleQueue 清理属于第二阶段风险项，只有 diagnostics 证明旧 sample 已成为累积延时主因时才评估；任何实现都必须限制在自家 low-latency policy 下，普通 RTSP default 不启用。

## 当前进展摘要

- 2026-07-08：建立本 Roadmap，明确 UDP 路线为视觉可靠，不做字节可靠。
- 2026-07-08：复核初始代码确认 `LegacyExoPlayerAdapter` 曾对所有 RTSP 无条件 `setForceUseRtpTcp(true)`；目标方案调整为普通 RTSP 默认 `EXOPLAYER_DEFAULT`，自家 low-latency 当前稳定策略可显式 `FORCE_TCP`，UDP 仅自家 `low_latency + 实验开关` 启用。
- 2026-07-08：明确 ExoPlayer fork P0 是 sequence gap detection 与 RTCP requester 解耦、AU 完整性守卫和 EXTERNAL_ONLY recovery event。
- 2026-07-08：明确 Cast-SDK P0 是 strategy/capability/diagnostics 隔离，防止影响普通 RTSP 业务直播流。
- 2026-07-08：完成当前代码架构 review，确认 `LegacyExoPlayerAdapter`、`RtspPlaybackDiagnostics` 和 `LiveFeedbackController` 已承担过多职责；UDP 前必须先拆 RTSP transport strategy、fork API bridge、diagnostics collector、recovery state/rebuild、live control policy。
- 2026-07-08：确认现有测试只覆盖基础组件和 TCP baseline，UDP 需要独立单测/弱网/真机报告体系；新增 `live-udp-low-latency` 报告目录和 FORCE_UDP/AUTO 专项矩阵。
- 2026-07-08：Cast-SDK receiver/sender 单元级完成第一步改造：新增 `LiveTransportStrategy`；抽出 `LegacyRtspMediaSourceBuilder`、`LegacyRtspTransportStrategyResolver`、`LegacyRtspFeedbackPolicyResolver`；普通 RTSP `DEFAULT` 改为 `EXOPLAYER_DEFAULT + passive/no recovery`；`PlaybackSession` 已支持一次性 `LiveTransportStrategy` 下发；`/debug/state` 已输出 `liveTransportStrategy`；CastExtension invite/SCPD/capability 和 Android sender SOAP 组参已补齐；capability 当前不误报 UDP 已可用，`LOW_LATENCY/SMOOTH` 继续保留 `FORCE_TCP` 稳定路径；`keyframe_request` 已带 `transportMode/lastRtpSequence/lastRtpTimestamp`；`sh scripts/receiver-android-unit-tests.sh` 通过 792 个 JVM 测试，`:sender:android:sdk-kotlin:testDebugUnitTest` 通过。
- 2026-07-08：ExoPlayer fork `labi-rtsp-feedback-exoplayer-2.19.1` 已拉到 commit `36eea9b5b60bc88547ebace0d80811740286f4ca`，并重新发布本地 Maven 产物到 `/Users/shenyingjun/Downloads/ExoPlayer/buildout/labi-maven-repo`；AAR 已确认包含 `RtspTransportStrategy`、`RtspTransportFallbackStats`、`RtspTransportFallbackReason` 和新版 `RtspDiagnosticsListener`。
- 2026-07-08：Cast-SDK 已反射接入 `RtspMediaSource.Factory#setRtspTransportStrategy(int)`；`RtspDiagnosticsListener#onTransportFallback(RtspTransportFallbackStats)` 已聚合到 `RtspRecoveryDiagnostics`、`/debug/state.rtsp`、`/debug/live/latency` 和 live control recovery JSON。H.264 malformed/corrupted/WAIT_IDR/dropped/recovered 继续走现有 diagnostics 聚合链路，新增 fallback 计数和最后一次 reason/from/to/elapsed 字段。
- 2026-07-08：Cast-SDK feedback policy 已按 transport strategy 分流：普通 RTSP `DEFAULT` 保持 passive；`LOW_LATENCY + FORCE_TCP` 继续使用 fork 低延迟 preset；`LOW_LATENCY + FORCE_UDP/AUTO_UDP_THEN_TCP` 使用 `EXTERNAL_ONLY + sequenceGapRequestThreshold=1`，由独立 live control channel 作为主 IDR 请求通道；`SMOOTH` 继续归一到 `FORCE_TCP`。
- 2026-07-08：验证结果：`sh scripts/receiver-android-unit-tests.sh` 通过 `793` 个 JVM 测试；本地 fork AAR 离线依赖下 `:receiver:android:sdk:player-exo-legacy:compileDebugJavaWithJavac` 通过；`:receiver:android:sdk:player-exo-legacy:testDebugUnitTest --tests com.castsdk.receiver.player.exolegacy.LegacyExoPlayerAdapterTest` 通过；`:receiver:android:sdk:player-exo-legacy:testDebugUnitTest` 通过；`:receiver:android:app:assembleDebug -PlabiDebugUseReleaseSigning=true` 通过并安装到设备 `8bd91cfe0421`。
- 2026-07-08：普通 RTSP 真机 smoke 使用本地 `mediamtx` TCP-only server 和 `rtsp://192.168.1.5:8554/cast-sdk-basic`。结果：Cast-SDK 普通路径隔离生效，日志显示 `strategy=exoplayer_default forceTcp=false recovery=false feedback=false packetDiagnostics=false`；但播放失败，ExoPlayer 报 `SETUP 461`，未观察到 `onTransportFallback` 或 TCP retry 成功。该问题需要 ExoPlayer fork 侧复核 `EXOPLAYER_DEFAULT/AUTO_UDP_THEN_TCP` 遇 UDP unsupported 的 fallback 回调和重试链路，修复后重跑普通 RTSP smoke。
- 2026-07-08：low-latency UDP integration 已完成 GZ 正常网络 screen/camera 基线，但弱网和 WAIT_IDR 恢复尚未完成。普通 `EXOPLAYER_DEFAULT` 的 UDP unsupported fallback 仍有 ExoPlayer fork 缺口；它影响普通 TCP-only RTSP 的 Exo fallback 复测，不阻塞自家 sender UDP 正常网络链路。
- 2026-07-08：按用户要求暂停 ADB 真机测试；后续先继续 Cast-SDK 代码集成、单元测试、文档和 code review，不再扩大设备侧验证。
- 2026-07-08：GZ 远程安装包 `20260708-151823-edbcc431` 后完成无 ADB 真机复测，报告目录 `qa/reports/receiver/live-udp-low-latency/20260708-gz-manual/`。普通 RTSP smoke 用户可见播放成功，但路径为 `LEGACY_EXO SETUP 461 -> SYSTEM fallback`，因此只算普通业务可播放和 Cast-SDK default 隔离通过，不算 Exo default fallback 通过。
- 2026-07-08：GZ 自家 camera low-latency live 基线通过：CastExtension `SetLivePlaybackMode(low_latency)` 和 `StartProjectionInvite` route hint 生效，接收端从 `SYSTEM` 切到 `LEGACY_EXO`；`/debug/live/latency` 显示 `transportMode=tcp-interleaved`、`packetReceivedCount=6452`、`packetDroppedCount=0`、`sequenceGap=0`、`queueResetCount=0`、`firstRtpToFirstDecodableVideoAccessUnitMs=99`、`firstRtpToPlayingMs=207`、`firstRtpToVideoSizeMs=239`、`accessUnitReadyCount=570`、`decoderInputCount=570`、`renderedFrameEventCount=559`。
- 2026-07-08：修复 Rust/macOS sender `StartProjectionInvite` 未下发 UDP transport strategy 的缺口。`cast-sender-cli` 的 `rtsp-tcp/rtsp-udp/rtsp-udp-tcp/auto` 已映射到 receiver `LiveTransportStrategy`、`UdpExperimentEnabled` 和 `TransportFallbackPolicy`；GZ 日志确认 `requestedStrategy=auto_udp_then_tcp`，接收端进入 `transport-ready mode=udp`。
- 2026-07-08：首轮 GZ UDP 进入 media path 后仍黑屏，根因是 sender RTSP publisher 在 connected UDP socket 上调用 `send_to`，macOS 返回 `Socket is already connected (os error 56)`，导致 `rtpSentPacketCount=0`、receiver `packetReceivedCount=0`、两次 first-packet timeout 后进入 `ERROR`。已修复为 UDP connected socket 使用 `send`，并新增 `udp_rtp_write_uses_connected_socket_send` 单测。
- 2026-07-08：GZ screen UDP 正常网络复测通过，报告目录 `qa/reports/receiver/live-udp-low-latency/20260708-gz-udp-screen-after-send-fix/`。sender `rtpSentFrameCount=645`、`rtpSentPacketCount=1991`、`socketWriteErrorCount=0`、`rtspTransport=udp`；receiver `/debug/live/latency` 显示 `transportMode=udp`、`packetReceivedCount=1993`、`packetDroppedCount=0`、`sequenceGap=0`、`firstRtpToFirstDecodableVideoAccessUnitMs=75`、`firstRtpToPlayingMs=91`、`firstRtpToVideoSizeMs=148`，播放期间进入 `PLAYING`。
- 2026-07-08：GZ camera UDP 正常网络复测通过，报告目录 `qa/reports/receiver/live-udp-low-latency/20260708-gz-udp-camera-after-send-fix/`。sender `rtpSentFrameCount=1058`、`rtpSentPacketCount=6057`、`socketWriteErrorCount=0`、`rtspTransport=udp`；receiver `/debug/live/latency` 显示 `transportMode=udp`、`packetReceivedCount=6069`、`packetDroppedCount=0`、`sequenceGap=0`、`firstRtpToFirstDecodableVideoAccessUnitMs=67`、`firstRtpToPlayingMs=124`、`firstRtpToVideoSizeMs=164`，播放期间进入 `PLAYING`。
- 2026-07-08：针对 GZ 现场反馈“接收端黑屏且有控制条/菜单”，本地修复 low-latency RTSP live UI 隔离：`PlaybackSession` 只在 `LOW_LATENCY` 或显式 live transport strategy 下把无 declared duration 的 RTSP 固定为 live，不改变普通 RTSP VOD adapter duration 行为；`PlaybackOverlayView` 在 live 菜单键路径先拦截并关闭 remote/touch menu 状态。`receiver-android-unit-tests.sh` 通过 `794 tests`；使用线上 `2.19.1-labi.7` 构建的 APK `20260708-171431-edbcc431` 可播放并拦截 MENU，但实际 transport fallback 为 `tcp-interleaved`，原因是该 AAR 缺新 transport strategy API。
- 2026-07-08：已用 ExoPlayer fork commit `36eea9b5b60bc88547ebace0d80811740286f4ca` 临时发布本地 Maven `2.19.1-labi.8-local` 到 `/private/tmp/labi-exoplayer-maven`；AAR 已确认包含 `RtspTransportStrategy`、`RtspTransportFallbackStats`、`RtspTransportFallbackReason`；Cast-SDK 使用 `-PlabiExoPlayerMavenUrl=file:///private/tmp/labi-exoplayer-maven -PlabiExoPlayerRtspVersion=2.19.1-labi.8-local` 构建签名 debug APK `20260708-172343-edbcc431` 成功，SHA256 `584104803334cd46c06e2a57891e304c23cbe3d501f005bf49f89c3c29e3aa27`，release key SHA-256 `5cf0b0966a7cbda36ddf23a79560a4cb6b668ae2697e8127ccdc73b6e4c7ffb6`。该包已安装到 GZ 并完成下面 camera/screen UDP 复测。
- 2026-07-08：GZ 新包 `20260708-172343-edbcc431` camera UDP/AUTO 复测通过。播放中 `/debug/state` 显示 `PLAYING`、`surfaceValid=true`、`hasPresentedFrame=true`、`videoSize=1280x720`、`livePlaybackMode=low_latency`、`liveTransportStrategy=auto_udp_then_tcp`；`/debug/live/latency` 显示 `transportMode=udp`、`packetReceivedCount=14386`、`packetDroppedCount=0`、`sequenceGap=0`、`firstRtpToFirstDecodableVideoAccessUnitMs=33`、`firstRtpToPlayingMs=40`、`firstRtpToVideoSizeMs=137`、`renderedFrameEventCount=1238`；sender `cameraBeforeStop` 显示 `lastSocketWriteError=null`、`socketWriteErrorDelta=0`。播放中发送 MENU 后日志出现 `live_menu_unsupported`，没有进入普通控制菜单。
- 2026-07-08：GZ 新包 `20260708-172343-edbcc431` screen UDP/AUTO 复测通过。播放中 `/debug/state` 显示 `PLAYING`、`surfaceValid=true`、`hasPresentedFrame=true`、`videoSize=1280x720`；停止后 `/debug/live/latency` 保留 `transportMode=udp`、`packetReceivedCount=4134`、`packetDroppedCount=0`、`sequenceGap=0`、`firstRtpToFirstDecodableVideoAccessUnitMs=254`、`firstRtpToPlayingMs=255`、`firstRtpToVideoSizeMs=417`、`renderedFrameEventCount=1079`；sender 显示 `rtspTransport=udp`、`rtpSentPacketCount=4141`、`socketWriteErrorCount=0`。启动阶段出现一次 `WAIT_IDR -> IDR recovered`，`waitingForIdrDurationMs=210`，没有触发 fallback 或 rebuild。
- 2026-07-08：当前 UDP 结论边界：正常网络 GZ screen/camera 已通；还不能宣称弱网视觉可靠完成。仍需补丢包、乱序、限速、短断流、WAIT_IDR -> independent live control `keyframe_request` -> `idr_recovered`、fallback TCP 和 10/30 分钟长稳矩阵。
- 2026-07-08：按屏幕镜像产品指标 `1920x1080 @ 30fps / 7000kbps` 复测 UDP/AUTO WAIT_IDR 恢复链路。修复前 sender 端独立 live control 其实已通：`liveControlKeyframeRequestCount=42`、`liveControlKeyframeAckCount=42`、`liveControlIdrEmittedCount=42`，但 receiver 只有首个 `first-decodable-video-au`，第二次进入 WAIT_IDR 后长期不恢复，`droppedUntilIdr=990`、`waitingForIdrMs=36778`、`wait-idr-timeout=1`。根因不是没有请求 IDR，也不是发送端没放 IDR，而是 1080p 大 IDR 约 `165KB` 被连续 UDP burst 发出，接收端拿不到完整 IDR。
- 2026-07-08：sender RTSP publisher 已增加 UDP 大 NAL burst pacing，不改变 `1080p/7Mbps`、GOP、payload size 或 TCP interleaved 行为。修复后同参数 GZ 实测：actual transport `udp`；sender `liveControlKeyframeRequestCount=3`、`liveControlKeyframeAckCount=3`、`liveControlIdrEmittedCount=3`、`socketWriteErrorCount=0`；receiver `wait-idr-started=3`、`wait-idr-ended=3`、`wait-idr-timeout=0`，恢复耗时约 `130ms / 865ms / 153ms`。其中一次 `865ms` 超出 `200-500ms` 目标，说明首版 pacing 已解决“长期拿不到完整 IDR”，但仍需继续调参和弱网矩阵验证。
- 2026-07-08：ExoPlayer/Cast-SDK roadmap 同步更新累积延时治理边界：`RtpH264Reader` FU-A sequence 异常的原有 `Log.w` 后续迁移为 diagnostics 聚合或限流事件；第一阶段 backlog flush 仅覆盖 low-latency policy 下 TCP interleaved queue、RTP reorder queue 和 H.264 AU assembler；`RtspMediaPeriod` / SampleQueue 受控清理放到第二阶段评估，不做普通路径 sample age 静默丢弃。
- 2026-07-08：已通知 ExoPlayer 项目 `开始RTSP改造` 会话按 U26 执行，实现默认关闭的 low-latency recovery/backlog policy，并在发布新版 Maven artifact 后通知 Cast-SDK 集成。Cast-SDK 侧已新增 `LegacyRtspRecoveryPolicyBridge`，只在 `LOW_LATENCY` 路径尝试反射接入 `RtpRecoveryPolicy` / `RtspRecoveryPolicy` / `RtspBacklogRecoveryPolicy` 或 boolean enable API；旧 Exo artifact 缺少该 API 时仅记录 `configured=false` 低频诊断，不影响播放。`sh scripts/receiver-android-unit-tests.sh` 已通过 `826` 个 JVM 测试。
- 2026-07-09：ExoPlayer fork 发布 `2.19.1-labi.9`，实现 commit `4535baa41d516b86a4efeee9942c34570ccab96d`，发布校验 commit `b15bd88659`，gh-pages commit `587d14cdc150c341f33b6cb7c7b1c44afb8b8143`。Cast-SDK 默认版本已切到 `2.19.1-labi.9`，并确认远端 Maven 解析到 `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.9`；`sh scripts/receiver-android-unit-tests.sh` 通过 `826` 个 JVM 测试，`:receiver:android:sdk:player-exo-legacy:compileDebugJavaWithJavac` 通过，`:receiver:android:app:assembleDebug -PlabiDebugUseReleaseSigning=true` 通过。GZ APK `20260709-002048-bbc718da` 已安装到 `192.168.1.11:2202`，`/debug/state` 确认运行 buildId 和 release key 签名一致。
- 2026-07-09：GZ 线上 Maven `2.19.1-labi.9` 正常网络集成复测通过，报告目录 `qa/reports/receiver/live-udp-low-latency/20260709-labi9-gz/`。screen AUTO 使用 `1920x1080 @ 30fps / 7000kbps`，receiver `transportMode=udp`、`packetReceivedCount=3263`、`sequenceGap=0`、`queueResetCount=0`、`rtpQueueMs=2`、`firstRtpToPlayingMs=82`；screen TCP 实际 `tcp-interleaved` 并播放成功；screen FORCE_UDP 实际 `udp` 并播放成功，`rtpQueueMs=0`；camera AUTO 实际 `udp` 并播放成功，`packetReceivedCount=3652`、`sequenceGap=0`、`queueResetCount=0`、`rtpQueueMs=0`、`firstRtpToPlayingMs=81`。日志确认 `rtsp transport-strategy configured=force_udp/auto_udp_then_tcp`、`rtsp low-latency recovery-policy configured=true`、`rtsp first-decodable-video-au ... type=IDR sps=true pps=true`。本轮没有注入丢包/乱序/限速/短断流，因此弱网、WAIT_IDR 恢复、fallback TCP 和长稳仍未完成。
- 2026-07-09：GZ 普通 RTSP smoke 使用本机 `mediamtx` 和 `rtsp://192.168.1.5:8554/cast-sdk-basic` 验证。Cast-SDK 普通路径隔离通过：日志显示 `requestedStrategy=exoplayer_default strategy=exoplayer_default`、`forceTcp=false`、`recovery=false`、`feedback=false`、`packetDiagnostics=false`；用户可见播放成功路径为 `LEGACY_EXO SETUP 461 -> SYSTEM fallback`，`/debug/state` 显示 `playbackState=PLAYING`、`playerRoute=SYSTEM`、`videoWidth=1280`、`videoHeight=720`、`hasPresentedFrame=true`。该结果证明 Cast-SDK default 隔离和系统兜底可用，但仍不代表 ExoPlayer fork default UDP unsupported fallback 闭环已完成。
- 2026-07-09：GZ camera AUTO 10 分钟正常网络长跑通过，命令参数保持 `1920x1080 @ 30fps / 7000kbps` 和 `rtsp-udp-tcp`。发送端 `playbackMonitor.errorCount=0`、`stoppedEarly=false`；停止后接收端 `playbackState=STOPPED`、`lastEvent=exo stop cleanup rtspPackets=188561 rtspTransport=udp`，播放期 `rebufferCount=0`。`/debug/live/latency` 最终显示 `transportMode=udp`、`packetReceivedCount=188561`、`packetDroppedCount=2`、`droppedBeforeEnqueueCount=2`、`sequenceGap=0`、`queueResetCount=0`、`maxQueueDepth=32`、`rtpQueueMs=1`、`sampleQueueMs=0`、`firstRtpToPlayingMs=71`、`firstRtpToFirstDecodableVideoAccessUnitMs=19`、`waitForIdrStartCount=5`、`recoveredCount=5`、`idrRecoveredCount=5`、`waitForIdrTimeoutCount=0`、`transportFallbackCount=0`。这证明正常网络下动态 pacing + recovery policy 没有造成长期卡死、fallback 或累积队列，但仍不是弱网验收。
- 2026-07-09：screen 10 分钟长跑继续验证。重新枚举后 `cast-sender-cli live screen list --json` 返回 `sources=[{id=1,name=Display 0 (1710x1112)}]`，使用 `--screen-id 1 --transport rtsp-udp-tcp --width 1920 --height 1080 --fps 30 --bitrate-kbps 7000` 可成功进入 GZ `PLAYING + LEGACY_EXO + transportMode=udp + videoSize=1920x1080`，启动期 `sequenceGap=0`、`queueResetCount=0`、`rtpQueueMs=1`、`firstRtpToPlayingMs=106-155`。但前台 sender 进程两次在约 `115-166s` 后收到终止，receiver 先表现为 `no-packet-timeout rebuild ageMs=1501/1654`，随后重连 `192.168.1.5:8554` 返回 `ECONNREFUSED` 并进入 `PLAYBACK_ERROR`。本地 `live screen start` 不投接收端也能复现前台进程 `143` 退出；`CAST_SCREEN_RTSP_TRACE=1` 显示退出前 screen capture、pacer、VideoToolbox encode 和 RTSP frame 输出仍持续正常，未看到 native 主动报错。`nohup` detached 方式因 macOS screen capture 后台运行限制/无交互上下文无法有效启动。结论：screen UDP 接收端启动链路已通，但 screen 10 分钟长稳阻塞在 macOS sender screen publisher 生命周期/进程终止原因，需 sender 侧补退出原因日志和长稳保护后复测。
- 2026-07-09：根据 UDP 启动初期花屏分析补充最小闭环最终方案：发送端每个 IDR AU start 前都带 SPS/PPS，含 startup short-GOP cache；ExoPlayer fork 在显式 `LOW_LATENCY + FORCE_UDP/AUTO_UDP_THEN_TCP` policy 下支持 startup initial WAIT_IDR，首包 P 帧或非完整 IDR 不进入 SampleQueue，完整 `IDR + SPS/PPS` 后恢复；Cast-SDK receiver 只负责反射配置 policy、聚合 diagnostics 和通过独立 live control channel 请求 IDR。已向 ExoPlayer `开始RTSP改造` 会话发送 review 并收到结论：`initialWaitForIdr` 放入 `RtspBacklogRecoveryPolicy`，新增 `initialWaitForIdr` / `initialWaitForIdrAfterSeek` 两个默认关闭开关，`LOW_LATENCY` preset 不默认开启，Cast-SDK 只在 UDP low-latency 路径显式启用。
- 2026-07-09：U29 ExoPlayer fork 侧实现和 `2.19.1-labi.11` Maven artifact 发布完成。source commit `56a0ea8e6b`，tag `exoplayer-rtsp-2.19.1-labi.11`，gh-pages commit `a9b0c489b1`；发布模块：`exoplayer-common/container/database/datasource/decoder/extractor/core/hls/rtsp`。新增 `RtspBacklogRecoveryPolicy.initialWaitForIdr` / `initialWaitForIdrAfterSeek` 和 Builder setter，preset 默认 false；`RtpH264Reader` 在显式 low-latency policy 下 startup/seek 可进入 `WAIT_IDR`，只允许完整 `IDR + SPS/PPS` 恢复提交。ExoPlayer 验证：targeted RTSP 单测、`:library-rtsp:testDebugUnitTest`、`:library-rtsp:assembleRelease` 均通过；远端 `exoplayer-rtsp-2.19.1-labi.11.aar` / `.pom` HTTP 200，AAR sha256 `33fd61dbca15833f01f45bb9badec28147654aea339128c265dd3367c5387723`，POM sha256 `9f4f711702c2e28b00898aa13a2c210f227a4695f02ea1bab7a8c2c1247594bf`；RTSP/core/HLS metadata `latest/release=2.19.1-labi.11`；远端 AAR `classes.jar` 经 `javap` 确认包含 `setInitialWaitForIdr(boolean)` / `setInitialWaitForIdrAfterSeek(boolean)` 和两个 public field，未包含 Cast-SDK 类型。Cast-SDK 侧已完成 sender startup short-GOP cache 每个 IDR 前补 SPS/PPS，以及 receiver `LegacyRtspRecoveryPolicyBridge` 对两个新 setter 的 UDP low-latency 专属反射配置；旧 artifact 缺 setter 时不走 fallback，避免误判初始门控已生效。Cast-SDK 验证：`cargo test -p cast-sender-live-rtsp` 通过 `46 tests`；`sh scripts/receiver-android-unit-tests.sh` 通过 `920 tests`。下一步切 Cast-SDK Maven 到 `2.19.1-labi.11`，构建 APK 并做 GZ UDP 启动花屏复测。
