# Portrait HEVC / Dolby Vision base-layer compatibility (2026-10-13)

## Baseline and evidence

- This change starts from immutable tag `exoplayer-rtsp-2.19.1-labi.35`, source commit `89b8f82d22ce302819cc604e1959a5ebd610a17c`. The main checkout has unrelated uncommitted audio and RTSP experiments; this candidate is isolated from them.
- The fixed input is `/Users/shenyingjun/Downloads/IMG_3160.MOV` (SHA-256 `efe295f2cffe951dd8534e5580f462343e51b7dceec61e002fd6ef7b4aac54ca`): HEVC Main10, coded 1920×1080, 11.598333 s, Dolby Vision profile 8 / level 4 / compatibility ID 4, HLG/BT.2020, display matrix −90°.
- The older QC log at 17:54 describes a 16.533333 s item. It is not byte-identity evidence for `IMG_3160.MOV`; its reported portrait `VideoSize` also does not prove pixel rotation.
- On 5Y (API 19), Legacy Exo reports `DecoderInitializationException` with `IllegalStateException` for `video/dolby-vision` / `hev1.08.04` using `OMX.RTK.video.decoder`, then the receiver's existing SYSTEM fallback plays. The device codec XML declares `video/hevc` but not `video/dolby-vision`.
- The user has verified that SYSTEM playback has the correct orientation on QC. The device test remains the authority for whether a proposed Exo output fix produces upright pixels.

## Why this change touches library/core

`MediaCodecVideoRenderer.getMediaFormat` configures the actual platform decoder. The input `Format` can retain `video/dolby-vision` while Media3/Exo's existing compatible base-layer selection chooses an HEVC codec with `codecMimeType=video/hevc`. Before this change, the renderer writes the Dolby Vision `KEY_PROFILE` into that HEVC `MediaFormat`. A Dolby Vision profile constant is not an HEVC profile constant and may cause `MediaCodec.configure` to fail. This is a renderer configuration defect, so it cannot be fixed in `library/rtsp`; local HTTP/MP4 playback also reaches it.

The candidate keeps the existing decoder selection, capability result, initialization data, color information, and Android API guards. It writes the Dolby Vision `KEY_PROFILE` only when the selected codec itself is `video/dolby-vision`. It does not claim that a codec supports Main10 or Dolby Vision, does not discard HLG/BT.2020, and does not add any decoder to the available list. A 5Y device run is still required to establish whether omitting the mismatched key lets its HEVC decoder initialize and decode this sample.

## QC rotation boundary and receiver contract

At API >= 21, `codecAppliesRotation()` assumes `MediaCodec` applies `KEY_ROTATION` when rendering to a Surface. `onOutputFormatChanged` swaps width/height for a 90°/270° input and reports unapplied rotation 0. Those values are metadata derived from the assumption; output dimensions cannot verify pixel orientation. The current evidence does not identify an exact, safe device/codec/source quirk that would make the Fork rotate pixels by itself.

The receiver can fix the verified QC case by selecting the already-correct SYSTEM route before Exo prepares the video. That requires no GPU transform and does not change other devices. The QC nickname alone is not a stable runtime key; record `Build.MANUFACTURER`, `Build.MODEL`, firmware/fingerprint, selected codec, source `Format.rotationDegrees`, and a pixel-level SYSTEM/Exo comparison on the fixed SHA-256 sample before shipping the route rule.

If retaining the Exo route is required and the fixed sample still appears sideways, the receiver needs a device-scoped output transform for that confirmed path only:

1. Scope to the verified QC manufacturer/model/firmware and `OMX.amlogic.hevc.decoder.awesome`, and to metadata-rotated HEVC/Dolby Vision-compatible input whose coded 1920×1080 pixels need −90° display rotation. Do not infer this from the delivered `VideoSize` alone.
2. Use an output surface capable of applying a texture transform for that path, with the input display matrix as the source of truth. Preserve aspect ratio and crop, and clear the transform on source replacement, route change, surface recreation, or a zero-rotation item.
3. Establish a single rotation owner. The Fork currently passes `KEY_ROTATION` to the codec on API 22 and assumes it was applied. If device testing proves that this codec ignores it, a codec-scoped Fork quirk should suppress the key and report the source rotation as unapplied so the receiver can transform the pixels exactly once. Do not enable that quirk before pixel verification. If the codec already rotates pixels on a particular format or firmware, disable the output transform. Verify actual pixels against SYSTEM on the same SHA-256 sample, and test 0°, 90°, 180°, 270°, physically portrait frames, and replacement transitions.
4. Do not switch every output to `TextureView` or rotate in CPU memory. The Fork cannot guarantee upright QC output from `VideoSize` or `KEY_ROTATION` alone.

## Verification and handoff

- Exact source-code candidate: local branch `codex/portrait-hevc-compat-20261013`, commit `c0d7bf127b763c10bee0df79cc068248459087b2`. Its parent is the immutable `labi.35` source commit above. Only `MediaCodecVideoRenderer.java` and `MediaCodecVideoRendererTest.java` are changed in this source commit. It has not been pushed or published.
- `MediaCodecVideoRendererTest`: 26 tests, 0 failures, 0 errors, 0 skipped. The new cases confirm that a native Dolby Vision decoder keeps profile 8, while HEVC and AVC base-layer configurations omit the mismatched Dolby Vision profile. The HEVC case runs at API 19 with `hev1.08.04` and confirms BT.2020/HLG color keys remain present.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:testDebugUnitTest --tests com.google.android.exoplayer2.video.MediaCodecVideoRendererTest`: passed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shenyingjun/Library/Android/sdk ./gradlew :library-core:assembleRelease`: passed.
- This is a host-side configuration test only. 5Y must verify that `OMX.RTK.video.decoder` now configures and actually decodes the fixed sample; if it still fails, the existing SYSTEM fallback remains the receiver's recovery path. QC must verify actual upright pixels on the same SHA-256 sample under both Exo and SYSTEM routes before any device-scoped rotation rule is adopted. Device runs are coordinated by the Cast-SDK receiver task. No Maven artifact has been published.
