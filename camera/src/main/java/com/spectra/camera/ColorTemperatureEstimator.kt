package com.spectra.camera

object ColorTemperatureEstimator {

    private val breakpoints = floatArrayOf(0.55f, 0.70f, 0.95f, 1.20f, 1.50f, 1.80f, 2.20f)
    private val kelvinValues = intArrayOf(8500, 7500, 6500, 5500, 4000, 3200, 2800, 2400)

    fun fromGainRatio(rGain: Float, bGain: Float): Int {
        val ratio = rGain / bGain.coerceAtLeast(0.01f)
        for (i in breakpoints.indices) {
            if (ratio < breakpoints[i]) return kelvinValues[i]
            if (i < breakpoints.size - 1 && ratio < breakpoints[i + 1]) {
                val t = (ratio - breakpoints[i]) / (breakpoints[i + 1] - breakpoints[i])
                return (kelvinValues[i] + t * (kelvinValues[i + 1] - kelvinValues[i])).toInt()
            }
        }
        return kelvinValues.last()
    }

    data class MixedLightingResult(
        val dominantKelvin: Int,
        val secondaryKelvin: Int?,
        val mixRatio: Float,
        val isMixed: Boolean
    )

    fun estimateMixedLighting(pixels: IntArray, width: Int, height: Int, gridSize: Int = 8): MixedLightingResult {
        val cellW = width / gridSize
        val cellH = height / gridSize
        val kelvinSamples = mutableListOf<Int>()

        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
                for (py in 0 until cellH) {
                    for (px in 0 until cellW) {
                        val x = gx * cellW + px
                        val y = gy * cellH + py
                        if (x < width && y < height) {
                            val p = pixels[y * width + x]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            val lum = (r * 77 + g * 150 + b * 29) shr 8
                            if (lum in 40..220) {
                                rSum += r; gSum += g; bSum += b; count++
                            }
                        }
                    }
                }
                if (count > cellW * cellH / 4) {
                    val rAvg = (rSum.toFloat() / count).coerceAtLeast(1f)
                    val bAvg = (bSum.toFloat() / count).coerceAtLeast(1f)
                    val gAvg = (gSum.toFloat() / count).coerceAtLeast(1f)
                    val rGain = gAvg / rAvg
                    val bGain = gAvg / bAvg
                    kelvinSamples.add(fromGainRatio(rGain, bGain))
                }
            }
        }

        if (kelvinSamples.isEmpty()) return MixedLightingResult(5500, null, 0f, false)

        kelvinSamples.sort()
        val median = kelvinSamples[kelvinSamples.size / 2]
        val low = kelvinSamples[kelvinSamples.size / 10]
        val high = kelvinSamples[kelvinSamples.size * 9 / 10]
        val spread = high - low

        return if (spread > 1500) {
            MixedLightingResult(
                dominantKelvin = median,
                secondaryKelvin = if (kotlin.math.abs(low - median) > kotlin.math.abs(high - median)) low else high,
                mixRatio = (spread.toFloat() / 5000f).coerceIn(0f, 1f),
                isMixed = true
            )
        } else {
            MixedLightingResult(median, null, 0f, false)
        }
    }
}
