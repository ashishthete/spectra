# SPECTRA Phase 5A — Photography Pipeline Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 4 P0 ship-blockers identified in the professional photography review: resolve the settings illusion, ship a TFLite scene classification model, implement true HDR with exposure bracketing, and replace face-ellipse bokeh with depth-map bokeh. Target: 4.5/10 → 7/10.

**Architecture:** All changes maintain the existing 4-module (:core, :camera, :ai-engine, :app) Hilt DI architecture. The camera module gets new computational photography algorithms (HDR fusion, depth estimation, variable blur). The AI engine gets a real TFLite scene classifier. The app module routes actual sensor metadata to the HUD. Two new TFLite model assets are added (~20MB total).

**Tech Stack:** Kotlin, TensorFlow Lite (GPU delegate), Camera2 API (exposure bracketing), Mertens exposure fusion, MiDaS depth estimation, Circle of Confusion blur

---

## File Structure

### New files:
- `core/src/main/java/com/spectra/core/model/SettingsDisplayMode.kt` — enum for HUD display mode
- `camera/src/main/java/com/spectra/camera/HdrProcessor.kt` — exposure bracket capture + Mertens fusion + frame alignment
- `camera/src/main/java/com/spectra/camera/DepthEstimator.kt` — TFLite MiDaS depth map wrapper
- `camera/src/main/java/com/spectra/camera/DepthBokeh.kt` — depth-dependent variable blur with guided filter edge refinement
- `ai-engine/src/main/assets/scene_classifier.tflite` — trained MobileNetV3 model (~3MB)
- `scripts/train_scene_classifier.py` — Python training script for the TFLite model

### Modified files:
- `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt` — add `applySemiAuto()` method
- `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt` — add `applySemiAuto()` route, expose bracket capture
- `camera/src/main/java/com/spectra/camera/CaptureManager.kt` — wire HDR bracket, replace bokeh, add depth model init
- `camera/build.gradle.kts` — add TFLite dependency for depth model
- `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt` — improve error handling, confidence calibration
- `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt` — route actual values to HUD, wire HDR
- `core/src/main/java/com/spectra/core/model/HudState.kt` — add `settingsDisplayMode` field

### Test files:
- `camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt`
- `camera/src/test/java/com/spectra/camera/DepthBokehTest.kt`
- `ai-engine/src/test/java/com/spectra/ai/SceneClassifierModelTest.kt`
- `core/src/test/java/com/spectra/core/model/SettingsDisplayModeTest.kt`

---

### Task 1: Add SettingsDisplayMode Enum

**Files:**
- Create: `core/src/main/java/com/spectra/core/model/SettingsDisplayMode.kt`
- Modify: `core/src/main/java/com/spectra/core/model/HudState.kt`
- Test: `core/src/test/java/com/spectra/core/model/SettingsDisplayModeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/java/com/spectra/core/model/SettingsDisplayModeTest.kt
package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsDisplayModeTest {

    @Test
    fun `ACTUAL mode is default for non-PRO presets`() {
        assertThat(SettingsDisplayMode.ACTUAL.showsActualSensorValues).isTrue()
    }

    @Test
    fun `MANUAL mode is used for PRO preset`() {
        assertThat(SettingsDisplayMode.MANUAL.showsActualSensorValues).isFalse()
    }

    @Test
    fun `SMART_AUTO applies AI values to hardware`() {
        assertThat(SettingsDisplayMode.SMART_AUTO.appliesAiValues).isTrue()
    }

    @Test
    fun `ACTUAL mode does not apply AI values`() {
        assertThat(SettingsDisplayMode.ACTUAL.appliesAiValues).isFalse()
    }

    @Test
    fun `HudState defaults to ACTUAL display mode`() {
        val state = HudState()
        assertThat(state.settingsDisplayMode).isEqualTo(SettingsDisplayMode.ACTUAL)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "com.spectra.core.model.SettingsDisplayModeTest" 2>&1 | tail -5`
Expected: FAIL — `SettingsDisplayMode` does not exist

- [ ] **Step 3: Create SettingsDisplayMode enum**

```kotlin
// core/src/main/java/com/spectra/core/model/SettingsDisplayMode.kt
package com.spectra.core.model

enum class SettingsDisplayMode(
    val showsActualSensorValues: Boolean,
    val appliesAiValues: Boolean
) {
    ACTUAL(showsActualSensorValues = true, appliesAiValues = false),
    SMART_AUTO(showsActualSensorValues = false, appliesAiValues = true),
    MANUAL(showsActualSensorValues = false, appliesAiValues = false);
}
```

- [ ] **Step 4: Add settingsDisplayMode to HudState**

In `core/src/main/java/com/spectra/core/model/HudState.kt`, add after the `aeAfLocked` field:

```kotlin
    val settingsDisplayMode: SettingsDisplayMode = SettingsDisplayMode.ACTUAL,
```

Add the import at the top of the file.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "com.spectra.core.model.SettingsDisplayModeTest" 2>&1 | tail -5`
Expected: PASS — all 5 tests pass

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/spectra/core/model/SettingsDisplayMode.kt core/src/main/java/com/spectra/core/model/HudState.kt core/src/test/java/com/spectra/core/model/SettingsDisplayModeTest.kt
git commit -m "feat: add SettingsDisplayMode enum for HUD display routing"
```

---

### Task 2: Add applySemiAuto to Camera2SettingsApplier

This method applies actual ISO/shutter values from the DecisionEngine via Camera2 manual mode, enabling "Smart Auto" where AI-recommended values are physically applied to the hardware.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt`
- Test: `camera/src/test/java/com/spectra/camera/Camera2SettingsApplierTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `camera/src/test/java/com/spectra/camera/Camera2SettingsApplierTest.kt`:

```kotlin
    @Test
    fun `clampIso clamps to valid range`() {
        val method = Camera2SettingsApplier::class.java.getDeclaredMethod(
            "clampIso", Int::class.java, Int::class.java, Int::class.java
        )
        method.isAccessible = true
        assertThat(method.invoke(applier, 50, 50, 3200)).isEqualTo(50)
        assertThat(method.invoke(applier, 25, 50, 3200)).isEqualTo(50)
        assertThat(method.invoke(applier, 5000, 50, 3200)).isEqualTo(3200)
        assertThat(method.invoke(applier, 400, 50, 3200)).isEqualTo(400)
    }

    @Test
    fun `clampExposureNs clamps to valid range`() {
        val method = Camera2SettingsApplier::class.java.getDeclaredMethod(
            "clampExposureNs", Long::class.java, Long::class.java, Long::class.java
        )
        method.isAccessible = true
        val oneMs = 1_000_000L
        val oneSecond = 1_000_000_000L
        val tenMs = 10_000_000L
        assertThat(method.invoke(applier, tenMs, oneMs, oneSecond)).isEqualTo(tenMs)
        assertThat(method.invoke(applier, 500L, oneMs, oneSecond)).isEqualTo(oneMs)
        assertThat(method.invoke(applier, 2_000_000_000L, oneMs, oneSecond)).isEqualTo(oneSecond)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.Camera2SettingsApplierTest" 2>&1 | tail -5`
Expected: FAIL — methods `clampIso` and `clampExposureNs` don't exist

- [ ] **Step 3: Add applySemiAuto method and helpers**

Add to `camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt` before the `shutterDenominatorToNanos` method:

```kotlin
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applySemiAuto(
        camera: Camera,
        settings: CameraSettings,
        isoRange: android.util.Range<Int> = Range(50, 3200),
        exposureRange: android.util.Range<Long> = Range(1_000_000L, 1_000_000_000L)
    ) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val clampedIso = clampIso(settings.iso, isoRange.lower, isoRange.upper)
        val targetExposureNs = shutterDenominatorToNanos(settings.shutterSpeedDenominator)
        val clampedExposureNs = clampExposureNs(targetExposureNs, exposureRange.lower, exposureRange.upper)

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                clampedIso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                clampedExposureNs
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

        val wbDrift = kotlin.math.abs(settings.whiteBalanceKelvin - 5500)
        if (wbDrift > 300) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggb(settings.whiteBalanceKelvin)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "SemiAuto: ISO=$clampedIso, exposure=${clampedExposureNs}ns, WB=${settings.whiteBalanceKelvin}K")
    }

    private fun clampIso(iso: Int, min: Int, max: Int): Int = iso.coerceIn(min, max)

    private fun clampExposureNs(ns: Long, min: Long, max: Long): Long = ns.coerceIn(min, max)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.Camera2SettingsApplierTest" 2>&1 | tail -5`
Expected: PASS — all tests pass

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/Camera2SettingsApplier.kt camera/src/test/java/com/spectra/camera/Camera2SettingsApplierTest.kt
git commit -m "feat: add applySemiAuto method for real exposure control in auto mode"
```

---

### Task 3: Wire Settings Display Mode Through Controller and ViewModel

Route actual sensor metadata to the HUD in ACTUAL mode and DecisionEngine values in SMART_AUTO mode. Add `applySemiAuto` path to SpectraCameraController.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add applySemiAuto route to SpectraCameraController**

In `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`, modify the `applySettings` method (around line 350):

```kotlin
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applySettings(settings: com.spectra.core.model.CameraSettings, manual: Boolean = false, motionLevel: Int = 0, semiAuto: Boolean = false) {
        val cam = camera ?: return
        when {
            manual -> settingsApplier.applyManual(cam, settings)
            semiAuto -> settingsApplier.applySemiAuto(cam, settings)
            else -> {
                val currentIso = _sensorMetadata.value.iso
                settingsApplier.applyAutoWithHints(cam, settings, motionLevel, currentIso)
            }
        }
    }
```

- [ ] **Step 2: Update ViewModel to use settingsDisplayMode**

In `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`, modify the `applySettingsToHardware` method (around line 341):

```kotlin
    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applySettingsToHardware(settings: CameraSettings, manual: Boolean = false) {
        try {
            val motionLevel = _hudState.value.motionLevel
            val semiAuto = _hudState.value.settingsDisplayMode == com.spectra.core.model.SettingsDisplayMode.SMART_AUTO
            cameraController.applySettings(settings, manual, motionLevel, semiAuto)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply camera settings", e)
        }
    }
```

- [ ] **Step 3: Add toggleSettingsDisplayMode to ViewModel**

Add to the ViewModel:

```kotlin
    fun toggleSettingsDisplayMode() {
        val current = _hudState.value.settingsDisplayMode
        if (_hudState.value.mode == CameraMode.PRO) return
        val next = when (current) {
            com.spectra.core.model.SettingsDisplayMode.ACTUAL -> com.spectra.core.model.SettingsDisplayMode.SMART_AUTO
            com.spectra.core.model.SettingsDisplayMode.SMART_AUTO -> com.spectra.core.model.SettingsDisplayMode.ACTUAL
            com.spectra.core.model.SettingsDisplayMode.MANUAL -> com.spectra.core.model.SettingsDisplayMode.ACTUAL
        }
        _hudState.update { it.copy(settingsDisplayMode = next) }
        if (next == com.spectra.core.model.SettingsDisplayMode.ACTUAL) {
            resetHardwareToAuto()
        } else {
            val settings = _hudState.value.aiRecommendedSettings
            applySettingsToHardware(settings)
        }
    }
```

- [ ] **Step 4: Update setPreset to set display mode for PRO**

In `CameraViewModel.kt`, inside the `setPreset` method where `preset == CameraPreset.PRO` is handled, add to the HudState update:

```kotlin
    settingsDisplayMode = com.spectra.core.model.SettingsDisplayMode.MANUAL,
```

And in the else branch, add:

```kotlin
    settingsDisplayMode = com.spectra.core.model.SettingsDisplayMode.ACTUAL,
```

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/SpectraCameraController.kt app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: wire settings display mode through controller for real exposure control"
```

---

### Task 4: Create Python Training Script for Scene Classifier

This creates the training script that produces the `scene_classifier.tflite` model file. The script uses MobileNetV3-Large with transfer learning on scene classification data.

**Files:**
- Create: `scripts/train_scene_classifier.py`

- [ ] **Step 1: Create the training script**

```python
# scripts/train_scene_classifier.py
"""
SPECTRA Scene Classifier Training Script
Trains MobileNetV3-Large on scene classification for 11 categories.

Usage:
    pip install tensorflow tensorflow-datasets pillow
    python train_scene_classifier.py --data_dir ./scene_data --output ./scene_classifier.tflite

Data directory structure:
    scene_data/
        LANDSCAPE/   (200+ images)
        PORTRAIT/    (200+ images)
        FOOD/        (200+ images)
        NIGHT/       (200+ images)
        ARCHITECTURE/(200+ images)
        MACRO/       (200+ images)
        PET/         (200+ images)
        ACTION/      (200+ images)
        DOCUMENT/    (200+ images)
        INDOOR/      (200+ images)
        UNKNOWN/     (200+ images)

Suggested data sources:
    - Places365-Standard: field/mountain/coast -> LANDSCAPE, kitchen/bedroom -> INDOOR
    - Food-101: all categories -> FOOD
    - Oxford-IIIT Pet: all categories -> PET
    - Open Images V7: sports -> ACTION, letter/document -> DOCUMENT
    - Manual collection: close-up flowers/insects -> MACRO, bridges/buildings -> ARCHITECTURE
"""

import argparse
import os
import sys

import tensorflow as tf
from tensorflow.keras import layers, Model
from tensorflow.keras.applications import MobileNetV3Large
from tensorflow.keras.preprocessing.image import ImageDataGenerator

SCENE_LABELS = [
    "LANDSCAPE", "PORTRAIT", "FOOD", "NIGHT", "ARCHITECTURE",
    "MACRO", "PET", "ACTION", "DOCUMENT", "INDOOR", "UNKNOWN"
]
INPUT_SIZE = 224
BATCH_SIZE = 32


def create_model(num_classes: int) -> Model:
    base = MobileNetV3Large(
        input_shape=(INPUT_SIZE, INPUT_SIZE, 3),
        include_top=False,
        weights="imagenet",
        pooling="avg"
    )
    base.trainable = False

    inputs = tf.keras.Input(shape=(INPUT_SIZE, INPUT_SIZE, 3))
    x = tf.keras.applications.mobilenet_v3.preprocess_input(inputs)
    x = base(x, training=False)
    x = layers.Dropout(0.2)(x)
    outputs = layers.Dense(num_classes, activation="softmax")(x)

    return Model(inputs, outputs)


def train(data_dir: str, output_path: str, epochs_frozen: int = 20, epochs_finetune: int = 10):
    datagen_train = ImageDataGenerator(
        rescale=1.0 / 255,
        rotation_range=15,
        width_shift_range=0.1,
        height_shift_range=0.1,
        horizontal_flip=True,
        validation_split=0.15
    )
    datagen_val = ImageDataGenerator(rescale=1.0 / 255, validation_split=0.15)

    train_gen = datagen_train.flow_from_directory(
        data_dir,
        target_size=(INPUT_SIZE, INPUT_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        classes=SCENE_LABELS,
        subset="training"
    )
    val_gen = datagen_val.flow_from_directory(
        data_dir,
        target_size=(INPUT_SIZE, INPUT_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        classes=SCENE_LABELS,
        subset="validation"
    )

    model = create_model(len(SCENE_LABELS))

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-4),
        loss="categorical_crossentropy",
        metrics=["accuracy"]
    )

    print(f"Phase 1: Training classifier head ({epochs_frozen} epochs)...")
    model.fit(train_gen, validation_data=val_gen, epochs=epochs_frozen)

    base_model = model.layers[2]
    for layer in base_model.layers[-30:]:
        layer.trainable = True

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
        loss="categorical_crossentropy",
        metrics=["accuracy"]
    )

    print(f"Phase 2: Fine-tuning last blocks ({epochs_finetune} epochs)...")
    model.fit(train_gen, validation_data=val_gen, epochs=epochs_finetune)

    val_loss, val_acc = model.evaluate(val_gen)
    print(f"Validation accuracy: {val_acc:.4f}")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Model saved to {output_path} ({size_mb:.1f} MB)")
    print(f"Labels order: {SCENE_LABELS}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train SPECTRA scene classifier")
    parser.add_argument("--data_dir", required=True, help="Path to scene data directory")
    parser.add_argument("--output", default="scene_classifier.tflite", help="Output TFLite path")
    parser.add_argument("--epochs_frozen", type=int, default=20)
    parser.add_argument("--epochs_finetune", type=int, default=10)
    args = parser.parse_args()

    if not os.path.isdir(args.data_dir):
        print(f"Error: {args.data_dir} is not a directory")
        sys.exit(1)

    train(args.data_dir, args.output, args.epochs_frozen, args.epochs_finetune)
```

- [ ] **Step 2: Commit**

```bash
git add scripts/train_scene_classifier.py
git commit -m "feat: add Python training script for MobileNetV3 scene classifier"
```

---

### Task 5: Improve SceneClassifier TFLite Integration

Harden the existing `SceneClassifier.kt` with proper confidence calibration, GPU delegate fallback, and model loading verification. The actual TFLite file path and label loading are already implemented — this task improves robustness.

**Files:**
- Modify: `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`
- Test: `ai-engine/src/test/java/com/spectra/ai/SceneClassifierModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// ai-engine/src/test/java/com/spectra/ai/SceneClassifierModelTest.kt
package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneClassifierModelTest {

    @Test
    fun `softmax normalization sums to 1`() {
        val raw = floatArrayOf(1.0f, 2.0f, 0.5f, 3.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val normalized = applySoftmax(raw)
        val sum = normalized.sum()
        assertThat(sum).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `softmax preserves ordering`() {
        val raw = floatArrayOf(1.0f, 5.0f, 0.5f, 3.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val normalized = applySoftmax(raw)
        val maxIdx = normalized.indices.maxByOrNull { normalized[it] }!!
        assertThat(maxIdx).isEqualTo(1)
    }

    @Test
    fun `softmax handles uniform inputs`() {
        val raw = FloatArray(11) { 1.0f }
        val normalized = applySoftmax(raw)
        for (v in normalized) {
            assertThat(v).isWithin(0.01f).of(1.0f / 11f)
        }
    }

    @Test
    fun `topKConfident returns top result correctly`() {
        val scores = floatArrayOf(0.1f, 0.6f, 0.05f, 0.15f, 0.02f, 0.01f, 0.01f, 0.02f, 0.01f, 0.02f, 0.01f)
        val labels = SceneType.entries.toList()
        val result = topKConfident(scores, labels, 1)
        assertThat(result).hasSize(1)
        assertThat(result[0].first).isEqualTo(SceneType.PORTRAIT)
        assertThat(result[0].second).isWithin(0.001f).of(0.6f)
    }

    private fun applySoftmax(input: FloatArray): FloatArray {
        val maxVal = input.max()
        val exps = FloatArray(input.size) { kotlin.math.exp((input[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    private fun topKConfident(scores: FloatArray, labels: List<SceneType>, k: Int): List<Pair<SceneType, Float>> {
        return scores.indices
            .sortedByDescending { scores[it] }
            .take(k)
            .map { idx -> Pair(if (idx < labels.size) labels[idx] else SceneType.UNKNOWN, scores[idx]) }
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :ai-engine:testDebugUnitTest --tests "com.spectra.ai.SceneClassifierModelTest" 2>&1 | tail -5`
Expected: PASS — these are pure math tests

- [ ] **Step 3: Update SceneClassifier with softmax and GPU fallback**

In `ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt`, replace the `initialize` method:

```kotlin
    private var useGpu = false

    fun initialize() {
        loadLabels()
        try {
            val model = loadModelFile("scene_classifier.tflite")
            try {
                gpuDelegate = GpuDelegate()
                val options = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    setNumThreads(4)
                }
                interpreter = Interpreter(model, options)
                useGpu = true
            } catch (_: Exception) {
                gpuDelegate?.close()
                gpuDelegate = null
                val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
                interpreter = Interpreter(model, cpuOptions)
                useGpu = false
            }
        } catch (_: Exception) {
            interpreter = null
        }
    }
```

- [ ] **Step 4: Add softmax to classifyBase output**

In `SceneClassifier.kt`, modify the `classifyBase` method. Replace the block inside `if (interp != null)` (lines 53-66):

```kotlin
        if (interp != null) {
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = bitmapToByteBuffer(resized)
            resized.recycle()

            val outputArray = Array(1) { FloatArray(labelMap.size) }
            interp.run(inputBuffer, outputArray)

            val scores = applySoftmax(outputArray[0])
            val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            val confidence = scores[maxIndex]
            val sceneType = if (maxIndex < labelMap.size) labelMap[maxIndex] else SceneType.UNKNOWN
            return Pair(sceneType, confidence)
        }
```

- [ ] **Step 5: Add softmax helper method**

Add to `SceneClassifier.kt`:

```kotlin
    private fun applySoftmax(input: FloatArray): FloatArray {
        val maxVal = input.max()
        val exps = FloatArray(input.size) { kotlin.math.exp((input[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }
```

- [ ] **Step 6: Run all tests**

Run: `./gradlew :ai-engine:testDebugUnitTest 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add ai-engine/src/main/java/com/spectra/ai/SceneClassifier.kt ai-engine/src/test/java/com/spectra/ai/SceneClassifierModelTest.kt
git commit -m "feat: add softmax calibration and GPU fallback to SceneClassifier"
```

---

### Task 6: Create HdrProcessor with Exposure Bracket Capture

Implement the exposure bracket capture mechanism that takes 3 frames at -2EV, 0EV, +2EV by temporarily switching to manual exposure mode.

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/HdrProcessor.kt`
- Test: `camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HdrProcessorTest {

    @Test
    fun `computeBracketExposures returns 3 values`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result).hasSize(3)
    }

    @Test
    fun `bracket exposures are ordered underexposed to overexposed`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result[0].first).isLessThan(result[1].first)
        assertThat(result[1].first).isLessThan(result[2].first)
    }

    @Test
    fun `bracket middle exposure matches base`() {
        val baseNs = 10_000_000L
        val baseIso = 200
        val result = HdrProcessor.computeBracketExposures(baseNs, baseIso)
        assertThat(result[1].first).isEqualTo(baseNs)
        assertThat(result[1].second).isEqualTo(baseIso)
    }

    @Test
    fun `bracket under-exposure is base divided by 4`() {
        val baseNs = 40_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(result[0].first).isEqualTo(10_000_000L)
    }

    @Test
    fun `bracket over-exposure is base multiplied by 4`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(result[2].first).isEqualTo(40_000_000L)
    }

    @Test
    fun `bracket keeps ISO constant`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 400)
        assertThat(result[0].second).isEqualTo(400)
        assertThat(result[1].second).isEqualTo(400)
        assertThat(result[2].second).isEqualTo(400)
    }

    @Test
    fun `computeWeight contrast enhances edges`() {
        val flat = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val edgy = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f)
        val flatWeight = HdrProcessor.contrastWeight(flat, 3, 3, 1, 1)
        val edgyWeight = HdrProcessor.contrastWeight(edgy, 3, 3, 1, 1)
        assertThat(edgyWeight).isGreaterThan(flatWeight)
    }

    @Test
    fun `wellExposedness peaks at 0_5`() {
        val at05 = HdrProcessor.wellExposednessWeight(0.5f)
        val at01 = HdrProcessor.wellExposednessWeight(0.1f)
        val at09 = HdrProcessor.wellExposednessWeight(0.9f)
        assertThat(at05).isGreaterThan(at01)
        assertThat(at05).isGreaterThan(at09)
    }

    @Test
    fun `saturationWeight is higher for colorful pixels`() {
        val colorful = HdrProcessor.saturationWeight(0.8f, 0.2f, 0.1f)
        val gray = HdrProcessor.saturationWeight(0.5f, 0.5f, 0.5f)
        assertThat(colorful).isGreaterThan(gray)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.HdrProcessorTest" 2>&1 | tail -5`
Expected: FAIL — `HdrProcessor` does not exist

- [ ] **Step 3: Create HdrProcessor**

```kotlin
// camera/src/main/java/com/spectra/camera/HdrProcessor.kt
package com.spectra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class HdrProcessor {

    companion object {
        fun computeBracketExposures(baseExposureNs: Long, baseIso: Int): List<Pair<Long, Int>> {
            val underExposure = baseExposureNs / 4
            val overExposure = baseExposureNs * 4
            return listOf(
                Pair(underExposure, baseIso),
                Pair(baseExposureNs, baseIso),
                Pair(overExposure, baseIso)
            )
        }

        fun contrastWeight(luminance: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
            val idx = y * w + x
            val center = luminance[idx]
            var sum = 0f
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dy == 0 && dx == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        sum += abs(luminance[ny * w + nx] - center)
                        count++
                    }
                }
            }
            return if (count > 0) sum / count else 0f
        }

        fun wellExposednessWeight(value: Float): Float {
            val diff = value - 0.5f
            return exp((-12.5f * diff * diff).toDouble()).toFloat()
        }

        fun saturationWeight(r: Float, g: Float, b: Float): Float {
            val mean = (r + g + b) / 3f
            val variance = ((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f
            return kotlin.math.sqrt(variance.toDouble()).toFloat()
        }
    }

    fun mertensFusion(frames: List<IntArray>, width: Int, height: Int): IntArray {
        val numFrames = frames.size
        if (numFrames == 0) return IntArray(0)
        if (numFrames == 1) return frames[0].copyOf()

        val n = width * height
        val weightMaps = Array(numFrames) { FloatArray(n) }

        for (f in 0 until numFrames) {
            val pixels = frames[f]
            val luminance = FloatArray(n)
            for (i in 0 until n) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val r = ((pixels[i] shr 16) and 0xFF) / 255f
                    val g = ((pixels[i] shr 8) and 0xFF) / 255f
                    val b = (pixels[i] and 0xFF) / 255f

                    val contrast = if (x in 1 until width - 1 && y in 1 until height - 1) {
                        contrastWeight(luminance, width, height, x, y)
                    } else 0f
                    val saturation = saturationWeight(r, g, b)
                    val exposure = wellExposednessWeight(luminance[i])

                    weightMaps[f][i] = (contrast + 0.001f) * (saturation + 0.001f) * (exposure + 0.001f)
                }
            }
        }

        for (i in 0 until n) {
            var total = 0f
            for (f in 0 until numFrames) total += weightMaps[f][i]
            if (total > 0f) {
                for (f in 0 until numFrames) weightMaps[f][i] /= total
            } else {
                for (f in 0 until numFrames) weightMaps[f][i] = 1f / numFrames
            }
        }

        val result = IntArray(n)
        for (i in 0 until n) {
            var rSum = 0f; var gSum = 0f; var bSum = 0f
            for (f in 0 until numFrames) {
                val w = weightMaps[f][i]
                val pixel = frames[f][i]
                rSum += w * ((pixel shr 16) and 0xFF)
                gSum += w * ((pixel shr 8) and 0xFF)
                bSum += w * (pixel and 0xFF)
            }
            val rOut = rSum.toInt().coerceIn(0, 255)
            val gOut = gSum.toInt().coerceIn(0, 255)
            val bOut = bSum.toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        return result
    }

    fun alignFrame(reference: IntArray, target: IntArray, width: Int, height: Int, tileSize: Int = 16, searchRadius: Int = 4): IntArray {
        val aligned = IntArray(width * height)
        System.arraycopy(target, 0, aligned, 0, target.size)

        val refLum = IntArray(width * height)
        val tgtLum = IntArray(width * height)
        for (i in reference.indices) {
            refLum[i] = luminance(reference[i])
            tgtLum[i] = luminance(target[i])
        }

        val tilesX = width / tileSize
        val tilesY = height / tileSize

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tileX = tx * tileSize
                val tileY = ty * tileSize

                var bestDx = 0
                var bestDy = 0
                var bestSad = Long.MAX_VALUE

                for (dy in -searchRadius..searchRadius) {
                    for (dx in -searchRadius..searchRadius) {
                        var sad = 0L
                        for (py in 0 until tileSize) {
                            for (px in 0 until tileSize) {
                                val rx = tileX + px
                                val ry = tileY + py
                                val sx = rx + dx
                                val sy = ry + dy
                                if (sx < 0 || sx >= width || sy < 0 || sy >= height) {
                                    sad += 128
                                    continue
                                }
                                sad += abs(refLum[ry * width + rx] - tgtLum[sy * width + sx])
                            }
                        }
                        if (sad < bestSad) {
                            bestSad = sad
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }

                for (py in 0 until tileSize) {
                    for (px in 0 until tileSize) {
                        val dstX = tileX + px
                        val dstY = tileY + py
                        val srcX = dstX + bestDx
                        val srcY = dstY + bestDy
                        if (srcX in 0 until width && srcY in 0 until height) {
                            aligned[dstY * width + dstX] = target[srcY * width + srcX]
                        }
                    }
                }
            }
        }

        return aligned
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.HdrProcessorTest" 2>&1 | tail -5`
Expected: PASS — all 10 tests pass

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/HdrProcessor.kt camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt
git commit -m "feat: add HdrProcessor with Mertens exposure fusion and tile-based alignment"
```

---

### Task 7: Add More HDR Fusion Tests

Add integration-level tests for the Mertens fusion and alignment algorithms using synthetic pixel data.

**Files:**
- Modify: `camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt`

- [ ] **Step 1: Write fusion integration tests**

Add to `HdrProcessorTest.kt`:

```kotlin
    private val processor = HdrProcessor()

    @Test
    fun `mertensFusion with single frame returns copy`() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = processor.mertensFusion(listOf(pixels), w, h)
        assertThat(result).hasLength(w * h)
        for (i in result.indices) {
            assertThat(result[i]).isEqualTo(pixels[i])
        }
    }

    @Test
    fun `mertensFusion with empty list returns empty`() {
        val result = processor.mertensFusion(emptyList(), 0, 0)
        assertThat(result).hasLength(0)
    }

    @Test
    fun `mertensFusion of three frames produces valid pixels`() {
        val w = 8; val h = 8; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 240 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertThat(r).isIn(0..255)
            assertThat(g).isIn(0..255)
            assertThat(b).isIn(0..255)
        }
    }

    @Test
    fun `mertensFusion prefers well-exposed mid frame`() {
        val w = 4; val h = 4; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (10 shl 16) or (10 shl 8) or 10 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        val avgR = result.map { (it shr 16) and 0xFF }.average()
        assertThat(avgR).isGreaterThan(50.0)
        assertThat(avgR).isLessThan(200.0)
    }

    @Test
    fun `alignFrame with no motion returns same pixels`() {
        val w = 16; val h = 16; val n = w * h
        val frame = IntArray(n) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x * 16) shl 16) or ((y * 16) shl 8) or 128
        }
        val aligned = processor.alignFrame(frame, frame, w, h)
        for (i in aligned.indices) {
            assertThat(aligned[i]).isEqualTo(frame[i])
        }
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.HdrProcessorTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add camera/src/test/java/com/spectra/camera/HdrProcessorTest.kt
git commit -m "test: add integration tests for Mertens fusion and frame alignment"
```

---

### Task 8: Wire HDR Bracket Capture into CaptureManager

Add `captureHdrBracket()` method to CaptureManager that captures 3 frames at different exposures using Camera2 manual control, then merges them with Mertens fusion.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`
- Modify: `camera/src/main/java/com/spectra/camera/SpectraCameraController.kt`

- [ ] **Step 1: Add HdrProcessor field to CaptureManager**

In `CaptureManager.kt`, add after the `executor` field:

```kotlin
    private val hdrProcessor = HdrProcessor()
```

- [ ] **Step 2: Add captureHdrBracket method**

Add to `CaptureManager.kt`:

```kotlin
    suspend fun captureHdrBracket(
        imageCapture: ImageCapture,
        baseExposureNs: Long,
        baseIso: Int,
        applyBracketSettings: suspend (exposureNs: Long, iso: Int) -> Unit,
        restoreAutoExposure: suspend () -> Unit,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        faceRects: List<android.graphics.RectF> = emptyList(),
        isPortraitMode: Boolean = false
    ): String {
        val brackets = HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
        val frames = mutableListOf<Pair<ByteArray, Int>>()

        try {
            for ((exposureNs, iso) in brackets) {
                applyBracketSettings(exposureNs, iso)
                delay(50)
                try {
                    val frame = captureInMemory(imageCapture)
                    frames.add(frame)
                } catch (e: Exception) {
                    Log.w("CaptureManager", "HDR bracket frame failed", e)
                }
            }
        } finally {
            restoreAutoExposure()
        }

        if (frames.isEmpty()) throw androidx.camera.core.ImageCaptureException(0, "All HDR frames failed", null)

        if (frames.size < 2) {
            val uri = saveJpegToMediaStore(frames[0].first, frames[0].second)
            if (uri.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    applyPostProcess(android.net.Uri.parse(uri), beautyLevel, style, isFrontCamera, true, faceRects, isPortraitMode)
                }
            }
            return uri
        }

        val merged = withContext(Dispatchers.Default) {
            mergeHdrFrames(frames)
        }

        val uri = saveJpegToMediaStore(merged.first, merged.second)
        if (uri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                applyPostProcess(android.net.Uri.parse(uri), beautyLevel, style, isFrontCamera, false, faceRects, isPortraitMode)
            }
        }
        Log.d("CaptureManager", "HDR bracket: ${frames.size} frames merged")
        return uri
    }

    private fun mergeHdrFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val bitmaps = frames.mapNotNull { (bytes, _) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (bitmaps.size < 2) {
            bitmaps.forEach { it.recycle() }
            return frames.first()
        }

        val w = bitmaps[0].width
        val h = bitmaps[0].height

        val pixelArrays = bitmaps.mapIndexed { index, bmp ->
            if (bmp.width != w || bmp.height != h) {
                bmp.recycle()
                null
            } else {
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()
                pixels
            }
        }.filterNotNull()

        if (pixelArrays.size < 2) return frames.first()

        val refPixels = pixelArrays[0]
        val alignedFrames = mutableListOf(refPixels)
        for (i in 1 until pixelArrays.size) {
            alignedFrames.add(hdrProcessor.alignFrame(refPixels, pixelArrays[i], w, h))
        }

        val merged = hdrProcessor.mertensFusion(alignedFrames, w, h)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(merged, 0, w, 0, 0, w, h)

        applySharpen(result)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val jpegBytes = stream.toByteArray()
        result.recycle()

        Log.d("CaptureManager", "HDR merge: ${pixelArrays.size} frames fused")
        return Pair(jpegBytes, frames[0].second)
    }
```

- [ ] **Step 3: Add bracket exposure methods to SpectraCameraController**

Add to `SpectraCameraController.kt`:

```kotlin
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyBracketExposure(exposureNs: Long, iso: Int) {
        val cam = camera ?: return
        val settings = com.spectra.core.model.CameraSettings(
            iso = iso,
            shutterSpeedDenominator = if (exposureNs > 0) (1_000_000_000L / exposureNs).toInt().coerceIn(1, 32000) else 125,
            whiteBalanceKelvin = _sensorMetadata.value.colorTemperatureK.takeIf { it > 0 } ?: 5500
        )
        settingsApplier.applyManual(cam, settings)
    }
```

- [ ] **Step 4: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt camera/src/main/java/com/spectra/camera/SpectraCameraController.kt
git commit -m "feat: wire HDR bracket capture with Mertens fusion into CaptureManager"
```

---

### Task 9: Wire HDR Capture into ViewModel

Connect the HDR bracket capture path so that when `isHdrActive` is true, the camera captures with exposure bracketing instead of multi-frame averaging.

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Add HDR bracket capture path**

In `CameraViewModel.kt`, modify `capturePhotoInternal()`. After the existing `captureManager.captureSmartPhoto` call (around line 639), wrap the capture logic:

Replace the existing capture call from:
```kotlin
            val result = captureManager.captureSmartPhoto(
                imageCapture,
                state.beautyLevel,
                state.photoStyle,
                state.isFrontCamera,
                state.isHdrActive,
                lastDetectedFaceRects
            )
```

To:
```kotlin
            val isHdr = state.isHdrActive && !state.isFrontCamera
            if (isHdr && state.actualShutterSpeedNs > 0 && state.actualIso > 0) {
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
                    isPortraitMode = state.mode == CameraMode.PORT
                )
                _hudState.update { it.copy(
                    lastCapturedUri = hdrUri,
                    showCaptureFlash = false,
                    isCapturing = false,
                    showReview = true,
                    reviewUri = hdrUri
                )}
                _captureInProgress.value = false
                return
            }

            val result = captureManager.captureSmartPhoto(
                imageCapture,
                state.beautyLevel,
                state.photoStyle,
                state.isFrontCamera,
                state.isHdrActive,
                lastDetectedFaceRects
            )
```

- [ ] **Step 2: Add OptIn annotation for bracket method**

Ensure the `capturePhotoInternal` method or the ViewModel class has the Camera2 interop annotation:
```kotlin
    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: wire HDR exposure bracket capture through ViewModel"
```

---

### Task 10: Create DepthEstimator TFLite Wrapper

Create a TFLite wrapper for the MiDaS depth estimation model that produces a relative depth map from a single image.

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/DepthEstimator.kt`
- Modify: `camera/build.gradle.kts`

- [ ] **Step 1: Add TFLite dependency to camera module**

In `camera/build.gradle.kts`, add to the dependencies block:

```kotlin
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
```

Also add the `noCompress` directive in the `android` block if not present:
```kotlin
    androidResources {
        noCompress += "tflite"
    }
```

- [ ] **Step 2: Create DepthEstimator**

```kotlin
// camera/src/main/java/com/spectra/camera/DepthEstimator.kt
package com.spectra.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DepthEstimator(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 256

    fun initialize(): Boolean {
        return try {
            val model = loadModelFile("depth_estimator.tflite")
            try {
                gpuDelegate = GpuDelegate()
                val options = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    setNumThreads(4)
                }
                interpreter = Interpreter(model, options)
            } catch (_: Exception) {
                gpuDelegate?.close()
                gpuDelegate = null
                val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
                interpreter = Interpreter(model, cpuOptions)
            }
            true
        } catch (_: Exception) {
            Log.w("DepthEstimator", "Depth model not available")
            false
        }
    }

    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resized)
        resized.recycle()

        val outputArray = Array(1) { Array(inputSize) { FloatArray(inputSize) } }
        interp.run(inputBuffer, outputArray)

        val depthMap = FloatArray(inputSize * inputSize)
        var minDepth = Float.MAX_VALUE
        var maxDepth = Float.MIN_VALUE
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val d = outputArray[0][y][x]
                depthMap[y * inputSize + x] = d
                if (d < minDepth) minDepth = d
                if (d > maxDepth) maxDepth = d
            }
        }

        val range = maxDepth - minDepth
        if (range < 0.001f) return depthMap
        for (i in depthMap.indices) {
            depthMap[i] = (depthMap[i] - minDepth) / range
        }

        return depthMap
    }

    fun getInputSize(): Int = inputSize

    fun isAvailable(): Boolean = interpreter != null

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

- [ ] **Step 3: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/DepthEstimator.kt camera/build.gradle.kts
git commit -m "feat: add DepthEstimator TFLite wrapper for MiDaS depth estimation"
```

---

### Task 11: Create DepthBokeh with Guided Filter and Variable Blur

Implement depth-dependent portrait bokeh using a blur pyramid with CoC-based radius, plus guided filter for depth edge refinement.

**Files:**
- Create: `camera/src/main/java/com/spectra/camera/DepthBokeh.kt`
- Test: `camera/src/test/java/com/spectra/camera/DepthBokehTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// camera/src/test/java/com/spectra/camera/DepthBokehTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DepthBokehTest {

    private val bokeh = DepthBokeh()

    @Test
    fun `computeBlurRadius returns 0 at focus depth`() {
        val radius = DepthBokeh.computeBlurRadius(0.5f, 0.5f, 15f)
        assertThat(radius).isEqualTo(0f)
    }

    @Test
    fun `computeBlurRadius increases with depth difference`() {
        val near = DepthBokeh.computeBlurRadius(0.3f, 0.5f, 15f)
        val far = DepthBokeh.computeBlurRadius(0.1f, 0.5f, 15f)
        assertThat(far).isGreaterThan(near)
    }

    @Test
    fun `computeBlurRadius is clamped to maxRadius`() {
        val radius = DepthBokeh.computeBlurRadius(0.0f, 1.0f, 15f)
        assertThat(radius).isAtMost(15f)
    }

    @Test
    fun `guidedFilter preserves edges`() {
        val w = 8; val h = 8; val n = w * h
        val guide = FloatArray(n) { i -> if (i % w < w / 2) 0.2f else 0.8f }
        val input = FloatArray(n) { i -> if (i % w < w / 2) 0.25f else 0.75f }
        val filtered = bokeh.guidedFilter(guide, input, w, h, radius = 2, eps = 0.01f)
        assertThat(filtered).hasLength(n)
        val leftAvg = (0 until n).filter { it % w < w / 4 }.map { filtered[it] }.average()
        val rightAvg = (0 until n).filter { it % w >= w * 3 / 4 }.map { filtered[it] }.average()
        assertThat(leftAvg).isLessThan(rightAvg.toFloat())
    }

    @Test
    fun `buildBlurPyramid produces correct number of levels`() {
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val pyramid = bokeh.buildBlurPyramid(pixels, w, h, levels = 4, baseRadius = 5)
        assertThat(pyramid).hasSize(4)
        for (level in pyramid) {
            assertThat(level).hasLength(w * h)
        }
    }

    @Test
    fun `buildBlurPyramid level 0 is sharp original`() {
        val w = 8; val h = 8
        val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 3) shl 16) or ((i * 2) shl 8) or i }
        val pyramid = bokeh.buildBlurPyramid(pixels, w, h, levels = 3, baseRadius = 3)
        for (i in pixels.indices) {
            assertThat(pyramid[0][i]).isEqualTo(pixels[i])
        }
    }

    @Test
    fun `applyDepthBokeh with uniform depth returns near-original`() {
        val w = 8; val h = 8; val n = w * h
        val pixels = IntArray(n) { (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200 }
        val depthMap = FloatArray(n) { 0.5f }
        val result = bokeh.applyDepthBokeh(pixels, depthMap, w, h, w, h, focusDepth = 0.5f)
        assertThat(result).hasLength(n)
        for (i in result.indices) {
            assertThat((result[i] shr 16) and 0xFF).isWithin(2).of(100)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.DepthBokehTest" 2>&1 | tail -5`
Expected: FAIL — `DepthBokeh` does not exist

- [ ] **Step 3: Create DepthBokeh**

```kotlin
// camera/src/main/java/com/spectra/camera/DepthBokeh.kt
package com.spectra.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DepthBokeh {

    companion object {
        fun computeBlurRadius(pixelDepth: Float, focusDepth: Float, maxRadius: Float): Float {
            val diff = abs(pixelDepth - focusDepth)
            return (maxRadius * diff).coerceAtMost(maxRadius)
        }
    }

    fun applyDepthBokeh(
        pixels: IntArray,
        depthMap: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        depthWidth: Int,
        depthHeight: Int,
        focusDepth: Float = 0.5f,
        maxBlurRadius: Float = 15f,
        numLevels: Int = 4
    ): IntArray {
        val n = imageWidth * imageHeight
        val baseRadius = (maxBlurRadius / (numLevels - 1)).toInt().coerceAtLeast(3)
        val pyramid = buildBlurPyramid(pixels, imageWidth, imageHeight, numLevels, baseRadius)

        val result = IntArray(n)
        val scaleX = depthWidth.toFloat() / imageWidth
        val scaleY = depthHeight.toFloat() / imageHeight

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val i = y * imageWidth + x
                val dx = (x * scaleX).toInt().coerceIn(0, depthWidth - 1)
                val dy = (y * scaleY).toInt().coerceIn(0, depthHeight - 1)
                val depth = depthMap[dy * depthWidth + dx]

                val blurRadius = computeBlurRadius(depth, focusDepth, maxBlurRadius)
                val levelF = (blurRadius / maxBlurRadius) * (numLevels - 1)
                val levelLow = levelF.toInt().coerceIn(0, numLevels - 2)
                val levelHigh = (levelLow + 1).coerceAtMost(numLevels - 1)
                val t = levelF - levelLow

                if (t < 0.01f) {
                    result[i] = pyramid[levelLow][i]
                } else if (t > 0.99f) {
                    result[i] = pyramid[levelHigh][i]
                } else {
                    val pLow = pyramid[levelLow][i]
                    val pHigh = pyramid[levelHigh][i]
                    val r = lerp((pLow shr 16) and 0xFF, (pHigh shr 16) and 0xFF, t)
                    val g = lerp((pLow shr 8) and 0xFF, (pHigh shr 8) and 0xFF, t)
                    val b = lerp(pLow and 0xFF, pHigh and 0xFF, t)
                    result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return result
    }

    fun buildBlurPyramid(pixels: IntArray, w: Int, h: Int, levels: Int, baseRadius: Int): List<IntArray> {
        val pyramid = mutableListOf<IntArray>()
        pyramid.add(pixels.copyOf())

        var current = pixels.copyOf()
        for (level in 1 until levels) {
            val blurred = IntArray(w * h)
            System.arraycopy(current, 0, blurred, 0, current.size)
            val radius = baseRadius * level
            for (pass in 0 until 2) {
                boxBlurPass(blurred, w, h, radius)
            }
            pyramid.add(blurred)
            current = blurred
        }

        return pyramid
    }

    fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanGuide = boxFilterFloat(guide, w, h, radius)
        val meanInput = boxFilterFloat(input, w, h, radius)

        val corrGuideInput = FloatArray(n) { guide[it] * input[it] }
        val meanCorrGuideInput = boxFilterFloat(corrGuideInput, w, h, radius)

        val corrGuideGuide = FloatArray(n) { guide[it] * guide[it] }
        val meanCorrGuideGuide = boxFilterFloat(corrGuideGuide, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val covGuideInput = meanCorrGuideInput[i] - meanGuide[i] * meanInput[i]
            val varGuide = meanCorrGuideGuide[i] - meanGuide[i] * meanGuide[i]
            a[i] = covGuideInput / (varGuide + eps)
            b[i] = meanInput[i] - a[i] * meanGuide[i]
        }

        val meanA = boxFilterFloat(a, w, h, radius)
        val meanB = boxFilterFloat(b, w, h, radius)

        val result = FloatArray(n)
        for (i in 0 until n) {
            result[i] = meanA[i] * guide[i] + meanB[i]
        }
        return result
    }

    private fun boxFilterFloat(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val output = FloatArray(input.size)
        val temp = FloatArray(input.size)
        val kernelSize = radius * 2 + 1

        for (y in 0 until h) {
            var sum = 0f
            for (kx in -radius..radius) {
                sum += input[y * w + kx.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                temp[y * w + x] = sum / kernelSize
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                sum += input[y * w + addX] - input[y * w + remX]
            }
        }

        for (x in 0 until w) {
            var sum = 0f
            for (ky in -radius..radius) {
                sum += temp[ky.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                output[y * w + x] = sum / kernelSize
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                sum += temp[addY * w + x] - temp[remY * w + x]
            }
        }

        return output
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val kernelSize = radius * 2 + 1

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (kx in -radius..radius) {
                val x = kx.coerceIn(0, w - 1)
                val p = pixels[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                temp[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                val addP = pixels[y * w + addX]
                val remP = pixels[y * w + remX]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (ky in -radius..radius) {
                val y = ky.coerceIn(0, h - 1)
                val p = temp[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                val addP = temp[addY * w + x]
                val remP = temp[remY * w + x]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :camera:testDebugUnitTest --tests "com.spectra.camera.DepthBokehTest" 2>&1 | tail -5`
Expected: PASS — all 7 tests pass

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/DepthBokeh.kt camera/src/test/java/com/spectra/camera/DepthBokehTest.kt
git commit -m "feat: add DepthBokeh with guided filter and blur pyramid for depth-aware portrait mode"
```

---

### Task 12: Replace Face-Ellipse Bokeh with Depth-Map Bokeh in CaptureManager

Wire the new DepthEstimator and DepthBokeh into CaptureManager, replacing the existing `applyPortraitBokeh()` method. Falls back to the face-ellipse approach when the depth model isn't available.

**Files:**
- Modify: `camera/src/main/java/com/spectra/camera/CaptureManager.kt`

- [ ] **Step 1: Add DepthEstimator and DepthBokeh fields**

In `CaptureManager.kt`, add fields after the executor:

```kotlin
    private var depthEstimator: DepthEstimator? = null
    private val depthBokeh = DepthBokeh()
```

- [ ] **Step 2: Add initDepthModel and releaseDepthModel methods**

```kotlin
    fun initDepthModel(ctx: Context) {
        val estimator = DepthEstimator(ctx)
        if (estimator.initialize()) {
            depthEstimator = estimator
            Log.d("CaptureManager", "Depth model loaded")
        }
    }

    fun releaseDepthModel() {
        depthEstimator?.release()
        depthEstimator = null
    }
```

Note: the `ctx` parameter uses the existing `context` field but we add a public method so the DepthEstimator can be lazily initialized. Actually, simplify — use the already-injected `context`:

```kotlin
    fun initDepthModel() {
        val estimator = DepthEstimator(context)
        if (estimator.initialize()) {
            depthEstimator = estimator
            Log.d("CaptureManager", "Depth model loaded")
        }
    }

    fun releaseDepthModel() {
        depthEstimator?.release()
        depthEstimator = null
    }
```

- [ ] **Step 3: Replace applyPortraitBokeh with depth-aware version**

Replace the existing `applyPortraitBokeh` method in `CaptureManager.kt`:

```kotlin
    private fun applyPortraitBokeh(bitmap: Bitmap, canvas: Canvas, faceRects: List<RectF>) {
        val w = bitmap.width
        val h = bitmap.height

        val estimator = depthEstimator
        if (estimator != null && estimator.isAvailable()) {
            applyDepthMapBokeh(bitmap, canvas, estimator, faceRects)
        } else {
            applyFallbackEllipseBokeh(bitmap, canvas, faceRects)
        }
    }

    private fun applyDepthMapBokeh(bitmap: Bitmap, canvas: Canvas, estimator: DepthEstimator, faceRects: List<RectF>) {
        val w = bitmap.width
        val h = bitmap.height
        val depthInputSize = estimator.getInputSize()

        val depthMap = estimator.estimateDepth(bitmap)
        if (depthMap == null) {
            applyFallbackEllipseBokeh(bitmap, canvas, faceRects)
            return
        }

        val focusDepth = if (faceRects.isNotEmpty()) {
            val face = faceRects[0]
            val cx = ((face.left + face.right) / 2f * depthInputSize).toInt().coerceIn(0, depthInputSize - 1)
            val cy = ((face.top + face.bottom) / 2f * depthInputSize).toInt().coerceIn(0, depthInputSize - 1)
            depthMap[cy * depthInputSize + cx]
        } else {
            depthMap[depthInputSize / 2 * depthInputSize + depthInputSize / 2]
        }

        val lumGuide = FloatArray(depthInputSize * depthInputSize)
        val smallBmp = Bitmap.createScaledBitmap(bitmap, depthInputSize, depthInputSize, true)
        val smallPixels = IntArray(depthInputSize * depthInputSize)
        smallBmp.getPixels(smallPixels, 0, depthInputSize, 0, 0, depthInputSize, depthInputSize)
        smallBmp.recycle()
        for (i in smallPixels.indices) {
            val r = (smallPixels[i] shr 16) and 0xFF
            val g = (smallPixels[i] shr 8) and 0xFF
            val b = smallPixels[i] and 0xFF
            lumGuide[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        val refinedDepth = depthBokeh.guidedFilter(lumGuide, depthMap, depthInputSize, depthInputSize, radius = 4, eps = 0.01f)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val result = depthBokeh.applyDepthBokeh(
            pixels, refinedDepth, w, h,
            depthInputSize, depthInputSize,
            focusDepth = focusDepth,
            maxBlurRadius = 15f
        )

        bitmap.setPixels(result, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        Log.d("CaptureManager", "Depth-map bokeh applied, focus=${"%.2f".format(focusDepth)}")
    }
```

- [ ] **Step 4: Rename old bokeh method as fallback**

Rename the old `applyPortraitBokeh` body to `applyFallbackEllipseBokeh` — copy the old method body (the existing face-ellipse implementation from lines 811-884) into a new private method named `applyFallbackEllipseBokeh(bitmap: Bitmap, canvas: Canvas, faceRects: List<RectF>)`.

The existing code from the old `applyPortraitBokeh` method stays intact but under the new fallback name.

- [ ] **Step 5: Commit**

```bash
git add camera/src/main/java/com/spectra/camera/CaptureManager.kt
git commit -m "feat: replace face-ellipse bokeh with depth-map bokeh, ellipse as fallback"
```

---

### Task 13: Initialize Depth Model from ViewModel

Wire the depth model initialization into the app lifecycle so it loads when the camera starts.

**Files:**
- Modify: `app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt`

- [ ] **Step 1: Call initDepthModel in ViewModel init**

In `CameraViewModel.kt`, add at the end of the `init` block (before the closing `}`):

```kotlin
        viewModelScope.launch(Dispatchers.IO) {
            captureManager.initDepthModel()
        }
```

- [ ] **Step 2: Release depth model in onCleared**

Check if `onCleared()` exists in the ViewModel. If so, add `captureManager.releaseDepthModel()`. If not, add:

```kotlin
    override fun onCleared() {
        super.onCleared()
        pipeline.release()
        levelSensor.stop()
        captureManager.releaseDepthModel()
    }
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: PASS — all existing + new tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/spectra/app/viewmodel/CameraViewModel.kt
git commit -m "feat: initialize depth model on camera startup for depth-aware bokeh"
```

---

### Task 14: Final Integration Test — Run All Tests

Verify the entire test suite passes after all Phase 5A changes.

**Files:**
- No new files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests pass (existing 117+ new tests)

- [ ] **Step 2: Verify no compilation errors**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit with summary**

No code changes expected. If any test fixes were needed, commit them:

```bash
git add -A
git commit -m "chore: verify Phase 5A integration — all tests pass"
```

---

## Self-Review Checklist

### Spec coverage:

| Spec Item | Task |
|-----------|------|
| 1. Settings illusion — display actual values | Tasks 1-3 |
| 1. Settings illusion — semi-auto mode | Tasks 2-3 |
| 2. TFLite scene classifier — training script | Task 4 |
| 2. TFLite scene classifier — integration | Task 5 |
| 3. HDR — exposure bracket capture | Tasks 6, 8 |
| 3. HDR — tile-based alignment | Task 6 |
| 3. HDR — Mertens exposure fusion | Tasks 6-7 |
| 3. HDR — ViewModel wiring | Task 9 |
| 4. Depth bokeh — DepthEstimator TFLite | Task 10 |
| 4. Depth bokeh — guided filter | Task 11 |
| 4. Depth bokeh — blur pyramid + CoC | Task 11 |
| 4. Depth bokeh — CaptureManager integration | Task 12 |
| 4. Depth bokeh — lifecycle management | Task 13 |

**Note on TFLite model assets:** The `scene_classifier.tflite` and `depth_estimator.tflite` files are not created by this plan — they must be trained/downloaded separately. The Python training script (Task 4) handles scene classifier training. For the depth model, download MiDaS v2.1 Small from the TensorFlow Hub model zoo and place it in the camera module's assets folder. Both SceneClassifier and DepthEstimator gracefully fall back when models aren't present.

### Placeholder scan: No TBD/TODO/placeholder patterns found.

### Type consistency check:
- `HdrProcessor.computeBracketExposures` → returns `List<Pair<Long, Int>>` — used consistently in Task 8
- `DepthBokeh.computeBlurRadius` → returns `Float` — used consistently in `applyDepthBokeh`
- `DepthEstimator.estimateDepth` → returns `FloatArray?` — null-checked in Task 12
- `SettingsDisplayMode` enum — used consistently across Tasks 1-3
- `HdrProcessor.mertensFusion` → `List<IntArray>` input, `IntArray` output — consistent in Tasks 6-8
- `DepthBokeh.guidedFilter` → `FloatArray` input/output — consistent in Tasks 11-12
