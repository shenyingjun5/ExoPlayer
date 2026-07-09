# RTCP SR Precise Latency Roadmap Review

Date: 2026-07-09

## Conclusion

Cast-SDK 的 RTCP Sender Report roadmap 方向是正确的：RTCP SR 应由发送端周期性发送，后续 ExoPlayer fork 负责解析标准 RTCP SR 并通过 diagnostics listener 上浮，Cast-SDK 再结合低频 `time_sync` 和 render RTP join 计算 `capture_to_render_ms`。

最新阶段边界：自家 RTSP server P0 已转为发送标准 RTCP SR。ExoPlayer fork 从本轮开始进入 P1/P0-next：解析接收端入站 RTCP SR，并通过 diagnostics listener 上浮给 Cast-SDK 反射接入。

需要保留的 ExoPlayer 限制是：当前 ExoPlayer fork 不只是没有 `onRtcpSenderReport(...)` 事件，也没有完整的入站 RTCP 数据消费路径。

- TCP interleaved 当前只把 RTP channel 注册到 `RtspMessageChannel`，现有 RTCP channel 主要用于 PLI/FIR 出站发送。
- UDP 当前创建了 RTP/RTCP 端口对，RTCP channel 已可用于 PLI/FIR 出站发送，但没有 loader/read loop 消费入站 RTCP packet。
- 因此 SR P0 不能只加 parser 和 listener，还必须补 TCP/UDP 两条 RTCP receive path。

本轮改动只限 ExoPlayer fork，不修改 Cast-SDK。实现落在 `library/rtsp`，默认无 listener 时不启动 RTCP SR 入站解析。

## Current ExoPlayer Fork State

### Existing RTCP Feedback Send Path

相关源码：

- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriod.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtcpFeedbackPacket.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspClient.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannel.java`

现有能力：

- `RtspMediaSource#requestKeyFrame(...)` 可触发 RTCP PLI/FIR。
- TCP interleaved 下通过 `RtspClient#sendInterleavedBinaryData(...)` 往 RTCP channel 发出 PLI/FIR。
- UDP 下通过 `RtpDataChannel#sendRtcpPacket(...)` 从绑定的 RTCP socket 发出 PLI/FIR。
- `RtspDiagnosticsListener` 已有 PLI/FIR sent/failed/throttled、RTP packet、H.264 AU、sample-read、decoder/render join、WAIT_IDR 等事件。

这部分是 RTCP 出站反馈，不等价于 RTCP 入站 SR。

### Missing RTCP SR Receive Path

相关源码：

- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMessageChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspClient.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/TransferRtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpDataChannel.java`

当前限制：

- `TransferRtpDataChannel#getTransport()` 宣告 `interleaved=%d-%d`，即 RTP channel 和 RTCP channel 是一对。
- `RtspMediaPeriod.RtpLoadInfo` 当前只把 `rtpDataChannel.getInterleavedBinaryDataListener()` 注册给 RTP channel。
- `TransferRtpDataChannel#onInterleavedBinaryDataReceived(...)` 默认把收到的数据全部进入 RTP packet queue，供 `RtpExtractor` 读取。
- 发送端发来的 RTCP SR 如果落在 RTCP interleaved channel，当前没有对应 listener 消费；也不能直接复用 RTP listener，否则 RTCP packet 会被错误送进 RTP extractor。
- `UdpDataSourceRtpDataChannel` 有 associated `rtcpChannel`，并支持 `sendRtcpPacket(...)`，但 `read(...)` 只读 RTP `dataSource`；RTCP channel 没有入站读取循环。

## Cast-SDK Roadmap Amendments

建议把 Cast-SDK roadmap 中当前阶段边界改成：

```text
本阶段先聚焦自家 RTSP server：

- 单视频 TCP interleaved 默认 `interleaved=0-1`，其中 `0` 为 RTP，`1` 为 RTCP。
- 未来多 track 默认按 track 顺序分配：`0-1 / 2-3 / 4-5`。
- 自家 server 可以固定相邻 channel 约定，便于 receiver、抓包和自动化测试。
- 通用 RTSP client 仍应以 `SETUP` response 的 `Transport` header 为准，不能硬编码所有服务端都相邻。
- ExoPlayer fork 本阶段不改动；旧 artifact 保持安全降级，不伪造 `capture_to_render_ms`。
```

建议把 “ExoPlayer fork 需要补 RTCP SR 解析和 listener” 改为后续 P1/P0-next：

```text
ExoPlayer fork 需要补齐两层能力：

1. RTCP SR parser / diagnostics API：
   - 新增标准 RTCP compound packet parser，解析 payload type 200 的 Sender Report。
   - 新增 RtspDiagnosticsListener.onRtcpSenderReport(RtcpSenderReportStats stats)。
   - stats 至少包含 trackId、mediaSsrc、rtpTimestamp、ntpTimeMs、receivedElapsedRealtimeMs、transportMode、clockRate、packetCount、octetCount。

2. RTCP 入站 channel：
   - TCP interleaved 下除 RTP channel 外，还要注册 RTCP channel listener，通常是 track 的 interleaved RTP channel + 1。
   - UDP 下除 RTP loader 外，还要消费 associated RTCP UDP socket 的入站 packet。
   - 入站 RTCP parser 必须和现有 PLI/FIR 出站发送互不阻塞，不能把 RTCP packet 喂给 RTP extractor。
```

建议新增或替换时间语义说明：

```text
RTCP SR 建立的是 rtpTimestamp -> sender media/presentation time 映射，不总是等价于真实 capture time。

- camera source：RTP timestamp 应基于 `CMSampleBuffer PTS`，通常可近似代表采集/展示时间。
- screen source：RTP timestamp 基于 synthetic presentation time，代表屏幕流媒体排程时间，不一定等于真实屏幕内容变化时刻。
- 因此稳定阶段指标应标注 latency confidence；只有 source timestamp 与真实 capture/presentation time 对齐时，`capture_to_render_ms` 才能解释为严格采集到渲染延迟。
```

建议补充多 track 说明：

```text
SR mapper 必须以 trackId + mediaSsrc + clockRate 作为 key。视频 H.264/H.265 通常是 90000Hz；未来音频可能是 48000Hz 或其他 clock rate，不能把 90000 写成通用假设。RTP timestamp 是 32-bit unsigned，需要 wraparound 展开后再做线性映射。
```

建议补充 SDP/SSRC 说明：

```text
P0 可以不在 SDP 中补 `a=ssrc`。SR packet 和 RTP packet 里的 SSRC 已足够建立 `SSRC -> RTP timestamp -> SR` 映射。`a=ssrc` 可作为 P1 诊断增强，用于提升日志可读性和多 track debug 便利性。
```

建议补充兼容性说明：

```text
Cast-SDK 反射接入应先判断 RtcpSenderReportStats 类和 RtspDiagnosticsListener.onRtcpSenderReport(...) 是否存在。旧 artifact 缺少该能力时保持 captureToRenderAvailable=false，reason 使用 waiting_for_rtcp_sender_report 或 rtcp_sr_not_supported，不能回退到伪造逐帧延迟。
```

## Proposed ExoPlayer API

新增 value type：

```java
public final class RtcpSenderReportStats {
  public final int trackId;
  public final int mediaSsrc;
  public final long rtpTimestamp;
  public final long ntpTimeMs;
  public final long ntpSeconds;
  public final long ntpFraction;
  public final long receivedElapsedRealtimeMs;
  public final @RtspTransportMode.Mode int transportMode;
  public final int clockRate;
  public final long packetCount;
  public final long octetCount;
}
```

新增 listener default method：

```java
default void onRtcpSenderReport(RtcpSenderReportStats stats) {}
```

API 设计判断：

- 放在 `RtspDiagnosticsListener` 合适，因为 SR 是观测能力，不应该改变播放默认行为。
- `RtspDiagnosticsListener` 当前已经用 default methods；新增 default method 对 Cast-SDK 反射接入友好。
- `packetCount`、`octetCount`、`rtpTimestamp`、`ssrc` 都按 unsigned 32-bit 存入 `long`，避免 Java `int` 符号位误读。
- `ntpSeconds`、`ntpFraction` 建议保留 raw 字段，`ntpTimeMs` 作为便捷字段；如果后续需要更高精度，可扩展 `ntpTimeUs`，但 P0 用 ms 足够支撑当前 debug 聚合。
- `receivedElapsedRealtimeMs` 使用接收端单调时钟，不用 wall clock。

## Implementation Plan

### Completed. ExoPlayer RTCP SR Receive

实现范围：

- `RtcpSenderReportPacket`：解析 RTCP compound packet，提取 payload type 200 的 Sender Report，忽略 RR/SDES/BYE/PSFB 等非 SR。
- `RtcpSenderReportStats`：公开 `trackId`、`ssrc`、`rtpTimestamp`、`ntpTimeMs`、`rawNtpSeconds`、`rawNtpFraction`、`receivedElapsedRealtimeMs`、`transportMode`、`clockRate`、`packetCount`、`octetCount`。
- `RtspDiagnosticsListener#onRtcpSenderReport(RtcpSenderReportStats)`：default no-op，Cast-SDK 可反射判断并接入。
- TCP interleaved：在已有 RTP interleaved listener 之外，为 RTCP channel 注册 listener，RTCP payload 不进入 `TransferRtpDataChannel` / RTP extractor。
- UDP：通过 `RtpDataChannel#readRtcpPacket(...)` 和 `UdpDataSourceRtpDataChannel` associated RTCP channel 增加入站 RTCP 读取；`RtspMediaPeriod` 为 diagnostics listener 场景启动独立 RTCP loader。
- 默认 passive：只有 `rtspDiagnosticsListener != null` 时才注册/启动 SR 入站处理；无 listener 时不维护 SR 状态，不增加高频事件。

### Implemented Files

源码：

- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtcpSenderReportPacket.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtcpSenderReportStats.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspDiagnosticsListener.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannel.java`
- `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriod.java`

测试：

- `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtcpSenderReportPacketTest.java`
- `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/UdpDataSourceRtpDataChannelTest.java`
- `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtspFeedbackApiTest.java`

### Verification

Targeted command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtcpSenderReportPacketTest --tests com.google.android.exoplayer2.source.rtsp.UdpDataSourceRtpDataChannelTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtspMessageChannelTest
```

Result: passed. `BUILD SUCCESSFUL in 2s`.

Full RTSP command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest
```

Result: passed. `BUILD SUCCESSFUL in 12s`.

### Cast-SDK Reflection Contract

Cast-SDK 可继续反射 `RtspMediaSource.Factory#setRtspDiagnosticsListener(...)`，并在 listener proxy 中处理：

```java
void onRtcpSenderReport(RtcpSenderReportStats stats)
```

字段读取：

- `trackId`
- `ssrc`
- `rtpTimestamp`
- `ntpTimeMs`
- `rawNtpSeconds`
- `rawNtpFraction`
- `receivedElapsedRealtimeMs`
- `transportMode`
- `clockRate`
- `packetCount`
- `octetCount`

旧 artifact 没有 `RtcpSenderReportStats` 或 `onRtcpSenderReport(...)` 时，继续保持 `captureToRenderAvailable=false`。

### Remaining Notes

- TCP interleaved RTCP receive 当前按 `rtpDataChannel.getLocalPort() + 1` 注册，匹配本 fork 生成的 `interleaved=%d-%d` 传输口径；更通用的 “按 SETUP response 实际 `interleaved=x-y` 回填” 可作为后续兼容增强。
- UDP RTCP receive 只在 diagnostics listener 存在时启动，timeout 不触发 playback error。
- SR parser 不参与 PLI/FIR、WAIT_IDR、drop-until-IDR 或 backlog recovery 决策。

### P0/P1. Mapper Confidence Signals

可在 ExoPlayer 或 Cast-SDK 侧补：

- SR sample age。
- SR sample count。
- RTP timestamp wrap count 或 expanded timestamp。
- clockRate mismatch / SSRC switch / SR jump。
- transportMode 分布。

建议 Cast-SDK 维护 mapper 和 confidence，ExoPlayer 只上浮原始 SR 事件，避免播放器 fork 持有业务状态。

### P1. Audio Extension

未来音频需要：

- 音频 RTP clock rate 通常不是 90000，可能是 48000 或 SDP 指定值。
- 精确 audio render/output 延迟不是 `VideoFrameMetadataListener` 能解决，需要 AudioTrack/audio sink 时间口径。
- 当前 P0 聚焦视频 RTSP live，不阻塞音频扩展。

## Side Effect Review

### PLI/FIR

SR 解析不应改变 PLI/FIR：

- PLI/FIR 出站仍由 `RtcpFeedbackPolicy`、WAIT_IDR、backlog recovery 或 Cast-SDK 显式请求控制。
- SR parser 被动解析入站 RTCP，不能主动触发 key-frame request。
- parser 必须忽略 PSFB payload type 206，避免把对端或本端 feedback 混成 SR。

### WAIT_IDR and Drop-Until-IDR

SR 只提供时钟映射，不参与 H.264 AU 完整性判断，不改变 WAIT_IDR 状态机。

### Performance

默认路径必须 passive：

- 无 diagnostics listener 或未启用 SR diagnostics 时，不维护 SR 状态，不做高频日志，不做 JSON 序列化。
- SR 频率通常 1s 一次，不是逐包热路径。
- TCP interleaved 下额外注册 RTCP channel listener 是低频路径，但要避免把 RTCP packet 放入 RTP queue。
- UDP RTCP receive loop 需要短超时和干净 close，避免线程泄漏或阻塞 release。

## Current Stage Answers

- 自家 RTSP server 应固定默认相邻 interleaved channel：单视频 `0-1`，未来多 track `0-1 / 2-3 / 4-5`。
- 通用 RTSP client 仍应以 `SETUP` response 为准，但本阶段不要求改 ExoPlayer。
- SDP `a=ssrc` P0 可以不补；SR/RTP packet 中的 SSRC 足够，`a=ssrc` 后续作为诊断增强。
- RTCP SR 映射的是 media/presentation time，不应无条件声明为真实 capture time。
- UDP RTCP receive loop 属于 ExoPlayer 后续 P1/P0-next，不阻塞当前 server SR 阶段。
- `ntpTimeUs` P0 暂不暴露；`ntpTimeMs + rawNtpSeconds + rawNtpFraction` 足够。

## Open Questions

- 当前 RTSP server 是否一定按 track 使用 `interleaved=RTP-RTCP` 的相邻 channel；若服务端返回非相邻 channel，后续实现应以 SETUP transport header 的实际 interleaved range 为准。
- SDP 是否提供稳定 media SSRC；如果没有，需要以首次 SR sender SSRC 作为 runtime media SSRC。
- 发送端 RTP timestamp 当前是否严格基于 capture/presentation time；这决定 SR 计算出来的是 capture-to-render 还是 send-media-to-render。
- ExoPlayer 后续实现时，UDP RTCP receive loop 放在 `RtspMediaPeriod` 还是 `RtpDataChannel` 内部更合适，需要代码实验确认关闭和错误传播边界。
