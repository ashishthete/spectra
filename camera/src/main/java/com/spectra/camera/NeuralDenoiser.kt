package com.spectra.camera

import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class NeuralDenoiser {
    private val TAG = "NeuralDenoiser"
    private var interpreter: Interpreter? = null
    private val tileSize = 256  // Process in 256x256 tiles
    private val overlap = 16    // Overlap tiles to avoid seam artifacts

    val isAvailable: Boolean get() = interpreter != null

    fun init(assetManager: AssetManager, modelPath: String = "denoise_model.tflite"): Boolean {
        return try {
            val model = loadModelFile(assetManager, modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Try NNAPI delegate for NPU acceleration (Hexagon DSP on Snapdragon)
                try {
                    addDelegate(org.tensorflow.lite.nnapi.NnApiDelegate())
                    Log.d(TAG, "NNAPI delegate enabled — using NPU")
                } catch (e: Exception) {
                    Log.d(TAG, "NNAPI not available, using CPU")
                }
            }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "Neural denoiser loaded: $modelPath")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load neural denoiser: $modelPath", e)
            false
        }
    }

    fun denoise(
        luminance: FloatArray,
        w: Int,
        h: Int,
        noiseLevel: Float = 0.05f
    ): FloatArray {
        val interp = interpreter ?: return luminance

        val output = FloatArray(w * h)
        System.arraycopy(luminance, 0, output, 0, luminance.size)

        var ty = 0
        while (ty < h) {
            var tx = 0
            val coreH = minOf(tileSize, h - ty)
            while (tx < w) {
                val coreW = minOf(tileSize, w - tx)

                val srcX0 = maxOf(0, tx - overlap)
                val srcY0 = maxOf(0, ty - overlap)
                val srcX1 = minOf(w, tx + coreW + overlap)
                val srcY1 = minOf(h, ty + coreH + overlap)

                val padW = tileSize
                val padH = tileSize
                val inputTile = FloatArray(padW * padH)
                for (row in 0 until (srcY1 - srcY0)) {
                    for (col in 0 until (srcX1 - srcX0)) {
                        inputTile[row * padW + col] = luminance[(srcY0 + row) * w + (srcX0 + col)]
                    }
                }

                val outputTile = runInference(inputTile, padW, padH, noiseLevel)

                val offX = tx - srcX0
                val offY = ty - srcY0
                for (row in 0 until coreH) {
                    for (col in 0 until coreW) {
                        output[(ty + row) * w + (tx + col)] = outputTile[(offY + row) * padW + (offX + col)]
                    }
                }

                tx += tileSize
            }
            ty += tileSize
        }

        return output
    }

    private fun runInference(tile: FloatArray, w: Int, h: Int, noiseLevel: Float): FloatArray {
        val interp = interpreter ?: return tile

        // Input: [1, H, W, 1] float32 (luminance normalized 0-1)
        val inputBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (v in tile) inputBuffer.putFloat(v / 255f)
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.w(TAG, "Inference failed on tile", e)
            return tile
        }

        outputBuffer.rewind()
        val result = FloatArray(w * h)
        for (i in result.indices) {
            result[i] = (outputBuffer.float * 255f).coerceIn(0f, 255f)
        }
        return result
    }

    private fun loadModelFile(assetManager: AssetManager, path: String): MappedByteBuffer {
        val fd = assetManager.openFd(path)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
