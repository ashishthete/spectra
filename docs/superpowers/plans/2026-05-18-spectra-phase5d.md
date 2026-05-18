# SPECTRA Phase 5D — Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 4 P3 polish items: highlight rolloff simulation, motion-type coaching, post-capture AI explainer overlay, and expose-for-highlights strategy. Final polish to complete the photography pipeline overhaul.

**Architecture:** Phase 5D adds finishing touches across all four modules. The camera module gains highlight rolloff curves in `ToneCurveEngine` (created in 5B) and expose-for-highlights logic in `HdrProcessor` (created in 5A) and `CaptureManager`. The AI engine gets motion-type-aware coaching messages in `CoachingEngine`, using the `MotionType` enum from Phase 5B's `MotionDetector`. The app module gains a post-capture explainer overlay composable and wires it through `HudState`. A new `CaptureExplanation` model in core captures AI decision rationale at capture time.

**Tech Stack:** Kotlin, Jetpack Compose, tone curves, Camera2 exposure control

---

## Dependencies

This plan assumes Phase 5A and Phase 5B are fully implemented. Key dependencies:

| Phase 5D Item | Depends On |
|---|---|
| 17. Highlight Rolloff | 5B #6: `camera/ToneCurveEngine.kt` exists with per-channel curve system |
| 18. Motion Coaching | 5B #7: `MotionDetector.kt` has `MotionType` enum (CAMERA_SHAKE, SUBJECT_MOTION, PAN, STATIC) |
| 19. AI Explainer | 5A #1: `SceneAnalysis` and `SensorMetadata` are populated with real values |
| 20. Expose-for-Highlights | 5A #3: `camera/HdrProcessor.kt` exists with bracket capture |

---

## File Structure

### New files:
- `core/src/main/java/com/spectra/core/model/CaptureExplanation.kt` — data class for AI decisions at capture time
- `app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt` — composable overlay showing what AI did

### Modified files:
- `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt` — add highlight shoulder Bezier curves (Phase 5B file)
- `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt` — motion-type-specific coaching messages
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt` — capture SceneAnalysis at capture time, show explainer, apply expose-for-highlights EV bias
- `core/src/main/java/com/spectra/core/model/HudState.kt` — add `captureExplanation` field
- `camera/src/main/java/com/spectra/camera/HdrProcessor.kt` — bias base exposure in brackets (Phase 5A file)
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt` — high-contrast shadow recovery + expose-for-highlights

### Test files:
- `camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt`
- `ai-engine/src/test/java/com/spectra/ai/MotionTypeCoachingTest.kt`
- `core/src/test/java/com/spectra/core/model/CaptureExplanationTest.kt`
- `camera/src/test/java/com/spectra/camera/ExposeForHighlightsTest.kt`

---

### Task 1: Add Highlight Shoulder Bezier to ToneCurveEngine

Add a soft highlight rolloff function that compresses highlights gradually instead of hard-clipping at 255. Each style gets its own shoulder curve. This modifies the `ToneCurveEngine` created in Phase 5B.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt`
- Test: `camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToneCurveHighlightRolloffTest {

    @Test
    fun `highlightShoulder compresses values above threshold`() {
        val input = 240
        val result = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isLessThan(240)
        assertThat(result).isGreaterThan(200)
    }

    @Test
    fun `highlightShoulder passes through values below threshold`() {
        val input = 150
        val result = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isEqualTo(150)
    }

    @Test
    fun `highlightShoulder at exactly threshold returns threshold`() {
        val input = 200
        val result = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isEqualTo(200)
    }

    @Test
    fun `highlightShoulder at 255 is clamped to maxOutput`() {
        val input = 255
        val result = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isAtMost(250)
    }

    @Test
    fun `highlightShoulder preserves monotonicity`() {
        val results = (200..255).map { ToneCurveEngine.highlightShoulder(it, shoulderStart = 200, maxOutput = 250, strength = 1.0f) }
        for (i in 1 until results.size) {
            assertThat(results[i]).isAtLeast(results[i - 1])
        }
    }

    @Test
    fun `highlightShoulder with zero strength is identity`() {
        val input = 240
        val result = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 255, strength = 0.0f)
        assertThat(result).isEqualTo(240)
    }

    @Test
    fun `highlightShoulder with half strength is between identity and full compression`() {
        val input = 240
        val full = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        val half = ToneCurveEngine.highlightShoulder(input, shoulderStart = 200, maxOutput = 250, strength = 0.5f)
        assertThat(half).isGreaterThan(full)
        assertThat(half).isLessThan(240)
    }

    @Test
    fun `buildHighlightRolloffCurve returns 256 entries`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(curve).hasLength(256)
    }

    @Test
    fun `buildHighlightRolloffCurve identity region is unchanged`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        for (i in 0..200) {
            assertThat(curve[i]).isEqualTo(i)
        }
    }

    @Test
    fun `buildHighlightRolloffCurve highlight region is compressed`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(curve[255]).isAtMost(250)
        assertThat(curve[230]).isLessThan(230)
    }

    @Test
    fun `buildHighlightRolloffCurve is monotonically increasing`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        for (i in 1 until 256) {
            assertThat(curve[i]).isAtLeast(curve[i - 1])
        }
    }

    @Test
    fun `NATURAL style has no highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.NATURAL)
        assertThat(params.strength).isEqualTo(0f)
    }

    @Test
    fun `FILM style has strong highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.FILM)
        assertThat(params.strength).isGreaterThan(0.5f)
        assertThat(params.maxOutput).isLessThan(255)
    }

    @Test
    fun `CINEMATIC style has highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.CINEMATIC)
        assertThat(params.strength).isGreaterThan(0f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ToneCurveHighlightRolloffTest" 2>&1 | tail -5`
Expected: FAIL — `highlightShoulder` and related methods do not exist on `ToneCurveEngine`

- [ ] **Step 3: Add HighlightParams data class and styleHighlightParams to ToneCurveEngine**

In `camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt`, add inside the `companion object` (or at the class level if no companion exists — add one):

```kotlin
    data class HighlightParams(
        val shoulderStart: Int = 200,
        val maxOutput: Int = 255,
        val strength: Float = 0f
    )

    companion object {
        fun styleHighlightParams(style: com.spectra.core.model.PhotoStyle): HighlightParams {
            return when (style) {
                com.spectra.core.model.PhotoStyle.NATURAL -> HighlightParams(
                    shoulderStart = 200, maxOutput = 255, strength = 0f
                )
                com.spectra.core.model.PhotoStyle.VIVID -> HighlightParams(
                    shoulderStart = 210, maxOutput = 252, strength = 0.3f
                )
                com.spectra.core.model.PhotoStyle.WARM -> HighlightParams(
                    shoulderStart = 205, maxOutput = 250, strength = 0.4f
                )
                com.spectra.core.model.PhotoStyle.FILM -> HighlightParams(
                    shoulderStart = 190, maxOutput = 240, strength = 0.8f
                )
                com.spectra.core.model.PhotoStyle.CINEMATIC -> HighlightParams(
                    shoulderStart = 195, maxOutput = 242, strength = 0.7f
                )
            }
        }

        fun highlightShoulder(input: Int, shoulderStart: Int, maxOutput: Int, strength: Float): Int {
            if (strength <= 0f || input <= shoulderStart) return input

            val range = 255 - shoulderStart
            val t = (input - shoulderStart).toFloat() / range
            val compressed = t * t * (3f - 2f * t)
            val shoulderOutput = shoulderStart + (maxOutput - shoulderStart) * compressed
            val linear = input.toFloat()
            val result = linear + strength * (shoulderOutput - linear)
            return result.toInt().coerceIn(shoulderStart, maxOutput)
        }

        fun buildHighlightRolloffCurve(shoulderStart: Int, maxOutput: Int, strength: Float): IntArray {
            val curve = IntArray(256)
            for (i in 0 until 256) {
                curve[i] = highlightShoulder(i, shoulderStart, maxOutput, strength)
            }
            return curve
        }
    }
```

If `ToneCurveEngine` already has a `companion object`, merge these methods into it. If not, add the companion object wrapper.

- [ ] **Step 4: Add applyHighlightRolloff method to ToneCurveEngine**

Add an instance method that applies the highlight rolloff to a per-channel curve array:

```kotlin
    fun applyHighlightRolloff(channelCurve: IntArray, style: com.spectra.core.model.PhotoStyle): IntArray {
        val params = styleHighlightParams(style)
        if (params.strength <= 0f) return channelCurve

        val rolloff = buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)
        val result = IntArray(256)
        for (i in 0 until 256) {
            result[i] = rolloff[channelCurve[i].coerceIn(0, 255)]
        }
        return result
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ToneCurveHighlightRolloffTest" 2>&1 | tail -5`
Expected: PASS — all 14 tests pass

- [ ] **Step 6: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/ToneCurveEngine.kt camera/src/test/java/com/spectra/camera/ToneCurveHighlightRolloffTest.kt
git commit -m "feat: add highlight rolloff shoulder curves to ToneCurveEngine per photo style"
```

---

### Task 2: Wire Highlight Rolloff into Post-Processing Pipeline

Apply the highlight rolloff curve during post-processing so each style's tone curve includes the soft shoulder compression.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

- [ ] **Step 1: Add applyHighlightRolloff call in applyPostProcess**

In `camera/src/main/java/com/spectra/camera/CaptureManager.kt`, inside the `applyPostProcess` method, add the highlight rolloff step after the style matrix is applied and before the beauty processing. Find this block (around line 648-657):

```kotlin
            if (style == PhotoStyle.FILM) {
                val liftPaint = Paint().apply { color = Color.argb(18, 40, 35, 50) }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), liftPaint)
            }
            if (style == PhotoStyle.CINEMATIC) {
                val tealPaint = Paint().apply { color = Color.argb(12, 0, 60, 70) }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), tealPaint)
            }
```

Add AFTER this block and BEFORE the beauty processing `if (beautyLevel > 0)`:

```kotlin
            if (style != PhotoStyle.NATURAL) {
                applyHighlightRolloffToBitmap(result, style)
            }
```

- [ ] **Step 2: Add applyHighlightRolloffToBitmap private method**

Add to `CaptureManager.kt`:

```kotlin
    private fun applyHighlightRolloffToBitmap(bitmap: Bitmap, style: PhotoStyle) {
        val params = ToneCurveEngine.styleHighlightParams(style)
        if (params.strength <= 0f) return

        val rolloffR = ToneCurveEngine.buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)
        val rolloffG = ToneCurveEngine.buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)
        val rolloffB = ToneCurveEngine.buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = rolloffR[(pixel shr 16) and 0xFF]
            val g = rolloffG[(pixel shr 8) and 0xFF]
            val b = rolloffB[pixel and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d("CaptureManager", "Highlight rolloff applied: style=$style, shoulder=${params.shoulderStart}, max=${params.maxOutput}")
    }
```

- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat: wire highlight rolloff into post-processing pipeline for all non-NATURAL styles"
```

---

### Task 3: Add Motion-Type-Specific Coaching Messages

Update `CoachingEngine` to generate differentiated coaching based on the `MotionType` enum from Phase 5B's `MotionDetector`. Camera shake, subject motion, and panning get distinct messages.

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/MotionTypeCoachingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/MotionTypeCoachingTest.kt
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.SceneType
import org.junit.Test

class MotionTypeCoachingTest {

    @Test
    fun `camera shake hint suggests bracing`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.INFINITY,
            motionType = MotionType.CAMERA_SHAKE
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).containsMatch("shaky|brace|hands|steady|stabilize")
    }

    @Test
    fun `subject motion hint mentions subject and burst`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.ACTION,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID,
            motionType = MotionType.SUBJECT_MOTION
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.ACTION)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).containsMatch("subject|moving|burst")
    }

    @Test
    fun `pan motion hint is encouraging`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.ACTION,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.MODERATE,
            distanceRange = DistanceRange.MID,
            motionType = MotionType.PAN
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.ACTION)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).containsMatch("panning|pan|blur|background")
    }

    @Test
    fun `static motion type does not trigger motion hint`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY,
            motionType = MotionType.STATIC
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        if (hint != null) {
            assertThat(hint.text.lowercase()).doesNotContain("shaky")
            assertThat(hint.text.lowercase()).doesNotContain("burst mode activated")
        }
    }

    @Test
    fun `camera shake in portrait mode suggests stabilization`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID,
            motionType = MotionType.CAMERA_SHAKE
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PORTRAIT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).containsMatch("shaky|brace|steady|stabilize")
    }

    @Test
    fun `subject motion with pets suggests burst`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID,
            motionType = MotionType.SUBJECT_MOTION
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PETS)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).containsMatch("subject|moving|burst")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MotionTypeCoachingTest" 2>&1 | tail -5`
Expected: FAIL — `SceneAnalysis` has no `motionType` field, `MotionType` enum does not exist

**Note:** Phase 5B should have added `MotionType` to `MotionDetector.kt` and `motionType` to `SceneAnalysis`. If those don't exist yet, we must add them as part of this task. The steps below assume they need to be created.

- [ ] **Step 3: Create MotionType enum (if not already created by Phase 5B)**

If the file does not exist, create `ai-engine/src/main/java/com/spectra/ai/model/MotionType.kt`:

```kotlin
// ai-engine/src/main/java/com/spectra/ai/model/MotionType.kt
package com.spectra.ai.model

enum class MotionType {
    STATIC,
    CAMERA_SHAKE,
    SUBJECT_MOTION,
    PAN;
}
```

- [ ] **Step 4: Add motionType field to SceneAnalysis (if not already added by Phase 5B)**

In `ai-engine/src/main/java/com/spectra/ai/model/SceneAnalysis.kt`, add the `motionType` field to the data class:

```kotlin
data class SceneAnalysis(
    val sceneType: SceneType = SceneType.UNKNOWN,
    val confidence: Float = 0f,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
    val motionLevel: MotionLevel = MotionLevel.STATIC,
    val distanceRange: DistanceRange = DistanceRange.INFINITY,
    val faceData: FaceData = FaceData.EMPTY,
    val motionType: MotionType = MotionType.STATIC,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isStable: Boolean get() = confidence >= 0.35f
    val isActionable: Boolean get() = confidence >= 0.70f
}
```

- [ ] **Step 5: Replace motionHint with motion-type-aware version in CoachingEngine**

In `ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt`, replace the `generateCoaching` method's motion check and the `motionHint` method.

First, update the motion branch in `generateCoaching` (around line 27):

Replace:
```kotlin
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis, preset)
```

With:
```kotlin
            (analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST) ||
            (analysis.motionLevel >= MotionLevel.MODERATE && analysis.motionType != MotionType.STATIC) ->
                motionTypeHint(analysis, preset)
```

Add the import at the top of the file:
```kotlin
import com.spectra.ai.model.MotionType
```

Then replace the `motionHint` method with `motionTypeHint`:

```kotlin
    private fun motionTypeHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (analysis.motionType) {
            MotionType.CAMERA_SHAKE -> CoachingHint(
                "Your hands are shaky — brace against something",
                ArrowDirection.STEADY,
                priority = 9
            )
            MotionType.SUBJECT_MOTION -> CoachingHint(
                "Your subject is moving fast — burst mode activated",
                ArrowDirection.NONE,
                priority = 8
            )
            MotionType.PAN -> CoachingHint(
                "Nice panning technique — slower shutter will blur the background",
                ArrowDirection.NONE,
                priority = 5
            )
            MotionType.STATIC -> motionFallbackHint(analysis, preset)
        }
    }

    private fun motionFallbackHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.KIDS ->
                CoachingHint("Active kid — hold shutter for burst mode", ArrowDirection.NONE, priority = 8)
            CameraPreset.PETS ->
                CoachingHint("Moving pet — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            CameraPreset.ACTION ->
                CoachingHint("Track the action — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            CameraPreset.MACRO ->
                CoachingHint("Too much movement for macro — stabilize first", ArrowDirection.STEADY, priority = 9)
            else ->
                CoachingHint("Movement detected — hold shutter for burst", ArrowDirection.NONE, priority = 7)
        }
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.MotionTypeCoachingTest" 2>&1 | tail -5`
Expected: PASS — all 6 tests pass

- [ ] **Step 7: Run existing CoachingEngine tests to check for regression**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.CoachingEngineTest" 2>&1 | tail -10`
Expected: PASS — all existing tests still pass (they don't set motionType, so it defaults to STATIC, which falls through to the existing preset-based hints)

- [ ] **Step 8: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/model/MotionType.kt ai-engine/src/main/java/com/spectra/ai/model/SceneAnalysis.kt ai-engine/src/main/java/com/spectra/ai/CoachingEngine.kt ai-engine/src/test/java/com/spectra/ai/MotionTypeCoachingTest.kt
git commit -m "feat: add motion-type-aware coaching — camera shake, subject motion, and pan get distinct messages"
```

---

### Task 4: Create CaptureExplanation Model

Create a data class that captures the AI's reasoning at capture time for the post-capture explainer overlay.

**Files:**
- Create: `core/src/main/java/com/spectra/core/model/CaptureExplanation.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Test: `core/src/test/java/com/spectra/core/model/CaptureExplanationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/java/com/spectra/core/model/CaptureExplanationTest.kt
package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureExplanationTest {

    @Test
    fun `CaptureExplanation formats ISO and shutter correctly`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 2_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            reasons = listOf("bright daylight", "fast subject detected")
        )
        assertThat(explanation.isoLabel).isEqualTo("ISO 100")
        assertThat(explanation.shutterLabel).isEqualTo("1/500s")
    }

    @Test
    fun `shutterLabel formats slow shutter correctly`() {
        val explanation = CaptureExplanation(
            iso = 800,
            shutterSpeedNs = 500_000_000L,
            sceneLabel = "NIGHT SCENE",
            lightingLabel = "LOW LIGHT",
            reasons = listOf("low light detected", "long exposure stacking")
        )
        assertThat(explanation.shutterLabel).isEqualTo("1/2s")
    }

    @Test
    fun `shutterLabel handles 1 second exposure`() {
        val explanation = CaptureExplanation(
            iso = 400,
            shutterSpeedNs = 1_000_000_000L,
            sceneLabel = "NIGHT SCENE",
            lightingLabel = "LOW LIGHT",
            reasons = listOf("night mode active")
        )
        assertThat(explanation.shutterLabel).isEqualTo("1s")
    }

    @Test
    fun `summary returns first 3 reasons only`() {
        val explanation = CaptureExplanation(
            iso = 200,
            shutterSpeedNs = 10_000_000L,
            sceneLabel = "PORTRAIT",
            lightingLabel = "GOLDEN HOUR",
            reasons = listOf("golden hour light", "face detected", "shallow depth", "extra reason")
        )
        assertThat(explanation.summaryReasons).hasSize(3)
        assertThat(explanation.summaryReasons).doesNotContain("extra reason")
    }

    @Test
    fun `headline formats as expected`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 2_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            reasons = listOf("bright daylight")
        )
        assertThat(explanation.headline).contains("ISO 100")
        assertThat(explanation.headline).contains("1/500s")
    }

    @Test
    fun `empty reasons produces empty summary`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 10_000_000L,
            sceneLabel = "UNKNOWN",
            lightingLabel = "",
            reasons = emptyList()
        )
        assertThat(explanation.summaryReasons).isEmpty()
    }

    @Test
    fun `HudState defaults to null captureExplanation`() {
        val state = HudState()
        assertThat(state.captureExplanation).isNull()
    }

    @Test
    fun `isHdrApplied included in explanation`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 5_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            isHdrApplied = true,
            reasons = listOf("high contrast scene", "HDR bracketing applied")
        )
        assertThat(explanation.isHdrApplied).isTrue()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "com.spectra.core.model.CaptureExplanationTest" 2>&1 | tail -5`
Expected: FAIL — `CaptureExplanation` does not exist

- [ ] **Step 3: Create CaptureExplanation data class**

```kotlin
// core/src/main/java/com/spectra/core/model/CaptureExplanation.kt
package com.spectra.core.model

data class CaptureExplanation(
    val iso: Int,
    val shutterSpeedNs: Long,
    val sceneLabel: String,
    val lightingLabel: String,
    val isHdrApplied: Boolean = false,
    val isPortraitBokeh: Boolean = false,
    val isNightMode: Boolean = false,
    val reasons: List<String> = emptyList()
) {
    val isoLabel: String
        get() = "ISO $iso"

    val shutterLabel: String
        get() {
            if (shutterSpeedNs <= 0L) return "Auto"
            val denominator = 1_000_000_000L / shutterSpeedNs
            return when {
                shutterSpeedNs >= 1_000_000_000L -> "${shutterSpeedNs / 1_000_000_000L}s"
                denominator >= 1 -> "1/${denominator}s"
                else -> "${shutterSpeedNs / 1_000_000}ms"
            }
        }

    val headline: String
        get() = "AI chose $isoLabel · $shutterLabel"

    val summaryReasons: List<String>
        get() = reasons.take(3)
}
```

- [ ] **Step 4: Add captureExplanation field to HudState**

In `core/src/main/java/com/spectra/core/model/HudState.kt`, add after the `analysisHeight` field (before the closing parenthesis):

```kotlin
    val captureExplanation: CaptureExplanation? = null,
    val showAiExplainer: Boolean = false
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "com.spectra.core.model.CaptureExplanationTest" 2>&1 | tail -5`
Expected: PASS — all 8 tests pass

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/spectra/core/model/CaptureExplanation.kt core/src/main/java/com/spectra/core/model/HudState.kt core/src/test/java/com/spectra/core/model/CaptureExplanationTest.kt
git commit -m "feat: add CaptureExplanation model and HudState field for post-capture AI explainer"
```

---

### Task 5: Create AiExplainerOverlay Composable

Build the post-capture overlay that shows what AI decisions were made, displayed as a translucent overlay for 2 seconds after capture.

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt`

- [ ] **Step 1: Create the AiExplainerOverlay composable**

```kotlin
// app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt
package com.spectra.app.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.CaptureExplanation

@Composable
fun AiExplainerOverlay(
    explanation: CaptureExplanation?,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible && explanation != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier
    ) {
        if (explanation != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✨",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WHAT AI DID",
                        color = HudColors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = explanation.headline,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )

                if (explanation.summaryReasons.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "because:",
                        color = HudColors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    for (reason in explanation.summaryReasons) {
                        Row(
                            modifier = Modifier.padding(vertical = 1.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                color = HudColors.accent,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = reason,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                if (explanation.isHdrApplied || explanation.isPortraitBokeh || explanation.isNightMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (explanation.isHdrApplied) {
                            AiBadge("HDR")
                        }
                        if (explanation.isPortraitBokeh) {
                            AiBadge("BOKEH")
                        }
                        if (explanation.isNightMode) {
                            AiBadge("NIGHT")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiBadge(label: String) {
    Text(
        text = label,
        color = HudColors.accent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(HudColors.accent.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/review/AiExplainerOverlay.kt
git commit -m "feat: add AiExplainerOverlay composable for post-capture AI decision display"
```

---

### Task 6: Build CaptureExplanation at Capture Time and Wire to HUD

Generate the `CaptureExplanation` from current sensor data and scene analysis when the shutter fires. Show the explainer overlay for 2 seconds, then auto-dismiss.

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add buildCaptureExplanation helper method**

Add to `CameraViewModel.kt` before the `onCleared()` method:

```kotlin
    private fun buildCaptureExplanation(): CaptureExplanation {
        val state = _hudState.value
        val reasons = mutableListOf<String>()

        if (state.sceneLabel.isNotEmpty() && state.sceneLabel != "READY") {
            reasons.add("${state.sceneLabel.lowercase()} scene detected")
        }

        if (state.lightingLabel.isNotEmpty() && state.lightingLabel != "—") {
            reasons.add("${state.lightingLabel.lowercase()} lighting")
        }

        if (state.motionLevel >= 3) {
            reasons.add("fast motion — high shutter speed selected")
        } else if (state.motionLevel >= 2) {
            reasons.add("moderate motion detected")
        }

        if (state.isHdrActive) {
            reasons.add("high contrast — HDR bracketing applied")
        }

        if (state.faceCount > 0) {
            reasons.add("${state.faceCount} face${if (state.faceCount > 1) "s" else ""} detected")
        }

        if (state.isLowLight) {
            reasons.add("low light — multi-frame noise reduction")
        }

        return CaptureExplanation(
            iso = if (state.actualIso > 0) state.actualIso else state.settings.iso,
            shutterSpeedNs = if (state.actualShutterSpeedNs > 0) state.actualShutterSpeedNs else
                (1_000_000_000L / state.settings.shutterSpeedDenominator.coerceAtLeast(1)),
            sceneLabel = state.sceneLabel,
            lightingLabel = state.lightingLabel,
            isHdrApplied = state.isHdrActive,
            isPortraitBokeh = state.mode == CameraMode.PORT && state.faceCount > 0,
            isNightMode = state.mode == CameraMode.NIGHT || state.isLowLight,
            reasons = reasons
        )
    }
```

Add the import at the top of the file:
```kotlin
import com.spectra.core.model.CaptureExplanation
```

- [ ] **Step 2: Generate explanation and show overlay on capture**

In `CameraViewModel.kt`, inside the `capturePhotoInternal()` method, right after the line:
```kotlin
        _hudState.update { it.copy(showCaptureFlash = true, isCapturing = true) }
```

Add:
```kotlin
        val explanation = buildCaptureExplanation()
```

Then, inside the first `_hudState.update` after `captureManager.captureSmartPhoto(...)` (around line 648), add the explanation fields:

Replace:
```kotlin
            _hudState.update { it.copy(
                lastCapturedUri = result.bestOriginalUri,
                showCaptureFlash = false,
                isCapturing = false,
                showSmartReview = true,
                bestOriginalUri = result.bestOriginalUri,
                aiEnhancedUri = null,
                isEnhancing = true
            )}
```

With:
```kotlin
            _hudState.update { it.copy(
                lastCapturedUri = result.bestOriginalUri,
                showCaptureFlash = false,
                isCapturing = false,
                showSmartReview = true,
                bestOriginalUri = result.bestOriginalUri,
                aiEnhancedUri = null,
                isEnhancing = true,
                captureExplanation = explanation,
                showAiExplainer = true
            )}
```

- [ ] **Step 3: Add auto-dismiss timer for explainer overlay**

Right after the `_hudState.update` block from Step 2, add:

```kotlin
            viewModelScope.launch {
                delay(3000)
                _hudState.update { it.copy(showAiExplainer = false) }
            }
```

- [ ] **Step 4: Add dismissAiExplainer method**

Add to `CameraViewModel.kt`:

```kotlin
    fun dismissAiExplainer() {
        _hudState.update { it.copy(showAiExplainer = false) }
    }
```

- [ ] **Step 5: Clear explanation on review dismiss**

In the `dismissSmartReview()` method, add the explanation fields to the update:

Replace:
```kotlin
    fun dismissSmartReview() {
        smartCaptureFrames = null
        _hudState.update { it.copy(
            showSmartReview = false,
            bestOriginalUri = null,
            aiEnhancedUri = null,
            isEnhancing = false
        )}
    }
```

With:
```kotlin
    fun dismissSmartReview() {
        smartCaptureFrames = null
        _hudState.update { it.copy(
            showSmartReview = false,
            bestOriginalUri = null,
            aiEnhancedUri = null,
            isEnhancing = false,
            captureExplanation = null,
            showAiExplainer = false
        )}
    }
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: build CaptureExplanation at capture time and wire to HUD with 3-second auto-dismiss"
```

---

### Task 7: Wire AiExplainerOverlay into ViewfinderScreen

Add the explainer overlay composable to the viewfinder screen so it appears after capture.

**Files:**
- Modify: `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`

- [ ] **Step 1: Add the import and composable call**

In `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`, add the import:

```kotlin
import com.spectra.app.ui.review.AiExplainerOverlay
```

Then, find where the `SmartReviewOverlay` or `ReviewOverlay` composable is placed. Add the `AiExplainerOverlay` INSIDE the same parent `Box` but positioned at the bottom, before the review overlays:

```kotlin
        AiExplainerOverlay(
            explanation = hudState.captureExplanation,
            isVisible = hudState.showAiExplainer && !hudState.showSmartReview,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        )
```

The explainer is hidden when the smart review overlay is showing (since the review takes over the full screen). It appears in the brief moment after capture flash completes and before the review overlay slides in.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt
git commit -m "feat: wire AiExplainerOverlay into ViewfinderScreen at bottom-center position"
```

---

### Task 8: Add Expose-for-Highlights Bias to HdrProcessor

Modify the HDR bracket calculation to bias the base exposure negatively (-0.5 to -1.0 EV) in high-contrast scenes, preserving highlights that can be recovered computationally.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`
- Test: `camera/src/test/java/com/spectra/camera/ExposeForHighlightsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// camera/src/test/java/com/spectra/camera/ExposeForHighlightsTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExposeForHighlightsTest {

    @Test
    fun `computeBracketExposuresForHighlights biases base exposure down`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -1.0f)
        val baseFrame = brackets[1]
        assertThat(baseFrame.first).isLessThan(baseNs)
    }

    @Test
    fun `highlight bias of -1 EV halves base exposure`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -1.0f)
        val baseFrame = brackets[1]
        assertThat(baseFrame.first).isEqualTo(5_000_000L)
    }

    @Test
    fun `highlight bias of -0_5 EV reduces base by sqrt(2)`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -0.5f)
        val baseFrame = brackets[1]
        val expected = (baseNs / Math.pow(2.0, 0.5)).toLong()
        assertThat(baseFrame.first).isWithin(100_000L).of(expected)
    }

    @Test
    fun `highlight brackets still ordered underexposed to overexposed`() {
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(10_000_000L, 200, evBias = -1.0f)
        assertThat(brackets[0].first).isLessThan(brackets[1].first)
        assertThat(brackets[1].first).isLessThan(brackets[2].first)
    }

    @Test
    fun `highlight brackets keep ISO constant`() {
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(10_000_000L, 400, evBias = -0.5f)
        for (bracket in brackets) {
            assertThat(bracket.second).isEqualTo(400)
        }
    }

    @Test
    fun `zero bias matches normal bracket computation`() {
        val baseNs = 10_000_000L
        val biased = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = 0f)
        val normal = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(biased[0].first).isEqualTo(normal[0].first)
        assertThat(biased[1].first).isEqualTo(normal[1].first)
        assertThat(biased[2].first).isEqualTo(normal[2].first)
    }

    @Test
    fun `computeHighlightEvBias returns negative bias for high contrast`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.5f)
        assertThat(bias).isLessThan(0f)
    }

    @Test
    fun `computeHighlightEvBias returns zero for low contrast`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.05f)
        assertThat(bias).isEqualTo(0f)
    }

    @Test
    fun `computeHighlightEvBias is clamped to -1 EV`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 1.0f)
        assertThat(bias).isAtLeast(-1.0f)
    }

    @Test
    fun `computeHighlightEvBias scales with contrast`() {
        val lowBias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.2f)
        val highBias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.8f)
        assertThat(highBias).isLessThan(lowBias)
    }

    @Test
    fun `computeShadowBoostStrength is zero for low contrast`() {
        val boost = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.05f)
        assertThat(boost).isEqualTo(0f)
    }

    @Test
    fun `computeShadowBoostStrength increases with contrast`() {
        val low = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.2f)
        val high = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.7f)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `computeShadowBoostStrength is clamped to 1`() {
        val boost = HdrProcessor.computeShadowBoostStrength(sceneContrast = 1.0f)
        assertThat(boost).isAtMost(1.0f)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ExposeForHighlightsTest" 2>&1 | tail -5`
Expected: FAIL — `computeBracketExposuresForHighlights`, `computeHighlightEvBias`, and `computeShadowBoostStrength` do not exist

- [ ] **Step 3: Add expose-for-highlights methods to HdrProcessor**

In `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`, add to the `companion object`:

```kotlin
        fun computeBracketExposuresForHighlights(
            baseExposureNs: Long,
            baseIso: Int,
            evBias: Float
        ): List<Pair<Long, Int>> {
            val biasedBase = if (evBias != 0f) {
                (baseExposureNs / Math.pow(2.0, (-evBias).toDouble())).toLong().coerceAtLeast(1L)
            } else {
                baseExposureNs
            }
            val underExposure = biasedBase / 4
            val overExposure = biasedBase * 4
            return listOf(
                Pair(underExposure, baseIso),
                Pair(biasedBase, baseIso),
                Pair(overExposure, baseIso)
            )
        }

        fun computeHighlightEvBias(sceneContrast: Float): Float {
            if (sceneContrast < 0.15f) return 0f
            val normalized = ((sceneContrast - 0.15f) / 0.85f).coerceIn(0f, 1f)
            return -(normalized * 1.0f).coerceIn(0f, 1.0f)
        }

        fun computeShadowBoostStrength(sceneContrast: Float): Float {
            if (sceneContrast < 0.15f) return 0f
            val normalized = ((sceneContrast - 0.15f) / 0.85f).coerceIn(0f, 1f)
            return (normalized * 1.0f).coerceIn(0f, 1.0f)
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.ExposeForHighlightsTest" 2>&1 | tail -5`
Expected: PASS — all 13 tests pass

- [ ] **Step 5: Run existing HdrProcessor tests for regression**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.HdrProcessorTest" 2>&1 | tail -5`
Expected: PASS — no regression

- [ ] **Step 6: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/HdrProcessor.kt camera/src/test/java/com/spectra/camera/ExposeForHighlightsTest.kt
git commit -m "feat: add expose-for-highlights bracket bias and shadow boost strength to HdrProcessor"
```

---

### Task 9: Wire Expose-for-Highlights into Capture Pipeline

Apply the highlight EV bias in the HDR bracket path and add shadow recovery in the single-frame high-contrast path.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add applyShadowRecovery method to CaptureManager**

Add to `camera/src/main/java/com/spectra/camera/CaptureManager.kt`:

```kotlin
    private fun applyShadowRecovery(bitmap: Bitmap, strength: Float) {
        if (strength <= 0f) return

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val lum = 0.299f * r + 0.587f * g + 0.114f * b

            if (lum < 0.4f) {
                val shadowFactor = 1f - (lum / 0.4f)
                val boost = 1f + strength * shadowFactor * 0.6f
                val rOut = (r * boost * 255f).toInt().coerceIn(0, 255)
                val gOut = (g * boost * 255f).toInt().coerceIn(0, 255)
                val bOut = (b * boost * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d("CaptureManager", "Shadow recovery applied: strength=${"%.2f".format(strength)}")
    }
```

- [ ] **Step 2: Wire shadow recovery into applyPostProcess for high-contrast scenes**

In `CaptureManager.kt`, inside the `applyPostProcess` method, add a `sceneContrast` parameter. First, update the method signature:

Replace:
```kotlin
    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false) {
```

With:
```kotlin
    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false, sceneContrast: Float = 0f) {
```

Then, inside the method, right AFTER the HDR tone map block:
```kotlin
            if (isHdr) {
                applyHdrToneMap(result)
                Log.d("CaptureManager", "HDR tone mapping applied")
            }
```

Add:
```kotlin
            if (sceneContrast > 0.15f && !isHdr) {
                val shadowStrength = HdrProcessor.computeShadowBoostStrength(sceneContrast)
                applyShadowRecovery(result, shadowStrength)
            }
```

- [ ] **Step 3: Update saveProcessedCopy to accept sceneContrast**

In `CaptureManager.kt`, update the `saveProcessedCopy` method signature to include `sceneContrast`:

Replace:
```kotlin
    suspend fun saveProcessedCopy(
        rawUri: String,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList(),
        isPortraitMode: Boolean = false
    ): String {
```

With:
```kotlin
    suspend fun saveProcessedCopy(
        rawUri: String,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList(),
        isPortraitMode: Boolean = false,
        sceneContrast: Float = 0f
    ): String {
```

And update the `applyPostProcess` call inside it to pass through `sceneContrast`:

Replace:
```kotlin
                applyPostProcess(Uri.parse(copyUri), beautyLevel, style, isFrontCamera, isHdr, faceRects, isPortraitMode)
```

With:
```kotlin
                applyPostProcess(Uri.parse(copyUri), beautyLevel, style, isFrontCamera, isHdr, faceRects, isPortraitMode, sceneContrast)
```

- [ ] **Step 4: Wire expose-for-highlights into ViewModel's capture path**

In `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`, update the `saveProcessedCopy` call inside `capturePhotoInternal()` to pass scene contrast.

Find the call:
```kotlin
                    val processedUri = captureManager.saveProcessedCopy(
                        result.bestOriginalUri,
                        state.beautyLevel,
                        state.photoStyle,
                        state.isFrontCamera,
                        state.isHdrActive,
                        lastDetectedFaceRects,
                        isPortraitMode = state.mode == CameraMode.PORT
                    )
```

Replace with:
```kotlin
                    val processedUri = captureManager.saveProcessedCopy(
                        result.bestOriginalUri,
                        state.beautyLevel,
                        state.photoStyle,
                        state.isFrontCamera,
                        state.isHdrActive,
                        lastDetectedFaceRects,
                        isPortraitMode = state.mode == CameraMode.PORT,
                        sceneContrast = state.sceneContrast
                    )
```

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: wire expose-for-highlights shadow recovery into capture pipeline for high-contrast scenes"
```

---

### Task 10: Apply Highlight EV Bias in HDR Bracket Path

When HDR bracketing is active and the scene has high contrast, use the biased bracket exposures instead of standard ones. This completes the expose-for-highlights strategy.

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Update HDR bracket capture to use highlight bias (if HDR bracket path exists from Phase 5A)**

If Phase 5A Task 9 wired `captureHdrBracket` into the ViewModel, find that HDR capture block and update it to compute highlight-biased brackets.

If the HDR bracket path is in `capturePhotoInternal()`, modify the section where `captureHdrBracket` is called. Before the `captureManager.captureHdrBracket(...)` call, compute the EV bias:

```kotlin
                val evBias = HdrProcessor.computeHighlightEvBias(state.sceneContrast)
```

And add a new method to `CaptureManager` that accepts the bias (or pass it to the existing method). The simplest approach: add an `evBias` parameter to `captureHdrBracket`:

In `CaptureManager.kt`, update the `captureHdrBracket` method to accept `evBias`:

After the existing parameter list, add:
```kotlin
    evBias: Float = 0f,
```

Then, replace the bracket computation line inside `captureHdrBracket`:

Replace:
```kotlin
        val brackets = HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
```

With:
```kotlin
        val brackets = if (evBias != 0f) {
            HdrProcessor.computeBracketExposuresForHighlights(baseExposureNs, baseIso, evBias)
        } else {
            HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
        }
```

- [ ] **Step 2: Pass evBias from ViewModel to CaptureManager**

If the HDR bracket call exists in the ViewModel (from Phase 5A Task 9), add the `evBias` parameter:

```kotlin
                val evBias = com.spectra.camera.HdrProcessor.computeHighlightEvBias(state.sceneContrast)
                val hdrUri = captureManager.captureHdrBracket(
                    imageCapture,
                    baseExposureNs = state.actualShutterSpeedNs,
                    baseIso = state.actualIso,
                    applyBracketSettings = { exposureNs, iso ->
                        cameraController.applyBracketExposure(exposureNs, iso)
                    },
                    restoreAutoExposure = {
                        resetHardwareToAuto()
                    },
                    beautyLevel = state.beautyLevel,
                    style = state.photoStyle,
                    isFrontCamera = state.isFrontCamera,
                    faceRects = lastDetectedFaceRects,
                    isPortraitMode = state.mode == CameraMode.PORT,
                    evBias = evBias
                )
```

If the HDR bracket path doesn't exist yet (Phase 5A not implemented), the `evBias` parameter defaults to `0f` and has no effect until Phase 5A is completed. The code paths are safe.

- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: apply highlight EV bias in HDR bracket path for expose-for-highlights strategy"
```

---

### Task 11: Final Integration Test — Run All Tests

Verify the entire test suite passes after all Phase 5D changes.

**Files:**
- No new files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests pass (existing + new Phase 5D tests)

- [ ] **Step 2: Verify no compilation errors**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit if any test fixes were needed**

If any tests needed fixes, commit them:

```bash
git add -A
git commit -m "chore: verify Phase 5D integration — all tests pass"
```

---

## Self-Review Checklist

### Spec coverage:

| Spec Item | Task(s) | Status |
|-----------|---------|--------|
| 17. Highlight Rolloff — shoulder Bezier function | Task 1 | Covered |
| 17. Highlight Rolloff — per-style parameters | Task 1 | Covered |
| 17. Highlight Rolloff — wired into post-processing | Task 2 | Covered |
| 18. Motion Coaching — MotionType enum | Task 3 | Covered |
| 18. Motion Coaching — camera shake message | Task 3 | Covered |
| 18. Motion Coaching — subject motion message | Task 3 | Covered |
| 18. Motion Coaching — pan motion message | Task 3 | Covered |
| 19. AI Explainer — CaptureExplanation model | Task 4 | Covered |
| 19. AI Explainer — overlay composable | Task 5 | Covered |
| 19. AI Explainer — build explanation at capture | Task 6 | Covered |
| 19. AI Explainer — wire to ViewfinderScreen | Task 7 | Covered |
| 19. AI Explainer — auto-dismiss after timeout | Task 6 | Covered |
| 20. Expose-for-Highlights — HDR bracket EV bias | Tasks 8, 10 | Covered |
| 20. Expose-for-Highlights — shadow recovery in single-frame | Task 9 | Covered |
| 20. Expose-for-Highlights — contrast-based bias strength | Task 8 | Covered |

### Placeholder scan: No TBD/TODO/placeholder patterns found.

### Type consistency check:
- `ToneCurveEngine.highlightShoulder` -> returns `Int` -- used consistently in `buildHighlightRolloffCurve` and `applyHighlightRolloffToBitmap`
- `ToneCurveEngine.HighlightParams` -> data class with `shoulderStart: Int`, `maxOutput: Int`, `strength: Float` -- used consistently in Tasks 1-2
- `ToneCurveEngine.styleHighlightParams` -> returns `HighlightParams` -- used in both test and production code
- `MotionType` enum -> used in `SceneAnalysis.motionType` field and `CoachingEngine.motionTypeHint()` -- consistent
- `CaptureExplanation` -> data class in core module, used by app module's ViewModel and overlay -- correct module boundary
- `CaptureExplanation.shutterLabel` -> formats `shutterSpeedNs: Long` to display string -- tested with multiple edge cases
- `HdrProcessor.computeBracketExposuresForHighlights` -> returns `List<Pair<Long, Int>>` -- same type as `computeBracketExposures`
- `HdrProcessor.computeHighlightEvBias` -> returns `Float` (0 to -1.0) -- used as `evBias` parameter
- `HdrProcessor.computeShadowBoostStrength` -> returns `Float` (0 to 1.0) -- used as `strength` parameter in `applyShadowRecovery`
- `HudState.captureExplanation` -> `CaptureExplanation?` -- nullable, checked in overlay composable
- `HudState.showAiExplainer` -> `Boolean` -- controls overlay visibility

### Dependency check:
- Task 1 depends on `ToneCurveEngine.kt` from Phase 5B #6 existing
- Task 3 depends on `MotionType` enum from Phase 5B #7 -- creates it if not present
- Task 8 depends on `HdrProcessor.kt` from Phase 5A #3 existing with `companion object`
- Task 10 depends on `captureHdrBracket` from Phase 5A #8 -- degrades gracefully if not present (evBias defaults to 0f)
- All tasks are safe to execute even if Phase 5A/5B are not yet complete -- new methods have default parameters and fallbacks

### Test coverage:
- `ToneCurveHighlightRolloffTest` — 14 tests covering shoulder function, curve builder, style params, monotonicity, edge cases
- `MotionTypeCoachingTest` — 6 tests covering each MotionType and regression on static
- `CaptureExplanationTest` — 8 tests covering formatting, summary truncation, HudState default, HDR flag
- `ExposeForHighlightsTest` — 13 tests covering bracket bias, shadow boost, contrast thresholds, monotonicity
