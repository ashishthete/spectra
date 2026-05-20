# SPECTRA Expanded Addendum Re-Review

Date: 2026-05-19

Scope: Re-review the current implementation against the expanded PDF/addendum criteria: ZSL, capability planning, recipe-driven capture, RAW/Bayer processing, temporal merge, trust mode, capture readiness, metadata transparency, Food/Macro/Astro/24MP additions, and video continuity.

## Findings

### P0: `CaptureRecipe` Exists, But The Live Capture Path Does Not Use It

`CaptureRecipe` now models the right concepts: frame counts, preferred lens, minimum shutter, ISO ceiling, HDR, burst merge, bokeh, skin protection, atmosphere preservation, and processing intensity.

Evidence:

- `core/src/main/java/com/spectra/core/model/CaptureRecipe.kt:3`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:423`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1040`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:93`

The issue is that the recipe is stored in HUD state, then normal capture calls `captureSmartPhoto(...)` with only `currentIso`, `processing`, and the HDR flag. `captureSmartPhoto` then chooses frame count from a small ISO/front-camera heuristic:

```text
front camera -> 1 frame
ISO > 1200 -> 3 frames
else -> 1 frame
```

That means Night's 5-9 frames, Food's 7-frame ceiling, Macro's stable burst, Portrait's bokeh intent, Landscape's HDR intent, and Action's fast-shutter intent are not actually driving frame acquisition.

Correct fix:

1. Pass `CaptureRecipe` into `captureSmartPhoto` and HDR capture.
2. Let the recipe select frame count, max frame count, burst merge, HDR bracket, depth bokeh, ISO ceiling, and lens preference.
3. Add tests that fail if a Night recipe still captures only one frame or if Macro stable capture does not attempt a focus/merge path.

### P0: HDR/AI Transparency Can Claim Work That Did Not Actually Happen

The app builds `CaptureExplanation` before capture finishes and uses `state.isHdrActive` to populate `hdr_bracket`, `isHdrApplied`, and the expected frame count.

Evidence:

- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:940`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1031`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1097`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1403`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1434`

If HDR bracket capture fails, the code falls back to normal capture, but the later processed-copy path still passes `state.isHdrActive`. The AI explainer can therefore say HDR was applied even when the actual saved original came from the fallback path.

Correct fix:

1. Build the explanation from actual capture results, not intended state.
2. Return a `CaptureResultMetadata` from `captureHdrBracket`, `captureSmartPhoto`, and post-processing.
3. Include actual frame count, actual stages, actual fallback reason, and actual URI type.

### P1: There Is Still No Real ZSL Ring Buffer

`FrameProvider` keeps only `latestFrame` from `ImageAnalysis`, and the capture path still requests still images at shutter time through `ImageCapture`.

Evidence:

- `camera/src/main/java/com/spectra/camera/FrameProvider.kt:12`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:359`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1040`

This is not ZSL. It does not keep timestamped YUV frames with capture metadata, AE/AWB/AF state, lens state, and motion state. It also cannot reliably choose pre-shutter frames for blink recovery, face unblur, HDR+, or best-shot selection.

Correct fix:

1. Add a `ZslFrameBuffer` with timestamped YUV frames plus `TotalCaptureResult` metadata.
2. Keep a small rolling window, for example 8-15 frames.
3. On shutter, select frames around the press timestamp based on motion, exposure, face state, and sharpness.
4. Use `ImageCapture` only as fallback when ZSL stream combinations are unsupported.

### P1: Capability Planning Is Still Too Thin For The Expanded Goals

`LensManager` reads focal length, JPEG size, aperture, AE step, ISO range, and exposure range. That is useful, but it is not the capability matrix described by the addendum.

Evidence:

- `camera/src/main/java/com/spectra/camera/LensManager.kt:26`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:355`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:359`

The code still hardcodes 50MP as `8160x6120` and 12MP as `4032x3024`, without checking output sizes per active camera, stream combinations, YUV/PRIVATE reprocessing, high-speed video, dynamic range profiles, RAW combinations, or physical camera IDs. This can fail or silently degrade on non-target devices and even on some S24 lens combinations.

Correct fix:

1. Add `CameraCapabilityMatrix`.
2. Read supported JPEG/YUV/RAW sizes per physical camera.
3. Check stream combinations before enabling RAW+JPEG, preview+analysis+video, ZSL, 50MP, or HDR.
4. Expose feature gates to UI and recipe selection.

### P1: RAW Fallback Is Still Misleading

When true RAW is supported, `captureDng()` saves a DNG. But if RAW is requested and unsupported, the fallback saves a JPEG into the RAW folder with a RAW name and EXIF description.

Evidence:

- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1054`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1059`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:990`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:995`
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt:1017`

This breaks trust. A JPEG fallback can be useful, but it must not be represented as RAW.

Correct fix:

1. Rename fallback to "Original JPEG fallback" or "RAW unavailable".
2. Store it outside `DCIM/Spectra/RAW`, or mark it explicitly as JPEG fallback.
3. Add UI state that says RAW is unsupported on this lens/session.

### P1: `LANDSCAPE` Recipe Always Enables HDR

`CaptureRecipe` has:

```kotlin
useHdrBracket = highContrast || true
```

Evidence:

- `core/src/main/java/com/spectra/core/model/CaptureRecipe.kt:102`

This means Landscape always requests HDR regardless of contrast, motion, thermal state, or stream support. Since the recipe is not yet used by capture, the bug is mostly latent, but it will become visible as soon as recipe-driven capture is wired.

Correct fix:

1. Replace with `highContrast && isStable`, or a policy that also checks thermal/capability state.
2. Add a test for low-contrast landscape.

### P2: Focus Stacking And Neural Denoising Exist But Are Not Integrated

The repository now includes `FocusStacker` and `NeuralDenoiser`, with tests. But the only production references found were their class definitions, not live capture usage.

Evidence:

- `camera/src/main/java/com/spectra/camera/FocusStacker.kt:3`
- `camera/src/main/java/com/spectra/camera/NeuralDenoiser.kt:12`
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1040`

This means Macro and low-light restoration are still mostly classical capture/post-processing paths. The classes are promising, but not yet product behavior.

Correct fix:

1. Wire `FocusStacker` into `CameraPreset.MACRO` when stable.
2. Wire `NeuralDenoiser` behind `ThermalPolicy.enableExpensiveDenoise`.
3. Keep a deterministic CPU fallback for unsupported devices.

### P2: True Scene / Authentic Mode Is Still Missing

The app has processing transparency and original/enhanced save options, but there is no explicit no-AI/no-beauty/no-semantic-edit capture contract.

Correct fix:

1. Add a `CameraPreset.TRUE_SCENE` or `PhotoStyle.AUTHENTIC`.
2. Disable beauty, bokeh, semantic edits, aggressive local tone mapping, and neural restoration.
3. Save neutral JPEG plus optional DNG.
4. Show a visible "True Scene" badge in review metadata.

### P2: Video Intelligence Is Still Mostly Coaching, Not Capture Control

Video recording exists, and coaching can run during recording. But the expanded criteria need AE/WB smoothing, subject lock, rack focus, audio metering, shutter-angle guidance, and lens-transition planning.

Evidence:

- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt:1197`
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt:461`

Correct fix:

1. Add video-specific capture state separate from still capture recipes.
2. Smooth exposure and WB changes during recording.
3. Add subject lock and rack-focus handoff using `RackFocusEngine`.
4. Add audio level metering and recording diagnostics.

## Positive Changes Since The Previous Review

These are genuine improvements and should stay:

1. HDR capture is no longer just a two-frame weak bracket. It now routes to 3-frame or 5-frame bracket paths depending on stability and thermal state.
2. `CaptureRecipe` is a good domain model and matches the PDF direction.
3. Burst merge is better than before: it now uses tile alignment and confidence, not only naive averaging.
4. Thermal policy exists and affects HDR frame count, bokeh radius, backend reporting, and expensive denoise policy.
5. AI transparency has a real data model and UI overlay.
6. Haptics exist for capture and burst feedback.
7. RAW DNG capture still exists, and RAW support is checked before the main DNG path.
8. Focus stacking, neural denoise, tone appearance, sky segmentation, rack focus, and depth tools exist as building blocks.

## Updated Score

Previous review score: **5.8 / 10**

Updated score after this pass: **6.6 / 10**

The project has moved from "feature-themed prototype with serious gaps" to "promising computational camera scaffold with several real capture improvements." It is still not world-class because too many high-level concepts are not yet the source of truth for the capture graph.

## Revised Priority Order

1. Make `CaptureRecipe` drive actual capture.
2. Return actual capture metadata and rebuild AI explanations from facts.
3. Add a real ZSL YUV ring buffer.
4. Add `CameraCapabilityMatrix` and gate features by stream support.
5. Fix RAW fallback labeling.
6. Wire Macro focus stacking and thermal-gated neural denoise.
7. Add True Scene / Authentic mode.
8. Expand video from coaching to real capture continuity.

## Bottom Line

The app is materially better than the earlier review suggested, because several addendum ideas now exist in code. But the main critique remains: SPECTRA needs a single truthful capture graph where intent, capability, recipe, frame acquisition, processing, and explanation all agree.

