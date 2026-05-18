# SPECTRA Phase 5C — Intelligence Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 5 P2 intelligence improvements: composition analysis with horizon coaching, preset consolidation (16 to 8), mixed lighting detection, ProcessingParams wiring + actionable coaching, and local tone mapping. Target: 8/10 to 8.5/10.

**Architecture:** All changes maintain the 4-module (:core, :camera, :ai-engine, :app) Hilt DI architecture. The ai-engine module gains a CompositionAnalyzer for saliency-based composition scoring and actionable coaching actions. The core module consolidates CameraPreset from 16 entries to 8. The camera module replaces the global Reinhard tone mapper with bilateral filter decomposition. ProcessingParams finally flows from PresetEngine through CaptureManager into actual post-processing.

**Tech Stack:** Kotlin, Jetpack Compose, saliency detection, bilateral filter decomposition

---

## File Structure

### New files:
- `ai-engine/src/main/java/com/spectra/ai/CompositionAnalyzer.kt` — saliency map + rule-of-thirds scoring + horizon coaching
- `ai-engine/src/main/java/com/spectra/ai/model/CoachingAction.kt` — sealed class for actionable coaching hints
- `ai-engine/src/main/java/com/spectra/ai/model/CompositionResult.kt` — data class for composition analysis results
- `ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt`
- `ai-engine/src/test/java/com/spectra/ai/model/CoachingActionTest.kt`
- `ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt`
- `ai-engine/src/test/java/com/spectra/ai/PresetConsolidationTest.kt`
- `camera/src/test/java/com/spectra/camera/LocalToneMappingTest.kt`
- `camera/src/test/java/com/spectra/camera/ProcessingParamsWiringTest.kt`

### Modified files:
- `core/src/main/java/com/spectra/core/model/CameraPreset.kt` — consolidate from 16 to 8 entries
- `core/src/main/java/com/spectra/core/model/HudState.kt` — add coachingAction field
- `ai-engine/src/main/java/com/spectra/ai/model/LightingCondition.kt` — add MIXED enum entry
- `ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt` — add action field
- `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt` — composition coaching, mixed lighting coaching, actionable hints
- `ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt` — add detectMixedLighting() method
- `ai-engine/src/main/java/com/spectra/ai/PresetEngine.kt` — merge settings for 8 presets
- `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt` — run composition analysis, pass pixels to mixed lighting
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt` — wire ProcessingParams to applyPostProcess, replace applyHdrToneMap with bilateral decomposition
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt` — pass ProcessingParams from preset profile to capture
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt` — wire preset processing params, handle coaching actions
- `app/src/main/java/com/spectra/app/ui/controls/PresetSelector.kt` — update for 8 presets (auto-adapts via CameraPreset.entries)
- `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt` — render action button for actionable hints

---

### Task 1: Create CompositionResult Data Model

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/model/CompositionResult.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt` (partial — model tests)

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt
package com.spectra.ai

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CompositionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionAnalyzerTest {

    @Test
    fun `CompositionResult defaults to centered subject with no suggestion`() {
        val result = CompositionResult()
        assertEquals(0.5f, result.subjectCentroidX, 0.01f)
        assertEquals(0.5f, result.subjectCentroidY, 0.01f)
        assertEquals(0f, result.thirdsScore, 0.01f)
        assertNull(result.suggestionText)
        assertEquals(ArrowDirection.NONE, result.suggestionArrow)
        assertEquals(0f, result.horizonTiltDegrees, 0.01f)
        assertFalse(result.needsLeveling)
    }

    @Test
    fun `CompositionResult needsLeveling when tilt exceeds 2 degrees`() {
        val result = CompositionResult(horizonTiltDegrees = 3.5f)
        assertTrue(result.needsLeveling)
    }

    @Test
    fun `CompositionResult does not need leveling when tilt under 2 degrees`() {
        val result = CompositionResult(horizonTiltDegrees = 1.5f)
        assertFalse(result.needsLeveling)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CompositionAnalyzerTest" 2>&1 | tail -5`
Expected: FAIL — `CompositionResult` does not exist

- [ ] **Step 3: Create CompositionResult data class**

```kotlin
// ai-engine/src/main/java/com/spectra/ai/model/CompositionResult.kt
package com.spectra.ai.model

import kotlin.math.abs

data class CompositionResult(
    val subjectCentroidX: Float = 0.5f,
    val subjectCentroidY: Float = 0.5f,
    val thirdsScore: Float = 0f,
    val suggestionText: String? = null,
    val suggestionArrow: ArrowDirection = ArrowDirection.NONE,
    val horizonTiltDegrees: Float = 0f
) {
    val needsLeveling: Boolean get() = abs(horizonTiltDegrees) > 2f
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CompositionAnalyzerTest" 2>&1 | tail -5`
Expected: PASS — all 3 tests pass

- [ ] **Step 5: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/model/CompositionResult.kt ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt
git commit -m "Add CompositionResult data model for composition analysis"
```

---

### Task 2: Implement CompositionAnalyzer with Saliency and Thirds Scoring

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/CompositionAnalyzer.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt` (add analyzer tests)

- [ ] **Step 1: Write the failing tests**

Append to `ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt`:

```kotlin
    // --- CompositionAnalyzer logic tests ---

    private val analyzer = CompositionAnalyzer()

    @Test
    fun `saliency map returns 64x64 array`() {
        // Create a 128x128 image with a bright spot at top-left
        val pixels = IntArray(128 * 128) { 0xFF202020.toInt() }
        // Place bright region at (10-30, 10-30)
        for (y in 10..30) {
            for (x in 10..30) {
                pixels[y * 128 + x] = 0xFFFFFFFF.toInt()
            }
        }
        val saliency = analyzer.computeSaliencyMap(pixels, 128, 128)
        assertEquals(64 * 64, saliency.size)
        // The bright region should have higher saliency than background
        val brightRegionSaliency = saliency[8 * 64 + 8] // approximately (16,16) -> (8,8) in 64x64
        val bgSaliency = saliency[50 * 64 + 50]
        assertTrue("Bright region should be more salient", brightRegionSaliency > bgSaliency)
    }

    @Test
    fun `subject on thirds intersection scores high`() {
        // Subject at (1/3, 1/3) — perfect rule of thirds
        val result = analyzer.scoreThirdsPlacement(1f / 3f, 1f / 3f)
        assertTrue("Thirds score should be > 0.8 for perfect placement", result >= 0.8f)
    }

    @Test
    fun `subject dead center scores low`() {
        val result = analyzer.scoreThirdsPlacement(0.5f, 0.5f)
        assertTrue("Center placement should score < 0.5", result < 0.5f)
    }

    @Test
    fun `analyze with face data uses face centroid`() {
        // Face rect centered at (0.33, 0.33) — on thirds
        val faceRect = android.graphics.RectF(0.23f, 0.23f, 0.43f, 0.43f)
        val pixels = IntArray(64 * 64) { 0xFF808080.toInt() }
        val result = analyzer.analyze(pixels, 64, 64, listOf(faceRect), 0f)
        // Subject centroid should be near the face center
        assertEquals(0.33f, result.subjectCentroidX, 0.05f)
        assertEquals(0.33f, result.subjectCentroidY, 0.05f)
    }

    @Test
    fun `analyze generates leveling hint when horizon tilted`() {
        val pixels = IntArray(64 * 64) { 0xFF808080.toInt() }
        val result = analyzer.analyze(pixels, 64, 64, emptyList(), 4.5f)
        assertTrue(result.needsLeveling)
        assertTrue(result.suggestionText?.contains("horizon") == true || result.suggestionText?.contains("level") == true)
    }

    @Test
    fun `analyze generates move hint for off-thirds subject`() {
        // Create image with bright spot at center (not on thirds)
        val pixels = IntArray(128 * 128) { 0xFF101010.toInt() }
        for (y in 55..73) {
            for (x in 55..73) {
                pixels[y * 128 + x] = 0xFFFFFFFF.toInt()
            }
        }
        val result = analyzer.analyze(pixels, 128, 128, emptyList(), 0f)
        // Subject is centered, should suggest moving off-center
        assertFalse(result.suggestionText.isNullOrEmpty())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CompositionAnalyzerTest" 2>&1 | tail -5`
Expected: FAIL — `CompositionAnalyzer` does not exist

- [ ] **Step 3: Create CompositionAnalyzer**

```kotlin
// ai-engine/src/main/java/com/spectra/ai/CompositionAnalyzer.kt
package com.spectra.ai

import android.graphics.RectF
import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CompositionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class CompositionAnalyzer @Inject constructor() {

    companion object {
        private const val SALIENCY_SIZE = 64
        private const val THIRDS_THRESHOLD = 0.15f // 15% of frame width
    }

    /**
     * Analyze composition using face bounds or saliency map.
     * @param pixels image pixel array (ARGB)
     * @param width image width
     * @param height image height
     * @param faceRects normalized face rects (0..1 coordinates)
     * @param rollAngleDegrees current horizon tilt from LevelSensor
     */
    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        faceRects: List<RectF>,
        rollAngleDegrees: Float
    ): CompositionResult {
        // Determine subject centroid
        val (cx, cy) = if (faceRects.isNotEmpty()) {
            // Use primary (largest) face center
            val primary = faceRects.maxBy { it.width() * it.height() }
            val faceCx = (primary.left + primary.right) / 2f
            val faceCy = (primary.top + primary.bottom) / 2f
            Pair(faceCx, faceCy)
        } else {
            // Compute saliency map and find peak
            val saliency = computeSaliencyMap(pixels, width, height)
            findSaliencyPeak(saliency, SALIENCY_SIZE, SALIENCY_SIZE)
        }

        val thirdsScore = scoreThirdsPlacement(cx, cy)
        val horizonTilt = rollAngleDegrees

        // Generate suggestion
        val (text, arrow) = generateSuggestion(cx, cy, thirdsScore, horizonTilt)

        return CompositionResult(
            subjectCentroidX = cx,
            subjectCentroidY = cy,
            thirdsScore = thirdsScore,
            suggestionText = text,
            suggestionArrow = arrow,
            horizonTiltDegrees = horizonTilt
        )
    }

    /**
     * Compute center-surround contrast saliency on a 64x64 downsample.
     * Uses luminance difference between a pixel and its surrounding ring at 3 scales.
     */
    fun computeSaliencyMap(pixels: IntArray, width: Int, height: Int): FloatArray {
        val s = SALIENCY_SIZE
        val saliency = FloatArray(s * s)

        // Downsample to 64x64 luminance
        val lum = FloatArray(s * s)
        val scaleX = width.toFloat() / s
        val scaleY = height.toFloat() / s
        for (sy in 0 until s) {
            for (sx in 0 until s) {
                val srcX = (sx * scaleX).toInt().coerceIn(0, width - 1)
                val srcY = (sy * scaleY).toInt().coerceIn(0, height - 1)
                val pixel = pixels[srcY * width + srcX]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                lum[sy * s + sx] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        // Center-surround at 3 scales (radii 2, 4, 8)
        val scales = intArrayOf(2, 4, 8)
        for (y in 0 until s) {
            for (x in 0 until s) {
                val centerLum = lum[y * s + x]
                var totalContrast = 0f
                for (radius in scales) {
                    var surroundSum = 0f
                    var surroundCount = 0
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            if (dx == 0 && dy == 0) continue
                            if (abs(dx) < radius / 2 && abs(dy) < radius / 2) continue // ring only
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until s && ny in 0 until s) {
                                surroundSum += lum[ny * s + nx]
                                surroundCount++
                            }
                        }
                    }
                    if (surroundCount > 0) {
                        val surroundMean = surroundSum / surroundCount
                        totalContrast += abs(centerLum - surroundMean)
                    }
                }
                saliency[y * s + x] = totalContrast / scales.size
            }
        }

        return saliency
    }

    /**
     * Find the peak of the saliency map, returns normalized (0..1) coordinates.
     */
    private fun findSaliencyPeak(saliency: FloatArray, w: Int, h: Int): Pair<Float, Float> {
        var maxVal = 0f
        var maxIdx = saliency.size / 2 // default center
        for (i in saliency.indices) {
            if (saliency[i] > maxVal) {
                maxVal = saliency[i]
                maxIdx = i
            }
        }
        val x = (maxIdx % w + 0.5f) / w
        val y = (maxIdx / w + 0.5f) / h
        return Pair(x, y)
    }

    /**
     * Score how close the subject centroid is to the nearest rule-of-thirds intersection.
     * Returns 0..1 where 1 = perfect thirds placement.
     */
    fun scoreThirdsPlacement(cx: Float, cy: Float): Float {
        val thirds = listOf(
            Pair(1f / 3f, 1f / 3f),
            Pair(2f / 3f, 1f / 3f),
            Pair(1f / 3f, 2f / 3f),
            Pair(2f / 3f, 2f / 3f)
        )
        var minDist = Float.MAX_VALUE
        for ((tx, ty) in thirds) {
            val dx = cx - tx
            val dy = cy - ty
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < minDist) minDist = dist
        }
        // Max possible distance from any thirds point is ~0.47 (corner to center of thirds)
        // Map distance to score: 0 distance = 1.0, THIRDS_THRESHOLD distance = 0.5
        val score = (1f - minDist / 0.47f).coerceIn(0f, 1f)
        return score
    }

    /**
     * Generate composition coaching suggestion based on analysis.
     */
    private fun generateSuggestion(
        cx: Float,
        cy: Float,
        thirdsScore: Float,
        horizonTilt: Float
    ): Pair<String?, ArrowDirection> {
        // Horizon leveling takes priority
        if (abs(horizonTilt) > 2f) {
            val direction = if (horizonTilt > 0) "right" else "left"
            val degrees = "%.0f".format(abs(horizonTilt))
            return Pair(
                "Level your horizon — tilted $degrees° $direction",
                ArrowDirection.STEADY
            )
        }

        // Check if subject is close enough to thirds
        if (thirdsScore >= 0.8f) {
            return Pair(null, ArrowDirection.NONE) // Good composition, no hint needed
        }

        // Subject is centered — suggest off-center placement
        val isCenteredX = abs(cx - 0.5f) < 0.08f
        val isCenteredY = abs(cy - 0.5f) < 0.08f
        if (isCenteredX && isCenteredY) {
            return Pair(
                "Subject is centered — try placing them off-center",
                ArrowDirection.NONE
            )
        }

        // Subject is off-thirds — suggest direction to move
        val thirds = listOf(
            Pair(1f / 3f, 1f / 3f),
            Pair(2f / 3f, 1f / 3f),
            Pair(1f / 3f, 2f / 3f),
            Pair(2f / 3f, 2f / 3f)
        )
        var nearestThirdsX = 1f / 3f
        var nearestThirdsY = 1f / 3f
        var minDist = Float.MAX_VALUE
        for ((tx, ty) in thirds) {
            val dist = sqrt((cx - tx) * (cx - tx) + (cy - ty) * (cy - ty))
            if (dist < minDist) {
                minDist = dist
                nearestThirdsX = tx
                nearestThirdsY = ty
            }
        }

        // Only suggest if distance exceeds threshold
        if (minDist <= THIRDS_THRESHOLD) {
            return Pair(null, ArrowDirection.NONE)
        }

        val dx = nearestThirdsX - cx
        val dy = nearestThirdsY - cy

        val arrow = when {
            abs(dx) > abs(dy) && dx > 0 -> ArrowDirection.RIGHT
            abs(dx) > abs(dy) && dx < 0 -> ArrowDirection.LEFT
            dy > 0 -> ArrowDirection.DOWN
            dy < 0 -> ArrowDirection.UP
            else -> ArrowDirection.NONE
        }

        val directionText = when (arrow) {
            ArrowDirection.LEFT -> "left"
            ArrowDirection.RIGHT -> "right"
            ArrowDirection.UP -> "up"
            ArrowDirection.DOWN -> "down"
            else -> "off-center"
        }

        return Pair("Move subject slightly $directionText", arrow)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CompositionAnalyzerTest" 2>&1 | tail -5`
Expected: PASS — all 9 tests pass

- [ ] **Step 5: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/CompositionAnalyzer.kt ai-engine/src/test/java/com/spectra/ai/CompositionAnalyzerTest.kt
git commit -m "Add CompositionAnalyzer with saliency map and rule-of-thirds scoring"
```

---

### Task 3: Wire Composition Analysis into FrameAnalysisPipeline and CoachingEngine

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`

- [ ] **Step 1: Add CompositionAnalyzer to FrameAnalysisPipeline constructor**

In `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`, add the import and constructor parameter:

```kotlin
import com.spectra.ai.model.CompositionResult
```

Add to the constructor after `coachingEngine`:

```kotlin
    private val compositionAnalyzer: CompositionAnalyzer,
```

Add a new StateFlow for composition results after the `_coachingHint` declaration:

```kotlin
    private val _compositionResult = MutableStateFlow(CompositionResult())
    val compositionResult: StateFlow<CompositionResult> = _compositionResult.asStateFlow()
```

- [ ] **Step 2: Run composition analysis in analyzeFrame()**

In the `analyzeFrame()` method, after the motion detection block (`val motion = motionDetector.currentMotion`) and before building `sceneAnalysis`, add:

```kotlin
        // Composition analysis
        val faceRects = if (currentFaceData.hasFaces) {
            currentFaceData.faces.map { it.bounds }
        } else {
            emptyList()
        }
        val compositionResult = compositionAnalyzer.analyze(
            pixels, bitmap.width, bitmap.height,
            faceRects, 0f // rollAngle injected below
        )
        _compositionResult.value = compositionResult
```

- [ ] **Step 3: Pass composition result to coaching engine**

Change the coaching generation call from:

```kotlin
            _coachingHint.value = coachingEngine.generateCoaching(sceneAnalysis, preset)
```

to:

```kotlin
            _coachingHint.value = coachingEngine.generateCoaching(sceneAnalysis, preset, compositionResult)
```

- [ ] **Step 4: Update CoachingEngine.generateCoaching() signature**

In `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`, add the import:

```kotlin
import com.spectra.ai.model.CompositionResult
```

Change the `generateCoaching` signature from:

```kotlin
    fun generateCoaching(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint? {
```

to:

```kotlin
    fun generateCoaching(analysis: SceneAnalysis, preset: CameraPreset, composition: CompositionResult? = null): CoachingHint? {
```

After the existing `when` block that generates `hint`, add composition coaching before returning:

```kotlin
        // Composition/horizon coaching (lower priority than motion/backlit hints)
        if (hint == null && composition != null) {
            hint = compositionHint(composition)
        }
```

Change `val hint = when {` to `var hint = when {`.

- [ ] **Step 5: Add compositionHint method to CoachingEngine**

Add this private method at the end of `CoachingEngine`:

```kotlin
    private fun compositionHint(composition: CompositionResult): CoachingHint? {
        if (composition.needsLeveling && composition.suggestionText != null) {
            return CoachingHint(composition.suggestionText, composition.suggestionArrow, priority = 6)
        }
        if (composition.thirdsScore < 0.5f && composition.suggestionText != null) {
            return CoachingHint(composition.suggestionText, composition.suggestionArrow, priority = 3)
        }
        return null
    }
```

- [ ] **Step 6: Run existing coaching tests to verify no regressions**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CoachingEngineTest" 2>&1 | tail -5`
Expected: PASS — existing tests should pass because `composition` parameter defaults to null

- [ ] **Step 7: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt
git commit -m "Wire CompositionAnalyzer into pipeline and coaching engine"
```

---

### Task 4: Add MIXED to LightingCondition Enum

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/model/LightingCondition.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt
package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MixedLightingTest {

    @Test
    fun `MIXED lighting condition exists`() {
        val mixed = LightingCondition.MIXED
        assertNotNull(mixed)
        assertEquals("MIXED", mixed.label)
    }

    @Test
    fun `MIXED is distinct from other conditions`() {
        val conditions = LightingCondition.entries
        val mixedCount = conditions.count { it == LightingCondition.MIXED }
        assertEquals(1, mixedCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MixedLightingTest" 2>&1 | tail -5`
Expected: FAIL — `MIXED` does not exist in `LightingCondition`

- [ ] **Step 3: Add MIXED to LightingCondition enum**

In `ai-engine/src/main/java/com/spectra/ai/model/LightingCondition.kt`, add `MIXED` before `UNKNOWN`:

```kotlin
enum class LightingCondition(val label: String) {
    GOLDEN_HOUR("GOLDEN HOUR"),
    BLUE_HOUR("BLUE HOUR"),
    BRIGHT_DAYLIGHT("DAYLIGHT"),
    OVERCAST("OVERCAST"),
    HARSH_MIDDAY("HARSH LIGHT"),
    BACKLIT("BACKLIT"),
    LOW_LIGHT("LOW LIGHT"),
    ARTIFICIAL("ARTIFICIAL"),
    STUDIO("STUDIO"),
    MIXED("MIXED"),
    UNKNOWN("—");
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MixedLightingTest" 2>&1 | tail -5`
Expected: PASS — both tests pass

- [ ] **Step 5: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/model/LightingCondition.kt ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt
git commit -m "Add MIXED lighting condition for mixed lighting detection"
```

---

### Task 5: Implement Mixed Lighting Detection in LightingAnalyzer

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt` (extend)

- [ ] **Step 1: Write the failing tests**

Append to `ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt`:

```kotlin
    private val analyzer = LightingAnalyzer()

    @Test
    fun `detectMixedLighting returns false for uniform color temperature`() {
        // 4x4 grid of pixels all at ~5500K (neutral white)
        // R/B ratio ~1.0 -> ~5800K for all regions
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFF808080.toInt() } // uniform gray
        val result = analyzer.detectMixedLighting(pixels, w, h)
        assertEquals(false, result.isMixed)
    }

    @Test
    fun `detectMixedLighting returns true for mixed warm and cool regions`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h)
        // Top half: warm (high R, low B) -> ~3200K
        for (y in 0 until h / 2) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (200 shl 16) or (140 shl 8) or 80
            }
        }
        // Bottom half: cool (low R, high B) -> ~8000K
        for (y in h / 2 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (80 shl 16) or (140 shl 8) or 210
            }
        }
        val result = analyzer.detectMixedLighting(pixels, w, h)
        assertEquals(true, result.isMixed)
        // Variance should exceed 1500K
        assert(result.ctVariance > 1500f) { "CT variance ${result.ctVariance} should exceed 1500" }
    }

    @Test
    fun `detectMixedLighting returns subject region CT`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h)
        // Top half warm, bottom half cool
        for (y in 0 until h / 2) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (200 shl 16) or (140 shl 8) or 80
            }
        }
        for (y in h / 2 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (80 shl 16) or (140 shl 8) or 210
            }
        }
        // Subject rect in top half (warm region)
        val subjectRect = android.graphics.RectF(0.25f, 0.1f, 0.75f, 0.4f)
        val result = analyzer.detectMixedLighting(pixels, w, h, subjectRect)
        // Subject CT should be warm (< 5000K)
        assert(result.subjectCt < 5000) { "Subject CT ${result.subjectCt} should be warm" }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MixedLightingTest" 2>&1 | tail -5`
Expected: FAIL — `detectMixedLighting` does not exist

- [ ] **Step 3: Add MixedLightingResult data class and detectMixedLighting method**

Add the data class at the top of `LightingAnalyzer.kt` (after the package and imports):

```kotlin
import android.graphics.RectF
```

Add just before the `LightingAnalyzer` class:

```kotlin
data class MixedLightingResult(
    val isMixed: Boolean = false,
    val ctVariance: Float = 0f,
    val regionCts: List<Int> = emptyList(),
    val subjectCt: Int = 5500
)
```

Add this method to `LightingAnalyzer`, after the `estimateColorTemperature` method:

```kotlin
    /**
     * Divide frame into a 4x4 grid and estimate color temperature per region.
     * If variance exceeds 1500K, flag as mixed lighting.
     */
    fun detectMixedLighting(
        pixels: IntArray,
        width: Int,
        height: Int,
        subjectRect: RectF? = null
    ): MixedLightingResult {
        if (pixels.isEmpty() || width == 0 || height == 0) {
            return MixedLightingResult()
        }

        val gridSize = 4
        val regionCts = mutableListOf<Int>()
        val cellW = width / gridSize
        val cellH = height / gridSize

        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val startX = gx * cellW
                val startY = gy * cellH
                val endX = minOf(startX + cellW, width)
                val endY = minOf(startY + cellH, height)

                var totalR = 0L
                var totalB = 0L
                var count = 0
                val step = maxOf(1, (endX - startX) * (endY - startY) / 500)
                var idx = 0
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        idx++
                        if (idx % step != 0) continue
                        val pixel = pixels[y * width + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        if (lum < 30 || lum > 240) continue
                        totalR += r
                        totalB += b
                        count++
                    }
                }

                val ct = if (count > 0) {
                    val avgR = totalR.toFloat() / count
                    val avgB = totalB.toFloat() / count
                    estimateCtFromRbRatio(avgR, avgB)
                } else {
                    5500
                }
                regionCts.add(ct)
            }
        }

        // Compute variance
        val mean = regionCts.average().toFloat()
        var varianceSum = 0f
        for (ct in regionCts) {
            val diff = ct - mean
            varianceSum += diff * diff
        }
        val variance = if (regionCts.size > 1) {
            kotlin.math.sqrt(varianceSum / regionCts.size)
        } else 0f

        val isMixed = variance > 1500f

        // Subject region CT
        val subjectCt = if (subjectRect != null) {
            val sx = ((subjectRect.left + subjectRect.right) / 2f * gridSize).toInt().coerceIn(0, gridSize - 1)
            val sy = ((subjectRect.top + subjectRect.bottom) / 2f * gridSize).toInt().coerceIn(0, gridSize - 1)
            regionCts[sy * gridSize + sx]
        } else {
            // Default to center region
            regionCts[gridSize / 2 * gridSize + gridSize / 2]
        }

        return MixedLightingResult(
            isMixed = isMixed,
            ctVariance = variance,
            regionCts = regionCts,
            subjectCt = subjectCt
        )
    }

    private fun estimateCtFromRbRatio(avgR: Float, avgB: Float): Int {
        val rbRatio = avgR / avgB.coerceAtLeast(1f)
        val breakpoints = floatArrayOf(0.7f, 0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.6f, 2.0f)
        val kelvinValues = intArrayOf(9000, 7500, 6500, 5800, 5200, 4500, 3800, 3200, 2800)
        for (i in breakpoints.indices) {
            if (rbRatio < breakpoints[i]) return kelvinValues[i]
            if (i < breakpoints.size - 1 && rbRatio < breakpoints[i + 1]) {
                val t = (rbRatio - breakpoints[i]) / (breakpoints[i + 1] - breakpoints[i])
                return (kelvinValues[i] + t * (kelvinValues[i + 1] - kelvinValues[i])).toInt()
            }
        }
        return kelvinValues.last()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MixedLightingTest" 2>&1 | tail -5`
Expected: PASS — all 5 tests pass

- [ ] **Step 5: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt ai-engine/src/test/java/com/spectra/ai/MixedLightingTest.kt
git commit -m "Add mixed lighting detection with 4x4 grid color temperature analysis"
```

---

### Task 6: Wire Mixed Lighting into Pipeline and Coaching

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`

- [ ] **Step 1: Add mixed lighting detection to analyzeFrame()**

In `FrameAnalysisPipeline.kt`, in the `analyzeFrame()` method, after the `estimatedCt` computation and before the `analyzeFromMetadata` call, add:

```kotlin
        // Mixed lighting detection
        val faceRectsForLighting = if (currentFaceData.hasFaces) {
            currentFaceData.primaryFace?.bounds
        } else null
        val mixedLighting = lightingAnalyzer.detectMixedLighting(pixels, bitmap.width, bitmap.height, faceRectsForLighting)
```

After the `lighting` is computed (from `analyzeFromMetadata`), override it if mixed:

```kotlin
        val finalLighting = if (mixedLighting.isMixed) LightingCondition.MIXED else lighting
```

Then use `finalLighting` instead of `lighting` in the `SceneAnalysis` constructor:

```kotlin
        val sceneAnalysis = SceneAnalysis(
            sceneType = sceneType,
            confidence = confidence,
            lighting = finalLighting,
            motionLevel = motion,
            distanceRange = distance,
            faceData = currentFaceData
        )
```

Add the import:
```kotlin
import com.spectra.ai.model.LightingCondition
```

- [ ] **Step 2: Add mixed lighting coaching to CoachingEngine**

In `CoachingEngine.kt`, update the `generateCoaching` method's `when` block. After the backlit check, add a mixed lighting check:

Change from:
```kotlin
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(preset)
```

to:
```kotlin
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(preset)
            analysis.lighting == LightingCondition.MIXED -> mixedLightingHint()
```

Add the `mixedLightingHint()` method:

```kotlin
    private fun mixedLightingHint(): CoachingHint {
        return CoachingHint(
            "Mixed lighting detected — WB matched to subject",
            ArrowDirection.NONE,
            priority = 5
        )
    }
```

Add the import if not present:
```kotlin
import com.spectra.ai.model.LightingCondition
```

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `./gradlew :ai-engine:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS — all tests pass

- [ ] **Step 4: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt
git commit -m "Wire mixed lighting detection into pipeline and add coaching hint"
```

---

### Task 7: Consolidate CameraPreset from 16 to 8

**Files:**
- Modify: `core/src/main/java/com/spectra/core/model/CameraPreset.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/PresetConsolidationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/PresetConsolidationTest.kt
package com.spectra.ai

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetConsolidationTest {

    @Test
    fun `exactly 8 presets exist`() {
        assertEquals(8, CameraPreset.entries.size)
    }

    @Test
    fun `AUTO preset exists`() {
        val auto = CameraPreset.AUTO
        assertEquals("Auto", auto.label)
    }

    @Test
    fun `PORTRAIT preset uses telephoto 3x`() {
        assertEquals(LensId.TELEPHOTO_3X, CameraPreset.PORTRAIT.preferredLens)
    }

    @Test
    fun `PORTRAIT is not front camera default`() {
        assertFalse(CameraPreset.PORTRAIT.isFrontCameraDefault)
    }

    @Test
    fun `ACTION preset exists`() {
        val action = CameraPreset.ACTION
        assertEquals("Action", action.label)
    }

    @Test
    fun `FOOD preset exists`() {
        val food = CameraPreset.FOOD
        assertEquals("Food", food.label)
    }

    @Test
    fun `LANDSCAPE preset uses main lens`() {
        assertEquals(LensId.MAIN, CameraPreset.LANDSCAPE.preferredLens)
    }

    @Test
    fun `PRO preset still exists`() {
        val pro = CameraPreset.PRO
        assertEquals("Pro", pro.label)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.PresetConsolidationTest" 2>&1 | tail -5`
Expected: FAIL — 16 presets exist, not 8; `AUTO` does not exist

- [ ] **Step 3: Replace CameraPreset enum with consolidated 8 entries**

Replace the entire content of `core/src/main/java/com/spectra/core/model/CameraPreset.kt`:

```kotlin
package com.spectra.core.model

enum class CameraPreset(
    val label: String,
    val icon: String,
    val preferredLens: LensId = LensId.MAIN,
    val isFrontCameraDefault: Boolean = false
) {
    AUTO("Auto", "✨", preferredLens = LensId.MAIN),
    PORTRAIT("Portrait", "👤", preferredLens = LensId.TELEPHOTO_3X),
    NIGHT("Night", "🌙", preferredLens = LensId.MAIN),
    FOOD("Food", "🍽", preferredLens = LensId.MAIN),
    LANDSCAPE("Landscape", "🏞", preferredLens = LensId.MAIN),
    ACTION("Action", "⚡", preferredLens = LensId.MAIN),
    MACRO("Macro", "🔍", preferredLens = LensId.MAIN),
    PRO("Pro", "⚙", preferredLens = LensId.MAIN);
}
```

- [ ] **Step 4: Run preset consolidation tests**

Run: `./gradlew :core:testDebugUnitTest 2>&1 | tail -5`
Expected: PASS (core tests should compile)

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.PresetConsolidationTest" 2>&1 | tail -5`
Expected: PASS — all 8 tests pass

- [ ] **Step 5: Commit**

```
git add core/src/main/java/com/spectra/core/model/CameraPreset.kt ai-engine/src/test/java/com/spectra/ai/PresetConsolidationTest.kt
git commit -m "Consolidate CameraPreset from 16 to 8: AUTO, PORTRAIT, NIGHT, FOOD, LANDSCAPE, ACTION, MACRO, PRO"
```

---

### Task 8: Update PresetEngine for Consolidated Presets

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/PresetEngine.kt`

- [ ] **Step 1: Replace getSettings with merged preset logic**

Replace the entire `getSettings` method with:

```kotlin
    private fun getSettings(preset: CameraPreset, light: LightingCondition, motion: MotionLevel): CameraSettings {
        val base = when (preset) {
            CameraPreset.AUTO -> autoSettings(light)
            CameraPreset.PORTRAIT -> portraitSettings(light)
            CameraPreset.NIGHT -> nightSettings(light)
            CameraPreset.FOOD -> foodSettings(light)
            CameraPreset.LANDSCAPE -> landscapeSettings(light)
            CameraPreset.ACTION -> actionSettings(light)
            CameraPreset.MACRO -> macroSettings(light)
            CameraPreset.PRO -> CameraSettings()
        }
        return adjustForMotion(base, motion, preset)
    }
```

- [ ] **Step 2: Add autoSettings method and update portraitSettings to handle face-count sub-modes**

Add the `autoSettings` method (replaces STREET / general):

```kotlin
    private fun autoSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4500, exposureCompensation = 0f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 200, whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }
```

- [ ] **Step 3: Update portraitSettings to merge SELFIE/COUPLE/GROUP logic**

Replace `portraitSettings` with:

```kotlin
    private fun portraitSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5300, exposureCompensation = 0.3f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 6200, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4200, exposureCompensation = 0.5f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
    }
```

- [ ] **Step 4: Update landscapeSettings to merge SUNSET logic (golden hour auto-detected)**

Replace `landscapeSettings` with:

```kotlin
    private fun landscapeSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6000, exposureCompensation = -0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 7000, exposureCompensation = 0f
        )
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5000, exposureCompensation = -0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6200, exposureCompensation = 0f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }
```

- [ ] **Step 5: Update foodSettings to merge PRODUCT logic**

Replace `foodSettings` with:

```kotlin
    private fun foodSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.7f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL, LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
    }
```

- [ ] **Step 6: Update actionSettings to merge KIDS/PETS logic**

Replace `actionSettings` with:

```kotlin
    private fun actionSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 2000, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }
```

- [ ] **Step 7: Remove deleted preset settings methods**

Delete these methods:
- `selfieSettings`
- `groupSettings`
- `coupleSettings`
- `kidsSettings`
- `petsSettings`
- `productSettings`
- `sunsetSettings`
- `streetSettings`
- `cinematicSettings`

- [ ] **Step 8: Update getProcessing for consolidated presets**

Replace `getProcessing` with:

```kotlin
    private fun getProcessing(preset: CameraPreset, isNight: Boolean): ProcessingParams {
        return when (preset) {
            CameraPreset.AUTO -> ProcessingParams(
                contrast = 50, saturation = 50, sharpness = 50,
                noiseReduction = if (isNight) 55 else 35
            )
            CameraPreset.PORTRAIT -> ProcessingParams(
                contrast = 42, saturation = 46, sharpness = 38,
                noiseReduction = if (isNight) 55 else 35,
                skinToneProcessing = 25, highlightProtection = 58
            )
            CameraPreset.NIGHT -> ProcessingParams(
                contrast = 45, saturation = 40, sharpness = 35,
                noiseReduction = 70, hdrStrength = 25,
                shadowRecovery = 40
            )
            CameraPreset.FOOD -> ProcessingParams(
                contrast = 52, saturation = 56, sharpness = 60,
                noiseReduction = 32, highlightProtection = 60,
                hdrStrength = 38
            )
            CameraPreset.LANDSCAPE -> ProcessingParams(
                contrast = 52, saturation = 54, sharpness = 55,
                noiseReduction = 30, hdrStrength = 55,
                highlightProtection = 68, shadowRecovery = 50
            )
            CameraPreset.ACTION -> ProcessingParams(
                contrast = 52, saturation = 53, sharpness = 52,
                noiseReduction = if (isNight) 50 else 30,
                hdrStrength = 35
            )
            CameraPreset.MACRO -> ProcessingParams(
                contrast = 50, saturation = 50, sharpness = 65,
                noiseReduction = 25
            )
            CameraPreset.PRO -> ProcessingParams.NATURAL
        }
    }
```

- [ ] **Step 9: Update companion object sets for consolidated presets**

Replace the companion object with:

```kotlin
    companion object {
        private val FACE_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val EYE_AF_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val BURST_PRESETS = setOf(CameraPreset.ACTION)
        private val STABILIZE_PRESETS = setOf(CameraPreset.NIGHT, CameraPreset.MACRO)
        private val TRACKING_PRESETS = setOf(CameraPreset.ACTION)
        private val NO_HDR_PRESETS = setOf(CameraPreset.NIGHT)
        private val BLUR_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val FAST_SHUTTER_PRESETS = setOf(CameraPreset.ACTION)
    }
```

- [ ] **Step 10: Update buildProfile for face-count aware PORTRAIT**

In `buildProfile`, after computing `settings` and `processing`, add face-count logic for PORTRAIT:

```kotlin
    fun buildProfile(preset: CameraPreset, analysis: SceneAnalysis): PresetProfile {
        val isNight = analysis.lighting == LightingCondition.LOW_LIGHT ||
                analysis.lighting == LightingCondition.ARTIFICIAL
        val settings = getSettings(preset, analysis.lighting, analysis.motionLevel)
        val processing = getProcessing(preset, isNight)

        val faceCount = analysis.faceData.faceCount
        val isGroup = preset == CameraPreset.PORTRAIT && faceCount >= 3

        return PresetProfile(
            preset = preset,
            settings = settings,
            processing = processing,
            facePriority = preset in FACE_PRESETS,
            eyeAf = preset in EYE_AF_PRESETS && !isGroup,
            blinkDetection = isGroup,
            burstEnabled = preset in BURST_PRESETS,
            motionStabilization = preset in STABILIZE_PRESETS,
            focusTracking = preset in TRACKING_PRESETS,
            multiFrameHdr = preset !in NO_HDR_PRESETS && !isNight,
            multiFrameStacking = preset == CameraPreset.NIGHT,
            backgroundBlur = preset in BLUR_PRESETS
        )
    }
```

- [ ] **Step 11: Run tests**

Run: `./gradlew :ai-engine:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS — all tests including PresetConsolidationTest pass. Some existing CoachingEngine tests may need updating if they reference removed presets.

- [ ] **Step 12: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/PresetEngine.kt
git commit -m "Merge PresetEngine settings for 8 consolidated presets with face-count sub-modes"
```

---

### Task 9: Fix All References to Removed Presets

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: Any test files referencing removed presets

- [ ] **Step 1: Update CoachingEngine for consolidated presets**

Replace the `motionHint` method with:

```kotlin
    private fun motionHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.ACTION ->
                CoachingHint("Track the action — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            CameraPreset.MACRO ->
                CoachingHint("Too much movement for macro — stabilize first", ArrowDirection.STEADY, priority = 9)
            else ->
                CoachingHint("Movement detected — hold shutter for burst", ArrowDirection.NONE, priority = 7)
        }
    }
```

Replace the `backlitHint` method with:

```kotlin
    private fun backlitHint(preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.PORTRAIT ->
                CoachingHint("Backlit subject — tap face to brighten", ArrowDirection.NONE, priority = 9)
            CameraPreset.LANDSCAPE ->
                CoachingHint("Great backlight — try a silhouette", ArrowDirection.NONE, priority = 5)
            else ->
                CoachingHint("Strong backlight — tap subject to brighten", ArrowDirection.NONE, priority = 9)
        }
    }
```

Replace the `presetHint` method with:

```kotlin
    private fun presetHint(preset: CameraPreset, analysis: SceneAnalysis): CoachingHint? {
        return when (preset) {
            CameraPreset.AUTO -> autoHint(analysis)
            CameraPreset.PORTRAIT -> portraitHint(analysis)
            CameraPreset.NIGHT -> nightHint(analysis)
            CameraPreset.FOOD -> foodHint(analysis)
            CameraPreset.LANDSCAPE -> landscapeHint(analysis)
            CameraPreset.ACTION -> actionHint(analysis)
            CameraPreset.MACRO -> macroHint(analysis)
            CameraPreset.PRO -> null
        }
    }
```

Add the `autoHint` method:

```kotlin
    private fun autoHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.lighting == LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Golden hour — look for long shadows and warm tones", ArrowDirection.NONE, priority = 4)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — hold steady for best results", ArrowDirection.STEADY, priority = 5)
            else -> null
        }
    }
```

Update `portraitHint` to handle face-count sub-modes. Replace with:

```kotlin
    private fun portraitHint(analysis: SceneAnalysis): CoachingHint? {
        val faces = analysis.faceData
        return when {
            faces.faceCount == 0 ->
                CoachingHint("Position your subject in the frame", ArrowDirection.NONE, priority = 7)
            faces.anyBlinking ->
                CoachingHint("Eyes closed — try again", ArrowDirection.NONE, priority = 8)
            faces.isGroupShot && !faces.allEyesOpen ->
                CoachingHint("Check that everyone's eyes are open", ArrowDirection.NONE, priority = 6)
            faces.isGroupShot ->
                CoachingHint("Make sure no one is cut off at the edges", ArrowDirection.NONE, priority = 3)
            analysis.distanceRange == DistanceRange.FAR ->
                CoachingHint("Move closer — show head and shoulders", ArrowDirection.NONE, priority = 7)
            analysis.distanceRange == DistanceRange.MACRO || analysis.distanceRange == DistanceRange.NEAR ->
                CoachingHint("Step back slightly for a flattering perspective", ArrowDirection.NONE, priority = 6)
            analysis.lighting == LightingCondition.HARSH_MIDDAY ->
                CoachingHint("Harsh light — find open shade for softer look", ArrowDirection.NONE, priority = 5)
            analysis.lighting == LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Beautiful light — angle face toward the sun", ArrowDirection.NONE, priority = 4)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — have subject face the brightest source", ArrowDirection.NONE, priority = 6)
            faces.isCoupleShot ->
                CoachingHint("Get them close — touching shoulders looks natural", ArrowDirection.NONE, priority = 3)
            else -> CoachingHint("Tap the eyes to lock focus there", ArrowDirection.NONE, priority = 3)
        }
    }
```

Remove deleted preset hint methods: `selfieHint`, `groupHint`, `coupleHint`, `kidsHint`, `petsHint`, `productHint`, `sunsetHint`, `streetHint`, `cinematicHint`.

- [ ] **Step 2: Update CameraViewModel for consolidated presets**

In `CameraViewModel.kt`, update the `setPreset` method. Replace:

```kotlin
            val mode = when (preset) {
                CameraPreset.NIGHT -> CameraMode.NIGHT
                CameraPreset.PORTRAIT, CameraPreset.COUPLE, CameraPreset.SELFIE -> CameraMode.PORT
                else -> CameraMode.PHOTO
            }
```

with:

```kotlin
            val mode = when (preset) {
                CameraPreset.NIGHT -> CameraMode.NIGHT
                CameraPreset.PORTRAIT -> CameraMode.PORT
                else -> CameraMode.PHOTO
            }
```

- [ ] **Step 3: Update existing tests referencing removed presets**

Search for references to removed presets (SELFIE, GROUP, COUPLE, KIDS, PETS, PRODUCT, SUNSET, STREET, CINEMATIC) in test files and update them to use consolidated presets. Common mappings:
- `CameraPreset.SELFIE` -> `CameraPreset.PORTRAIT` (with isFrontCamera context)
- `CameraPreset.GROUP` -> `CameraPreset.PORTRAIT` (with faceCount >= 3)
- `CameraPreset.COUPLE` -> `CameraPreset.PORTRAIT` (with faceCount == 2)
- `CameraPreset.KIDS` -> `CameraPreset.ACTION`
- `CameraPreset.PETS` -> `CameraPreset.ACTION`
- `CameraPreset.PRODUCT` -> `CameraPreset.FOOD`
- `CameraPreset.SUNSET` -> `CameraPreset.LANDSCAPE`
- `CameraPreset.STREET` -> `CameraPreset.AUTO`
- `CameraPreset.CINEMATIC` -> `CameraPreset.AUTO`

Run: `grep -rn "CameraPreset\.\(SELFIE\|GROUP\|COUPLE\|KIDS\|PETS\|PRODUCT\|SUNSET\|STREET\|CINEMATIC\)" --include="*.kt" .` to find all references and update them.

- [ ] **Step 4: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -15`
Expected: PASS — all tests pass with consolidated presets

- [ ] **Step 5: Commit**

```
git add -A
git commit -m "Update all references to use consolidated 8 presets across coaching, ViewModel, and tests"
```

---

### Task 10: Create CoachingAction Sealed Class

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/model/CoachingAction.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/model/CoachingActionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/model/CoachingActionTest.kt
package com.spectra.ai.model

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingActionTest {

    @Test
    fun `SWITCH_LENS carries target lens`() {
        val action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
        assertEquals(LensId.TELEPHOTO_3X, action.lensId)
        assertTrue(action is CoachingAction)
    }

    @Test
    fun `ENABLE_BURST is a singleton`() {
        val action = CoachingAction.EnableBurst
        assertTrue(action is CoachingAction)
    }

    @Test
    fun `SWITCH_PRESET carries target preset`() {
        val action = CoachingAction.SwitchPreset(CameraPreset.NIGHT)
        assertEquals(CameraPreset.NIGHT, action.preset)
    }

    @Test
    fun `display labels are descriptive`() {
        assertEquals("Switch to 3x", CoachingAction.SwitchLens(LensId.TELEPHOTO_3X).label)
        assertEquals("Enable Burst", CoachingAction.EnableBurst.label)
        assertEquals("Switch to Night", CoachingAction.SwitchPreset(CameraPreset.NIGHT).label)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.model.CoachingActionTest" 2>&1 | tail -5`
Expected: FAIL — `CoachingAction` does not exist

- [ ] **Step 3: Create CoachingAction sealed class**

```kotlin
// ai-engine/src/main/java/com/spectra/ai/model/CoachingAction.kt
package com.spectra.ai.model

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId

sealed class CoachingAction {
    abstract val label: String

    data class SwitchLens(val lensId: LensId) : CoachingAction() {
        override val label: String get() = "Switch to ${lensId.zoomLabel}"
    }

    data object EnableBurst : CoachingAction() {
        override val label: String get() = "Enable Burst"
    }

    data class SwitchPreset(val preset: CameraPreset) : CoachingAction() {
        override val label: String get() = "Switch to ${preset.label}"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.model.CoachingActionTest" 2>&1 | tail -5`
Expected: PASS — all 4 tests pass

- [ ] **Step 5: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/model/CoachingAction.kt ai-engine/src/test/java/com/spectra/ai/model/CoachingActionTest.kt
git commit -m "Add CoachingAction sealed class for actionable coaching hints"
```

---

### Task 11: Add action Field to CoachingHint

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`

- [ ] **Step 1: Add action field to CoachingHint**

Replace the contents of `ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt`:

```kotlin
package com.spectra.ai.model

enum class ArrowDirection {
    UP, DOWN, LEFT, RIGHT, STEADY, NONE;
}

data class CoachingHint(
    val text: String,
    val arrow: ArrowDirection = ArrowDirection.NONE,
    val priority: Int = 0,
    val action: CoachingAction? = null
)
```

- [ ] **Step 2: Add coachingAction to HudState**

In `core/src/main/java/com/spectra/core/model/HudState.kt`, add after `cloudCoachingArrow`:

```kotlin
    val coachingActionLabel: String? = null,
    val coachingActionType: String? = null,
    val coachingActionPayload: String? = null,
```

- [ ] **Step 3: Run existing tests to verify backward compatibility**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS — `action` defaults to `null`, so all existing CoachingHint constructors remain valid

- [ ] **Step 4: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/model/CoachingHint.kt core/src/main/java/com/spectra/core/model/HudState.kt
git commit -m "Add optional action field to CoachingHint and coachingAction to HudState"
```

---

### Task 12: Attach Actions to Coaching Hints in CoachingEngine

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`

- [ ] **Step 1: Add action imports**

Add at the top of `CoachingEngine.kt`:

```kotlin
import com.spectra.ai.model.CoachingAction
import com.spectra.core.model.LensId
```

- [ ] **Step 2: Attach SWITCH_LENS to portrait distance hint**

In `portraitHint`, update the FAR distance hint:

```kotlin
            analysis.distanceRange == DistanceRange.FAR ->
                CoachingHint(
                    "Move closer — or try 3x telephoto",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
                )
```

- [ ] **Step 3: Attach ENABLE_BURST to motion hints**

In `motionHint`, update the ACTION preset hint:

```kotlin
            CameraPreset.ACTION ->
                CoachingHint(
                    "Track the action — hold shutter for burst",
                    ArrowDirection.NONE,
                    priority = 8,
                    action = CoachingAction.EnableBurst
                )
```

And the default motion hint:

```kotlin
            else ->
                CoachingHint(
                    "Movement detected — hold shutter for burst",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.EnableBurst
                )
```

- [ ] **Step 4: Attach SWITCH_PRESET to low-light autoHint**

In `autoHint`, update the LOW_LIGHT hint:

```kotlin
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint(
                    "Low light — try Night mode for best results",
                    ArrowDirection.STEADY,
                    priority = 5,
                    action = CoachingAction.SwitchPreset(CameraPreset.NIGHT)
                )
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :ai-engine:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt
git commit -m "Attach actionable CoachingActions to relevant coaching hints"
```

---

### Task 13: Wire CoachingAction Through ViewModel and Update CoachingDirective UI

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`

- [ ] **Step 1: Pass coaching action data to HudState in ViewModel**

In `CameraViewModel.kt`, update the coaching hint collector. Change:

```kotlin
        viewModelScope.launch {
            pipeline.coachingHint.collect { hint ->
                _hudState.update { it.copy(
                    coachingText = hint?.text,
                    coachingArrow = hint?.arrow?.name ?: "NONE"
                )}
            }
        }
```

to:

```kotlin
        viewModelScope.launch {
            pipeline.coachingHint.collect { hint ->
                _hudState.update { it.copy(
                    coachingText = hint?.text,
                    coachingArrow = hint?.arrow?.name ?: "NONE",
                    coachingActionLabel = hint?.action?.label,
                    coachingActionType = when (hint?.action) {
                        is com.spectra.ai.model.CoachingAction.SwitchLens -> "SWITCH_LENS"
                        is com.spectra.ai.model.CoachingAction.EnableBurst -> "ENABLE_BURST"
                        is com.spectra.ai.model.CoachingAction.SwitchPreset -> "SWITCH_PRESET"
                        null -> null
                    },
                    coachingActionPayload = when (val a = hint?.action) {
                        is com.spectra.ai.model.CoachingAction.SwitchLens -> a.lensId.name
                        is com.spectra.ai.model.CoachingAction.SwitchPreset -> a.preset.name
                        is com.spectra.ai.model.CoachingAction.EnableBurst -> null
                        null -> null
                    }
                )}
            }
        }
```

- [ ] **Step 2: Add executeCoachingAction method to ViewModel**

Add this method to `CameraViewModel`:

```kotlin
    fun executeCoachingAction() {
        val state = _hudState.value
        val type = state.coachingActionType ?: return
        val payload = state.coachingActionPayload

        when (type) {
            "SWITCH_LENS" -> {
                val lens = payload?.let { name ->
                    try { LensId.valueOf(name) } catch (_: Exception) { null }
                }
                if (lens != null) switchLens(lens)
            }
            "ENABLE_BURST" -> startBurst()
            "SWITCH_PRESET" -> {
                val preset = payload?.let { name ->
                    try { CameraPreset.valueOf(name) } catch (_: Exception) { null }
                }
                if (preset != null) setPreset(preset)
            }
        }
        dismissCoaching()
    }
```

- [ ] **Step 3: Update CoachingDirective to render action button**

In `app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt`, update the signature to accept an optional action:

```kotlin
@Composable
fun CoachingDirective(
    text: String,
    arrowDirection: ArrowDirection,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
```

After the `CoachingBox(text = text)` call inside the `Row`, add the action button:

```kotlin
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = actionLabel,
                    style = HudTypography.coaching,
                    color = HudColors.accent,
                    modifier = Modifier
                        .background(HudColors.accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .clickable { onAction() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
```

- [ ] **Step 4: Update CoachingDirective callers**

Search for all callers of `CoachingDirective` in the codebase and pass `actionLabel` and `onAction` where the HudState data is available. Typically in `HudOverlay.kt` or `ViewfinderScreen.kt`, add:

```kotlin
actionLabel = hudState.coachingActionLabel,
onAction = if (hudState.coachingActionLabel != null) {{ viewModel.executeCoachingAction() }} else null,
```

- [ ] **Step 5: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt app/src/main/java/com/spectra/app/ui/hud/CoachingDirective.kt
git commit -m "Wire actionable coaching through ViewModel and render Apply button in CoachingDirective"
```

---

### Task 14: Wire ProcessingParams to CaptureManager Post-Processing

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Test: `camera/src/test/java/com/spectra/camera/ProcessingParamsWiringTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// camera/src/test/java/com/spectra/camera/ProcessingParamsWiringTest.kt
package com.spectra.camera

import com.spectra.core.model.ProcessingParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingParamsWiringTest {

    @Test
    fun `noiseReduction maps to bilateral sigma`() {
        val params = ProcessingParams(noiseReduction = 50)
        val sigma = CaptureManager.nrSigma(params.noiseReduction)
        assertEquals(2.0f, sigma, 0.1f) // 0.5 + (50/100) * 3.0 = 2.0
    }

    @Test
    fun `noiseReduction 0 maps to minimum sigma`() {
        val sigma = CaptureManager.nrSigma(0)
        assertEquals(0.5f, sigma, 0.01f)
    }

    @Test
    fun `noiseReduction 100 maps to maximum sigma`() {
        val sigma = CaptureManager.nrSigma(100)
        assertEquals(3.5f, sigma, 0.01f)
    }

    @Test
    fun `sharpness maps to USM strength`() {
        val strength = CaptureManager.sharpnessStrength(50)
        assertEquals(0.4f, strength, 0.01f) // 0.1 + (50/100) * 0.6 = 0.4
    }

    @Test
    fun `saturation maps to multiplier`() {
        val multiplier = CaptureManager.saturationMultiplier(50)
        assertEquals(1.0f, multiplier, 0.01f) // 0.5 + (50/100) * 1.0 = 1.0
    }

    @Test
    fun `contrast maps to ColorMatrix scale`() {
        val scale = CaptureManager.contrastScale(50)
        assertEquals(1.0f, scale, 0.05f) // neutral at 50
    }

    @Test
    fun `contrast at 100 produces boost`() {
        val scale = CaptureManager.contrastScale(100)
        assert(scale > 1.0f) { "Scale $scale should be > 1.0 at contrast=100" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ProcessingParamsWiringTest" 2>&1 | tail -5`
Expected: FAIL — `CaptureManager.nrSigma` does not exist

- [ ] **Step 3: Add ProcessingParams mapping functions to CaptureManager**

Add these companion-accessible functions to `CaptureManager`:

```kotlin
    companion object {
        /** Map noiseReduction (0-100) to bilateral filter sigma */
        fun nrSigma(nr: Int): Float = 0.5f + (nr / 100f) * 3f

        /** Map sharpness (0-100) to USM strength */
        fun sharpnessStrength(sharpness: Int): Float = 0.1f + (sharpness / 100f) * 0.6f

        /** Map saturation (0-100) to ColorMatrix saturation multiplier */
        fun saturationMultiplier(sat: Int): Float = 0.5f + (sat / 100f) * 1.0f

        /** Map contrast (0-100) to ColorMatrix scale. 50 = neutral (1.0) */
        fun contrastScale(contrast: Int): Float {
            val normalized = (contrast - 50) / 50f // -1..+1
            return 1.0f + normalized * 0.2f // 0.8..1.2
        }

        /** Map contrast (0-100) to offset. Paired with contrastScale */
        fun contrastOffset(contrast: Int): Float {
            val scale = contrastScale(contrast)
            return (-128f * (scale - 1f))
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ProcessingParamsWiringTest" 2>&1 | tail -5`
Expected: PASS — all 7 tests pass

- [ ] **Step 5: Add ProcessingParams parameter to applyPostProcess**

Update the `applyPostProcess` signature to accept `ProcessingParams`:

```kotlin
import com.spectra.core.model.ProcessingParams
```

Change signature from:

```kotlin
    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false) {
```

to:

```kotlin
    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false, processing: ProcessingParams = ProcessingParams()) {
```

- [ ] **Step 6: Use ProcessingParams to drive contrast and saturation in applyPostProcess**

Replace the generic enhancePaint block:

```kotlin
            val enhancePaint = Paint().apply {
                val contrast = ColorMatrix(floatArrayOf(
                    1.03f, 0f, 0f, 0f, -4f,
                    0f, 1.03f, 0f, 0f, -4f,
                    0f, 0f, 1.03f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val saturation = ColorMatrix().apply { setSaturation(1.05f) }
                contrast.postConcat(saturation)
                colorFilter = ColorMatrixColorFilter(contrast)
            }
            canvas.drawBitmap(result, 0f, 0f, enhancePaint)
```

with:

```kotlin
            val cScale = contrastScale(processing.contrast)
            val cOffset = contrastOffset(processing.contrast)
            val satMult = saturationMultiplier(processing.saturation)
            val enhancePaint = Paint().apply {
                val contrastMat = ColorMatrix(floatArrayOf(
                    cScale, 0f, 0f, 0f, cOffset,
                    0f, cScale, 0f, 0f, cOffset,
                    0f, 0f, cScale, 0f, cOffset,
                    0f, 0f, 0f, 1f, 0f
                ))
                val satMat = ColorMatrix().apply { setSaturation(satMult) }
                contrastMat.postConcat(satMat)
                colorFilter = ColorMatrixColorFilter(contrastMat)
            }
            canvas.drawBitmap(result, 0f, 0f, enhancePaint)
```

- [ ] **Step 7: Use ProcessingParams to drive sharpening strength**

Replace the `applySharpen(result)` call in `applyPostProcess` after styles with:

```kotlin
            // Apply sharpening driven by ProcessingParams
            val sharpStr = sharpnessStrength(processing.sharpness)
            applySharpenWithStrength(result, sharpStr)
```

Copy `applySharpen` to `applySharpenWithStrength` with configurable strength:

```kotlin
    private fun applySharpenWithStrength(bitmap: Bitmap, strength: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val output = IntArray(w * h)
        System.arraycopy(pixels, 0, output, 0, pixels.size)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val cR = (pixels[idx] shr 16) and 0xFF
                val cG = (pixels[idx] shr 8) and 0xFF
                val cB = pixels[idx] and 0xFF

                var sumR = 0; var sumG = 0; var sumB = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dy == 0 && dx == 0) continue
                        val nIdx = (y + dy) * w + (x + dx)
                        sumR += (pixels[nIdx] shr 16) and 0xFF
                        sumG += (pixels[nIdx] shr 8) and 0xFF
                        sumB += pixels[nIdx] and 0xFF
                    }
                }
                val avgR = sumR / 8; val avgG = sumG / 8; val avgB = sumB / 8
                val rOut = (cR + (cR - avgR) * strength).toInt().coerceIn(0, 255)
                val gOut = (cG + (cG - avgG) * strength).toInt().coerceIn(0, 255)
                val bOut = (cB + (cB - avgB) * strength).toInt().coerceIn(0, 255)
                output[idx] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }
        bitmap.setPixels(output, 0, w, 0, 0, w, h)
    }
```

- [ ] **Step 8: Update all callers of applyPostProcess to pass ProcessingParams**

In `captureMultiFrame`, `saveProcessedCopy`, and `generateAiEnhanced`, add a `processing: ProcessingParams = ProcessingParams()` parameter and pass it through to `applyPostProcess`.

Update `capturePhoto` and `captureSmartPhoto` signatures to accept `processing: ProcessingParams = ProcessingParams()` and thread it through.

- [ ] **Step 9: Run tests**

Run: `./gradlew :camera:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 10: Commit**

```
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt camera/src/test/java/com/spectra/camera/ProcessingParamsWiringTest.kt
git commit -m "Wire ProcessingParams to CaptureManager post-processing: contrast, saturation, sharpness"
```

---

### Task 15: Pass ProcessingParams from ViewModel Through Controller to CaptureManager

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Pass ProcessingParams to captureSmartPhoto and saveProcessedCopy**

In `CameraViewModel.kt`, in `capturePhotoInternal()`, update the `captureManager.captureSmartPhoto` call:

```kotlin
            val result = captureManager.captureSmartPhoto(
                imageCapture,
                state.beautyLevel,
                state.photoStyle,
                state.isFrontCamera,
                state.isHdrActive,
                lastDetectedFaceRects,
                processing = state.processing
            )
```

Update the `captureManager.saveProcessedCopy` call:

```kotlin
                    val processedUri = captureManager.saveProcessedCopy(
                        result.bestOriginalUri,
                        state.beautyLevel,
                        state.photoStyle,
                        state.isFrontCamera,
                        state.isHdrActive,
                        lastDetectedFaceRects,
                        isPortraitMode = state.mode == CameraMode.PORT,
                        processing = state.processing
                    )
```

- [ ] **Step 2: Update CaptureManager public API**

In `CaptureManager.kt`, add `processing: ProcessingParams = ProcessingParams()` parameter to:
- `capturePhoto()`
- `captureSmartPhoto()`
- `saveProcessedCopy()`
- `generateAiEnhanced()`
- `captureMultiFrame()` (private, but needs threading)

And pass it through to `applyPostProcess()`.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "Pass ProcessingParams from ViewModel through capture pipeline to post-processing"
```

---

### Task 16: Implement Local Tone Mapping with Bilateral Decomposition

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Test: `camera/src/test/java/com/spectra/camera/LocalToneMappingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// camera/src/test/java/com/spectra/camera/LocalToneMappingTest.kt
package com.spectra.camera

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalToneMappingTest {

    @Test
    fun `bilateral decomposition preserves detail while compressing range`() {
        // Create a high-contrast test image: left half dark, right half bright
        val w = 64
        val h = 64
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lum = if (x < w / 2) 20 else 230
                pixels[y * w + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        CaptureManager.applyLocalToneMap(bitmap, 0.5f)

        // Read back pixels
        val output = IntArray(w * h)
        bitmap.getPixels(output, 0, w, 0, 0, w, h)

        // After local tone mapping, the contrast ratio should be reduced
        val darkAvg = (0 until h).flatMap { y -> (0 until w / 2).map { x ->
            ((output[y * w + x] shr 16) and 0xFF)
        }}.average()
        val brightAvg = (0 until h).flatMap { y -> (w / 2 until w).map { x ->
            ((output[y * w + x] shr 16) and 0xFF)
        }}.average()

        // Dark side should be brighter than original 20
        assertTrue("Dark side ($darkAvg) should be lifted above 20", darkAvg > 25)
        // Bright side should be compressed below original 230
        assertTrue("Bright side ($brightAvg) should be compressed below 230", brightAvg < 225)
        // But overall contrast should still exist (not flat)
        assertTrue("Should maintain some contrast", brightAvg > darkAvg + 50)

        bitmap.recycle()
    }

    @Test
    fun `local tone map with zero compression is nearly identity`() {
        val w = 32
        val h = 32
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { i ->
            val lum = (i * 255 / (w * h)).coerceIn(0, 255)
            (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        CaptureManager.applyLocalToneMap(bitmap, 1.0f) // compression = 1.0 = no compression

        val output = IntArray(w * h)
        bitmap.getPixels(output, 0, w, 0, 0, w, h)

        // With compression factor 1.0, output should be close to input
        var maxDiff = 0
        for (i in pixels.indices) {
            val inLum = (pixels[i] shr 16) and 0xFF
            val outLum = (output[i] shr 16) and 0xFF
            val diff = kotlin.math.abs(inLum - outLum)
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("Max diff ($maxDiff) should be small with no compression", maxDiff < 30)

        bitmap.recycle()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.LocalToneMappingTest" 2>&1 | tail -5`
Expected: FAIL — `CaptureManager.applyLocalToneMap` does not exist

- [ ] **Step 3: Implement applyLocalToneMap using bilateral decomposition**

Add to `CaptureManager.kt`'s companion object:

```kotlin
        /**
         * Local tone mapping using bilateral filter decomposition (Durand & Dorsey).
         * 1. Compute log-luminance
         * 2. Bilateral filter to get base layer (large-scale contrast)
         * 3. Detail layer = log-luminance - base
         * 4. Compress base layer by compression_factor
         * 5. Reconstruct: exp(compressed_base + detail)
         *
         * @param compressionFactor 0.3-0.7 for visible effect, 1.0 = no compression
         */
        fun applyLocalToneMap(bitmap: Bitmap, compressionFactor: Float) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            // Step 1: Compute luminance and log-luminance
            val luminance = FloatArray(w * h)
            val logLum = FloatArray(w * h)
            for (i in pixels.indices) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).coerceAtLeast(0.001f)
                logLum[i] = kotlin.math.ln(luminance[i])
            }

            // Step 2: Approximate bilateral filter on log-luminance for base layer
            // Using iterative box blur as fast approximation (3 passes)
            val base = FloatArray(w * h)
            System.arraycopy(logLum, 0, base, 0, logLum.size)
            bilateralApprox(base, logLum, w, h, spatialRadius = 5, rangeSigma = 0.4f)

            // Step 3: Detail = logLum - base
            val detail = FloatArray(w * h)
            for (i in detail.indices) {
                detail[i] = logLum[i] - base[i]
            }

            // Step 4: Compress base
            val baseMean = base.average().toFloat()
            for (i in base.indices) {
                base[i] = baseMean + (base[i] - baseMean) * compressionFactor
            }

            // Step 5: Reconstruct
            for (i in pixels.indices) {
                val newLum = kotlin.math.exp(base[i] + detail[i]).coerceIn(0.001f, 10f)
                val scale = if (luminance[i] > 0.001f) (newLum / luminance[i]).coerceIn(0.2f, 5f) else 1f

                val r = ((pixels[i] shr 16) and 0xFF)
                val g = ((pixels[i] shr 8) and 0xFF)
                val b = (pixels[i] and 0xFF)

                val rOut = (r * scale).toInt().coerceIn(0, 255)
                val gOut = (g * scale).toInt().coerceIn(0, 255)
                val bOut = (b * scale).toInt().coerceIn(0, 255)

                pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        /**
         * Approximate bilateral filter: spatial smoothing that preserves edges.
         * Uses a simplified joint-bilateral approach with box kernel.
         */
        private fun bilateralApprox(
            output: FloatArray,
            guide: FloatArray,
            w: Int,
            h: Int,
            spatialRadius: Int,
            rangeSigma: Float
        ) {
            val temp = FloatArray(output.size)
            val rangeVar = 2f * rangeSigma * rangeSigma

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val centerVal = guide[idx]
                    var weightedSum = 0f
                    var weightSum = 0f

                    val y0 = maxOf(0, y - spatialRadius)
                    val y1 = minOf(h - 1, y + spatialRadius)
                    val x0 = maxOf(0, x - spatialRadius)
                    val x1 = minOf(w - 1, x + spatialRadius)

                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            val nIdx = ny * w + nx
                            val diff = guide[nIdx] - centerVal
                            val rangeWeight = kotlin.math.exp(-(diff * diff) / rangeVar)
                            weightedSum += output[nIdx] * rangeWeight
                            weightSum += rangeWeight
                        }
                    }

                    temp[idx] = if (weightSum > 0f) weightedSum / weightSum else output[idx]
                }
            }

            System.arraycopy(temp, 0, output, 0, output.size)
        }
```

- [ ] **Step 4: Replace applyHdrToneMap calls with applyLocalToneMap**

In `applyPostProcess`, replace:

```kotlin
            if (isHdr) {
                applyHdrToneMap(result)
                Log.d("CaptureManager", "HDR tone mapping applied")
            }
```

with:

```kotlin
            if (isHdr) {
                applyLocalToneMap(result, 0.5f)
                Log.d("CaptureManager", "Local tone mapping (bilateral decomposition) applied")
            }
```

In `stackAndEnhance`, replace:

```kotlin
        applyHdrToneMap(result)
```

with:

```kotlin
        applyLocalToneMap(result, 0.5f)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.LocalToneMappingTest" 2>&1 | tail -5`
Expected: PASS — both tests pass

- [ ] **Step 6: Commit**

```
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt camera/src/test/java/com/spectra/camera/LocalToneMappingTest.kt
git commit -m "Replace global Reinhard tone mapping with bilateral decomposition local tone mapping"
```

---

### Task 17: Wire RollAngle into Composition Analysis Pipeline

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`

- [ ] **Step 1: Add LevelSensor dependency to FrameAnalysisPipeline**

In `FrameAnalysisPipeline.kt`, add the LevelSensor to the constructor. Since `FrameAnalysisPipeline` is in the `:ai-engine` module and `LevelSensor` is in `:camera`, pass the roll angle as a parameter instead.

Add `rollAngleDegrees: Float = 0f` parameter to `analyzeFrame()`:

```kotlin
    fun analyzeFrame(
        bitmap: Bitmap,
        mode: CameraMode = CameraMode.PHOTO,
        preset: CameraPreset = CameraPreset.PORTRAIT,
        isFrontCamera: Boolean = false,
        focusDistanceDiopters: Float = 0f,
        exposureTimeNs: Long = 0L,
        iso: Int = 100,
        colorTemperature: Int = 5500,
        rollAngleDegrees: Float = 0f
    ) {
```

Update the `compositionAnalyzer.analyze()` call to use the passed rollAngle instead of hardcoded `0f`:

```kotlin
        val compositionResult = compositionAnalyzer.analyze(
            pixels, bitmap.width, bitmap.height,
            faceRects, rollAngleDegrees
        )
```

- [ ] **Step 2: Pass rollAngle from CameraViewModel**

In `CameraViewModel.kt`, in the `frameProvider.frames.collect` block, update the `pipeline.analyzeFrame` call to pass the level angle:

```kotlin
                pipeline.analyzeFrame(
                    bitmap,
                    mode = meta.mode,
                    preset = meta.preset,
                    isFrontCamera = meta.isFrontCamera,
                    focusDistanceDiopters = meta.actualFocusDistance,
                    exposureTimeNs = meta.actualShutterSpeedNs,
                    iso = if (meta.actualIso > 0) meta.actualIso else 100,
                    colorTemperature = if (meta.actualColorTemperature > 0) meta.actualColorTemperature else 5500,
                    rollAngleDegrees = meta.levelAngle
                )
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "Wire LevelSensor rollAngle into composition analysis for horizon coaching"
```

---

### Task 18: Final Integration Test and Cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: PASS — all unit tests across all modules pass

- [ ] **Step 2: Run lint check**

Run: `./gradlew lintDebug 2>&1 | tail -10`
Expected: No new critical lint errors

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify PresetSelector auto-adapts to 8 presets**

The `PresetSelector.kt` composable uses `CameraPreset.entries` which will automatically show only the 8 consolidated presets. No code change needed in this file.

Verify by searching for hardcoded preset references:

Run: `grep -rn "CameraPreset\.\(SELFIE\|GROUP\|COUPLE\|KIDS\|PETS\|PRODUCT\|SUNSET\|STREET\|CINEMATIC\)" --include="*.kt" .`
Expected: No matches in production code (only in migration comments if any)

- [ ] **Step 5: Final commit**

```
git add -A
git commit -m "Phase 5C complete: composition analysis, preset consolidation, mixed lighting, ProcessingParams wiring, local tone mapping"
```

---

## Self-Review Checklist

- [ ] **Composition Analysis:** CompositionAnalyzer computes center-surround saliency at 64x64, scores against 4 thirds intersections, generates directional coaching hints with arrow direction
- [ ] **Horizon Coaching:** LevelSensor rollAngle flows through FrameAnalysisPipeline to CompositionAnalyzer; |roll| > 2 degrees triggers leveling hint
- [ ] **Preset Consolidation:** CameraPreset enum has exactly 8 entries: AUTO, PORTRAIT, NIGHT, FOOD, LANDSCAPE, ACTION, MACRO, PRO
- [ ] **Preset Mapping:** SELFIE/COUPLE/GROUP merged into PORTRAIT (face-count driven), KIDS/PETS merged into ACTION, PRODUCT merged into FOOD, SUNSET merged into LANDSCAPE, STREET/CINEMATIC merged into AUTO
- [ ] **PresetEngine:** All 16 settings methods replaced with 8; face-count sub-modes in PORTRAIT (eyeAf for single, blinkDetection for group)
- [ ] **CoachingEngine:** Updated for 8 presets; portrait hint handles single/couple/group via FaceData; removed 9 deleted preset methods
- [ ] **Mixed Lighting:** LightingAnalyzer.detectMixedLighting() divides frame into 4x4 grid, estimates CT per region, flags MIXED when variance > 1500K
- [ ] **LightingCondition.MIXED:** Added to enum, wired into pipeline (overrides base lighting when detected), coaching hint: "Mixed lighting detected -- WB matched to subject"
- [ ] **CoachingAction:** Sealed class with SwitchLens(lensId), EnableBurst, SwitchPreset(preset) variants, each with displayable label
- [ ] **CoachingHint.action:** Optional field added, backward compatible (defaults to null)
- [ ] **Actionable Coaching UI:** CoachingDirective renders action button when action is present; ViewModel.executeCoachingAction() dispatches lens switch, burst enable, or preset switch
- [ ] **ProcessingParams Wiring:** CaptureManager.applyPostProcess now accepts ProcessingParams; contrast/saturation/sharpness mapped to ColorMatrix and USM via documented formulas
- [ ] **ProcessingParams Flow:** PresetEngine -> PresetProfile -> HudState.processing -> CaptureManager.applyPostProcess
- [ ] **Local Tone Mapping:** applyHdrToneMap (global Reinhard) replaced with applyLocalToneMap (bilateral filter decomposition); log-luminance -> bilateral -> base/detail split -> compress base -> reconstruct
- [ ] **Bilateral Filter:** Approximate bilateral with spatial radius 5, range sigma 0.4; preserves edges while smoothing luminance for base layer extraction
- [ ] **No deleted preset references remain** in production Kotlin files
- [ ] **All tests pass** across :core, :camera, :ai-engine, :app modules
- [ ] **Build succeeds** with assembleDebug
