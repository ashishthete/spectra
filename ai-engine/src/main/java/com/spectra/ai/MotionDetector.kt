package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.model.MotionLevel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MotionDetector @Inject constructor() {

    /**
     * Classifies the source of detected motion by cross-referencing
     * gyroscope angular velocity with inter-frame pixel differences.
     *
     * - STABLE:         low gyro + low frame-diff  (tripod / held still)
     * - CAMERA_SHAKE:   high gyro + high frame-diff (hand tremor)
     * - SUBJECT_MOTION: low gyro + high frame-diff  (moving subject, camera steady)
     * - PANNING:        high gyro + low frame-diff   (intentional pan, static subject)
     */
    enum class MotionSource {
        STABLE,
        CAMERA_SHAKE,
        SUBJECT_MOTION,
        PANNING
    }

    private val frameBuffer = ArrayDeque<FloatArray>(3)
    private val downsampleSize = 32

    var currentMotion: MotionLevel = MotionLevel.STATIC
        private set

    var currentMotionSource: MotionSource = MotionSource.STABLE
        private set

    private var lastGyroVelocity: Float = 0f
    private var lastConsistentFrames: Int = 0
    private var lastFrameDiff: Float = 0f

    fun addFrame(downsampled: FloatArray) {
        frameBuffer.addLast(downsampled)
        if (frameBuffer.size > 3) frameBuffer.removeFirst()
        lastFrameDiff = computeFrameDiff()
        currentMotion = classifyMotionLevel(lastFrameDiff)
        currentMotionSource = classifyMotionSource(lastGyroVelocity, lastFrameDiff, lastConsistentFrames)
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
        currentMotionSource = classifyMotionSource(lastGyroVelocity, lastFrameDiff, lastConsistentFrames)
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

    /**
     * Classify the source of motion from gyro magnitude, frame difference,
     * and directional consistency.
     *
     * Thresholds:
     *  - gyro >= 0.5 rad/s  -> camera is moving significantly
     *  - frame-diff >= 0.05 -> visible change between consecutive frames
     *  - For panning: lower gyro threshold (0.3 rad/s) but requires
     *    >= 5 consistent-direction frames to distinguish from erratic shake.
     *
     * This method is public so callers can classify arbitrary sensor
     * readings without feeding frames through the detector.
     */
    fun classifyMotionSource(
        gyroMagnitude: Float,
        frameDiff: Float,
        consistentFrames: Int = 0
    ): MotionSource {
        return when {
            // Panning: moderate+ gyro in a consistent direction with frame change
            gyroMagnitude > 0.3f && consistentFrames >= 5 && frameDiff > 0.05f ->
                MotionSource.PANNING
            // Camera shake: high gyro + noticeable frame change
            gyroMagnitude > 0.5f && frameDiff > 0.05f ->
                MotionSource.CAMERA_SHAKE
            // Subject motion: camera still but scene changing
            gyroMagnitude < 0.2f && frameDiff > 0.08f ->
                MotionSource.SUBJECT_MOTION
            // Everything else is effectively stable
            else -> MotionSource.STABLE
        }
    }

    fun reset() {
        frameBuffer.clear()
        currentMotion = MotionLevel.STATIC
        currentMotionSource = MotionSource.STABLE
        lastGyroVelocity = 0f
        lastConsistentFrames = 0
        lastFrameDiff = 0f
    }
}
