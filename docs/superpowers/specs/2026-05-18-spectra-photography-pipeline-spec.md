# SPECTRA Photography Pipeline Overhaul — Design Spec

**Date:** 2026-05-18
**Based on:** Professional Photography Review (4.5/10) + Computational Photography Research
**Goal:** Transform SPECTRA from a UI-first camera demo into a genuinely competitive computational photography app. Target score: 7/10 (P0 fixes) → 8.5/10 (full roadmap).

---

## Problem Statement

The app simulates camera intelligence without actually controlling the camera. The DecisionEngine computes beautiful per-scene settings, the HUD displays them prominently, but in auto mode, only EV compensation reaches the hardware. ISO and shutter speed are cosmetic. This "settings illusion" is the #1 credibility problem.

Beyond exposure control, the post-processing pipeline has fundamental quality gaps: no spatial noise reduction, no HDR exposure bracketing, face-ellipse bokeh (no depth map), ColorMatrix-only color grading, texture-destroying beauty processing, and a heuristic scene classifier with no TFLite model.

---

## Architecture

The overhaul touches all four modules but concentrates on `:camera` (capture pipeline) and `:ai-engine` (scene intelligence). The changes add real computational photography algorithms replacing the current placeholder implementations while maintaining the existing module boundaries and Hilt DI graph.

### Module Impact Summary

| Module | Changes |
|--------|---------|
| `:core` | New models: `DepthMap`, `ToneCurve`, `NoiseProfile`. Consolidate presets from 16 → 8 |
| `:camera` | New: exposure bracketing, bilateral NR, frequency-separation beauty, LUT color grading, luminance-only sharpening, depth-aware bokeh, local tone mapping. Refactor: `ProcessingParams` actually drives processing |
| `:ai-engine` | New: TFLite scene classifier, gyroscope-based motion type, composition analysis. Refactor: HUD shows actual sensor values in auto mode |
| `:app` | New: IMU motion classification display, composition overlay guides. Refactor: preset consolidation UI |

---

## Phase 5A — Foundation Fixes (P0: Ship-Blockers)

### 1. Resolve the Settings Illusion

**Problem:** `DecisionEngine` computes ISO/shutter values displayed on HUD, but `Camera2SettingsApplier.applyAutoWithHints()` only sets EV compensation. Users see "ISO 50 · 1/500s" while the camera shoots ISO 400 · 1/60s.

**Approach: Display actual sensor values in auto mode, apply real values in semi-auto mode.**

In auto/preset modes, the HUD will display the actual sensor metadata that `SpectraCameraController.metadataCallback` already reads from `CaptureResult`. The `SensorMetadata` flow is already populated with real ISO, exposure time, and focus distance — we just need to route it to the HUD instead of the DecisionEngine's recommendations.

For advanced users wanting the AI's recommended values applied, introduce a "Smart Auto" variant that uses `CONTROL_AE_MODE_OFF` + directly set `SENSOR_SENSITIVITY` and `SENSOR_EXPOSURE_TIME` from the DecisionEngine, with a smooth crossfade from the current AE-converged values. This is essentially extending PRO mode's `applyManual()` approach to all presets, gated by a user preference.

**Files affected:**
- `CameraViewModel.kt` — route `sensorMetadata` to HUD display in auto mode instead of `settingsProfile`
- `Camera2SettingsApplier.kt` — new `applySemiAuto()` method that sets `AE_MODE_OFF` + DecisionEngine values with ISO ceiling constraints
- `HudState.kt` — add `displayMode: SettingsDisplayMode` enum (ACTUAL vs RECOMMENDED)
- Settings readout composables — switch data source based on mode

**Key principle:** Never display a value the camera isn't using. In auto mode: show "Auto" or actual sensor readback. In Smart Auto / PRO: show the applied values.

### 2. TFLite Scene Classification Model

**Problem:** The heuristic classifier runs on a 48x48 pixel downsample. It can distinguish NIGHT (dark pixels) and LANDSCAPE (green + sky) but misclassifies food, pets, action, and indoor scenes because these require semantic understanding, not pixel statistics.

**Approach: Bundle a pre-trained MobileNetV3-Small model fine-tuned on Places365 + custom scene categories.**

**Model selection:** MobileNetV3-Large offers the best accuracy for mobile with minimal size/speed penalty:
- Input: 224x224 RGB
- Output: 11 scene classes matching `SceneType` enum
- Model size: ~2.7MB (float16 quantized), ~1.4MB (INT8 quantized)
- Inference: ~3ms on S24 Ultra GPU delegate (Adreno 750)
- Expected accuracy: ~85-92% top-1 on validation set (vs. ~40% for current heuristic)

**Training pipeline:**
1. Start with MobileNetV3-Large pretrained on ImageNet
2. Dataset: Places365-Standard (1.8M images, 365 categories) mapped to our SceneType (e.g., `field`/`mountain`/`coast` → LANDSCAPE, `restaurant`/`food_court` → FOOD, `bridge`/`skyscraper` → ARCHITECTURE)
3. Supplement with Open Images V7 for PET/FOOD/DOCUMENT, manual collection for MACRO and ACTION
4. Transfer learning: freeze all layers except classifier head → 20-30 epochs at LR 1e-4 → unfreeze last 3 blocks → 10 epochs at LR 1e-5
5. Export: `tf.lite.TFLiteConverter` with float16 quantization
6. Training: ~2-4 hours on Google Colab T4 GPU

**Integration:** The existing `SceneClassifier` already has the TFLite interpreter path — `loadModelFile("scene_classifier.tflite")` and `bitmapToByteBuffer()` are implemented. The heuristic becomes the fallback when the model fails to load, exactly as currently structured.

**Files affected:**
- `scene_classifier.tflite` — new asset file (~4MB)
- `scene_labels.txt` — already exists, maps indices to SceneType names
- `SceneClassifier.kt` — minimal changes (already structured for TFLite primary + heuristic fallback)

### 3. HDR with Exposure Bracketing

**Problem:** Current "HDR" is single-capture tone mapping. `stackAndEnhance()` captures multiple frames at the same exposure and averages them — this reduces noise but does NOT increase dynamic range. Real HDR requires frames at different exposures.

**Approach: 3-frame exposure bracket + Mertens exposure fusion.**

**Capture pipeline:**
1. Read current AE-converged values from `SensorMetadata` (actual ISO and exposure time)
2. Switch to `CONTROL_AE_MODE_OFF` momentarily
3. Capture 3 frames: -2EV, 0EV, +2EV (vary shutter speed, keep ISO constant to avoid noise differences)
   - Frame 1: `exposureTime / 4` (underexposed — captures highlights)
   - Frame 2: `exposureTime` (base exposure)
   - Frame 3: `exposureTime * 4` (overexposed — captures shadows)
4. Restore `CONTROL_AE_MODE_ON`

**Frame alignment:** Tile-based block matching (HDR+ approach):
- Divide each frame into 16x16 tiles
- For each tile, search ±4 pixels in the reference frame for best match (SAD metric)
- Apply per-tile motion vector to align frames
- Reject tiles with motion > threshold (ghosting prevention)

**Merge algorithm: Mertens exposure fusion** (Mertens, Kautz, Van Reeth 2007):
- Weight each pixel by three quality measures: contrast (Laplacian), saturation, well-exposedness (Gaussian of distance from 0.5)
- Build Laplacian pyramid per frame, weighted by quality maps
- Collapse pyramid to produce final fused image
- Result: HDR image directly in LDR, no separate tone mapping needed

**Why Mertens over Debevec:** Mertens operates directly on LDR images without needing camera response curve calibration. Simpler pipeline, fewer artifacts, and the output is directly displayable without a separate tone mapping step.

**Performance:** ~150ms for 3-frame 12MP fusion on S24 Ultra GPU. Acceptable for post-capture.

**Files affected:**
- `CaptureManager.kt` — new `captureHdrBracket()` method, new `mertensFusion()` implementation
- `Camera2SettingsApplier.kt` — new `captureExposureBracket()` that temporarily takes manual control
- `SpectraCameraController.kt` — expose `captureBurst` path

### 4. Depth-Map Portrait Bokeh

**Problem:** Current bokeh uses a face-shaped ellipse mask. Hands, shoulders, objects held near the face get blurred. The blur is uniform (no depth falloff) and uses box blur (no bokeh shape).

**Approach: ML monocular depth estimation + Circle of Confusion variable blur.**

**Depth estimation:** The S24 Ultra has no ToF sensor and Samsung doesn't expose depth APIs to third-party apps. Use **MiDaS v2.1 Small** TFLite model for monocular depth estimation:
- Input: 256x256 RGB
- Output: 256x256 relative depth map (0.0 = far, 1.0 = near)
- Model size: ~17MB
- Inference: ~30ms on GPU delegate
- Run once at capture time, not live preview

**Depth-dependent blur radius (Circle of Confusion):**
```
For each pixel:
  depth_diff = abs(pixel_depth - focus_depth)
  blur_radius = max_radius * (depth_diff / max_depth_range)
  blur_radius = clamp(blur_radius, 0, max_blur_radius)
```
Where `focus_depth` = depth at the detected face center, `max_radius` = 15 pixels (tunable per preset).

**Blur implementation:** Two-pass separable Gaussian with per-pixel variable radius. For mobile efficiency:
1. Pre-compute 4 blur levels (radius 0, 5, 10, 15) using iterative box blur
2. For each pixel, lerp between the two nearest blur levels based on its CoC
3. This "discrete blur pyramid" approach is ~3x faster than true per-pixel variable blur

**Edge refinement:** The ML depth map has soft edges at object boundaries. Apply guided filter (He et al. 2013) using the original image as guide to sharpen depth edges. This prevents bokeh halos around the subject.

**Files affected:**
- `depth_estimator.tflite` — new asset file (~17MB)
- New: `camera/DepthEstimator.kt` — TFLite depth model wrapper
- `CaptureManager.kt` — replace `applyPortraitBokeh()` with depth-aware version
- New: `camera/GuidedFilter.kt` — depth edge refinement

---

## Phase 5B — Quality Improvements (P1)

### 5. Spatial Noise Reduction

**Problem:** No single-frame noise reduction exists. Multi-frame averaging only works in low light with static subjects. Every photo has unprocessed sensor noise.

**Approach: Bilateral filter on YCbCr with ISO-adaptive strength.**

**Algorithm:**
1. Convert image to YCbCr color space
2. Estimate noise level from ISO: `sigma = 0.5 + ISO / 400.0` (higher ISO = more noise)
3. Apply bilateral filter to Cb and Cr channels (chroma noise is perceptually worse and cheaper to remove — process at half resolution)
   - Spatial sigma: 5 pixels
   - Range sigma: `sigma * 2` (aggressive chroma denoising)
4. Apply lighter bilateral filter to Y channel (luminance — preserve detail)
   - Spatial sigma: 3 pixels
   - Range sigma: `sigma * 0.8`
5. Convert back to RGB
6. Apply BEFORE sharpening in the pipeline

**Performance:** ~15ms for 12MP on CPU with optimized pixel loop. For the "AI enhanced" path, use the existing multi-frame temporal averaging first, then apply spatial bilateral.

**Files affected:**
- New: `camera/NoiseReducer.kt` — bilateral filter implementation with YCbCr separation
- `CaptureManager.kt` — insert NR step before sharpening in `applyPostProcess()`

### 6. Per-Channel Tone Curves / LUT Color Grading

**Problem:** Photo styles (VIVID, FILM, CINEMATIC, WARM) are linear ColorMatrix transforms. A linear transform can't reproduce the non-linear highlight rolloff, shadow lift, and per-channel response curves that make film simulations look like film.

**Approach: Replace ColorMatrix styles with 1D per-channel tone curves + optional 3D LUT.**

**Tone curve implementation:**
- Define per-channel (R, G, B) tone curves as arrays of 256 values (input → output mapping)
- Apply as a simple lookup: `outputR = curveR[inputR]`
- O(1) per pixel, faster than ColorMatrix

**Style definitions as tone curves:**
- **VIVID:** S-curve on all channels (boost midtone contrast), slight blue boost in shadows, preserve highlight rolloff
- **WARM:** Shift red curve up, blue curve down, gentle S-curve
- **FILM:** Lifted black point (curve starts at ~15, not 0), compressed highlights (curve peaks at ~240, not 255), cross-channel blue-to-green tint in shadows
- **CINEMATIC:** Teal shadows (lift B curve below midpoint, suppress R), orange highlights (lift R curve above midpoint), crushed blacks (all curves start at ~20)

**3D LUT path (recommended for maximum quality):** Bundle `.cube` format LUT files (33x33x33, ~108KB each). Apply using OpenGL ES 3.0 fragment shader with a 3D texture (`GL_TEXTURE_3D`). The shader does a single `texture3D()` lookup with trilinear interpolation — runs in <0.5ms on Adreno 750. This is the standard approach used by Instagram, VSCO, and Halide. Alternative: use GPUImage library's `GPUImageLookupFilter` with a 2D 512x512 LUT texture (8x8 grid of 64x64 tiles) for broader compatibility.

**Creating LUTs:** Apply grading to a Hald CLUT identity image in DaVinci Resolve or RawTherapee, export as `.cube`. Alternatively, define per-channel cubic Bezier spline curves (5-8 control points per channel) and bake to a 3D LUT at init time.

**Files affected:**
- New: `camera/ToneCurveEngine.kt` — per-channel curve application + 3D LUT GPU shader
- New: `camera/assets/*.cube` — 5 LUT files for each style (~500KB total)
- `CaptureManager.kt` — replace `getStyleMatrix()` calls with `ToneCurveEngine.apply()`
- `core/model/PhotoStyle.kt` — each style carries curve data or LUT reference

### 7. Camera Shake vs. Subject Motion Separation

**Problem:** `MotionDetector` computes global frame-to-frame brightness difference. It can't distinguish between the user's hand shaking (camera motion) and a child running (subject motion). These require opposite responses: camera shake needs OIS/faster shutter, subject motion needs burst/tracking AF.

**Approach: Cross-reference gyroscope data with frame-diff analysis.**

**Implementation:**
1. Register `Sensor.TYPE_GYROSCOPE` in `LevelSensor.kt` (alongside existing accelerometer)
2. Compute angular velocity magnitude from gyroscope: `omega = sqrt(gx^2 + gy^2 + gz^2)`
3. Classify motion type:
   - **Camera shake:** `omega > 0.5 rad/s` AND `frameDiff > 0.05` (both gyro and pixels move)
   - **Subject motion:** `omega < 0.2 rad/s` AND `frameDiff > 0.08` (gyro still, pixels change)
   - **Pan motion:** `omega > 0.3 rad/s` AND gyro direction is consistent (same axis for 5+ frames) AND `frameDiff > 0.05`
   - **Static:** `omega < 0.2 rad/s` AND `frameDiff < 0.02`

**Response differentiation:**
- Camera shake → increase shutter speed, suggest stabilization, show "hold steady" coaching
- Subject motion → burst mode, continuous AF, increase shutter speed
- Pan motion → intentional — use slightly slower shutter for background motion blur, tracking AF
- Static → normal processing

**Files affected:**
- `LevelSensor.kt` — add `TYPE_GYROSCOPE` registration, expose `angularVelocity: StateFlow<Float>`
- `MotionDetector.kt` — add `gyroVelocity` parameter, new `MotionType` enum (CAMERA_SHAKE, SUBJECT_MOTION, PAN, STATIC)
- `FrameAnalysisPipeline.kt` — pass gyro data to MotionDetector
- `CoachingEngine.kt` — differentiated coaching per motion type

### 8. Frequency-Separation Beauty Processing

**Problem:** Current beauty mode downscales 12x → upscales (blur) → blends at 28% opacity → adds white brightening overlay. This destroys skin texture, makes skin waxy, and applies uniformly to all skin tones.

**Approach: LAB color space chroma smoothing with skin-tone masking.**

**Algorithm:**
1. **Skin detection mask:** Convert face region to YCbCr. Skin pixels: `77 < Cb < 127 AND 133 < Cr < 173` (validated across skin tones). Morphological open/close to clean mask (remove hair, eyebrows, lips).
2. **LAB conversion:** Convert face region from RGB to LAB color space.
3. **Selective smoothing:** Apply bilateral filter ONLY to A and B channels (color/hue) — NOT to L (lightness/detail). This removes blotchiness and uneven color while preserving all skin texture (pores, fine lines, freckles).
   - Bilateral radius proportional to `beautyLevel`: 3 / 5 / 8 pixels
   - Range sigma: 10 / 15 / 20
4. **Convert back:** LAB → RGB → blend with skin mask
5. **No brightening overlay.** The white overlay is removed entirely. Skin brightness is preserved from the original.

**Why LAB over frequency separation:** LAB chroma smoothing is simpler to implement, equally effective, and naturally handles all skin tones because the L channel (which carries texture detail) is completely untouched. Frequency separation in RGB requires careful Gaussian radius tuning per face size.

**Files affected:**
- `CaptureManager.kt` — replace `applyFaceAwareBeauty()` with LAB-based implementation
- New: `camera/ColorSpaceUtils.kt` — RGB↔LAB and RGB↔YCbCr conversions

### 9. Show Actual Sensor Values in Auto Mode HUD

**Problem:** The settings readout (ISO, shutter, WB) shows the DecisionEngine's *recommended* values in auto mode, while the camera uses its own AE-computed values. This misleads users.

**Approach:** Already covered by Fix #1. In auto mode, display actual `SensorMetadata` values from `CaptureResult`. In PRO mode, display the user's manually set values. In "Smart Auto" (if implemented), display the DecisionEngine values since those ARE being applied.

### 10. Edge-Aware Luminance-Only Sharpening

**Problem:** Current USM sharpens all RGB channels equally, amplifying chromatic noise in flat regions (sky, skin).

**Approach: Sharpen only the luminance channel, with edge masking.**

**Algorithm:**
1. Convert to YCbCr (or compute luminance: `Y = 0.299R + 0.587G + 0.114B`)
2. Apply unsharp mask to Y only: `Y_sharp = Y + strength * (Y - blur(Y))`
3. Edge mask: compute Laplacian magnitude of Y. Suppress sharpening where Laplacian < threshold (flat areas like sky and skin).
4. Adaptive strength per scene type:
   - Portrait: strength 0.2 (softer)
   - Landscape: strength 0.5 (more detail)
   - Macro: strength 0.6 (maximum detail)
   - Night: strength 0.15 (avoid amplifying noise)
5. Reconstruct RGB using sharpened Y + original Cb/Cr

**Files affected:**
- `CaptureManager.kt` — replace `applySharpen()` with luminance-only + edge-aware version

---

## Phase 5C — Intelligence Improvements (P2)

### 11. Composition Analysis

**Problem:** The coaching system has no concept of WHERE the subject is in the frame. It can't give spatial composition guidance.

**Approach: Subject saliency + rule-of-thirds scoring.**

**Implementation:**
1. **Subject position detection:** Use the existing face detection bounds. For non-face scenes, compute a simple saliency map using center-surround contrast at 3 scales on a 64x64 downsample.
2. **Thirds scoring:** Compare subject centroid to the 4 rule-of-thirds intersection points. If the subject is >15% of frame width from the nearest intersection, generate a coaching hint.
3. **Horizon detection:** Use `LevelSensor.rollAngle` — if |roll| > 2°, suggest leveling.
4. **Leading lines (optional):** Hough line detection on edge map. If dominant lines converge toward a vanishing point, score composition higher.

**Coaching outputs:**
- "Move subject slightly left" (with LEFT arrow direction)
- "Level your horizon — tilted 3° right"
- "Subject is centered — try placing them off-center for more dynamic composition"

**Files affected:**
- New: `ai-engine/CompositionAnalyzer.kt` — saliency + thirds scoring
- `CoachingEngine.kt` — new composition-based coaching rules
- `FrameAnalysisPipeline.kt` — run composition analysis, pass to coaching

### 12. Horizon Leveling Coaching

Already covered by Composition Analysis (#11). Use `LevelSensor.rollAngle` data that already exists but isn't used for coaching.

### 13. Consolidate Presets from 16 → 8

**Problem:** SELFIE, GROUP, PORTRAIT, COUPLE, KIDS, PETS, FOOD, PRODUCT, LANDSCAPE, SUNSET, NIGHT, STREET, CINEMATIC, ACTION, MACRO, PRO — 16 presets causes choice paralysis. Many are near-duplicates (SELFIE ≈ PORTRAIT front camera, KIDS ≈ PETS ≈ ACTION fast shutter, COUPLE ≈ PORTRAIT, SUNSET ≈ LANDSCAPE golden hour, STREET ≈ general photo, PRODUCT ≈ MACRO).

**New preset list (8):**

| Preset | Replaces | Key Behavior |
|--------|----------|--------------|
| **AUTO** | (new) | Full auto, AI handles everything. Default mode |
| **PORTRAIT** | SELFIE, PORTRAIT, COUPLE, GROUP | Face detection drives behavior. Front camera = selfie settings. Face count drives group vs. couple vs. single |
| **NIGHT** | NIGHT | Low-light stacking, long exposure |
| **FOOD** | FOOD, PRODUCT | Close-up, warm WB, high saturation |
| **LANDSCAPE** | LANDSCAPE, SUNSET | Golden hour detected automatically from lighting |
| **ACTION** | ACTION, KIDS, PETS | Fast shutter, burst, tracking AF |
| **MACRO** | MACRO | Close focus, high sharpness |
| **PRO** | PRO | Full manual with AI ghost markers |

**Migration:** The `CameraPreset` enum shrinks to 8 entries. `PresetEngine` merges settings — e.g., PORTRAIT uses face count from `FaceData` to internally select single/couple/group behavior.

**Files affected:**
- `core/model/CameraPreset.kt` — reduce to 8 entries
- `PresetEngine.kt` — merge settings logic, use FaceData for portrait sub-modes
- `app/ui/controls/ModeSelector.kt` — update UI

### 14. Mixed Lighting Detection

**Problem:** A room with a window and a tungsten lamp (extremely common) gets a single WB value. The warm and cool regions fight each other.

**Approach: Regional color temperature analysis.**

Divide the frame into a 4x4 grid. Estimate color temperature per region using the existing `estimateColorTemperature()` algorithm. If the variance across regions exceeds 1500K, flag as mixed lighting.

**Response:** Use the color temperature of the region containing the subject (face region, or center if no face). Show coaching hint: "Mixed lighting detected — WB matched to subject."

**Files affected:**
- `LightingAnalyzer.kt` — new `detectMixedLighting()` method
- `LightingCondition.kt` — add `MIXED` condition
- `CoachingEngine.kt` — mixed lighting coaching

### 15. Wire ProcessingParams to Processing + Make Coaching Actionable

**Problem 1:** `PresetEngine` computes detailed `ProcessingParams` (noise reduction 0-100, sharpness 0-100, HDR strength, skin tone processing) per preset, but `CaptureManager.applyPostProcess()` ignores them entirely. Processing is hardcoded.

**Problem 2:** Coaching says "use 3x telephoto" but the user must manually switch. When coaching recommends a specific action the app can take, it should offer a one-tap confirmation to execute it.

**Approach for ProcessingParams:** Pass `ProcessingParams` through the capture pipeline and use them to drive the new noise reduction, sharpening, and tone mapping modules.

**Approach for actionable coaching:** When a `CoachingHint` has a concrete action (lens switch, mode change), add an `action: CoachingAction?` field. The HUD shows the hint with a small "Apply" button. Tapping it executes the action via the ViewModel. Actions: `SWITCH_LENS(lensId)`, `ENABLE_BURST`, `SWITCH_PRESET(preset)`.

**Files affected (coaching):**
- `ai-engine/model/CoachingHint.kt` — add `action: CoachingAction?` field
- New: `ai-engine/model/CoachingAction.kt` — sealed class for actionable hints
- `CoachingEngine.kt` — attach actions to relevant hints
- `app/ui/viewfinder/CoachingOverlay.kt` — render action button

**Mapping:**
- `noiseReduction` (0-100) → bilateral filter sigma: `0.5 + (noiseReduction / 100.0) * 3.0`
- `sharpness` (0-100) → USM strength: `0.1 + (sharpness / 100.0) * 0.6`
- `hdrStrength` (0-100) → exposure fusion weight bias toward extreme brackets
- `contrast` (0-100) → tone curve S-curve steepness
- `saturation` (0-100) → global saturation multiplier: `0.5 + (saturation / 100.0) * 1.0`
- `skinToneProcessing` (0-100) → beauty bilateral radius: `2 + (skinTone / 100.0) * 8`
- `highlightProtection` (0-100) → tone curve highlight shoulder compression
- `shadowRecovery` (0-100) → tone curve shadow lift

**Files affected:**
- `CaptureManager.kt` — `applyPostProcess()` accepts `ProcessingParams`
- `SpectraCameraController.kt` — pass `ProcessingParams` from preset profile to capture
- `CameraViewModel.kt` — wire preset profile's processing params to capture call

### 16. Local Tone Mapping

**Problem:** Current `applyHdrToneMap()` applies a global Reinhard curve uniformly. Professional HDR tools use local tone mapping where each region gets its own curve.

**Approach: Bilateral filter decomposition (Durand & Dorsey).**

**Algorithm:**
1. Compute log-luminance: `L_log = log(0.001 + luminance)`
2. Bilateral filter `L_log` to get base layer (large-scale contrast)
3. Detail layer = `L_log - base`
4. Compress base layer: `base_compressed = base * compression_factor` (0.3-0.7)
5. Reconstruct: `L_output = exp(base_compressed + detail)`
6. Scale RGB by `L_output / luminance`

This preserves local detail while compressing the global dynamic range. Shadows get lifted without washing out highlight detail.

**Performance:** ~20ms at 12MP with approximate bilateral (Porikli recursive). Acceptable for post-capture.

**Files affected:**
- `CaptureManager.kt` — replace `applyHdrToneMap()` with bilateral decomposition version

---

## Phase 5D — Polish (P3)

### 17. Highlight Rolloff Simulation

Soft shoulder on the tone curve that compresses highlights gradually instead of hard-clipping at 255. This gives images a "filmic" quality where bright areas gently compress rather than blowing out.

Implemented as part of the per-channel tone curve system (#6) — each style gets a highlight shoulder defined as a Bezier curve from the midpoint to the max value.

### 18. Motion Type in Coaching

Use the camera-shake vs. subject-motion classification (#7) to differentiate coaching:
- "Your hands are shaky — brace against something" (camera shake)
- "Your subject is moving fast — burst mode activated" (subject motion)
- "Nice panning technique — slower shutter will blur the background" (pan motion)

### 19. "What AI Did" Post-Capture Explainer

After capture, show a brief (2-second) overlay: "AI chose ISO 100 · 1/500s because: bright daylight, fast subject detected." This builds user trust and education.

**Implementation:** The `SceneAnalysis` at capture time is saved. A composable shows a translucent overlay with 2-3 bullet points explaining the key decisions.

### 20. Expose-for-Highlights Strategy

In high-contrast scenes, meter for highlights (expose to keep highlights below 240 in the brightest non-specular region) and recover shadows computationally. This is the professional "expose right" technique adapted for computational photography.

**Implementation:** In the HDR bracketing path (#3), bias the base frame slightly toward underexposure (-0.5 to -1.0 EV). The shadow recovery happens through the exposure fusion merge. In single-frame capture, apply negative EV compensation in high-contrast scenes and boost shadows in post-processing using the local tone mapping (#16).

---

## Algorithm Summary

| Feature | Algorithm | Runtime (12MP, S24) | Asset Size |
|---------|-----------|--------------------:|------------|
| Scene classification | MobileNetV3-Large TFLite (float16) | ~3ms | ~2.7MB |
| Depth estimation | MiDaS v2.1 Small TFLite | ~30ms | ~17MB |
| HDR merge | Mertens exposure fusion (3-frame) | ~150ms | — |
| Frame alignment | Tile-based block matching (16x16) | ~20ms | — |
| Local tone mapping | Bilateral decomposition (Durand) | ~20ms | — |
| Noise reduction | Bilateral filter (YCbCr separated) | ~15ms | — |
| Portrait bokeh | Variable Gaussian (blur pyramid + depth map) | ~40ms | — |
| Beauty processing | LAB chroma bilateral + skin mask | ~5ms/face | — |
| Color grading | 3D LUT GPU shader (trilinear interp) | <0.5ms | ~0.5MB LUTs |
| Sharpening | Luminance-only USM + edge mask | ~8ms | — |
| Motion classification | Gyroscope + frame-diff cross-ref | <1ms | — |
| Composition analysis | Saliency + thirds scoring | ~5ms | — |

**Total new asset size:** ~20.7MB (scene model 2.7MB + depth model 17MB + LUTs 0.5MB + headroom)

---

## Implementation Priority

| Phase | Items | Est. Effort | Impact |
|-------|-------|-------------|--------|
| **5A (P0)** | #1 Settings illusion, #2 TFLite scene model, #3 HDR bracketing, #4 Depth-map bokeh | ~3 weeks | 4.5 → 7.0 |
| **5B (P1)** | #5 Noise reduction, #6 Tone curves, #7 Motion separation, #8 Beauty, #9 HUD values, #10 Sharpening | ~2 weeks | 7.0 → 8.0 |
| **5C (P2)** | #11 Composition, #13 Preset consolidation, #14 Mixed lighting, #15 Wire ProcessingParams, #16 Local tone mapping | ~2 weeks | 8.0 → 8.5 |
| **5D (P3)** | #17 Highlight rolloff, #18 Motion coaching, #19 AI explainer, #20 Expose-for-highlights | ~1 week | Polish |

---

## Testing Strategy

Each processing algorithm should have unit tests with known input images:
- **Noise reduction:** Generate synthetic noisy image, verify output PSNR > input PSNR
- **Tone curves:** Verify identity curve produces identical output, verify S-curve boosts midtone contrast
- **Bilateral filter:** Verify edge preservation (high-frequency detail at edges retained vs. Gaussian)
- **HDR fusion:** Verify merged image has lower contrast ratio than any single bracket
- **Depth estimation:** Verify TFLite model loads and produces 256x256 output
- **Scene classification:** Verify TFLite model loads, classify known test images
- **Composition:** Verify centered subject scores lower than thirds-placed subject

On-device testing protocol for each feature:
1. Capture reference images in controlled conditions
2. Compare A/B: old processing vs. new processing
3. Verify no regression in capture speed (< 500ms total post-processing)
4. Verify memory usage stays under 256MB during processing

---

## Out of Scope

- Real-time live preview bokeh (depth model is capture-time only)
- 3D LUT loading from external files (tone curves first, LUTs later)
- Depth map from hardware (S24 Ultra doesn't expose this API)
- ML-based denoising via NPU (bilateral filter first, ML denoising later)
- Video HDR or video processing pipeline changes
- RAW capture support
