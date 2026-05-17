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
