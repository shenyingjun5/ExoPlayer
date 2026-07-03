# RTSP Feedback Implementation Progress

## Current Scope

第一阶段只实现 RTSP feedback/diagnostics API 骨架和内部配置透传，保持 ExoPlayer 2.19.1 默认行为不变。

## Repository Check

- branch: `labi-rtsp-feedback-exoplayer-2.19.1`
- origin: `https://github.com/shenyingjun5/ExoPlayer.git`
- upstream fetch: `https://github.com/google/ExoPlayer.git`
- upstream push: `DISABLED`
- initial untracked files: `.codegraph/`, `AGENTS.md`, `labi-docs/`

## Phase 1 Plan

| ID | Task | Status | Verification |
| --- | --- | --- | --- |
| P1.1 | Add lightweight public API types for diagnostics, feedback requests, feedback policy, and transport mode | Done | `RtspFeedbackApiTest` covers defaults and value behavior |
| P1.2 | Add optional setters on `RtspMediaSource.Factory` | Done | `RtspFeedbackApiTest.factorySetters_passConfigurationToMediaPeriod` |
| P1.3 | Pass listener and policy through `RtspMediaSource`, `RtspMediaPeriod`, `RtspClient`, `RtpDataLoadable`, `RtpExtractor`, and `RtpPacketReorderingQueue` | Done | Constructor/default behavior covered by API tests |
| P1.4 | Emit only low-cost diagnostics events when listener is non-null | Done | RTSP unit tests passed |
| P1.5 | Keep RTCP PLI/FIR send implementation out of phase 1 | Done | No binary feedback send path added |

## Phase 1 Test Status

- `git diff --check`: passed for tracked changes.
- Installed JDK: Homebrew `openjdk@17` at `/opt/homebrew/opt/openjdk@17`.
- Java version: `openjdk version "17.0.19"`.
- Test command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest --tests com.google.android.exoplayer2.source.rtsp.RtspMediaPeriodTest`
- Result: passed. `BUILD SUCCESSFUL in 2m 38s`, `195 actionable tasks: 195 executed`.
- Full RTSP unit test command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
- Result: passed. `BUILD SUCCESSFUL in 31s`, `195 actionable tasks: 1 executed, 194 up-to-date`.

## Out of Scope For This Phase

- PLI/FIR packet builders.
- TCP interleaved RTCP binary frame sending.
- UDP RTCP feedback sending.
- Media3 P0 RTP payload reader backports.
- Cast-SDK dependency changes.
