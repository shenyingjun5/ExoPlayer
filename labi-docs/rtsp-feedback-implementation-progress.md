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
