package com.spectra.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DepthEstimator(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private val inputSize = 256
    private var delegateType = "none"

    fun initialize(): Boolean {
        return try {
            val model = loadModelFile("depth_estimator.tflite")
            interpreter = tryNnApi(model) ?: tryGpu(model) ?: tryCpu(model)
            val interp = interpreter ?: return false
            val inputShape = interp.getInputTensor(0).shape()
            val outputShape = interp.getOutputTensor(0).shape()
            Log.d("DepthEstimator", "Model validated: delegate=$delegateType, input=${inputShape.toList()}, output=${outputShape.toList()}")
            true
        } catch (_: Exception) {
            Log.w("DepthEstimator", "Depth model not available")
            false
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

    fun estimateDepth(bitmap: Bitmap): FloatArray? = estimateDepthAtSize(bitmap, inputSize)

    fun estimateDepthHighRes(bitmap: Bitmap, outputSize: Int = 512): FloatArray? {
        val baseMap = estimateDepthAtSize(bitmap, inputSize) ?: return null
        if (outputSize <= inputSize) return baseMap
        return bilinearUpsample(baseMap, inputSize, inputSize, outputSize, outputSize)
    }

    private fun bilinearUpsample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            val sy = dy * scaleY
            val y0 = sy.toInt().coerceIn(0, srcH - 2)
            val y1 = y0 + 1
            val fy = sy - y0
            for (dx in 0 until dstW) {
                val sx = dx * scaleX
                val x0 = sx.toInt().coerceIn(0, srcW - 2)
                val x1 = x0 + 1
                val fx = sx - x0
                val v00 = src[y0 * srcW + x0]
                val v10 = src[y0 * srcW + x1]
                val v01 = src[y1 * srcW + x0]
                val v11 = src[y1 * srcW + x1]
                dst[dy * dstW + dx] = v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
                        v01 * (1 - fx) * fy + v11 * fx * fy
            }
        }
        return dst
    }

    private fun estimateDepthAtSize(bitmap: Bitmap, size: Int): FloatArray? {
        val interp = interpreter ?: return null

        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val inputBuffer = bitmapToByteBuffer(resized, size)
        resized.recycle()

        return try {
            val outputArray = Array(1) { Array(size) { FloatArray(size) } }
            interp.run(inputBuffer, outputArray)

            val depthMap = FloatArray(size * size)
            var minDepth = Float.MAX_VALUE
            var maxDepth = Float.MIN_VALUE
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val d = outputArray[0][y][x]
                    depthMap[y * size + x] = d
                    if (d < minDepth) minDepth = d
                    if (d > maxDepth) maxDepth = d
                }
            }

            val range = maxDepth - minDepth
            if (range < 0.001f) return depthMap
            for (i in depthMap.indices) {
                depthMap[i] = (depthMap[i] - minDepth) / range
            }
            depthMap
        } catch (e: Exception) {
            Log.w("DepthEstimator", "Depth estimation at ${size}x$size failed", e)
            null
        }
    }

    fun getInputSize(): Int = inputSize

    fun isAvailable(): Boolean = interpreter != null

    private fun bitmapToByteBuffer(bitmap: Bitmap, size: Int = inputSize): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
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
        nnApiDelegate?.close()
        interpreter = null
        gpuDelegate = null
        nnApiDelegate = null
    }
}
