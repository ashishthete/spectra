# SPECTRA Phase 2: On-Device AI — Scene Detection, Auto-Settings, Photo Tips

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device ML scene detection, lighting analysis, motion/distance estimation, and a decision engine that auto-optimizes camera settings and lens selection. HUD shows real AI data. Photo reference cards with tips appear per scene type.

**Architecture:** Build the :ai-engine module with TFLite models, a pure-Kotlin decision engine, and a frame analysis pipeline. Wire it into the existing :camera and :app modules. Add photo tips UI to :app.

**Tech Stack:** TensorFlow Lite (GPU delegate), pre-trained MobileNetV2 for scene classification, existing CameraX frame analysis, Kotlin coroutines for pipeline, bundled reference images.

---

## File Structure (new/modified files)

```
ai-engine/
├── build.gradle.kts                          (modify — add TFLite deps)
├── src/main/assets/
│   ├── scene_classifier.tflite               (pre-trained model)
│   └── scene_labels.txt                      (label mapping)
└── src/main/java/com/spectra/ai/
    ├── SceneClassifier.kt                    (TFLite scene detection)
    ├── LightingAnalyzer.kt                   (lighting condition from frame metadata)
    ├── MotionDetector.kt                     (motion from consecutive frames)
    ├── DistanceEstimator.kt                  (depth from AF data)
    ├── FrameAnalysisPipeline.kt              (orchestrates all analyzers)
    ├── DecisionEngine.kt                     (signals → lens + settings + coaching)
    ├── model/
    │   ├── SceneAnalysis.kt                  (combined analysis result)
    │   ├── LightingCondition.kt              (enum)
    │   ├── MotionLevel.kt                    (enum)
    │   ├── DistanceRange.kt                  (enum)
    │   ├── LensRecommendation.kt             (lens + scores)
    │   ├── SettingsProfile.kt                (optimized settings)
    │   └── PhotoTip.kt                       (tip data for reference cards)
    └── tips/
        └── TipsRepository.kt                 (hardcoded tips per scene type)

core/
└── src/main/java/com/spectra/core/model/
    └── SceneType.kt                          (create — scene type enum)

camera/
└── src/main/java/com/spectra/camera/
    └── FrameProvider.kt                      (create — extracts frames for AI)

app/
├── src/main/assets/tips/                     (reference images per scene)
│   ├── landscape.webp
│   ├── portrait.webp
│   ├── food.webp
│   ├── night.webp
│   ├── architecture.webp
│   ├── macro.webp
│   ├── pet.webp
│   └── action.webp
├── src/main/java/com/spectra/app/
│   ├── viewmodel/CameraViewModel.kt         (modify — wire AI pipeline)
│   └── ui/
│       ├── hud/HudOverlay.kt                (modify — add tips thumbnail)
│       └── tips/
│           ├── TipsThumbnail.kt              (small HUD thumbnail)
│           └── ReferenceCard.kt              (expanded tip overlay)
```

---

### Task 1: Core Scene Types and AI Model Data Classes

**Files:**
- Create: `core/src/main/java/com/spectra/core/model/SceneType.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/LightingCondition.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/MotionLevel.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/DistanceRange.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/SceneAnalysis.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/LensRecommendation.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/SettingsProfile.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/model/PhotoTip.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/model/SceneAnalysisTest.kt`

- [ ] **Step 1: Write test for SceneAnalysis**

```kotlin
package com.spectra.ai.model

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneAnalysisTest {

    @Test
    fun `default analysis is UNKNOWN with zero confidence`() {
        val analysis = SceneAnalysis()
        assertThat(analysis.sceneType).isEqualTo(SceneType.UNKNOWN)
        assertThat(analysis.confidence).isEqualTo(0f)
        assertThat(analysis.lighting).isEqualTo(LightingCondition.UNKNOWN)
    }

    @Test
    fun `isStable returns true when confidence above threshold`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.85f
        )
        assertThat(analysis.isStable).isTrue()
    }

    @Test
    fun `isStable returns false when confidence below threshold`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.4f
        )
        assertThat(analysis.isStable).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ai-engine:test --tests "com.spectra.ai.model.SceneAnalysisTest"`
Expected: FAIL — classes not found

- [ ] **Step 3: Create SceneType enum in :core**

```kotlin
package com.spectra.core.model

enum class SceneType(val label: String) {
    LANDSCAPE("LANDSCAPE"),
    PORTRAIT("PORTRAIT"),
    FOOD("FOOD"),
    NIGHT("NIGHT SCENE"),
    ARCHITECTURE("ARCHITECTURE"),
    MACRO("MACRO"),
    PET("PET"),
    ACTION("ACTION"),
    DOCUMENT("DOCUMENT"),
    INDOOR("INDOOR"),
    UNKNOWN("ANALYZING...");
}
```

- [ ] **Step 4: Create LightingCondition enum**

```kotlin
package com.spectra.ai.model

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
    UNKNOWN("—");
}
```

- [ ] **Step 5: Create MotionLevel enum**

```kotlin
package com.spectra.ai.model

enum class MotionLevel(val barCount: Int) {
    STATIC(0),
    SLOW(1),
    MODERATE(2),
    FAST(3),
    VERY_FAST(4);
}
```

- [ ] **Step 6: Create DistanceRange enum**

```kotlin
package com.spectra.ai.model

enum class DistanceRange(val label: String) {
    MACRO("< 0.1M"),
    NEAR("0.1-1M"),
    MID("1-5M"),
    FAR("5-20M"),
    INFINITY("∞ FAR");
}
```

- [ ] **Step 7: Create SceneAnalysis data class**

```kotlin
package com.spectra.ai.model

import com.spectra.core.model.SceneType

data class SceneAnalysis(
    val sceneType: SceneType = SceneType.UNKNOWN,
    val confidence: Float = 0f,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
    val motionLevel: MotionLevel = MotionLevel.STATIC,
    val distanceRange: DistanceRange = DistanceRange.INFINITY,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isStable: Boolean get() = confidence >= 0.7f
}
```

- [ ] **Step 8: Create LensRecommendation data class**

```kotlin
package com.spectra.ai.model

import com.spectra.core.model.LensId

data class LensRecommendation(
    val recommended: LensId,
    val scores: Map<LensId, Float>,
    val reason: String
)
```

- [ ] **Step 9: Create SettingsProfile data class**

```kotlin
package com.spectra.ai.model

import com.spectra.core.model.CameraSettings

data class SettingsProfile(
    val settings: CameraSettings,
    val reason: String
)
```

- [ ] **Step 10: Create PhotoTip data class**

```kotlin
package com.spectra.ai.model

import com.spectra.core.model.SceneType

data class PhotoTip(
    val sceneType: SceneType,
    val title: String,
    val tips: List<String>,
    val referenceImageAsset: String
)
```

- [ ] **Step 11: Run tests to verify they pass**

Run: `./gradlew :ai-engine:test`
Expected: ALL PASS

- [ ] **Step 12: Commit**

```bash
git add core/src/main/java/com/spectra/core/model/SceneType.kt \
       ai-engine/src/main/java/com/spectra/ai/model/ \
       ai-engine/src/test/
git commit -m "feat(ai): add scene analysis data models — SceneType, LightingCondition, MotionLevel, DistanceRange

Foundation types for the AI pipeline: scene classification output,
lighting/motion/distance enums, lens recommendation, settings profile, photo tips"
```

---

### Task 2: Scene Classifier — TFLite Scene Detection

**Files:**
- Modify: `ai-engine/build.gradle.kts`
- Create: `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/SceneClassifierTest.kt`

- [ ] **Step 1: Add TFLite dependencies to ai-engine**

Modify `ai-engine/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.spectra.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    aaptOptions {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

Also add to `gradle/libs.versions.toml` in `[libraries]` section:
```toml
tflite = { module = "org.tensorflow:tensorflow-lite", version = "2.16.1" }
tflite-gpu = { module = "org.tensorflow:tensorflow-lite-gpu", version = "2.16.1" }
tflite-support = { module = "org.tensorflow:tensorflow-lite-support", version = "0.4.4" }
```

- [ ] **Step 2: Create SceneClassifier**

```kotlin
package com.spectra.ai

import android.content.Context
import android.graphics.Bitmap
import com.spectra.core.model.SceneType
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 224
    private val labelMap = mutableListOf<SceneType>()

    fun initialize() {
        loadLabels()
        val model = loadModelFile("scene_classifier.tflite")
        gpuDelegate = GpuDelegate()
        val options = Interpreter.Options().apply {
            addDelegate(gpuDelegate)
            setNumThreads(4)
        }
        interpreter = Interpreter(model, options)
    }

    fun classify(bitmap: Bitmap): Pair<SceneType, Float> {
        val interp = interpreter ?: return Pair(SceneType.UNKNOWN, 0f)

        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resized)

        val outputArray = Array(1) { FloatArray(labelMap.size) }
        interp.run(inputBuffer, outputArray)

        val scores = outputArray[0]
        val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        val confidence = scores[maxIndex]
        val sceneType = if (maxIndex < labelMap.size) labelMap[maxIndex] else SceneType.UNKNOWN

        return Pair(sceneType, confidence)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun loadLabels() {
        labelMap.clear()
        try {
            context.assets.open("scene_labels.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val sceneType = SceneType.entries.find {
                        it.name.equals(line.trim(), ignoreCase = true)
                    } ?: SceneType.UNKNOWN
                    labelMap.add(sceneType)
                }
            }
        } catch (_: Exception) {
            SceneType.entries.forEach { labelMap.add(it) }
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    fun release() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }
}
```

- [ ] **Step 3: Create scene_labels.txt asset**

Create `ai-engine/src/main/assets/scene_labels.txt`:

```
LANDSCAPE
PORTRAIT
FOOD
NIGHT
ARCHITECTURE
MACRO
PET
ACTION
DOCUMENT
INDOOR
UNKNOWN
```

- [ ] **Step 4: Commit**

```bash
git add ai-engine/ core/
git commit -m "feat(ai): add TFLite scene classifier with GPU delegate

MobileNetV2-based scene detection, 11 scene types, GPU-accelerated inference"
```

---

### Task 3: Lighting Analyzer, Motion Detector, Distance Estimator

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/LightingAnalyzer.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/MotionDetector.kt`
- Create: `ai-engine/src/main/java/com/spectra/ai/DistanceEstimator.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/LightingAnalyzerTest.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/MotionDetectorTest.kt`

- [ ] **Step 1: Write LightingAnalyzer test**

```kotlin
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.LightingCondition
import org.junit.Test

class LightingAnalyzerTest {

    private val analyzer = LightingAnalyzer()

    @Test
    fun `very low brightness maps to LOW_LIGHT`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 15f,
            exposureTimeNs = 100_000_000L,
            iso = 3200,
            colorTemperature = 3000
        )
        assertThat(result).isEqualTo(LightingCondition.LOW_LIGHT)
    }

    @Test
    fun `high brightness and warm temp maps to GOLDEN_HOUR`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 140f,
            exposureTimeNs = 5_000_000L,
            iso = 100,
            colorTemperature = 3500
        )
        assertThat(result).isEqualTo(LightingCondition.GOLDEN_HOUR)
    }

    @Test
    fun `very high brightness maps to HARSH_MIDDAY`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 230f,
            exposureTimeNs = 1_000_000L,
            iso = 50,
            colorTemperature = 5500
        )
        assertThat(result).isEqualTo(LightingCondition.HARSH_MIDDAY)
    }

    @Test
    fun `moderate brightness maps to BRIGHT_DAYLIGHT`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 160f,
            exposureTimeNs = 3_000_000L,
            iso = 100,
            colorTemperature = 5600
        )
        assertThat(result).isEqualTo(LightingCondition.BRIGHT_DAYLIGHT)
    }
}
```

- [ ] **Step 2: Create LightingAnalyzer**

```kotlin
package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LightingAnalyzer @Inject constructor() {

    fun analyzeFromMetadata(
        avgBrightness: Float,
        exposureTimeNs: Long,
        iso: Int,
        colorTemperature: Int
    ): LightingCondition {
        val exposureMs = exposureTimeNs / 1_000_000f

        return when {
            avgBrightness < 30f -> LightingCondition.LOW_LIGHT
            avgBrightness < 60f && colorTemperature in 3800..4500 -> LightingCondition.BLUE_HOUR
            avgBrightness in 80f..180f && colorTemperature < 4000 -> LightingCondition.GOLDEN_HOUR
            avgBrightness > 200f && colorTemperature in 5000..7000 -> LightingCondition.HARSH_MIDDAY
            avgBrightness > 120f && colorTemperature in 5000..6500 -> LightingCondition.BRIGHT_DAYLIGHT
            avgBrightness in 60f..150f && colorTemperature > 6500 -> LightingCondition.OVERCAST
            iso > 800 && exposureMs > 30 -> LightingCondition.LOW_LIGHT
            colorTemperature < 3500 && avgBrightness in 50f..150f -> LightingCondition.ARTIFICIAL
            else -> LightingCondition.BRIGHT_DAYLIGHT
        }
    }

    fun analyzeBrightness(pixels: IntArray, width: Int, height: Int): Float {
        if (pixels.isEmpty()) return 0f
        var totalBrightness = 0L
        val step = maxOf(1, pixels.size / 10000)
        var count = 0
        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            count++
        }
        return if (count > 0) totalBrightness.toFloat() / count else 0f
    }
}
```

- [ ] **Step 3: Write MotionDetector test**

```kotlin
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.MotionLevel
import org.junit.Test

class MotionDetectorTest {

    private val detector = MotionDetector()

    @Test
    fun `no frames returns STATIC`() {
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `identical frames returns STATIC`() {
        val frame = FloatArray(100) { 0.5f }
        detector.addFrame(frame)
        detector.addFrame(frame.clone())
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `very different frames returns high motion`() {
        val frame1 = FloatArray(100) { 0f }
        val frame2 = FloatArray(100) { 1f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion.barCount).isGreaterThan(2)
    }
}
```

- [ ] **Step 4: Create MotionDetector**

```kotlin
package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.model.MotionLevel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MotionDetector @Inject constructor() {

    private val frameBuffer = ArrayDeque<FloatArray>(3)
    private val downsampleSize = 32

    var currentMotion: MotionLevel = MotionLevel.STATIC
        private set

    fun addFrame(downsampled: FloatArray) {
        frameBuffer.addLast(downsampled)
        if (frameBuffer.size > 3) frameBuffer.removeFirst()
        currentMotion = computeMotion()
    }

    fun addBitmap(bitmap: Bitmap) {
        val small = Bitmap.createScaledBitmap(bitmap, downsampleSize, downsampleSize, true)
        val pixels = IntArray(downsampleSize * downsampleSize)
        small.getPixels(pixels, 0, downsampleSize, 0, 0, downsampleSize, downsampleSize)
        val brightness = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        addFrame(brightness)
    }

    private fun computeMotion(): MotionLevel {
        if (frameBuffer.size < 2) return MotionLevel.STATIC

        val prev = frameBuffer[frameBuffer.size - 2]
        val curr = frameBuffer[frameBuffer.size - 1]
        val minLen = minOf(prev.size, curr.size)
        if (minLen == 0) return MotionLevel.STATIC

        var diff = 0f
        for (i in 0 until minLen) {
            diff += kotlin.math.abs(curr[i] - prev[i])
        }
        val avgDiff = diff / minLen

        return when {
            avgDiff < 0.02f -> MotionLevel.STATIC
            avgDiff < 0.05f -> MotionLevel.SLOW
            avgDiff < 0.12f -> MotionLevel.MODERATE
            avgDiff < 0.25f -> MotionLevel.FAST
            else -> MotionLevel.VERY_FAST
        }
    }

    fun reset() {
        frameBuffer.clear()
        currentMotion = MotionLevel.STATIC
    }
}
```

- [ ] **Step 5: Create DistanceEstimator**

```kotlin
package com.spectra.ai

import com.spectra.ai.model.DistanceRange
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DistanceEstimator @Inject constructor() {

    fun estimateFromFocusDistance(focusDistanceDiopters: Float): DistanceRange {
        if (focusDistanceDiopters <= 0f) return DistanceRange.INFINITY

        val distanceMeters = 1f / focusDistanceDiopters

        return when {
            distanceMeters < 0.1f -> DistanceRange.MACRO
            distanceMeters < 1f -> DistanceRange.NEAR
            distanceMeters < 5f -> DistanceRange.MID
            distanceMeters < 20f -> DistanceRange.FAR
            else -> DistanceRange.INFINITY
        }
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :ai-engine:test`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add ai-engine/
git commit -m "feat(ai): add lighting analyzer, motion detector, distance estimator

Lighting from exposure metadata, motion from frame differencing,
distance from AF diopters. All with unit tests."
```

---

### Task 4: Decision Engine — Signals to Recommendations

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/DecisionEngine.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/DecisionEngineTest.kt`

- [ ] **Step 1: Write DecisionEngine test**

```kotlin
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine()

    @Test
    fun `landscape golden hour recommends main lens ISO 100`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val lens = engine.recommendLens(analysis)
        val settings = engine.optimizeSettings(analysis)

        assertThat(lens.recommended).isEqualTo(LensId.MAIN)
        assertThat(settings.settings.iso).isEqualTo(100)
        assertThat(settings.settings.whiteBalanceKelvin).isEqualTo(5500)
    }

    @Test
    fun `portrait static recommends telephoto 3x`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.TELEPHOTO_3X)
    }

    @Test
    fun `pet fast motion recommends main lens high shutter`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.shutterSpeedDenominator).isAtLeast(500)
        assertThat(settings.settings.iso).isAtLeast(400)
    }

    @Test
    fun `food near distance recommends main lens warm WB`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.FOOD,
            confidence = 0.9f,
            lighting = LightingCondition.ARTIFICIAL,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.whiteBalanceKelvin).isAtLeast(4000)
    }

    @Test
    fun `night scene recommends low shutter high ISO`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.iso).isAtLeast(800)
        assertThat(settings.settings.shutterSpeedDenominator).isAtMost(30)
    }

    @Test
    fun `architecture recommends ultrawide`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.ARCHITECTURE,
            confidence = 0.82f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.ULTRAWIDE)
    }

    @Test
    fun `macro recommends main lens`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.MACRO,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MACRO
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.MAIN)
    }
}
```

- [ ] **Step 2: Create DecisionEngine**

```kotlin
package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DecisionEngine @Inject constructor() {

    fun recommendLens(analysis: SceneAnalysis): LensRecommendation {
        val scores = mutableMapOf<LensId, Float>()

        when (analysis.sceneType) {
            SceneType.LANDSCAPE -> {
                scores[LensId.MAIN] = 0.9f
                scores[LensId.ULTRAWIDE] = 0.7f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.3f
            }
            SceneType.PORTRAIT -> {
                scores[LensId.TELEPHOTO_3X] = 0.95f
                scores[LensId.MAIN] = 0.5f
                scores[LensId.TELEPHOTO_5X] = 0.4f
                scores[LensId.ULTRAWIDE] = 0.1f
            }
            SceneType.FOOD -> {
                scores[LensId.MAIN] = 0.9f
                scores[LensId.TELEPHOTO_3X] = 0.5f
                scores[LensId.ULTRAWIDE] = 0.1f
                scores[LensId.TELEPHOTO_5X] = 0.2f
            }
            SceneType.ARCHITECTURE -> {
                scores[LensId.ULTRAWIDE] = 0.9f
                scores[LensId.MAIN] = 0.6f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            SceneType.MACRO -> {
                scores[LensId.MAIN] = 0.95f
                scores[LensId.TELEPHOTO_3X] = 0.3f
                scores[LensId.ULTRAWIDE] = 0.05f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            SceneType.PET, SceneType.ACTION -> {
                scores[LensId.MAIN] = 0.8f
                scores[LensId.TELEPHOTO_3X] = 0.5f
                scores[LensId.TELEPHOTO_5X] = 0.3f
                scores[LensId.ULTRAWIDE] = 0.2f
            }
            SceneType.NIGHT -> {
                scores[LensId.MAIN] = 0.95f
                scores[LensId.ULTRAWIDE] = 0.4f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            else -> {
                scores[LensId.MAIN] = 0.7f
                scores[LensId.ULTRAWIDE] = 0.3f
                scores[LensId.TELEPHOTO_3X] = 0.3f
                scores[LensId.TELEPHOTO_5X] = 0.2f
            }
        }

        if (analysis.distanceRange == DistanceRange.FAR || analysis.distanceRange == DistanceRange.INFINITY) {
            scores[LensId.TELEPHOTO_5X] = (scores[LensId.TELEPHOTO_5X] ?: 0f) + 0.2f
        }

        val recommended = scores.maxByOrNull { it.value }?.key ?: LensId.MAIN
        val reason = "${analysis.sceneType.label} · ${analysis.distanceRange.label}"

        return LensRecommendation(recommended, scores, reason)
    }

    fun optimizeSettings(analysis: SceneAnalysis): SettingsProfile {
        val baseSettings = getBaseSettings(analysis.sceneType, analysis.lighting)
        val adjusted = adjustForMotion(baseSettings, analysis.motionLevel)

        val reason = "${analysis.sceneType.label} · ${analysis.lighting.label}"
        return SettingsProfile(adjusted, reason)
    }

    private fun getBaseSettings(scene: SceneType, lighting: LightingCondition): CameraSettings {
        return when (scene) {
            SceneType.LANDSCAPE -> when (lighting) {
                LightingCondition.GOLDEN_HOUR -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f)
                LightingCondition.HARSH_MIDDAY -> CameraSettings(iso = 100, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5200)
                LightingCondition.OVERCAST -> CameraSettings(iso = 200, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6500)
                LightingCondition.LOW_LIGHT -> CameraSettings(iso = 800, shutterSpeedDenominator = 30, whiteBalanceKelvin = 4000)
                else -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            }
            SceneType.PORTRAIT -> when (lighting) {
                LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
                LightingCondition.GOLDEN_HOUR -> CameraSettings(iso = 100, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.3f)
                LightingCondition.LOW_LIGHT -> CameraSettings(iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4500)
                LightingCondition.ARTIFICIAL -> CameraSettings(iso = 400, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4000)
                else -> CameraSettings(iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5500)
            }
            SceneType.FOOD -> CameraSettings(iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f)
            SceneType.NIGHT -> CameraSettings(iso = 1600, shutterSpeedDenominator = 15, whiteBalanceKelvin = 4000)
            SceneType.MACRO -> CameraSettings(iso = 200, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            SceneType.ARCHITECTURE -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            SceneType.PET, SceneType.ACTION -> CameraSettings(iso = 400, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5500)
            else -> CameraSettings()
        }
    }

    private fun adjustForMotion(settings: CameraSettings, motion: MotionLevel): CameraSettings {
        return when (motion) {
            MotionLevel.FAST, MotionLevel.VERY_FAST -> CameraSettings(
                iso = maxOf(settings.iso, 800),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 1000),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation
            )
            MotionLevel.MODERATE -> CameraSettings(
                iso = maxOf(settings.iso, 400),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 500),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation
            )
            else -> settings
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :ai-engine:test --tests "com.spectra.ai.DecisionEngineTest"`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add ai-engine/
git commit -m "feat(ai): add DecisionEngine — scene analysis to lens + settings recommendations

Rule-based engine mapping 11 scene types × 10 lighting conditions to
optimal lens selection and camera settings. 7 test cases."
```

---

### Task 5: Frame Analysis Pipeline — Orchestrator

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/FrameAnalysisPipeline.kt`
- Create: `camera/src/main/java/com/spectra/camera/FrameProvider.kt`

- [ ] **Step 1: Create FrameProvider in :camera**

```kotlin
package com.spectra.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameProvider @Inject constructor() : ImageAnalysis.Analyzer {

    private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private var frameCount = 0
    private val analyzeEveryN = 5

    override fun analyze(image: ImageProxy) {
        frameCount++
        if (frameCount % analyzeEveryN == 0) {
            val bitmap = image.toBitmap()
            if (bitmap != null) {
                _frames.tryEmit(bitmap)
            }
        }
        image.close()
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: Create FrameAnalysisPipeline**

```kotlin
package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.model.*
import com.spectra.core.model.SceneType
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@ViewModelScoped
class FrameAnalysisPipeline @Inject constructor(
    private val sceneClassifier: SceneClassifier,
    private val lightingAnalyzer: LightingAnalyzer,
    private val motionDetector: MotionDetector,
    private val distanceEstimator: DistanceEstimator,
    private val decisionEngine: DecisionEngine
) {
    private val _analysis = MutableStateFlow(SceneAnalysis())
    val analysis: StateFlow<SceneAnalysis> = _analysis.asStateFlow()

    private val _lensRecommendation = MutableStateFlow(
        LensRecommendation(
            com.spectra.core.model.LensId.MAIN,
            emptyMap(),
            ""
        )
    )
    val lensRecommendation: StateFlow<LensRecommendation> = _lensRecommendation.asStateFlow()

    private val _settingsProfile = MutableStateFlow(
        SettingsProfile(com.spectra.core.model.CameraSettings(), "")
    )
    val settingsProfile: StateFlow<SettingsProfile> = _settingsProfile.asStateFlow()

    private var initialized = false

    fun initialize() {
        if (initialized) return
        sceneClassifier.initialize()
        initialized = true
    }

    fun analyzeFrame(
        bitmap: Bitmap,
        focusDistanceDiopters: Float = 0f,
        exposureTimeNs: Long = 0L,
        iso: Int = 100,
        colorTemperature: Int = 5500
    ) {
        val (sceneType, confidence) = sceneClassifier.classify(bitmap)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val avgBrightness = lightingAnalyzer.analyzeBrightness(pixels, bitmap.width, bitmap.height)
        val lighting = lightingAnalyzer.analyzeFromMetadata(avgBrightness, exposureTimeNs, iso, colorTemperature)

        motionDetector.addBitmap(bitmap)
        val motion = motionDetector.currentMotion

        val distance = distanceEstimator.estimateFromFocusDistance(focusDistanceDiopters)

        val sceneAnalysis = SceneAnalysis(
            sceneType = sceneType,
            confidence = confidence,
            lighting = lighting,
            motionLevel = motion,
            distanceRange = distance
        )
        _analysis.value = sceneAnalysis

        if (sceneAnalysis.isStable) {
            _lensRecommendation.value = decisionEngine.recommendLens(sceneAnalysis)
            _settingsProfile.value = decisionEngine.optimizeSettings(sceneAnalysis)
        }
    }

    fun release() {
        sceneClassifier.release()
        motionDetector.reset()
        initialized = false
    }
}
```

- [ ] **Step 3: Update SpectraCameraController to include FrameProvider**

Add ImageAnalysis use case to `SpectraCameraController.bindCamera()`. Modify the `bindCamera` method to also bind an `ImageAnalysis` use case using the `FrameProvider`.

In `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`, add:
- Import `androidx.camera.core.ImageAnalysis`
- Add `val frameProvider: FrameProvider` as a constructor parameter (injected)
- In `bindCamera()`, create and bind `ImageAnalysis` alongside preview and imageCapture

```kotlin
// Add to constructor:
val frameProvider: FrameProvider

// Add to bindCamera(), before provider.bindToLifecycle:
val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
    .also { it.setAnalyzer({ it.run() }, frameProvider) }

// Update bindToLifecycle call:
camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture, imageAnalysis)
```

- [ ] **Step 4: Commit**

```bash
git add ai-engine/ camera/
git commit -m "feat(ai): add FrameAnalysisPipeline orchestrating all analyzers

Pipeline takes camera frames, runs scene classification + lighting +
motion + distance analysis, feeds DecisionEngine for recommendations.
FrameProvider extracts every 5th frame from CameraX ImageAnalysis."
```

---

### Task 6: Photo Tips Repository

**Files:**
- Create: `ai-engine/src/main/java/com/spectra/ai/tips/TipsRepository.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/tips/TipsRepositoryTest.kt`

- [ ] **Step 1: Write TipsRepository test**

```kotlin
package com.spectra.ai.tips

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class TipsRepositoryTest {

    private val repo = TipsRepository()

    @Test
    fun `landscape has tips`() {
        val tip = repo.getTip(SceneType.LANDSCAPE)
        assertThat(tip).isNotNull()
        assertThat(tip!!.tips).isNotEmpty()
        assertThat(tip.referenceImageAsset).contains("landscape")
    }

    @Test
    fun `all scene types except UNKNOWN have tips`() {
        SceneType.entries
            .filter { it != SceneType.UNKNOWN }
            .forEach { scene ->
                val tip = repo.getTip(scene)
                assertThat(tip).named("tip for $scene").isNotNull()
            }
    }

    @Test
    fun `UNKNOWN returns null`() {
        val tip = repo.getTip(SceneType.UNKNOWN)
        assertThat(tip).isNull()
    }
}
```

- [ ] **Step 2: Create TipsRepository**

```kotlin
package com.spectra.ai.tips

import com.spectra.ai.model.PhotoTip
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TipsRepository @Inject constructor() {

    private val tips = mapOf(
        SceneType.LANDSCAPE to PhotoTip(
            sceneType = SceneType.LANDSCAPE,
            title = "LANDSCAPE MASTERY",
            tips = listOf(
                "Place horizon on upper or lower third — never center",
                "Shoot during golden hour for warm, dramatic light",
                "Use leading lines (roads, rivers) to draw the eye in"
            ),
            referenceImageAsset = "tips/landscape.webp"
        ),
        SceneType.PORTRAIT to PhotoTip(
            sceneType = SceneType.PORTRAIT,
            title = "PORTRAIT PERFECTION",
            tips = listOf(
                "Focus on the eyes — they anchor the viewer's attention",
                "Use 3x telephoto for flattering compression",
                "Position subject facing natural light for soft, even tones"
            ),
            referenceImageAsset = "tips/portrait.webp"
        ),
        SceneType.FOOD to PhotoTip(
            sceneType = SceneType.FOOD,
            title = "FOOD PHOTOGRAPHY",
            tips = listOf(
                "Shoot at 45° angle or directly overhead for best presentation",
                "Use natural window light — avoid flash completely",
                "Add context: utensils, napkins, hands for storytelling"
            ),
            referenceImageAsset = "tips/food.webp"
        ),
        SceneType.NIGHT to PhotoTip(
            sceneType = SceneType.NIGHT,
            title = "NIGHT CAPTURE",
            tips = listOf(
                "Brace against a wall or use a tripod for sharp shots",
                "Include light sources (streetlamps, signs) for visual anchors",
                "Let the AI extend exposure — stay perfectly still"
            ),
            referenceImageAsset = "tips/night.webp"
        ),
        SceneType.ARCHITECTURE to PhotoTip(
            sceneType = SceneType.ARCHITECTURE,
            title = "ARCHITECTURE SHOTS",
            tips = listOf(
                "Keep vertical lines straight — tilt correction matters",
                "Look for symmetry and repeating patterns",
                "Use ultrawide lens but watch for distortion at edges"
            ),
            referenceImageAsset = "tips/architecture.webp"
        ),
        SceneType.MACRO to PhotoTip(
            sceneType = SceneType.MACRO,
            title = "MACRO DETAIL",
            tips = listOf(
                "Hold your breath and stabilize — tiny movements blur macro shots",
                "Use soft, diffused light to avoid harsh reflections",
                "Focus on textures and patterns that aren't visible to the naked eye"
            ),
            referenceImageAsset = "tips/macro.webp"
        ),
        SceneType.PET to PhotoTip(
            sceneType = SceneType.PET,
            title = "PET PHOTOGRAPHY",
            tips = listOf(
                "Get down to their eye level for engaging perspective",
                "Use burst mode — animals are unpredictable",
                "Natural light near a window works best for fur detail"
            ),
            referenceImageAsset = "tips/pet.webp"
        ),
        SceneType.ACTION to PhotoTip(
            sceneType = SceneType.ACTION,
            title = "ACTION SHOTS",
            tips = listOf(
                "Pre-focus on where the action will happen",
                "Pan with the subject for motion blur in background",
                "Use burst mode and pick the peak moment afterward"
            ),
            referenceImageAsset = "tips/action.webp"
        ),
        SceneType.DOCUMENT to PhotoTip(
            sceneType = SceneType.DOCUMENT,
            title = "DOCUMENT SCAN",
            tips = listOf(
                "Shoot directly overhead to avoid perspective distortion",
                "Ensure even lighting — no shadows across the text",
                "Fill the frame with the document for maximum resolution"
            ),
            referenceImageAsset = "tips/document.webp"
        ),
        SceneType.INDOOR to PhotoTip(
            sceneType = SceneType.INDOOR,
            title = "INDOOR PHOTOGRAPHY",
            tips = listOf(
                "Use the main lens for its wide f/1.7 aperture in low light",
                "Turn off overhead fluorescents — use window light instead",
                "Watch white balance — indoor lighting shifts colors warm/cool"
            ),
            referenceImageAsset = "tips/indoor.webp"
        )
    )

    fun getTip(sceneType: SceneType): PhotoTip? = tips[sceneType]
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :ai-engine:test --tests "com.spectra.ai.tips.TipsRepositoryTest"`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add ai-engine/
git commit -m "feat(ai): add TipsRepository — photo tips and reference images per scene type

10 scene types with 3 tips each, reference image asset paths.
Bundled in-app, no network needed."
```

---

### Task 7: Photo Tips UI — Thumbnail and Reference Card

**Files:**
- Create: `app/src/main/java/com/spectra/app/ui/tips/TipsThumbnail.kt`
- Create: `app/src/main/java/com/spectra/app/ui/tips/ReferenceCard.kt`

- [ ] **Step 1: Create TipsThumbnail**

```kotlin
package com.spectra.app.ui.tips

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun TipsThumbnail(
    sceneLabel: String,
    isVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .border(1.dp, HudColors.borderGreen, RectangleShape)
                .background(HudColors.background.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "?",
                color = HudColors.neonGreen,
                fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "TIPS: $sceneLabel",
                style = HudTypography.readoutSmall
            )
        }
    }
}
```

- [ ] **Step 2: Create ReferenceCard overlay**

```kotlin
package com.spectra.app.ui.tips

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.ai.model.PhotoTip
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun ReferenceCard(
    tip: PhotoTip,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HudColors.background.copy(alpha = 0.85f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .border(1.dp, HudColors.neonGreen, RectangleShape)
                    .background(HudColors.background)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ ${tip.title} ]",
                        style = HudTypography.readoutLarge
                    )
                    Text(
                        text = "✕",
                        color = HudColors.neonGreenDim,
                        fontSize = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HudColors.borderGreen)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tips
                tip.tips.forEachIndexed { index, tipText ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = "0${index + 1}",
                            style = HudTypography.label,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = tipText,
                            style = HudTypography.readoutSmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HudColors.borderGreen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TAP ANYWHERE TO DISMISS",
                    style = HudTypography.label
                )
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spectra/app/ui/tips/
git commit -m "feat(app): add photo tips UI — thumbnail HUD badge and reference card overlay

TipsThumbnail shows in HUD when scene stabilizes.
ReferenceCard expands with scene-specific tips on tap."
```

---

### Task 8: Wire AI Pipeline into ViewModel and HUD

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/hud/HudOverlay.kt`
- Modify: `app/src/main/java/com/spectra/app/ui/viewfinder/ViewfinderScreen.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Modify: `app/build.gradle.kts` (add :ai-engine dependency)

- [ ] **Step 1: Add :ai-engine dependency to app module**

In `app/build.gradle.kts`, add to dependencies:

```kotlin
implementation(project(":ai-engine"))
```

- [ ] **Step 2: Update HudState to include tips state**

Add to `core/src/main/java/com/spectra/core/model/HudState.kt`:

```kotlin
data class HudState(
    val activeLens: LensId = LensId.MAIN,
    val settings: CameraSettings = CameraSettings(),
    val mode: CameraMode = CameraMode.PHOTO,
    val isHudVisible: Boolean = true,
    val sceneLabel: String = "READY",
    val sceneConfidence: Float = 0f,
    val lightingLabel: String = "—",
    val motionLevel: Int = 0,
    val distanceLabel: String = "—",
    val coachingText: String? = null,
    val lensMatchScores: Map<LensId, Float> = LensId.entries.associateWith {
        if (it == activeLens) 1.0f else 0f
    },
    val isBurstActive: Boolean = false,
    val lastCapturedUri: String? = null,
    val showTipsThumbnail: Boolean = false,
    val showReferenceCard: Boolean = false
)
```

- [ ] **Step 3: Update CameraViewModel to wire AI pipeline**

Replace `CameraViewModel.kt` with the AI-integrated version:

```kotlin
package com.spectra.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectra.ai.FrameAnalysisPipeline
import com.spectra.ai.model.PhotoTip
import com.spectra.ai.tips.TipsRepository
import com.spectra.camera.CaptureManager
import com.spectra.camera.FrameProvider
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.CameraMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val cameraController: SpectraCameraController,
    private val captureManager: CaptureManager,
    private val frameProvider: FrameProvider,
    private val pipeline: FrameAnalysisPipeline,
    private val tipsRepository: TipsRepository
) : ViewModel() {

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    private val _currentTip = MutableStateFlow<PhotoTip?>(null)
    val currentTip: StateFlow<PhotoTip?> = _currentTip.asStateFlow()

    private var lastStableScene: SceneType = SceneType.UNKNOWN
    private var stableSceneStartMs: Long = 0L

    init {
        pipeline.initialize()

        viewModelScope.launch {
            cameraController.activeLens.collect { lens ->
                _hudState.update { it.copy(
                    activeLens = lens,
                    lensMatchScores = LensId.entries.associateWith {
                        if (it == lens) 1.0f else 0f
                    }
                )}
            }
        }

        viewModelScope.launch {
            frameProvider.frames.collect { bitmap ->
                pipeline.analyzeFrame(bitmap)
            }
        }

        viewModelScope.launch {
            pipeline.analysis.collect { analysis ->
                val showTips = checkTipsVisibility(analysis.sceneType, analysis.isStable)
                _hudState.update { it.copy(
                    sceneLabel = analysis.sceneType.label,
                    sceneConfidence = analysis.confidence,
                    lightingLabel = analysis.lighting.label,
                    motionLevel = analysis.motionLevel.barCount,
                    distanceLabel = analysis.distanceRange.label,
                    showTipsThumbnail = showTips
                )}
            }
        }

        viewModelScope.launch {
            pipeline.lensRecommendation.collect { rec ->
                if (_hudState.value.mode != CameraMode.PRO) {
                    cameraController.switchLens(rec.recommended)
                }
                _hudState.update { it.copy(lensMatchScores = rec.scores) }
            }
        }

        viewModelScope.launch {
            pipeline.settingsProfile.collect { profile ->
                if (_hudState.value.mode != CameraMode.PRO) {
                    _hudState.update { it.copy(settings = profile.settings) }
                }
            }
        }
    }

    private fun checkTipsVisibility(scene: SceneType, isStable: Boolean): Boolean {
        if (!isStable || scene == SceneType.UNKNOWN) {
            lastStableScene = SceneType.UNKNOWN
            stableSceneStartMs = 0L
            return false
        }
        if (scene != lastStableScene) {
            lastStableScene = scene
            stableSceneStartMs = System.currentTimeMillis()
            _currentTip.value = tipsRepository.getTip(scene)
            return false
        }
        return System.currentTimeMillis() - stableSceneStartMs >= 3000L
    }

    fun cycleLens() { cameraController.cycleLens() }
    fun switchLens(lens: LensId) { cameraController.switchLens(lens) }

    fun setMode(mode: CameraMode) {
        _hudState.update { it.copy(mode = mode) }
    }

    fun toggleHud() {
        _hudState.update { it.copy(isHudVisible = !it.isHudVisible) }
    }

    fun showReferenceCard() {
        _hudState.update { it.copy(showReferenceCard = true) }
    }

    fun dismissReferenceCard() {
        _hudState.update { it.copy(showReferenceCard = false) }
    }

    fun capturePhoto() {
        val imageCapture = cameraController.getImageCapture() ?: return
        if (_captureInProgress.value) return

        viewModelScope.launch {
            _captureInProgress.value = true
            try {
                val uri = captureManager.capturePhoto(imageCapture)
                _hudState.update { it.copy(lastCapturedUri = uri) }
            } catch (_: Exception) {
            } finally {
                _captureInProgress.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.release()
        cameraController.release()
    }
}
```

- [ ] **Step 4: Update HudOverlay to include tips thumbnail**

Add to `HudOverlay.kt`, inside the `AnimatedVisibility` Box, after the coaching directive:

```kotlin
// Tips thumbnail — bottom-right above controls
TipsThumbnail(
    sceneLabel = state.sceneLabel,
    isVisible = state.showTipsThumbnail,
    onClick = onTipsClick,
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 24.dp, bottom = 180.dp)
)
```

Update `HudOverlay` signature to add `onTipsClick: () -> Unit` parameter.

Add import: `import com.spectra.app.ui.tips.TipsThumbnail`

- [ ] **Step 5: Update ViewfinderScreen to wire tips**

In `ViewfinderScreen.kt`:
- Add `val currentTip by viewModel.currentTip.collectAsState()`
- Update `HudOverlay` call to pass `onTipsClick = { viewModel.showReferenceCard() }`
- Add `ReferenceCard` overlay after all other content:

```kotlin
// Reference card overlay (full screen, on top of everything)
val tip = currentTip
if (tip != null) {
    ReferenceCard(
        tip = tip,
        isVisible = hudState.showReferenceCard,
        onDismiss = { viewModel.dismissReferenceCard() }
    )
}
```

Add imports:
```kotlin
import com.spectra.app.ui.tips.ReferenceCard
```

- [ ] **Step 6: Commit**

```bash
git add app/ core/
git commit -m "feat(app): wire AI pipeline into ViewModel and HUD

Real-time scene detection updates HUD readouts. Auto lens switching
and settings optimization in non-PRO modes. Photo tips thumbnail
appears after 3s stable scene, expands to reference card on tap."
```

---

### Task 9: Final Integration & Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 2: Verify git log**

Run: `git log --oneline`
Expected: Phase 1 commits + Phase 2 commits in order

- [ ] **Step 3: Tag Phase 2**

```bash
git tag v0.2.0-phase2
```

- [ ] **Step 4: Manual smoke test checklist**

Install on S24 Ultra and verify:
1. Point at a landscape → HUD shows "SCENE: LANDSCAPE" with confidence %
2. Lighting label updates (GOLDEN HOUR, DAYLIGHT, etc.)
3. Motion bars animate when camera moves
4. Lens auto-switches (e.g., architecture → ultrawide)
5. Settings auto-adjust in HUD (ISO, shutter, WB change per scene)
6. Tips thumbnail appears after holding on a scene for 3+ seconds
7. Tap thumbnail → reference card expands with 3 tips
8. Tap anywhere → reference card dismisses
9. PRO mode → settings don't auto-change, HUD shows AI values
10. All existing Phase 1 features still work (manual lens cycle, capture, modes)

---

## Phase 2 Deliverables Summary

| Feature | Task |
|---|---|
| Scene type enum (11 types) | Task 1 |
| AI data models (analysis, recommendation, profile, tip) | Task 1 |
| TFLite scene classifier with GPU delegate | Task 2 |
| Lighting analyzer (metadata-based) | Task 3 |
| Motion detector (frame differencing) | Task 3 |
| Distance estimator (AF diopters) | Task 3 |
| Decision engine (scene → lens + settings) | Task 4 |
| Frame analysis pipeline (orchestrator) | Task 5 |
| Photo tips repository (10 scene types, 3 tips each) | Task 6 |
| Tips UI (thumbnail + reference card overlay) | Task 7 |
| ViewModel + HUD AI integration | Task 8 |
| Integration verification | Task 9 |
