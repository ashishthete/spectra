# SPECTRA 10/10 Roadmap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring SPECTRA from 6.0/10 to world-class — a camera app that competes with iPhone, Pixel, and Halide on image quality, intelligence, and polish.

**Architecture:** Eight phases, ordered by impact and dependency. Each phase is shippable. OOM stability first, then processing quality (the biggest gap), then intelligence/UI/advanced/performance/premium features.

**Tech Stack:** Kotlin, CameraX/Camera2, TFLite (MobileNetV3 + NAFNet), Jetpack Compose, ML Kit, OpenCV JNI (immediate NR speedup), GLES 3.1 compute shaders (GPU acceleration — RenderScript is deprecated/removed in Android 14+)

**Research-Informed Decisions (2026-05-18):**
- **NR:** OpenCV JNI bilateral = 10-20x speedup immediately. Long-term: NAFNet TFLite on Hexagon NPU (~200ms at 1080p). All major camera apps use neural NR.
- **HDR:** ±1.5EV adaptive brackets (not ±1EV). Add MTB ghost detection. Laplacian pyramid post-fusion confirmed worthwhile.
- **Color:** 3D LUTs (33x33x33) are dramatically better than 1D per-channel curves — cross-channel hue shifts are essential. Design in DaVinci Resolve, export .cube files.
- **Sharpening:** 3-level Laplacian pyramid (not single-scale USM). Pipeline: denoise → sharpen → tonemap.
- **Beauty:** Frequency separation with Gaussian sigma 6-8px at full-res. Skin mask via MediaPipe Face Mesh + YCbCr thresholding.
- **GPU:** GLES 3.1 compute shaders (not RenderScript — deprecated). 30-50x speedup. Bilateral: ~20ms vs ~1000ms CPU. Use HardwareBuffer for zero-copy camera-to-GPU path.
- **Highlight rolloff:** Hable filmic / ACES curve is industry standard, not simple quadratic.

---

## Phase 6A: Stability & OOM Guards (2 tasks)

*Fix: processing crashes that kill the app on any capture*

### Task 1: Fix OOM in applyLocalToneMap (DONE)

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt:914-960`

Already applied: `applyLocalToneMap` now downsamples to 960px for bilateral decomposition, computes scale map at low res, samples it back to full-res pixels. Memory drops from ~240MB to ~15MB.

- [x] Completed

### Task 2: Add OOM guard wrapper for all post-processing (DONE)

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt:751` (`applyPostProcess`)
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

Already applied: Each heavy processing step (local tone map, NR, beauty, bokeh, sharpening) now catches `OutOfMemoryError` individually, logs warning, calls `System.gc()`, and continues. The outer `applyPostProcess` also catches OOM. The ViewModel falls back to showing the raw original URI if processing fails.

- [x] Completed

---

## Phase 6B: Processing Quality (8 tasks)

*The biggest quality gap: NR, HDR, sharpening, color science, highlight rolloff*

### Task 3: Fix NoiseReducer — OpenCV JNI bilateral + tiled full-res

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/NoiseReducer.kt`
- Create: `camera/src/main/cpp/bilateral_jni.cpp` (JNI bridge to OpenCV)
- Test: `camera/src/test/java/com/spectra/camera/NoiseReducerTest.kt`

**Problem:** Current NR downsamples to 1920px, runs bilateral, upsamples back — obliterates fine detail, produces waxy look. Pure Kotlin bilateral takes ~1000ms even at reduced res.

**Solution (2-tier, research-informed):**
- **Immediate (this task):** Replace Kotlin bilateral with OpenCV's NEON-optimized `cv::bilateralFilter` via JNI. 10-20x speedup, enabling full-res Y-channel processing without the downsample hack. Use 128x128 tiles (optimal for Cortex-X4 L1 cache). Chroma NR stays at half-res.
- **Future (Phase 6F Task 33):** Move bilateral to GLES 3.1 compute shader for ~50x speedup (~20ms at 12MP). Even further: NAFNet TFLite model on Hexagon NPU for iPhone/Pixel-class neural NR.

- [ ] **Step 1: Write test for tiled processing — edge tile outputs match non-tiled for small images**

```kotlin
@Test
fun `tiled bilateral matches single-pass for small images`() {
    val w = 64; val h = 64
    val channel = IntArray(w * h) { (it * 3 + 17) % 256 }
    val singlePass = NoiseReducer.bilateralFilter(channel.copyOf(), w, h, 3, 1.0f)
    val tiledPass = NoiseReducer.tiledBilateralFilter(channel.copyOf(), w, h, 3, 1.0f, tileSize = 128)
    for (i in singlePass.indices) {
        assertThat(tiledPass[i]).isWithin(1).of(singlePass[i])
    }
}
```

- [ ] **Step 2: Run test to verify it fails** (tiledBilateralFilter doesn't exist yet)

- [ ] **Step 3: Implement tiledBilateralFilter**

```kotlin
internal fun tiledBilateralFilter(
    channel: IntArray, w: Int, h: Int,
    spatialRadius: Int, rangeSigma: Float,
    tileSize: Int = 512
): IntArray {
    val output = IntArray(channel.size)
    val overlap = spatialRadius + 1

    var ty = 0
    while (ty < h) {
        var tx = 0
        val tileH = minOf(tileSize, h - ty)
        while (tx < w) {
            val tileW = minOf(tileSize, w - tx)

            val srcX0 = maxOf(0, tx - overlap)
            val srcY0 = maxOf(0, ty - overlap)
            val srcX1 = minOf(w, tx + tileW + overlap)
            val srcY1 = minOf(h, ty + tileH + overlap)
            val srcW = srcX1 - srcX0
            val srcH = srcY1 - srcY0

            val tileSrc = IntArray(srcW * srcH)
            for (row in 0 until srcH) {
                System.arraycopy(channel, (srcY0 + row) * w + srcX0, tileSrc, row * srcW, srcW)
            }

            val filtered = bilateralFilter(tileSrc, srcW, srcH, spatialRadius, rangeSigma)

            val offX = tx - srcX0
            val offY = ty - srcY0
            for (row in 0 until tileH) {
                for (col in 0 until tileW) {
                    output[(ty + row) * w + (tx + col)] = filtered[(offY + row) * srcW + (offX + col)]
                }
            }

            tx += tileSize
        }
        ty += tileSize
    }
    return output
}
```

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Update `apply()` to use tiled filter for Y channel, reduced-res for chroma**

Replace the current downsample-upscale approach: Y channel uses `tiledBilateralFilter` at full resolution, Cb/Cr channels keep the existing half-res approach.

- [ ] **Step 6: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/NoiseReducer.kt camera/src/test/java/com/spectra/camera/NoiseReducerTest.kt
git commit -m "feat: tiled bilateral NR preserving full-res luma detail"
```

### Task 4: Fix double sharpening in HDR path

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt:700-701,823`

**Problem:** `mergeHdrFrames()` at line 701 calls `applySharpen(result)`. Then `applyPostProcess()` at line 823 calls `applySharpenLuminance(result, sceneType)`. HDR photos are sharpened twice — once with basic USM, once with edge-aware luminance sharpening.

**Depends on:** None (independent)

- [ ] **Step 1: Remove `applySharpen(result)` from `mergeHdrFrames()`**

Delete line 701. The edge-aware luminance sharpening in `applyPostProcess` is superior and sufficient.

- [ ] **Step 2: Also remove `applySharpen(result)` from `stackAndEnhance()`**

Line 318 — same double-sharpening problem in the multi-frame night path.

- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "fix: remove double sharpening from HDR and night paths"
```

### Task 5: Fix highlight rolloff — use Hable filmic curve (industry standard)

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt`
- Modify: `camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt`

**Problem:** Current rolloff uses t² (quadratic), which is convex — it compresses midtones too aggressively and doesn't compress near-whites enough. Real film stocks (Kodak Portra, Fuji Pro 400H) have a logarithmic shoulder.

**Research finding:** The industry standard is the **Hable/Uncharted 2 filmic curve** or **ACES filmic**: `f(x) = (x*(A*x+C*B)+D*E) / (x*(A*x+B)+D*F) - E/F`. This produces the characteristic film shoulder with toe (shadow lift), linear mid, and exponential highlight rolloff `1 - e^(-kx)`. Pre-bake into the 256-entry LUT.

**Fix:** Replace simple quadratic with Hable filmic mapping for the highlight region.

- [ ] **Step 1: Update `buildHighlightRolloffCurve` to use Hable filmic shoulder**

```kotlin
// Hable filmic: toe + linear + shoulder
fun hableFilmic(x: Float): Float {
    val A = 0.15f; val B = 0.50f; val C = 0.10f
    val D = 0.20f; val E = 0.02f; val F = 0.30f
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F
}
// Normalize: output = hableFilmic(t * exposureScale) / hableFilmic(whitePoint)
```

- [ ] **Step 2: Update tests for new curve shape**

The concave curve produces higher output values in the mid-shoulder range than the convex curve. Adjust test expectations.

- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt
git commit -m "fix: use concave highlight rolloff for natural film shoulder"
```

### Task 6: Fix NATURAL style — it should do almost nothing

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt`

**Problem:** NATURAL style applies a tone curve with contrast and saturation adjustments. A "natural" style should produce output nearly identical to the sensor capture — minimal processing. Users selecting NATURAL are saying "don't change my colors."

**Depends on:** None (independent, but should precede Task 7)

- [ ] **Step 1: Make NATURAL style apply identity or near-identity curve**

In `ToneCurveEngine.apply()`, when style is NATURAL, either skip entirely or apply only a very subtle contrast curve (1.01 scale max).

- [ ] **Step 2: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt
git commit -m "fix: NATURAL style applies near-identity processing"
```

### Task 7: Replace ColorMatrix styles with 3D LUTs (33x33x33)

**Files:**
- Create: `camera/src/main/assets/lut_vivid.cube`
- Create: `camera/src/main/assets/lut_film.cube`
- Create: `camera/src/main/assets/lut_cinematic.cube`
- Modify: `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt`
- Create: `camera/src/main/java/com/spectra/camera/Lut3D.kt`
- Test: `camera/src/test/java/com/spectra/camera/ToneCurveEngineTest.kt`

**Problem:** VIVID, FILM, CINEMATIC styles are ColorMatrix transforms — single multiplication per pixel. ColorMatrix cannot express cross-channel hue shifts (e.g., "warm shadows + cool highlights" requires knowing all three channels simultaneously).

**Research finding:** 3D LUTs (33x33x33, ~108KB each) are the industry standard used by VSCO, Halide, and Fujifilm film simulations. They capture cross-channel interactions that 1D per-channel curves fundamentally cannot. The quality difference is dramatic — hue-dependent saturation (Velvia boosts greens but leaves skin tones alone) is only possible with 3D LUTs. On GLES 3.0+, `GL_TEXTURE_3D` handles trilinear interpolation in hardware. Even CPU-based trilinear 3D LUT lookup is only ~2x slower than 1D lookup.

**Solution:** Design LUTs in DaVinci Resolve (free), export as `.cube` files, load at startup, apply via trilinear interpolation. CPU implementation first, GPU upgrade in Phase 6F.

**Depends on:** Task 6 (NATURAL identity baseline must be defined first)

- [ ] **Step 1: Write `.cube` file parser (industry standard format)**

```kotlin
// .cube format: "LUT_3D_SIZE 33" header, then 33^3 lines of "R G B" floats 0.0-1.0
object Lut3D {
    fun parse(cubeText: String): FloatArray // 33*33*33*3 = 107,811 floats
    fun apply(r: Int, g: Int, b: Int, lut: FloatArray, size: Int): Triple<Int,Int,Int>
    // Trilinear interpolation between 8 nearest LUT vertices
}
```

- [ ] **Step 2: Create initial LUT .cube files for each style**

Design in DaVinci Resolve or compute programmatically:
- **VIVID:** S-curve contrast, hue-dependent saturation boost (greens/blues strong, skin tones preserved)
- **FILM:** Lifted blacks to 10-15/255, warm shadow crossover (R up / B down in shadows), cool highlights, soft shoulder starting at 70% luminance
- **CINEMATIC:** Teal shadows (B/G lifted low), orange highlights (R pushed high), compressed DR (blacks to 20/255, whites to 235/255)

- [ ] **Step 3: Write test for 3D LUT identity (identity.cube passes all values unchanged)**

```kotlin
@Test
fun `identity 3D LUT passes all values unchanged`() {
    val lut = Lut3D.generateIdentity(33)
    for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
        val (outR, outG, outB) = Lut3D.apply(r, g, b, lut, 33)
        assertThat(outR).isWithin(1).of(r)
        assertThat(outG).isWithin(1).of(g)
        assertThat(outB).isWithin(1).of(b)
    }
}
```

- [ ] **Step 4: Replace ColorMatrix application in ToneCurveEngine with 3D LUT application**
- [ ] **Step 5: Run tests, commit**

```bash
git add camera/src/main/java/com/spectra/camera/Lut3D.kt camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt camera/src/main/assets/*.cube camera/src/test/java/com/spectra/camera/ToneCurveEngineTest.kt
git commit -m "feat: replace ColorMatrix styles with 3D LUT color grading"
```

### Task 8: Improve HDR — ±1.5EV adaptive brackets + MTB ghost detection + Laplacian post-fusion

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`
- Test: `camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt`

**Problem:** ±2EV bracket spread is too wide — causes ghosting in moving scenes and visible alignment artifacts. Mertens fusion weights should include Laplacian contrast (edge-aware), not just brightness/saturation. No ghost detection.

**Research finding:** ±1.5EV is the sweet spot — ±1EV loses shadow detail, ±2EV introduces excessive ghosting. **MTB (Median Threshold Bitmap) alignment** is the standard ghost detection: threshold each frame at median brightness → XOR between frames → regions with high XOR density are ghosted → exclude from fusion. Post-fusion Laplacian pyramid blending (3-level) produces seamless transitions at exposure boundaries.

**Depends on:** Task 4 (double sharpening removed from mergeHdrFrames first, since both touch the same code path)

- [ ] **Step 1: Change bracket spread to ±1.5EV**

```kotlin
val underExposure = (baseExposureNs / 2.83f).toLong()  // -1.5EV (÷√8)
val overExposure = (baseExposureNs * 2.83f).toLong()    // +1.5EV (×√8)
```

- [ ] **Step 2: Add MTB ghost detection**

```kotlin
fun detectGhostRegions(frames: List<IntArray>, w: Int, h: Int): BooleanArray {
    val ghostMask = BooleanArray(w * h)
    val medians = frames.map { frame -> frame.sorted()[frame.size / 2] }
    val mtbs = frames.mapIndexed { i, frame ->
        BooleanArray(frame.size) { px -> frame[px] > medians[i] }
    }
    val refMtb = mtbs[0]
    for (i in 1 until mtbs.size) {
        for (px in refMtb.indices) {
            if (refMtb[px] != mtbs[i][px]) ghostMask[px] = true
        }
    }
    return ghostMask
}
```

In ghosted regions, use only the base exposure frame instead of fusing all three.

- [ ] **Step 3: Add Laplacian contrast weight to Mertens fusion**

In `mertensFusion`, compute per-pixel Laplacian magnitude (3x3 kernel: `center*4 - top - bottom - left - right`) and multiply into the weight map. Sharper frames/regions get higher fusion weight.

- [ ] **Step 4: Update tests, commit**

```bash
git add camera/src/main/java/com/spectra/camera/HdrProcessor.kt camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt
git commit -m "fix: ±1.5EV brackets, MTB ghost detection, Laplacian contrast weighting"
```

### Task 9: 3-level Laplacian pyramid sharpening + pipeline reorder

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt` (`applySharpenLuminance`, `applyPostProcess`)

**Problem:** Two issues: (1) Current sharpening uses single-scale USM with a binary edge mask — produces halos and can't distinguish fine detail from medium structure. (2) Sharpening happens after tone curves in the pipeline, amplifying quantization noise from curve operations.

**Research finding:** 3-level Laplacian pyramid sharpening is the professional standard. Decompose the luminance into 3 frequency bands: fine (pores, texture), mid (wrinkles, edges), coarse (large structures). Boost mid-detail at 0.3-0.5x, fine detail at 0.1-0.15x, leave coarse alone. This avoids halos and produces natural-looking sharpening. **Pipeline order must be denoise → sharpen → tonemap** — sharpening before tone curves operates on cleaner data.

**Depends on:** Task 4 (double sharpening must be removed first so only this sharpening pass remains)

- [ ] **Step 1: Implement 3-level Laplacian pyramid decomposition**

```kotlin
fun laplacianPyramid(luma: FloatArray, w: Int, h: Int): List<FloatArray> {
    val levels = mutableListOf<FloatArray>()
    var current = luma; var cw = w; var ch = h
    for (level in 0 until 3) {
        val blurred = gaussianBlur5x5(current, cw, ch)
        val detail = FloatArray(cw * ch) { current[it] - blurred[it] }
        levels.add(detail)
        current = downsample2x(blurred, cw, ch)
        cw /= 2; ch /= 2
    }
    levels.add(current) // residual
    return levels
}

fun reconstructFromPyramid(levels: List<FloatArray>, boosts: FloatArray, w: Int, h: Int): FloatArray {
    var result = levels.last()
    for (i in levels.size - 2 downTo 0) {
        result = upsample2x(result, ...)
        val boosted = FloatArray(levels[i].size) { result[it] + levels[i][it] * (1f + boosts[i]) }
        result = boosted
    }
    return result
}
```

Boosts: `[0.12f, 0.4f, 0.0f]` — fine detail: 12% boost, mid-detail: 40% boost, coarse: no boost.

- [ ] **Step 2: Replace single-scale USM with Laplacian pyramid in `applySharpenLuminance`**

- [ ] **Step 3: Reorder pipeline: move sharpening before tone curves**

In `applyPostProcess`, change order from:
`NR → toneCurve → highlightRolloff → sharpen → vignette`
to:
`NR → sharpen → toneCurve → highlightRolloff → vignette`

- [ ] **Step 4: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat: 3-level Laplacian pyramid sharpening, reorder pipeline denoise→sharpen→tonemap"
```

### Task 10: Frequency-separation beauty with skin mask

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt` (replace `applyLabBeauty`)

**Problem:** Current beauty mode does 12x downscale -> bilateral -> upscale -> blend at 28% opacity. This destroys skin texture completely and applies blur globally (including eyes, hair, clothing).

**Research finding:** Professional beauty retouching uses **frequency separation**: Gaussian blur sigma 6-8px at full resolution (NOT bilateral — Gaussian preserves color transitions better for frequency split). Subtract to get high-frequency texture. Smooth only the low-frequency layer (removes blemishes, color unevenness). Recombine: `output = filtered_low + original_high` preserves every pore. **Skin mask** via MediaPipe Face Mesh (468 landmarks → convex hull of face region) + YCbCr thresholding (Cb: 77-127, Cr: 133-173) ensures beauty is applied ONLY to skin, not eyes/hair/background. Since MediaPipe requires Android context, use simpler YCbCr-only skin detection for now (no ML dependency in camera module).

**Solution:**
1. Detect skin pixels via YCbCr thresholding (Cb: 77-127, Cr: 133-173)
2. Gaussian blur sigma 7px at full resolution → low-frequency layer
3. Subtract: high-frequency = original - blurred (texture/pores)
4. Smooth low-frequency layer with second Gaussian pass
5. Recombine: `output = smoothed_low + original_high` (skin pixels only)
6. Non-skin pixels pass through unchanged

- [ ] **Step 1: Write test — beauty processing preserves texture variance**

```kotlin
@Test
fun `frequency separation preserves texture variance`() {
    val w = 64; val h = 64
    // Create textured pattern (alternating high/low values simulating pores)
    val textured = IntArray(w * h) { i ->
        val base = 180
        val texture = if ((i / w + i % w) % 2 == 0) 10 else -10
        val r = base + texture; val g = base + texture; val b = base - 20 // skin-ish tone
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    val varianceBefore = computeVariance(textured)
    applyFreqSepBeauty(textured, w, h, beautyLevel = 2)
    val varianceAfter = computeVariance(textured)
    assertThat(varianceAfter).isGreaterThan(varianceBefore * 0.5)
}
```

- [ ] **Step 2: Implement YCbCr skin detection mask**

```kotlin
fun buildSkinMask(pixels: IntArray, w: Int, h: Int): BooleanArray {
    return BooleanArray(pixels.size) { i ->
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8) and 0xFF
        val b = pixels[i] and 0xFF
        val cb = (128 - 37.797f * r / 255f - 74.203f * g / 255f + 112f * b / 255f).toInt()
        val cr = (128 + 112f * r / 255f - 93.786f * g / 255f - 18.214f * b / 255f).toInt()
        cb in 77..127 && cr in 133..173
    }
}
```

- [ ] **Step 3: Implement frequency-separation beauty with skin mask**

```kotlin
fun applyFreqSepBeauty(pixels: IntArray, w: Int, h: Int, beautyLevel: Int) {
    val skinMask = buildSkinMask(pixels, w, h)
    val sigma = 5f + beautyLevel * 2f // 7px at level 1, 9px at level 2, 11px at level 3
    // Extract luminance channel
    // low = gaussianBlur(luminance, sigma)
    // high = luminance - low
    // smoothed_low = gaussianBlur(low, sigma * 0.5)
    // For skin pixels: output_luma = smoothed_low + high
    // Non-skin: unchanged
    // Reconstruct RGB
}
```

- [ ] **Step 4: Run test, commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt camera/src/test/java/com/spectra/camera/CaptureManagerBeautyTest.kt
git commit -m "feat: frequency-separation beauty with YCbCr skin mask, preserving texture"
```

---

## Phase 6C: Camera Intelligence (8 tasks)

*Make the AI decisions real, not cosmetic — the #1 credibility problem*

### Task 11: Display actual sensor metadata in auto mode HUD

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: `camera/src/main/java/com/spectra/camera/SensorMetadata.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/hud/SettingsReadout.kt`

**Problem:** In auto mode, HUD shows AI-recommended ISO/shutter that aren't applied to hardware. User sees "ISO 50 · 1/500s" while camera shoots ISO 400 · 1/60s. This is the **#1 credibility problem** from the review.

**Solution:** Read actual sensor metadata from CaptureResult callbacks. Display real values with "AUTO" label. AI recommendations shown separately as "AI suggests:" subtitle only when significantly different from actual.

- [ ] **Step 1: Add `actualIso: Int` and `actualShutterNs: Long` to HudState**
- [ ] **Step 2: Wire CaptureResult callback to extract SENSOR_SENSITIVITY and SENSOR_EXPOSURE_TIME**
- [ ] **Step 3: Update SettingsReadout to show actual values with "AUTO" prefix in auto mode**
- [ ] **Step 4: When in auto+hints mode, show AI recommendations as a small "AI: ISO XX · 1/YYYs" subtitle below actual values — clearly differentiated from applied values**
- [ ] **Step 5: Commit**

### Task 12: Semi-auto exposure for ALL non-PRO presets

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/DecisionEngine.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Problem:** The `applySemiAuto()` method exists but is never called outside PRO mode. Non-PRO presets use `applyAutoWithHints()` which only nudges EV. The review's #1 finding: "the app simulates camera intelligence without actually controlling the camera."

**Solution:** Use `applySemiAuto()` for ALL non-AUTO presets when scene confidence > 0.7. For AUTO preset and low-confidence classifications, fall back to `applyAutoWithHints()` with clear HUD labeling that values are approximate.

Decision matrix (expanded from original):
- **PORTRAIT** → semi-auto: moderate shutter (1/125+), ISO ceiling 800, face-exposed
- **ACTION** → semi-auto: fast shutter (1/500+), ISO ceiling 3200
- **NIGHT** → semi-auto: long exposure + low ISO
- **LANDSCAPE** → semi-auto: base ISO, moderate shutter
- **FOOD** → semi-auto: base ISO, moderate shutter, warm WB
- **MACRO** → semi-auto: fast shutter (prevent micro-shake), base ISO
- **AUTO** (low confidence) → auto+hints (safe fallback, HUD shows "AUTO" prefix)

- [ ] **Step 1: Add `getPresetExposureStrategy(preset, sceneAnalysis): ExposureStrategy` to DecisionEngine**

Return a data class with target ISO range, min shutter speed, and a `useSemiAuto: Boolean` flag.

- [ ] **Step 2: Wire CameraViewModel to call `applySemiAuto` when strategy says to, `applyAutoWithHints` otherwise**
- [ ] **Step 3: Update HUD to show "SEMI-AUTO" vs "AUTO" prefix based on which mode is active**
- [ ] **Step 4: Write test: each preset returns correct exposure strategy**
- [ ] **Step 5: Commit**

### Task 13: Temporal scene smoothing (hysteresis)

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`

**Problem:** Scene classification can flip between LANDSCAPE and INDOOR frame-to-frame, causing HUD flicker and settings thrashing.

**Solution:** Require 3 consecutive consistent classifications before changing the displayed scene type. Keep a small ring buffer of the last 5 classifications and only switch when the majority agrees.

- [ ] **Step 1: Add ring buffer and hysteresis logic to SceneClassifier**
- [ ] **Step 2: Write test: rapid scene changes are smoothed**
- [ ] **Step 3: Commit**

### Task 14: Front camera scene classification

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`

**Problem:** The classifier treats front camera same as rear. Front camera is almost always PORTRAIT/SELFIE context — it should bias toward face-relevant scene types and never classify as LANDSCAPE.

- [ ] **Step 1: When `isFrontCamera=true`, override non-face scene types to PORTRAIT if faces detected**
- [ ] **Step 2: Add SELFIE scene type variant with selfie-specific processing hints**
- [ ] **Step 3: Commit**

### Task 15: Face priority in auto exposure

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Problem:** AE metering is global. When faces are detected, exposure should be biased to correctly expose the face region, not the background.

**Solution:** Use Camera2's `CONTROL_AE_REGIONS` to set metering weight on detected face bounds. ML Kit provides face bounding boxes — convert to sensor-space coordinates and pass as AE regions with high weight.

- [ ] **Step 1: Add `applyFaceMetering(camera, faceRects)` method**
- [ ] **Step 2: Call from ViewModel when faceData has faces**
- [ ] **Step 3: Commit**

### Task 16: Separate camera shake from subject motion

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/MotionDetector.kt`

**Problem:** `MotionDetector` combines gyro data and frame-diff but doesn't distinguish the source. High gyro + high frame-diff = camera shake. Low gyro + high frame-diff = subject motion. High gyro + low frame-diff = intentional pan with static subject.

- [ ] **Step 1: Add `MotionSource` enum: CAMERA_SHAKE, SUBJECT_MOTION, PANNING, STABLE**
- [ ] **Step 2: Cross-reference gyro angular velocity with frame difference to classify**
- [ ] **Step 3: Expose `motionSource: MotionSource` alongside existing `motionType`**
- [ ] **Step 4: Commit**

### Task 17: Real-time horizon coaching from LevelSensor

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/hud/LevelIndicator.kt`

**Problem:** The review notes "Has LevelSensor data but never uses it for coaching." The LevelIndicator widget shows the level visually, but there is no coaching tip nudging the user to straighten the horizon. The pre-flight check (Task 30) only fires at capture time — users need persistent real-time feedback.

**Solution:** When roll angle > 2 degrees, generate a coaching hint: "Tilt [left/right] to level the horizon." When roll angle > 5 degrees, make the LevelIndicator glow amber as a warning. Suppress after 3 consecutive dismissals (user may want a dutch angle).

- [ ] **Step 1: Add horizon-based coaching generation in CoachingEngine**
- [ ] **Step 2: Add amber warning glow to LevelIndicator when tilted > 5 degrees**
- [ ] **Step 3: Add dismissal counter to suppress after 3 consecutive ignores**
- [ ] **Step 4: Commit**

### Task 18: Spatial composition coaching ("move subject left")

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CompositionAnalyzer.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`

**Problem:** The review says the app "can't say 'move the subject to the left third.'" The existing CompositionAnalyzer detects rule-of-thirds compliance but doesn't generate actionable spatial coaching.

**Solution:** Use the face bounding boxes (or saliency center when no faces) to determine where the subject IS. Compare against the nearest thirds/golden-ratio intersection. Generate directional coaching: "Move camera [left/right/up/down] to place subject on thirds."

- [ ] **Step 1: Add `subjectPositionRelativeToThirds(faceRects, w, h): CompositionSuggestion` to CompositionAnalyzer**

Return a data class with direction (LEFT, RIGHT, UP, DOWN, CENTERED) and distance from nearest thirds intersection.

- [ ] **Step 2: Generate coaching tips from CompositionSuggestion when distance > threshold**
- [ ] **Step 3: Write test: face in center generates "move to thirds" suggestion**
- [ ] **Step 4: Commit**

---

## Phase 6D: UI Polish (9 tasks)

*Professional look and feel — close the "feels amateur" gap*

### Task 19: Replace emoji icons with text labels

**Files:**
- Modify: `core/src/main/java/com/spectra/core/model/CameraPreset.kt`
- Modify: `core/src/main/java/com/spectra/core/model/FlashMode.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/controls/PresetSelector.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/controls/TopControlBar.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt`

**Problem:** Professional camera apps don't use emoji. Halide, Blackmagic, and Samsung's own Expert RAW use typography and minimal vector icons.

**Solution:** Replace emoji icons with uppercase monospace text abbreviations in the warm amber color scheme. e.g., "👤" -> "PORT", "🌙" -> "NGHT", "⚡" -> "FLSH".

- [ ] **Step 1: Update CameraPreset icon field from emoji to 3-4 char abbreviation**
- [ ] **Step 2: Update FlashMode icon**
- [ ] **Step 3: Update PresetSelector to render text in Spectra amber monospace instead of emoji**
- [ ] **Step 4: Remove emoji from AiExplainerOverlay**
- [ ] **Step 5: Commit**

### Task 20: Reduce presets from 16 to 8

**Files:**
- Modify: `core/src/main/java/com/spectra/core/model/CameraPreset.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/PresetEngine.kt`

**Problem:** 16 presets is overwhelming. Users face choice paralysis. Many presets overlap.

**Solution:** Keep: AUTO, PORTRAIT, NIGHT, LANDSCAPE, ACTION, FOOD, MACRO, PRO. Remove: ARCHITECTURE (merge into LANDSCAPE), PET (merge into AUTO), INDOOR (merge into AUTO), DOCUMENT, SUNSET (merge into LANDSCAPE), STREET, STUDIO, ASTRO.

- [ ] **Step 1: Remove 8 presets, update enum**
- [ ] **Step 2: Update PresetEngine to map removed presets to remaining ones**
- [ ] **Step 3: Commit**

### Task 21: Logarithmic PRO mode sliders

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/pro/ProModePanel.kt`

**Problem:** ISO and shutter speed sliders are linear. ISO 50->100 is as much screen distance as ISO 1600->1650, but the first is a full stop and the second is negligible. Photographers think in stops (logarithmic).

**Solution:** Apply log2 mapping to ISO and shutter speed sliders. Position 0.0 = min, 0.5 = geometric midpoint, 1.0 = max.

- [ ] **Step 1: Add `logSliderValue(value, min, max)` and `logSliderFromValue(position, min, max)` utilities**
- [ ] **Step 2: Apply to ISO slider (50-3200 -> log2 scale)**
- [ ] **Step 3: Apply to shutter speed slider**
- [ ] **Step 4: Commit**

### Task 22: Increase touch targets to 48dp minimum

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/controls/TopControlBar.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/controls/ModeSelector.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/controls/PresetSelector.kt`

**Problem:** Some control buttons are smaller than the Material Design minimum of 48dp, making them hard to tap, especially with cold/gloved hands.

- [ ] **Step 1: Audit all clickable elements, add `.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`**
- [ ] **Step 2: Commit**

### Task 23: Build overlay state machine (prevent overlay chaos)

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/OverlayPriority.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Problem:** Coaching, AI explainer, beauty overlay, review overlay can all appear simultaneously, covering the viewfinder.

**Solution:** Define overlay priority enum. Only one "informational" overlay active at a time. Priority: capture flash > review > coaching > AI explainer > beauty. Higher priority dismisses lower.

- [ ] **Step 1: Define OverlayPriority enum with ordered priorities**
- [ ] **Step 2: Add `activeOverlay: OverlayPriority?` to HudState**
- [ ] **Step 3: Gate overlay visibility in ViewfinderScreen on activeOverlay**
- [ ] **Step 4: Commit**

### Task 24: Fix AI explainer timing

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Problem:** AI explainer has a fixed 3-second auto-dismiss timer. If the user is reading the reasons list, it vanishes.

**Solution:**
- Show explainer 1 second after capture completes (not immediately)
- Auto-dismiss after 5 seconds OR when user taps to dismiss (whichever first)
- If user interacts with explainer (scrolling reasons), extend timer by 3 seconds

- [ ] **Step 1: Add 1s delay before showing, extend to 5s auto-dismiss**
- [ ] **Step 2: Commit**

### Task 25: Live histogram in auto mode

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/MiniHistogram.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`

**Problem:** The review lists "no histogram in auto mode" as amateur. Currently histogram only appears in PRO mode (`ProModePanel`). Auto mode users have no exposure feedback besides the viewfinder image.

**Solution:** Add a small toggleable mini-histogram (64x40dp) in the top-right corner of the viewfinder. Tap to toggle visibility. Compute from the same downsampled analysis frame already captured in `FrameAnalysisPipeline`. Uses luminance-only histogram (256 bins, normalized, rendered as a tiny Canvas path).

- [ ] **Step 1: Add `showMiniHistogram: Boolean` to HudState and `histogramData: IntArray?` field**
- [ ] **Step 2: Compute histogram from analysis pixels in FrameAnalysisPipeline, update HudState**
- [ ] **Step 3: Create MiniHistogram composable — Canvas draw of luminance distribution**
- [ ] **Step 4: Add to ViewfinderScreen with tap-to-toggle**
- [ ] **Step 5: Commit**

### Task 26: Before/after comparison slider in review

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/hud/ReviewOverlay.kt` (SmartReviewOverlay)

**Problem:** The review screen shows original and processed as two stacked halves. This is a binary comparison — the review calls it "amateur." Professional editing apps use a swipe-to-reveal slider.

**Solution:** Replace the top/bottom split with a single full-screen image where a vertical drag slider reveals original on the left and processed on the right. User drags the divider to compare.

- [ ] **Step 1: Create `ComparisonSlider` composable with draggable vertical divider**

Use `Modifier.pointerInput` to track horizontal drag. Clip the original image to the left of the divider, processed image to the right. Draw a thin amber line at the divider position.

- [ ] **Step 2: Replace top/bottom split in SmartReviewOverlay with ComparisonSlider**
- [ ] **Step 3: Commit**

### Task 27: Interactive coaching — "tap to apply" actions

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`
- Modify: `core/src/main/java/com/spectra/core/model/CoachingHint.kt` (add `action` field)
- Modify: `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Problem:** The review says coaching "talks but doesn't act." When coaching says "try the ultrawide lens," the user has to manually find and switch to it. There's no one-tap action.

**Solution:** Add an optional `CoachingAction` sealed class to `CoachingHint`. When present, the coaching UI shows a small "APPLY" button. Tapping it executes the action (switch lens, change preset, adjust setting).

Actions:
- `SwitchLens(lensId)` — switches to recommended lens
- `SwitchPreset(preset)` — switches to recommended preset
- `AdjustSetting(key, value)` — changes a specific setting

- [ ] **Step 1: Add `CoachingAction` sealed class and `action: CoachingAction?` field to CoachingHint**
- [ ] **Step 2: Generate actionable hints for lens recommendations and preset suggestions**
- [ ] **Step 3: Add "APPLY" button to CoachingDirective composable, wire to ViewModel**
- [ ] **Step 4: Commit**

---

## Phase 6E: Advanced Features (5 tasks)

*Differentiation and competitive parity*

### Task 28: TFLite scene classification model (MobileNetV3 + Places365)

**Files:**
- Create: `ai-engine/src/main/assets/scene_classifier.tflite`
- Modify: `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/SceneClassifierTest.kt`

**Problem:** The heuristic classifier runs on a 48x48 downsample analyzing pixel statistics. It can distinguish NIGHT from DAY but not FOOD from PET. A trained model at 224x224 can.

**Solution:** Bundle a quantized MobileNetV3-Large trained on Places365 (365 scene categories, ~3MB model). Map Places365 output to SPECTRA's SceneType enum. Use heuristic as fallback when model confidence is low.

**Depends on:** Task 13 (temporal smoothing should be in place so TFLite output is also smoothed)

- [ ] **Step 1: Download pre-trained MobileNetV3-Places365 and convert to TFLite int8 quantized**
- [ ] **Step 2: Create `TfLiteSceneClassifier` class wrapping the model**
- [ ] **Step 3: Add Places365->SceneType mapping table**
- [ ] **Step 4: Update SceneClassifier to try TFLite first, fall back to heuristic**
- [ ] **Step 5: Commit**

### Task 29: Multi-frame night mode with per-tile alignment

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt` (`averageFrames`)

**Problem:** Current night mode averages frames without alignment. Any camera or subject movement creates ghosting.

**Solution:** Before averaging, align each frame to the reference using per-tile cross-correlation. Divide each frame into 64x64 tiles, estimate per-tile shift at 1/4 resolution, apply shifts. This handles both global translation (camera shake) and local motion (tree branches, moving people) — a significant step beyond simple global phase correlation.

- [ ] **Step 1: Implement `estimateTileShifts(refPixels, framePixels, w, h, tileSize): Array<Pair<Int,Int>>`**

For each tile, compute cross-correlation on luminance at 1/4 resolution. Return per-tile (dx, dy) offset.

- [ ] **Step 2: Implement `applyTileShifts(pixels, shifts, w, h, tileSize): IntArray`**

Shift each tile independently with bilinear interpolation at tile boundaries.

- [ ] **Step 3: Integrate into averageFrames — align before accumulating**
- [ ] **Step 4: Write test: synthetic frame with known per-tile shifts is correctly aligned**
- [ ] **Step 5: Commit**

### Task 30: RAW (DNG) capture option

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Modify: `core/src/main/java/com/spectra/core/model/CameraSettings.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/pro/ProModePanel.kt`

**Problem:** Advanced users and professional photographers want RAW files for their own post-processing.

**Solution:** In PRO mode, add a "RAW" toggle. When enabled, capture uses `ImageFormat.RAW_SENSOR` and saves a DNG file alongside the JPEG (RAW+JPEG workflow). Skip all post-processing for the RAW file.

- [ ] **Step 1: Add `captureRaw` flag to CameraSettings**
- [ ] **Step 2: Implement DNG capture using Camera2's `DngCreator`**
- [ ] **Step 3: Add RAW toggle button to ProModePanel**
- [ ] **Step 4: Commit**

### Task 31: Focus stacking for macro mode

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Create: `camera/src/main/java/com/spectra/camera/FocusStacker.kt`

**Problem:** Macro photography has extremely shallow depth of field. A single focus distance can't keep the entire subject sharp.

**Solution:** In MACRO preset, capture 3-5 frames at different focus distances (using Camera2's `LENS_FOCUS_DISTANCE`). For each pixel, select the sharpest frame's value using Laplacian variance in a local window.

- [ ] **Step 1: Implement `FocusStacker.stack(frames, w, h): IntArray`**
- [ ] **Step 2: Wire into MACRO capture path**
- [ ] **Step 3: Commit**

### Task 32: Mixed lighting detection and per-region WB

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt`
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

**Problem:** A room with a window (5500K daylight) and a lamp (3200K tungsten) is one of the most common real-world scenarios. Current WB is global — it picks one temperature.

**Solution:** In post-processing, detect color temperature regions using a grid approach (divide image into 8x8 zones, estimate per-zone temperature). Apply WB correction as a graduated filter — cool zones get warmed, warm zones get cooled, meeting at a neutral point.

**Depends on:** Task 28 (TFLite model helps disambiguate colored objects from lighting differences)

- [ ] **Step 1: Implement zone-based color temperature estimation**
- [ ] **Step 2: Apply graduated WB correction in post-processing**
- [ ] **Step 3: Commit**

---

## Phase 6F: Performance & GPU Acceleration (4 tasks)

*Without this, full-res NR and HDR will be too slow for a camera app*

**Research finding:** RenderScript is **deprecated in Android 12** and **removed in Android 14+**. The replacement is **GLES 3.1 compute shaders**. S24 Ultra has Adreno 750 with GLES 3.2 support. Compute shaders achieve 30-50x speedup over CPU Kotlin for per-pixel operations. Use `HardwareBuffer` + `EGLImageKHR` for zero-copy camera→GPU path. CPU OpenCV JNI (Task 3) is the fallback for devices without GLES 3.1.

### Task 33: GLES 3.1 compute shader infrastructure

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/gpu/GpuContext.kt`
- Create: `camera/src/main/java/com/spectra/camera/gpu/ComputeShader.kt`
- Create: `camera/src/main/assets/shaders/bilateral.comp`

**Problem:** Full-resolution tiled bilateral NR (Task 3) runs on CPU via OpenCV JNI. For a 12MP image with 3 channels, even OpenCV takes ~100ms per channel. GPU compute can do the same in ~20ms total.

**Solution:** Create a reusable GLES 3.1 compute shader framework. `GpuContext` manages EGL context + `GLES31` init. `ComputeShader` compiles GLSL compute shaders, manages SSBOs (Shader Storage Buffer Objects), and dispatches work groups. First shader: bilateral filter.

- [ ] **Step 1: Create `GpuContext` — EGL initialization and lifecycle**

```kotlin
class GpuContext {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    
    fun init(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        // Request GLES 3.1 context
        // Return false if not supported (CPU fallback)
    }
    fun makeCurrent() { ... }
    fun release() { ... }
}
```

- [ ] **Step 2: Create `ComputeShader` — compile, bind SSBOs, dispatch**

```kotlin
class ComputeShader(source: String) {
    fun setBuffer(binding: Int, data: FloatArray) // Upload to SSBO
    fun setUniform(name: String, value: Any)
    fun dispatch(groupsX: Int, groupsY: Int, groupsZ: Int)
    fun readBuffer(binding: Int, output: FloatArray) // Download from SSBO
}
```

- [ ] **Step 3: Write `bilateral.comp` compute shader**

```glsl
#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) buffer Input { float inData[]; };
layout(std430, binding = 1) buffer Output { float outData[]; };
uniform int width; uniform int height;
uniform int spatialRadius; uniform float rangeSigma;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= width || pos.y >= height) return;
    int idx = pos.y * width + pos.x;
    float center = inData[idx];
    float wSum = 0.0, vSum = 0.0;
    float rangeSigmaSq2 = 2.0 * rangeSigma * rangeSigma;
    float spatialSigma = float(spatialRadius) / 2.0;
    float spatialSigmaSq2 = 2.0 * spatialSigma * spatialSigma;
    for (int ny = max(0, pos.y-spatialRadius); ny <= min(height-1, pos.y+spatialRadius); ny++) {
        for (int nx = max(0, pos.x-spatialRadius); nx <= min(width-1, pos.x+spatialRadius); nx++) {
            float nVal = inData[ny * width + nx];
            float dx = float(nx - pos.x), dy = float(ny - pos.y);
            float sw = exp(-(dx*dx + dy*dy) / spatialSigmaSq2);
            float rd = nVal - center;
            float rw = exp(-(rd*rd) / rangeSigmaSq2);
            float w = sw * rw;
            wSum += w; vSum += nVal * w;
        }
    }
    outData[idx] = wSum > 0.0 ? vSum / wSum : center;
}
```

- [ ] **Step 4: Update NoiseReducer to use GPU bilateral when available, OpenCV JNI fallback, Kotlin CPU as last resort**
- [ ] **Step 5: Benchmark: verify <100ms for 12MP NR on S24 Ultra GPU**
- [ ] **Step 6: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/gpu/ camera/src/main/assets/shaders/ camera/src/main/java/com/spectra/camera/NoiseReducer.kt
git commit -m "feat: GLES 3.1 compute shader bilateral NR, 30-50x speedup"
```

### Task 34: GLES compute shader for HDR Mertens fusion

**Files:**
- Create: `camera/src/main/assets/shaders/mertens_fusion.comp`
- Modify: `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`

**Problem:** Mertens fusion with Laplacian weighting (Task 8) processes every pixel across 3 frames with weight computation. CPU-bound for 12MP images: ~2-3 seconds.

**Solution:** Port Mertens weight computation and per-pixel fusion to a GLES 3.1 compute shader. Each pixel computes its own exposure/contrast/saturation weights and blends independently — perfect for GPU parallelism. Use the `ComputeShader` infrastructure from Task 33.

- [ ] **Step 1: Write `mertens_fusion.comp` — weights + blend in one dispatch**
- [ ] **Step 2: Wire into HdrProcessor with SSBO for 3 input frames + 1 output**
- [ ] **Step 3: Benchmark: verify <200ms for 3-frame HDR at 12MP**
- [ ] **Step 4: Commit**

```bash
git add camera/src/main/assets/shaders/mertens_fusion.comp camera/src/main/java/com/spectra/camera/HdrProcessor.kt
git commit -m "feat: GLES 3.1 compute shader HDR fusion, <200ms at 12MP"
```

### Task 35: HardwareBuffer zero-copy camera→GPU path

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/gpu/GpuContext.kt`
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

**Problem:** Current pipeline: camera → Bitmap → IntArray → FloatArray → GPU SSBO → FloatArray → IntArray → Bitmap. Three unnecessary CPU copies before GPU processing even starts.

**Research finding:** `HardwareBuffer` + `EGLImageKHR` enables zero-copy camera-to-GPU: the camera writes directly to a GPU-accessible buffer. On S24 Ultra (Adreno 750), this eliminates ~15ms of copy overhead per frame and reduces memory pressure.

**Solution:** Use `ImageReader` with `HardwareBuffer` format. Wrap `HardwareBuffer` as an `EGLImageKHR` via `eglCreateImageKHR`. Bind as SSBO input to compute shaders. Process entirely on GPU. Copy back to CPU only for final JPEG encoding.

- [ ] **Step 1: Add HardwareBuffer support to GpuContext**
- [ ] **Step 2: Wire camera capture to use HardwareBuffer-backed ImageReader**
- [ ] **Step 3: Benchmark end-to-end pipeline: camera → GPU → JPEG**
- [ ] **Step 4: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/gpu/ camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat: zero-copy camera→GPU via HardwareBuffer+EGLImageKHR"
```

### Task 36: Processing pipeline timing and budget

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

**Problem:** The post-processing pipeline has no timing instrumentation. We don't know how long each stage takes or where to optimize next.

**Solution:** Add `System.nanoTime()` timing around each processing stage (NR, tone curve, sharpening, beauty, bokeh, HDR). Log timing breakdown. If total processing exceeds 2 seconds, skip the lowest-priority operation (beauty > vignette > highlight rolloff).

- [ ] **Step 1: Add timing instrumentation to each stage in `applyPostProcess`**
- [ ] **Step 2: Add adaptive quality budget — skip low-priority stages when over 2s**
- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat: pipeline timing instrumentation and adaptive quality budget"
```

---

## Phase 6G: Premium Differentiators (5 tasks)

*Features that make SPECTRA genuinely unique*

### Task 37: "Golden Moment" auto-capture

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/GoldenMomentDetector.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Concept:** When lighting is good, composition follows rule of thirds, faces are smiling with open eyes, and camera is stable — automatically fire the shutter. User enables this with a toggle and just has to frame the shot.

**Implementation:** Score each frame on: face expression (smile confidence from ML Kit), composition (thirds alignment), stability (gyro steady for >0.5s), exposure (histogram centered). When composite score exceeds threshold, auto-capture.

- [ ] **Step 1: Implement GoldenMomentDetector with composite scoring**
- [ ] **Step 2: Add UI toggle and haptic/audio feedback on auto-capture**
- [ ] **Step 3: Commit**

### Task 38: Light direction indicator

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/LightDirectionIndicator.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt`

**Concept:** Show a small directional arrow on the viewfinder indicating where the primary light source is relative to the subject. Helps users position subjects for flattering light.

**Implementation:** Analyze brightness gradient across the frame. The direction of the gradient vector points toward the light source. Display as a subtle compass-style indicator.

- [ ] **Step 1: Compute brightness gradient direction from analysis frame**
- [ ] **Step 2: Create minimal Compose UI showing light direction**
- [ ] **Step 3: Commit**

### Task 39: "Before you shoot" pre-flight check

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/hud/PreflightCheck.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

**Concept:** One-second scan before capture showing: ✓ level ✓ faces sharp ✓ composition balanced ✓ exposure good. Appears as a subtle check/warning bar above the shutter button.

**Implementation:** Aggregate existing signals: LevelSensor (horizon), face sharpness score, composition analysis, exposure histogram. Show green checkmarks for good, amber warnings for issues.

- [ ] **Step 1: Create PreflightCheck composable**
- [ ] **Step 2: Wire to existing analysis signals**
- [ ] **Step 3: Commit**

### Task 40: Shot suggestions based on scene

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/ShotSuggestionEngine.kt`

**Concept:** When the AI detects a scene type with high confidence, suggest specific shot compositions. For LANDSCAPE: "Try placing the horizon on the lower third." For PORTRAIT: "Move slightly right for more flattering light angle."

**Implementation:** Scene-specific suggestion banks that consider current composition analysis, light direction, and subject position to generate contextual one-liner suggestions.

**Depends on:** Task 18 (spatial composition analysis provides the context data)

- [ ] **Step 1: Build suggestion bank per scene type**
- [ ] **Step 2: Context-filter suggestions based on current frame analysis**
- [ ] **Step 3: Display via coaching system**
- [ ] **Step 4: Commit**

### Task 41: Adaptive noise ceiling per scene type

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/DecisionEngine.kt`
- Modify: `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt`

**Concept:** Set maximum acceptable ISO per scene type. LANDSCAPE: ISO 200 max (tripod expected, prioritize quality). PORTRAIT: ISO 800 max (need fast shutter for faces, accept some noise). ACTION: ISO 3200 max (shutter speed is king). NIGHT: ISO 1600 max (rely on multi-frame averaging instead).

**Implementation:** When using semi-auto mode (Task 12), enforce ISO ceiling by adjusting shutter speed down instead of ISO up, within reasonable bounds.

**Depends on:** Task 12 (semi-auto must be in place for ceiling to be enforced)

- [ ] **Step 1: Define ISO ceiling per SceneType**
- [ ] **Step 2: Enforce in applySemiAuto via clamping**
- [ ] **Step 3: Commit**

---

## Phase 6H: Future Roadmap (not scored — acknowledged gaps)

These items are recognized gaps from the review but are either out of current scope, require significant infrastructure, or depend on video mode development:

1. **Cinematic rack focus** (video mode) — smooth focus transitions between two tap-selected points. Requires video capture pipeline, which is currently not implemented.
2. **"Teach me" pedagogical mode** — real-time explanations of WHY each AI decision was made (not just what it did). Extends coaching into an educational tool.
3. **Semantic depth masking** — ML Kit segmentation to understand WHAT objects are (person vs. coffee cup vs. background) for intelligent selective focus beyond geometric depth.
4. **Exposure lock-then-recompose guide** — visual indicator of safe recompose range after AE/AF lock.
5. **Neural noise reduction** — ML-based denoiser trained on paired noisy/clean images, replacing bilateral filter entirely. Requires training pipeline and model optimization.
6. **Device-specific calibration profiles** — per-device exposure tuning, color correction, and lens aberration data.
7. **User onboarding / feature discovery** — first-run experience explaining AI features, coaching, PRO mode.

---

## Score Projection (Adjusted)

| Phase | Focus | Tasks | Optimistic | Conservative | Notes |
|-------|-------|-------|-----------|-------------|-------|
| 6A | Stability & OOM guards | 2 | 6.5 | 6.5 | Done — crashes fixed |
| 6B | Processing quality | 8 | 7.5 | 7.0 | Laplacian pyramid + frequency separation are complex |
| 6C | Camera intelligence | 8 | 8.5 | 7.8 | Semi-auto tuning is hard |
| 6D | UI polish | 9 | 9.0 | 8.3 | Low-risk, well-defined |
| 6E | Advanced features | 5 | 9.3 | 8.7 | TFLite mapping is non-trivial |
| 6F | Performance / GPU | 4 | 9.6 | 9.1 | GLES 3.1 compute + zero-copy pipeline |
| 6G | Premium differentiators | 5 | 9.8 | 9.3 | Unique but not quality-fundamental |

**Total: 41 tasks across 8 phases.**

**Realistic final: 9.0-9.5/10** — competitive with Halide and Expert RAW. The remaining 0.5-1.0 gap to iPhone/Pixel is Apple/Google's proprietary ISP + ML pipelines, which can't be fully matched with Camera2 + software processing. The acknowledged Phase 6H items (neural NR, semantic depth, device calibration) would close this gap further.

---

## Dependencies (Updated)

**Phase ordering:**
- 6A must complete before 6B (stability before features)
- 6B should complete before 6F (need CPU implementations before GPU acceleration)
- 6C Task 11 before Task 12 (display actual values before applying them)
- 6C Task 13 before 6E Task 28 (temporal smoothing before TFLite — so model output is also smoothed)
- 6E Task 28 before Task 32 (TFLite model improves mixed lighting disambiguation)

**Within-phase dependencies:**
- Task 4 (double sharpening) before Task 8 (HDR refinement) and Task 9 (sharpening improvement) — same code paths
- Task 6 (NATURAL identity) before Task 7 (3D LUTs) — baseline must be defined first
- Task 12 (semi-auto) before Task 41 (ISO ceiling) — ceiling enforces in semi-auto path
- Task 18 (spatial composition) before Task 40 (shot suggestions) — suggestions need composition context
- Task 33 (GPU infra) before Task 34 (GPU HDR) and Task 35 (zero-copy) — shader framework required first

**Independent tasks (can start anytime):**
- Task 3 (tiled NR), Task 5 (highlight rolloff), Task 10 (beauty), Task 19 (emoji removal), Task 20 (preset reduction), Task 25 (histogram), Task 26 (comparison slider)
