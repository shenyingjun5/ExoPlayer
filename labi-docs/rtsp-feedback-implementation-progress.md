# RTSP Feedback Implementation Progress

## Current Scope

当前分支已经越过第一阶段 API 骨架，完成了 RTSP feedback/diagnostics 基础能力、RTCP PLI/FIR 发送链路、Media3 RTSP P0/P1 小范围 backport，以及 H.264 低延迟 loading 兜底诊断事件。

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

## H.264 Low-Latency Loading Diagnostic

| ID | Task | Status | Verification |
| --- | --- | --- | --- |
| H1 | Add public H.264 access-unit diagnostics value type | Done | `RtspFeedbackApiTest.diagnosticsValueTypes_haveStableEquality` |
| H2 | Add no-op default listener callback | Done | `RtspFeedbackApiTest.emptyListeners_allowNoOpCallbacks` |
| H3 | Pass RTSP diagnostics listener into `RtpH264Reader` | Done | Targeted RTSP tests passed |
| H4 | Report only first complete IDR access unit with SPS/PPS available | Done | `RtpH264ReaderTest` single packet/FU-A/missing SPS-PPS/non-IDR cases |
| H5 | Document Cast-SDK loading fallback semantics | Done | `labi-docs/rtsp-feedback-enhancement-plan.md` |
| H6 | Publish `2.19.1-labi.2` artifact | Done | GitHub Pages POM/AAR returned HTTP 200 |
| H7 | Tighten default no-listener performance path | Done | No access-unit diagnostics state is maintained without listener |
| H8 | Publish `2.19.1-labi.3` artifact | Done | Full RTSP test and release AAR build passed |
| H9 | Split passive defaults, low-latency policy, and packet diagnostics for `2.19.1-labi.4` | Done | Targeted RTSP tests, full RTSP unit tests, release AAR build, Maven publish passed |

API:

- `RtspDiagnosticsListener.onFirstDecodableVideoAccessUnitReady(RtspH264AccessUnitStats)`

Semantics:

- This is not a rendered-frame callback.
- It fires after the RTP/H.264 reader has assembled a complete IDR access unit and SPS/PPS are available.
- It fires once per H.264 track.
- Cast-SDK loading priority should stay `renderedFirstFrame` first, then this event, then `videoSize` / `PLAYING` poll fallback.
- Default no-listener playback should not maintain H.264 access-unit diagnostics state.

Targeted test command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.reader.RtpH264ReaderTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest
```

Targeted result:

- passed. `BUILD SUCCESSFUL in 3s`, `195 actionable tasks: 4 executed, 191 up-to-date`.

Full RTSP unit test command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest
```

Full RTSP unit result:

- passed. `BUILD SUCCESSFUL in 17s`, `195 actionable tasks: 1 executed, 194 up-to-date`.

Release AAR build command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease
```

Release AAR build result:

- passed. `BUILD SUCCESSFUL in 4s`, `94 actionable tasks: 4 executed, 90 up-to-date`.

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
| R6 | Publish `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.2` with H.264 access-unit diagnostic | Done | Published to GitHub Pages |
| R7 | Publish `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.3` with no-listener performance tightening | Done | Published to GitHub Pages |
| R8 | Publish `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.4` with passive defaults and packet diagnostics gating | Done | Published to GitHub Pages; gh-pages commit `c6acf50ca6` |

## `2.19.1-labi.5` Low-Latency Receiver Task Package

Plan document:

- `labi-docs/rtsp-live-low-latency-labi5-task-plan.md`

Scope:

- Keep work inside `library/rtsp` unless renderer/core evidence requires a separate follow-up.
- Preserve `2.19.1-labi.4` default behavior. With no diagnostics listener and default passive policy, playback behavior must remain unchanged.
- Focus on RTSP over TCP interleaved first. Because RTP payload readers are shared, AU integrity fixes also apply to UDP.

| ID | Task | Status | Notes |
| --- | --- | --- | --- |
| L5-P0.1 | H.264 AU integrity guard for FU-A gaps and timestamp change without marker | Done | Corrupted AU does not submit `sampleMetadata` |
| L5-P0.2 | WAIT_IDR state machine | Done | Drops non-IDR until complete IDR after corruption/gap/reset |
| L5-P0.3 | AU corrupted / WAIT_IDR RTCP feedback reasons | Done | AU corrupted requests key frame; gap/reset still reuse queue request |
| L5-P0.4 | Corrupted AU / WAIT_IDR diagnostics | Done | Listener-only recovery stats |
| L5-P0.5 | Access-unit latency event | Done | RTSP-layer H.264 assembled time only; decoder/render is P1/P2 |
| L5-P0.6 | Queue age / `rtpQueueMs` | Done | Reorder stats now include oldest packet age and queue span |
| L5-P0.7 | RTP timestamp to AU/sample/render mapping P0 | Done | AU events now include `sampleTimeUs`; render joins through existing `VideoFrameMetadataListener.presentationTimeUs` |
| L5-P0.8 | RTSP SampleQueue read/buffer diagnostics | Done | `onRtspSampleRead(...)` reports source queue read time and buffered-ahead metrics under packet diagnostics gate |
| L5-P1.1 | First-decodable timeout hook | Planned | Timer integration after P0 guard/state machine |
| L5-P1.2 | TCP delay accumulation evidence | Planned | Provide metrics; Cast-SDK controls rebuild |
| L5-P1.3 | Media3 1.2.x TCP fallback race/hang diff pass | Planned | Focused backport review only |
| L5-P2.1 | UDP loss/reorder 5s window and AUTO fallback notes | Planned | Documentation first; Cast-SDK default remains FORCE_TCP |

Initial repository check:

- branch: `labi-rtsp-feedback-exoplayer-2.19.1`
- HEAD: `1fed1fcffb feat(rtsp): split low latency feedback controls`
- latest published tag: `exoplayer-rtsp-2.19.1-labi.4`
- dirty files before `labi.5` work: `.codegraph/.gitignore`

Implementation notes:

- `RtpH264Reader` keeps old constructors and default recovery disabled.
- `DefaultRtpPayloadReaderFactory` enables H.264 low-latency recovery only when a non-passive `RtcpFeedbackPolicy` is supplied.
- `RtpPayloadReader.onRtpStreamDiscontinuity(...)` is a default no-op for non-H.264 readers.
- `RtpPacketReorderingQueue` still owns sequence gap / queue reset key-frame requests; H.264 reader uses those discontinuity signals to enter WAIT_IDR without sending duplicate gap/reset requests.
- `RtspH264AccessUnitReadyStats` reports RTP-layer assembled time and `sampleTimeUs`. It is gated by `setRtspPacketDiagnosticsEnabled(true)` and is not a decoder input or rendered-frame callback.
- `RtspSampleReadStats` reports when RTSP `SampleQueue` returns a sample to downstream, plus `sampleQueueBufferedAheadMs` and `mediaPeriodBufferedAheadMs`. This is a source queue read event, not a guaranteed MediaCodec input-buffer queued event.
- Render mapping does not touch `library/core` in P0. Cast-SDK should use ExoPlayer's existing `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered(presentationTimeUs, releaseTimeNs, ...)` and join `presentationTimeUs` to `RtspH264AccessUnitReadyStats.sampleTimeUs`.
- `RtpReorderingStats.oldestPacketAgeMs` and `RtpReorderingStats.queueSpanMs` are computed only when stats are requested.

## Low-Latency LoadControl Floor Follow-up

Scope:

- This is a small `library/core` follow-up for Cast-SDK LOW_LATENCY mode only.
- Default ExoPlayer 2.19.1 behavior remains unchanged: `DefaultLoadControl` still applies a 500ms minimum loading floor unless callers explicitly opt in.
- Cast-SDK can reflectively call `DefaultLoadControl.Builder#setMinBufferFloorMs(int)` after `setBufferDurationsMs(...)`.

API:

- `DefaultLoadControl.DEFAULT_MIN_BUFFER_FLOOR_MS = 500`
- `DefaultLoadControl.Builder#setMinBufferFloorMs(int minBufferFloorMs)`

Semantics:

- `setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)` keeps its original meaning.
- `bufferForPlaybackMs` and `bufferForPlaybackAfterRebufferMs` are not clamped by this floor.
- The new floor only changes the lower bound used by `shouldContinueLoading(...)`.
- For Cast-SDK LOW_LATENCY, `setBufferDurationsMs(150, 800, 50, 100)` plus `setMinBufferFloorMs(150)` makes the continue-loading low-water floor 150ms instead of the default 500ms.
- If Cast-SDK does not call `setMinBufferFloorMs(...)`, the old 500ms protection remains active.

Risk boundary:

- Lowering the floor can reduce steady-state source buffering, but increases sensitivity to encoder burst, transport jitter, decoder scheduling, and weak-network gaps.
- This should only be enabled for the explicit low-latency RTSP profile and verified with `/debug/live/latency` metrics.
- SMOOTH and default playback should not call the new API.

Verification:

- Targeted core command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:testDebugUnitTest --tests com.google.android.exoplayer2.DefaultLoadControlTest`
- Targeted core result: passed. `BUILD SUCCESSFUL in 3s`.
- Full RTSP command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
- Full RTSP result: passed. `BUILD SUCCESSFUL in 12s`.

Verification:

- Targeted command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.reader.RtpH264ReaderTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest --tests com.google.android.exoplayer2.source.rtsp.reader.RtpReaderUtilsTest`
- Targeted result: passed. `BUILD SUCCESSFUL in 2s`.
- Full RTSP command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
- Full RTSP result: passed. Final combined run with release AAR build returned `BUILD SUCCESSFUL in 12s`.
- Release AAR command:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease`
- Release AAR result: passed in the same final combined verification run.

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
- Default release version override to `2.19.1-labi.4`.
- Fork SCM metadata in generated POM.
- AAR type workaround recognition for `com.zknowai.exoplayer`.

Generated local Maven repo:

- path: `buildout/labi-maven-repo`
- current version: `2.19.1-labi.4`
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

## Release Scope Decision

Status: Active from next release after `2.19.1-labi.6`.

Decision:

- Use normal full Maven publication for future `2.19.1-labi.N` releases.
- Publish core, HLS, RTSP, and required transitive ExoPlayer modules under `com.zknowai.exoplayer`.
- This supersedes the earlier "publish RTSP only by default" guidance.

Reason:

- RTSP low-latency loading now depends on the fork core API `DefaultLoadControl.Builder#setMinBufferFloorMs(int)`.
- Publishing only RTSP is no longer enough for consumers that need that core low-latency hook.

Cast-SDK integration boundary:

- ExoPlayer fork should publish the full artifact set.
- Cast-SDK decides which artifacts to consume in its own repository and validation flow.

Latest publication:

- release tag: `exoplayer-rtsp-2.19.1-labi.2`
- Pages repo root: `https://shenyingjun5.github.io/ExoPlayer/`
- RTSP POM: `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.2/exoplayer-rtsp-2.19.1-labi.2.pom`
- RTSP AAR: `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.2/exoplayer-rtsp-2.19.1-labi.2.aar`
- Verification: POM and AAR URLs returned HTTP 200.

## `2.19.1-labi.7` Independent Feedback Channel Support

Status: Ready for publication.

Scope:

- C11: Expose H.264 WAIT_IDR lifecycle diagnostics:
  `onH264WaitForIdrStarted`, `onH264AccessUnitDroppedUntilIdr`,
  `onH264WaitForIdrTimedOut`, and `onH264WaitForIdrEnded`.
- C12: Add feedback strategy in `RtcpFeedbackPolicy`:
  `RTCP_ONLY`, `EXTERNAL_ONLY`, and `BOTH`.
- C13: Extend `RtspH264RecoveryStats` with
  `waitingForIdrDurationMs`, `lastRtpSequence`, `lastRtpTimestamp`, and
  `idrRecoveredCount`.

Default behavior:

- `RtcpFeedbackPolicy.DEFAULT` remains passive.
- No listener/policy means no automatic key-frame request and no H.264 WAIT_IDR recovery behavior.
- `EXTERNAL_ONLY` enables recovery events for Cast-SDK's independent live control channel but disables automatic RTCP PLI/FIR sending.

Verification:

- Targeted RTSP tests passed:
  `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.reader.RtpH264ReaderTest`.
- Full RTSP tests passed:
  `:library-rtsp:testDebugUnitTest`.

Publication target:

- version: `2.19.1-labi.7`
- release tag: `exoplayer-rtsp-2.19.1-labi.7`
- artifact scope: normal full Maven publication under `com.zknowai.exoplayer`.

## `2.19.1-labi.8` RTSP Transport Strategy Release

Status: Published.

Release inputs:

- source branch: `labi-rtsp-feedback-exoplayer-2.19.1`
- source commit: `36eea9b5b60bc88547ebace0d80811740286f4ca`
- version: `2.19.1-labi.8`
- release tag: unchanged for this test release, per Cast-SDK request.
- Pages repo root: `https://shenyingjun5.github.io/ExoPlayer/`
- gh-pages commit: `c502f85fcfa71ea28bccd3d58d9ab1da7a4fb566`

Scope:

- Publish formal Maven artifacts for Cast-SDK verified RTSP transport strategy API:
  `RtspMediaSource.Factory#setRtspTransportStrategy(int)`,
  `RtspTransportStrategy`, `RtspTransportFallbackStats`,
  `RtspTransportFallbackReason`, and
  `RtspDiagnosticsListener#onTransportFallback(...)`.
- Publish the normal full artifact set required by Cast-SDK:
  `exoplayer-common`, `exoplayer-container`, `exoplayer-database`,
  `exoplayer-datasource`, `exoplayer-decoder`, `exoplayer-extractor`,
  `exoplayer-core`, `exoplayer-hls`, and `exoplayer-rtsp`.
- Do not introduce Cast-SDK types into ExoPlayer artifacts.
- Preserve ordinary RTSP default behavior: `EXOPLAYER_DEFAULT` remains UDP-first
  with TCP fallback; diagnostics remain opt-in.

Publication:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew -PreleaseVersionOverride=2.19.1-labi.8 -PmavenRepo=/private/tmp/exoplayer-gh-pages :library-common:publishReleasePublicationToMavenRepository :library-container:publishReleasePublicationToMavenRepository :library-database:publishReleasePublicationToMavenRepository :library-datasource:publishReleasePublicationToMavenRepository :library-decoder:publishReleasePublicationToMavenRepository :library-extractor:publishReleasePublicationToMavenRepository :library-core:publishReleasePublicationToMavenRepository :library-hls:publishReleasePublicationToMavenRepository :library-rtsp:publishReleasePublicationToMavenRepository
```

Normal publish result:

- Failed at `:library-core:testReleaseUnitTest` with the known upstream async timeout tests:
  `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`.
- The same timeout class was already recorded in the release process and is not
  caused by the RTSP transport strategy API.

Final artifact publish command:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew -PreleaseVersionOverride=2.19.1-labi.8 -PmavenRepo=/private/tmp/exoplayer-gh-pages :library-common:publishReleasePublicationToMavenRepository :library-container:publishReleasePublicationToMavenRepository :library-database:publishReleasePublicationToMavenRepository :library-datasource:publishReleasePublicationToMavenRepository :library-decoder:publishReleasePublicationToMavenRepository :library-extractor:publishReleasePublicationToMavenRepository :library-core:publishReleasePublicationToMavenRepository :library-hls:publishReleasePublicationToMavenRepository :library-rtsp:publishReleasePublicationToMavenRepository -x lint -x test -x testDebugUnitTest -x testReleaseUnitTest
```

Final artifact publish result: passed.

Verification:

- `maven-metadata.xml` for `exoplayer-core`, `exoplayer-hls`, and
  `exoplayer-rtsp` has `latest/release` set to `2.19.1-labi.8`.
- HTTP checks returned `200`:
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-core/2.19.1-labi.8/exoplayer-core-2.19.1-labi.8.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-hls/2.19.1-labi.8/exoplayer-hls-2.19.1-labi.8.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.8/exoplayer-rtsp-2.19.1-labi.8.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.8/exoplayer-rtsp-2.19.1-labi.8.aar`
- Remote `exoplayer-rtsp-2.19.1-labi.8.aar` `classes.jar` contains:
  - `com/google/android/exoplayer2/source/rtsp/RtspTransportStrategy.class`
  - `com/google/android/exoplayer2/source/rtsp/RtspTransportFallbackStats.class`
  - `com/google/android/exoplayer2/source/rtsp/RtspTransportFallbackReason.class`
  - `com/google/android/exoplayer2/source/rtsp/RtspDiagnosticsListener.class`
- Remote `exoplayer-rtsp-2.19.1-labi.8.aar` class list has no Cast-SDK package match.
- SHA-256:
  - RTSP AAR: `1baab928361874d106530059d65362a3df38b07ce9b3dcba1eb2c987d14f3102`
  - RTSP POM: `7fcd964abdd2cbc454efcfaee27db3040ae64a8f988372544ed3c96b2e6e3eaa`
  - Core POM: `eb6e9d4e4efeeb074fcc5f3b9c9a0233365b57bffa25fe16d4d4ed88bf9e8ac0`
  - HLS POM: `adfb804b8a064ad37b59ad5c509ce643a8818584d0d8734fe7050bd5a48caaf2`

Cast-SDK integration value:

- Maven repository: `https://shenyingjun5.github.io/ExoPlayer/`
- Gradle version to consume: `2.19.1-labi.8`
- Main coordinates:
  - `com.zknowai.exoplayer:exoplayer-core:2.19.1-labi.8`
  - `com.zknowai.exoplayer:exoplayer-hls:2.19.1-labi.8`
  - `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.8`

## `2.19.1-labi.12` TCP Low-Latency Recovery Controls

Status: Published on 2026-07-10.

- version: `2.19.1-labi.12`
- source commit: `b1d53e9e9faf3310f006c2cf00de778a816e33e1`
- release tag: `exoplayer-rtsp-2.19.1-labi.12`
- gh-pages commit: `e0540862a9`
- Maven repo: `https://shenyingjun5.github.io/ExoPlayer/`
- published modules:
  - `com.zknowai.exoplayer:exoplayer-common:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-container:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-database:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-datasource:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-decoder:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-extractor:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-core:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-hls:2.19.1-labi.12`
  - `com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.12`
- remote metadata:
  - `exoplayer-core`: `latest/release=2.19.1-labi.12`
  - `exoplayer-hls`: `latest/release=2.19.1-labi.12`
  - `exoplayer-rtsp`: `latest/release=2.19.1-labi.12`
- remote HTTP/SHA256:
  - RTSP POM: `6663453340b16820515ceeeb977958f3fe10282d25fd24d6bdb6097a702ea50c`
  - RTSP AAR: `e0914be6b3b06b2eaf48532c58b765321b5c88c15cbf497145356581724cc14f`
  - RTSP metadata: `23e4366868754752bb0e4e530c9fb413d57c1d9ca9aa4aa6c88a32812d493ecb`
- remote `exoplayer-rtsp-2.19.1-labi.12.aar` `classes.jar` contains:
  - `RtspBacklogRecoveryPolicy.Builder#setTcpInterleavedRtpReorderWaitMs(long)`
  - `RtspBacklogRecoveryPolicy.Builder#setUdpRtpReorderWaitMs(long)`
  - `RtspBacklogRecoveryPolicy.Builder#setMediaPeriodRecoverySignalEnabled(boolean)`
  - `RtspMediaSource#requestRtcpPli(int)`
  - `RtspMediaSource#requestOneShotRtcpPli(int)`
  - `RtspMediaSource#requestRtcpFir(int)`
  - `RtspMediaSource#requestOneShotRtcpFir(int)`
  - `RtspDiagnosticsListener#onRtspMediaPeriodRecoveryRequired(RtspMediaPeriodRecoveryStats)`
  - `RtcpFeedbackResult`
  - `RtspMediaPeriodRecoveryStats`
- remote RTSP AAR class list has no Cast-SDK package match.
- tests:
  - targeted RTSP tests passed:
    `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpExtractorTest`
  - full RTSP unit tests passed:
    `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
  - RTSP release AAR passed:
    `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:assembleRelease`
  - first full publish without skips hit existing non-RTSP `:library-core:testDebugUnitTest`
    async/fixture failures: `ExoPlayerTest.onEvents_correspondToListenerCalls`,
    `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`,
    `PlaylistPlaybackTest.test_subtitle`; Maven publish then followed existing policy with
    `-x lint -x test -x testDebugUnitTest -x testReleaseUnitTest`.
- default behavior:
  - ordinary RTSP default remains `EXOPLAYER_DEFAULT + RtcpFeedbackPolicy.DEFAULT
    + RtspBacklogRecoveryPolicy.DISABLED + listener null + packet diagnostics false`.
  - `DISABLED` keeps original RTP reorder wait `30ms`.
  - media-period recovery signal defaults false and only emits for explicit policy enabled
    + signal enabled + TCP interleaved reset.
  - one-shot PLI/FIR requires explicit public API call and does not enable automatic RTCP feedback.

## Low-Latency Backlog Recovery Policy

Status: Published as `2.19.1-labi.9`.

Scope:

- Add a low-latency-only backlog/recovery policy for self-owned RTSP live.
- Keep ordinary RTSP default behavior unchanged:
  `EXOPLAYER_DEFAULT + RtcpFeedbackPolicy.DEFAULT + listener null +
  packet diagnostics false + RtspBacklogRecoveryPolicy.DISABLED`.
- Do not add RTP hot-path logs, JSON, file IO, network IO, or blocking callbacks.
- Do not do first-stage `SampleQueue` silent age drop. Source queue cleanup remains
  a later controlled reset/rebuild task.

Public API:

- `RtspBacklogRecoveryPolicy`
- `RtspBacklogRecoveryPolicy.DISABLED`
- `RtspBacklogRecoveryPolicy.LOW_LATENCY_DEFAULT`
- `RtspBacklogRecoveryPolicy.LOW_LATENCY`
- `RtspMediaSource.Factory#setRtspBacklogRecoveryPolicy(RtspBacklogRecoveryPolicy)`
- `RtspDiagnosticsListener#onRtspBacklogQueueReset(RtspBacklogRecoveryStats)`

Cast-SDK reflection bridge shape:

- Builder setters:
  `setEnabled(boolean)`,
  `setTcpInterleavedBacklogWarnMs(long)`,
  `setTcpInterleavedBacklogResetMs(long)`,
  `setTcpInterleavedBacklogResetPackets(int)`,
  `setRtpReorderBacklogWarnMs(long)`,
  `setRtpReorderBacklogResetMs(long)`,
  `setRtpReorderBacklogResetPackets(int)`,
  `setWaitForIdrTimeoutMs(long)`.
- Default low-latency thresholds:
  TCP interleaved `150ms warn / 300ms reset / 240 packets`,
  RTP reorder `100ms warn / 200ms reset / 240 packets`,
  WAIT_IDR timeout `800ms`.

Implementation:

- `TransferRtpDataChannel` tracks packet arrival time only when TCP backlog
  recovery is explicitly enabled. When threshold is hit it flushes the whole
  interleaved packet queue, reports one low-frequency diagnostics event, and
  surfaces `QUEUE_RESET` to `RtpExtractor`.
- `RtpPacketReorderingQueue` applies depth/age/span resets only when the policy
  is enabled. Reset keeps the latest packet as the new continuity point, reports
  a queue reset event, and does not send RTCP unless the existing RTCP policy
  allows a requester.
- `RtpExtractor` forwards queue/data-channel discontinuity to payload readers
  when RTCP recovery policy or backlog recovery policy enables that behavior.
- `DefaultRtpPayloadReaderFactory` enables H.264 WAIT_IDR/drop-until-idr for
  backlog recovery without coupling it to RTCP sending.
- `RtpH264Reader` no longer prints FU-A sequence malformed warnings directly;
  recovery evidence goes through diagnostics instead of release hot-path logs.

Verification:

- Targeted RTSP tests passed:
  `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.TransferRtpDataChannelTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest --tests com.google.android.exoplayer2.source.rtsp.RtpExtractorTest`.
- Full RTSP unit tests passed:
  `:library-rtsp:testDebugUnitTest`.
- Release AAR build passed:
  `:library-rtsp:assembleRelease`.
- `git diff --check`: passed.

Release:

- version: `2.19.1-labi.9`
- source commit: `4535baa41d516b86a4efeee9942c34570ccab96d`
- Pages repo root: `https://shenyingjun5.github.io/ExoPlayer/`
- gh-pages commit: `587d14cdc1`
- published modules:
  `exoplayer-common`, `exoplayer-container`, `exoplayer-database`,
  `exoplayer-datasource`, `exoplayer-decoder`, `exoplayer-extractor`,
  `exoplayer-core`, `exoplayer-hls`, and `exoplayer-rtsp`.
- remote HTTP checks returned `200`:
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-core/2.19.1-labi.9/exoplayer-core-2.19.1-labi.9.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-hls/2.19.1-labi.9/exoplayer-hls-2.19.1-labi.9.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.9/exoplayer-rtsp-2.19.1-labi.9.pom`
  - `https://shenyingjun5.github.io/ExoPlayer/com/zknowai/exoplayer/exoplayer-rtsp/2.19.1-labi.9/exoplayer-rtsp-2.19.1-labi.9.aar`
- remote `maven-metadata.xml` for `exoplayer-rtsp` has `latest/release`
  set to `2.19.1-labi.9`; local metadata for `exoplayer-core`,
  `exoplayer-hls`, and `exoplayer-rtsp` also has `latest/release` set to
  `2.19.1-labi.9`.
- remote `exoplayer-rtsp-2.19.1-labi.9.aar` `classes.jar` contains:
  `RtspBacklogRecoveryPolicy.class`,
  `RtspBacklogRecoveryPolicy$Builder.class`,
  `RtspBacklogRecoveryStats.class`,
  `RtspDiagnosticsListener.class`, and
  `RtspMediaSource$Factory.class`.
- `javap` confirmed:
  `RtspMediaSource.Factory#setRtspBacklogRecoveryPolicy(RtspBacklogRecoveryPolicy)`,
  `RtspBacklogRecoveryPolicy.DISABLED`,
  `RtspBacklogRecoveryPolicy.LOW_LATENCY_DEFAULT`,
  `RtspBacklogRecoveryPolicy.LOW_LATENCY`,
  and the Cast-SDK bridge Builder setter names.
- remote RTSP AAR class list has no Cast-SDK package match.
- SHA-256:
  - RTSP AAR:
    `beef715b3fadfdf5b4409e1396ff5818208321ea2b6bdb7427d2aa99d46f1d7f`
  - RTSP POM:
    `7cf830492ab097d3787efb5cc8a2e646334eb47d173b493747f37b683510666c`
  - Core POM:
    `770dd39eaf1e779d447c05a43187d109bd9b4f0ea1de5c7efafbd48790223a03`
  - HLS POM:
    `b963e412c01008129f3a59374dc5c4a9fde250198e43896eb195bfc235b8a36c`

## RTCP SR Precise Latency Roadmap Review

Status: Documentation review completed and re-scoped to server-first.

Review document:

- `labi-docs/rtcp-sr-latency-roadmap-review.md`

Conclusion:

- Cast-SDK 的 RTCP SR 精确延迟 roadmap 方向正确，但最新阶段边界改为
  server-first：当前 P0 先让自家 RTSP server 发送标准、稳定、可抓包验证
  的 RTCP Sender Report。
- ExoPlayer fork 暂时保持不变，不实现 SR parser，不发布新 artifact。
- 当前 fork 缺口仍记录为后续 P1/P0-next：没有
  `onRtcpSenderReport(...)` listener，也缺少入站 RTCP receive path。TCP
  interleaved 目前只注册 RTP channel listener；UDP RTCP channel 目前主要
  用于 PLI/FIR 出站发送，没有独立读取循环。
- 后续如进入 ExoPlayer SR implementation，必须先补 RTCP 入站 channel，再补
  SR parser/stats/listener。不能把 RTCP packet 喂给 RTP extractor，也不能让
  SR parser 改变 PLI/FIR、WAIT_IDR 或 drop-until-IDR 语义。

Server-first decisions:

- 自家单视频 TCP interleaved 默认 `0-1`，未来多 track 默认
  `0-1/2-3/4-5`；通用 RTSP client 仍应以 SETUP response 为准。
- SDP `a=ssrc` P0 可以不补；SR/RTP packet 中的 SSRC 已足够，`a=ssrc`
  后续作为诊断增强。
- RTCP SR 映射 media/presentation time，不无条件等同真实 capture time：
  camera 使用 `CMSampleBuffer PTS`，screen 使用 synthetic presentation time。
- `ntpTimeUs` P0 暂不暴露；`ntpTimeMs + rawNtpSeconds + rawNtpFraction`
  足够支撑当前 debug 聚合。

Next implementation package:

- P0 server-side: RTSP publisher sends standard RTCP SR over TCP interleaved
  RTCP channel and UDP RTCP port.
- P0 server-side: timestamp/SSRC/channel fixtures and capture/presentation time
  trace validation.
- P1/P0-next ExoPlayer: `RtcpSenderReportPacket` parser and tests for
  compound/malformed RTCP.
- P1/P0-next ExoPlayer: `RtcpSenderReportStats` and
  `RtspDiagnosticsListener#onRtcpSenderReport(...)`.
- P1/P0-next ExoPlayer: TCP interleaved RTCP channel registration and passive
  SR dispatch.
- P1/P0-next ExoPlayer: UDP RTCP receive loop or equivalent non-blocking
  receive path.
- P1/P0-next ExoPlayer: trackId / mediaSsrc / clockRate mapping with 32-bit
  RTP timestamp wrap handled by Cast-SDK mapper or exposed raw fields.
- P1: SR sample age/count/confidence signals and future audio clock-rate
  extension.

Publication:

- Server-only stage does not require a new ExoPlayer artifact.
- A new immutable Maven artifact is required only after ExoPlayer SR
  receive/parser/listener implementation lands.

## RTCP SR Receive Diagnostics Implementation

Status: Implemented, pending release.

Scope:

- Add `RtcpSenderReportPacket` parser for RTCP compound packets and Sender Report
  extraction.
- Add public `RtcpSenderReportStats`.
- Add default no-op `RtspDiagnosticsListener#onRtcpSenderReport(...)`.
- Add TCP interleaved RTCP receive listener registration in `RtspMediaPeriod`.
- Add UDP RTCP receive through `RtpDataChannel#readRtcpPacket(...)` and
  `UdpDataSourceRtpDataChannel`.
- Keep default playback passive: no diagnostics listener means no SR receive
  parser/loader state is started.

Tests:

- Targeted RTSP tests passed:
  `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtcpSenderReportPacketTest --tests com.google.android.exoplayer2.source.rtsp.UdpDataSourceRtpDataChannelTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtspMessageChannelTest`.
- Full RTSP unit tests passed:
  `:library-rtsp:testDebugUnitTest`.
- Release AAR build passed:
  `:library-rtsp:assembleRelease`.

Cast-SDK reflection fields:

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

## RTP Sequence Wrap Boundary Fix

Status: Implemented, tested, released as `2.19.1-labi.13`.

Scope:

- Fix `RtpPacketReorderingQueue.calculateSequenceNumberShift()` to use the RTP
  16-bit sequence number space size, `RtpPacket.MAX_SEQUENCE_NUMBER + 1`, when
  calculating wrap-around distance.
- Prevent adjacent packets `65535 -> 0` from being treated as equal by the
  `TreeSet` comparator, which previously caused sequence `0` to be dropped as a
  duplicate at every 65,536-packet boundary.
- Keep the change as a protocol correctness fix for all RTSP transports. It is
  not guarded by low-latency policy because default RTSP should also preserve
  continuous RTP sequence semantics.

Tests:

- Added coverage for continuous `65534,65535,0,1` wrap boundary ordering.
- Added coverage for out-of-order packets across the same wrap boundary.
- Added coverage for a late old packet after wrap, which should still be
  dropped without reporting a discontinuity.
- Passed targeted RTSP test:
  `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest`.
- Passed full RTSP unit tests:
  `:library-rtsp:testDebugUnitTest`.
- Passed release AAR build:
  `:library-rtsp:assembleRelease`.

Publication:

- Version: `2.19.1-labi.13`.
- Source commit/tag: `944cc32560`, `exoplayer-rtsp-2.19.1-labi.13`.
- GitHub Pages commit: `a152784ee8`.
- Published modules: `exoplayer-common`, `exoplayer-container`,
  `exoplayer-database`, `exoplayer-datasource`, `exoplayer-decoder`,
  `exoplayer-extractor`, `exoplayer-core`, `exoplayer-hls`, `exoplayer-rtsp`.
- Local RTSP AAR SHA256 before remote propagation:
  `f85bc6ec28e6adee4e9ff99d20b407ad4514d6635fd1f29b3b0e060dd55cfd27`.

Performance/default-path review:

- No new logging, diagnostics callback, JSON, file IO, network IO, blocking call
  or allocation in the RTP hot path.
- The runtime change is one integer constant in existing arithmetic. Comparator
  behavior becomes strictly correct at wrap boundaries and remains unchanged for
  non-wrap sequence comparisons.

## TCP T30/T32 Recovery Observability

Status: Implemented, tested, released as `2.19.1-labi.14`.

Scope:

- Extend low-frequency `RtspBacklogRecoveryStats` with expected/actual/last
  RTP sequence context, maximum packet inter-arrival, and extractor read stall.
  TCP interleaved byte-queue resets report unavailable sequence fields as
  `C.INDEX_UNSET`; queue age and span remain authoritative there.
- Preserve the existing `rtpTimestamp -> sampleTimeUs -> presentationTimeUs`
  join. `RtspSampleReadStats` and `RtspDecoderInputQueuedStats` now expose
  `RtspSampleRtpTimestampMappingStatus` to distinguish mapped data, a missing
  mapping, and mapping cleared for recovery.
- Do not modify `library/core`: Cast-SDK's existing
  `VideoFrameMetadataListener` is the render-side event. The source-side
  decoder-input handoff is not represented as a MediaCodec callback.
- A reorder/backlog reset clears only the affected track's explicit
  packet-diagnostics mapping. It does not flush `SampleQueue`, loader,
  MediaCodec, or renderer state.

Tests:

- Added reset-context coverage in `RtpPacketReorderingQueueTest`.
- Added recovery-reset stale-mapping and next-access-unit mapping coverage in
  `RtspFeedbackApiTest`.
- Passed targeted RTSP tests:
  `:library-rtsp:testDebugUnitTest --tests com.google.android.exoplayer2.source.rtsp.RtpPacketReorderingQueueTest --tests com.google.android.exoplayer2.source.rtsp.RtspFeedbackApiTest --tests com.google.android.exoplayer2.source.rtsp.RtpExtractorTest`.
- Passed full RTSP unit tests: `:library-rtsp:testDebugUnitTest`.
- Passed release AAR build: `:library-rtsp:assembleRelease`.

Performance/default-path review:

- `RtspMediaPeriod` creates mapping state only when both a diagnostics listener
  and packet diagnostics are enabled. Default RTSP creates neither mapping
  list, lock nor status container.
- RTP reset aggregation is enabled only when a listener and explicit reorder
  backlog policy are both present. It uses primitive fields and the existing
  queue synchronization; no new RTP hot-path logging, allocation, lock, JSON,
  file IO, network IO, or blocking callback is introduced.

Publication:

- Version: `2.19.1-labi.14`.
- Source commit/tag: `86731dbe10`, `exoplayer-rtsp-2.19.1-labi.14`.
- GitHub Pages commit: `2ad553165a`.
- Published modules: `exoplayer-common`, `exoplayer-container`,
  `exoplayer-database`, `exoplayer-datasource`, `exoplayer-decoder`,
  `exoplayer-extractor`, `exoplayer-core`, `exoplayer-hls`, `exoplayer-rtsp`.
- Remote HTTP verification:
  - AAR SHA256: `b7d93208e8d0621abc77b0305ceddf3937252011807f1e886a7737ff9400b090`.
  - POM SHA256: `a6ee5ed23610e5c66cc67a350fe5505a71b918394c0af7695bd5b7895d13ffc3`.
  - RTSP metadata SHA256: `eb36f83e517332a024c0dae466de2af82075d2e91184058599135dd06bd62de0`;
    `latest/release=2.19.1-labi.14`.
  - Remote `classes.jar` contains `RtspSampleRtpTimestampMappingStatus`,
    `RtspBacklogRecoveryStats`, `RtspSampleReadStats` and
    `RtspDecoderInputQueuedStats`; `javap` confirms all T30 reset-context fields.

## FORCE_TCP Content Profile Recovery Isolation

Status: Implemented, verified and released as `2.19.1-labi.25`.

Review conclusion:

- Existing APIs can express the three content-profile budgets without adding product concepts to
  the fork. `RtspBacklogRecoveryPolicy.Builder#setSampleQueueBacklogRecoveryThresholdMs(long)` is
  scoped to each `RtspMediaSource`, while `DefaultLoadControl.Builder#setBufferDurationsMs(...)`
  and `setMinBufferFloorMs(int)` are scoped to each Player instance.
- Cast-SDK must keep the TCP interleaved queue, RTP reorder and `WAIT_IDR` production values
  unchanged and vary only the video SampleQueue hard threshold (`800/4000/1200ms`) and the
  Player-level LoadControl snapshot.
- The `.24` implementation incorrectly required packet diagnostics for the low-frequency
  SampleQueue recovery signal. This made an explicitly enabled recovery policy depend on a
  diagnostics switch.

Scope:

- Decouple the one-shot `ACTION_REBUILD_REQUIRED` SampleQueue signal from high-frequency packet
  diagnostics. A non-null listener and an explicitly enabled recovery policy remain mandatory.
- Keep `onRtspSampleRead` and `onRtspDecoderInputQueued` behind packet diagnostics. With packet
  diagnostics disabled, no per-sample stats objects or clock reads are added.
- Keep ordinary RTSP unchanged: `EXOPLAYER_DEFAULT`, `RtspBacklogRecoveryPolicy.DISABLED`, null
  listener and packet diagnostics disabled take the original short-circuit path.
- In the explicit recovery-only video path, check only the current SampleQueue buffered-ahead
  value until the configured threshold is reached. Scan the media-period buffered position only
  for the one-shot recovery event, and stop monitoring after that event.

Tests:

- Packet diagnostics disabled still emits the explicitly enabled low-frequency recovery signal.
- Disabled policy and audio tracks do not emit video recovery.
- Configured hard thresholds `800ms`, `1200ms` and `4000ms` trigger at the exact boundary.
- Targeted `RtspFeedbackApiTest` and `DefaultLoadControlTest` pass.
- Full `:library-rtsp:testDebugUnitTest` and `:library-rtsp:assembleRelease` pass.

Publication:

- Source commit/tag: `e64aa346f12385b6e66c7be33eebbd89c7f63a2d`,
  `exoplayer-rtsp-2.19.1-labi.25`.
- GitHub Pages commit: `9ee4572aaa2bdd697fc5f341a034b9b8afb1ad47`.
- Published modules: `exoplayer-common`, `exoplayer-container`, `exoplayer-database`,
  `exoplayer-datasource`, `exoplayer-decoder`, `exoplayer-extractor`, `exoplayer-core`,
  `exoplayer-hls`, `exoplayer-rtsp`.
- Remote core/HLS/RTSP POM and AAR requests returned HTTP 200; RTSP metadata reports
  `latest/release=2.19.1-labi.25`.
- Remote SHA256:
  - RTSP AAR: `f5b570950ba82c1df3b00f63cffbf4e5544b4322afe3f89ee20f030ce218cfe3`.
  - RTSP POM: `0bfe6f201525b45b4ba68c57c7ad72b0e16674ed14930321695e7a69b6505e75`.
  - Core AAR: `a48d0d62704e597a1221d4f7e02addbc1bf4ce32a8f7985c8b0b3afd7b6813b4`.
  - HLS AAR: `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`.
- Remote `javap` confirms the existing recovery API surface:
  `setRtspBacklogRecoveryPolicy`, `setSampleQueueBacklogRecoverySignalEnabled`,
  `setSampleQueueBacklogRecoveryThresholdMs`, `setMediaPeriodRecoverySignalEnabled`, and
  `onRtspMediaPeriodRecoveryRequired`.
- The first all-module publish dependency run executed 4,852 core tests and hit two existing
  asynchronous timeout failures in `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`. Both failed again in
  isolated reruns without touching RTSP code. Publication therefore followed the existing release
  policy and skipped duplicate `test/lint` tasks after the targeted/full RTSP tests and release AAR
  build had passed.

## E24/T64 TCP Interleaved Depth Reset Age Gate

Status: Implemented, verified, and released as `2.19.1-labi.26`.

Review conclusion:

- A TCP interleaved queue depth of 240 packets is not sufficient evidence of sustained backlog.
  The H8 `240 packets / 106ms` sample is consistent with a valid high-bitrate IDR or motion burst.
- Keep the configured age reset as an independent hard trigger. Require depth and a minimum oldest
  packet age together for the packet safety-cap branch.
- Add `tcpInterleavedBacklogDepthResetMinAgeMs` and
  `Builder#setTcpInterleavedBacklogDepthResetMinAgeMs(long)`. When unset, the value inherits
  `tcpInterleavedBacklogResetMs`, so existing `300ms/240 packets` callers gain the safe behavior
  without choosing a new threshold. Zero explicitly disables the depth branch.

Tests and boundaries:

- Deterministic tests cover `240 packets / 106ms` without reset, packet + configured minimum-age
  reset, the independent `300ms` age reset, disabled/depth-only behavior, and reset diagnostics.
- Targeted `TransferRtpDataChannelTest` and `RtspFeedbackApiTest`, full
  `:library-rtsp:testDebugUnitTest`, and `:library-rtsp:assembleRelease` pass.
- The ordinary RTSP path still uses its original `byte[]` queue and returns before clock reads or
  envelope allocation. The extra primitive comparisons run only in the explicitly enabled TCP
  backlog-recovery path. No UDP, WAIT_IDR, feedback, SampleQueue, audio, logging, IO, or locking
  behavior changes.

Publication:

- Source commit/tag: `b29eb06cfe`, `exoplayer-rtsp-2.19.1-labi.26`.
- GitHub Pages commit: `f9ea587c33714e5c09b6f5d9a18ca9b86ece9a9f`.
- Published modules: `exoplayer-common`, `exoplayer-container`, `exoplayer-database`,
  `exoplayer-datasource`, `exoplayer-decoder`, `exoplayer-extractor`, `exoplayer-core`,
  `exoplayer-hls`, and `exoplayer-rtsp`.
- Remote RTSP metadata reports `latest/release=2.19.1-labi.26`; remote RTSP/core/HLS AAR and POM
  requests return HTTP 200.
- Remote SHA256: RTSP AAR
  `3496d04bb6cbaddb2b7e9fe27f3bd5c0025c145f6c229e0b8fcf11701517ba44`, RTSP POM
  `8182e8153b40515522033166af59528893cc8b5d1fc142118e200df90f8bde9e`, core AAR
  `a48d0d62704e597a1221d4f7e02addbc1bf4ce32a8f7985c8b0b3afd7b6813b4`, and HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`.
- Remote `javap` confirms public field `tcpInterleavedBacklogDepthResetMinAgeMs` and Builder method
  `setTcpInterleavedBacklogDepthResetMinAgeMs(long)`.

## Low-Frequency Video RTP Activity

Status: Implemented, verified, and released as `2.19.1-labi.27`.

Scope:

- Added `RtspRtpTrackActivityStats` and
  `RtspDiagnosticsListener#onRtspRtpTrackActivity(...)` for a throttled video-track RTP heartbeat
  independent from packet diagnostics.
- Added `RtspBacklogRecoveryPolicy.Builder#setRtpActivityNotificationIntervalMs(long)` with a
  `500ms` default and `0` explicit disable value.
- Gated the feature on explicit enabled recovery policy, non-null listener, and video MIME. Audio,
  disabled/default RTSP, and listener-null sessions do not maintain state or emit callbacks.
- Reused the packet arrival elapsed-realtime value already read by `RtpExtractor`; no new packet
  clock reads, logs, JSON, IO, locks, volatile fields, or unbounded allocations were added.
- Did not change queue, reorder, WAIT_IDR, feedback, SampleQueue, reset threshold, or transport
  behavior.

Verification:

- Targeted `RtpExtractorTest`, `RtspRtpTrackActivityStatsTest`, and `RtspFeedbackApiTest`: passed.
- Full `:library-rtsp:testDebugUnitTest`: `320 tests`, `0 failures`, `0 errors`.
- `:library-rtsp:assembleRelease`: passed.

Publication:

- Source commit/tag: `3c998e50e109309939e9e48d2b808c0dbb340707`,
  `exoplayer-rtsp-2.19.1-labi.27`.
- GitHub Pages commit: `355689a724a4921919454ceeafc8d0c28efad810`.
- Published modules: `exoplayer-common`, `exoplayer-container`, `exoplayer-database`,
  `exoplayer-datasource`, `exoplayer-decoder`, `exoplayer-extractor`, `exoplayer-core`,
  `exoplayer-hls`, and `exoplayer-rtsp`.
- Remote RTSP/core/HLS metadata reports `latest/release=2.19.1-labi.27`; all six AAR/POM
  requests returned HTTP 200.
- Remote SHA256: RTSP AAR
  `042af27d851de87fccc3c882a59f9b343045e2ad2f49f33e4150e49b4198a521`, RTSP POM
  `ad03acce882a02c772b41fad8c166685ff24db2ca4e7563b086bf68c0a1d8d00`, core AAR
  `a48d0d62704e597a1221d4f7e02addbc1bf4ce32a8f7985c8b0b3afd7b6813b4`, core POM
  `2eb3c89e1568df535a1ff2d03d71f9de57763732922bd0042cd9ddf2301ff6f8`, HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`, and HLS POM
  `be3efc03db1d9df7c84be0d57d0957bf89eefd83421c2487ca33088acb320fe8`.
- Remote RTSP AAR `javap` confirms `RtspRtpTrackActivityStats`,
  `RtspDiagnosticsListener#onRtspRtpTrackActivity(...)`, policy field
  `rtpActivityNotificationIntervalMs`, and Builder method
  `setRtpActivityNotificationIntervalMs(long)`.


## Low-Frequency Media Clock Diagnostics

Status: Published as `2.19.1-labi.28`.

Review and scope:

- Existing public Player, AnalyticsListener and RTCP SR APIs cannot identify the active
  `DefaultMediaClock` source or expose the active audio renderer clock position and advancement
  state. A core API is required because `library/core` cannot depend on RTSP policy types.
- Added `MediaClockDiagnosticsListener`, `MediaClockSnapshot`,
  `ExoPlayer.Builder#setMediaClockDiagnosticsListener`, and
  `setMediaClockDiagnosticsIntervalMs`. Cast-SDK owns the outer gate and must configure these only
  for its explicit low-latency RTSP system-audio player.
- Listener null or interval zero does not schedule the diagnostics message. The enabled path samples
  on the playback looper at a minimum 250ms interval and posts the immutable snapshot to the
  application looper.
- This is observation-only. It does not modify media-clock selection, AudioSink, SampleQueue,
  LoadControl, decoder, transport, feedback, or recovery behavior. No packet/sample/frame hot-path
  logging, allocation, clock read, lock, JSON, or IO was added.

Verification so far:

- Targeted `MediaClockDiagnosticsTest`, `MediaClockSnapshotTest`, and
  `DefaultMediaClockTest`: passed.
- Full `:library-rtsp:testDebugUnitTest`: `320 tests`, `0 failures`, `0 errors`.
- `:library-core:lintDebug`, `:library-core:assembleRelease`, and
  `:library-rtsp:assembleRelease`: passed.
- Full core run executed `4861` tests. `PlaylistPlaybackTest.test_subtitle` passed on isolated
  rerun. The two previously documented asynchronous timeouts in
  `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes` reproduced in isolated
  reruns and are unrelated to this diagnostics path.
- Local and remote release core AARs contain `MediaClockDiagnosticsListener`,
  `MediaClockSnapshot`, both builder setters, all clock-source constants, and all snapshot fields.

Publication:

- Version: `com.zknowai.exoplayer:*:2.19.1-labi.28`.
- Source commit/tag: `b4d8c803954b1f27439666ec08b552dcedca4016` /
  `exoplayer-rtsp-2.19.1-labi.28`.
- GitHub Pages commit: `9d0d2d4a59b9a9188850d9ecb9cb9e167480a58f`.
- Published modules: common, container, database, datasource, decoder, extractor, core, HLS, and
  RTSP.
- Remote RTSP/core/HLS metadata reports `latest/release=2.19.1-labi.28`; all six AAR/POM requests
  returned HTTP 200.
- Remote SHA256: core AAR
  `132feeec3d8996f2ab6fb2fc01ff13b5822546663e05f24ef4eefa9e8704875b`, core POM
  `8daf0ec0cfbfa4c052e50895f695ea310da3d835d5ceee86e8013b3740d37a24`, HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`, HLS POM
  `7744c7ccb0189a02b587d590526d7a59f3caa03b487c4659b0d4079ff6acb9cf`, RTSP AAR
  `042af27d851de87fccc3c882a59f9b343045e2ad2f49f33e4150e49b4198a521`, and RTSP POM
  `dd2d3fd13fa4f534c235d410fc85860b9895aac6204b962aa935926d40284072`.
- Remote `javap` confirms `ExoPlayer.Builder` and `SimpleExoPlayer.Builder` listener/interval
  setters, `MediaClockDiagnosticsListener#onMediaClockSnapshot`, all `MediaClockSnapshot` fields,
  and no Cast-SDK classes in the core AAR.

## T87C Persistent SampleQueue Backlog Confirmation

Status: Published as `2.19.1-labi.29`.

Scope and semantics:

- Kept the configured `800ms` production threshold and all transport, WAIT_IDR, decoder, media
  clock, SampleQueue core, seek, flush, and drop behavior unchanged.
- Replaced the one-observation timestamp-ahead signal with a primitive candidate under the existing
  explicit enabled-policy, sample-recovery, non-null-listener, video-track gate.
- A candidate emits only after consumed reads advance through the queue tail captured at the first
  breach while the threshold remains breached and newly queued unread samples still exist. This
  rejects sparse `[0,833]`, `[0,833,866]`, and `[0,100,900]` timestamp gaps without adding another
  time threshold or frame-rate assumption.
- WAIT_IDR start/end, RTP/transfer queue reset, seek, track selection, TCP retry, skip, and release
  invalidate or clear the candidate through a period-local volatile boundary token. The token is an
  idempotent invalidation marker, not an event counter; concurrent increments need only change it
  once to prevent a candidate from spanning a recovery boundary.
- Added final-event evidence fields to `RtspMediaPeriodRecoveryStats`: `triggerSampleTimeUs`,
  `largestQueuedSampleTimeUs`, `sampleQueueReadIndex`, `sampleQueueWriteIndex`,
  `sampleQueueUnreadSampleCount`, and `confirmationReadCount`. Existing constructors remain.
- Listener-null and policy-disabled lifecycle paths return before candidate primitive writes. The
  enabled read path uses primitive state and existing SampleQueue indexes; only the final one-shot
  signal reads elapsed realtime and allocates a stats object.

Verification:

- Targeted `RtspFeedbackApiTest`: passed.
- Full `:library-rtsp:testDebugUnitTest`: `331 tests`, `0 failures`, `0 errors`.
- `:library-rtsp:assembleRelease`: passed.
- Diff review found no new packet/sample logging, JSON, IO, lock, object queue, ring buffer, or
  default-path clock read/allocation. No Cast-SDK type is referenced.

Publication:

- Source commit/tag: `03e78148cd3f83fb9885f77a2953690c05c47e43`,
  `exoplayer-rtsp-2.19.1-labi.29`.
- GitHub Pages commit: `8008ef8e3b`.
- Published modules: common, container, database, datasource, decoder, extractor, core, HLS, and
  RTSP.
- Remote core/HLS/RTSP metadata reports `latest/release=2.19.1-labi.29`; all six AAR/POM requests
  returned HTTP 200.
- Remote SHA256: RTSP AAR
  `2cbf0f7454c63e0277c1c4e19ec621af30ed20816275accf97cdd63c1a193a92`, RTSP POM
  `624ff304e8b8cbf79864f8a231e149ebf1a06919113f1f3e7182bec6486d943e`, core AAR
  `132feeec3d8996f2ab6fb2fc01ff13b5822546663e05f24ef4eefa9e8704875b`, core POM
  `fd89f463c3c31037f7118f77b52c9db85b65a3d6a35618295e5ac70d5c1d1734`, HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`, HLS POM
  `d181169f15487539e2acaa33efce641db0e35bc15b84d7db543e24106105a606`.
- Remote RTSP AAR `javap` confirms all six evidence fields, both legacy constructors, the new full
  constructor, and no Cast-SDK classes.

## T88 Broad Media3 ExoPlayer Playback Backport Batch 1

Status: Implemented, verified, and published as `2.19.1-labi.30`.

Scope:

- Backported Media3 RTSP UDP bind preparation fix from commit
  `8bf3b5c78191c4129e7318c9587cfc3b400387d3`.
  - `RtspMediaPeriod.InternalListener.onLoadError(...)` now retries `BindException` before checking
    `prepared`, so UDP port conflicts during initial preparation can recover.
  - `RtpDataLoadable.load()` no longer dereferences a null `dataChannel` in `finally`, preserving
    the original bind/open failure instead of masking it with `NullPointerException`.
- Backported Media3 `DefaultLoadControl` OOM guard from commit
  `d32189df0f8a59c8992b37ceff02f5d3ceb2f822`.
  - When `prioritizeTimeOverSizeThresholds=true`, the player only ignores byte-size limits if heap
    headroom remains above the Media3 4% threshold.
  - The fork's low-latency `setMinBufferFloorMs(int)` behavior is unchanged.
- Backported Media3 `DefaultAudioSink` AudioTrack initialization retry strategy from commit
  `6be48df57df0493868175b05050b9767965b61c4`.
  - Because ExoPlayer 2.19.1 does not have Media3's newer `AudioOutputProvider` abstraction, the
    policy was manually adapted to the existing `AudioTrack` construction path.
  - On initialization failure only, retry buffer size is halved down to the max of 1-second audio
    data and platform min buffer size. Normal audio write/render paths are unchanged.

Performance and default-behavior review:

- No new diagnostics listener, packet/sample/frame callback, JSON, IO, lock, or logging was added
  to packet/frame hot paths.
- RTSP change only affects loader error handling and cleanup after open failure.
- LoadControl adds one heap-headroom check only inside `shouldContinueLoading(...)` when buffered
  duration is below min buffer and `prioritizeTimeOverSizeThresholds=true`. The default
  `prioritizeTimeOverSizeThresholds=false` path does not query the heap. The memory-pressure log is
  emitted once per pressure-stop episode rather than once per loading evaluation.
- AudioTrack retry logic only runs after `AudioTrack` initialization throws. Successful
  initialization and steady-state audio rendering do not execute the retry loop.

Verification:

- Initial `./gradlew :library-rtsp:testDebugUnitTest` failed because `ANDROID_HOME` was not set in
  the shell environment.
- Passed:
  `ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`.
- Passed:
  `ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:testDebugUnitTest --tests com.google.android.exoplayer2.DefaultLoadControlTest --tests com.google.android.exoplayer2.audio.DefaultAudioSinkTest`.
- Added AudioTrack retry behavior coverage for retry ordering, aligned minimum, eventual success,
  suppressed failures, and terminal failure.
- Added `RtspMediaPeriodTest` integration coverage proving a transient preparation-time
  `BindException` retries the RTP data channel and completes preparation.
- Full `:library-rtsp:testDebugUnitTest`: `333 tests`, `0 failures`, `0 errors`.
- Full `:library-core:testDebugUnitTest` executed `4,866 tests`: `4,864` passed and two unrelated
  network-error expectation tests timed out while waiting for
  `http://this-will-throw-an-exception.mp4` to fail:
  `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`. Both fail at their
  `runUntilError(...)` line and do not exercise the changed LoadControl or AudioTrack retry paths.
- `:library-core:lint`, `:library-rtsp:lint`, `:library-core:assembleRelease`,
  `:library-hls:assembleRelease`, and `:library-rtsp:assembleRelease` passed.

Next recommended Media3 backport batch:

- Audio session id concurrency fix: `16cb8176055bf5680e1e54b918ee347e5f28c1cc`.
- `MediaCodec` operating-rate fallback: `d1a3251ca412f98af19f1e5b6b45c92ca356f64d`.
- Surface immediate-render decision fix: `59ace1a2bc0149073c1e3600845422d905c2a45b`.

Publication:

- Source commit/tag: `ca9c8d4dbb1910f2c4f78c3e6f87f5e753d776a6`,
  `exoplayer-rtsp-2.19.1-labi.30`.
- GitHub Pages commit: `1b02ba1f5fe7b5eb621eecbc8a981936a96c2bbc`.
- Published modules: common, container, database, datasource, decoder, extractor, core, HLS, and
  RTSP.
- Remote core/HLS/RTSP metadata reports `latest/release=2.19.1-labi.30`; all six AAR/POM requests
  returned HTTP 200.
- Remote SHA256: core AAR
  `83207e7250f8156c6da49cfe6b57587adff5e7107a3d5349d1872f94af3c2c71`, core POM
  `817e6e599a77d5a1b58501483101d2bc35159659c59cbaabdec060ab30a9ee90`, HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`, HLS POM
  `966ea52d3cc5b95fc80bfdb186861ef028e1b3b901f4ec513c5f74e92cf64319`, RTSP AAR
  `cbd22f1e184c1a72a9d228dd3a5742fd98b6e218623b76797338955835bfc9a4`, and RTSP POM
  `75fa5f8801359f7f0d08caec40308a8c2c24b004ca9b6de253ebd672c313ac8f`.

## T89 Broad Media3 ExoPlayer Playback Backport Batch 2

Status: Implemented, verified, and published as `2.19.1-labi.31`.

Scope:

- Backported Media3 commit `da867c6b1ac93a6ce86413664bb02fa3df8a4058` to
  `MediaCodecRenderer.flushOrReleaseCodec()`.
- If a codec has been created but has not received any input buffer, position reset no longer calls
  `MediaCodec.flush()`. Some device codecs can swallow every later sample after this empty flush.
- Once any codec input has been queued, the existing flush behavior is unchanged. Codec release,
  drain actions, DRM update, EOS and device workaround conditions remain ahead of the new guard and
  retain their existing semantics.

Performance and compatibility review:

- The steady-state codec path adds no work. The reset/disable path adds one primitive boolean
  branch and no allocation, logging, lock or callback.
- No public API changed. The fix applies to audio and video codec renderers and does not depend on
  RTSP diagnostics or low-latency policy.
- No new Android API is used, so Android 4.4 compatibility is unchanged.
- Media3 audio-session concurrency, operating-rate fallback, Surface immediate-render,
  `VideoFrameReleaseHelper` callback and joining-counter commits were reviewed separately. They are
  either architecturally absent from 2.19.1 or require broader global behavior changes, so they are
  not included in this batch.

Verification:

- Targeted `MediaCodecRendererTest`: passed, including new assertions that reset before the first
  queued input does not flush and reset after queued input still flushes.
- Full `:library-rtsp:testDebugUnitTest`: `333 tests`, `0 failures`, `0 errors`.
- Full `:library-core:testDebugUnitTest` executed `4,868 tests`: `4,856` passed, `10` skipped, and
  the same two unrelated network-error expectation tests recorded for `labi.30` timed out at
  `runUntilError(...)`: `ExoPlayerTest.onEvents_correspondToListenerCalls` and
  `DefaultAnalyticsCollectorTest.onEvents_isReportedWithCorrectEventTimes`.
- `:library-core:lint`, `:library-rtsp:lint`, `:library-core:assembleRelease`,
  `:library-hls:assembleRelease`, and `:library-rtsp:assembleRelease` passed.

Publication:

- Source commit/tag: `53998610c7`, `exoplayer-rtsp-2.19.1-labi.31`.
- GitHub Pages commit: `c4fc783`.
- Published modules: common, container, database, datasource, decoder, extractor, core, HLS, and
  RTSP.
- Remote core/HLS/RTSP metadata reports `latest/release=2.19.1-labi.31`; all six AAR/POM requests
  returned HTTP 200.
- Remote SHA256: core AAR
  `b3c4ec2241a8ced41d85a058df86fcf7c69954ff68f1a3589c6d8fb980a48dc5`, core POM
  `ad1d55e7f04012c541ad10ad9eaed8dde2dc1e498fee61821fbd1e79aedf2352`, HLS AAR
  `e922c1bf2762abaf1fe738518fb2243bbb23cd26354ed2c5e3fcd6bc916793aa`, HLS POM
  `e5b19b16e76a9e07d3d8a5863fd7b6e77beff56686569ac3d2e62e22b6929856`, RTSP AAR
  `cbd22f1e184c1a72a9d228dd3a5742fd98b6e218623b76797338955835bfc9a4`, and RTSP POM
  `f4a434f3c39c7e841ee7ac57edf5801d20964f149dbc9897f1eaddf028b6b825`.
