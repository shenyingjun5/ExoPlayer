# Portrait HEVC / Dolby Vision base-layer compatibility (2026-10-13)

## Current scope — 2026-10-14 release convergence

The user deferred HEVC/Dolby Vision/HDR. The candidate now restores the baseline Dolby Vision
profile assignment in `getMediaFormat`; the HEVC profile experiment remains in Git history
(`c0d7bf127b`), not in the final working-tree implementation. The corresponding profile tests
now assert baseline behavior. The historical analysis below is evidence, not an active release claim.

Retained scope: opt-in, exact-device AVC SDR rotation, API19 timestamp/flush lifecycle fixes,
frame-processor callback/size cleanup and actual audio EOS drain completion. The EOS drain change
is shared by Exo audio playback, and frame-processor infrastructure is shared with existing effects;
these require regression beyond the two rotation devices. Normal running audio clock is unchanged.
Receiver preselection additionally restricts API19 Konka/rtd294x_tv035 and API22 amlogic/r34a0.
The renderer still owns codec/format/angle/color/protection/tunneling/effect eligibility.

The installed `portrait-api19-flush2` APK still contains the earlier HEVC experiment and is only
historical acceptance evidence. It must not be promoted as the final binary. No formal Maven
version, published `.35` artifact, or release identity has been replaced by this convergence.

## Baseline and evidence

- This change starts from immutable tag `exoplayer-rtsp-2.19.1-labi.35`, source commit `89b8f82d22ce302819cc604e1959a5ebd610a17c`. The main checkout has unrelated uncommitted audio and RTSP experiments; this candidate is isolated from them.
- The fixed input is `/Users/shenyingjun/Downloads/IMG_3160.MOV` (SHA-256 `efe295f2cffe951dd8534e5580f462343e51b7dceec61e002fd6ef7b4aac54ca`): HEVC Main10, coded 1920×1080, 11.598333 s, Dolby Vision profile 8 / level 4 / compatibility ID 4, HLG/BT.2020, display matrix −90°.
- The older QC log at 17:54 describes a 16.533333 s item. It is not byte-identity evidence for `IMG_3160.MOV`; its reported portrait `VideoSize` also does not prove pixel rotation.
- On 5Y (API 19), Legacy Exo reports `DecoderInitializationException` with `IllegalStateException` for `video/dolby-vision` / `hev1.08.04` using `OMX.RTK.video.decoder`, then the receiver's existing SYSTEM fallback plays. The device codec XML declares `video/hevc` but not `video/dolby-vision`.
- The 2026-10-14 same-byte QC comparison gives a narrower result: iOS Photos `.compatible` H.264 input is upright through SYSTEM and sideways through Legacy Exo. The original HEVC input has also appeared sideways through SYSTEM, so SYSTEM is not a general HEVC rotation fallback.

## Why this change touches library/core

`MediaCodecVideoRenderer.getMediaFormat` configures the actual platform decoder. The input `Format` can retain `video/dolby-vision` while Media3/Exo's existing compatible base-layer selection chooses an HEVC codec with `codecMimeType=video/hevc`. Before this change, the renderer writes the Dolby Vision `KEY_PROFILE` into that HEVC `MediaFormat`. A Dolby Vision profile constant is not an HEVC profile constant and may cause `MediaCodec.configure` to fail. This is a renderer configuration defect, so it cannot be fixed in `library/rtsp`; local HTTP/MP4 playback also reaches it.

The candidate keeps the existing decoder selection, capability result, initialization data, color information, and Android API guards. It writes the Dolby Vision `KEY_PROFILE` only when the selected codec itself is `video/dolby-vision`. It does not claim that a codec supports Main10 or Dolby Vision, does not discard HLG/BT.2020, and does not add any decoder to the available list. A 5Y device run is still required to establish whether omitting the mismatched key lets its HEVC decoder initialize and decode this sample.

## QC rotation boundary and receiver contract

At API >= 21, `codecAppliesRotation()` assumes `MediaCodec` applies `KEY_ROTATION` when rendering to a Surface. `onOutputFormatChanged` swaps width/height for a 90°/270° input and reports unapplied rotation 0. Those values are metadata derived from the assumption; output dimensions cannot verify pixel orientation. The current evidence does not identify an exact, safe device/codec/source quirk that would make the Fork rotate pixels by itself.

The receiver's SYSTEM route is upright for the confirmed H.264 sample, but this does not establish a general route rule for other inputs. The QC nickname alone is not a stable runtime key; any route decision needs an exact input/firmware comparison and must not conceal Exo's rotation ownership defect.

If retaining the Exo route is required and the fixed sample still appears sideways, the receiver needs a device-scoped output transform for that confirmed path only:

1. Scope to the verified QC manufacturer/model/firmware and selected codec. The confirmed H.264 failure uses `OMX.amlogic.avc.decoder.awesome`; the HEVC path is a separate case. Do not infer pixel orientation from the delivered `VideoSize` alone.
2. Use an output surface capable of applying a texture transform for that path, with the input display matrix as the source of truth. Preserve aspect ratio and crop, and clear the transform on source replacement, route change, surface recreation, or a zero-rotation item.
3. Establish a single rotation owner. The Fork currently passes `KEY_ROTATION` to the codec on API 22 and assumes it was applied. If device testing proves that this codec ignores it, a codec-scoped Fork quirk should suppress the key and report the source rotation as unapplied so the receiver can transform the pixels exactly once. Do not enable that quirk before pixel verification. If the codec already rotates pixels on a particular format or firmware, disable the output transform. Verify actual pixels against SYSTEM on the same SHA-256 sample, and test 0°, 90°, 180°, 270°, physically portrait frames, and replacement transitions.
4. Do not switch every output to `TextureView` or rotate in CPU memory. The Fork cannot guarantee upright QC output from `VideoSize` or `KEY_ROTATION` alone.

### Read-only platform and receiver review

- The [Android `MediaFormat.KEY_ROTATION` reference](https://developer.android.com/reference/android/media/MediaFormat#KEY_ROTATION) lists the public constant as added in API 23. That does **not** prove API 22 cannot handle the literal `rotation-degrees` key: official [AOSP 5.0.2 `ACodec.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-5.0.2_r1/media/libstagefright/ACodec.cpp) and [AOSP 5.1.1 `ACodec.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-5.1.1_r18/media/libstagefright/ACodec.cpp) already read that key and map 90°/180°/270° to `native_window_set_buffers_transform`. The [platform `MediaCodec` documentation](https://developer.android.com/reference/android/media/MediaCodec#Transformations%20When%20Rendering%20onto%20Surface) warns that some pre-Android-6 software decoders may not apply rotation. Neither the AOSP implementation nor that warning establishes what QC's vendor `OMX.amlogic.hevc.decoder.awesome` actually renders.
- In this Fork, `MediaCodecVideoRenderer.getMediaFormat` passes the source rotation via `KEY_ROTATION` and `codecAppliesRotation()` returns true for API >= 21. `onOutputFormatChanged` then swaps the reported dimensions and sets unapplied rotation to zero. This is a framework/surface transform assumption, not proof that the decoded pixels or the vendor output buffer were transformed.
- The existing Amlogic workaround in `MediaCodecRenderer.codecNeedsEosFlushWorkaround` is for specific API <= 19 `OMX.amlogic.avc.decoder.awesome` devices after EOS. `MediaCodecInfo.needsRotatedVerticalResolutionWorkaround` checks capability declarations, not rendered orientation. There is no matching HEVC rotation quirk or known codec parameter in this Fork to reuse safely.
- Both receiver adapters accept a `Surface`. `SystemMediaPlayerAdapter` passes the URL and surface to framework `MediaPlayer`; `LegacyExoPlayerAdapter` passes a surface to Exo. The normal UI path is a `SurfaceView`; `VideoFitCalculator` uses rotation only to calculate layout size. It does not rotate pixels. The existing `EnhancedVideoRenderContainer` is a separate GLES2 shader path, does not add a source-rotation matrix, and must not be enabled as a blanket fix for this HLG/Main10 sample without color/HDR validation.
- The older QC Exo log is for a 16.533333 s item. The 2026-10-14 same-byte A/B is now available for the 11.6 s iOS-compatible H.264 input. It proves a route difference on that input, but does not distinguish ignored `KEY_ROTATION`, incorrect sign, or a platform/surface transform lost after configuration.

### 2026-10-14 same-byte H.264 evidence and owner boundary

- Input: iOS Photos `.compatible` MOV SHA-256 `095ad3d43e129627600780a3c760334e2d6d0e5f13818176788800c3ba5c28c7`; H.264 High, coded 1920×1080. Its `tkhd` matrix has `a00=0, a01=65536, a10=-65536, a11=0`. `AtomParsers.parseTkhd` maps that exact matrix to `Format.rotationDegrees=90`; `ffprobe` calls the same display matrix `rotation=-90` because its sign convention differs. Exo's `getMediaFormat` writes the `Format` value to `MediaFormat.KEY_ROTATION` (`rotation-degrees`). The actual configured key has not yet been captured from a device-side codec trace.
- Device properties from the saved `getprop`: API 22, `ro.product.manufacturer=amlogic`, `ro.product.model=AOSP on r34a0`, fingerprint `Android/r34a0/r34a0:5.1.1/LMY47V/20201105:userdebug/dev-keys`. Legacy Exo first-frame event names `OMX.amlogic.avc.decoder.awesome`. The user saw Exo sideways and SYSTEM upright with this exact HTTP resource and APK. Both adapters reported `1080×1920 / rotation=0`; on Exo these fields follow `codecAppliesRotation()` and are not pixel evidence. The saved ADB screenshots are black captures; the visual result is user observation.
- The receiver's later `dumpsys media.player` capture identifies SYSTEM's H.264 path as `AmlogicPlayer` / `VFORMAT_H264`, without naming an OMX component. The Exo/SYSTEM result therefore compares two player pipelines, not the same OMX decoder configuration. The black ADB capture may be related to vendor overlay output; it does not prove a `TextureView` or `SurfaceTexture` can acquire these decoded pixels. Test that before selecting an output-transform implementation.
- AOSP 5.1.1 `ACodec` knows the `rotation-degrees` key and uses `native_window_set_buffers_transform`, so removing the key globally on API 22 is not justified. The Fork has no direct evidence that this Amlogic codec ignores the key; a configuration-only fix remains conditional on an exact-device visual check. If the key is ignored, a change only to `codecAppliesRotation()` can report `unappliedRotationDegrees=90` but cannot rotate pixels on the receiver's `SurfaceView`. The receiver would need one scoped output transform, with the Fork suppressing codec rotation for that path to avoid double rotation.

### Smallest discriminating device check

1. Keep the confirmed H.264 SHA-256, QC firmware, `SurfaceView`, and enhancement-off setting fixed. Capture the selected decoder, extractor `Format.rotationDegrees`, configured `MediaFormat.KEY_ROTATION`, output crop/size, and orientation after the final surface update. The extractor's 90° result is established from the matrix and code path, but the per-session configured value still needs a codec-side trace.
2. With a diagnostic-only AAR scoped to this exact manufacturer/model/fingerprint, codec, and H.264 input, compare the current key (expected 90°), 270°, and 0° while leaving UI transforms disabled. An upright 270° result implicates sign handling; an upright 0° result implicates a double transform; if all remain sideways, the key or native-window transform is likely ignored/lost. The last result still needs an output-buffer/native-window trace before assigning blame to the vendor codec.
3. If configuration cannot rotate actual pixels, a Fork-only change to `VideoSize` is insufficient. Pair an exact-path Fork rotation-ownership quirk (do not send `KEY_ROTATION`, report 90° unapplied) with one receiver output transform. Test actual pixels and aspect ratio across source replacement, surface recreation, seek, and 0°/90°/180°/270° inputs. Validate H.264 before considering the separate HDR/HEVC path; do not use a general SYSTEM fallback for HEVC.

The receiver task has prepared `-c copy` H.264 MOV variants with effective display-matrix rotations 0° and 180°. All three files have the same encoded-video-packet SHA-256 `6b997af702359a44fccc91124edea6b15374f4b7a27b3e30a9f6b3e6d7d82b18`. These are a useful first input-metadata experiment without publishing a new Fork artifact. The variants still change the renderer's derived display size, so compare visible pixel orientation and native-window logs, not `VideoSize` alone.

### Opt-in Fork rotation-ownership candidate (not a pixel fix)

The 0° matrix variant played through the same QC Legacy Exo codec with the same encoded video packets. The user saw the same sideways person at the correct landscape layout size. Thus the original matrix changed Exo's layout metadata but did not rotate the observed pixels. The receiver's output-transform experiment is still required; SYSTEM's `AmlogicPlayer` result does not prove that the Exo codec can rotate or that a GPU surface can sample its output.

The candidate touches `library/core` because `MediaCodecVideoRenderer` is where the decoder `MediaFormat` and delivered `VideoSize` are decided. It adds a default-off factory option scoped to API 22, manufacturer `amlogic`, device `r34a0`, exact decoder `OMX.amlogic.avc.decoder.awesome`, and AVC input with normal non-tunneled, non-frame-processed output. When enabled and matched, the Fork omits `KEY_ROTATION` from the codec configuration and reports the source rotation as unapplied with coded width/height. It does **not** rotate pixels or enable any receiver output transform. All other devices, codecs, formats, and disabled sessions retain the existing API >= 21 behavior. The receiver must enable the option only for a session with a separately verified single output transform; enabling the Fork option alone can change layout without fixing the visible frame.

The opt-in contract requires the receiver to consume `VideoSize.unappliedRotationDegrees`, apply that rotation exactly once to actual output pixels, preserve crop/aspect ratio, and reset the transform on 0° input, source/route change, and surface recreation. Before production use, verify actual pixels on the same QC firmware for 0°/90°/180°/270° matrices, source replacement, surface recreation, and A/V continuity. HEVC/Dolby Vision and HDR handling are outside this candidate.

Implementation is through `DefaultRenderersFactory.setEnableAmlogicR34a0AvcUnappliedRotation(boolean)` before `createRenderers`. A receiver that loads Exo reflectively can invoke this setter on its factory only for the experimental QC H.264 session and only after its output transform has been independently verified. The renderer checks the selected decoder at configuration time, so a decoder fallback, unsupported API, or mismatched device retains the default codec-owned rotation path. The opt-in state is local to that renderer/factory instance, not a process-wide switch. Host tests cover the exact-match gate, exclusions, omitted `KEY_ROTATION`, coded dimensions and unapplied 90°, plus default behavior. `MediaCodecVideoRendererTest` passed 29/29 tests; `:library-core:assembleRelease` passed. Neither result proves hardware pixel rotation.

### Renderer-owned QC GPU output candidate

The 2026-10-14 QC device probe decoded the identical H.264 packets to a `SurfaceTexture` and rendered them through a neutral OES/GLES output. Explicit 0° left the person sideways; explicit 90° made the person upright at the correct portrait aspect ratio. This supports a Fork-owned pixel transform for this exact path, without relying on the receiver to rotate `SurfaceView` pixels. The probe used a video-only MP4 remux of the same packets because the platform `MediaExtractor` could not enumerate the original MOV; the production Fork still uses Exo's MOV extractor.

This candidate adds a **separate**, default-off `DefaultRenderersFactory.setEnableAmlogicR34a0AvcRotationOutput(boolean)` switch. The receiver enables it only for a known finite, local/progressive, unprotected video session, and must include the flag in its player reuse identity so a later RTSP or capture session cannot inherit it. The Fork independently gates on API 22, manufacturer `amlogic`, device `r34a0`, selected codec `OMX.amlogic.avc.decoder.awesome`, AVC, a nonzero display-matrix rotation, nonsecure/non-DRM, non-tunneled output, and no caller-supplied video effects. Other paths continue using the previous output behavior. The earlier `UnappliedRotation` switch remains a separate experimental metadata-only contract.

The renderer must decide after the decoder is selected, omit `KEY_ROTATION` for this path, and route the codec output through its existing `VideoFrameProcessorManager` and `library/effect` SurfaceTexture/GLES processor. `Format.rotationDegrees` and the successful QC probe angle are clockwise; `ScaleAndRotateTransformation.setRotationDegrees` is counterclockwise, so this path passes the negative source angle. Output `VideoSize` must have post-rotation dimensions and `unappliedRotationDegrees=0`. The GL processor must report first frame only after a successful `eglSwapBuffers`, on the currently bound display Surface. Codec teardown must precede processor input-Surface release; on a codec replacement, the internal effect and processor are recreated or removed so a 0° item cannot retain the old 90° transform. Seek, surface changes, release, and decoder fallback must not leak the old output path.

This reaches `library/core`, `library/common`, and `library/effect` because codec selection and rendering live in core, the asynchronous processor listener contract lives in common, and the only reliable post-swap signal is inside effect's EGL output. The receiver APK must package the **matching patched `exoplayer-effect` artifact** alongside matching core/common versions; core invokes it reflectively. This adds no effect dependency or GL work to default playback. The QC probe establishes pixel feasibility but is not a production playback acceptance test. Verify actual pixels, first-frame timing, seek, replacement into 0° media, output Surface recreation, audio sync, and absence of regressions in RTSP/SYSTEM/HEVC on device before release.

The first QC Receiver candidate reached `SetURI` prepare but failed before Play with `VideoFrameProcessingException: width -1 must be positive`. The receiver supplies a bare `Surface`, so Exo sends no `MSG_SET_VIDEO_OUTPUT_RESOLUTION`; the GPU processor received `Size.UNKNOWN=(-1,-1)` and tried to create a `Presentation` with that width. The follow-up keeps the existing receiver `setSurface(Surface)` contract. Only when the processor has an unknown output size, `FinalShaderProgramWrapper` queries the actual EGL window width and height. It waits without creating the presentation shader while either dimension is nonpositive or unavailable, and reconfigures on a later resize. A known positive size retains the caller's viewport. Neither source-video dimensions nor a synthetic `1×1` viewport are substituted. The QC renderer reports ready after its first successful output swap in this unknown-size path. This host-side change still needs the same-device playback check, including Surface replacement and resize; the failed candidate is not evidence of successful pixel rotation.

The next QC run confirmed upright, correctly proportioned moving H.264 frames at the 2 s, 5 s, and 9 s captures. An earlier Receiver debug snapshot with a static 57 ms position was not a direct player-clock measurement. A later `COMPLETED` entry followed a `GetPositionInfo` request, which may trigger the Receiver's cached-position completion fallback; it does not independently prove Exo EOS. `VideoSize` remained unknown because `VideoFrameProcessorManager.releaseProcessedFrames` skips size notification when it force-renders the first frame before Play, while its normal branch compares the pending frame timestamp in the wrong direction and against a timestamp with `initialStreamOffsetUs` added. This renderer event defect belongs in `library/core`. The follow-up consumes the pending size change once when a non-dropped processed frame is submitted for output, comparing the pending and released timestamps in the same VideoFrameProcessor time domain. It does not change EOS or frame scheduling, and it does not claim the Receiver has consumed the notification until the next device run.

The next Receiver candidate confirmed upright, correctly proportioned moving H.264 frames and reported `1080×1920`, but its Exo `Player.Listener` reached `READY`/`isPlaying=true` without an `ENDED` event by 35 s for the 11.6 s sample. The Receiver debug position/duration fields are cached session state, not direct Exo clock reads, so their zero values do not diagnose EOS. A subsequent 0° display-matrix control using the same encoded video packets did not activate the GPU rotation path (`1920×1080 / rotation=0`) and also remained `PLAYING` without `ENDED` at 35 s. This rules out the GPU effect chain as the sole cause of the observed noncompletion. `MediaCodecVideoRenderer.isEnded()` does add a processed-last-frame condition when frame processing is enabled, but these runs do not establish whether codec EOS occurred, whether another renderer remained active, or whether the HTTP/container stream signaled end. An experimental QC-only frame-count EOS gate passed host tests but was removed before commit because the 0° control showed the same symptom without the gate. No EOS production change is included in this branch pending a discriminating observation.

### QC HEVC/HLG color boundary (read-only, 2026-10-14)

- The original HEVC Main10/Dolby Vision-compatible source is HLG/BT.2020. The separate QC neutral-OES HEVC probe decoded real pixels with `OMX.amlogic.hevc.decoder.awesome`; its explicit 90° output is upright but looks pale/yellow relative to the iOS-compatible H.264 reference. Its reported decoder output `MediaFormat` has no color-standard/transfer/range keys. Missing output keys do **not** establish that the source or sampled pixels are SDR, nor does a comparison with a separately converted H.264 file establish the precise color conversion fault. The probe is not a production Receiver color acceptance test.
- The Cast-SDK device-side read-only `dumpsys SurfaceFlinger` check reported `GLES: ARM, Mali-450 MP, OpenGL ES 2.0`. Its extensions include `GL_OES_EGL_image_external` but not `GL_EXT_YUV_target`. This is the QC API 22 device used for the portrait probe, not a general claim about all API 22 devices.
- The existing renderer maps HLG input to BT.2020 PQ display output in `experimentalGetVideoFrameProcessorColorConfiguration`. `DefaultVideoFrameProcessor` rejects HDR display output below API 33. Its existing HLG/BT.2020-to-SDR/BT.709 Gamma 2.2 shader path requires OpenGL ES 3 and raw-YUV external-texture sampling through `GL_EXT_YUV_target`. This QC device reports neither prerequisite, so the existing `library/effect` HDR-input path cannot safely perform that tone mapping on it. An OES RGB probe showing upright pixels does not prove it can supply the original HLG YUV or that the vendor has not already applied a conversion.
- Keep the AVC SDR rotation switch restricted to its exact codec/color gate. Do not widen it to HEVC, label this HLG source as SDR to enter the ES 2 shader path, or change SYSTEM/NativeRTSP behavior on the strength of the probe. A future QC HEVC color fix needs a separately scoped, measured output path with verified input pixel/color semantics and HLG-to-SDR conversion; absent those facts, the minimal safe choice is to leave HEVC color behavior unchanged and report the limitation. This document-only finding introduces no shader, global transcode, or production behavior change.

### Subsequent EOS and reuse evidence (Cast-SDK owner, 2026-10-14)

The earlier EOS hypothesis is superseded by a bounded independent Debug probe: video ended,
audio handled EOS with null input/output buffers, and written/head/endHead all equaled 556992,
yet the audio sink still reported pending/not ended. A deterministic test reproduces this after
AudioTrack.stop with nonzero latency. The isolated fix uses the existing simulated playback head
only while stopTimestampUs is valid; normal playback clocks, caps, wrap and underrun logic stay
unchanged. A separate floor-rounding experiment was disproved and removed, not incorporated.

Rotation ownership is retained when onDisabled flushes but keeps the codec, and cleared on actual
codec release. The exact GPU-rotation flush path also re-arms a known output size for its next real
frame and clears the previous stream's PTS gate, because equal-size reuse need not trigger a new
onOutputSizeChanged callback. No decoder/media-type gate is expanded.

QC candidate6 (build 20261014-085725-593c42ed) completed five consecutive same-H.264/AAC SetURI/Play
cycles with new real Exo ENDED events, no GetPositionInfo intervention, and upright moving pixels.
Its missing reused size notification is addressed by the subsequent candidate7; that specific
device check remains separate. The final isolated unit run passed 45 cases (audio tracker 6,
renderer 34, output-size gate 5), core AAR SHA-256
808be72db4062753190c273f06e87fcbb2355cdc4555386936b79913cb3bf910.
Code is still uncommitted on top of 0ed838417d; no formal version or publication is advanced.
Evidence lives in Cast-SDK qa/reports/receiver/functional/20261014-qc-portrait-retest/receiver-candidate/.
Original HEVC/HDR, 5Y hardware, audible output and full surface/performance acceptance remain open.

## Verification and handoff

- Exact source-code candidate: local branch `codex/portrait-hevc-compat-20261013`, commit `c0d7bf127b763c10bee0df79cc068248459087b2`. Its parent is the immutable `labi.35` source commit above. Only `MediaCodecVideoRenderer.java` and `MediaCodecVideoRendererTest.java` are changed in this source commit. It has not been pushed or published.
- `MediaCodecVideoRendererTest`: 26 tests, 0 failures, 0 errors, 0 skipped. The new cases confirm that a native Dolby Vision decoder keeps profile 8, while HEVC and AVC base-layer configurations omit the mismatched Dolby Vision profile. The HEVC case runs at API 19 with `hev1.08.04` and confirms BT.2020/HLG color keys remain present.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:testDebugUnitTest --tests com.google.android.exoplayer2.video.MediaCodecVideoRendererTest`: passed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:assembleRelease`: passed.
- This is a host-side configuration test only. 5Y must verify that `OMX.RTK.video.decoder` now configures and actually decodes the fixed sample; if it still fails, the existing SYSTEM fallback remains the receiver's recovery path. QC must verify actual upright pixels on the same SHA-256 sample under both Exo and SYSTEM routes before any device-scoped rotation rule is adopted. Device runs are coordinated by the Cast-SDK receiver task. No Maven artifact has been published.
# 2026-10-14 API19 AVC 候选补充（未发布）

最新候选 flush2 仅在下述精确 API19 旋转路径关闭迟到时的跳关键帧重建，继续使用现有
逐帧丢弃/强制渲染策略；最后帧不丢弃，明确 seek/disable 仍执行 producer-first 释放。
避免 GL 重建本身再次造成迟到。48 项 Fork 测试通过，Receiver 194 项测试及三个边界检查
通过。5Y 无截图对照已取得真实 ENDED/COMPLETED、Stop 后 GL 线程退出；方向、运动和
五次重播仍单独验收。私有 Maven `2.19.1-labi.35-portrait-api19-flush2`，不修改正式 `.35`。

5Y Konka/rtd294x_tv035/API19 的独立 OES 0°/90°对照已取得真实像素；90°人物正立并
解码到 EOS。复用既有有限视频 rotation output，仅增加精确 OMX.RTK.video.decoder AVC
门禁与通用工厂 opt-in，保持 SDR/非保护/非 tunneling/无 caller effects 限制。
API19 不支持 decoder rotation，不通过修改编码宽高或仅上报角度冒充像素旋转。
Receiver 集成验收尚未完成；独立探针不是生产性能或内存结论。

原始 HEVC configure-only 对照有/无 max-width/max-height 均失败，AVC 均成功且也出现
adaptive 警告；撤销仅凭该警告移除 max 参数的推论。本次不改 HEVC 参数或路由。
QC HEVC/HDR 原片仍未完成方向与色彩验收，不开放 HDR GPU 门禁，不伪造 SDR 元数据。

API19 第一轮 Receiver 候选画面转正但不能推进，未验收。隔离探针确认11.6秒片源的
SurfaceTexture 最终 timestamp 为32629592671微秒，属于设备运行时钟，而非媒体PTS。
新候选只在已开启的API19精确旋转路径逐帧注册codec输出PTS；FrameInfo新增可选媒体
时间覆盖值，默认不变，effect消费同一个已注册FrameInfo。原有API<29单pending帧约束
继续保证对应关系；不引入第二个无界时间戳队列，
不从诊断数据修正时钟，也不将该规则扩展到QC/API22或任何镜像。

PTS候选fresh进程能自然完成，但再次播放出现确定的永久等待：5Y motion-r2的6/12/24秒
及Stop后栈均为`maybeDropBuffersToKeyframe -> DefaultVideoFrameProcessor.flush -> latch.await`。
8/14秒截图字节相同。API19 RTK codec flush会丢弃已注册但尚未交付的Surface帧，不能再
等待其回调以完成processor flush。候选在共同`flushOrReleaseCodec`入口，仅API19且
精确旋转路径已激活时释放codec，随后现有`onCodecReleased`释放processor/隔离旧代回调，
原调用者在需要续播时重建。覆盖关键帧追赶、seek与disable；不更改其它API、媒体、QC、
NativeRTSP或普通效果链。不能以超时忽略flush后继续复用旧Surface作为兜底。
真机复验未通过前保持隔离候选，正式Maven坐标不变。
