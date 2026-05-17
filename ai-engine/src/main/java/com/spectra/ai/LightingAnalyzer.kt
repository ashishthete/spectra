package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LightingAnalyzer @Inject constructor() {

    private var lastCondition: LightingCondition = LightingCondition.UNKNOWN
    private var pendingCondition: LightingCondition? = null
    private var pendingCount: Int = 0

    fun analyzeFromMetadata(
        avgBrightness: Float,
        exposureTimeNs: Long,
        iso: Int,
        colorTemperature: Int
    ): LightingCondition {
        val exposureMs = exposureTimeNs / 1_000_000f

        val raw = when {
            avgBrightness < 30f -> LightingCondition.LOW_LIGHT
            avgBrightness < 60f && colorTemperature in 3800..4500 -> LightingCondition.BLUE_HOUR
            avgBrightness in 80f..180f && colorTemperature < 4000 -> LightingCondition.GOLDEN_HOUR
            avgBrightness > 200f && colorTemperature in 5000..7000 -> LightingCondition.HARSH_MIDDAY
            avgBrightness > 120f && colorTemperature in 5000..6500 -> LightingCondition.BRIGHT_DAYLIGHT
            avgBrightness in 60f..150f && colorTemperature > 6500 -> LightingCondition.OVERCAST
            iso > 800 && exposureMs > 30 -> LightingCondition.LOW_LIGHT
            colorTemperature < 3500 && avgBrightness in 50f..150f -> LightingCondition.ARTIFICIAL
            else -> LightingCondition.BRIGHT_DAYLIGHT
        }

        if (raw == lastCondition) {
            pendingCondition = null
            pendingCount = 0
            return raw
        }

        if (raw == pendingCondition) {
            pendingCount++
        } else {
            pendingCondition = raw
            pendingCount = 1
        }

        if (pendingCount >= HYSTERESIS_FRAMES) {
            lastCondition = raw
            pendingCondition = null
            pendingCount = 0
            return raw
        }

        return lastCondition
    }

    companion object {
        private const val HYSTERESIS_FRAMES = 5
    }

    fun analyzeBrightness(pixels: IntArray, width: Int, height: Int): Float {
        if (pixels.isEmpty()) return 0f
        var totalBrightness = 0L
        val step = maxOf(1, pixels.size / 10000)
        var count = 0
        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            count++
        }
        return if (count > 0) totalBrightness.toFloat() / count else 0f
    }

    fun estimateColorTemperature(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 5500
        var totalR = 0L; var totalG = 0L; var totalB = 0L
        val step = maxOf(1, pixels.size / 5000)
        var count = 0
        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            if (lum < 30 || lum > 240) continue
            totalR += r; totalG += g; totalB += b
            count++
        }
        if (count == 0) return 5500
        val avgR = totalR.toFloat() / count
        val avgG = totalG.toFloat() / count
        val avgB = totalB.toFloat() / count
        if (avgG < 1f) return 5500
        val rbRatio = avgR / avgB.coerceAtLeast(1f)
        return when {
            rbRatio > 2.0f -> 2800
            rbRatio > 1.6f -> 3200
            rbRatio > 1.3f -> 3800
            rbRatio > 1.15f -> 4500
            rbRatio > 1.0f -> 5200
            rbRatio > 0.9f -> 5800
            rbRatio > 0.8f -> 6500
            rbRatio > 0.7f -> 7500
            else -> 9000
        }
    }
}
