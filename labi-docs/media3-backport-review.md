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
| `da867c6b1ac93a6ce86413664bb02fa3df8a4058` | Applicable. ExoPlayer 2.19.1 unconditionally calls `MediaCodec.flush()` before the codec has necessarily received an input buffer. Some platform codecs then swallow subsequent samples. Skip only this empty flush; preserve every release/workaround and normal flush path. | Implemented. `MediaCodecRendererTest` covers both empty and non-empty codec reset paths. Planned for `2.19.1-labi.31`. |
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
7. In progress: Backport the applicable empty-codec flush fix as the isolated `labi.31`
   core-stability release.
8. Next: Review TCP fallback race/hang as a separate fallback-stability batch.
9. Later and evidence-driven: operating-rate behavior, frame-rate-change codec selection, and
   product-specific HLS/file extractor fixes.

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
