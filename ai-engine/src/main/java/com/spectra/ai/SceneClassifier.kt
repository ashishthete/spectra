package com.spectra.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.spectra.ai.model.FaceData
import com.spectra.core.model.SceneType
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
    private var nnApiDelegate: NnApiDelegate? = null
    private val inputSize = 224
    private val labelMap = mutableListOf<SceneType>()
    private var delegateType = "cpu"

    // Temporal scene smoothing (hysteresis) to prevent HUD flicker
    private val hysteresis = SceneHysteresis()

    fun initialize() {
        loadLabels()
        try {
            val model = loadModelFile("scene_classifier.tflite")
            interpreter = tryNnApi(model) ?: tryGpu(model) ?: tryCpu(model)
            validateModelMetadata()
        } catch (e: Exception) {
            Log.w("SceneClassifier", "TFLite model not available, using heuristic classification", e)
            interpreter = null
        }
    }

    private fun validateModelMetadata() {
        val interp = interpreter ?: return
        val outputShape = interp.getOutputTensor(0).shape()
        val outputDim = if (outputShape.size >= 2) outputShape[1] else outputShape[0]
        if (labelMap.isNotEmpty() && labelMap.size != outputDim) {
            Log.w("SceneClassifier", "Label count (${labelMap.size}) != model output dim ($outputDim), falling back to heuristics")
            interpreter?.close()
            interpreter = null
        } else {
            Log.d("SceneClassifier", "Model validated: delegate=$delegateType, labels=${labelMap.size}, output=$outputDim")
        }
    }

    private fun tryNnApi(model: MappedByteBuffer): Interpreter? = try {
        val delegate = NnApiDelegate(
            NnApiDelegate.Options().setAllowFp16(true).setUseNnapiCpu(false)
        )
        val options = Interpreter.Options().apply { addDelegate(delegate) }
        val interp = Interpreter(model, options)
        nnApiDelegate = delegate
        delegateType = "nnapi"
        interp
    } catch (_: Exception) {
        nnApiDelegate?.close(); nnApiDelegate = null; null
    }

    private fun tryGpu(model: MappedByteBuffer): Interpreter? = try {
        val delegate = GpuDelegate()
        val options = Interpreter.Options().apply { addDelegate(delegate); setNumThreads(4) }
        val interp = Interpreter(model, options)
        gpuDelegate = delegate
        delegateType = "gpu"
        interp
    } catch (_: Exception) {
        gpuDelegate?.close(); gpuDelegate = null; null
    }

    private fun tryCpu(model: MappedByteBuffer): Interpreter? = try {
        val options = Interpreter.Options().apply { setNumThreads(4) }
        delegateType = "cpu"
        Interpreter(model, options)
    } catch (_: Exception) { null }

    fun classify(bitmap: Bitmap, isFrontCamera: Boolean = false, faceData: FaceData = FaceData.EMPTY): Pair<SceneType, Float> {
        val (baseScene, baseConf) = classifyBase(bitmap, faceData)
        val (boostedScene, boostedConf) = applyFaceBoost(baseScene, baseConf, faceData)
        // Apply front camera bias BEFORE hysteresis so smoothing works on biased scene
        val (biasedScene, biasedConf) = if (isFrontCamera) {
            applyFrontCameraBias(boostedScene, boostedConf, faceData.faceCount)
        } else {
            Pair(boostedScene, boostedConf)
        }
        return Pair(applyHysteresis(biasedScene), biasedConf)
    }

    /**
     * Front camera bias: remap non-face scene types to PORTRAIT.
     * NIGHT is preserved (low-light selfies are a real use case).
     * LANDSCAPE, MACRO, DOCUMENT are never valid for front camera.
     * UNKNOWN is upgraded to PORTRAIT when faces are detected.
     */
    private fun applyFrontCameraBias(scene: SceneType, confidence: Float, faceCount: Int): Pair<SceneType, Float> {
        return when (scene) {
            SceneType.LANDSCAPE, SceneType.MACRO, SceneType.DOCUMENT ->
                Pair(SceneType.PORTRAIT, maxOf(confidence, 0.70f))
            SceneType.FOOD, SceneType.ACTION ->
                if (faceCount > 0) Pair(SceneType.PORTRAIT, maxOf(confidence, 0.70f))
                else Pair(scene, confidence)
            SceneType.UNKNOWN ->
                if (faceCount > 0) Pair(SceneType.PORTRAIT, maxOf(confidence, 0.70f))
                else Pair(scene, confidence)
            else -> Pair(scene, confidence)
        }
    }

    private fun classifyBase(bitmap: Bitmap, faceData: FaceData = FaceData.EMPTY): Pair<SceneType, Float> {
        val interp = interpreter
        if (interp != null && labelMap.isNotEmpty()) {
            val safeBmp = if (bitmap.config == null || bitmap.colorSpace == null) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return classifyHeuristic(bitmap, faceData)
            } else bitmap
            val resized = Bitmap.createScaledBitmap(safeBmp, inputSize, inputSize, true)
            if (safeBmp !== bitmap) safeBmp.recycle()
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

        return classifyHeuristic(bitmap, faceData)
    }

    private fun applyFaceBoost(scene: SceneType, confidence: Float, faceData: FaceData): Pair<SceneType, Float> {
        if (!faceData.hasFaces) return Pair(scene, confidence)

        return when {
            faceData.isGroupShot -> Pair(SceneType.PORTRAIT, maxOf(confidence, 0.80f))
            faceData.isCoupleShot -> Pair(SceneType.PORTRAIT, maxOf(confidence, 0.75f))
            faceData.isSingleFace -> {
                if (scene == SceneType.PORTRAIT) {
                    Pair(SceneType.PORTRAIT, maxOf(confidence, 0.85f))
                } else {
                    Pair(SceneType.PORTRAIT, maxOf(confidence, 0.70f))
                }
            }
            else -> Pair(scene, confidence)
        }
    }

    private fun classifyHeuristic(bitmap: Bitmap, faceData: FaceData = FaceData.EMPTY): Pair<SceneType, Float> {
        val size = 96
        val safeBitmap = if (bitmap.config == null || bitmap.colorSpace == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return Pair(SceneType.UNKNOWN, 0f)
        } else bitmap
        val sample = Bitmap.createScaledBitmap(safeBitmap, size, size, true)
        if (safeBitmap !== bitmap) safeBitmap.recycle()
        val pixels = IntArray(size * size)
        sample.getPixels(pixels, 0, size, 0, 0, size, size)
        sample.recycle()

        val n = pixels.size.toFloat()
        var totalR = 0L; var totalG = 0L; var totalB = 0L
        var brightPixels = 0; var darkPixels = 0
        var greenDominant = 0; var blueSkyLike = 0
        var highSatCount = 0; var lowSatCount = 0
        val lumValues = FloatArray(pixels.size)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            totalR += r; totalG += g; totalB += b
            val lum = (0.299f * r + 0.587f * g + 0.114f * b)
            lumValues[i] = lum
            if (lum > 210) brightPixels++
            if (lum < 40) darkPixels++

            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val sat = if (maxC > 0) (maxC - minC) / maxC.toFloat() else 0f
            if (sat > 0.4f) highSatCount++
            if (sat < 0.15f) lowSatCount++

            if (g > r + 15 && g > b + 15 && sat > 0.25f) greenDominant++
            if (b > 150 && b > r + 40 && lum > 100 && sat > 0.2f) blueSkyLike++
        }

        val avgLum = lumValues.average().toFloat()
        val darkRatio = darkPixels / n
        val brightRatio = brightPixels / n
        val greenRatio = greenDominant / n
        val skyRatio = blueSkyLike / n
        val highSatRatio = highSatCount / n
        val lowSatRatio = lowSatCount / n

        var edgeSum = 0.0
        var edgeCount = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val c = lumValues[y * size + x]
                val t = lumValues[(y - 1) * size + x]
                val b2 = lumValues[(y + 1) * size + x]
                val l = lumValues[y * size + (x - 1)]
                val r2 = lumValues[y * size + (x + 1)]
                edgeSum += kotlin.math.abs(4 * c - t - b2 - l - r2)
                edgeCount++
            }
        }
        val edgeDensity = if (edgeCount > 0) (edgeSum / edgeCount).toFloat() else 0f

        val topThird = lumValues.take(size * size / 3)
        val bottomThird = lumValues.takeLast(size * size / 3)
        val topAvgLum = if (topThird.isNotEmpty()) topThird.average().toFloat() else avgLum
        val bottomAvgLum = if (bottomThird.isNotEmpty()) bottomThird.average().toFloat() else avgLum
        val topBrighter = topAvgLum > bottomAvgLum + 30

        val scores = mutableMapOf<SceneType, Float>()

        if (faceData.hasFaces) {
            val faceArea = faceData.faces.sumOf {
                (it.bounds.width() * it.bounds.height()).toDouble()
            }.toFloat()
            scores[SceneType.PORTRAIT] = 0.55f + faceArea.coerceAtMost(0.3f)
        }

        if (darkRatio > 0.5f) {
            scores[SceneType.NIGHT] = 0.45f + darkRatio * 0.2f
            scores[SceneType.INDOOR] = 0.40f + darkRatio * 0.1f
        }

        if (greenRatio > 0.15f && topBrighter) {
            scores[SceneType.LANDSCAPE] = 0.40f + greenRatio * 0.5f + (if (skyRatio > 0.1f) 0.15f else 0f)
        }

        if (skyRatio > 0.2f && topBrighter && greenRatio < 0.1f) {
            scores[SceneType.ARCHITECTURE] = 0.40f + skyRatio * 0.3f
        }

        if (edgeDensity > 15f && lowSatRatio > 0.5f) {
            scores[SceneType.DOCUMENT] = 0.45f + (edgeDensity / 40f).coerceAtMost(0.25f)
        }

        if (edgeDensity > 12f && highSatRatio > 0.3f && !faceData.hasFaces) {
            val existing = scores[SceneType.ARCHITECTURE] ?: 0f
            scores[SceneType.ARCHITECTURE] = maxOf(existing, 0.40f + edgeDensity / 50f)
        }

        val avgR = (totalR / n).toFloat()
        val avgG = (totalG / n).toFloat()
        val avgB = (totalB / n).toFloat()
        val warmDominant = avgR > avgG + 20 && avgR > avgB + 30

        if (warmDominant && highSatRatio > 0.25f && edgeDensity < 12f && !faceData.hasFaces) {
            scores[SceneType.FOOD] = 0.45f + highSatRatio * 0.3f + (if (warmDominant) 0.1f else 0f)
        }

        if (!faceData.hasFaces && edgeDensity in 5f..15f && highSatRatio > 0.15f && avgLum in 80f..200f) {
            val existing = scores[SceneType.PET] ?: 0f
            scores[SceneType.PET] = maxOf(existing, 0.30f + highSatRatio * 0.2f)
        }

        if (!faceData.hasFaces && edgeDensity > 8f && brightRatio < 0.3f && darkRatio < 0.3f) {
            val existing = scores[SceneType.ACTION] ?: 0f
            scores[SceneType.ACTION] = maxOf(existing, 0.25f + edgeDensity / 60f)
        }

        if (avgLum in 60f..180f && lowSatRatio < 0.4f && !faceData.hasFaces && greenRatio < 0.15f) {
            scores[SceneType.INDOOR] = maxOf(scores[SceneType.INDOOR] ?: 0f, 0.35f)
        }

        if (scores.isEmpty()) {
            scores[SceneType.INDOOR] = 0.35f
        }

        val best = scores.maxByOrNull { it.value }!!
        return Pair(best.key, best.value.coerceAtMost(0.75f))
    }

    private fun applySoftmax(input: FloatArray): FloatArray {
        val maxVal = input.max()
        val exps = FloatArray(input.size) { kotlin.math.exp((input[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            buffer.putFloat((r - 0.485f) / 0.229f)
            buffer.putFloat((g - 0.456f) / 0.224f)
            buffer.putFloat((b - 0.406f) / 0.225f)
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
            // Leave labelMap empty so heuristic path is used when labels file is missing
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    private fun applyHysteresis(rawScene: SceneType): SceneType {
        return hysteresis.smooth(rawScene)
    }

    fun release() {
        interpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
        interpreter = null
        gpuDelegate = null
        nnApiDelegate = null
    }
}
