# Live RTSP TCP Interleaved 低延迟专项 Roadmap

## 文档定位

本文是自家摄像头、屏幕和视频 live 使用 `RTP/RTCP over RTSP TCP interleaved` 时的低延迟专项执行文档，负责回答：

- TCP 为什么会随时间积累延迟。
- 发送端、Cast-SDK 接收端和 ExoPlayer fork 分别在哪里控制积压。
- 独立 live control channel、RTCP PLI/FIR、IDR、丢帧和 RTSP rebuild 如何协同。
- 下一步按什么顺序开发、测试、灰度和回滚。

总入口仍是 `2026-07-03-sender-live-latency-optimization-roadmap.md`；独立反馈协议以 `2026-07-07-live-independent-feedback-channel-roadmap.md` 为准；UDP 路线以 `2026-07-08-live-udp-low-latency-transport-roadmap.md` 为准。涉及 TCP 传输积压和恢复策略时，以本文为准。

## 背景和目标

TCP interleaved 能隐藏网络丢包并保证 RTP 字节按序到达，但不能保证低延迟。丢包重传、接收端读取变慢或发送端写入受阻时，旧视频字节会进入应用队列、系统 socket buffer、ExoPlayer RTP 队列、SampleQueue 和 MediaCodec。TCP 已经发送进流里的旧字节不能像 UDP 包一样跳过，因此只请求新 IDR 不能自动清除旧数据，新的 IDR 还可能排在旧字节后面。

本专项目标：

- 保持屏幕镜像产品验收规格 `1920x1080 @ 30fps / 7000kbps`，不能通过降规格掩盖问题。
- 正常网络下端到端延迟稳定，不随播放时间线性增长。
- 弱网或设备瞬时处理变慢时，优先保留最新画面；允许短暂冻结，不允许长期播放旧画面。
- 建立“发送端保鲜、接收端清旧、独立通道请求 IDR、必要时重建 media session”的完整闭环。
- 所有激进策略只在自家显式 `LOW_LATENCY` live session 生效。
- 普通第三方 RTSP、`DEFAULT` playback mode、视频点播、HLS、音乐和图片路径行为及性能保持不变。

## 不做范围

- 不修改普通第三方 RTSP 的默认 transport、buffer、feedback、diagnostics 或 fallback 行为。
- 不把 RTSP rebuild 作为正常抖动时的第一恢复手段。
- 不把 `0.5s GOP`、降码率、降分辨率作为 TCP 低延迟默认方案。
- 不依赖无限增大 `SO_SNDBUF` 或应用 pending buffer 来换取“流畅”。
- 不在 RTP packet hot path 增加逐包日志、JSON、文件 IO、网络 IO 或阻塞 callback。
- 不在 Cast SDK App/Demo UI 层实现播放器队列恢复状态机。
- 本阶段不自研完整 RTSP/TCP 播放栈。

## 相关文档

- `docs/plans/2026-07-03-sender-live-latency-optimization-roadmap.md`
- `docs/plans/2026-07-03-receiver-exoplayer-rtsp-feedback-fork-plan.md`
- `docs/plans/2026-07-05-live-end-to-end-latency-observability-plan.md`
- `docs/plans/2026-07-06-exoplayer-rtsp-loadcontrol-low-latency-analysis.md`
- `docs/plans/2026-07-07-live-independent-feedback-channel-roadmap.md`
- `docs/plans/2026-07-08-live-udp-low-latency-transport-roadmap.md`
- `docs/plans/2026-07-09-sender-live-frame-drop-backpressure-roadmap.md`
- `docs/receiver-android-architecture.md`
- `docs/sender-architecture.md`

## 当前代码和验证基线

### 已具备能力

发送端：

- `cast-sender-live-rtsp` 已对 media socket 设置 `TCP_NODELAY` 和 non-blocking write。
- 应用层 `pending_tcp_interleaved` 已有 `200ms` 存续时间硬门限。
- 当前动态字节预算按 `IDR access unit bytes * 2` 计算，并限制在 `256KB-2MB`。
- pending 超过时间或字节门限时不清半个 interleaved frame，而是返回 `ConnectionReset`，让上层重建 RTSP client。
- 发送失败、整帧跳过和参数集发送失败都会进入 `awaiting_keyframe`，丢 P 帧直到下一帧完整 IDR。
- macOS VideoToolbox 已实现自适应 GOP：启动/恢复阶段约 `1s`，稳定阶段约 `2s`，收到恢复请求后回到约 `1s`。
- 发送端已经支持独立 live control channel、TCP/UDP RTCP PLI/FIR 和 IDR 请求限流。

接收端：

- 自家 `LOW_LATENCY` 使用 `150/500/50/100ms` LoadControl，并通过 fork 把 loading floor 收敛到 `150ms`。
- ExoPlayer fork 已有 TCP interleaved backlog warn/reset、RTP reorder backlog reset、H.264 `WAIT_IDR`、timeout 和 recovered diagnostics。
- Cast-SDK 已有 `500ms` 预警、`800ms` 强恢复口径、RTP no-packet timeout 和 RTSP rebuild 兜底。
- 普通 RTSP 已拆到 `EXOPLAYER_DEFAULT + passive recovery`，显式 low-latency 配置只消费到下一次 prepare。

### 已确认缺口

1. 动态 TCP 字节预算仍主要由 IDR 大小决定，不等价于延迟预算。按 `7Mbps` 计算，`256KB` 约等于 `300ms` 媒体，`1MB` 约等于 `1.2s`，`2MB` 约等于 `2.4s`。
2. `SO_SNDBUF` 只能限制应用写入系统的空间，当前没有限制和观测 macOS kernel 中尚未发送的字节；应用 pending 为零不代表 media socket 没有旧数据。
3. macOS raw frame channel 满时当前 `try_send` 丢的是新帧，旧帧仍留在队列，和低延迟“最新帧优先”目标相反。
4. 本地 ExoPlayer `RtpExtractor` 对所有 transport 使用固定约 `30ms` reorder cutoff；TCP interleaved 本身按序，低延迟 TCP 没必要等待完整 UDP 乱序窗口。
5. ExoPlayer 的 RTP queue reset 不能保证清掉已进入 SampleQueue 的旧 access unit；只请求 IDR 可能继续播放数秒旧样本。
6. TCP low-latency 当前使用 `LOW_LATENCY_DEFAULT/BOTH`，ExoPlayer RTCP 和独立 live control channel 可能对同一恢复事件重复请求 IDR。
7. `LiveFeedbackController.TcpJsonClient` 当前把 `scheduled` 和 `throttled` 都折叠成 boolean success，无法证明发送端实际安排了 IDR。
8. live control client 当前同步读取 request response，等待 ACK 时可能读到并丢弃异步 `idr_emitted`，无法形成 `request -> ack -> idr_emitted -> receiver recovered` 关联。
9. `LegacyExoPlayerAdapter`、`LiveFeedbackController` 和 `cast-sender-live-rtsp/src/lib.rs` 已分别达到约 `3521/1037/3616` 行；继续把策略塞入大文件会扩大 TCP、UDP 和普通 RTSP 的耦合面。
10. 还没有完成 `1080p30/7Mbps` TCP 的动态视频、弱网、10/30 分钟延迟趋势验收，现有 720p TCP 数据不能替代产品规格验收。

### 现有验证证据

- `qa/reports/receiver/live-latency/20260706-232057/summary.md`：`1280x720@30 / 2500kbps / rtsp-tcp / low-latency` 的 20 秒基线，首 RTP 到首帧 `189ms`，但时长和规格不足以证明 TCP 长稳达标。
- `qa/reports/receiver/functional/20260710-123442/summary.md`：`1920x1080@30 / 7000kbps` UDP 动态视频暴露 SampleQueue 可积压到约 4 秒。该报告证明“只请求 IDR 不清 SampleQueue”是跨 transport 的播放器队列缺口，但不能作为 TCP 性能结果。
- 当前没有 `1920x1080@30 / 7000kbps / FORCE_TCP` 的 10/30 分钟动态视频报告；本文 T18 保持未开始。

## 根因模型

```text
capture frame queue
  -> VideoToolbox encoder
  -> RTP packetization
  -> sender application pending
  -> sender kernel TCP send queue
  -> network/TCP retransmission and congestion window
  -> receiver kernel TCP receive queue
  -> ExoPlayer interleaved packet queue
  -> RTP reorder queue
  -> H.264 access unit
  -> SampleQueue (压缩 H.264，位于 MediaCodec 之前)
  -> MediaCodec input/output
  -> Surface render
```

TCP 延迟治理不能只看一个 buffer。需要分别回答：

- 旧 raw frame 是否还在等编码。
- 旧 RTP 字节是否还在 sender app 或 kernel 中。
- 旧 packet/AU 是否已经进入 ExoPlayer。
- 旧 sample 是否已经排在 MediaCodec 前。
- 新 IDR 是否真的被安排、发出、收到并恢复。

## 方案取舍

| 方案 | 优点 | 缺点 | 适用性 |
| --- | --- | --- | --- |
| 只调小 LoadControl/GOP | 改动小 | 清不了 TCP/socket/SampleQueue 旧数据；可能增加 IDR burst | 不作为主方案，只保留 A/B 参数优化 |
| 只请求 IDR | 恢复参考链简单 | 新 IDR 可能排在旧 TCP 字节后；不会丢 SampleQueue 旧样本 | 只适合没有显著 backlog 的轻故障 |
| 发送端限流 + 接收端受控清队列 + 必要时 rebuild | 能同时控制积压源头和播放器旧数据 | 需要 Cast-SDK 与 ExoPlayer fork 协同，状态机更完整 | 推荐正式方案 |
| 默认改 UDP | 无 TCP 队头阻塞 | 引入丢包、AU 损坏和兼容性风险 | 独立 UDP Roadmap，不替代 TCP 专项 |
| 自研 RTSP player | 可完全控制所有队列 | 开发、兼容和维护成本高 | 长期路线，不进入本阶段 |

推荐采用第三种方案，并保留 TCP 作为自家 smooth 路径和 low-latency 的稳定 transport。UDP 继续按独立实验 Roadmap 灰度。

## 最终架构

### 会话边界

```text
普通 RTSP / DEFAULT
  EXOPLAYER_DEFAULT
  RtcpFeedbackPolicy.DEFAULT
  RtspBacklogRecoveryPolicy.DISABLED
  listener=null
  packet diagnostics=false

自家 LOW_LATENCY + FORCE_TCP/AUTO fallback 后的 TCP
  TCP low-latency transport policy
  bounded sender queues
  independent live control primary feedback
  ExoPlayer queue/backlog recovery enabled
  aggregate diagnostics enabled only when requested

自家 SMOOTH + FORCE_TCP
  stability-oriented buffers
  no aggressive SampleQueue catch-up
  source unreachable/rebuild protection retained
```

所有 TCP 专项开关必须同时校验：自家 capability、live session、`LOW_LATENCY` playback mode 和明确 transport。不能仅根据 URI 是 `rtsp://` 就启用。

### 恢复状态机

```text
NORMAL
  queue within target
  |
  | queue >= 500ms or trend rising
  v
WARN
  aggregate diagnostics + stop loading tendency
  |
  | queue >= 800ms sustained 300-500ms
  v
CATCH_UP
  stop loader / flush RTP packet queue / reset AU and SampleQueue
  enter WAIT_IDR
  request one IDR through independent channel
  |
  | complete IDR recovered within 800ms
  v
RECOVERED -> NORMAL

CATCH_UP / WAIT_IDR
  |
  | media socket still backlogged, no complete IDR, or queue remains > 800ms
  v
REBUILD
  close old RTSP media connection and buffers
  rebuild RTSP media source/session
  request fresh IDR
  |
  | first complete IDR rendered
  v
RECOVERED -> NORMAL
```

这里的 `500/800/300-500/800ms` 是首轮产品实验和验收参数，不是永久硬编码。后续根据设备性能、码率、queue slope、恢复耗时和长稳数据做配置化调整，但不能通过放大阈值容忍持续延迟。

## 发送端方案

### 1. raw frame 队列改为 latest-frame-wins

低延迟模式 channel 满时，不能继续保留旧 raw frame 并丢最新帧。目标语义：

- 优先淘汰最旧的未编码 P 候选帧。
- 保留最新帧，缩短 capture-to-encode age。
- 一旦丢帧可能破坏参考链，触发全局 IDR gate，后续从新 IDR 恢复。
- smooth/default 模式保持原策略，避免影响普通业务。

实现归属：`cast-sender-live-macos`，策略单独放入 frame backlog 模块，不继续扩充 `macos.rs` 主流程。

### 2. TCP backlog 改为时间预算主导

当前 `IDR bytes * 2` 只适合容纳大关键帧，不适合作为延迟上限。新预算由目标码率和允许积压时间直接计算：

```text
time_budget_bytes = target_bitrate_bps / 8 * target_pending_ms / 1000
atomic_write_floor_bytes = max_interleaved_rtp_frame_bytes + framing_headroom

application_pending_limit = clamp(
  max(time_budget_bytes, atomic_write_floor_bytes),
  safe_min,
  safe_max)
```

这里的原子写入单位是单个 interleaved RTP frame，不是整个 IDR access unit。关键约束是不能为了容纳完整 IDR 把“可积压时间”放大到秒级。如果单个 IDR 已经超过时间预算，应使用轻量 pacing、及时 drain 和必要时重建，而不是把 buffer 无限调大。

首轮值：

- 应用 pending age hard limit 继续 `200ms`，目标 P95 `<=100ms`。
- `1080p/7Mbps` 的 150-200ms 媒体预算约 `128-171KB`，实际还要加 interleaved/RTP 头和单帧原子写入余量。
- 动态字节门限保留安全下限，但必须同时受 age、not-sent 和连接重建约束，不能把 `256KB-2MB` 解释为可接受延迟。

### 3. 控制 kernel 中未发送的旧字节

macOS/iOS 低延迟 TCP 实验接入 `TCP_NOTSENT_LOWAT`：

- 首轮 A/B 使用 `64KB/128KB`，不是直接固定一个全平台常量。
- 保留足够 `SO_SNDBUF` 以避免单个完整 IDR无法写入，但用 not-sent low-water mark 限制继续灌入 kernel 的旧数据。
- diagnostics 只周期采样 `notSentBytes/limitHitCount`；不逐包 syscall 和日志。
- 不支持该 socket option 的平台静默降级到现有 age/bytes/rebuild 保护，不能阻断播放。

### 4. 大 IDR 做 bitrate-aware pacing

TCP pacing 目的不是降低码率或降低 1080p 指标，而是避免一个大 IDR 瞬间灌满应用和 kernel buffer。

- 只对达到 packet/byte 门限的大 access unit 生效。
- 初始 pacing target 取目标码率的 `1.2x-1.5x` 做 A/B。
- pacing 必须受总发送期限约束；若写阻塞或预计超过 freshness deadline，转入 rebuild，不能慢慢补发旧 IDR。
- GOP 不改成默认 `500ms`。继续使用启动/恢复约 `1s`、稳定约 `2s`、请求后立即强制下一帧 IDR。

### 5. 全局 IDR gate

live control、RTCP PLI、RTCP FIR、raw-frame-drop、TCP backlog 和 PLAY 不能各自维护互不相干的 throttle。发送端建立 session 级 gate：

- 同一 recovery cycle 只允许一个 pending IDR action。
- `scheduled` 表示已调用 encoder 强制关键帧。
- `throttled` 表示没有新安排，必须返回关联的 pending request/generation。
- 编码器产出完整 IDR 后发送 `idr_emitted`，带 `requestId/generation/rtpTimestamp`。
- 首轮最小间隔 `400-800ms` 按恢复状态和 IDR 实际产出动态决定，不能仅按请求到达时间固定 sleep。

## 独立反馈通道方案

独立 TCP live control channel 保持主反馈通道，因为它与 RTSP media TCP socket 分离，不受同一条 interleaved 视频字节队列直接阻塞。

必须完成：

1. `requestKeyFrame` 返回结构化状态 `SCHEDULED/THROTTLED/REJECTED/TIMEOUT`，不再返回 boolean。
2. `THROTTLED` 不能计为新 IDR 已安排；它只表示发送端已有 pending action 或仍在 gate 内。
3. client 改为单独 reader/dispatcher，按 `type + requestId` 分发 ACK 和异步 `idr_emitted`，不能在同步 `readLineUntil` 中丢弃不匹配消息。
4. 建立 `request -> keyframe_ack -> idr_emitted -> receiver idr_recovered/rendered` 关联指标。
5. 恢复触发采用边沿/周期去重；持续 `waitingForIdr=true` 或 backlog level 高不能每 `400ms` 无限重发。
6. 控制通道 ACK 超过 `150-200ms`，一次快速重试仍失败，才触发一次 RTCP PLI fallback。
7. PLI 后 `500-800ms` 仍无完整 IDR，可升级一次 FIR；再失败进入 RTSP rebuild。FIR 必须是实际发送能力，不能只增加计数。

## RTCP 策略

目标策略：

```text
live control healthy
  -> EXTERNAL_ONLY

live control unavailable before prepare
  -> RTCP_ONLY/BOTH fallback policy

runtime control timeout
  -> one-shot RTCP PLI
  -> optional one-shot FIR escalation
  -> rebuild
```

当前 ExoPlayer `BOTH` 是持续允许 RTCP，不是“独立通道失败后才发送”的条件 fallback，而且同时开启 PLI/FIR 时当前实现优先 PLI，不能自动完成 PLI 到 FIR 的升级。因此 ExoPlayer fork 需要增加 one-shot feedback API 或等价的动态 fallback controller；在该 API 可用前，不能直接把当前 TCP policy 从 `BOTH` 切成 `EXTERNAL_ONLY` 后宣称 fallback 已完整。

## Cast-SDK 接收端方案

Cast-SDK 负责跨模块编排，不负责在 Java 上层逐包处理 RTP：

- 根据 capability、playback mode 和实际 transport 选择 policy。
- 聚合 ExoPlayer 的 queue/backlog/WAIT_IDR/recovered 事件。
- 驱动独立反馈状态机和 one-shot RTCP fallback。
- 管理轻恢复、强恢复、media source rebuild 和 source-unreachable 上浮。
- 对外只输出低频结构化 diagnostics 和必要 listener 事件。

建议拆分：

```text
receiver/android/sdk/core/.../livefeedback/
  LiveFeedbackController              编排和生命周期
  LiveFeedbackTransport               TCP JSON transport 接口
  TcpLiveFeedbackTransport            socket reader/writer + correlation
  LiveRecoveryStateMachine            recovery cycle、去重、升级

receiver/android/sdk/player-exo-legacy/.../rtsp/
  LegacyRtspPolicyResolver            transport/feedback/recovery policy
  LegacyRtspDiagnosticsBridge         fork listener 到 SDK model
  LegacyRtspLatencyRecoveryController queue reset/WAIT_IDR/rebuild signal
  LegacyRtspMediaSourceBuilder        反射 API 组装
```

`LegacyExoPlayerAdapter` 只保留播放器生命周期和委托，避免 TCP、UDP 和普通 RTSP 策略继续堆在 3000 行主类中。

## ExoPlayer fork 方案

ExoPlayer fork 负责最接近内部队列、无法由 Cast-SDK 正确替代的能力：

1. transport-aware reorder wait：显式 low-latency TCP 为 `0-2ms`，UDP 保持可配置 `20-30ms`，默认普通 RTSP 保持原值。
2. TCP interleaved packet queue 的 age/depth warn/reset，reset 后进入 H.264 `WAIT_IDR`。
3. 自家 low-latency policy 下的受控 SampleQueue catch-up：停止 loader、清 RTP/AU/SampleQueue 旧数据、重置 timestamp/decoder-facing state、等待完整 IDR 后恢复。
4. 暴露一次性 `requestRtcpPli/requestRtcpFir` 或等价 fallback API，让 Cast-SDK 在独立通道失败时触发，而不是长期启用 `BOTH`。
5. 暴露低频累计指标：transport queue age/depth、RTP reorder age/depth、SampleQueue buffered ahead、reset reason/count、WAIT_IDR duration、one-shot RTCP result。
6. 保持 `DISABLED/DEFAULT` 完全不进入上述分支，不增加普通 RTSP hot-path 日志和对象分配。

SampleQueue 是压缩 H.264、位于 MediaCodec 输入之前。清理它能去掉播放器尚未解码的旧帧，但不能撤回已经进入 TCP 流或 MediaCodec 的数据，所以必须与 sender bounds、WAIT_IDR 和 rebuild 共同工作。

## 轻恢复与强恢复边界

轻恢复适用：

- queue 短暂超过 `500ms`，但 sender pending/not-sent 没有持续增长。
- TCP 数据仍持续到达。
- 完整 IDR 能在 `800ms` 内到达。

动作：清 Exo 内部可控旧队列、进入 WAIT_IDR、独立通道请求一次 IDR。

强恢复适用：

- queue `>=800ms` 持续 `300-500ms`。
- sender pending age 达到 `200ms` 或 not-sent 持续超标。
- WAIT_IDR `800ms` 无完整 IDR。
- queue 清理后仍继续快速增长，说明旧字节仍藏在 TCP/kernel。
- RTP no-packet 达到当前 low-latency `1500ms`。

动作：关闭旧 media TCP connection，清 socket/RTP/AU/SampleQueue/decoder 状态，重建 RTSP media source/session，并请求新 IDR。独立 live control channel 尽量保持不断开，便于关联重建前后的 recovery generation。

## Diagnostics 和性能边界

### 必要聚合指标

发送端：

- `rawFrameQueueAgeMs/depth/dropOldestCount`
- `pendingTcpBytes/ageMs/limitReason`
- `tcpNotSentBytes/lowatLimitHitCount`
- `socketWriteBlockCount`
- `idrRequestScheduled/throttled/rejected`
- `idrEmittedMs/requestId/generation`
- `adaptiveGopState/currentFrames`

接收端：

- `tcpInterleavedQueueAgeMs/depth/resetCount`
- `rtpReorderQueueAgeMs/depth`
- `sampleQueueBufferedAheadMs/resetCount`
- `decoderInputToRenderMs`
- `waitIdrCount/duration/timeout/recovered`
- `liveControlAckMs/idrEmittedMs/idrRecoverMs`
- `rtcpPliFallbackCount/rtcpFirFallbackCount`
- `rtspRebuildCount/rebuildDurationMs`
- `captureToRenderP50/P95/P99`

### 性能约束

- release 默认 diagnostics `off`；普通 RTSP listener 为 `null`。
- `basic` 只做计数、时间戳和固定窗口聚合，不保留逐包对象。
- socket queue 查询按低频周期采样，不在每个 RTP packet 上 syscall。
- callback 必须非阻塞；日志、JSON 和 debug page 聚合放到 RTP/decoder hot path 外。
- 所有新反射/API 探测只在 media source prepare 阶段执行一次并缓存结果。
- 新增能力失败时回退现有播放行为，不因 diagnostics 缺失中断主业务。

## 模块责任和具体修改位置

| 层面 | 主要位置 | 下一步职责 |
| --- | --- | --- |
| macOS capture/encoder | `sender/rust/crates/cast-sender-live-macos/src/macos.rs` 及新 backlog 模块 | latest-frame-wins、全局 IDR gate、自适应 GOP保持 |
| RTSP publisher | `sender/rust/crates/cast-sender-live-rtsp/src/lib.rs` 及新 `tcp_backpressure.rs` | 时间预算、`TCP_NOTSENT_LOWAT`、IDR pacing、pending/rebuild diagnostics |
| live control server | `sender/rust/crates/cast-sender-live-rtsp/src/live_control.rs` | 结构化 ACK、requestId/generation、`idr_emitted` 关联 |
| receiver core | `receiver/android/sdk/core/.../LiveFeedbackController.java` 及新 `livefeedback` 包 | 异步 transport、recovery state machine、去重、fallback 编排 |
| receiver legacy player | `receiver/android/sdk/player-exo-legacy/...` | policy 选择、fork API 反射桥接、queue reset/rebuild 委托 |
| ExoPlayer fork | `library/rtsp`、必要时 `library/core` | transport-aware reorder、SampleQueue catch-up、one-shot PLI/FIR、累计指标 |
| Demo/CLI | macOS Demo、sender CLI、receiver debug page | 只提供模式/实验开关和展示聚合指标，不承载恢复逻辑 |

## 实施 Roadmap

状态口径：只有代码、自动测试和对应验证都完成后才能标记“已完成”；当前已有能力标记“已完成（基线）”，本轮新增工作默认“未开始”。

| ID | 优先级 | 任务 | 归属 | 状态 | 验证方式 |
| --- | --- | --- | --- | --- | --- |
| T0 | P0 | 建立 TCP 专项 Roadmap 和文档权威边界 | Docs | 已完成 | 文档链接和普通 RTSP 边界人工检查 |
| T1 | P0 | 保留 `TCP_NODELAY`、non-blocking write、pending `200ms`、动态 `SO_SNDBUF` 基线 | Cast sender | 已完成（基线） | 现有 Rust 单测和代码复核 |
| T2 | P0 | raw frame queue 改为 low-latency latest-frame-wins | Cast sender | 未开始 | 单测覆盖满队列淘汰旧帧、smooth/default 不变 |
| T3 | P0 | 抽出 `tcp_backpressure` 模块，按 bitrate/time 计算应用 pending 预算 | Cast sender | 未开始 | 7Mbps 下 100/150/200ms 换算、单 RTP 原子写边界和大 IDR pacing 分流单测 |
| T4 | P0 | macOS 接入 `TCP_NOTSENT_LOWAT` 和低频 not-sent diagnostics | Cast sender | 未开始 | 64/128KB A/B；不支持平台降级测试 |
| T5 | P0 | 建立 session 级全局 IDR gate，统一 live control/RTCP/backpressure | Cast sender | 未开始 | 同一 recovery generation 只 schedule 一次；IDR emitted 关联 |
| T6 | P0 | live control ACK 改为结构化状态并关联 requestId/generation | 两端 Cast SDK | 未开始 | scheduled/throttled/rejected/timeout 单测 |
| T7 | P0 | receiver live control 改为异步 reader/dispatcher，不丢 `idr_emitted` | Cast receiver | 未开始 | ACK 和异步事件乱序、并发、断线重连单测 |
| T8 | P0 | recovery state machine 改为边沿/周期去重 | Cast receiver | 未开始 | 持续 WAIT_IDR/backlog 只触发一个 recovery cycle |
| T9 | P0 | transport-aware reorder wait，TCP low-latency `0-2ms` | ExoPlayer fork | 未开始 | TCP/UDP/default 三组 RTSP 单测和性能对比 |
| T10 | P0 | low-latency TCP 受控清 RTP/AU/SampleQueue 并进入 WAIT_IDR | ExoPlayer fork | 未开始 | 旧 sample 不再送 decoder；完整 IDR 恢复；default 不变 |
| T11 | P0 | Cast-SDK 接入 queue reset/recovered 和 media rebuild 编排 | Cast receiver | 未开始 | 轻恢复、800ms timeout、强恢复状态机 JVM 测试 |
| T12 | P0 | 增加 one-shot RTCP PLI fallback API | ExoPlayer fork | 未开始 | EXTERNAL_ONLY 下按命令发送一次 PLI；普通 RTSP 不发送 |
| T13 | P0 | TCP feedback 从 always-on BOTH 迁移为 external primary + conditional PLI | Cast receiver | 阻塞于 T12 | 控制通道正常时无重复 RTCP；超时后仅一次 PLI |
| T14 | P1 | one-shot FIR escalation 和发送端跨通道去重 | ExoPlayer + Cast sender/receiver | 未开始 | PLI 无恢复后一次 FIR；成功后不重复 |
| T15 | P1 | 大 IDR bitrate-aware TCP pacing | Cast sender | 未开始 | 1.2x/1.5x A/B，不降低 1080p/7Mbps 输出 |
| T16 | P1 | 拆分 `LegacyExoPlayerAdapter` 和 `LiveFeedbackController` 低延迟模块 | Cast receiver | 未开始 | 行为等价回归、依赖方向检查、文件职责 README |
| T17 | P1 | 补齐固定窗口聚合 diagnostics 和 debug page | 两端 Cast SDK | 未开始 | basic/off 性能对比；无逐包日志 |
| T18 | P1 | 1080p30/7Mbps TCP 正常网络、动态视频和 10/30 分钟长稳 | QA | 未开始 | 真机报告，不允许用 720p 代替 |
| T19 | P1 | TCP 弱网矩阵：限速、延迟、抖动、丢包、短断流 | QA | 未开始 | Linux gateway `tc netem` + 两端联合指标 |
| T20 | P1 | 普通第三方 RTSP smoke 和性能隔离回归 | QA | 未开始 | EXOPLAYER_DEFAULT、listener null、无额外 recovery/log |
| T21 | P2 | Android 11+ capability-gated codec low-latency hint 评估 | ExoPlayer/Cast receiver | 未开始 | 支持设备 A/B；Android 4.4 和 default 路径不启用 |
| T22 | P2 | LoadControl `80-100/250-300/30-50/80-100ms` 实验档 | ExoPlayer/Cast receiver | 未开始 | 只在 T2-T13 稳定后 A/B，不提前用于掩盖积压 |

## 测试方案

### 单元测试

发送端：

- latest-frame-wins 与 default/smooth 隔离。
- bitrate × time budget 换算、clamp、大 IDR 和 framing headroom。
- pending age、bytes、not-sent 三类超限原因。
- 全局 IDR gate 对 PLAY/live control/PLI/FIR/backpressure 的去重。
- pacing 总期限和连接重建分支。

Cast-SDK 接收端：

- `SCHEDULED/THROTTLED/REJECTED/TIMEOUT` 状态转换。
- ACK 与 `idr_emitted` 任意顺序、requestId 不匹配、断线和重连。
- WAIT_IDR/backlog level 连续上报不形成请求风暴。
- one-shot PLI、FIR 和 rebuild 只按状态机升级一次。
- `DEFAULT`、`SMOOTH`、`LOW_LATENCY TCP`、`LOW_LATENCY UDP` policy 隔离。

ExoPlayer fork：

- TCP low-latency reorder wait `0-2ms`；UDP 和 default 保持各自值。
- TCP packet queue age/depth reset 后进入 WAIT_IDR。
- SampleQueue catch-up 不把旧 P 帧送入 decoder。
- 完整 `SPS/PPS + IDR` 后恢复。
- one-shot PLI/FIR 的实际发送和失败上报。
- policy disabled 时无额外 callback、日志、reset 和 hot-path 分配。

### 集成和真机矩阵

固定产品规格：

```text
screen: 1920x1080 @ 30fps / 7000kbps
content A: 静态桌面和低运动
content B: 前台播放 1080p H.264 高运动视频
transport: FORCE_TCP
duration: 2min smoke / 10min trend / 30min stability
```

网络矩阵：

| 场景 | 初始条件 | 重点验证 |
| --- | --- | --- |
| 正常 LAN | 无整形 | 首帧、稳定延迟、无 queue 线性增长 |
| 带宽接近码率 | 8/10Mbps | pacing、not-sent、可恢复性 |
| 带宽低于码率 | 5/6Mbps，持续 5-15s | 快速强恢复，不长期播放旧画面 |
| 延迟/抖动 | 50-150ms + 20-80ms jitter | TCP HOL、queue slope、rebuild |
| 丢包 | 0.1/0.5/1/3% | TCP 重传隐藏丢包后的延迟恢复 |
| 短断流 | 0.5/1/3s | no-packet、control channel、rebuild |
| control failure | 独立通道断开/ACK timeout | one-shot PLI/FIR fallback |
| 息屏/锁屏/后台 | sender 或 receiver 状态变化 | capture restart、session generation、恢复 |

### 普通业务回归

- 第三方 RTSP camera/live：`EXOPLAYER_DEFAULT`，不强制 TCP，不启用 aggressive reset。
- 自家 `SMOOTH + FORCE_TCP`：缓冲稳定，不应用 low-latency SampleQueue catch-up。
- HLS、MP4、音乐、图片：播放器路由、首屏、控制和错误 UI 不变。
- Android 4.4/5.x：反射 API 缺失时降级，不崩溃、不改变默认 RTSP。
- diagnostics `off` 对 CPU、内存、线程、日志量和 APK 包体无可见回归。

## 验收标准

以下是下一阶段目标，不代表当前已经达成：

- `1920x1080 @ 30fps / 7000kbps` 全程保持，不因 TCP/UDP 模式降低产品指标。
- 正常 LAN 首 RTP 到首帧 P95 `<=500ms`。
- 时钟同步可用时，稳定 `captureToRender P95 <=500ms`、P99 `<=800ms`。
- sender application pending age P95 `<=100ms`，hard limit `<=200ms`。
- 正常播放 `sampleQueueBufferedAheadMs` 目标 `100-500ms`，不得随时长线性增长。
- queue 超过 `800ms` 后必须进入强恢复；恢复后 2 秒内回到 `<500ms`。
- WAIT_IDR 后完整 IDR 恢复目标 `<=800ms`；超时必须升级，不无限重发请求。
- rebuild 后恢复播放目标 `<=2s`，并清除旧 media connection 的积压。
- 10/30 分钟高运动视频无持续累积延迟、无无限 loading、无长期卡死。
- 可见花屏为 0；允许有统计明确、时间受控的短暂冻结。
- 独立反馈通道健康时，同一 recovery cycle 不重复发送 RTCP PLI/FIR。
- 普通第三方 RTSP 的 transport、buffer、日志量、CPU 和错误行为无回归。

## 灰度和回滚

- 所有新策略放在版本化 `TcpLowLatencyPolicy` 后，默认只对自家 `LOW_LATENCY` 开启。
- 分项灰度：`latestFrameWins`、`tcpNotSentLowat`、`transportAwareReorder`、`sampleQueueCatchUp`、`conditionalRtcpFallback`、`idrPacing` 可独立关闭。
- 接收端 capability 中声明支持的 policy version；发送端不认识时继续使用现有稳定 TCP 路径。
- SampleQueue catch-up 或 one-shot RTCP API 不可用时，Cast-SDK 回退到现有 backlog policy + RTSP rebuild，不影响普通业务。
- 任一灰度项导致黑屏、恢复时间上升、CPU/内存明显回归或第三方 RTSP 变化，单独关闭该项，不需要回滚全部 fork。

## 下一步执行顺序

### 第一批：先消除确定性结构问题

1. T2-T5：发送端 latest-frame-wins、时间预算、not-sent 和全局 IDR gate。
2. T6-T8：独立反馈 ACK/异步事件/恢复周期去重。
3. T9-T12：ExoPlayer transport-aware reorder、SampleQueue catch-up 和 one-shot PLI。
4. T11/T13：Cast-SDK 集成新的 fork artifact，完成 external primary + conditional fallback。

### 第二批：完整恢复和性能收敛

1. T14-T15：FIR 升级和大 IDR TCP pacing。
2. T16-T17：模块拆分、聚合 diagnostics 和 debug page。
3. T18-T20：1080p/7Mbps 真机、弱网、长稳和普通 RTSP 隔离回归。

### 第三批：数据驱动参数优化

1. 根据 T18/T19 数据调整 not-sent、queue 和 rebuild 阈值。
2. 再评估 codec low-latency hint 和更小 LoadControl。
3. 不在 P0 闭环完成前调整默认 GOP 到 `500ms`，也不通过降低码率/分辨率通过验收。

## 当前进展摘要

- 2026-07-10：完成现有 Cast-SDK、ExoPlayer 本地源码和真机报告复核，建立本文。
- 已确认 TCP 基线具备 non-blocking write、`TCP_NODELAY`、pending age/cap、动态 `SO_SNDBUF`、WAIT_IDR、backlog policy、独立反馈通道和自适应 GOP。
- 已确认下一阶段关键工作不是继续缩短 IDR 间隔，而是控制 sender kernel backlog、清 receiver SampleQueue、消除重复 IDR 请求并建立可关联的恢复状态机。
- 尚未执行本文 T2-T22 的新增代码和真机验证；不能把方案状态表述为已完成。

## ExoPlayer 实施复核/进展

### 执行边界

- 本 fork 只实现 ExoPlayer `library/rtsp` 内部可正确控制的能力：RTP reorder 等待策略、TCP interleaved/reorder backlog 到 H.264 `WAIT_IDR` 的恢复衔接、受控 catch-up/rebuild signal、one-shot RTCP PLI/FIR API、低频 diagnostics。
- 普通 RTSP default 必须继续保持 `EXOPLAYER_DEFAULT + RtcpFeedbackPolicy.DEFAULT + RtspBacklogRecoveryPolicy.DISABLED + listener null + packet diagnostics false`，不得因本专项改变 transport、buffer、retry/error、decoder 或 SampleQueue 行为。
- 自家 TCP low-latency 必须由显式 policy/API 开启，不能通过全局静态常量、URI scheme 或 transport 是 TCP 的事实影响第三方 RTSP。
- SampleQueue 处理风险最高；第一实现只允许在显式 low-latency recovery policy 下走受控恢复，不做普通路径按 sample age 静默丢弃。若无法在 ExoPlayer 内部证明状态正确，必须提供明确 recovery signal/API 让 Cast-SDK rebuild，而不是伪装为已清理。
- diagnostics 只做低频聚合或状态变化事件；RTP hot path 禁止逐包 `Log`、JSON、文件 IO、网络 IO、阻塞 callback 和无界对象分配。

### 当前代码复核结论

- `RtspBacklogRecoveryPolicy` 已存在并默认 `DISABLED`，适合作为 TCP low-latency 专项的显式开关承载；但现有字段还不能表达 transport-aware reorder wait 和 media-period catch-up/rebuild action。
- `TransferRtpDataChannel` / `RtspMessageChannel` 已有 TCP interleaved 数据入口和 backlog reset 基础，可继续沿用低频 queue reset diagnostics；不能在此处新增逐包日志或复杂对象分配。
- `RtpPacketReorderingQueue` 当前 cutoff 仍是 RTP extractor 侧统一策略，需要改成由 session/policy 传入，满足 TCP low-latency `0-2ms`、UDP 可配置 `20-30ms`、default 原值不变。
- `RtpH264Reader` 已有 `WAIT_IDR`、drop-until-IDR、完整 `SPS/PPS + IDR` 恢复和 initial WAIT_IDR 能力，可复用作为 packet/reorder reset 后恢复边界。
- `RtspMediaPeriod` / SampleQueue catch-up 仍未实现；这里涉及 loader、sample timestamp mapping、多轨同步、decoder 已入队边界，必须先做代码路径复核和最小测试，再决定是原地 reset 还是只暴露 rebuild signal。
- `RtcpFeedbackPolicy.EXTERNAL_ONLY` 下现有自动 requester 不应长期发送 RTCP；T12/T14 需要新增 one-shot PLI/FIR controller/API，使 Cast-SDK 在 live control timeout 后显式请求一次并获得真实 `scheduled/throttled/failed` 结果。

### ExoPlayer 任务进展

| ID | 状态 | ExoPlayer 侧拆解 | 验证 |
| --- | --- | --- | --- |
| T9 | ExoPlayer 已实现，待发布 | `RtspBacklogRecoveryPolicy` 新增 `setTcpInterleavedRtpReorderWaitMs(long)`、`setUdpRtpReorderWaitMs(long)` 和 `getRtpReorderWaitMs(int)`；`RtpExtractor` 构造时按本 session/policy 固化 wait。`DISABLED` 仍保持 ExoPlayer 2.19.1 原值 `30ms`；`LOW_LATENCY` preset TCP 为 `2ms`，UDP 为 `30ms`。 | `RtspFeedbackApiTest`、`RtpExtractorTest` 已覆盖 default/TCP/UDP 隔离 |
| T10 | ExoPlayer 第一阶段已实现，待发布 | 未做普通路径或通用 SampleQueue 按 age 静默丢弃。`RtspBacklogRecoveryPolicy` 新增 `setMediaPeriodRecoverySignalEnabled(boolean)`，默认 false；仅当 policy enabled + signal enabled + TCP interleaved reset 时，通过 `RtspDiagnosticsListener#onRtspMediaPeriodRecoveryRequired(...)` 上报 `ACTION_REBUILD_REQUIRED`，由 Cast-SDK 执行受控 rebuild。原因：ExoPlayer 内部无法在不影响多轨、timestamp mapping 和 decoder 已入队边界的情况下证明原地清理 SampleQueue 一定正确。 | `RtspFeedbackApiTest` 已覆盖 TCP reset 触发 recovery signal、UDP reset 不触发 |
| T12 | ExoPlayer 已实现，待发布 | `RtspMediaSource` 新增 `requestRtcpPli(int)` / `requestOneShotRtcpPli(int)`；`RtspMediaPeriod` 在播放线程返回 `RtcpFeedbackResult`，支持 `SCHEDULED`、`THROTTLED`、`FAILED`。显式 one-shot PLI 可在 `EXTERNAL_ONLY` 且 `pliEnabled=true` 下发送，不改变自动 feedback 策略。 | `RtspFeedbackApiTest` 已覆盖无 active period 的 failed 结果和 result value object；发送路径复用既有 RTCP packet/channel 单元测试，后续由 Cast-SDK 真机验证 |
| T14 | ExoPlayer 已实现，待发布 | `RtspMediaSource` 新增 `requestRtcpFir(int)` / `requestOneShotRtcpFir(int)`；显式 one-shot FIR 可在 `EXTERNAL_ONLY` 且 `firEnabled=true` 下发送，PLI 后是否升级 FIR 由 Cast-SDK 决定。 | 同 T12 |

### 当前执行日志

- 2026-07-10：已将 Cast-SDK TCP low-latency roadmap 同步到 ExoPlayer `docs/labi/2026-07-10-live-tcp-low-latency-transport-roadmap.md`，并新增本章节作为 ExoPlayer fork 执行 ledger。
- 2026-07-10：已确认本轮开发必须保持默认业务隔离：普通 RTSP default 不启用 transport-aware reorder、SampleQueue catch-up 或 one-shot feedback；所有新增行为必须受显式 low-latency policy/API 控制。
- 2026-07-10：完成 ExoPlayer 实现：transport-aware RTP reorder wait、TCP reset media-period rebuild signal、one-shot PLI/FIR result API、低频 diagnostics value object；没有新增 RTP hot path 日志、JSON、文件/网络 IO 或阻塞 callback。
- 2026-07-10：targeted 测试通过：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpExtractorTest`。
- 2026-07-10：完整 RTSP 单测通过：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`。
- 2026-07-10：release AAR 构建通过：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease`。
- 2026-07-10：已发布 `2.19.1-labi.12` 到 GitHub Pages Maven repo。source commit `b1d53e9e9faf3310f006c2cf00de778a816e33e1`，tag `exoplayer-rtsp-2.19.1-labi.12`，gh-pages commit `e0540862a9`。
- 2026-07-10：发布模块：`exoplayer-common`、`exoplayer-container`、`exoplayer-database`、`exoplayer-datasource`、`exoplayer-decoder`、`exoplayer-extractor`、`exoplayer-core`、`exoplayer-hls`、`exoplayer-rtsp`。三组关键 metadata 均为 `latest/release=2.19.1-labi.12`：`exoplayer-core`、`exoplayer-hls`、`exoplayer-rtsp`。
- 2026-07-10：远端 HTTP/SHA256 校验通过：
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.12/exoplayer-rtsp-2.19.1-labi.12.pom`：`6663453340b16820515ceeeb977958f3fe10282d25fd24d6bdb6097a702ea50c`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.12/exoplayer-rtsp-2.19.1-labi.12.aar`：`e0914be6b3b06b2eaf48532c58b765321b5c88c15cbf497145356581724cc14f`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/maven-metadata.xml`：`23e4366868754752bb0e4e530c9fb413d57c1d9ca9aa4aa6c88a32812d493ecb`
- 2026-07-10：远端 RTSP AAR `classes.jar` 经 `javap` 确认包含：
  - `RtspBacklogRecoveryPolicy.Builder#setTcpInterleavedRtpReorderWaitMs(long)`
  - `RtspBacklogRecoveryPolicy.Builder#setUdpRtpReorderWaitMs(long)`
  - `RtspBacklogRecoveryPolicy.Builder#setMediaPeriodRecoverySignalEnabled(boolean)`
  - `RtspMediaSource#requestRtcpPli(int)`、`requestOneShotRtcpPli(int)`、`requestRtcpFir(int)`、`requestOneShotRtcpFir(int)`
  - `RtspDiagnosticsListener#onRtspMediaPeriodRecoveryRequired(RtspMediaPeriodRecoveryStats)`
  - `RtcpFeedbackResult`、`RtspMediaPeriodRecoveryStats`
- 2026-07-10：远端 RTSP AAR class list 未包含 Cast-SDK 类型。首次全量 publish 未跳过测试时触发既有非 RTSP `:library-core:testDebugUnitTest` async/fixture 失败：`ExoPlayerTest.onEvents_correspondToListenerCalls` timeout、`DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes` timeout、`PlaylistPlaybackTest.test_subtitle` comparison failure；随后按既有发布策略使用 `-x lint -x test -x testDebugUnitTest -x testReleaseUnitTest` 完成 Maven 发布。RTSP targeted/full 单测和 release AAR 构建均已单独通过。
- 2026-07-10：根据 Cast-SDK 小米真机 1080p/30fps/7Mbps FORCE_TCP 10 分钟长跑定位，修复 `RtpPacketReorderingQueue.calculateSequenceNumberShift()` 在 `65535 -> 0` 连续 wrap 边界把相邻 sequence 误判为相等的问题。RTP 16-bit sequence 空间大小为 `RtpPacket.MAX_SEQUENCE_NUMBER + 1`，不能使用最大值本身作为 wrap 距离。该修复属于通用 RTP 序列语义修正，不依赖 low-latency 开关，不新增 RTP hot path 日志、对象分配、锁、IO 或 callback。
- 2026-07-10：新增 `RtpPacketReorderingQueueTest` 覆盖连续 `65534,65535,0,1`、跨 wrap 乱序、wrap 后迟到旧包。验证通过：targeted `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest`、完整 `:library-rtsp:testDebugUnitTest`、`:library-rtsp:assembleRelease`。已发布 Maven artifact `2.19.1-labi.13`：source commit/tag `944cc32560` / `exoplayer-rtsp-2.19.1-labi.13`，gh-pages commit `a152784ee8`，RTSP AAR SHA256 `f85bc6ec28e6adee4e9ff99d20b407ad4514d6635fd1f29b3b0e060dd55cfd27`。
