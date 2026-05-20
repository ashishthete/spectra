# SPECTRA Pipeline V2: Best-in-Class Feature Enhancement Spec

**Date:** 2026-05-19
**Goal:** Transform SPECTRA from 4.5/10 to 8.5+/10 against iPhone Camera, Google Pixel, Samsung Expert RAW, Halide, and Blackmagic Camera.
**Target Hardware:** Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750 GPU, Hexagon v75 NPU)

---

## Table of Contents

1. [Hardware-Accelerated Compute Foundation](#1-hardware-accelerated-compute-foundation)
2. [Lens Orchestration & Optical Fixes](#2-lens-orchestration--optical-fixes)
3. [Settings Illusion Resolution](#3-settings-illusion-resolution)
4. [HDR: Mertens Fusion with Laplacian Pyramids](#4-hdr-mertens-fusion-with-laplacian-pyramids)
5. [Multi-Frame Noise Reduction](#5-multi-frame-noise-reduction)
6. [Depth-Map Portrait Bokeh](#6-depth-map-portrait-bokeh)
7. [TFLite Scene Classification](#7-tflite-scene-classification)
8. [Inclusive Color Science & Skin Tones](#8-inclusive-color-science--skin-tones)
9. [WYSIWYG Preview Pipeline](#9-wysiwyg-preview-pipeline)
10. [Pro-Grade Utilities](#10-pro-grade-utilities)
11. [Priority & Performance Budget](#11-priority--performance-budget)

---

## 1. Hardware-Accelerated Compute Foundation

### Problem

All pixel processing in SPECTRA is pure Kotlin/JVM iteration. A 12MP image has 12 million pixels; bilateral noise reduction alone takes 2.5 seconds. The 200MP sensor mode produces 800MB RGBA buffers that cause OOM. This is the root cause of every performance complaint.

### S24 Ultra Hardware Available

| Component | Spec | Relevant Capability |
|-----------|------|-------------------|
| GPU | Adreno 750 | Vulkan 1.3 compute, OpenCL 3.0, FP16 packed math (2x throughput) |
| NPU | Hexagon v75 | ~45 TOPS INT4, ~22 TOPS INT8, NNAPI + SNPE/QNN |
| CPU | Kryo (Cortex-X4) | 3.39 GHz peak, NEON SIMD |
| RAM | 12 GB LPDDR5X | ~5-7 GB available for apps |
| Thermal | 1.9x vapor chamber | GPU throttles from 903 MHz to 366 MHz under sustained load |

### Migration Strategy

**Tier 1 — Per-pixel ops (WB, tone curves, USM, color grading, LUT):**
Use **AGSL RuntimeShader** (Android 13+). GLSL-like syntax, integrates with Canvas/RenderEffect, runs on GPU automatically.

```glsl
// Example: ACES tone mapping in AGSL
uniform shader inputImage;
uniform float exposure;
half4 main(float2 coord) {
    half4 c = inputImage.eval(coord);
    half3 x = c.rgb * half3(exposure);
    half3 a = x * (2.51 * x + 0.03);
    half3 b = x * (2.43 * x + 0.59) + 0.14;
    return half4(clamp(a / b, half3(0), half3(1)), c.a);
}
```

**Tier 2 — Multi-pass compute (HDR fusion, NR, bokeh, guided filter):**
Use **Vulkan compute shaders** via NDK. Zero-copy pipeline with `AHardwareBuffer` + `VK_ANDROID_external_memory_android_hardware_buffer`.

Pipeline architecture:
```
Camera2 (YUV_420_888) → AHardwareBuffer (zero-copy)
    → Vulkan: YUV→RGB + WB gains
    → Vulkan: Noise reduction (bilateral)
    → Vulkan: Tone mapping (guided filter + ACES)
    → Vulkan: Sharpening (USM)
    → Vulkan: Color grading (3D LUT)
    → Encode JPEG/HEIF
```

**Tier 3 — ML inference (scene detection, depth estimation):**
Use **TFLite with NNAPI delegate** (auto-routes INT8 ops to Hexagon NPU). Fallback: GPU delegate.

### Vulkan Shader Synchronization

Layout transitions for pipeline stages:
1. **Ingestion**: `VK_IMAGE_LAYOUT_UNDEFINED` → `VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL`
2. **Processing**: → `VK_IMAGE_LAYOUT_GENERAL` for compute shader read/write
3. **Output**: → `VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL` for readback

Workgroup size: 16x16 (256 threads) — optimal for Adreno 750 cache utilization.
Use FP16 for all image processing kernels (2x throughput on Adreno).

### Projected Speedups

| Pipeline Stage | Current (CPU) | Vulkan/NDK Target | Speedup |
|---------------|--------------|-------------------|---------|
| Bilateral NR | 2.5s (12MP) | < 45ms | 55x |
| Laplacian Sharpening | 400ms | < 15ms | 26x |
| Mertens HDR Fusion | N/A (unused) | < 120ms | New |
| Disc-Kernel Bokeh | 3.2s (12MP) | < 100ms | 32x |
| 3D LUT Color Grading | 150ms | < 5ms | 30x |
| Guided Filter Tone Map | 800ms | < 20ms | 40x |

### Current Files to Modify

- `camera/ImageEnhancer.kt` — guided filter, tone mapping → Vulkan compute
- `camera/NoiseReducer.kt` — bilateral filter → Vulkan compute
- `camera/HdrProcessor.kt` — Mertens fusion → Vulkan compute
- `camera/DepthBokeh.kt` — blur pyramid → Vulkan compute
- `camera/ToneCurveEngine.kt` — LUT apply → AGSL RuntimeShader
- `camera/CaptureManager.kt` — pipeline orchestration → AHardwareBuffer flow

### Thermal Mitigation

- Prefer NPU over GPU for inference (40% better perf/watt)
- Process frames in bursts with idle gaps, not continuous max load
- Monitor `PowerManager.THERMAL_STATUS_*` to dynamically reduce pipeline complexity
- Use 12MP binned mode for all real-time preview processing

---

## 2. Lens Orchestration & Optical Fixes

### Problem: Broken 3X Telephoto Mapping

`LensManager.matchFocalToLens()` uses hardcoded thresholds that fail on S24 Ultra. The 3X telephoto (physical FL ~10mm) and 5X periscope (~18.6mm) can both land in the wrong bucket.

**S24 Ultra Physical Focal Lengths (Camera2 API reports these):**

| Lens | Equivalent FL | Physical FL | Sensor Size | Aperture |
|------|-------------|------------|-------------|----------|
| Ultra Wide | 13mm | 2.22mm | 1/2.55" | f/2.2 |
| Main (Wide) | 23mm | 6.30mm | 1/1.3" | f/1.7 |
| 3X Telephoto | 67mm | 10.00mm | 1/3.52" | f/2.4 |
| 5X Periscope | 111-115mm | 18.60mm | 1/2.52" | f/3.4 |

### Fix: Corrected Thresholds

**File:** `camera/LensManager.kt:69-75`

Current broken logic:
```kotlin
focalLength < 3f  → ULTRAWIDE
3f..7f            → MAIN
7f..12f           → TELEPHOTO_3X   // 10mm lands here BUT barely
> 12f             → TELEPHOTO_5X   // 18.6mm correctly lands here
```

Corrected with wider bands:
```kotlin
LensCategory = {
    ULTRA_WIDE  if FL < 3.0mm
    WIDE        if 3.0mm <= FL < 8.0mm
    TELE_3X     if 8.0mm <= FL < 15.0mm
    TELE_5X     if FL >= 15.0mm
}
```

### Fix: Query Actual AE Compensation Step

**File:** `camera/Camera2SettingsApplier.kt:101`

Current code assumes 1/6 EV steps (`evSteps = (totalEv * 6).toInt()`). S24 Ultra uses **1/10 EV steps**.

Fix: Query `CONTROL_AE_COMPENSATION_STEP` from `CameraCharacteristics`:
```kotlin
val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
// step is a Rational, e.g., 1/10 on S24 Ultra
val stepsPerEv = 1.0 / step.toDouble()  // = 10.0
val evSteps = (totalEv * stepsPerEv).toInt()
```

Also query `CONTROL_AE_COMPENSATION_RANGE` for clamping (S24: -30 to +30 = -3.0 to +3.0 EV).

### Intelligent Lens Switching (Exposure Triangle Aware)

Professional cameras account for each sensor's light-gathering ability. The f/1.7 main sensor captures **4x more light** than the f/3.4 5X telephoto.

In low light, stay on the main sensor and use digital crop (3X crop from 200MP binned gives clean 12MP) rather than switching to the noisier 3X telephoto hardware:

```
SwitchToTele = (Zoom >= 3.0) AND (Lux > MinTeleLux)
```

Where `MinTeleLux` is estimated from `actualIso` and `actualShutterSpeedNs`:
- If ISO > 800 or shutter > 1/30s → stay on main sensor with crop
- If ISO <= 400 and shutter <= 1/125s → safe to switch to telephoto hardware

---

## 3. Settings Illusion Resolution

### Problem

The `DecisionEngine` computes per-scene settings (ISO 50, 1/500s for landscape daylight). The HUD displays them. But in auto mode, `Camera2SettingsApplier.applyAutoWithHints()` only sends EV compensation and WB — **the displayed ISO/shutter never reach the sensor**.

A photographer seeing "ISO 50 · 1/500s" believes the app set those values. It didn't.

### Fix: Display Actual Sensor Values in Auto Mode

**File:** `core/model/HudState.kt` already has `actualIso`, `actualShutterSpeedNs`, `actualFocusDistance`, `actualColorTemperature`.

**File:** `core/model/HudState.kt:72` has `settingsDisplayMode: SettingsDisplayMode = SettingsDisplayMode.ACTUAL`.

The fix is in the ViewModel and HUD rendering:
1. In auto/semi-auto modes, the HUD readouts must display `actualIso` and formatted `actualShutterSpeedNs` — never `settings.iso`
2. Show AI recommendations separately as suggestions (e.g., "AI suggests: ISO 50 · 1/500s") with a button to apply them (switching to semi-auto)
3. In PRO mode, display the user-set values since they directly control the sensor

### What to Show Where

| Mode | ISO/Shutter Display | WB Display | EV Display |
|------|-------------------|------------|------------|
| AUTO | Actual sensor metadata | Actual CCT | Actual EV comp |
| Preset (PORTRAIT, etc.) | Actual sensor metadata | AI-recommended (applied if drift > 500K) | AI EV bias |
| PRO | User-set values | User-set Kelvin | User-set EV |

### Raise ISO Ceiling

**File:** `core/model/CameraSettings.kt:32` — `iso.coerceIn(50, 3200)`.

S24 Ultra Camera2 API exposes up to ISO 3200 in Pro Mode, but the hardware sensor supports higher. Query `SENSOR_INFO_SENSITIVITY_RANGE` from CameraCharacteristics. If the hardware supports ISO 6400+, raise the clamp accordingly. For night mode specifically, allow up to ISO 12800 (sensor will be noisier, but multi-frame stacking compensates).

---

## 4. HDR: Mertens Fusion with Laplacian Pyramids

### Problem

Current HDR is a 2-frame luminance-weighted average (`CaptureManager.kt:948-1014`). This loses highlight detail and produces flat results. The full Mertens fusion in `HdrProcessor.kt` exists but was disabled for being too slow (24 seconds in pure Kotlin).

### Algorithm: Mertens Exposure Fusion

For N bracketed frames, compute per-pixel quality weights:

**1. Contrast (C):** Absolute Laplacian filter response on grayscale:
```
C_k(x,y) = |I_k * K_Laplacian|
```

**2. Saturation (S):** Standard deviation across RGB channels:
```
S_k(x,y) = sqrt((1/3) * sum_c((I_k,c - mu_k)^2))
```

**3. Well-Exposedness (E):** Gaussian weighting toward mid-tone (0.5):
```
E_k(x,y) = exp(-(I_k - 0.5)^2 / (2 * sigma^2))    // sigma = 0.2
```

**Combined weight:**
```
W_k = C_k^wc * S_k^ws * E_k^we    // wc = ws = we = 1.0
```

After normalization, blend using Laplacian pyramid of images weighted by Gaussian pyramid of weight maps. 4-5 pyramid levels.

### HDR Activation: Dynamic Range Deficiency (DRD)

Replace the current `analyzeSceneContrast()` heuristic with histogram-based DRD:

```
DRD = ClippedHighlights/TotalPixels + ClippedShadows/TotalPixels
```

- `ClippedHighlights` = pixels with luminance > 250
- `ClippedShadows` = pixels with luminance < 5

If **DRD > 0.05** (5% of image lost to clipping), trigger 3-frame bracket at **+/- 2.0 EV**.
For static scenes (gyroscope confirms stability), expand to 5 frames for 14+ stops of DR.

### Bracket Strategy

```
3-frame bracket: [-2.0 EV, 0 EV, +2.0 EV]
5-frame bracket: [-3.0 EV, -1.5 EV, 0 EV, +1.5 EV, +3.0 EV]
```

Use EV compensation (`camera.cameraControl.setExposureCompensationIndex()`) since it works in auto mode. Convert EV to compensation steps using the queried step size (1/10 EV on S24 = 20 steps for 2.0 EV).

### Ghost Detection

Use Median Threshold Bitmaps (MTB) — already implemented in `HdrProcessor.kt:90-122`. XOR reference MTB with each alternate frame; disagreement regions are ghosted. In ghosted pixels, use only the base frame.

### Implementation Target

- Process at half resolution for weight maps (6MP), upsample with guided filter
- Vulkan compute for pyramid construction, weight computation, and blending
- Target: **< 120ms** for 3-frame fusion at 12MP on Adreno 750

### Files to Modify

- `camera/HdrProcessor.kt` — replace `mertensFusion()` with GPU-accelerated version
- `camera/CaptureManager.kt:948-1014` — replace `mergeHdrFrames()` luminance-weighted blend with Mertens fusion call
- `app/viewmodel/CameraViewModel.kt` — HDR bracket logic, DRD-based activation

---

## 5. Multi-Frame Noise Reduction

### Problem

`NoiseReducer.kt` uses O(r^2) bilateral filter in pure Kotlin. For high-ISO images (> 2MP), it falls back to downsampled box blur with upscale blend — destroying detail.

### Algorithm: Temporal Stacking + Spatial Bilateral

**Multi-frame path (burst of 3-5 frames, ISO > 800):**

1. **Alignment:** Tile-based SAD matching at 1/8 resolution (already implemented in `CaptureManager.burstMerge()`). Upgrade to hierarchical pyramid alignment for sub-pixel accuracy.

2. **Robust temporal merge:** Per-pixel Wiener shrinkage in frequency domain (Google HDR+ approach):
```
merged(f) = ref(f) + sum_k(w_k(f) * (alt_k(f) - ref(f)))
where w_k(f) = |S(f)|^2 / (|S(f)|^2 + |N(f)|^2)
```
This simultaneously denoises and preserves detail by merging signal and rejecting noise per frequency.

3. **Motion rejection:** Pixels where `|alt - ref| > motionThreshold` get zero weight (already in `CaptureManager.kt:538` with `motionThreshold = 30`).

**Single-frame path (ISO > 400, no burst available):**

GPU-accelerated bilateral filter in YCbCr space:
- Luma: bilateral with `spatialRadius = 3-5`, `rangeSigma` adaptive to ISO
- Chroma: bilateral with `spatialRadius = chromaRadius + 2`, `rangeSigma * 2.5` (chroma noise is worse, can blur more aggressively)

### ISO-Adaptive Parameters

| ISO Range | Spatial Radius | Range Sigma | Chroma Sigma | Burst Frames |
|-----------|---------------|-------------|-------------|-------------|
| < 400 | Skip NR | - | - | 1 |
| 400-800 | 3 | 1.5 | 3.75 | 1-2 |
| 800-1600 | 4 | 2.0 | 5.0 | 3 |
| 1600-3200 | 5 | 3.0 | 7.5 | 4-5 |
| > 3200 | 5 | 4.0 | 10.0 | 5 |

### Performance Target

- Bilateral filter on GPU: **< 45ms** at 12MP (55x improvement over current 2.5s)
- Burst merge (5 frames): **< 200ms** total including alignment

### Files to Modify

- `camera/NoiseReducer.kt` — GPU-accelerated bilateral, remove downsampled fallback
- `camera/CaptureManager.kt:143-226` — improve `burstMerge()` with hierarchical alignment and Wiener merge

---

## 6. Depth-Map Portrait Bokeh

### Problem

Current bokeh uses box blur pyramid (`DepthBokeh.kt`) — produces flat, "Instagram 2012" look. No specular highlights, no disc shape, haloed edges from naive depth map upsampling.

### Algorithm: Three-Stage Bokeh Pipeline

**Stage 1: Depth Estimation**

Use **MiDaS v2.1 small** TFLite model (already wired in `DepthEstimator.kt`):
- Input: 256x256 RGB float32
- Output: 256x256 inverse depth (disparity)
- Normalize to [0,1]: `depth_norm = (depth - min) / (max - min)`
- Inference: ~33-50ms on Hexagon NPU via NNAPI delegate

For better quality, consider **Depth Anything V2 Small** (available on Qualcomm AI Hub, optimized for Snapdragon NPU, superior edge quality).

**Stage 2: Guided Filter Depth Upsampling**

The 256x256 depth map applied to a 12MP image causes haloed edges. Use the guided filter with the full-resolution luminance image as guide:

```
Q_i = a_j * I_i + b_j
where:
  a_j = cov(I, D) / (var(I) + epsilon)
  b_j = mean(D) - a_j * mean(I)
```

`epsilon = 0.01` for tight edge alignment. Use **fast guided filter** (He & Sun, 2015): subsample by 4x, compute on low-res, upsample coefficients. 10x speedup with minimal quality loss.

Already partially implemented in `DepthBokeh.kt:86-114` — currently applied at model resolution, must upsample to image resolution.

**Stage 3: Disc-Kernel Scatter Bokeh**

Replace box blur pyramid with physically-based disc/hexagonal kernel:

**Circle of Confusion radius:**
```
CoC(d) = k * |d - d_focus| / d
```
Where `k` controls simulated aperture size (10-25 pixels for portrait).

**Rendering approach — scatter-as-gather on GPU:**
For each output pixel, sample all contributing pixels within the CoC radius using a Poisson disc pattern. Weight by kernel shape (disc = uniform, hexagonal = 6-blade aperture simulation).

**Quality enhancements:**
1. **Specular Bloom:** Extract bright pixels (L > 0.9), apply `pow(n)` to inflate into highlight discs, then `pow(1/n)` after blending. Creates characteristic bright bokeh balls.
2. **Cat-Eye Distortion:** Scale kernel into oval based on distance from image center — simulates optical vignetting of real lenses.
3. **Chromatic Aberration:** Slightly offset R, G, B scatter patterns to replicate fringing from fast prime lenses.

### Performance Target

| Stage | Target Time |
|-------|------------|
| Depth estimation (NPU) | < 50ms |
| Guided filter upsample | < 15ms |
| Disc-kernel bokeh (GPU) | < 100ms |
| **Total** | **< 165ms** |

### Focus Depth Selection

```kotlin
val focusDepth = if (faceRects.isNotEmpty()) {
    // Sample 3x3 grid around face center in depth map
    val cx = ((face.left + face.right) / 2f * depthSize).toInt()
    val cy = ((face.top + face.bottom) / 2f * depthSize).toInt()
    // Median of 3x3 neighborhood for robustness
    medianDepth(depthMap, cx, cy, depthSize)
} else {
    // Center of frame, tap-to-focus point, or nearest depth
    depthMap[depthSize / 2 * depthSize + depthSize / 2]
}
```

### Files to Modify

- `camera/DepthEstimator.kt` — add NNAPI delegate, consider Depth Anything V2
- `camera/DepthBokeh.kt` — replace box blur pyramid with disc-kernel scatter, add guided filter upsampling pipeline
- `camera/CaptureManager.kt:1507-1554` — update `applyDepthMapBokeh()` to use new pipeline
- **New:** `camera/src/main/cpp/` — NDK Vulkan compute shaders for bokeh rendering

---

## 7. TFLite Scene Classification

### Problem

`scene_classifier.tflite` and `depth_estimator.tflite` are not bundled in assets. The heuristic classifier (`SceneClassifier.kt:125-252`) runs on 48x48 thumbnails — correctly detects NIGHT and LANDSCAPE but completely fails on FOOD, PET, ACTION (contextual scenes that require understanding what's in the frame).

### Model: EfficientNet-Lite0 on Places365

| Property | Value |
|----------|-------|
| Architecture | EfficientNet-Lite0 (NAS-optimized, ReLU6, no SE blocks) |
| Parameters | 4.7M |
| Model size (INT8) | 5.4 MB |
| Input | 224x224x3 float32 (RGB, ImageNet normalized) |
| Output | N-class softmax probabilities |
| Inference (NPU) | 4-7ms via NNAPI delegate |
| Inference (GPU) | 9ms via GPU delegate |
| Inference (CPU INT8) | 6.5ms (4 threads) |
| ImageNet top-1 | 75.1% |

### Training Data: Places365

Fine-tune EfficientNet-Lite0 on [Places365](https://github.com/CSAILVision/places365) dataset (1.8M images, 365 scene categories). Map Places365 categories to SPECTRA's scene types:

| SPECTRA Scene | Places365 Categories (examples) |
|--------------|-------------------------------|
| LANDSCAPE | field/wild, mountain, valley, lake/natural, ocean |
| PORTRAIT | (not a Places365 category — use face detection instead) |
| NIGHT | bar, nightclub, sky, highway at night |
| FOOD | bakery, restaurant, kitchen, food_court |
| ARCHITECTURE | building_facade, cathedral, skyscraper, tower |
| INDOOR | bedroom, living_room, office, classroom |
| MACRO | (augment with macro photography dataset) |
| ACTION | stadium, track, gymnasium |
| DOCUMENT | (augment with document image dataset) |

### Input Preprocessing

```kotlin
// In SceneClassifier.bitmapToByteBuffer()
val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)  // ImageNet
val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
for (pixel in pixels) {
    buffer.putFloat((((pixel shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
    buffer.putFloat((((pixel shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1])
    buffer.putFloat(((pixel and 0xFF) / 255f - MEAN[2]) / STD[2])
}
```

### Inference Acceleration

```kotlin
// Prefer NNAPI (routes to Hexagon NPU for INT8 models)
val nnapiDelegate = NnApiDelegate(NnApiDelegate.Options().apply {
    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER)
})
val options = Interpreter.Options().apply {
    addDelegate(nnapiDelegate)
    setNumThreads(4)
}
interpreter = Interpreter(model, options)
```

### Heuristic Fallback

Keep `classifyHeuristic()` as fallback when TFLite model fails to load. But bump analysis resolution from 48x48 to at least 96x96 for better edge/texture discrimination.

### Files to Modify

- `ai-engine/src/main/assets/` — bundle `scene_classifier.tflite` (INT8 quantized EfficientNet-Lite0)
- `ai-engine/src/main/assets/scene_labels.txt` — update to match trained model output classes
- `ai-engine/SceneClassifier.kt` — add NNAPI delegate, ImageNet normalization, bump heuristic resolution

---

## 8. Inclusive Color Science & Skin Tones

### Problem

`CaptureManager.kt:1989-1998` uses YCbCr skin detection with thresholds (`Cb in 77..127, Cr in 133..173`) biased toward lighter skin tones. Darker complexions fall outside these ranges, causing the beauty filter to miss them entirely.

### Solution: Monk Skin Tone (MST) Scale Integration

The 10-shade MST scale replaces the outdated Fitzpatrick scale. The goal: face detection, AWB, auto-exposure, and beauty processing calibrated across the entire human spectrum.

**Three-stage "Real Tone" pipeline:**

1. **Semantic Face Awareness:** Identify face count and MST shade for every subject. Use ML Kit face detection (already integrated) + a lightweight skin tone classifier on the detected face crop.

2. **Nuanced AWB:** Adjust color temperature per-face to bring out natural brown and deep-red tones, preventing the "ashy" desaturation common in standard camera algorithms.

3. **Adaptive Well-Exposedness (AWE):** Instead of global exposure target, use a weighted mask that prioritizes faces — even in group shots with diverse MST levels.

### Perceptually Uniform Lab-Space Processing

**File:** `CaptureManager.kt:1943-1987` `applyLabBeauty()`

Skin analysis should use CIE L*a*b* color space (separates lightness from color channels in a way matching human vision). Current implementation works in YCbCr which is less perceptually uniform.

**Undertone classification** via mean Lab values of detected skin region compared against MST reference points using CIEDE2000 delta E:

| Skin Tone Category | Lab Reference (L, a, b) | Tonal Strategy |
|-------------------|------------------------|----------------|
| Very Light (MST 1-2) | (85, 12, 15) | Highlight protection, pink/red retention |
| Medium (MST 3-6) | (65, 18, 25) | Warmth enhancement, texture separation |
| Dark (MST 7-10) | (35, 15, 20) | Stray light reduction, contrast boost |

### Beauty Filter: Lab Frequency Separation

The beauty filter should:
1. Convert to Lab space
2. Apply bilateral filter to **L channel only** to smooth blemishes
3. Preserve high-frequency detail (pores, hair texture) in the high-pass layer
4. Leave a* and b* channels untouched (preserves natural skin color)

This prevents the "plastic" look of cheap beauty modes while effectively smoothing skin.

### Wider Skin Detection Range

Replace hardcoded YCbCr thresholds with a more inclusive range:
```kotlin
// Expanded for darker skin tones
cb in 70..135 && cr in 125..180
```

Or better: use the ML Kit face detection bounding box directly as the skin mask (face region = skin, no color threshold needed).

### Files to Modify

- `camera/CaptureManager.kt:1943-1998` — Lab-space beauty, expanded skin detection
- `camera/ColorSpaceUtils.kt` — add RGB↔Lab conversion
- `ai-engine/DecisionEngine.kt` — face-weighted exposure metering

---

## 9. WYSIWYG Preview Pipeline

### Problem

The viewfinder shows the raw camera preview. The captured photo goes through noise reduction, tone mapping, sharpening, color grading, beauty — producing a result that looks noticeably different from what the user composed. This destroys trust.

### Solution: Unified GPU Preview Pipeline

Run the same processing chain on preview frames as on captured images, at lower resolution:

1. **Preview resolution:** 1080p (1920x1080 = 2MP) — achievable at 30+ FPS
2. **Same shader code:** The Vulkan/AGSL shaders used for capture processing run on preview frames
3. **GLSL `samplerExternalOES`:** Process YUV frames directly from camera hardware without CPU roundtrip

### Preview Pipeline (runs per frame at 30 FPS):

| Stage | Target Time | Notes |
|-------|------------|-------|
| YUV→RGB + WB | 1ms | AGSL shader |
| Tone mapping (guided filter) | 3ms | Simplified: smaller radius |
| Color grading (LUT) | 1ms | AGSL shader |
| Light NR (if high ISO) | 2ms | Reduced radius bilateral |
| USM sharpening | 1ms | AGSL shader |
| **Total** | **< 8ms** | Leaves 25ms headroom for 30 FPS |

Heavy operations (full NR, HDR fusion, bokeh) only run on the final captured frame — not in preview. But the tone/color signature must match.

### Files to Modify

- `camera/SpectraCameraController.kt` — add preview frame analyzer with GPU processing
- `app/ui/viewfinder/ViewfinderScreen.kt` — display processed preview instead of raw
- **New:** `camera/PreviewProcessor.kt` — lightweight GPU preview pipeline

---

## 10. Pro-Grade Utilities

### True DNG RAW Capture

**File:** `camera/CaptureManager.kt:858-895` — current `saveRawCopy()` saves JPEG labeled as "RAW".

Fix: Use Camera2 `RAW_SENSOR` output + `DngCreator` class:
```kotlin
val dngCreator = DngCreator(cameraCharacteristics, captureResult)
dngCreator.setOrientation(exifOrientation)
dngCreator.writeImage(outputStream, rawImage)  // Image from RAW_SENSOR ImageReader
```

Note: Third-party apps on S24 Ultra are capped at 12MP DNG (sensor binned). This is a Samsung firmware limitation, not fixable in app code.

### Focus Distance Readout

**File:** `core/model/HudState.kt:59` — `actualFocusDistance: Float`

The Camera2 `LENS_FOCUS_DISTANCE` is reported in **diopters** (1/meters). Convert for display:
```kotlin
val meters = if (diopters > 0f) 1.0f / diopters else Float.MAX_VALUE
val displayText = when {
    meters > 100f -> "INF"
    meters > 1f -> "%.1fm".format(meters)
    else -> "%.0fcm".format(meters * 100)
}
```

### Focus Peaking

Sobel filter in fragment shader to detect high-frequency edges, overlay in contrasting color (green or magenta):

```glsl
// Sobel edge detection for focus peaking
float sobelX = -1*tl + 1*tr - 2*ml + 2*mr - 1*bl + 1*br;
float sobelY = -1*tl - 2*tm - 1*tr + 1*bl + 2*bm + 1*br;
float edge = sqrt(sobelX*sobelX + sobelY*sobelY);
if (edge > threshold) {
    fragColor = vec4(0.0, 1.0, 0.0, 0.8);  // Green overlay
}
```

### Zebra Stripes

Already in `HudState.kt:94-95` (`zebraEnabled`, `zebraThreshold = 235`). Implement as GPU shader on preview:
```glsl
if (luminance > zebraThreshold / 255.0) {
    // Diagonal stripe pattern
    float stripe = mod(fragCoord.x + fragCoord.y, 8.0);
    if (stripe < 4.0) fragColor = vec4(1.0, 0.0, 0.0, 0.5);
}
```

### Shutter Vibration Damping

When shutter button is pressed, wait 100ms "stabilization window" for the gyroscope to report minimal angular velocity before triggering capture. This eliminates shake from the physical tap force.

---

## 11. Priority & Performance Budget

### Implementation Priority

| Priority | Item | Impact | Effort |
|----------|------|--------|--------|
| **P0** | Fix LensManager focal length thresholds | Unlocks 3X telephoto | Small |
| **P0** | Fix AE compensation step query | Correct EV on all lenses | Small |
| **P0** | Resolve settings illusion (show actual values) | Credibility | Medium |
| **P0** | Bundle TFLite scene classifier | Core AI feature | Medium |
| **P0** | Bundle TFLite depth estimator | Portrait mode | Medium |
| **P1** | Mertens HDR fusion (CPU first, GPU later) | HDR quality | Large |
| **P1** | Guided filter depth upsampling + disc bokeh | Portrait quality | Large |
| **P1** | GPU-accelerated NR (Vulkan bilateral) | Processing speed | Large |
| **P1** | WYSIWYG preview pipeline | User trust | Large |
| **P1** | Inclusive skin tone detection | Color science | Medium |
| **P2** | True DNG RAW capture | Pro users | Medium |
| **P2** | Focus peaking + zebras (GPU shader) | Pro tools | Medium |
| **P2** | Intelligent lens switching (lux-aware) | Photo quality | Medium |
| **P2** | DRD-based HDR activation | Automation | Small |
| **P2** | Raise ISO ceiling to 12800 | Night capability | Small |
| **P3** | Full Vulkan compute pipeline | Performance | Very Large |
| **P3** | AGSL RuntimeShader for preview | Performance | Large |
| **P3** | Semantic HDR (sky/subject segmentation) | Advanced | Very Large |
| **P3** | Multi-lens fusion / Super-Res zoom | Advanced | Very Large |
| **P3** | Specular bloom + cat-eye bokeh | Polish | Medium |

### Total Pipeline Performance Budget (GPU-Accelerated Target)

| Stage | Time | Hardware |
|-------|------|----------|
| Scene Classification | 5ms | NPU |
| White Balance | 2ms | GPU |
| Noise Reduction | 45ms | GPU |
| HDR Fusion (3 brackets) | 120ms | GPU |
| Depth Estimation | 50ms | NPU |
| Bokeh Rendering | 100ms | GPU |
| Tone Mapping | 20ms | GPU |
| Sharpening (USM) | 5ms | GPU |
| Color Grading (LUT) | 3ms | GPU |
| **Total single-frame** | **~130ms** | |
| **Total with HDR** | **~250ms** | |
| **Total with HDR + bokeh** | **~350ms** | |

### Transformation Summary

| Feature Area | Current State | Target | Outcome |
|-------------|--------------|--------|---------|
| Compute | CPU iteration (seconds) | Vulkan + AHardwareBuffer | Sub-second capture |
| Optics | Broken 3X/5X mapping | Corrected EFL + lux switching | Access to best portrait lens |
| HDR | 2-frame luminance average | Mertens + Laplacian pyramids | Natural 14-stop DR |
| Bokeh | 256px box blur | Guided filter + disc kernel | Mirrorless-quality portraits |
| Color | YCbCr bias to light skin | MST scale + Lab beauty | Universal ethnic inclusivity |
| Trust | Preview/capture mismatch | Unified GPU viewfinder | Professional WYSIWYG |
| AI | Missing TFLite models | EfficientNet-Lite0 INT8 + MiDaS | Real scene intelligence |
| Settings | Fake HUD values | Actual sensor metadata | Professional credibility |
