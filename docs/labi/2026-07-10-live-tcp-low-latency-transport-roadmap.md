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
- 不把 `0.5s GOP`、降码率、降分辨率作为正常网络下的 TCP 低延迟默认方案；真实持续拥堵下允许通过独立灰度策略临时降码率，但正常验收和恢复后的目标仍是 `1080p30/7Mbps`。
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
- 动态字节预算已改为 bitrate/time 模型并限制在 `64KB-512KB`；`7Mbps/200ms` 为 `175000 bytes`，不再由 IDR 大小放大。
- macOS/iOS 已接入 `TCP_NOTSENT_LOWAT` 和低频 `SO_NWRITE` 采样；`7Mbps/75ms` 为 `65625 bytes`，不支持平台静默降级。
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

1. 正常网络的 bitrate/time pending 和 kernel not-sent 基线已实现，但还没有持续拥堵下的 runtime bitrate 调节和吞吐闭环。
2. `.13` 10 分钟复测出现一次 `370ms` TCP reorder entry 滞留；发送端当时无 block/error，缺少 receiver loader stall、packet inter-arrival、GC/调度和 sender frame trace 的同时间轴证据。
3. capture-to-render 依赖 live control clock sync freshness `<=10s`；本轮 heartbeat 在恢复后没有持续续约，导致样本数停在 594，长跑延迟趋势不可信。
4. 稳定阶段还没有完整拆出 capture queue、publisher、decoder-input-to-render 和 Surface 的 P50/P95，不能确定剩余约 400ms 的主要归属。
5. ExoPlayer queue reset 能清 RTP/AU，但已经进入 SampleQueue/MediaCodec 的旧数据仍只能通过受控 media source rebuild 清除；不能在普通路径静默删 sample。
6. Android 11+ codec low-latency hint、ScreenCaptureKit queueDepth 1 和更小 LoadControl 尚未完成单变量 A/B。
7. `LegacyExoPlayerAdapter` 和低延迟 diagnostics/recovery 仍需要继续拆分，避免 TCP、UDP 和普通 RTSP 策略重新耦合到大类。
8. `1080p30/7Mbps` 已完成 `.13` 10 分钟传输复测，但 30 分钟、弱网矩阵、普通第三方 RTSP 隔离和 diagnostics 性能 A/B 尚未完成。

### 现有验证证据

- `qa/reports/receiver/live-latency/20260706-232057/summary.md`：`1280x720@30 / 2500kbps / rtsp-tcp / low-latency` 的 20 秒基线，首 RTP 到首帧 `189ms`，但时长和规格不足以证明 TCP 长稳达标。
- `qa/reports/receiver/functional/20260710-123442/summary.md`：`1920x1080@30 / 7000kbps` UDP 动态视频暴露 SampleQueue 可积压到约 4 秒。该报告证明“只请求 IDR 不清 SampleQueue”是跨 transport 的播放器队列缺口，但不能作为 TCP 性能结果。
- `.13` 之前已完成小米真机 `1920x1080@30 / 7000kbps / FORCE_TCP` 的 120 秒 smoke 和网页高动态视频 10 分钟长跑；旧版每跨越 65,536 个 RTP packet 都会误触发恢复。该项作为修复前历史证据保留，当前结论以下一条 `.13` 复测为准。
- `qa/reports/receiver/longrun/20260711-tcp-screen-1080p30-labi13-600s/summary.md`：`2.19.1-labi.13` 下完成同规格 10 分钟复测，`496465/496465` RTP receive/dequeue、0 gap/drop/duplicate，跨约 7 次 sequence wrap 无周期性误丢包，三个显式接收队列均为 0。发生 1 次非 wrap 的 `370ms` TCP reorder backlog reset，独立通道在 `68ms` 内完成 IDR 恢复；capture-to-render 窗口在 594 个样本后停止增长，延迟趋势验收仍未完成。

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

`2.19.1-labi.12` 已补齐 one-shot PLI/FIR，Cast-SDK TCP low-latency policy 已从持续允许 RTCP 的 `BOTH` 切为 `EXTERNAL_ONLY`：独立通道一次重试仍失败才发一次 PLI，同周期后续 WAIT_IDR timeout 最多再发一次 FIR。普通 RTSP 仍使用 `DEFAULT`，不会进入这套 fallback controller。

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
3. 自家 low-latency policy 下，TCP queue reset 后发出 `ACTION_REBUILD_REQUIRED`；Cast-SDK 校验仍是同一 media source/session 后执行受控 RTSP rebuild，清掉旧 socket、RTP/AU/SampleQueue/decoder 状态。第一阶段不做 SampleQueue 按 age 静默丢弃，避免多轨、timestamp mapping 和已入 decoder 样本的边界风险。
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

## 2026-07-11 下一阶段方案

### 阶段结论

当前 TCP 主链路已经从“会卡死、会周期性误恢复”收敛到“可持续传输且恢复闭环有效”，但还不能进入最终产品验收：

- `labi.13` 已证明 sequence wrap 修复有效，正常 10 分钟内没有 socket pending、SampleQueue 积压或 session rebuild。
- 首帧 capture-to-render 约 `255ms`，首 RTP 到渲染 `168ms`，说明首屏链路已经进入可用区间。
- 稳定窗口记录到 P50/P95 `428/594ms`，P95 仍高于 `500ms` 目标；恢复后样本没有继续增长，当前不能判定后续延迟是否稳定。
- 唯一一次 queue reset 发生时发送端仍保持 `28-30fps`、socket block/error 为 0，不能直接归因于发送端或网络拥堵。应先建立跨端事件关联，不能简单把 ExoPlayer reset 阈值从 `300ms` 放大来隐藏问题。

下一阶段按“先稳定观测和恢复，再压固定延迟，最后做弱网自适应”推进。任何 A/B 参数只对自家显式 `LOW_LATENCY` 生效，普通 RTSP 保持 `EXOPLAYER_DEFAULT + policy disabled + listener null`。

### P0：先修观测和偶发 reset

1. 修复 live control heartbeat/clock sync 长跑续接。当前 capture-to-render 只有 clock sync 年龄 `<=10s` 才记样本，10 分钟复测在 594 个样本后停止增长。需要保证正常播放和 WAIT_IDR 恢复后仍低频续约 clock sync，并记录失效原因；不能通过无限放大 freshness 窗口掩盖时钟漂移。
2. 补 queue reset 跨端关联。ExoPlayer 聚合上报 expected/actual sequence、queue oldest age/span、最近 packet inter-arrival max、loader read stall；Cast-SDK 关联 receiver GC/主线程 stall、live control generation；sender 关联 capture/encode/publisher/first-last RTP、`SO_NWRITE` 和 not-sent。默认只做固定窗口计数/最大值，不新增逐包日志或对象分配。
3. 保留现有 `300ms` TCP queue reset 和 `WAIT_IDR -> independent request -> complete IDR` 恢复闭环，直到数据证明 reset 是阈值误判。一次 `68ms` 恢复优于放大队列后累积旧画面。
4. 修复观测后重跑同规格 10 分钟，要求 capture-to-render sample count 持续增长到停止前，才能继续做参数 A/B。

T29 代码进展（2026-07-11）：

- `LiveFeedbackController` 已把心跳从“仅由 configure/diagnostics 顺带触发”改成 live control session 生命周期内独立的 `500ms` 周期任务；没有 live control channel 时不创建任务，普通第三方 RTSP 不受影响。
- 每次有效 pong 都通过低频 listener 快照把新 clock offset/RTT/updatedAt 推到 `PlaybackSession`，不再依赖播放器 diagnostics 恰好触发通知。
- session 切换、停用和 close 会取消周期任务；旧 session 的 ping、request、recovery ACK 和异步 `idr_emitted` 不能覆盖或关闭新 session client。
- `LiveFeedbackControllerTest` 已覆盖无 playback diagnostics 持续心跳、clock sync 通知、恢复请求后续约、停用取消和旧 session 回执隔离；Gradle core 全量单测通过。真机 10 分钟样本持续性须在 ExoPlayer T30/T32 新版本集成后统一复测，因此 T29 暂不标记最终完成。

### P1：正常网络固定延迟优化

按收益/风险顺序进行，不能同时改多个参数：

1. `ScreenCaptureKit queueDepth=2 -> 1` A/B。理论上可减少最多约一帧采集等待，风险是高动态内容 capture drop 增加；只有 P95 改善且 drop/encoder stall 不增加才允许灰度。
2. 补稳定帧分段 P50/P95：capture queue age、capture-to-encode、encode、encode-to-publisher、publisher-to-send、network、receive-to-AU、AU-to-decoder-input、decoder-input-to-render。当前三个显式队列为 0，重点确认剩余约 `400ms` 是否在帧年龄或 MediaCodec/Surface。
3. Android 11+ capability-gated codec low-latency hint A/B。只在命中已验证 codec profile 时启用，configure/start/首帧任一失败立即回退默认 decoder；Android 4.4-10 和普通 RTSP 不变。
4. LoadControl `80-100/250-300/30-50/80-100ms` 只作为最后的单变量实验。本轮 SampleQueue/Exo buffer 为 0，预计收益有限，不把它作为主要优化手段。
5. 大 IDR TCP pacing 仅在 packet/byte burst 达门限时启用，目标 `1.2x/1.5x target bitrate` A/B；正常 LAN 没有 not-sent/pending 时不得人为给所有 IDR增加 sleep。

### P1：持续拥堵下自适应码率

正常网络必须保持 `1080p30/7Mbps`。但当链路可持续吞吐低于 7Mbps 时，TCP 可靠传输无法同时保持 7Mbps 输入、低延迟和不丢旧数据；此时临时降低编码码率是必要的产品退化策略，不是降低正常验收标准。

拥堵判断不能依赖单个瞬时指标，使用发送端主信号和接收端校验信号：

```text
sender primary:
  pendingTcpAge/bytes slope
  SO_NWRITE / notSentBytes slope
  socket WouldBlock windows
  measured media bytes / drain time

receiver confirmation:
  tcp interleaved / RTP / SampleQueue backlog
  capture-to-render slope
  WAIT_IDR / queue reset frequency
```

状态机：

```text
NORMAL_7000
  -> PRESSURE: 两个以上信号持续 300-500ms
  -> CONGESTED: pending age >=80ms、receiver backlog >=500ms，或 drain rate 持续低于 input rate
  -> SEVERE: pending age 接近 200ms、backlog >=800ms 或连续恢复
  -> RECOVERING: 连续健康 10s 后逐级恢复
```

首轮码率梯度：

```text
7000 -> 6000 -> 5000 -> 4000kbps
```

- 首先保持 `1920x1080@30fps`，每次只降一级；严重拥堵允许快速降两级。
- `4000kbps` 是首轮实验 floor，不是永久常量。低于该吞吐持续 `10-15s` 时，再评估 `30 -> 24/20fps`；降分辨率放在最后，不在本阶段默认启用。
- 恢复必须慢于降档：连续健康 `10s` 才进入 RECOVERING，每 `5-10s` 最多升一级；任一 backlog 回升立即停止升档。
- bitrate-only runtime update 不应强制 IDR；如果平台 encoder 必须重建，必须走完整 `SPS/PPS + IDR` generation 切换，旧 session 不允许混用参数集。
- VideoToolbox 增加受控 runtime `AverageBitRate` 和 `DataRateLimits` 更新，检查每次 `VTSessionSetProperty` 返回值；失败时保持当前稳定码率，不中断投屏。
- 模式、当前目标码率、降档原因、估计吞吐、停留时间和升降档次数进入低频 diagnostics；不在每帧或每包上报。

### I 帧低质量、P 帧逐步变清晰的结论

该思路在编码理论上可行，但不推荐直接作为默认实现：提高 IDR QP 会让恢复帧更小、更模糊，后续 P 帧可以通过残差逐步补回细节；但 IDR 是后续 P 帧的参考，低质量参考会把修复码率转移到后续多个 P 帧，可能只是把一次 burst 延后，并造成明显的“先糊后清晰”。当前 macOS VideoToolbox 只设置 session 级 `AverageBitRate`，公开稳定路径没有项目已验证的 per-frame IDR QP 控制；异步编码期间临时切 session quality 还存在作用到错误帧的风险。

推荐替代为“恢复窗口码率斜坡”实验：

```text
congestion target bitrate = B
complete recovery IDR: 0.7B-0.85B 的受控窗口 + DataRateLimits
next 300-800ms P frames: 逐级恢复到 B
stable 10s: 再按拥堵状态机向 7000kbps 恢复
```

这里降低的是一个短恢复窗口的编码预算，不依赖单帧私有 QP API；仍要求完整 SPS/PPS + IDR、视觉不可花屏、恢复后画质可预测。该实验只有在普通 IDR pacing 和自适应码率完成后再做，验收必须同时满足：IDR burst 降低、WAIT_IDR 恢复 `<=800ms`、无新的恢复振荡、明显模糊不超过 `500ms`。不满足则关闭，不影响默认 7Mbps 路径。

## 模块责任和具体修改位置

| 层面 | 主要位置 | 下一步职责 |
| --- | --- | --- |
| macOS capture/encoder | `sender/rust/crates/cast-sender-live-macos/src/macos.rs`、`macos_camera_native.m` 及新 rate controller | latest-frame-wins、runtime bitrate/DataRateLimits、queueDepth A/B、恢复窗口码率斜坡 |
| RTSP publisher | `sender/rust/crates/cast-sender-live-rtsp/src/lib.rs`、`tcp_backpressure.rs` 及新 congestion policy | 时间预算、`TCP_NOTSENT_LOWAT`、吞吐/斜率判断、IDR pacing、码率建议、pending/rebuild diagnostics |
| live control server | `sender/rust/crates/cast-sender-live-rtsp/src/live_control.rs` | 结构化 ACK、requestId/generation、`idr_emitted` 关联 |
| receiver core | `receiver/android/sdk/core/.../LiveFeedbackController.java` 及 `livefeedback` 包 | heartbeat/clock sync 续约、异步 transport、恢复状态、receiver backlog 反馈和去重 |
| receiver legacy player | `receiver/android/sdk/player-exo-legacy/...` | policy 选择、timestamp mapping、codec profile、fork diagnostics、queue reset/rebuild 委托 |
| ExoPlayer fork | `library/rtsp`、必要时 `library/core` | transport-aware reorder、loader/reset 聚合诊断、decoder-input-to-render、codec low-latency profile |
| Demo/CLI | macOS Demo、sender CLI、receiver debug page | 只提供模式/实验开关和展示聚合指标，不承载恢复逻辑 |

## 实施 Roadmap

状态口径：只有代码、自动测试和对应验证都完成后才能标记“已完成”；当前已有能力标记“已完成（基线）”，本轮新增工作默认“未开始”。

| ID | 优先级 | 任务 | 归属 | 状态 | 验证方式 |
| --- | --- | --- | --- | --- | --- |
| T0 | P0 | 建立 TCP 专项 Roadmap 和文档权威边界 | Docs | 已完成 | 文档链接和普通 RTSP 边界人工检查 |
| T1 | P0 | 保留 `TCP_NODELAY`、non-blocking write、pending `200ms`、动态 `SO_SNDBUF` 基线 | Cast sender | 已完成（基线） | 现有 Rust 单测和代码复核 |
| T2 | P0 | encoded AU queue 改为 low-latency latest-frame-wins | Cast sender | 已完成（单元级） | `cast-sender-live-rtsp` 单测覆盖按完整 AU 淘汰、claimed AU 不被拆散、等待新 IDR；smooth 继续 bounded channel |
| T3 | P0 | 抽出 `tcp_backpressure` 模块，按 bitrate/time 计算应用 pending 预算 | Cast sender | 已完成（单元级） | 7Mbps 下 100/150/200ms 换算和 clamp 单测；大 IDR 不再抬高 backlog budget |
| T4 | P0 | macOS 接入 `TCP_NOTSENT_LOWAT` 和低频 not-sent diagnostics | Cast sender | 已完成（单元级） | macOS 配置 `TCP_NOTSENT_LOWAT`，100ms 周期用 `SO_NWRITE` 采样；不支持平台静默降级；真机 A/B 待 T18 |
| T5 | P0 | 建立 session 级全局 IDR gate，统一 live control/RTCP/backpressure | Cast sender | 已完成（单元级） | 同一 pending generation 只 schedule 一次；ACK/`idr_emitted` 带 requestId/generation；Rust 相关 111 tests 通过 |
| T6 | P0 | live control ACK 改为结构化状态并关联 requestId/generation | 两端 Cast SDK | 已完成（单元/构建） | scheduled/throttled/rejected/timeout；同一 requestId 重试；sender/receiver 自动测试通过 |
| T7 | P0 | receiver live control 改为异步 reader/dispatcher，不丢 `idr_emitted` | Cast receiver | 已完成（单元/构建） | `idr_emitted` 先于 ACK 仍正确分发；eventCount/debug snapshot 可见；断线后 controller 重建 client |
| T8 | P0 | recovery state machine 改为边沿/周期去重 | Cast receiver | 已完成（单元/构建） | 持续 WAIT_IDR/backlog 每周期只请求一次，timeout 才升级 |
| T9 | P0 | transport-aware reorder wait，TCP low-latency `0-2ms` | ExoPlayer fork | 已完成（`2.19.1-labi.12`） | LOW_LATENCY TCP `2ms`、UDP `30ms`、DISABLED `30ms`；fork RTSP tests 通过 |
| T10 | P0 | TCP queue reset 后发 media-period rebuild signal；不静默丢 SampleQueue | ExoPlayer fork | 已完成（第一阶段，`2.19.1-labi.12`） | 仅 explicit low-latency policy + TCP reset 上报 `ACTION_REBUILD_REQUIRED`；default 不变 |
| T11 | P0 | Cast-SDK 接入 queue reset/recovered 和 media rebuild 编排 | Cast receiver | 已完成（单元/构建） | callback 只接受 LOW_LATENCY TCP rebuild action，并校验同一 media source/URI 后受控 rebuild |
| T12 | P0 | 增加 one-shot RTCP PLI fallback API | ExoPlayer fork | 已完成（`2.19.1-labi.12`） | EXTERNAL_ONLY 下显式 one-shot PLI；普通 RTSP 不自动发送 |
| T13 | P0 | TCP feedback 从 always-on BOTH 迁移为 external primary + conditional PLI | Cast receiver | 已完成（单元/构建） | 独立通道一次重试失败后才 one-shot PLI；LOW_LATENCY 以外返回 unavailable |
| T14 | P1 | one-shot FIR escalation 和发送端跨通道去重 | ExoPlayer + Cast sender/receiver | 已完成（单元级） | 同周期 PLI 后仅 timeout 可升级一次 FIR；session gate 跨 live control/RTCP/backpressure 去重；真机待 T18/T19 |
| T15 | P1 | 大 IDR bitrate-aware TCP pacing | Cast sender | 已完成（A/B 否决） | 小米 1080p30/7Mbps 同屏源：1.2x/1.5x 均引入 publisher 同步阻塞、raw frame drop 和 queue reset；off 控制组稳定，生产实现已撤回，不降低规格也不保留热路径负担 |
| T16 | P1 | 拆分 `LegacyExoPlayerAdapter` 和 `LiveFeedbackController` 低延迟模块 | Cast receiver | 部分完成 | 已拆 `TcpLiveFeedbackClient`、sender `access_unit_queue/tcp_backpressure`；legacy policy/diagnostics/recovery controller 仍待拆 |
| T17 | P1 | 补齐固定窗口聚合 diagnostics 和 debug page | 两端 Cast SDK | 部分完成 | 已有 TCP send/not-sent、requestId/generation、`idr_emitted` snapshot；debug page 和性能 A/B 待完成 |
| T18 | P1 | 1080p30/7Mbps TCP 正常网络、动态视频和 10/30 分钟长稳 | QA | 已完成 | `.15` 30 分钟 P50/P95/max 509/533/546ms、SampleQueue/Exo/RTP queue=0、1170849/1170849 RTP、0 drop/gap；1 no-packet rebuild 后稳定恢复 |
| T19 | P1 | TCP 弱网矩阵：限速、延迟、抖动、丢包、短断流 | QA | 未开始 | Linux gateway `tc netem` + 两端联合指标 |
| T20 | P1 | 普通第三方 RTSP smoke 和性能隔离回归 | QA | 已完成 | 小米独立 1080p30 RTSP 源经通用 `cast rtsp`：PLAYING/首帧、EXOPLAYER_DEFAULT、default mode、packet diagnostics false、recovery normal |
| T21 | P0 | 将 low-latency TCP publisher 改为完整 AU transaction；同一连接内禁止 partial AU 后继续 RTP sequence | Cast sender | 已完成 | complete-AU deadline、invalid pending、SPS/PPS + multi-NAL IDR 连续序列和缓存只发最新恢复 AU 单测；`cast-sender-live-rtsp` 60 tests 通过 |
| T22 | P0 | recovery IDR 在完整 SPS/PPS/IDR 写完后才退出 awaiting-keyframe 并发送 `idr_emitted` | Cast sender | 已完成 | 完整恢复 AU 成功后才清 `awaiting_keyframe`；缓存 P AU 不重放；60 秒和 10 分钟真机首帧均来自 `recovery_au_cache` |
| T23 | P0 | 修复 screen/camera 停止顺序和 publisher join 卡死 | Cast sender | 已完成 | stop 改为先停 capture、后停 publisher；60 秒和 600 秒真机命令均正常退出 |
| T24 | P1 | 真机脚本补超时 finally 清理、周期采样和失败现场保存 | QA tooling | 已完成 | sender stdout/stderr 直写文件，避免 PIPE 堵塞；10 秒采样、超时现场、子进程清理和稳定 device-id fallback 已接入 |
| T25 | P2 | Android 11+ codec low-latency API/capability 研究和 profile 设计 | ExoPlayer/Cast receiver | 未开始 | 输出标准/厂商 key、codec allowlist、失败回退和普通路径隔离方案；实现与 A/B 归 T35 |
| T26 | P2 | LoadControl `80-100/250-300/30-50/80-100ms` 实验 profile 和隔离设计 | ExoPlayer/Cast receiver | 未开始 | 明确只对自家 LOW_LATENCY 启用及旧 core 降级；实际单变量 A/B 归 T36 |
| T27 | P0 | 修复 ExoPlayer RTP 16-bit sequence 在 `65535 -> 0` 临界回绕时误丢包 | ExoPlayer fork + Cast receiver | 已完成 | fork `2.19.1-labi.13` 已发布；10 分钟高动态屏幕跨约 7 次 wrap，496465/496465 receive/dequeue、0 gap/drop/duplicate，无周期性误恢复 |
| T28 | P0 | 固化 `.13` 10 分钟复测报告和新阶段边界 | Docs/QA | 已完成 | `20260711-tcp-screen-1080p30-labi13-600s/summary.md` 包含 wrap、reset、SampleQueue、延迟和终态证据 |
| T29 | P0 | 修复 live control heartbeat/clock sync 在正常播放和 WAIT_IDR 后持续续约 | Cast receiver | 已完成 | Gradle core 全量通过；`.14` 真机 10 分钟 ping/pong 1285/1272，capture-to-render 窗口达到 900 并持续滚动到 Stop，未复现 594 后停止 |
| T30 | P0 | 增加 TCP reorder reset 跨端聚合关联，不加逐包日志 | ExoPlayer fork + 两端 Cast SDK | 已完成 | `.14` API、Cast DTO/反射/debug JSON 和单测完成；400ms 真机暂停得到 queueDepth=2、oldestAge/queueSpan=474ms 的真实 reset 事件；普通路径默认关闭 |
| T31 | P0 | 定位并复现正常 LAN 单 packet `370ms` 滞留 | QA/ExoPlayer | 已完成 | debug `run-as` 400ms receiver pause 复现 queue reset，WAIT_IDR 丢 6 帧并在 21ms 恢复；600ms 样本未新增 reset，说明触发还取决于恢复瞬间 burst/帧边界；无需新增 Exo fault hook |
| T32 | P0 | 补稳定帧全链路分段 P50/P95 和 recovery 后 timestamp mapping | 两端 Cast SDK + ExoPlayer | 已完成 | `.14` mapping 持续 mapped；Cast 固定窗口按 rendered RTP timestamp 精确关联 AU/decoder/render；小米 60 秒 900 样本：capture-to-render 397/423ms、AU-to-decoder 5/9ms、decoder-to-render 383/397ms |
| T33 | P0 | 清理已进入 SampleQueue/decoder 的低延迟旧时间线 | ExoPlayer fork + Cast receiver | 已完成 | `.15` 新增 explicit sample backlog one-shot generation signal；Cast 800ms 阈值、generation 幂等、`setMediaSource(source,true)`、TCP initial WAIT_IDR；30 分钟未累积且未产生额外 backlog rebuild |
| T34 | P1 | ScreenCaptureKit `queueDepth=2/1` 单变量 A/B | Cast sender/QA | 已完成（保留 2） | depth=2 三轮正式样本加一轮 warmup 均 0 raw drop/restart/reset/gap/rebuild；depth=1 首轮仅 1 个新 capture frame、5 次 restart、45 次恢复请求，按 fail-fast 停止；release/default=2、Smooth=3 |
| T35 | P1 | Android 11+ codec low-latency capability profile A/B | ExoPlayer/Cast receiver | 未开始 | CONTROL/候选 profile 对比 decoder-input-to-render；configure/start/首帧失败自动回退；default 路径不变 |
| T36 | P2 | 更小 LoadControl 单变量实验 | ExoPlayer/Cast receiver | 未开始 | `150/500` 对比 `80-100/250-300`；只接受延迟改善且 rebuffer/CPU/内存不回归 |
| T37 | P1 | VideoToolbox runtime bitrate/DataRateLimits API 和安全回退 | Cast sender | 未开始 | 属性返回值、异步线程安全、7000/6000/5000/4000 切换、失败保持旧配置单测/真机验证 |
| T38 | P1 | TCP 拥堵状态机和码率梯度 | Cast sender + receiver feedback | 未开始 | 8/10/6/5/4Mbps、抖动/丢包/短断流矩阵；降档快、恢复慢、正常 LAN 始终 7000 |
| T39 | P2 | 恢复窗口码率斜坡实验，替代默认 per-frame 低质量 IDR | Cast sender/QA | 未开始 | IDR burst、恢复耗时、500ms 内主观清晰度、恢复振荡 A/B；默认关闭，可独立回滚 |
| T40 | P1 | 完成 30 分钟正常网、弱网矩阵和普通 RTSP 隔离回归 | QA | 未开始 | T18-T20 全量标准；crash/ANR/OOM、内存趋势、SampleQueue、延迟趋势和用户可见画面 |
| T41 | P0 | 下一阶段模块 code review 和性能边界审计 | Cast SDK + ExoPlayer | 未开始 | 检查 default 隔离、hot path 分配/日志/锁、文件/函数边界、回滚开关和测试覆盖 |

## 测试方案

### 单元测试

发送端：

- latest-frame-wins 与 default/smooth 隔离。
- bitrate × time budget 换算、clamp、大 IDR 和 framing headroom。
- pending age、bytes、not-sent 三类超限原因。
- 全局 IDR gate 对 PLAY/live control/PLI/FIR/backpressure 的去重。
- pacing 总期限和连接重建分支。
- runtime bitrate 7000/6000/5000/4000 属性更新成功、失败保持旧值和线程安全。
- congestion state machine 的 300-500ms 去抖、快速降档、10s 健康门槛、5-10s 逐级恢复和反振荡。
- recovery bitrate ramp 默认关闭；开启时 IDR/后续 P 窗口和恢复到稳定码率的时序可重复。

Cast-SDK 接收端：

- `SCHEDULED/THROTTLED/REJECTED/TIMEOUT` 状态转换。
- ACK 与 `idr_emitted` 任意顺序、requestId 不匹配、断线和重连。
- WAIT_IDR/backlog level 连续上报不形成请求风暴。
- one-shot PLI、FIR 和 rebuild 只按状态机升级一次。
- `DEFAULT`、`SMOOTH`、`LOW_LATENCY TCP`、`LOW_LATENCY UDP` policy 隔离。
- heartbeat 正常播放持续运行；request/ACK/异步 `idr_emitted` 与 ping 并发不饿死 heartbeat。
- WAIT_IDR、client 重连和 source generation 切换后 clock sync 能重新建立，旧 generation 不续写新会话。
- clock sync 过期时停止记延迟样本并上报 reason，恢复后样本继续增长，不复用过期 offset。

ExoPlayer fork：

- TCP low-latency reorder wait `0-2ms`；UDP 和 default 保持各自值。
- TCP packet queue age/depth reset 后进入 WAIT_IDR。
- TCP queue reset 的 media-period signal 只在 explicit low-latency TCP policy 下产生，Cast-SDK rebuild 后旧 SampleQueue 不再沿用。
- 完整 `SPS/PPS + IDR` 后恢复。
- one-shot PLI/FIR 的实际发送和失败上报。
- TCP reset 聚合诊断包含 expected/actual sequence、oldest age、queue span、inter-arrival max 和 loader stall，不增加默认逐包日志。
- recovery 前后 sample-to-RTP timestamp mapping 和 decoder-input-to-render 事件连续。
- policy disabled 时无额外 callback、日志、reset 和 hot-path 分配；第一阶段不做普通路径 SampleQueue age drop。

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

弱网自适应附加断言：

- 无整形、10Mbps 和 8Mbps 场景始终保持 `7000kbps`，不允许误降档。
- 6/5/4Mbps 持续拥堵时按梯度降档，pending age 不越过 `200ms` 后继续增长，SampleQueue 不进入秒级积压。
- 限速解除后至少健康 10 秒才升档，不出现每秒上下振荡；最终恢复 `7000kbps`。
- 每次码率切换保持 H.264 可解码连续、无黑屏/花屏；不因 bitrate-only update 额外请求 IDR。
- recovery ramp A/B 记录 IDR bytes/packets、first-last packet send duration、WAIT_IDR recover、500ms 清晰度和后续 P 帧总字节，防止只把 burst 延后。

### 普通业务回归

- 第三方 RTSP camera/live：`EXOPLAYER_DEFAULT`，不强制 TCP，不启用 aggressive reset。
- 自家 `SMOOTH + FORCE_TCP`：缓冲稳定，不启用 low-latency queue reset/rebuild signal。
- HLS、MP4、音乐、图片：播放器路由、首屏、控制和错误 UI 不变。
- Android 4.4/5.x：反射 API 缺失时降级，不崩溃、不改变默认 RTSP。
- diagnostics `off` 对 CPU、内存、线程、日志量和 APK 包体无可见回归。

## 验收标准

以下是下一阶段目标，不代表当前已经达成：

- `1920x1080 @ 30fps / 7000kbps` 全程保持，不因 TCP/UDP 模式降低产品指标。
- 正常 LAN 和可用带宽 `>=8Mbps` 时自适应码率不得误触发，稳定目标始终为 `7000kbps`。
- 正常 LAN 首 RTP 到首帧 P95 `<=500ms`。
- 时钟同步可用时，稳定 `captureToRender P95 <=500ms`、P99 `<=800ms`。
- sender application pending age P95 `<=100ms`，hard limit `<=200ms`。
- 正常播放 `sampleQueueBufferedAheadMs` 目标 `100-500ms`，不得随时长线性增长。
- queue 超过 `800ms` 后必须进入强恢复；恢复后 2 秒内回到 `<500ms`。
- WAIT_IDR 后完整 IDR 恢复目标 `<=800ms`；超时必须升级，不无限重发请求。
- rebuild 后恢复播放目标 `<=2s`，并清除旧 media connection 的积压。
- 10/30 分钟高运动视频无持续累积延迟、无无限 loading、无长期卡死。
- 可见花屏为 0；允许有统计明确、时间受控的短暂冻结。
- 持续带宽低于目标时允许临时降码率，但不得通过无限 TCP 排队维持虚假的 7Mbps；降档后 pending/SampleQueue 必须收敛，网络恢复后回到 7Mbps。
- 码率状态 60 秒内无持续上下振荡；恢复窗口实验的明显模糊时间 `<=500ms`，否则关闭实验。
- 独立反馈通道健康时，同一 recovery cycle 不重复发送 RTCP PLI/FIR。
- 普通第三方 RTSP 的 transport、buffer、日志量、CPU 和错误行为无回归。

## 灰度和回滚

- 所有新策略放在版本化 `TcpLowLatencyPolicy` 后，默认只对自家 `LOW_LATENCY` 开启。
- 分项灰度：`latestFrameWins`、`tcpNotSentLowat`、`transportAwareReorder`、`mediaPeriodRecoverySignal`、`conditionalRtcpFallback`、`idrPacing`、`adaptiveBitrate`、`recoveryBitrateRamp`、`codecLowLatencyProfile` 可独立关闭。
- `adaptiveBitrate` 初始默认关闭，仅实验设备开启；灰度稳定后也只在自家 `LOW_LATENCY + TCP` 且 sender/receiver capability 都支持时启用。
- `recoveryBitrateRamp` 始终独立于 `adaptiveBitrate`，首轮默认关闭；出现模糊、恢复变慢或振荡时只关闭该项。
- 接收端 capability 中声明支持的 policy version；发送端不认识时继续使用现有稳定 TCP 路径。
- media-period recovery signal 或 one-shot RTCP API 不可用时，Cast-SDK 回退到既有 backlog diagnostics + 受控 RTSP rebuild，不影响普通业务。
- 任一灰度项导致黑屏、恢复时间上升、CPU/内存明显回归或第三方 RTSP 变化，单独关闭该项，不需要回滚全部 fork。

## 下一步执行顺序

### 第一批：先恢复可信观测

1. T29：修复 clock sync heartbeat 和恢复后采样续接。
2. T30-T31：补 reset 事件关联并复现 370ms 滞留，根因明确前不改 300ms 阈值。
3. T32：补稳定帧逐段 P50/P95 和 decoder-input-to-render。
4. 按原规格重跑 10 分钟，确认样本持续到测试结束。

### 第二批：正常网络延迟 A/B

1. T15：仅对大 IDR 做 bitrate-aware pacing，先解决正常网络仍出现的 TCP AU deadline。
2. T34：ScreenCaptureKit queueDepth 2/1。
3. T35：capability-gated codec low-latency profile。
4. T36：最后才评估更小 LoadControl；每次只改一个变量。

### 第三批：自适应能力开发（先完成本地能力和自动测试）

1. T37：先提供可验证、可回退的 runtime bitrate/DataRateLimits 能力。
2. T38：实现 7000/6000/5000/4000 梯度和快降慢升状态机，先完成状态机单测和正常 LAN 不降码率验证。
3. T39：在默认关闭的实验档验证恢复窗口码率斜坡，不直接做 per-frame 模糊 IDR。

### 第四批：最后执行弱网与总验收

1. T19：Linux gateway / `tc netem` 环境就绪后执行限速、延迟、抖动、丢包和短断流矩阵。
2. T40：汇总 T18-T20 的正常网、弱网和普通 RTSP 结果。
3. T41：完成全模块 code review 和性能边界审计后才能灰度。

## 当前进展摘要

- 2026-07-10：完成现有 Cast-SDK、ExoPlayer 本地源码和真机报告复核，建立本文。
- 2026-07-10：小米 `M2010J19SC` 完成 release-key debug APK 覆盖安装。`1920x1080@30 / 7000kbps / FORCE_TCP` 120 秒 smoke 通过，首 RTP 到渲染约 `175ms`，该轮无丢包、gap 或 reset。
- 2026-07-10：同设备 10 分钟长跑失败。卡死前 `captureToRender P50/P95=421/427ms`、49 个样本；随后发生 17 次 queue reset、2 次 session rebuild，最终 `source_unreachable/no-packet-timeout`，末次采样已 604 秒无 RTP。发送端超过 `hold-secs=600` 后仍未退出，测试脚本在 690 秒超时。证据见 `qa/reports/receiver/functional/20260710-185707/tcp-screen-dynamic-600s/summary.md`。
- 2026-07-10：代码复核确认新的完整 AU queue 只覆盖入队边界，publisher 仍逐 `EncodedNal` 写 socket；非阻塞写中断会预先推进 RTP sequence 并丢弃同 NAL 后续 packet，recovery IDR 后续 NAL 也可能回到非可靠写路径。另有 screen session 先 join publisher、后停 capture 的退出顺序风险。上述问题修复前 T18 不得完成。
- 2026-07-10：完成 T21-T24。low-latency TCP 改为完整 AU transaction；重连缓存只发送最新完整 `SPS/PPS + IDR`，不再在 200ms deadline 内重放整个短 GOP；screen/camera stop 改为先停 capture；真机脚本改为 sender 日志直写文件并周期采样。`cast-sender-live-rtsp` 60 tests、receiver JVM 977 tests、Gradle player/app 构建和 60 秒真机 smoke 通过。
- 2026-07-10：小米 `M2010J19SC` 使用网页真实视频完成 `1920x1080@30 / 7000kbps / FORCE_TCP` 600 秒长跑。发送端 481,751 RTP packets、19,486 frames，socket block/error/pending 均为 0；接收端 481,856/481,849 receive/dequeue、18,803 rendered frames，`exoBufferedDurationMs/rtpQueueMs/sampleQueueMs=0`、queue reset/rebuild=0。P50/P95 从动态段早期约 `401/425ms` 变为末段 `436/555ms`，队列为 0，不能归因于 TCP backlog。
- 2026-07-10：同轮发现 7 次严格周期性的 `access-unit-corrupted -> WAIT_IDR -> recovered`，间隔约 87-89 秒并对应每 65,536 个 RTP packet 回绕；request/ACK/recovered 均为 7，等待 40-331ms，累计丢 7 个旧包和 24 个 WAIT_IDR AU。代码确认 `RtpPacketReorderingQueue.calculateSequenceNumberShift()` 使用 `MAX_SEQUENCE_NUMBER` 而不是 65,536 序列空间，导致 `65535 -> 0` 比较返回 0。证据见 `qa/reports/receiver/longrun/20260710-tcp-screen-webvideo-600s-retry/summary.md`，T27 已交 ExoPlayer 会话处理。
- 2026-07-10：ExoPlayer fork 发布 `2.19.1-labi.13`（source fix `944cc32560`），使用 65,536 序列空间修复 `65535 -> 0`，并补连续边界、跨 wrap 乱序和 wrap 后迟到旧包测试。Cast-SDK 默认依赖已切 `.13`，`dependencyInsight` 确认线上 Maven 生效；Legacy Exo 单测、receiver JVM `977` tests 和 release-key debug APK 构建通过，新包 buildID `20260710-205503-0d39cf58` 已安装小米。
- 2026-07-10：Mac 锁屏期间 ScreenCaptureKit 无可用显示源，先完成 `MacBook Air相机 / 1920x1080@30 / 7000kbps / FORCE_TCP` 30 秒 smoke。接收端确认 `1920x1080`、首 RTP 到渲染 `208ms`、sender 首 RTP 到渲染 `237.5ms`，2044/2044 packets、0 drop/gap/reset/rebuild、三个接收队列均为 0。摄像头约 60-70 RTP packets/s，无法在 10 分钟内覆盖 65,536 包回绕，不替代 T18 高动态屏幕长跑。证据见 `qa/reports/receiver/functional/20260710-camera-1080p-labi13-30s/summary.md`。
- 2026-07-11：使用 `.13` release-key debug APK完成 `1920x1080@30 / 7000kbps / FORCE_TCP` 600 秒屏幕复测。496465/496465 RTP packets、0 gap/drop/duplicate，跨约 7 次 wrap 无周期性恢复，SampleQueue/RTP/Exo buffer 均为 0；首帧 capture-to-render `255ms`。发生一次非 wrap 的 370ms reorder backlog reset，独立通道在 68ms 内恢复，未 rebuild。稳定延迟窗口 P50/P95 为 428/594ms，但 sample count 在 594 后停止增长，T18 仍保持进行中。证据见 `qa/reports/receiver/longrun/20260711-tcp-screen-1080p30-labi13-600s/summary.md`。
- 2026-07-11：ExoPlayer fork T30/T32 发布 `2.19.1-labi.14`（source `86731dbe1050877fd511c1626e260be812d6bf78`）。Cast-SDK 默认线上依赖已统一切 `.14`，反射聚合 expected/actual/last sequence、inter-arrival、extractor stall 和 sample/decoder mapping status；普通 RTSP 仍不启用 packet diagnostics/mapping 状态。
- 2026-07-11：小米完成 `.14` 同规格 600 秒复测。receiver 480656/480656 RTP、0 drop/gap/reset/rebuild，sender socket block/error/queued bytes 均为 0；capture-to-render P50/P95/max 434/459/488ms，窗口达到 900 后持续滚动，live-control ping/pong 1285/1272。T29 完成，T18 还需 30 分钟，T31 需受控触发 reset，T32 需启用 sender trace 补 decoder-input-to-render。证据见 `qa/reports/receiver/longrun/20260711-tcp-screen-1080p30-labi14-600s/summary.md`。
- 2026-07-11：T31 通过 debug `run-as` 接收端 400ms 调度暂停复现 TCP reorder reset：queueDepth=2、oldestAge/queueSpan=474ms，随后 WAIT_IDR 丢 6 帧并在 21ms 恢复，未 rebuild；600ms 样本未新增 reset，确认触发还与恢复瞬间 RTP burst/帧边界相关，不需要 ExoPlayer 新增 fault hook。证据见 `qa/reports/receiver/functional/20260711-tcp-receiver-pause-matrix-labi14/summary.md`。
- 2026-07-11：T32 完成精确 rendered RTP timestamp join 和两个 900 样本固定窗口。小米 60 秒 1080p30/7Mbps FORCE_TCP：capture-to-render P50/P95/max 397/423/441ms，AU-to-decoder 5/9/11ms，decoder-to-render 383/397/406ms；47980/47980 RTP、0 drop/gap/reset，sender 0 socket block/error/queued。当前主要延迟明确位于 decoder input 到 Surface render。证据见 `qa/reports/receiver/functional/20260711-tcp-stage-percentiles-labi14-60s/summary.md`。
- 2026-07-11：T18 `.14` 30 分钟同规格长稳 FAIL。最终 capture-to-render P50/P95/max 1912/1939/1953ms，SampleQueue/Exo buffered ahead 1300ms，AU-to-decoder 1294/1330ms，decoder-to-render 585/648ms；RTP 0 drop/gap，但 4 reset、1 rebuild。独立控制通道 request/ack/idrEmitted=7/7/7、ping/pong=3841/3841，证明反馈链路正常，根因是已入 SampleQueue/decoder 的旧时间线无法被 WAIT_IDR 清理。新增 T33 并已派发 ExoPlayer 会话。证据见 `qa/reports/receiver/longrun/20260711-tcp-screen-1080p30-labi14-stage-windows-1800s/summary.md`。
- 2026-07-11：ExoPlayer T33 发布 `.15`（source `8346a0c086e28945376f73599c344ee835bb2a72`，tag `exoplayer-rtsp-2.19.1-labi.15`，gh-pages `1b6c22e213`）。Cast 默认线上依赖切 `.15`，显式 low-latency policy 设置 SampleQueue backlog signal=on、threshold=800ms、TCP initial WAIT_IDR=on；按 recovery generation 幂等处理并优先 `setMediaSource(source,true)`。
- 2026-07-11：`.15` 30 分钟同规格复测 PASS：capture-to-render P50/P95/max 509/533/546ms，SampleQueue/Exo/RTP queue 均 0，1170849/1170849 RTP、0 drop/gap、1 reset；仅 1 次发送端 TCP deadline 后 no-packet rebuild，无 SampleQueue backlog rebuild signal，未复现 `.14` 的 1.9s 累积。证据见 `qa/reports/receiver/longrun/20260711-tcp-screen-1080p30-labi15-1800s/summary.md`。
- 2026-07-11：T20 普通 RTSP 默认隔离 smoke 通过。独立 1920x1080@30 RTSP publisher 经通用 `cast rtsp` 投送，小米为 PLAYING、首帧已呈现；`default + exoplayer_default + packetDiagnostics=false + recovery normal`，`.15` 新 policy 未进入普通业务路径。证据见 `qa/reports/receiver/functional/20260711-third-party-rtsp-default-labi15/summary.md`。
- 2026-07-11：Cast-SDK code review 将 mapping status 的健康帧路径改为由直接 RTP timestamp 推断 `mapped`，仅缺映射时反射新字段，避免每帧新增一次 `Class.getField`。最终 buildID `20260711-101241-12ceff1f` 覆盖安装后，同规格 30 秒 smoke 通过：2506/2506 RTP、0 drop/gap/reset、ping/pong 27/27、P50/P95/max 363/391/406ms。
- 2026-07-11：清理 Roadmap 重复任务号：SampleQueue backlog 恢复保留为唯一 T33，后续正常网 A/B、自适应能力、总验收和 review 顺延为 T34-T41；弱网矩阵固定放在本地能力、正常网 A/B 和普通 RTSP 隔离完成之后。
- 2026-07-11：T15 在小米 1080p30/7Mbps FORCE_TCP 完成 `1.2x / 1.5x / off` 单变量真机验证。1.2x pacing 84 个 IDR、累计 sleep 9720ms、raw drop 600、queue reset 8；1.5x pacing 109 个 IDR、累计 sleep 8902ms、raw drop 116、queue reset 5；off 控制组 raw drop 1、reset/keyframe request/rebuild 均 0。结论：publisher 线程内同步 sleep 会形成反馈放大，生产实现已撤回。输入播放器没有全程循环，因此该轮用于否决同步 sleep，不作为高动态画质/吞吐验收。证据见 `qa/reports/receiver/functional/20260711-tcp-idr-pacing-ab/summary.md`。
- 2026-07-11：T34 使用真正循环的 1920x1080 动态视频完成 ScreenCaptureKit queueDepth A/B。depth=2 的 3 轮正式样本和 1 轮 warmup 均无 raw drop、capture restart、RTP reset/gap 或 rebuild；depth=1 首轮只有 1 个新 capture frame、5 次 capture restart，发送端进入 `capture_stalled`，接收端产生 45 次恢复请求，按 fail-fast 停止后续重复。默认继续 2，Debug-only A/B 入口保留，普通/release 路径不读环境变量。该批次暴露 clock sync 未续约，capture-to-render 分位数不可用，单列为观测回归，不影响 depth=1 的稳定性否决。证据见 `qa/reports/receiver/functional/20260711-screen-queue-depth-ab/summary.md`。
- 2026-07-11：下一阶段确定按“观测续接 -> reset 根因 -> 稳定分段 -> 正常网单变量 A/B -> 弱网自适应码率”推进。正常网络继续固定 1080p30/7Mbps；持续拥堵允许 7000/6000/5000/4000 临时降档并快降慢升。单独降低 IDR质量不作为默认方案，只保留短恢复窗口码率斜坡实验。
- 已确认 TCP 基线具备 non-blocking write、`TCP_NODELAY`、pending age/cap、动态 `SO_SNDBUF`、WAIT_IDR、backlog policy、独立反馈通道和自适应 GOP。
- 2026-07-10：ExoPlayer fork 完成并发布 `2.19.1-labi.12`（source `b1d53e9e9faf3310f006c2cf00de778a816e33e1`），提供 transport-aware reorder、media-period rebuild signal、one-shot PLI/FIR；fork targeted/full RTSP tests 和 release AAR 通过。
- 2026-07-10：Cast sender 完成 complete-AU latest queue、bitrate/time TCP budget、Darwin `TCP_NOTSENT_LOWAT/SO_NWRITE`、session IDR gate 和 requestId/generation 关联；相关五个 crate 共 `112` tests 通过。
- 2026-07-10：Cast receiver 完成异步 live-control reader/dispatcher、恢复周期去重、external primary + PLI/FIR fallback、media-period signal 到受控 rebuild、`.12` Maven 集成；receiver JVM `975` tests（无 Exo classpath 的 fork integration case skip）通过，Gradle `player-exo-legacy/core` tests 和 debug APK assemble 通过。
- Gradle `dependencyInsight` 已确认最终 App runtime 使用 `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.13`，不是本地源码包。
- Code review 已修复：不完整 AU 后误接收 P 帧、throttled 请求覆盖原 scheduled requestId、`idr_emitted` 未进入 eventCount、Windows gate 永不解除、旧 media-period signal 误重建新 session 等边界。
- T27 sequence wrap、T18 30 分钟正常网、T20 普通 RTSP隔离和 T33 SampleQueue backlog 恢复边界已完成；T19 弱网矩阵尚未执行，当前不能宣称全部弱网产品验收完成。

## ExoPlayer E24/T64 TCP depth reset 最小包龄

### 2026-07-13 实施复核

- H8 现场的 `queueDepth=240, oldestAgeMs=106, queueSpanMs=106` 来自
  `TransferRtpDataChannel.shouldFlushBacklog()` 的 `age OR depth` 条件。1080p/7Mbps 的 IDR
  或高运动 burst 可以在约 100ms 内达到 240 个 RTP packet；depth 单独触发不能证明消费端
  持续滞留，会无谓进入 `QUEUE_RESET -> WAIT_IDR -> ACTION_REBUILD_REQUIRED`。
- 保留 `oldestAge >= tcpInterleavedBacklogResetMs` 的独立硬触发，不放宽生产 `300ms`。
  depth 分支改为同时满足 `queueDepth >= tcpInterleavedBacklogResetPackets` 和
  `oldestAge >= tcpInterleavedBacklogDepthResetMinAgeMs`。
- 新 Builder API：
  `setTcpInterleavedBacklogDepthResetMinAgeMs(long)`。未调用时继承
  `tcpInterleavedBacklogResetMs`，不引入新的猜测阈值；显式设为 `0` 可关闭 depth 分支而保留
  age 硬触发。当前 Cast 配置 `resetMs=300/resetPackets=240` 即使不调用新 API，也不会在
  `240 packets / 106ms` reset。
- 变更只存在于显式 `RtspBacklogRecoveryPolicy.enabled` 的 TCP interleaved envelope 分支。
  普通 `EXOPLAYER_DEFAULT + DISABLED + listener null + packet diagnostics false` 仍直接写原始
  `byte[]` queue，不读取时钟、不创建 envelope、不执行新增比较。UDP、RTP reorder、WAIT_IDR、
  PLI/FIR、SampleQueue 和音频恢复判断均未修改。

### E24 状态

| ID | 状态 | 验证 |
| --- | --- | --- |
| E24/T64 | 已实现并验证，待发布 | `240 packets / 106ms` 不 reset；packet + 显式 minimum-age reset；`age=300ms` 独立 reset；depth-only/default 不改变原队列行为；reset stats 字段准确；完整 RTSP unit test 和 release AAR 构建通过 |

## ExoPlayer T35/T36 实施复核/进展

### 2026-07-11 方案复核

- 已从 Cast-SDK 工作树同步本 roadmap；以 `2.19.1-labi.15` 为基线，T15 同步 IDR pacing 已被真机 A/B 否决，T34 `queueDepth=1` 首轮 capture stalled，二者均不进入本轮 fork 改动。
- T35 仅新增通用、默认禁用的 Android 11+ `MediaFormat.KEY_LOW_LATENCY` capability profile。profile 必须显式启用且命中调用方提供的 codec allowlist 才设置标准 key；不使用反射和厂商私有 key，Android 4.4-10 不执行该分支。
- T35 的 configure/start 失败由 renderer 对同一 codec 自动以无 hint 配置重试一次，再继续既有 decoder fallback。首帧超时不能由 renderer 擅自重建播放器或清媒体时间线：Cast receiver 持有 session generation、Surface 和 RTSP rebuild 权限，应基于既有 `onVideoDecoderInitialized`、`onVideoCodecError`、`onRenderedFirstFrame` 限流地重建为未启用 profile 的播放器；fork 不新增 decoder hot-path callback。
- T36 不增加新的 fork LoadControl policy：`DefaultLoadControl.Builder#setBufferDurationsMs(...)` 已完整表达候选 `80-100/250-300/30-50/80-100ms`，fork `setMinBufferFloorMs(...)` 已允许显式下调 loading floor。参数选择、单变量 A/B、首帧/rebuffer/seek 多轨评估归 Cast receiver；fork 保持默认值和 `DEFAULT_MIN_BUFFER_FLOOR_MS=500` 不变。

### 本轮任务状态

| ID | 状态 | ExoPlayer 侧范围 | 默认隔离 |
| --- | --- | --- | --- |
| T35 | ExoPlayer 已发布 `2.19.1-labi.16` | 标准 Android 11+ low-latency key、allowlist、configure/start 无 hint 自动重试；首帧超时 rebuild 由 Cast receiver 使用既有 video renderer 事件执行 | profile 默认 `DISABLED`；普通 renderer 不设置 key、不增加 callback 或 hot-path 分配 |
| T36 | 已完成设计复核，待 Cast-SDK A/B | 复用既有 `DefaultLoadControl.Builder` API，补 core 单测锁定默认/显式 floor 行为 | 不改默认 buffer 值，不做 SampleQueue 静默丢弃或 RTSP 专用加载分支 |

### 2026-07-11 发布与验证

- 已发布 `com.zknowai.exoplayer:*:2.19.1-labi.16`。实现 source commit/tag 为 `d6a25424bc` / `exoplayer-rtsp-2.19.1-labi.16`，GitHub Pages commit 为 `44499822b1`。
- 发布模块：`exoplayer-common`、`exoplayer-container`、`exoplayer-database`、`exoplayer-datasource`、`exoplayer-decoder`、`exoplayer-extractor`、`exoplayer-core`、`exoplayer-hls`、`exoplayer-rtsp`。
- 已通过：`MediaCodecLowLatencyProfileTest`、`DefaultLoadControlTest`、`MediaCodecVideoRendererTest`、完整 `:library-rtsp:testDebugUnitTest`、`:library-rtsp:assembleRelease`。
- 远端 `exoplayer-core` metadata 的 `latest/release` 均为 `2.19.1-labi.16`；AAR SHA256 为 `a48d0d62704e597a1221d4f7e02addbc1bf4ce32a8f7985c8b0b3afd7b6813b4`，POM SHA256 为 `62130c90dda51bf00f9b709a1a6ed587406dfd20f617509c5e05519a8a8ee9c8`。`javap` 已确认 `MediaCodecLowLatencyProfile`、`DefaultRenderersFactory#setMediaCodecLowLatencyProfile(...)` 和 builder setter 均在远端 AAR。

## TCP Media-Period Recovery Signal 修复

### 2026-07-11 根因与边界

- Cast-SDK T42 真实视频现场的 `expected/actual/last sequence=-1` 证明 reset 来自 `TransferRtpDataChannel.maybeFlushBacklog()`，不是 `RtpPacketReorderingQueue`。
- `.16` 中 `TransferRtpDataChannelFactory` 在 `RtspMediaSource.Factory` 创建时持有外层 listener；TCP channel 直接上报 `onRtspBacklogQueueReset`，绕过 `RtspMediaPeriod.ForwardingRtspDiagnosticsListener`，因此未调用 `maybeNotifyMediaPeriodRecoveryRequired()`，没有 `ACTION_REBUILD_REQUIRED`。
- 修复在 `RtpDataLoadable` 创建 channel 后、注册 interleaved listener 前，将其 diagnostics listener 重绑为当前 period forwarding listener。`TransferRtpDataChannel` 使用 `volatile` 引用保证 RTSP message thread 可见；没有 RTP hot-path 日志、IO、锁、阻塞 callback 或逐包对象分配。
- `RtspMediaPeriod` 现在为每个实际 queue-reset rebuild request 递增 period-local `recoveryGeneration`，不再使用旧构造器的 generation `0`。transport queue 触发的 stats 明确以 `sampleQueue/mediaPeriodBufferedAheadMs=TIME_UNSET` 表示其来源不是 SampleQueue backlog。
- 默认隔离：`listener=null` 时 forwarding listener 不创建；policy `DISABLED` 时不会产生 reset flush 或 rebuild signal。普通 RTSP 不改变 transport、buffer、decoder 或 retry 行为。

### 验证

- 新增 `tcpInterleavedChannelReset_forwardsMediaPeriodRecoverySignal`：复现 TCP queue reset 绕过路径，验证 `onRtspBacklogQueueReset -> ACTION_REBUILD_REQUIRED`；连续两次 reset generation 为 `1`、`2`。
- 已通过定向 `RtspFeedbackApiTest`、`TransferRtpDataChannelTest`、完整 `:library-rtsp:testDebugUnitTest`、`:library-rtsp:assembleRelease`。
- 已发布 `com.zknowai.exoplayer:*:2.19.1-labi.17`。source commit/tag 为 `2fa74c7679` / `exoplayer-rtsp-2.19.1-labi.17`，GitHub Pages commit 为 `837c900570`。
- 远端 RTSP metadata 的 `latest/release` 均为 `2.19.1-labi.17`；AAR SHA256 为 `8ed198be164009f37ce552194fc205f3eab3e7ecd28de5c900c963a29c13083a`，POM SHA256 为 `8e09c14889fb8426beb06ca3b482c6d988ac0974c54c8b79c43c7f0503d52f30`。

## T44 TCP Read-Stall Snapshot

### 2026-07-11 实施复核

- T43 整 AU admission/staging 已由 Cast-SDK 经过五轮真机验证否决并撤回；本 fork 不重新引入 staging、事务 buffer、阈值放宽或等待时间。
- 真机 reset 为 `TransferRtpDataChannel` 特征：sequence 字段均为 `-1`、`queueDepth=1`、queue age/span `300-550ms`。`RtspMessageChannel` receiver callback 在其专用 loader thread，同期 main dispatch `2-8ms`、Java/native heap 无压力；当前证据无法把根因归为主线程或内存。
- 原有 `extractorReadStallMs` 只在 RTP 包已经被 `RtpExtractor` 成功读取后更新，reset 会先丢掉仍在 Transfer queue 的包，因此对本故障存在盲区。没有足够证据安全修改 socket receiver loop、调度或 queue/recovery 阈值。
- 新增低频 reset snapshot 字段：`dataChannelReadInProgress`、`dataChannelReadInProgressMs`、`dataChannelConsumerStallMs`、`dataChannelReaderThreadName`。仅当显式 TCP backlog recovery policy 已启用且 diagnostics listener 非空时维护原始状态；不增加逐包对象分配、日志、锁、IO、阻塞 callback 或普通 RTSP 开销。
- Cast-SDK 后续应只在 `onRtspBacklogQueueReset` 时读取这四个字段：`readInProgress=true` 表示卡在 channel read；否则 `consumerStallMs` 指向 loader 已离开 channel read 后的 extractor/output/scheduling 停顿。拿到真机分类证据前，不改 reset/WAIT_IDR/no-packet 阈值。
- 已发布 `com.zknowai.exoplayer:*:2.19.1-labi.18`。source commit/tag 为 `4a9542150360cec0847ec2da2410afe15663c0b1` / `exoplayer-rtsp-2.19.1-labi.18`，GitHub Pages commit 为 `a1bd7b97cab0acd8df32476f59ffc1cb5e08312e`；RTSP AAR/POM SHA256 分别为 `e52105b0acb4599b0d2f20ebc487ff8335ca152962a7e7f5cf060dd5d8289070` / `228108e6c0dd5e55608b74ee993a59de7b40c9d8e6e6d6c5366c0747da7a66e6`。

## T46 TCP Packet/Arrival 原子关联

### 2026-07-12 实施复核

- `.18` 小米真实视频 reset 的 `queueDepth=1` 与 `oldestAgeMs/queueSpanMs=406` 是逻辑不一致状态，不是 receiver loader 实际停顿：`dataChannelConsumerStallMs=0` 表明 consumer 刚完成 read。
- 根因是 low-latency recovery 分支将 packet 和 arrival timestamp 分别写入两个 `LinkedBlockingQueue`。producer 的 `packetQueue.add(data)` 与 timestamp queue `add(arrival)` 之间，consumer 可以先取走 data，导致旧 timestamp 滞留并给下一 packet 错误计龄。
- 修复将该显式 policy 分支改为单一 `PacketEnvelope(data, arrivalElapsedRealtimeMs)` queue；dequeue、oldest age、span 和 reset clear 均只处理同一 envelope，无法产生 orphan timestamp。普通 RTSP `DISABLED` 分支继续使用原始 `LinkedBlockingQueue<byte[]>`，不增加时钟、对象、锁或 callback。
- low-latency recovery 分支原本每 packet 已分配两个 queue node 和一个装箱 `Long`；新实现为一个 queue node 和一个 envelope，不新增锁，并减少该显式实验路径的分配。
- 测试覆盖旧竞态等价时序（首包 dequeue 后下一包入队）、FIFO、reset clear 后下一 packet、close 语义；不放宽 `300ms` reset 阈值。
- 已发布 `com.zknowai.exoplayer:*:2.19.1-labi.19`。source commit/tag 为 `e3509cd7fda358148d70de8079165f45bcdf2576` / `exoplayer-rtsp-2.19.1-labi.19`，GitHub Pages commit 为 `1bf60be928bc927c897003a7852dd07d647ca99f`；RTSP AAR/POM SHA256 分别为 `00f2fcdbf107980e5c32d5fe4c7f707359358f2b0d7ecacd6f34bf4f0170d219` / `64ae009a3511fd8a0012c46c6793ca6976ae1e62ec9144d672cc2aac22dc4648`。

## 双轨音频恢复隔离

### 2026-07-13 实施复核

- `.23` 已让 AAC/audio track 不发送 PLI/FIR，并为 `RtspSampleReadStats` 增加 `sampleMimeType`，但 code review 发现未知或已替换 track 的 recovery lookup 默认返回 video，stale audio reset 仍可能误触发 rebuild，因此 `.23` 不作为最终集成版本。
- `.24` 将 recovery 判定改为 fail-closed：只有显式 policy enabled、TCP interleaved、且当前 track MIME 明确为 video 时，queue/backlog reset 才能触发 `ACTION_REBUILD_REQUIRED`。audio、unknown/stale track、UDP 和 policy disabled 均不触发。
- `RtpLoadInfo.requestKeyFrame()` 与 media-period recovery 共用 MIME 判定；audio/unknown 不发送 PLI/FIR。视频 SampleQueue backlog 与视频 TCP reset 的既有低延迟行为保持不变。
- 默认普通 RTSP 不启用 recovery policy；新增判断只在低频 feedback/reset 边界执行。没有 RTP/sample hot-path 日志、JSON、IO、锁、阻塞 callback 或逐包对象分配。
- 测试覆盖 video/audio/unknown MIME、policy/transport gate、unknown TCP channel reset、audio SampleQueue backlog、`sampleMimeType` value object；定向 `RtspFeedbackApiTest`、完整 `:library-rtsp:testDebugUnitTest` 和 `:library-rtsp:assembleRelease` 均通过。
