package com.spectra.ai

import android.content.Context
import android.graphics.Bitmap
import com.spectra.ai.model.FaceData
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
        try {
            val model = loadModelFile("scene_classifier.tflite")
            gpuDelegate = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
                setNumThreads(4)
            }
            interpreter = Interpreter(model, options)
        } catch (_: Exception) {
            interpreter = null
        }
    }

    fun classify(bitmap: Bitmap, isFrontCamera: Boolean = false, faceData: FaceData = FaceData.EMPTY): Pair<SceneType, Float> {
        if (isFrontCamera) {
            return Pair(SceneType.PORTRAIT, 0.70f)
        }

        val (baseScene, baseConf) = classifyBase(bitmap)
        return applyFaceBoost(baseScene, baseConf, faceData)
    }

    private fun classifyBase(bitmap: Bitmap): Pair<SceneType, Float> {
        val interp = interpreter
        if (interp != null) {
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

        return classifyHeuristic(bitmap)
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

    private fun classifyHeuristic(bitmap: Bitmap): Pair<SceneType, Float> {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        var totalR = 0L; var totalG = 0L; var totalB = 0L
        var greenCount = 0; var blueCount = 0; var warmCount = 0
        var brightPixels = 0; var darkPixels = 0

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalR += r; totalG += g; totalB += b
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            if (lum > 200) brightPixels++
            if (lum < 50) darkPixels++
            if (g > r + 20 && g > b + 20) greenCount++
            if (b > r + 30 && b > g + 10) blueCount++
            if (r > b + 40 && r > 120) warmCount++
        }

        val n = pixels.size.toFloat()
        val avgR = totalR / n; val avgG = totalG / n; val avgB = totalB / n
        val avgLum = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB
        val greenRatio = greenCount / n
        val blueRatio = blueCount / n
        val warmRatio = warmCount / n
        val brightRatio = brightPixels / n
        val darkRatio = darkPixels / n

        return when {
            darkRatio > 0.6 -> Pair(SceneType.INDOOR, 0.55f)
            greenRatio > 0.3 -> Pair(SceneType.LANDSCAPE, 0.60f)
            blueRatio > 0.25 -> Pair(SceneType.LANDSCAPE, 0.55f)
            warmRatio > 0.3 && avgLum > 100 -> Pair(SceneType.FOOD, 0.50f)
            brightRatio > 0.5 -> Pair(SceneType.ARCHITECTURE, 0.45f)
            avgLum < 80 -> Pair(SceneType.INDOOR, 0.50f)
            avgLum in 80.0..160.0 -> Pair(SceneType.PORTRAIT, 0.45f)
            else -> Pair(SceneType.INDOOR, 0.40f)
        }
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
