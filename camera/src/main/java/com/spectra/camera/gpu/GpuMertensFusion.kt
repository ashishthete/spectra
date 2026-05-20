package com.spectra.camera.gpu

import android.content.res.AssetManager
import android.util.Log

object GpuMertensFusion {

    private const val TAG = "GpuMertensFusion"

    private var shaderSource: String? = null

    fun loadShader(assets: AssetManager) {
        if (shaderSource != null) return
        shaderSource = try {
            assets.open("shaders/mertens_fusion.comp").bufferedReader().readText()
        } catch (e: Exception) {
            Log.w(TAG, "Could not load mertens_fusion.comp: ${e.message}")
            null
        }
    }

    fun fuse(
        gpuContext: GpuContext?,
        frames: List<FloatArray>,
        w: Int,
        h: Int
    ): FloatArray? {
        if (gpuContext == null || !gpuContext.isAvailable) return null
        val src = shaderSource ?: return null
        if (frames.size != 3) return null

        return try {
            gpuContext.makeCurrent()

            val shader = ComputeShader(src)
            if (shader.programId == 0) return null

            val n = w * h * 3
            val outputF = FloatArray(n)

            shader.setBuffer(0, frames[0])
            shader.setBuffer(1, frames[1])
            shader.setBuffer(2, frames[2])
            shader.setBuffer(3, outputF)
            shader.setUniform("uWidth", w)
            shader.setUniform("uHeight", h)

            val groupsX = (w + 15) / 16
            val groupsY = (h + 15) / 16
            shader.dispatch(groupsX, groupsY)
            shader.readBuffer(3, outputF)
            shader.release()

            Log.d(TAG, "GPU Mertens fusion: ${w}x${h}, 3 frames")
            outputF
        } catch (e: Exception) {
            Log.w(TAG, "GPU Mertens fusion failed: ${e.message}")
            null
        }
    }

    fun fuseFromPixels(
        gpuContext: GpuContext?,
        frames: List<IntArray>,
        w: Int,
        h: Int
    ): IntArray? {
        if (frames.size != 3) return null
        if (gpuContext == null) return null
        val n = w * h

        val rgbFrames = frames.map { pixels ->
            val rgb = FloatArray(n * 3)
            for (i in 0 until n) {
                rgb[i * 3] = ((pixels[i] shr 16) and 0xFF).toFloat()
                rgb[i * 3 + 1] = ((pixels[i] shr 8) and 0xFF).toFloat()
                rgb[i * 3 + 2] = (pixels[i] and 0xFF).toFloat()
            }
            rgb
        }

        val fusedRgb = fuse(gpuContext, rgbFrames, w, h) ?: return null

        val result = IntArray(n)
        for (i in 0 until n) {
            val r = fusedRgb[i * 3].toInt().coerceIn(0, 255)
            val g = fusedRgb[i * 3 + 1].toInt().coerceIn(0, 255)
            val b = fusedRgb[i * 3 + 2].toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return result
    }
}
