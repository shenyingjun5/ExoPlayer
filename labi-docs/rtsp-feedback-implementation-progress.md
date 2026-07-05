# RTSP Feedback Implementation Progress

## Current Scope

当前分支已经越过第一阶段 API 骨架，完成了 RTSP feedback/diagnostics 基础能力、RTCP PLI/FIR 发送链路和 Media3 RTSP P0/P1 小范围 backport。

已落地提交：

- `dd3af995c7 feat(rtsp): backport Media3 live fixes`
- `93e0c32c7a feat(rtsp): send RTCP feedback requests`

与发布准备相关的 Gradle 改动：

- `build.gradle`
- `constants.gradle`
- `missing_aar_type_workaround.gradle`
- `publish.gradle`

这些 Gradle 改动把默认发布 group/version/scm 指向 fork artifact，属于 X9 发布配置。

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
| P1.5 | Keep RTCP PLI/FIR send implementation out of phase 1 | Done for phase 1 | Later superseded by RTCP Feedback Completion below |

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

## Out of Scope For Phase 1

- PLI/FIR packet builders: later completed in `93e0c32c7a`.
- TCP interleaved RTCP binary frame sending: later completed in `93e0c32c7a`.
- UDP RTCP feedback sending: later completed in `93e0c32c7a`.
- Media3 P0 RTP payload reader backports: completed in `dd3af995c7`.
- Cast-SDK dependency changes.

## RTCP Feedback Completion

| ID | Task | Status | Verification |
| --- | --- | --- | --- |
| F1 | Build RTCP PLI and FIR packets | Done | `RtcpFeedbackPacketTest` |
| F2 | Send RTCP feedback over TCP interleaved frames | Done | `RtspMessageChannelTest` |
| F3 | Send RTCP feedback over UDP RTCP channel | Done | `UdpDataSourceRtpDataChannelTest` |
| F4 | Add `requestKeyFrame(reason)` chain from `RtspMediaSource` to RTSP tracks | Done | Compiled with RTSP tests |
| F5 | Add feedback throttling and PLI/FIR diagnostics/listener events | Done | `RtspFeedbackApiTest` and targeted RTCP tests |
| F6 | Trigger key-frame requests from RTP sequence gap and queue reset | Done | `RtpPacketReorderingQueueTest` |

Note: UDP RTCP feedback required one small `UdpDataSource.send(...)` helper so packets are sent
from the already bound RTCP socket/channel instead of an unrelated ephemeral socket.

## RTCP Feedback Test Status

- Targeted feedback test command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtcpFeedbackPacketTest --tests com.google.android.exoplayer2.source.rtsp.RtspMessageChannelTest --tests com.google.android.exoplayer2.source.rtsp.UdpDataSourceRtpDataChannelTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest`
- Targeted result: passed. `BUILD SUCCESSFUL in 2s`, `195 actionable tasks: 2 executed, 193 up-to-date`.
- Full RTSP unit test command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
- Full RTSP unit result: passed. `BUILD SUCCESSFUL in 12s`, `195 actionable tasks: 5 executed, 190 up-to-date`.
- Release AAR build command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease`
- Release AAR build result: passed. `BUILD SUCCESSFUL in 388ms`, `94 actionable tasks: 94 up-to-date`.

## Remaining Work

| ID | Task | Status | Notes |
| --- | --- | --- | --- |
| R1 | First-packet timeout triggers key-frame request | Not done | Needs timer/loader integration and false-positive guard |
| R2 | Decoder recover or falling-behind trigger | Not done | Likely needs Cast-SDK/player evidence before touching renderer/core |
| R3 | RTSP setup/keepalive/TCP fallback/302 P1 interop backports | Partially done | 302/Public/keepalive/invalid SDP/user-info completed; TCP fallback race/hang remains |
| R4 | Publish `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.1` | Done | Static Maven repo generated locally and pushed to GitHub Pages |
| R5 | Cast-SDK artifact integration and device validation | Not done | Must be performed in Cast-SDK repo after artifact publication |

## RTSP P1 Interop Completion

| ID | Task | Status | Verification |
| --- | --- | --- | --- |
| I1 | Preserve RTSP 302 `Location` URI as provided | Done | `RtspClientTest` |
| I2 | Ignore custom methods in OPTIONS `Public` header | Done | `RtspMessageUtilTest` |
| I3 | Use RTSP `Session` timeout for keepalive interval | Done | Full `:library-rtsp:testDebugUnitTest` |
| I4 | Skip invalid SDP media descriptions | Done | `SessionDescriptionTest` |
| I5 | Handle URL encoded `@` in RTSP user-info | Done | `RtspMessageUtilTest` |
| I6 | RTSP setup loading-state check | Already present | Existing `RtspClient` setup path |
| I7 | TCP fallback race/hang | Not done | Needs focused Media3 1.2.x diff pass |

Verification:

- Targeted command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspMessageUtilTest --tests com.google.android.exoplayer2.source.rtsp.RtspClientTest --tests com.google.android.exoplayer2.source.rtsp.SessionDescriptionTest`
- Targeted result: passed. `BUILD SUCCESSFUL in 2s`.
- Full command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
- Full result: passed. `BUILD SUCCESSFUL in 12s`.

## Publication Status

Local Gradle changes configure:

- Default group override from `com.google.android.exoplayer` to `com.zknowai.exoplayer`.
- Default release version override to `2.19.1-labi.1`.
- Fork SCM metadata in generated POM.
- AAR type workaround recognition for `com.zknowai.exoplayer`.

Generated local Maven repo:

- path: `buildout/labi-maven-repo`
- version: `2.19.1-labi.1`
- group: `com.zknowai.exoplayer`
- artifact closure:
  `exoplayer-common`, `exoplayer-container`, `exoplayer-database`, `exoplayer-datasource`,
  `exoplayer-decoder`, `exoplayer-extractor`, `exoplayer-core`, `exoplayer-rtsp`.

Publish command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew -PmavenRepo=/Users/shenyingjun/Downloads/ExoPlayer/buildout/labi-maven-repo :library-common:publishReleasePublicationToMavenRepository :library-container:publishReleasePublicationToMavenRepository :library-database:publishReleasePublicationToMavenRepository :library-datasource:publishReleasePublicationToMavenRepository :library-decoder:publishReleasePublicationToMavenRepository :library-extractor:publishReleasePublicationToMavenRepository :library-core:publishReleasePublicationToMavenRepository :library-rtsp:publishReleasePublicationToMavenRepository
```

Result:

- `library-common`, `library-container`, `library-database`, `library-datasource`, `library-decoder`, and `library-extractor` published with their lint/test dependencies passing.
- `library-core:testDebugUnitTest` failed on two upstream async timeout tests:
  `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`.
- The two failed core tests also fail when rerun directly; core source was not changed for RTSP feedback.
- `library-core` and `library-rtsp` artifacts were generated with core tests explicitly excluded:
  `-x :library-core:test -x :library-core:testDebugUnitTest -x :library-core:testReleaseUnitTest`.
- `library-core:lint`, `library-rtsp:lint`, and `library-rtsp:test` passed in the final publish run.

Remote publication:

- release tag: `exoplayer-rtsp-2.19.1-labi.1`
- Pages repo root: `https://shenyingjun5.github.io/ExoPlayer/`
- RTSP POM: `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.1/exoplayer-rtsp-2.19.1-labi.1.pom`
- RTSP AAR: `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.1/exoplayer-rtsp-2.19.1-labi.1.aar`
- Verification: GitHub Pages status `built`; POM and AAR URLs returned HTTP 200.
