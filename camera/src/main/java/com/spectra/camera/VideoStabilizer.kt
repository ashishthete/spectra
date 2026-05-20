package com.spectra.camera

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class VideoStabilizer(
    private val smoothingWindowSize: Int = 30,
    private val cropMargin: Float = 0.05f
) {

    private val TAG = "VideoStabilizer"

    data class FrameTransform(
        val dx: Float = 0f,
        val dy: Float = 0f,
        val dAngle: Float = 0f
    )

    private val trajectoryX = mutableListOf<Float>()
    private val trajectoryY = mutableListOf<Float>()
    private val trajectoryAngle = mutableListOf<Float>()
    private var cumulativeX = 0f
    private var cumulativeY = 0f
    private var cumulativeAngle = 0f

    fun addFrame(transform: FrameTransform): FrameTransform {
        cumulativeX += transform.dx
        cumulativeY += transform.dy
        cumulativeAngle += transform.dAngle

        trajectoryX.add(cumulativeX)
        trajectoryY.add(cumulativeY)
        trajectoryAngle.add(cumulativeAngle)

        val smoothX = movingAverage(trajectoryX)
        val smoothY = movingAverage(trajectoryY)
        val smoothAngle = movingAverage(trajectoryAngle)

        val correctionX = smoothX - cumulativeX
        val correctionY = smoothY - cumulativeY
        val correctionAngle = smoothAngle - cumulativeAngle

        return FrameTransform(
            dx = correctionX,
            dy = correctionY,
            dAngle = correctionAngle
        )
    }

    fun estimateTransformFromGyro(
        gyroX: Float, gyroY: Float, gyroZ: Float,
        dtSeconds: Float,
        focalLengthPx: Float
    ): FrameTransform {
        val dx = -gyroY * dtSeconds * focalLengthPx
        val dy = gyroX * dtSeconds * focalLengthPx
        val dAngle = gyroZ * dtSeconds
        return FrameTransform(dx, dy, dAngle)
    }

    fun applyCorrection(
        pixels: IntArray, w: Int, h: Int,
        correction: FrameTransform
    ): IntArray {
        val result = IntArray(w * h)
        val cx = w / 2f; val cy = h / 2f
        val cosA = cos(correction.dAngle)
        val sinA = sin(correction.dAngle)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val rx = x - cx; val ry = y - cy
                val srcXf = (cosA * rx - sinA * ry + cx - correction.dx)
                val srcYf = (sinA * rx + cosA * ry + cy - correction.dy)
                val srcX = srcXf.toInt().coerceIn(0, w - 1)
                val srcY = srcYf.toInt().coerceIn(0, h - 1)
                result[y * w + x] = pixels[srcY * w + srcX]
            }
        }
        return result
    }

    fun getCropRect(width: Int, height: Int): android.graphics.Rect {
        val marginW = (width * cropMargin).toInt()
        val marginH = (height * cropMargin).toInt()
        return android.graphics.Rect(marginW, marginH, width - marginW, height - marginH)
    }

    fun reset() {
        trajectoryX.clear()
        trajectoryY.clear()
        trajectoryAngle.clear()
        cumulativeX = 0f
        cumulativeY = 0f
        cumulativeAngle = 0f
    }

    private fun movingAverage(data: List<Float>): Float {
        val size = data.size
        if (size == 0) return 0f
        val start = maxOf(0, size - smoothingWindowSize)
        var sum = 0f
        for (i in start until size) sum += data[i]
        return sum / (size - start)
    }
}
