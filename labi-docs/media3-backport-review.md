# Media3 RTSP Backport Review

## Scope

本评估面向 Cast-SDK Android 接收端使用的 ExoPlayer 2.19.1 RTSP fork，目标是只选择性回迁 AndroidX Media3 中对 RTSP live、RTP/H.264/H.265、弱网恢复、首帧/黑屏恢复有直接价值的修复。

本文档同时记录评估结论和已落地的分批回迁状态。回迁仍遵守小范围手工 backport：不引入完整 Media3，不整目录覆盖，不修改 Cast-SDK 仓库。

## Local Repository State

- 工作目录：`/Users/shenyingjun/Downloads/ExoPlayer`
- `origin`：`https://github.com/shenyingjun5/ExoPlayer.git`
- `upstream`：`https://github.com/google/ExoPlayer.git`
- 当前分支：`labi-rtsp-feedback-exoplayer-2.19.1`
- 分支跟踪：`origin/labi-rtsp-feedback-exoplayer-2.19.1`
- 当前未跟踪文件：`.codegraph/`、`AGENTS.md`、`labi-docs/`

## Sources Reviewed

- AndroidX Media3 release notes through stable `1.10.1` and `1.11.0-alpha01` preview on 2026-07-03.
- Local ExoPlayer 2.19.1 RTSP source under `library/rtsp`.
- Media3 1.10.0 RTSP source for `RtpH264Reader`, `RtpH265Reader`, `RtpExtractor`, `RtpPacket`, `RtspMediaSource`, `SessionDescriptionParser`, and `RtpPacketReorderingQueue`.
- GitHub issues and commits linked from Media3 release notes for P0 candidates.

## Priority Matrix

| Priority | Candidate | Media3 version | Evidence | Local 2.19.1 status | Backport decision |
| --- | --- | --- | --- | --- | --- |
| P0 | H.264/H.265 access unit size reset when one AU spans multiple RTP packets | 1.10.0 | issue `androidx/media#3121`, commit `16356f8d9a234ec5456e20413250a41e6670c0bb` | 已回迁：`RtpH264Reader` / `RtpH265Reader` now track RTP timestamp boundary and corrupted AU state | Done. Covered by focused reader tests. |
| P0 | RTP timestamp wraparound | 1.9.1 | issue `androidx/media#2930`, commit `0b20baa08215ab786932855ee7a51955908f813f` | 已回迁：`RtpReaderUtils.toSampleTimeUs` masks RTP timestamp delta as unsigned 32-bit | Done. Covered by `RtpReaderUtilsTest`. |
| P0 | Fragmented NAL unit missing RTP packet handling for H.264/H.265 | 1.8.0 | issue `androidx/media#2613`, commit `8f0b9ac535eb49070768469db2a6ae764303f224` | 已回迁：missing/interrupted FU marks current AU corrupted and suppresses metadata | Done. Covered by H.264/H.265 reader tests. |
| P0 | H.265 RTP Aggregation Packet support | 1.8.0 | PR `androidx/media#2413`, merge commit `5dc4d94daec9799cad5ad5caf71b75ab2e18897b` | 已回迁：AP type 48 parses aggregated NAL units and rejects malformed packets | Done. Covered by positive and malformed AP tests. |
| P1 | RTSP 302 Location URI handling | 1.8.0 | issue `androidx/media#2398` from release notes | Local `RtspClient` handles 302 but should be checked against Location URI preservation before patching | Evaluate after P0. Good interop value, not core self live path. |
| P1 | SDP trailing whitespace tolerance | 1.8.0 | issue `androidx/media#2357` from release notes | 已回迁：parser trims SDP lines before matching | Done. Covered by `SessionDescriptionTest`. |
| P1 | SDP empty session information line | 1.4.x | Media3 release notes mention empty `i=` tolerance | 已回迁：SDP line regex accepts empty payload after optional whitespace | Done. Covered by `SessionDescriptionTest`. |
| P1 | RTP header extension parsing | 1.5.x | Media3 release notes mention RTP header extension crash | 已回迁：`RtpPacket.parse` skips extension header/payload and rejects truncated extension data | Done. Covered by `RtpPacketTest`. |
| P1 | `rtspt://` scheme for forcing TCP | 1.6.1 | issue `androidx/media#1484` from release notes | 已回迁：factory treats `rtspt://` as TCP and internal URI is converted to `rtsp://` | Done. Covered by `RtspFeedbackApiTest`. |
| P1 | UDP port binding transient stalls/failures | 1.10.0 | release notes RTSP extension | Current Cast-SDK main path is expected TCP interleaved; UDP remains secondary | Evaluate only if UDP becomes supported acceptance path. |
| P1 | RTSP setup, keepalive, OPTIONS public header tolerance, TCP fallback races | 1.2.x and later | release notes list setup state, server timeout, OPTIONS public header, TCP fallback fixes | Local code already has keepalive monitor and UDP-to-TCP fallback paths; exact diffs need separate pass | Batch after P0 and before release if third-party RTSP compatibility is a goal. |
| P1 | MediaCodecVideoRenderer late input, decoder recovery, Surface first-frame behavior | 1.6.x-1.10.x | release notes Video/ExoPlayer sections | Touches `library/core`, not `library/rtsp`; Android API guard risk higher | Do not mix with RTSP module patch. Track only if black-screen evidence points to renderer. |
| P2 | LoadControl defaults and stuck player detection | 1.6.0 / 1.9.0 | release notes mention lower default playback buffers and `StuckPlayerDetector` | Core behavior change, not RTSP-specific; may alter all playback | Prefer Cast-SDK-side player config first. Backport only with measured need. |
| P2 | Generic error handling policy changes | 1.9.x | release notes mention load error retry handling | Not RTSP-specific | Defer. |
| 不回迁 | UI, Session, Compose, Transformer, IMA, Cast, downloads, inspector, preload, metadata features | multiple | release notes | Outside fork scope | Do not backport. |

## P0 Detailed Assessment

### P0.1 Access Unit Boundary and Sample Size Reset

Media3 commit:

- `16356f8d9a234ec5456e20413250a41e6670c0bb`
- Message: fixes RTSP H.264/H.265 regression caused by incorrect sample size resets.
- Issue: `androidx/media#3121`

Relevant Media3 behavior:

- `RtpH264Reader` and `RtpH265Reader` add `previousTimestamp`.
- A timestamp change is treated as a new access unit boundary.
- Reader state is reset only when a new AU is detected or when RTP marker completes the AU.
- Metadata is emitted only if the current AU is not marked corrupted.

Local impact:

- The 2.19.1 readers accumulate `fragmentedSampleSizeBytes` and reset it only when `rtpMarker` is true.
- They do not explicitly detect timestamp boundary.
- When multiple single NAL packets belong to one AU, the old code can produce incorrect sample sizing or corrupted output.

Backport shape:

- Add `previousTimestamp`.
- Add `resetReaderStateForNewAccessUnit()`.
- On timestamp change, reset per-AU state.
- On marker, emit metadata only when not corrupted, then reset per-AU state.
- Add tests for multiple NAL packets in one AU and timestamp-boundary reset.

Risk:

- Low to medium. The change is isolated to RTP payload readers, but affects sample metadata timing and size.
- No Android API level risk.

### P0.2 RTP Timestamp Wraparound

Media3 commit:

- `0b20baa08215ab786932855ee7a51955908f813f`
- Message: handle RTP timestamp wraparound in `RtpReaderUtils`.
- Issue: `androidx/media#2930`

Local impact:

- Current `RtpReaderUtils.toSampleTimeUs` uses direct signed subtraction:

```java
rtpTimestamp - firstReceivedRtpTimestamp
```

- RTP timestamps are 32-bit unsigned values, so wraparound can produce negative sample time deltas.

Backport shape:

- Mask the subtraction result with `0xFFFFFFFFL` before scaling.
- Add a unit test around a timestamp near `0xFFFF_FFFF` followed by a small wrapped timestamp.

Risk:

- Low. Utility-only change.
- No Android API level risk.

### P0.3 Fragmented NAL Missing Packet Handling

Media3 commit:

- `8f0b9ac535eb49070768469db2a6ae764303f224`
- Message: handle H.264/H.265 fragmentation unit packet loss.
- Issue: `androidx/media#2613`

Relevant Media3 behavior:

- Adds `isCurrentAccessUnitCorrupted`.
- Adds `isProcessingFragmentationUnit`.
- If a FU sequence is interrupted or a non-start FU sequence number is unexpected, the reader marks the AU corrupted.
- Subsequent fragments for the same NAL/AU are discarded until the next AU boundary.
- Metadata is not emitted for corrupted AU.

Local impact:

- Current H.264/H.265 readers log unexpected FU sequence and return.
- They do not discard later fragments for the same NAL.
- This can feed incomplete NAL data into the sample queue and decoder, causing corruption, stalls, or memory growth under weak network.

Backport shape:

- Combine with P0.1 because Media3 state machine now uses the same AU reset function.
- Add tests for missing middle FU packet and interrupted FU.

Risk:

- Medium. Correct behavior is to drop corrupted AU, but tests must verify that the next clean AU still emits.
- No Android API level risk.

### P0.4 H.265 Aggregation Packet

Media3 commit:

- `5dc4d94daec9799cad5ad5caf71b75ab2e18897b`
- PR: `androidx/media#2413`

Local impact:

- Current `RtpH265Reader` throws for payload type 48:

```java
throw new UnsupportedOperationException("need to implement processAggregationPacket");
```

Backport shape:

- Implement `processAggregationPacket`.
- Reject malformed AP where NAL unit size exceeds packet size, packet has trailing bytes, or AP contains fewer than two NAL units.
- Preserve key-frame flag if any aggregated NAL is IDR.

Risk:

- Low to medium. It only enables a previously unsupported H.265 packetization mode.
- Add positive AP fixture and malformed AP tests.

## P1 and P2 Notes

## Additional ExoPlayer PR Scan

This scan compares Media3 release-note PRs/issues against the current fork after commits
`dd3af995c7` and `93e0c32c7a`.

| Priority | Media3 PR / issue | Area | Local status | Recommendation |
| --- | --- | --- | --- | --- |
| P1 | `androidx/media#2398` | RTSP 302 `Location` URI handling | 已回迁：redirect URI now uses `Location` as provided and only updates auth user-info if the redirect URI includes it. | Done. Covered by `RtspClientTest`. |
| P1 | `androidx/media#577` | RTSP setup loading-state check | 已存在：local `onSetupResponseReceived` already checks state is not `RTSP_STATE_UNINITIALIZED`. | No code change needed. Covered by RTSP setup flow tests indirectly. |
| P1 | `androidx/media#613` | RTSP `OPTIONS` public header tolerance | 已回迁：unknown/custom methods in `Public` are ignored. | Done. Covered by `RtspMessageUtilTest`. |
| P1 | `androidx/media#662` | Use server RTSP setup timeout for keepalive interval | 已回迁：`RtspClient` stores `Session` timeout and uses timeout / 2 for keepalive. | Done. Covered by full RTSP unit compile/run; timing-specific assertion intentionally avoided. |
| P1 | Media3 1.2.x release note | TCP fallback race/hang | Local code has UDP-to-TCP fallback, but no focused comparison against Media3 1.2.x race fix. | Keep as a single fallback-stability patch. Higher risk than parser fixes; do after release config or before wider field test. |
| P1 | `androidx/media#1087` | Skip invalid SDP media descriptions | 已回迁：invalid media descriptions are skipped and their media-level lines are ignored until the next `m=` section. | Done. Covered by `SessionDescriptionTest`. |
| P1 | `androidx/media#1138` | URL encoded `@` in RTSP user-info | 已回迁：`removeUserInfo` splits on the last `@`; encoded `@` in user-info is preserved during parsing. | Done. Covered by `RtspMessageUtilTest`. |
| P1 | Media3 1.11.0-alpha01 release note, commit `8bf3b5c78191c4129e7318c9587cfc3b400387d3` | UDP port binding transient stalls/failures | 已回迁：preparation 阶段允许重试 `BindException`，channel 创建前失败不再被 cleanup NPE 覆盖。 | Done. Covered by `RtpDataLoadableTest`, preparation retry integration coverage in `RtspMediaPeriodTest`, and full RTSP tests. |
| P2 | `androidx/media#2941` | Discard video codecs below API 30 when frame rate changes | Not merged; touches video renderer / codec selection. | Defer. Potentially useful for camera streams with frame-rate changes, but core/renderer blast radius is larger than RTSP module. Need real black-screen/stutter evidence. |
| P2 | Media3 1.11.0-alpha01 release note | Surface/frame rendering decisions and frame-rate estimation | Not merged; touches `MediaCodecVideoRenderer` / UI surfaces. | Watch only. Do not backport from alpha without reproduction. |
| P2 | `androidx/media#1893` and Media3 1.9.0 stuck-player changes | Stuck player detection and wake lock defaults | Not merged; Media3 1.9.0 also raises `minSdk` to 23. | Do not wholesale backport. Prefer Cast-SDK-side timeout/retry policy and this fork's RTCP diagnostics. |
| 不回迁 | `androidx/media#3016`, `#2873`, `#2993`, `#2979`, preload/ads/CMCD changes | Core playlist/preload/ads/general ExoPlayer | Not RTSP-specific. | Do not merge for this fork unless Cast-SDK reports a matching failure outside RTSP. |
| 不回迁 | Media3 UI, Session, Transformer, IMA, Cast, downloads, inspector PRs | Non-RTSP modules | Outside fork scope. | Do not merge. |

Recommended next batch:

1. Next remaining RTSP client-interoperability item: compare and backport the 1.2.x TCP fallback race/hang fix if the diff is still relevant after the feedback changes.
2. UDP binding preparation fix has been backported as an isolated change with integration coverage.
3. Keep renderer/Surface/LoadControl PRs as evidence-driven follow-ups only.

### RTSP Protocol Interop

The first parser/client interop batch has been applied:

- SDP trailing whitespace (`androidx/media#2357`).
- SDP empty `i=` line tolerance.
- RTP header extension parsing hardening.
- `rtspt://` scheme as TCP transport alias (`androidx/media#1484`).
- 302 `Location` URI preservation (`androidx/media#2398`).
- RTSP setup state check (`androidx/media#577`) already present locally.
- OPTIONS `Public` header custom method tolerance (`androidx/media#613`).
- Server timeout based keepalive interval (`androidx/media#662`).
- Invalid SDP media description skipping (`androidx/media#1087`).
- Encoded `@` user-info handling (`androidx/media#1138`).

The following should remain separate follow-up work:

- TCP fallback race/hang fixes from the 1.2.x line.
- UDP port binding transient stalls/failures from 1.11.0-alpha01 are now backported and covered by
  preparation retry tests.

These are useful for third-party RTSP servers, but should not be mixed with the packet-reader correctness patch.

### Renderer, Surface, LoadControl, and Error Recovery

Renderer and load-control changes should not be mixed into the RTSP module patch:

- `MediaCodecVideoRenderer` and Surface fixes touch `library/core` or UI/session surfaces and carry wider playback risk.
- LoadControl default changes can affect non-RTSP playback and should first be expressed through Cast-SDK player configuration.
- Stuck-player detection is conceptually useful, but Media3 1.9.0 also raises `minSdk` to 23. Do not import its framework wholesale into the Android 4.4 fork.

## 2026-08-09 Broad ExoPlayer Playback Backport Review

Scope changed from RTSP-only to all ExoPlayer-related normal playback fixes that are valuable for
the fork while keeping Android 4.4+ support and avoiding Media3 module migration.

Implemented first batch:

Release status: published as the immutable full-module artifact set `2.19.1-labi.30` from source
commit `ca9c8d4dbb` and tag `exoplayer-rtsp-2.19.1-labi.30`.

| Priority | Media3 source | Area | Local decision | Status |
| --- | --- | --- | --- | --- |
| P0 | commit `8bf3b5c78191c4129e7318c9587cfc3b400387d3` | RTSP UDP port binding transient stalls/failures | Backport small RTSP-only diff. Retry `BindException` during preparation and preserve the original bind failure if channel creation fails before `dataChannel` is assigned. | Implemented. Covered by full RTSP unit tests, focused `RtpDataLoadableTest`, and `RtspMediaPeriodTest` preparation retry integration coverage. |
| P0/P1 | commit `d32189df0f8a59c8992b37ceff02f5d3ceb2f822` | `DefaultLoadControl` OOM guard for `prioritizeTimeOverSizeThresholds` | Backport heap-headroom guard into existing 2.19.1 single-player `DefaultLoadControl`. Preserve fork `setMinBufferFloorMs(int)` behavior. | Implemented. Covered by existing `DefaultLoadControlTest`; Media3 upstream did not add a deterministic heap-pressure unit test. |
| P1 | commit `6be48df57df0493868175b05050b9767965b61c4`, issue `androidx/media#3207` | `DefaultAudioSink` AudioTrack initialization retry | Media3 patch depends on newer `AudioOutputProvider`; manually ported the retry policy to 2.19.1 `AudioTrack` construction. On initialization failure, retry by halving down to the max of 1-second audio buffer and platform min buffer. | Implemented. Covered by threshold calculation, retry ordering, eventual success, frame alignment, suppressed failure, and terminal failure tests in `DefaultAudioSinkTest`. |

Second core-stability batch:

| Media3 source | Decision for ExoPlayer 2.19.1 | Status |
| --- | --- | --- |
| `da867c6b1ac93a6ce86413664bb02fa3df8a4058` | Applicable. ExoPlayer 2.19.1 unconditionally calls `MediaCodec.flush()` before the codec has necessarily received an input buffer. Some platform codecs then swallow subsequent samples. Skip only this empty flush; preserve every release/workaround and normal flush path. | Implemented and published as `2.19.1-labi.31` from source commit `53998610c7` and tag `exoplayer-rtsp-2.19.1-labi.31`. `MediaCodecRendererTest` covers both empty and non-empty codec reset paths. |
| `16cb8176055bf5680e1e54b918ee347e5f28c1cc` | Not applicable. The Media3 race comes from constructor-time background-looper access to `ExoPlayerImpl.period`. ExoPlayer 2.19.1 initializes and dispatches the audio session ID synchronously on the application thread, and `setAudioSessionId` verifies that thread. | No code change. |
| `d1a3251ca412f98af19f1e5b6b45c92ca356f64d` | Defer. The upstream change is not just a frame-rate fallback: it also changes the global policy from codec reinitialization to retaining an old operating rate when a new rate is unknown, and relies on newer renderer callbacks. Normal 1x RTSP playback does not benefit enough to justify this cross-renderer behavior change without a reproduction. | Evidence-driven only. |
| `59ace1a2bc0149073c1e3600845422d905c2a45b` | Not applicable as written. The fixed forced `join(renderNextFrameImmediately=true)` behavior belongs to newer `VideoFrameReleaseControl`; 2.19.1 surface replacement only sets a joining deadline and does not force the next frame to render immediately. | No code change. |
| `fd8a6b2c5750729120bee3b9bb52a8603c96da1d` | Not applicable as written. Media3 uses per-helper samplers and display callbacks that can enqueue duplicates. ExoPlayer 2.19.1 uses one shared `VSyncSampler`; only the observer-count transition from zero to one posts a callback, and display changes do not post another sampler callback. | No code change. |
| `f5d86b271ad6931a8606b97f6104a20968f82416` | Not applicable as written. It depends on Media3's newer decoder-input dropping and `VideoFrameReleaseControl` joining architecture, which 2.19.1 does not have. | No code change. |

The one-line skipped-input counter change bundled in Media3 commit `59ace1a...` was also excluded.
In 2.19.1 the relevant non-key sample is already filtered by the older `SampleQueue`/renderer path in
the tested scenario, so adding a renderer-only counter would not provide a complete or reliable
metric.

Do not merge now:

- Ktor, Session, UI, Transformer, IMA, Cast, Compose, downloads, inspector, and preload modules.
- HLS `Format.selectionPriority` / SCORE unless Cast-SDK has a matching HLS requirement.
- MPEG-PS, MP3 gapless, Dolby Vision profile-specific fixes unless product playback scope expands.

## Recommended Implementation Order

1. Done: Backport `RtpReaderUtils` timestamp wraparound and add focused tests.
2. Done: Backport H.264/H.265 fragmented NAL loss handling and AU boundary state as one combined reader patch.
3. Done: Add H.265 Aggregation Packet support with positive and malformed packet tests.
4. Done: Apply low-risk P1 parser/factory hardening for SDP, RTP header extension, and `rtspt://`.
5. Done: Backport RTSP redirect, setup-state, keepalive timeout, OPTIONS Public, invalid SDP media, and encoded user-info interop fixes.
6. Done: Backport first broad ExoPlayer playback batch: RTSP UDP bind retry, `DefaultLoadControl`
   OOM guard, and `DefaultAudioSink` AudioTrack retry down to 1-second threshold.
7. Done: Backport the applicable empty-codec flush fix as the isolated `labi.31`
   core-stability release.
8. Superseded by the 2026-09-19 sweep: the TCP fallback race/hang item is now covered by Batch A
   below, together with four related RTSP seek/stale-response state-machine fixes.
9. Later and evidence-driven: operating-rate behavior, frame-rate-change codec selection, and
   product-specific HLS/file extractor fixes.
10. Done: 2026-09-19 Batch B implemented — `AudioTrackPositionTracker` wrap-distance threshold and
    `writtenFrames` clamp, plus the `DefaultAudioSink` `outputBuffer` guard. `be15915b6abc` reviewed
    and dropped as not applicable as written. New `AudioTrackPositionTrackerTest`; full
    `com.google.android.exoplayer2.audio.*` suite (2395 tests) passed.
11. Next: implement Batch A (RTSP seek stability, five commits as one batch) as the `labi.33`
    candidate.

## Android 4.4 Compatibility

The P0 candidates are Java-level RTSP payload parsing changes and do not require newer Android framework APIs. They are compatible with the fork goal of Android 4.4+ support if backported manually into the existing ExoPlayer 2.19.1 package namespace.

Do not import Media3 Gradle configuration, modules, annotations, UI/session code, or AndroidX-wide minSdk assumptions.

## Implementation Status

2026-07-03 first P0 backport batch:

- Implemented RTP timestamp wraparound handling in `RtpReaderUtils`.
- Implemented H.264/H.265 access-unit boundary state reset by RTP timestamp.
- Implemented corrupted access-unit suppression for interrupted or missing H.264/H.265 Fragmentation Units.
- Implemented H.265 RTP Aggregation Packet support with malformed packet validation.
- Added focused tests:
  - `RtpReaderUtilsTest`
  - `RtpH264ReaderTest`
  - `RtpH265ReaderTest`

2026-07-03 second P1 backport batch:

- Implemented SDP trailing whitespace and empty `i=` tolerance in `SessionDescriptionParser`.
- Implemented RTP header extension skip/truncation validation in `RtpPacket`.
- Implemented `rtspt://` TCP alias in `RtspMediaSource.Factory`, with internal URI normalized to `rtsp://`.
- Added focused tests in `SessionDescriptionTest`, `RtpPacketTest`, and `RtspFeedbackApiTest`.

2026-07-03 third P1 interop backport batch:

- Implemented RTSP 302 `Location` URI preservation in `RtspClient`.
- Implemented OPTIONS `Public` header custom method tolerance in `RtspMessageUtil`.
- Implemented encoded `@` user-info handling in `RtspMessageUtil`.
- Implemented invalid SDP media description skipping in `SessionDescriptionParser`.
- Implemented server timeout based RTSP keepalive interval in `RtspClient`.
- Confirmed RTSP setup state check was already present locally.
- Added focused tests in `RtspClientTest`, `RtspMessageUtilTest`, and `SessionDescriptionTest`.

Verification status:

- `git diff --check` passed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:test` passed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest` passed after the third P1 interop batch.
- The earlier `:exoplayer-rtsp:test` command was invalid for this checkout; the correct Gradle target is `:library-rtsp:test`.

2026-08-09 first broad ExoPlayer playback backport batch:

- Implemented RTSP UDP bind retry during preparation and fixed `RtpDataLoadable` cleanup so a
  channel-open failure before `dataChannel` assignment preserves the original `IOException`.
- Implemented Media3 `DefaultLoadControl` heap-headroom guard for
  `prioritizeTimeOverSizeThresholds`, adapted to this fork's configurable `minBufferFloorUs`.
- Implemented `DefaultAudioSink` AudioTrack initialization retry down to a 1-second audio buffer
  threshold, adapted from newer Media3 `AudioOutputProvider` code back to ExoPlayer 2.19.1
  `AudioTrack` construction.
- Added focused tests:
  - `RtpDataLoadableTest`
  - `DefaultAudioSinkTest`

Verification status:

- `ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-rtsp:testDebugUnitTest`
  passed.
- `ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:testDebugUnitTest --tests com.google.android.exoplayer2.DefaultLoadControlTest --tests com.google.android.exoplayer2.audio.DefaultAudioSinkTest`
  passed.

## 2026-09-19 Upstream Watch: Media3 1.11.0 / 1.11.1 / `main`

Sweep boundary: everything after the 2026-08-09 review, which stopped at `1.11.0-alpha01`
(2026-06-23). New material reviewed:

- `release` branch RELEASENOTES: `1.11.0` (2026-08-05), `1.11.1` (2026-09-10).
- `main` branch RELEASENOTES `Unreleased changes` (future `1.12.0`).
- Commit-level diffs by path: `libraries/exoplayer_rtsp`, `.../audio/*`, `.../video/*`,
  `.../ExoPlayerImpl.java`.

Method note: the GitHub mirror's default branch is `release`, which is cut per release. Fixes that
are merged but not yet shipped only exist on `main`, so a path-scoped commit query must pass
`sha=main`. Querying the default branch silently returns nothing for post-release fixes.

### Already covered by this fork (verified, no action)

| Upstream item | Where it appears | Fork status |
| --- | --- | --- |
| `DefaultLoadControl` OOM guard | 1.11.0 note | Backported in `labi.30` |
| `AudioTrack` init retry down to 1 s (`#3207`) | 1.11.0 note | Backported in `labi.30` |
| Codec swallowing samples after empty flush | 1.11.0 note | Backported in `labi.31` |
| Audio session ID race (`#3241`) | 1.11.0 note | Assessed not applicable |
| RTSP UDP port binding retry | 1.11.0 RTSP section | Backported (commit `8bf3b5c78191`) |
| Operating-rate fallback, scrub-mode frames, joining-drop accounting, `VideoFrameReleaseHelper` display changes | 1.11.0 video notes | Assessed not applicable / evidence-driven |

The 1.11.0 RTSP extension section contains only the already-backported UDP bind retry, so the RTSP
release notes for 1.11.0/1.11.1 add nothing new. The valuable RTSP work is all still unreleased on
`main`.

### Batch A — RTSP seek / stale-response state machine (5 commits, unreleased)

All five are on `main` only and belong to one state-machine fix series. Local line numbers below
refer to the current working tree at `eddd9003ff`.

| Order | Commit | Date | Subject | Local gap |
| --- | --- | --- | --- | --- |
| 1 | `31fce52a2de2` | 2026-08-18 | Clear pending RTSP requests when resetting state to INIT | `sendSetupRequest`, `sendTeardownRequest` and the 302 `Location` branch never call `pendingRequests.clear()`. A reply that arrives after a state reset is matched against a stale request. |
| 2 | `c103d65280a6` | 2026-07-22 | Prevent `RtspClient` from processing stale RTSP responses post `close()` | `close()` only closes `keepAliveMonitor` + `messageChannel`. It never resets `rtspState`, nulls `sessionId`, or clears `pendingSetupRtpLoadInfos` / `pendingRequests`, and it only sends TEARDOWN when `keepAliveMonitor != null`. |
| 3 | `c93d54c1ceef` | 2026-08-19 | Handle pending RTSP seeks during init states | **Real crash path.** `RtspMediaPeriod.seekToUs` still has `case RTSP_STATE_UNINITIALIZED: case RTSP_STATE_INIT: default: throw new IllegalStateException();` with the comment `// Never happens.` Combined with fix 2 making `close()` always reset to `UNINITIALIZED`, a seek arriving after close/reconnect now hits this throw. |
| 4 | `cef7def9263c` | 2026-09-17 | Reset RTSP loader wrappers when updating pending seek position | Three separate gaps: `requestedSeekPositionUs` is assigned before the `isSeekPending()` test; the pending branch never calls `rtspLoaderWrappers.get(i).seekTo(...)` (only the non-pending path does); and `onPlaybackStarted` re-enters `seekToUs(requestedSeekPositionUs)` while also clearing the same field. Result: loadable target position diverges from the pending seek position, which is the reported backward-seek audio dropout / video freeze. |
| 5 | `d0ad9729a784` | 2026-07-27 | Prevent accidental RTSP TCP fallback when rapid scrubbing | Both guards miss `!prepared`: `seekToUs` line 489 (`getBufferedPositionUs() == 0 && !isUsingRtpTcp`) and `onLoadCompleted` line 1223 (`getBufferedPositionUs() == 0`). On an already-prepared period, seeking to 0 falsely triggers the UDP→TCP fallback. |

Adaptation notes:

- Commit 3 (`31fce52a2de2`) also refactors `setupSelectedTracks` to take a new `TrackSetupInfo`
  carrier instead of `RtpLoadInfo`. That refactor only exists to decouple the RTSP module from
  `RtspMediaPeriod` in Media3's module graph. It is not needed here; backport only the three
  `pendingRequests.clear()` insertions.
- Commit 2 (`c103d65280a6`) must additionally clear this fork's own `currentSetupRtpLoadInfo` field,
  which does not exist upstream.
- Backporting order matters. Commit 3 only becomes reachable once commit 2 resets the state on
  close, so applying commit 3 alone leaves the state reset inconsistent. Keep the batch atomic.

Risk: low on Android API surface (all `library/rtsp`, no framework API changes), medium on behaviour
because it rewrites the seek state machine. Upstream ships `RtspMediaPeriodTest` and `RtspClientTest`
cases with these commits; port them rather than writing new coverage from scratch.

#### Batch A implementation status

Implemented from source commit `eddd9003ff`. All five commits were applied in one change set, as
required; nothing in `library/rtsp` was partially applied.

| File | Change |
| --- | --- |
| `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspClient.java` | `close()` now sends TEARDOWN whenever `sessionId != null`, then resets `rtspState` to `RTSP_STATE_UNINITIALIZED`, nulls `sessionId`, and clears `pendingSetupRtpLoadInfos`, `currentSetupRtpLoadInfo` and `pendingRequests`. `retryWithRtpTcp()` no longer nulls `sessionId` itself. `pendingRequests.clear()` added in all three places `31fce52a2de2` touches: `sendSetupRequest`, `sendTeardownRequest` and the 302/301 `Location` branch. |
| `library/rtsp/src/main/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriod.java` | `seekToUs` no longer throws for `RTSP_STATE_UNINITIALIZED` / `RTSP_STATE_INIT`; the pending-seek branch now records `requestedSeekPositionUs` and re-seeks every `RtpLoadWrapper`. Both UDP-first-packet guards gained `!prepared`. `onPlaybackStarted` clears `requestedSeekPositionUs` before re-entering `seekToUs`. |
| `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtspMediaPeriodTest.java` | Extended by 570 added / 6 removed lines: seven new seek tests plus a `TestResponseProvider`, a `FakeRtpDataChannel` that can be made to finish its load, and a `FakeRtpDataChannelFactory` that records whether the TCP fallback was used. `PAUSE` support added to the server double. |
| `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtspClientTest.java` | Ported `close_serverWithoutDescribeSupport_clearsPendingRequestAndPreventsError` from `c103d65280a6`. |
| `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtspTestUtils.java` | RTP-Info urls changed from absolute (`rtsp://localhost/test/...`) to relative. See the deviation note below. |
| `library/rtsp/src/test/java/com/google/android/exoplayer2/source/rtsp/RtspServer.java` | Added `RtspServer.ResponseProvider#getPauseResponse` and a `METHOD_PAUSE` branch. |

Adaptation notes for this fork:

- `31fce52a2de2` brings three `pendingRequests.clear()` insertions only. Its `TrackSetupInfo`
  refactor was dropped, because it exists solely to break the `rtsp` → `RtspMediaPeriod` dependency
  in Media3's module graph.
- `c103d65280a6` also clears the fork-private `currentSetupRtpLoadInfo`, which has no upstream
  counterpart.
- `default: throw new IllegalStateException()` is kept in `seekToUs` for genuinely impossible states;
  only the two init states were moved into the pending-seek branch.

Verification:

- All five commits were re-diffed against upstream during review. Four matched on inspection; the
  `pendingRequests.clear()` insertions of `31fce52a2de2` had only landed in `sendSetupRequest`, so
  the `sendTeardownRequest` and 302/301 `Location` insertions were added and the whole suite re-run.
  Both are now in place, so `RtspClient.java` tracks `31fce52a2de2` + `c103d65280a6` line-for-line
  (plus the fork-private `currentSetupRtpLoadInfo` clear).
- `git diff --check` clean.
- `:library-rtsp:test` (`testDebugUnitTest` + `testReleaseUnitTest`) → 32 classes, 341 tests,
  0 failures, 0 skipped in both variants.
- Baseline stability gate: the whole 20-test `RtspMediaPeriodTest` set was run 5 times back to back
  with zero flaky tests before any mutation probing, and the full class was run 8 times during
  de-flaking.
- All mutations were reverted and the tree re-verified afterwards (`git diff --check`, full suite).

#### Batch A deliberate test deviations

Two assertions differ from the upstream tests on purpose. Both are consequences of the fork's own
test doubles, not of the production code.

- **Relative RTP-Info urls.** The pre-existing local `RtspTestUtils` formatted RTP-Info as
  `url=rtsp://localhost/test/%s;...` with a hard-coded port, while `RtspMediaTrack` derives its uri
  from the `Content-Base` header, which uses a different port. The two never matched, so
  `RtpDataLoadable` never applied the timestamp and the seek paths under test were never actually
  exercised — three ported tests failed with `expected: 640000 but was: 0`. The url is now relative
  and resolved against the session uri by `RtspTrackTiming.resolveUri`, which is what upstream does
  and what a real server sends. This was a latent test-infrastructure bug, not a product bug.
- **`> SEEK_POSITION_US` instead of `640_000`.** `RtpPacketReorderingQueue` flushes against wall-clock
  time while the dump is indexed by RTP timestamps, so the exact buffered position at the moment the
  assertion runs is genuinely non-deterministic — the same test was observed returning `640000` and
  `6037000` on different runs. The three affected tests assert that the buffered position advanced
  past the seek position instead of comparing with a fixed value; a position that stays at the seek
  position, or at 0, still fails. The in-buffer test compares against a value captured in the same
  run, so it is not weakened at all.

#### Batch A mutation matrix and coverage gaps

Eleven mutations were applied to the production code, one group at a time, and the target test set was
re-run for each, against the clean baseline above. The `RtspMediaPeriod` probes were run against the
full 20-test class; the client-side `pendingRequests.clear()` probes were run against the RTSP test
classes, and the combined M6b+M6c run against the whole `com.google.android.exoplayer2.source.rtsp.*`
package.

| # | Mutation | Result |
| --- | --- | --- |
| M1 | Pending-seek branch no longer records `requestedSeekPositionUs` | KILLED |
| M2 | Pending-seek branch no longer re-seeks the loader wrappers | KILLED |
| M3a | Drop `!prepared` from the UDP first-packet guard in `seekToUs` | KILLED |
| M3b | Drop `!prepared` from the guard in `onLoadCompleted` | KILLED by `onLoadCompleted_afterPrepare_doesNotTriggerTcpFallback` |
| M4 | `seekToUs` throws again for `RTSP_STATE_UNINITIALIZED` / `RTSP_STATE_INIT` | KILLED |
| M5a | `close()` does not clear `pendingRequests` | KILLED by `close_serverWithoutDescribeSupport_clearsPendingRequestAndPreventsError` |
| M5b | `close()` does not reset `rtspState` / null `sessionId` | **SURVIVED** |
| M6a | `sendSetupRequest` does not clear `pendingRequests` | **SURVIVED** |
| M6b | 302/301 `Location` branch does not clear `pendingRequests` | **SURVIVED** |
| M6c | `sendTeardownRequest` does not clear `pendingRequests` | **SURVIVED** |
| M7 | `onPlaybackStarted` re-enters `seekToUs` with the field still set | **SURVIVED** |

Five probes are bound to a test. The survivors are honest gaps and should not be read as verified:

- **M6a/M6b/M6c — none of the three `31fce52a2de2` insertions is covered by any assertion.** The
  structural reason is that every mutation only matters when a response arrives *after* a state
  reset, and neither test double can hold a response back: `RtspServer.ResponseProvider` answers
  synchronously inside `handleRtspMessage`. Upstream's own test for this commit,
  `setupSelectedTracks_withDelayedPlayResponse_clearsPendingRequestAndPreventsError`, is built on the
  `TrackSetupInfo` carrier that this fork deliberately did not take — it injects a transport string
  directly instead of waiting for a live `RtpDataLoadable` to populate one. So it cannot be ported as
  written. Closing this gap needs either `TrackSetupInfo` or a server double with a response latch.
- **M6c is additionally redundant today.** `sendTeardownRequest` has exactly one caller in this fork
  — `close()` — and `close()` clears `pendingRequests` itself a few lines later. The insertion is
  kept so the fork tracks upstream line-for-line (it matters as soon as any other path tears a
  session down), not because it changes current behaviour.
- **M5b** is only partly covered: the ported client test asserts the reset state, but not the
  `sessionId` nulling that makes a stale TEARDOWN impossible.
- **M7** is guarded by the `requestedSeekPositionUs` bookkeeping around the re-entrant call. The
  current tests do not distinguish the two orderings.

None of these is a common-path behaviour difference, so they do not block the batch. They are listed
so a later reader does not mistake a green suite for full coverage.

### Batch B — audio underrun false buffering and media clock snap

Two of the three commits are applicable and implemented. The third is not applicable as written; the
reasoning is given below the table.

| Commit | Date | Released in | Subject | Local status |
| --- | --- | --- | --- | --- |
| `4c95cd96b53e` | 2026-09-18 | unreleased (`#3407`) | Do not treat unexpected `AudioTrack` position decrease as overflow | Applicable. Lines 645-648 are exactly the code being fixed: `if (this.rawPlaybackHeadPosition > rawPlaybackHeadPosition) { rawPlaybackHeadWrapCount++; }`. Any position reset or decrease is counted as a 32-bit wrap, jumping the position by 2^32 frames (~24.8 h at 48 kHz), which permanently breaks `hasPendingData()` and stalls playback in `STATE_BUFFERING`. Implemented. |
| `bc0652cb5471` | 2026-05-29 | 1.11.0 | Fix media clock snap during audio underruns (`#3210`) | Applicable. `AudioTrackPositionTracker.getCurrentPositionUs` had no clamp against `writtenFrames`, so the timestamp poller could extrapolate past the frames actually written and then snap back on recovery. Implemented. |
| `be15915b6abc` | 2026-05-27 | 1.11.0 | Fix transient buffering during audio underruns (`#3210`) | **Not applicable as written.** See below. |

#### Why `be15915b6abc` is not applicable as written

The upstream patch replaces a sink-only readiness check with a 100 ms grace period:

```java
// Media3 1.11.0 DecoderAudioRenderer / MediaCodecAudioRenderer
public boolean isReady() {
  boolean isReady = audioSink.hasPendingData();
  if (isReady) { ...; return true; }
  if (hasBeenReady && isStarted && isSourceReady() && !hasReadStreamToEnd()) { /* 100 ms grace */ }
  return false;
}
```

Media3 could drop everything else because by 1.11.0 both audio renderers report readiness from the
sink alone. 2.19.1 still ORs in the decoder state, and that changes the reachability of the grace
branch:

- `MediaCodecAudioRenderer.isReady()` line 638 is `audioSink.hasPendingData() || super.isReady()`.
- `DecoderAudioRenderer.isReady()` line 565 is
  `audioSink.hasPendingData() || (inputFormat != null && (isSourceReady() || outputBuffer != null))`.
- `MediaCodecRenderer.isReady()` line 1721 is
  `inputFormat != null && (isSourceReady() || hasOutputBuffer() || codecHotswapDeadlineMs ...)`.

The grace branch requires `isSourceReady()` to be true, but it is only reached after the sink check
and the OR-ed decoder check both returned false. With `inputFormat != null`, `isSourceReady() == true`
implies `MediaCodecRenderer.isReady() == true`, so the branch is unreachable. The only combination
that reaches it is `inputFormat == null`, which `onDisabled()` sets, and that is incidental to the
patch's bookkeeping rather than the downstream-underrun scenario it targets.

The remaining case, sink empty and source not ready, is genuine upstream starvation. Debouncing it
would suppress the buffering indicator on a receiver whose RTSP feed has stalled, which is exactly
the signal the product needs. Porting it as written would therefore be dead code, and porting it
with a loosened condition would be a behaviour change in the wrong direction. No code change.

#### Batch B adaptation notes

- `bc0652cb5471` changes `getCurrentPositionUs()` to take a `writtenFrames` argument. 2.19.1 keeps
  the `sourceEnded` parameter because it is used for the latency adjustment, so the local signature
  became `getCurrentPositionUs(boolean sourceEnded, long writtenFrames)`. The call sites are
  `DefaultAudioSink.getCurrentPositionUs` (passes `getWrittenFrames()`) and the tracker's own
  `hasPendingData(writtenFrames)`.
- `4c95cd96b53e` needs `MIN_RAW_PLAYBACK_HEAD_POSITION_WRAP_DISTANCE = 1L << 31` plus an `else`
  branch calling `resetSyncParams()` and resetting the timestamp poller — both helpers already exist
  locally. The poller is null-guarded locally, unlike upstream.
- The `DefaultAudioSink.hasPendingData()` hunk assumes Media3's `AudioOutput` abstraction. 2.19.1
  delegates to `audioTrackPositionTracker.hasPendingData(getWrittenFrames())`, so the guard was
  re-expressed as `outputBuffer != null || audioTrackPositionTracker.hasPendingData(...)`. Without
  it, the new clamp would report "no pending data" while a buffer is still queued for the AudioTrack.
- The existing sink-level `min(positionUs, configuration.framesToDurationUs(getWrittenFrames()))` in
  `DefaultAudioSink.getCurrentPositionUs` is now redundant but was deliberately kept, to avoid an
  unrelated behaviour change in the same patch.

#### Batch B implementation status

Implemented from source commit `eddd9003ff`:

| File | Change |
| --- | --- |
| `library/core/src/main/java/com/google/android/exoplayer2/audio/AudioTrackPositionTracker.java` | Wrap distance threshold and stale-offset reset in `updateRawPlaybackHeadPosition`; `writtenFrames` clamp with state reset in `getCurrentPositionUs`. |
| `library/core/src/main/java/com/google/android/exoplayer2/audio/DefaultAudioSink.java` | Passes `getWrittenFrames()` to the tracker; `outputBuffer` guard in `hasPendingData()`. |
| `library/core/src/test/java/com/google/android/exoplayer2/audio/AudioTrackPositionTrackerTest.java` | New. Four tests covering a genuine wrap-around, an unexpected decrease, a residual position resetting to zero, and the written-frames clamp. |

Verification:

- `:library-core:testDebugUnitTest --tests com.google.android.exoplayer2.audio.*` passed.
- `AudioTrackPositionTrackerTest` 4/4 and the pre-existing `DefaultAudioSinkTest` 37/37 passed.
- Mutation check: setting the wrap distance to `0` and disabling the clamp made exactly the three
  expected tests fail, while the genuine wrap-around test still passed. This confirms the tests bind
  to the fix rather than passing incidentally.
- Test-runtime note: Robolectric does not virtualise `System.nanoTime()`, and the tracker smooths
  playback head samples against it, so a reported position carries a sub-millisecond jitter around
  the exact frame duration (measured 113-662 µs). Assertions therefore discriminate on the 2^32
  frame order-of-magnitude gap instead of exact values, and the "no wrap" cases use a written frame
  count above 2^32 (`5_000_000_000`) so the mistaken jump is not clamped away before it is observed.


### Batch C — watch only, do not backport

| Upstream | Content | Decision |
| --- | --- | --- |
| 1.11.0 ExoPlayer | Dynamic scheduling enabled by default, plus the follow-up `#3286` stale-position fix | 2.19.1 has no dynamic scheduling. Enabling it is a global behaviour change. Skip. |
| 1.11.0 Video | `MediaCodecVideoRenderer.Builder.setMaxEarlyUsThreshold()` (50 ms default) | No equivalent builder in 2.19.1. Defer unless evidence points at early-frame scheduling. |
| 1.11.0 / 1.11.1 / `main` video | Dropped-vs-skipped accounting, identical release timestamps, stale frames after a skipped flush | Already evaluated as not applicable; upstream restructured these onto `VideoFrameReleaseControl`, which 2.19.1 lacks. |
| 1.11.1 | Pre-warm stalls, and Surface return to the primary renderer when a seek resets both renderers | Secondary-renderer prewarm path. The receiver plays a single video stream. |
| 1.11.0 Extractors | AVI audio OOM, MP4 empty `ilst` OOB, Matroska tracks after clusters, MPEG-TS last frame | Unrelated to the RTSP live path unless a matching container shows up in the field. |
| `main` | `setLoadOnlySelectedTracks(true)` default on | `ProgressiveMediaSource` / `DefaultMediaSourceFactory` path only. RTSP does not use it. |
| `main` | Multiple `VideoFrameMetadataListener`, central `Flags` registry, playlist ID | API evolution on Media3 surfaces. Not applicable. |

### Priority summary

| Rank | Batch | Why | Suggested release |
| --- | --- | --- | --- |
| 1 | B, implemented (`bc0652cb5471`, `4c95cd96b53e`) | Directly targets the `STATE_BUFFERING` stall and A/V drift under weak network; small and isolated in `library/core` audio. `be15915b6abc` dropped with a structural reason. | `labi.32` candidate |
| 2 | A, implemented (`31fce52a2de2`, `c103d65280a6`, `c93d54c1ceef`, `cef7def9263c`, `d0ad9729a784`) | Contains a genuine `IllegalStateException` crash path and the rapid-scrub seek desync; shipped as one atomic batch. Three of the nine mutation probes survived — see the coverage-gap list | `labi.33` candidate |
| 3 | C | Behaviour changes without local reproduction | Evidence-driven only |
