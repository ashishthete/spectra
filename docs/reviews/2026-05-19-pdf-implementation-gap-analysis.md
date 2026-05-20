# SPECTRA PDF Implementation Gap Analysis

**Date:** 2026-05-19  
**Sources checked:**
- `/Users/ashishthete/Downloads/SPECTRA App_ Best-in-Class Feature Enhancement.pdf`
- `/Users/ashishthete/Downloads/Advanced Architectures in Mobile Computational Photography_ Pipelines, Hardware Acceleration, and Algorithmic Frontiers.pdf`

**Goal:** Verify whether the current SPECTRA app incorporates the correct technical recommendations from the PDFs, identify incorrect or partial integrations, and provide an actionable fix plan.

---

## Executive Summary

The app has incorporated many of the correct ideas from the PDFs:

- S24 Ultra lens focal-length bucket fixes
- AE compensation step/range querying
- DRD-style HDR trigger logic
- Mertens-style exposure fusion code
- ML scene classifier asset and NNAPI/GPU/CPU delegate fallback
- Depth estimator asset and guided depth upsampling
- Monk Skin Tone classifier and face-aware EV bias
- RAW_SENSOR DNG capture helper
- AGSL/GLES shader paths for some processing and pro overlays
- Actual sensor metadata display in the HUD

However, several core recommendations are still incomplete or mismatched:

- The real HDR capture path does not use the intended 3-frame `[-2, 0, +2 EV]` bracket. It captures only two exposure-compensation-biased frames.
- The compute pipeline is not the requested Vulkan/NDK zero-copy architecture. It is mostly Kotlin CPU with some AGSL/GLES helpers.
- Depth bokeh is better than the old face ellipse, but it is not a Vulkan scatter-bokeh renderer.
- Skin tone AWB shifts are computed but not fully applied to camera white balance.
- RAW exists, but the app does not yet implement Linear DNG multi-frame RAW or ZSL/reprocessing.
- Multi-frame burst denoising is still global/thumbnail alignment plus averaging, not tile-based HDR+/Night Sight style Wiener merging.

Current implementation is directionally correct, but the app should not be considered fully aligned with the PDFs yet.

---

## Priority Fix List

| Priority | Area | Status | Required Fix |
|---|---|---|---|
| P0 | HDR capture | Incorrect runtime behavior | Replace 2-frame `±EV steps` path with 3-frame `[-2, 0, +2 EV]`, optionally 5-frame when stable |
| P0 | Compute architecture | Partial | Add real Vulkan/NDK or clearly scope current GLES/AGSL path as interim |
| P0 | Multi-frame denoising | Partial | Replace simple average with aligned robust temporal merge |
| P1 | Depth bokeh | Partial | Move guided filter and bokeh renderer to GPU compute, improve real scatter/specular behavior |
| P1 | Skin tone rendering | Partial | Apply MST AWB shifts and subject-weighted exposure consistently |
| P1 | RAW | Partial | Preserve DNG path, add metadata checks, and plan Linear DNG/multi-frame RAW separately |
| P2 | WYSIWYG preview | Partial | Ensure preview and final processing share the same parameterized shader path |
| P2 | ZSL/reprocessing | Missing | Add Camera2 reprocess/ZSL plan or explicitly defer |
| P2 | Thermal/performance | Missing | Add thermal gating and performance budget tests |

---

## 1. HDR Capture Is Not Correct Yet

### PDF Requirement

The feature PDF recommends histogram/DRD-based HDR activation and a Mertens exposure fusion pipeline using:

- 3-frame bracket: `[-2.0 EV, 0 EV, +2.0 EV]`
- 5-frame bracket for stable scenes: `[-3.0, -1.5, 0, +1.5, +3.0 EV]`
- Mertens weights from contrast, saturation, and well-exposedness
- Laplacian pyramid blending
- Ghost/motion handling

The architecture PDF also emphasizes HDR+/Night Sight style multi-frame capture, alignment, and robust merging.

### Current Implementation

Correct lower-level pieces exist:

- `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`
  - `computeBracketExposures()` creates `[-2, 0, +2 EV]` exposure-time brackets.
  - `computeBracketExposures5Frame()` creates a 5-frame bracket.
  - `computeDrd()` and `shouldTriggerHdr()` implement clipped-pixel DRD.
  - `mertensFusionPyramid()` implements contrast/saturation/well-exposedness weights and pyramid fusion.

But the actual user-facing capture path does something different:

- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
  - `capturePhotoInternal()` uses `val evSteps = listOf(-4, 4)`.
  - It captures only two frames.
  - It does not include the base exposure frame.
  - It uses AE compensation steps rather than exposure-time bracket values.

On an S24 Ultra-like `1/10 EV` AE step, `-4` and `+4` means roughly `-0.4 EV` and `+0.4 EV`, not `-2 EV` and `+2 EV`. The inline comment says `±0.67 EV`, which is also device-dependent and probably wrong for 1/10 EV devices.

### Impact

This is the highest-confidence mismatch. The app will show HDR behavior, but it will not recover highlights/shadows at the level promised by the PDFs. A two-frame weak bracket also undermines Mertens fusion because the algorithm needs meaningful exposure diversity.

### Fix

1. Route HDR capture through `CaptureManager.captureHdrBracket()` instead of the custom two-frame ViewModel path.
2. For normal HDR:
   - Use `HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)`.
   - Capture `[-2 EV, 0, +2 EV]`.
3. For stable scenes:
   - Use `computeBracketExposures5Frame()`.
   - Gate this using `levelSensor.angularVelocity` and current motion/face data.
4. Avoid raw AE-compensation step lists for HDR bracketing unless converting EV to device-specific compensation indices using the queried AE step.
5. Always restore the prior exposure mode after bracketing.
6. Add tests that fail if HDR capture uses fewer than 3 frames for active HDR.

### Acceptance Criteria

- HDR active path captures at least three frames.
- Base exposure is included.
- Bracket spacing is approximately `±2 EV` for the 3-frame path.
- Static scenes can choose five frames.
- Tests cover S24-like `1/10 EV` and fallback AE step values.

---

## 2. Hardware Acceleration Is Partial, Not the PDF Architecture

### PDF Requirement

The feature PDF calls for:

- Vulkan compute shaders or optimized NDK for multi-pass image operations
- `AHardwareBuffer` zero-copy sharing between Camera2, NPU, GPU, and encoder
- `VK_ANDROID_external_memory_android_hardware_buffer`
- Explicit image layout transitions
- Sub-second processing targets for NR, HDR fusion, bokeh, LUTs, and guided filtering

### Current Implementation

The app has useful GPU-related work:

- `camera/src/main/java/com/spectra/camera/gpu/ComputeShader.kt` wraps GLES 3.1 compute shaders.
- `camera/src/main/java/com/spectra/camera/gpu/ShaderPipeline.kt` uses AGSL `RuntimeShader` paths.
- `camera/src/main/java/com/spectra/camera/gpu/PreviewEffect.kt` and `ProOverlayShaders.kt` implement preview and pro overlay effects.
- `NoiseReducer` can use `BilateralFilterShader` when a GLES context is available.

But this is not the requested Vulkan/NDK architecture:

- No Vulkan NDK layer is present.
- No `AHardwareBuffer` import/export pipeline is present.
- Most high-quality processing still decodes JPEG/Bitmap into CPU arrays.
- HDR fusion and depth bokeh are still mostly Kotlin loops.

### Impact

The app may pass tests and work functionally, but it will not meet the latency and memory targets in the PDFs, especially for 12MP, 50MP, or 200MP paths.

### Fix

Choose one of two paths:

**Option A: Implement the requested Vulkan foundation**

1. Add a `camera-native` or `camera/src/main/cpp` layer.
2. Add Vulkan initialization and device capability checks.
3. Add `AHardwareBuffer` import support.
4. Port these stages first:
   - Bilateral/guided filtering
   - HDR weight-map generation
   - Laplacian pyramid downsample/upsample
   - Depth bokeh blur/scatter
5. Keep Kotlin CPU paths as fallback.

**Option B: Explicitly scope current GLES/AGSL as Phase 1**

1. Update specs/docs to call it an interim GPU path.
2. Add performance budgets for the existing path.
3. Avoid claiming Vulkan-level performance until the native path exists.

### Acceptance Criteria

- App can report which processing backend is active: CPU, AGSL, GLES compute, or Vulkan.
- Processing tests include backend fallback behavior.
- Profiling covers 12MP still capture on target hardware.
- Vulkan claims are removed from user-facing docs until implemented.

---

## 3. Multi-Frame Noise Reduction Is Too Primitive

### PDF Requirement

The architecture PDF describes HDR+/Night Sight style capture:

- Constant-exposure raw burst
- Reference-frame selection by sharpness
- Coarse-to-fine tile alignment
- Subpixel alignment where safe
- Frequency-domain or Wiener-style robust merging
- Fallback to spatial filtering when alignment confidence is poor

### Current Implementation

`CaptureManager.burstMerge()`:

- Picks a sharpest reference frame.
- Uses thumbnail grayscale global alignment.
- Averages pixels after a global integer shift.
- Does not perform hierarchical tile alignment.
- Does not compute alignment confidence.
- Does not perform Wiener/frequency-domain merging.
- Operates on JPEG/Bitmap data, not RAW_SENSOR burst frames.

### Impact

This helps low-light noise slightly, but it can blur moving subjects and fine detail. It is not comparable to Pixel HDR+ or Night Sight.

### Fix

1. Add tile-based alignment:
   - Work at multiple scales.
   - Use SAD/NCC over tiles.
   - Track per-tile confidence.
2. Merge with confidence weighting:
   - High confidence: temporal average.
   - Low confidence: prefer reference frame or spatial denoise.
3. Add motion masks for faces and subject edges.
4. Keep a memory-bounded path for large captures.
5. Later, add RAW burst support when Camera2 session architecture is ready.

### Acceptance Criteria

- Handheld low-light tests show less blur than simple averaging.
- Moving subject regions preserve the reference frame more often.
- Unit tests cover tile motion, failed alignment, and global motion.

---

## 4. Depth Bokeh Is Improved But Not Best-In-Class Yet

### PDF Requirement

The feature PDF asks for:

- ML or hardware depth map
- Guided-filter depth upsampling
- Circle of Confusion from depth
- Disc-kernel scatter bokeh
- Specular bloom
- Cat-eye distortion
- Chromatic aberration
- Vulkan compute implementation

### Current Implementation

Good pieces exist:

- `camera/src/main/assets/depth_estimator.tflite` exists.
- `DepthEstimator` uses NNAPI, GPU, then CPU fallback.
- `DepthBokeh.upsampleDepthWithGuidedFilter()` implements guided upsampling.
- `DepthBokeh.applyDepthBokeh()` builds a blur pyramid and uses CoC.
- Specular bloom, cat-eye distortion, and chromatic aberration functions exist.

Limitations:

- The renderer is CPU/Kotlin.
- `buildDiscBlurPyramid()` still uses repeated box blur passes, not true scatter bokeh.
- Specular bloom is a local boost, not energy-scattering highlight discs.
- There is no known performance guard for high-megapixel portrait processing beyond general OOM fallbacks.

### Fix

1. Keep the depth estimator and guided filter model flow.
2. Move guided filter coefficient calculation to GPU or native code.
3. Replace box-blur pyramid with a real disc/scatter approximation.
4. Add max-radius and resolution downscale policies for thermal/memory safety.
5. Add visual regression tests with synthetic depth maps:
   - Hair/edge boundary
   - Foreground subject
   - Bright background lights
   - Corner cat-eye deformation

### Acceptance Criteria

- Portrait processing does not create face-edge halos on test masks.
- Bright point highlights visibly expand as discs, not only boosted pixels.
- CPU fallback remains available.

---

## 5. Skin Tone Support Is Present But Not Fully Applied

### PDF Requirement

The feature PDF asks for:

- Monk Skin Tone Scale classification
- Face-aware exposure
- Nuanced AWB shifts for brown/deep-red tones
- Adaptive Well-Exposedness for diverse group shots
- Lab-space beauty processing with texture preservation

### Current Implementation

Correct pieces:

- `SkinToneClassifier` defines MST 1 through 10.
- It uses Lab conversion and CIEDE2000 matching.
- `FrameAnalysisPipeline` extracts cheek regions and stores `skinToneShade`, `skinToneAwbShiftK`, and `skinToneEvComp`.
- `CameraViewModel.computeMstEvBias()` applies EV bias for the primary face.

Gaps:

- `skinToneAwbShiftK` is computed but not applied to camera white balance.
- Group-shot exposure is primary-face based, not weighted across all faces.
- Beauty processing has skin-aware masking, but the full PDF requirement for Lab frequency separation is only partially represented.

### Fix

1. Apply `skinToneAwbShiftK` to the recommended WB before `applySettingsToHardware()`.
2. Aggregate all detected faces:
   - Weight by face area and center proximity.
   - Avoid overcorrecting one face at the expense of another.
3. Add caps:
   - Maximum AWB shift per update.
   - Hysteresis to avoid color flicker.
4. Extend beauty processing:
   - Smooth mainly low-frequency Lab/chroma tone.
   - Preserve high-frequency texture.

### Acceptance Criteria

- Darker MST faces receive exposure protection without washing out highlights.
- AWB shift is visible in applied settings, not only stored in face data.
- Group-shot tests do not overfit to one face.

---

## 6. RAW Support Exists, But Linear DNG/Multi-RAW Does Not

### PDF Requirement

The architecture PDF describes Samsung Expert RAW style output:

- Processed JPEG/HEIF for immediate viewing
- 16-bit Linear DNG
- Multi-frame raw merge
- Metadata-preserving raw workflow

The feature PDF at minimum requires true RAW_SENSOR DNG via `DngCreator`.

### Current Implementation

The app has true RAW_SENSOR capture:

- `RawCaptureHelper.supportsRaw()` checks `REQUEST_AVAILABLE_CAPABILITIES_RAW`.
- `ImageReader` is created with `ImageFormat.RAW_SENSOR`.
- `DngCreator.writeImage()` saves DNG.

This satisfies the first step of true RAW support.

Gaps:

- No Linear DNG multi-frame output.
- RAW capture opens its own Camera2 session separate from the CameraX preview/capture flow.
- No ZSL or reprocess session.
- No DNG compression/adaptive pixel controls.

### Fix

1. Keep `RawCaptureHelper` as the baseline RAW path.
2. Add robust metadata logging:
   - Sensor sensitivity
   - Exposure time
   - Lens shading map availability
   - White balance gains
3. Add UI state for RAW support unavailable or RAW capture failed.
4. Treat Linear DNG/multi-frame RAW as a separate future phase.

### Acceptance Criteria

- RAW toggle saves real `.dng` on RAW-capable cameras.
- RAW fallback JPEG copy is clearly labeled as fallback, not true RAW.
- User can tell when RAW is unsupported.

---

## 7. WYSIWYG Preview Is Only Partially Solved

### PDF Requirement

The feature PDF asks for a unified GPU preview pipeline so final capture matches the viewfinder.

### Current Implementation

The app has:

- AGSL preview effects.
- Pro overlays for focus peaking and zebra.
- HUD actual sensor metadata display.

But final processing still uses multiple CPU and Bitmap paths:

- `ImageEnhancer`
- `NoiseReducer`
- `HdrProcessor`
- `DepthBokeh`
- Style and beauty processing in `CaptureManager`

Preview and capture are not guaranteed to share one parameterized processing graph.

### Fix

1. Define a shared `ProcessingGraph` or `ProcessingParams -> ShaderParams` mapper.
2. Use the same tone, WB, highlight rolloff, and LUT parameters for preview and final capture.
3. Add a debug overlay that reports active preview/capture processing mismatch.
4. Add golden-image tests for parameter parity.

### Acceptance Criteria

- Changing a style/preset changes preview and capture through the same parameter source.
- HDR preview indicator matches DRD-based capture decision.
- No hidden ColorMatrix-only path remains for major styles.

---

## 8. ZSL and Camera2 Reprocessing Are Missing

### PDF Requirement

The architecture PDF describes:

- Zero Shutter Lag circular buffer
- YUV/RAW reprocessing sessions
- `InputConfiguration`
- `createReprocessCaptureRequest(totalCaptureResult)`
- Background processing without preview freezes

### Current Implementation

The app uses CameraX `ImageCapture` for most still captures and a separate Camera2 RAW helper. There is no clear ZSL circular buffer or Camera2 reprocess session.

### Fix

1. Decide whether to stay CameraX-first or introduce a Camera2 capture session manager.
2. If Camera2 is introduced:
   - Query hardware level and stream combinations.
   - Build a ZSL YUV ring buffer first.
   - Add reprocess only on supported devices.
3. Gate by device capability to avoid breaking non-S24 devices.

### Acceptance Criteria

- Shutter lag measurement is logged.
- Devices without reprocess support fall back safely.
- Preview frame drops are measured during capture.

---

## 9. Scene Classification Is Now Structurally Correct

### PDF Requirement

The previous review called for a real TFLite scene classifier instead of heuristic-only detection.

### Current Implementation

The app now includes:

- `ai-engine/src/main/assets/scene_classifier.tflite`
- `scene_labels.txt`
- `SceneClassifier` with NNAPI, GPU, then CPU fallback
- 224x224 inference input
- Heuristic fallback
- Face-aware portrait bias and hysteresis

This is directionally correct.

### Remaining Risk

The code confirms the model is wired, but the model quality cannot be judged from code alone. The asset may be placeholder, undertrained, or label-misaligned unless validated.

### Fix

1. Add model metadata validation.
2. Add test images or synthetic fixtures for label order.
3. Log delegate type and top-3 predictions in debug builds.
4. Add a model card under `ai-engine/src/main/assets/README_MODEL.md`.

### Acceptance Criteria

- Label count equals model output dimension.
- Test fixtures cover portrait, landscape, night, food, document, macro.
- Fallback is used only when model load/inference fails.

---

## 10. Thermal and Performance Controls Are Missing

### PDF Requirement

The feature PDF calls for thermal-aware processing:

- Prefer NPU over GPU for inference
- Process in bursts with idle gaps
- Monitor thermal status
- Reduce processing complexity under thermal pressure
- Use 12MP binned mode for real-time preview processing

### Current Implementation

The app chooses NNAPI first for ML, which is good. But there is no clear `PowerManager.THERMAL_STATUS_*` integration or dynamic processing quality controller.

### Fix

1. Add `ThermalManager` or `PerformanceBudget`.
2. Track:
   - Thermal status
   - Capture resolution
   - Active backend
   - Estimated processing cost
3. Degrade gracefully:
   - Reduce bokeh radius/resolution
   - Disable 5-frame HDR
   - Disable expensive denoise
   - Prefer 3-frame over 5-frame capture

### Acceptance Criteria

- Processing complexity changes when thermal status rises.
- Logs explain why a feature was reduced.
- Tests cover policy decisions without requiring real hardware thermal events.

---

## Recommended Fix Sequence

## Addendum: Missing Items And High-Value Additions From The PDFs

This is the second-pass answer to: "Anything missing from this, or any good additions?" The earlier sections cover the biggest gaps, but the two PDFs imply several extra product and engineering ideas that deserve to be tracked explicitly.

### Highest-Value Missing Items

| Item | Why It Matters | Current State | Correct Fix | Priority |
| --- | --- | --- | --- | --- |
| ZSL as the main capture experience | The user should get the decisive moment they saw, not the moment after shutter lag | Mentioned as missing, but not elevated enough as a user-experience feature | Build a timestamped YUV ring buffer, then select pre-shutter and post-shutter frames for HDR, best-shot, deblur, and blink recovery | P0/P1 |
| Capability and stream planner | Best-in-class camera apps are honest about what the device can do in each lens/mode | Lens metadata is improved, but feature gating is not yet complete | Add a `CameraCapabilityMatrix` that reads hardware level, RAW, YUV/PRIVATE reprocess, max FPS, dynamic range, OIS/EIS, stream combinations, physical camera IDs, lens calibration, and thermal budget | P0 |
| Preset-specific capture recipes | Auto, Portrait, Night, Food, Landscape, Action, and Macro should not all capture the same way | Presets exist, but capture behavior is not yet recipe-driven | Introduce `CaptureRecipe` objects that define frame count, exposure schedule, lens choice, merge method, semantic masks, stabilization, and post-process policy per scene intent | P0/P1 |
| RAW/Bayer-aware pipeline | Real computational photography starts before RGB; naive RGB fusion creates color artifacts and texture loss | RAW DNG exists, but normal processing is mostly bitmap/RGB | Add a linear RAW/YUV pipeline with black-level correction, lens shading, demosaic policy, color matrix handling, Bayer-aware alignment, and neutral tone output | P1 |
| Hybrid 2D/3D denoise and merge | The architecture PDF calls for stronger temporal denoise than simple averaging | Burst denoise is present but basic | Replace simple burst averaging with tile-aligned temporal merge, confidence maps, motion masks, and detail-preserving denoise | P1 |
| NPU-native restoration model | The app should use neural restoration where classical merge fails, especially low light and texture | TFLite acceleration exists for scene/depth, not a restoration pipeline | Add a small quantized denoise/deblur/detail model with NNAPI/GPU fallback, tiled inference, and a non-ML fallback | P1/P2 |
| iCAM06/color-appearance thinking | Local tone mapping can destroy saturation, skin, and highlight realism without color appearance compensation | Tone mapping exists, but color appearance handling is not explicit | Add color appearance compensation after local tone mapping, especially saturation compression, skin hue protection, and highlight rolloff | P1 |
| 24MP fusion / adaptive resolution output | Modern phones often blend high-res detail with binned low-noise capture | Not implemented | Add an optional "Detail+" output that fuses clean 12MP frames with high-res detail where texture confidence is high | P2 |
| Focus stacking for Macro | Macro on phones fails because depth of field is tiny | Macro guidance exists, but no focus-stack pipeline | Add guided micro focus sweep, depth/contrast plane selection, and focus-stack merge for static macro scenes | P2 |
| Authentic / documentary mode | A premium AI camera needs a trust mode where it does less, not more | Not present | Add "True Scene" mode: neutral JPEG, RAW sidecar, no beauty, no semantic replacement, fixed metadata, conservative HDR, and clear processing disclosure | P2 |
| Astro mode | The architecture PDF implies multi-minute tripod capture and star alignment | Not present | Add tripod detection, long capture queue, star alignment, dark-frame subtraction, sky mask, and a simple constellation/framing guide | P2/P3 |
| Food focal ring and table cleanup | Food needs different optics: close distance, specular control, warm light, background suppression | Food can be detected, but capture behavior is not specialized enough | Add plate/food segmentation, highlight protection, warm-cool control, radial focal emphasis, and clutter/background de-emphasis | P2 |
| Hair-type-aware portrait rendering | Portrait quality fails most visibly at hair edges | Depth and bokeh exist, but semantic matting is not strong enough | Add hair/edge segmentation, curl/strand-preserving sharpening, and less aggressive blur near uncertain boundaries | P1/P2 |
| Haptic capture readiness | Beginners need to know when the camera has achieved focus/exposure/stability without reading text | Stabilization waiting exists, but not a full tactile readiness system | Add haptics for "stable", "focus locked", "subject tracked", and "capture now"; avoid constant instructional text | P1 |
| Metadata and processing transparency | AI photos can lose trust if users do not know what changed | Smart review exists, but could be clearer | Store processing badges: HDR frames, lens, shutter, ISO, WB, face retouch level, denoise strength, bokeh, crop, and semantic edits | P1 |

### Correct Architecture To Add

The app should move toward a single capture strategy graph:

```text
Scene + user intent
  -> Capability planner
  -> Capture recipe
  -> Frame acquisition
  -> Alignment + selection
  -> Merge / restore / depth / semantic masks
  -> Color appearance + look
  -> Review transparency
```

The critical change is that "Portrait", "Night", "Food", "Action", and "Macro" should not be UI labels that merely influence post-processing. They should select different physical capture plans.

### Suggested Capture Recipes

| Mode | Correct Capture Behavior | Correct Processing Behavior |
| --- | --- | --- |
| Auto | 3 to 7 ZSL-selected YUV/RAW frames depending on motion and DR | Conservative HDR, face-aware exposure, natural color, minimal visible sharpening |
| Portrait | 3 to 5 frames, short shutter bias, subject-priority AF/AE, depth capture if available | Geometry-aware face rendering, hair-safe bokeh, skin-tone-preserving tone curve |
| Night | Multiple short frames plus optional longer frames only when stable | Motion-segmented merge, highlight protection, warmer practical lights, avoid plastic denoise |
| Food | 5 to 7 frames, 2x/3x preference when possible, plate/food priority | Specular protection, warm controlled WB, texture retention, subtle background de-emphasis |
| Landscape | 5 to 9 frames, low ISO, sky/ground exposure split | Semantic sky/ground tone mapping, foliage texture preservation, natural greens/blues |
| Action | 10 to 30 rolling frames, fast shutter, pre-shutter ZSL | Best-shot selection, face unblur when possible, less HDR if it risks ghosting |
| Macro | Focus distance warning, optional micro focus sweep | Focus stacking, texture-preserving sharpening, neutral color accuracy |
| Video | Stable shutter/exposure continuity, subject tracking, lens transition planning | Smooth AE/WB, audio meters, cinematic stabilization, rack-focus assistance |

### Good Additions Beyond The PDFs

These are not all explicitly required by the PDFs, but they would make SPECTRA feel more premium and more useful for normal users.

1. **Capture Readiness Meter**
   - Quietly evaluates focus confidence, hand shake, subject motion, lighting quality, face exposure, and composition.
   - Shows a tiny confidence state or uses haptics instead of verbose instructions.
   - Correct fix: create a `CaptureReadinessScore` from AF state, gyro motion, face sharpness, exposure clipping, and framing quality.

2. **Mistake Prevention Before Shutter**
   - Warns only for irreversible problems: subject too close, lens dirty, face badly backlit, motion too high, focus missed, or shutter too slow.
   - Correct fix: split AI suggestions into `blocking`, `helpful`, and `silent` categories.

3. **After-Shot Triage**
   - Immediately flags "closed eyes", "motion blur", "missed focus", "bad crop", "harsh flash", or "better frame available".
   - Correct fix: use burst/ZSL frames to recommend or auto-select the better shot.

4. **Lens Choice Coach**
   - Normal users misuse ultra-wide and digital zoom constantly.
   - Correct fix: recommend 2x/3x for portraits and food, 0.6x only for architecture/large landscapes, and avoid digital zoom when a better physical lens is available.

5. **Perspective Guard**
   - Helps users avoid tilted buildings, distorted faces at wide angle, and slanted horizons.
   - Correct fix: combine horizon, vanishing lines, face geometry, and lens focal length into a simple framing warning.

6. **Subject Separation Without Fake Blur**
   - Many "AI camera" apps overuse bokeh.
   - Correct fix: use light direction, contrast, crop, color separation, and focal length recommendations before adding synthetic blur.

7. **Cinematic Video Assistant**
   - Video needs continuity more than single-frame perfection.
   - Correct fix: add AE/WB smoothing, subject lock, rack focus, shutter-angle guidance, exposure zebra, audio level meter, and lens-transition avoidance during recording.

8. **Personal Look Memory**
   - Users secretly love consistency.
   - Correct fix: learn preferred warmth, contrast, skin rendering, and retouch strength per user, but keep the default natural.

9. **"Why This Looks Bad" Review**
   - Beginners do not learn from scores alone.
   - Correct fix: replace abstract quality scores with one concrete cause: "face is backlit", "shutter too slow", "lens too wide", "focus missed", or "background is brighter than subject".

10. **True Scene / No-AI Badge**
    - A trust feature photographers may actually respect.
    - Correct fix: add an output mode that visibly promises no beautification, no sky replacement, no generative edit, and no aggressive local manipulation.

### Items To Defer

Some PDF ideas are good but should not distract from the core app yet.

| Defer Item | Reason |
| --- | --- |
| Full multi-minute astro | High engineering cost and limited daily use |
| Underwater/ocean color mode | Useful but niche unless the product targets travel/action users |
| Full pro RAW editor | The app goal is effortless capture, not Lightroom replacement |
| Complex manual cinema UI | Keep video simple first; expose pro tools only after auto video is excellent |
| Heavy neural super-resolution everywhere | Risk of fake texture and high thermal cost |

### Revised Priority After This Pass

1. Fix actual HDR capture path and preview/final mismatch.
2. Add capability planner and capture recipe layer.
3. Implement ZSL YUV ring buffer before advanced reprocessing.
4. Make presets physically different, not just different looks.
5. Add capture readiness and mistake prevention.
6. Replace burst averaging with aligned temporal merge.
7. Close the skin-tone AWB and portrait hair-edge loops.
8. Add transparent processing badges and True Scene mode.
9. Add video exposure/focus continuity tools.
10. Add advanced modes like 24MP fusion, macro stacking, astro, and food focal ring only after the core pipeline is trusted.

---

### Phase 1: Correctness Before Speed

1. Fix HDR runtime capture to use 3-frame bracket with base exposure.
2. Apply MST AWB shift, not only EV bias.
3. Add tests around HDR frame count and bracket math.
4. Add model metadata validation for scene/depth assets.

### Phase 2: Quality Stabilization

1. Replace burst average with tile-aligned confidence merge.
2. Improve depth bokeh visual quality and add synthetic visual tests.
3. Add preview/capture parameter parity checks.
4. Add RAW error/support UI states.

### Phase 3: Architecture Upgrade

1. Decide Vulkan/NDK vs scoped GLES/AGSL path.
2. Add backend reporting and performance instrumentation.
3. Introduce ZSL/reprocessing only after stream-combination checks.
4. Add thermal quality policy.

---

## Verification Performed

Command run:

```bash
./gradlew test
```

Result:

```text
BUILD SUCCESSFUL
```

Note: Tests were all up to date during this run. This verifies the project currently builds and test tasks pass, but it does not prove the PDF feature behavior is complete. The biggest remaining issues are behavioral and architectural.

---

## Final Assessment

SPECTRA has incorporated the correct feature themes from the PDFs, and several important fixes are genuinely present. The best examples are lens mapping, AE step querying, TFLite scene/depth model wiring, RAW_SENSOR DNG capture, actual sensor HUD values, and initial skin tone analysis.

The main problem is that some of the most important features are currently implemented as names or partial algorithms rather than as the full capture pipeline described in the PDFs. The HDR path is the clearest example: the app contains Mertens and bracket helpers, but the actual capture path still uses a weak two-frame bracket.

Treat the app as a strong Phase 1 implementation, not yet a best-in-class computational photography pipeline.
