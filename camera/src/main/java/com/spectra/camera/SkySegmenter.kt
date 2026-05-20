package com.spectra.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SkySegmenter {

    fun detectSkyMask(pixels: IntArray, w: Int, h: Int): BooleanArray {
        val mask = BooleanArray(w * h)

        val upperH = max(h / 3, 1)
        var sumR = 0L; var sumG = 0L; var sumB = 0L; var count = 0
        for (y in 0 until upperH) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
                count++
            }
        }
        val avgR = (sumR / count).toInt()
        val avgG = (sumG / count).toInt()
        val avgB = (sumB / count).toInt()
        val avgLum = (avgR * 77 + avgG * 150 + avgB * 29) shr 8

        val lumThreshold = max(avgLum - 40, 60)
        val colorTolerance = max(avgLum / 5, 20)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val lum = (r * 77 + g * 150 + b * 29) shr 8

                val isBlueSky = b > lumThreshold && b > r + 15 && b > g && g > r * 0.5f
                val isOvercastSky = lum > lumThreshold &&
                    abs(r - g) < colorTolerance && abs(g - b) < colorTolerance
                val isWarmSky = lum > lumThreshold && r > g && r > b &&
                    abs(r - avgR) < colorTolerance && abs(g - avgG) < colorTolerance

                val verticalBias = 1f - (y.toFloat() / h)
                val isUpperHalf = verticalBias > 0.4f

                mask[i] = (isBlueSky || isOvercastSky || isWarmSky) && isUpperHalf
            }
        }

        refineMask(mask, w, h)
        return mask
    }

    fun computeSkyFraction(mask: BooleanArray): Float {
        var count = 0
        for (v in mask) if (v) count++
        return count.toFloat() / mask.size
    }

    fun applyGndFilter(
        pixels: IntArray, w: Int, h: Int,
        skyMask: BooleanArray,
        strength: Float = 0.5f
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!skyMask[i]) continue

                val verticalPos = y.toFloat() / h
                val gradientFactor = 1f - strength * (1f - verticalPos)

                val r = ((pixels[i] shr 16) and 0xFF)
                val g = ((pixels[i] shr 8) and 0xFF)
                val b = (pixels[i] and 0xFF)

                val newR = (r * gradientFactor).toInt().coerceIn(0, 255)
                val newG = (g * gradientFactor).toInt().coerceIn(0, 255)
                val newB = (b * gradientFactor).toInt().coerceIn(0, 255)

                pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }
    }

    fun applySkyRecovery(
        pixels: IntArray, w: Int, h: Int,
        skyMask: BooleanArray,
        shadowBoost: Float = 0.15f
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (skyMask[i]) continue

                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f

                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (lum > 0.4f) continue

                val boost = 1f + shadowBoost * (1f - lum / 0.4f)
                val newR = (r * boost).coerceAtMost(1f)
                val newG = (g * boost).coerceAtMost(1f)
                val newB = (b * boost).coerceAtMost(1f)

                pixels[i] = (0xFF shl 24) or
                    ((newR * 255).toInt().coerceIn(0, 255) shl 16) or
                    ((newG * 255).toInt().coerceIn(0, 255) shl 8) or
                    (newB * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun refineMask(mask: BooleanArray, w: Int, h: Int) {
        var foundNonSky = false
        for (x in 0 until w) {
            foundNonSky = false
            for (y in 0 until h) {
                val i = y * w + x
                if (foundNonSky) {
                    mask[i] = false
                } else if (!mask[i]) {
                    foundNonSky = true
                }
            }
        }
    }
}
