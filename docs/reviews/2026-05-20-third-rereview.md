# SPECTRA Third Re-Review

Date: 2026-05-20

Scope: Re-review the current workspace after the previous expanded addendum review. This pass checks whether the prior P0/P1 issues were actually fixed in code, not just represented in models.

## Findings

### P1: HDR Metadata Still Assumes Success Instead Of Reporting Actual Captured Frames

The HDR path now builds `CaptureResultMetadata`, which is a good fix direction. But the metadata is synthesized in `CameraViewModel` as `3` or `5` frames based on intended stability, not returned from `captureHdrBracket(...)` or `captureHdrBracket5Frame(...)`.

Evidence:

- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:997`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1009`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1021`

If one or more HDR bracket frames fail and the HDR function still saves a 2-frame merge or fallback frame, the explainer can still say "5-frame HDR" or "3-frame HDR."

Correct fix:

1. Make HDR capture return a `CaptureResultWithMeta`.
2. Populate `actualFrameCount`, `hdrFrameCount`, `didHdr`, and `fallbackReason` inside `CaptureManager`.
3. Only say "HDR" when a meaningful bracket merge actually happened.

### P1: Macro Focus Stacking Captures Multiple Frames But Does Not Sweep Focus

Macro now uses `FocusStacker` when the recipe is `MACRO` and multiple frames are captured. That is progress. But the frame acquisition loop captures repeated stills with a fixed focus state; it does not move focus through near/mid/far planes.

Evidence:

- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:176`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:193`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:197`

This is closer to temporal sharpness stacking than real macro focus stacking. It may help hand motion, but it will not reliably increase depth of field.

Correct fix:

1. Add a macro capture plan that changes lens focus distance per frame through Camera2.
2. Capture near/mid/far frames with enough settling time.
3. Save focus positions in metadata.
4. Fall back to current repeated-frame stacking only when manual focus control is unavailable.

### P1: True Scene Skips Processing But Is Not Truthfully Represented In The Explainer

`saveProcessedCopy(...)` now skips enhancement when `preset == "TRUE_SCENE"`, which is the right trust-mode behavior. But `CaptureExplanation.isTrueScene` only checks `processingBackend == "TRUE_SCENE"`, while `buildCaptureExplanation(...)` always sets `processingBackend = thermalPolicy.processingBackend`.

Evidence:

- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:549`
- `core/src/main/java/com/spectra/core/model/CaptureExplanation.kt:63`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1548`

The overlay also still labels the panel as "WHAT AI DID" and the headline as "AI chose ISO..." regardless of True Scene mode.

Evidence:

- `core/src/main/java/com/spectra/core/model/CaptureExplanation.kt:35`
- `app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt:62`

Correct fix:

1. Add `isTrueScene` or `processingMode` directly to `CaptureExplanation`.
2. In True Scene, use copy like "True Scene captured" and "No AI processing applied."
3. Show a "TRUE SCENE" badge even when no processing stages exist.

### P1: Recipe Capture Is Wired, But It Uses Only Base Frame Count

`captureSmartPhoto(...)` now accepts `recipe`, and that resolves the prior P0 that recipes were only HUD state. However, it currently uses `recipe.baseFrameCount` and ignores the adaptive space between `baseFrameCount` and `maxFrameCount`.

Evidence:

- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:153`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:155`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:173`

Night can define `maxFrameCount = 9`, Auto can define `maxFrameCount = 7`, Food can define `maxFrameCount = 7`, but the capture path does not choose more frames based on stability, scene contrast, thermal state, or motion.

Correct fix:

1. Add `CaptureRecipe.resolveFrameCount(...)`.
2. Inputs should include stability, gyro motion, ISO, lux, thermal state, face motion, and high contrast.
3. Tests should prove Night stable can exceed 5 frames and Auto stable low light can exceed 3 when safe.

### P1: Capability Planning And ZSL Are Still Missing

The code still does not have a `CameraCapabilityMatrix`, a YUV ZSL ring buffer, or reprocess-session planning.

Evidence:

- `camera/src/main/java/com/spectra/camera/LensManager.kt:63`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:355`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:361`

The app still hardcodes capture sizes such as `8160x6120` and `4032x3024` without verifying the active lens output sizes and stream combinations. It also still captures on shutter through `ImageCapture`, not from a timestamped pre-shutter YUV buffer.

Correct fix:

1. Add per-camera output size discovery for JPEG, YUV, RAW, and video.
2. Add stream-combination checks before enabling preview + analysis + still + video + RAW modes.
3. Build a rolling YUV ZSL buffer with `TotalCaptureResult` metadata.
4. Use still capture fallback only when ZSL is unsupported.

### P2: Neural Denoise Can Run, But The User-Facing Metadata Cannot Know It Ran

`saveProcessedCopy(...)` can run neural denoise and logs/stages it locally. But the explainer is built before post-processing starts, and `CaptureResultMetadata.didNeuralDenoise` is never set to `true` anywhere in the capture path.

Evidence:

- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:135`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:578`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:600`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1113`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1139`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1521`

Correct fix:

1. Return post-processing metadata from `saveProcessedCopy`.
2. Merge capture metadata and processing metadata before showing the final explainer.
3. Do not show stages from intent; show stages from completed processing.

## Fixed Or Improved Since The Previous Pass

1. `CaptureRecipe` is now passed into `captureSmartPhoto(...)`.
2. RAW fallback no longer saves a JPEG into `DCIM/Spectra/RAW`; it now uses `DCIM/Spectra/Originals` and labels RAW unavailable.
3. `LANDSCAPE` recipe no longer always enables HDR.
4. Normal capture explanations now use `CaptureResultMetadata` instead of only intended HUD state.
5. Macro mode has a first focus-stack implementation.
6. Neural denoise is wired into post-processing behind thermal policy.
7. True Scene exists and skips post-processing.
8. `./gradlew test` passes.

## Verification

Command:

```bash
./gradlew test
```

Result:

```text
BUILD SUCCESSFUL
```

Notes:

- The first test attempt was blocked by sandbox permissions on the Gradle wrapper cache.
- The second run was executed with permission to access `~/.gradle` and passed.
- There were Kotlin warnings in tests and deprecation warnings for CameraX `setTargetResolution`, but no failing tests.

## Updated Score

Previous expanded re-review score: **6.6 / 10**

Current score: **7.0 / 10**

SPECTRA has crossed an important line: several earlier review items are now real code paths, not just plans. The remaining gap is less about ambition and more about truthfulness and camera-system maturity: actual HDR frame reporting, true focus sweep macro, ZSL, capability planning, and post-process metadata.

## Revised Priority Order

1. Make HDR capture return actual metadata.
2. Make True Scene visible and non-AI in the explainer.
3. Add adaptive recipe frame count selection.
4. Add true macro focus sweep.
5. Return post-processing metadata from `saveProcessedCopy`.
6. Add `CameraCapabilityMatrix`.
7. Add ZSL YUV ring buffer.
8. Replace hardcoded capture sizes with supported-size selection.

