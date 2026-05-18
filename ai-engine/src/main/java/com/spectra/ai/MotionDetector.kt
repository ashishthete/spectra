package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.model.MotionLevel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MotionDetector @Inject constructor() {

    enum class MotionType {
        STATIC,
        CAMERA_SHAKE,
        SUBJECT_MOTION,
        PAN
    }

    private val frameBuffer = ArrayDeque<FloatArray>(3)
    private val downsampleSize = 32

    var currentMotion: MotionLevel = MotionLevel.STATIC
        private set

    var currentMotionType: MotionType = MotionType.STATIC
        private set

    private var lastGyroVelocity: Float = 0f
    private var lastConsistentFrames: Int = 0
    private var lastFrameDiff: Float = 0f

    fun addFrame(downsampled: FloatArray) {
        frameBuffer.addLast(downsampled)
        if (frameBuffer.size > 3) frameBuffer.removeFirst()
        lastFrameDiff = computeFrameDiff()
        currentMotion = classifyMotionLevel(lastFrameDiff)
        currentMotionType = classifyMotionType()
    }

    fun addBitmap(bitmap: Bitmap) {
        val small = Bitmap.createScaledBitmap(bitmap, downsampleSize, downsampleSize, true)
        val pixels = IntArray(downsampleSize * downsampleSize)
        small.getPixels(pixels, 0, downsampleSize, 0, 0, downsampleSize, downsampleSize)
        small.recycle()
        val brightness = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        addFrame(brightness)
    }

    fun updateGyro(angularVelocity: Float, consistentFrames: Int) {
        lastGyroVelocity = angularVelocity
        lastConsistentFrames = consistentFrames
        currentMotionType = classifyMotionType()
    }

    private fun computeFrameDiff(): Float {
        if (frameBuffer.size < 2) return 0f
        val prev = frameBuffer[frameBuffer.size - 2]
        val curr = frameBuffer[frameBuffer.size - 1]
        val minLen = minOf(prev.size, curr.size)
        if (minLen == 0) return 0f

        var diff = 0f
        for (i in 0 until minLen) {
            diff += kotlin.math.abs(curr[i] - prev[i])
        }
        return diff / minLen
    }

    private fun classifyMotionLevel(avgDiff: Float): MotionLevel {
        return when {
            avgDiff < 0.02f -> MotionLevel.STATIC
            avgDiff < 0.05f -> MotionLevel.SLOW
            avgDiff < 0.12f -> MotionLevel.MODERATE
            avgDiff < 0.25f -> MotionLevel.FAST
            else -> MotionLevel.VERY_FAST
        }
    }

    private fun classifyMotionType(): MotionType {
        val omega = lastGyroVelocity
        val diff = lastFrameDiff

        return when {
            omega > 0.3f && lastConsistentFrames >= 5 && diff > 0.05f -> MotionType.PAN
            omega > 0.5f && diff > 0.05f -> MotionType.CAMERA_SHAKE
            omega < 0.2f && diff > 0.08f -> MotionType.SUBJECT_MOTION
            else -> MotionType.STATIC
        }
    }

    fun reset() {
        frameBuffer.clear()
        currentMotion = MotionLevel.STATIC
        currentMotionType = MotionType.STATIC
        lastGyroVelocity = 0f
        lastConsistentFrames = 0
        lastFrameDiff = 0f
    }
}
