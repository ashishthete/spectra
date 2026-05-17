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
