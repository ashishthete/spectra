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
