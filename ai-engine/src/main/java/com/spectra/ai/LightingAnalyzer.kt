package com.spectra.ai

import android.graphics.RectF
import com.spectra.ai.model.LightingCondition
import javax.inject.Inject
import javax.inject.Singleton

data class LightDirection(
    val angleDegrees: Float,
    val strength: Float
)

data class MixedLightingResult(
    val isMixed: Boolean = false,
    val ctVariance: Float = 0f,
    val regionCts: List<Int> = emptyList(),
    val subjectCt: Int = 5500
)

@Singleton
class LightingAnalyzer @Inject constructor() {

    var lastEstimatedDuv: Float = 0f
        private set

    var lastHighlightProtection: Float = 0f
        private set

    private var lastCondition: LightingCondition = LightingCondition.UNKNOWN
    private var pendingCondition: LightingCondition? = null
    private var pendingCount: Int = 0

    fun analyzeFromMetadata(
        avgBrightness: Float,
        exposureTimeNs: Long,
        iso: Int,
        colorTemperature: Int,
        brightnessVariance: Float = 0f
    ): LightingCondition {
        val exposureMs = exposureTimeNs / 1_000_000f

        val raw = when {
            avgBrightness < 30f -> LightingCondition.LOW_LIGHT
            avgBrightness < 60f && colorTemperature in 3800..4500 -> LightingCondition.BLUE_HOUR
            brightnessVariance > 4000f && avgBrightness > 80f -> LightingCondition.BACKLIT
            avgBrightness in 80f..180f && colorTemperature < 4000 -> LightingCondition.GOLDEN_HOUR
            avgBrightness > 200f && colorTemperature in 5000..7000 -> LightingCondition.HARSH_MIDDAY
            avgBrightness > 120f && colorTemperature in 5000..6500 -> LightingCondition.BRIGHT_DAYLIGHT
            avgBrightness in 60f..150f && colorTemperature > 6500 -> LightingCondition.OVERCAST
            iso > 800 && exposureMs > 30 -> LightingCondition.LOW_LIGHT
            colorTemperature < 3500 && avgBrightness in 50f..150f -> LightingCondition.ARTIFICIAL
            colorTemperature in 4800..5500 && avgBrightness in 100f..200f && iso < 200 -> LightingCondition.STUDIO
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

    var lastBrightnessVariance: Float = 0f
        private set

    fun analyzeBrightness(pixels: IntArray, width: Int, height: Int): Float {
        if (pixels.isEmpty()) { lastBrightnessVariance = 0f; return 0f }
        var totalBrightness = 0L
        val step = maxOf(1, pixels.size / 10000)
        var count = 0
        val samples = mutableListOf<Float>()
        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
            totalBrightness += lum.toLong()
            samples.add(lum)
            count++
        }
        val avg = if (count > 0) totalBrightness.toFloat() / count else 0f
        var varianceSum = 0f
        for (s in samples) { val d = s - avg; varianceSum += d * d }
        lastBrightnessVariance = if (count > 1) varianceSum / count else 0f
        return avg
    }

    fun detectMixedLighting(
        pixels: IntArray,
        width: Int,
        height: Int,
        subjectRect: RectF? = null
    ): MixedLightingResult {
        if (pixels.isEmpty() || width == 0 || height == 0) {
            return MixedLightingResult()
        }

        val gridSize = 4
        val regionCts = mutableListOf<Int>()
        val cellW = width / gridSize
        val cellH = height / gridSize

        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val startX = gx * cellW
                val startY = gy * cellH
                val endX = minOf(startX + cellW, width)
                val endY = minOf(startY + cellH, height)

                var totalR = 0L
                var totalG = 0L
                var totalB = 0L
                var count = 0
                val step = maxOf(1, (endX - startX) * (endY - startY) / 500)
                var idx = 0
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        idx++
                        if (idx % step != 0) continue
                        val pixel = pixels[y * width + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        if (lum < 30 || lum > 240) continue
                        totalR += r
                        totalG += g
                        totalB += b
                        count++
                    }
                }

                val ct = if (count > 0) {
                    val avgR = totalR.toFloat() / count
                    val avgG = totalG.toFloat() / count
                    val avgB = totalB.toFloat() / count
                    estimateCtAndDuvFromRgb(avgR / 255f, avgG / 255f, avgB / 255f).first
                } else {
                    5500
                }
                regionCts.add(ct)
            }
        }

        val mean = regionCts.average().toFloat()
        var varianceSum = 0f
        for (ct in regionCts) {
            val diff = ct - mean
            varianceSum += diff * diff
        }
        val variance = if (regionCts.size > 1) {
            kotlin.math.sqrt(varianceSum / regionCts.size)
        } else 0f

        val isMixed = variance > 1500f

        val subjectCt = if (subjectRect != null) {
            val sx = ((subjectRect.left + subjectRect.right) / 2f * gridSize).toInt().coerceIn(0, gridSize - 1)
            val sy = ((subjectRect.top + subjectRect.bottom) / 2f * gridSize).toInt().coerceIn(0, gridSize - 1)
            regionCts[sy * gridSize + sx]
        } else {
            regionCts[gridSize / 2 * gridSize + gridSize / 2]
        }

        return MixedLightingResult(
            isMixed = isMixed,
            ctVariance = variance,
            regionCts = regionCts,
            subjectCt = subjectCt
        )
    }

    fun applyGraduatedWbCorrection(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetCt: Int = 0 // 0 = auto (subject-biased average)
    ): IntArray {
        if (pixels.isEmpty() || width == 0 || height == 0) return pixels

        val gridSize = 8
        val cellW = width / gridSize
        val cellH = height / gridSize
        if (cellW == 0 || cellH == 0) return pixels

        // Estimate CT per zone
        val zoneCts = Array(gridSize) { IntArray(gridSize) }
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val startX = gx * cellW
                val startY = gy * cellH
                val endX = minOf(startX + cellW, width)
                val endY = minOf(startY + cellH, height)

                var totalR = 0L; var totalG = 0L; var totalB = 0L; var count = 0
                val step = maxOf(1, (endX - startX) * (endY - startY) / 500)
                var idx = 0
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        idx++
                        if (idx % step != 0) continue
                        val pixel = pixels[y * width + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        if (lum < 30 || lum > 240) continue
                        totalR += r; totalG += g; totalB += b; count++
                    }
                }
                zoneCts[gy][gx] = if (count > 0) {
                    estimateCtAndDuvFromRgb(totalR.toFloat() / (count * 255f), totalG.toFloat() / (count * 255f), totalB.toFloat() / (count * 255f)).first
                } else 5500
            }
        }

        // Determine target CT
        val actualTarget = if (targetCt > 0) targetCt else {
            // Subject-biased: weight center zones more heavily
            var weightedSum = 0.0; var weightSum = 0.0
            for (gy in 0 until gridSize) {
                for (gx in 0 until gridSize) {
                    val dx = (gx - gridSize / 2f + 0.5f) / (gridSize / 2f)
                    val dy = (gy - gridSize / 2f + 0.5f) / (gridSize / 2f)
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    val weight = (1.0 - dist * 0.5).coerceAtLeast(0.2)
                    weightedSum += zoneCts[gy][gx] * weight
                    weightSum += weight
                }
            }
            (weightedSum / weightSum).toInt()
        }

        // Compute per-zone R/B gain to shift from zone CT to target CT
        // WB correction: warmer zones (low CT) need less red, more blue; cooler zones (high CT) need more red, less blue
        val rGains = Array(gridSize) { FloatArray(gridSize) }
        val bGains = Array(gridSize) { FloatArray(gridSize) }
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val zoneCt = zoneCts[gy][gx]
                val ctRatio = actualTarget.toFloat() / zoneCt.coerceAtLeast(1)
                // Approximate: higher CT = bluer, lower CT = redder
                // To correct toward target: if zone is warmer (lower CT), reduce red boost
                val correction = kotlin.math.ln(ctRatio.toDouble()).toFloat() * 0.15f
                rGains[gy][gx] = (1f - correction).coerceIn(0.8f, 1.2f)
                bGains[gy][gx] = (1f + correction).coerceIn(0.8f, 1.2f)
            }
        }

        // Apply with bilinear interpolation between zone centers
        val result = pixels.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Map pixel to zone grid (continuous coordinates)
                val gxf = (x.toFloat() / cellW - 0.5f).coerceIn(0f, gridSize - 1f)
                val gyf = (y.toFloat() / cellH - 0.5f).coerceIn(0f, gridSize - 1f)

                val gx0 = gxf.toInt().coerceIn(0, gridSize - 2)
                val gy0 = gyf.toInt().coerceIn(0, gridSize - 2)
                val gx1 = gx0 + 1
                val gy1 = gy0 + 1
                val tx = gxf - gx0
                val ty = gyf - gy0

                // Bilinear interpolation of gains
                val rGain = (1 - tx) * (1 - ty) * rGains[gy0][gx0] +
                            tx * (1 - ty) * rGains[gy0][gx1] +
                            (1 - tx) * ty * rGains[gy1][gx0] +
                            tx * ty * rGains[gy1][gx1]
                val bGain = (1 - tx) * (1 - ty) * bGains[gy0][gx0] +
                            tx * (1 - ty) * bGains[gy0][gx1] +
                            (1 - tx) * ty * bGains[gy1][gx0] +
                            tx * ty * bGains[gy1][gx1]

                val pixel = pixels[y * width + x]
                val a = (pixel shr 24) and 0xFF
                val r = ((((pixel shr 16) and 0xFF) * rGain).toInt()).coerceIn(0, 255)
                val g = (pixel shr 8) and 0xFF
                val b = (((pixel and 0xFF) * bGain).toInt()).coerceIn(0, 255)
                result[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return result
    }

    internal fun estimateCtFromRbRatio(avgR: Float, avgB: Float): Int {
        return estimateCtAndDuvFromRgb(avgR / 255f, 0.5f, avgB / 255f).first
    }

    fun computeLightDirection(pixels: IntArray, width: Int, height: Int): LightDirection {
        if (pixels.isEmpty() || width == 0 || height == 0) return LightDirection(0f, 0f)

        val gridSize = 4
        val cellW = width / gridSize
        val cellH = height / gridSize
        if (cellW == 0 || cellH == 0) return LightDirection(0f, 0f)

        val zoneBrightness = Array(gridSize) { FloatArray(gridSize) }
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val startX = gx * cellW
                val startY = gy * cellH
                val endX = minOf(startX + cellW, width)
                val endY = minOf(startY + cellH, height)
                var total = 0.0
                var count = 0
                val step = maxOf(1, (endX - startX) * (endY - startY) / 400)
                var idx = 0
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        idx++
                        if (idx % step != 0) continue
                        val pixel = pixels[y * width + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        total += 0.299 * r + 0.587 * g + 0.114 * b
                        count++
                    }
                }
                zoneBrightness[gy][gx] = if (count > 0) (total / count).toFloat() else 0f
            }
        }

        var gx = 0f
        var gy = 0f
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize - 1) {
                gx += zoneBrightness[row][col + 1] - zoneBrightness[row][col]
            }
        }
        for (col in 0 until gridSize) {
            for (row in 0 until gridSize - 1) {
                gy += zoneBrightness[row][col] - zoneBrightness[row + 1][col]
            }
        }
        gx /= (gridSize * (gridSize - 1)).toFloat()
        gy /= (gridSize * (gridSize - 1)).toFloat()

        val magnitude = kotlin.math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
        val maxPossibleGradient = 255f
        val strength = (magnitude / maxPossibleGradient).coerceIn(0f, 1f)

        val angleDegrees = if (magnitude > 0f) {
            val rawAngle = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble())).toFloat()
            (rawAngle + 360f) % 360f
        } else {
            0f
        }

        return LightDirection(angleDegrees, strength)
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
        val result = estimateCtAndDuvFromRgb(avgR / 255f, avgG / 255f, avgB / 255f)
        lastEstimatedDuv = result.second
        return result.first
    }

    fun estimateCtAndDuvFromRgb(r: Float, g: Float, b: Float): Pair<Int, Float> {
        val rL = if (r <= 0.04045f) r / 12.92f else Math.pow(((r + 0.055) / 1.055), 2.4).toFloat()
        val gL = if (g <= 0.04045f) g / 12.92f else Math.pow(((g + 0.055) / 1.055), 2.4).toFloat()
        val bL = if (b <= 0.04045f) b / 12.92f else Math.pow(((b + 0.055) / 1.055), 2.4).toFloat()

        val xVal = rL * 0.4124f + gL * 0.3576f + bL * 0.1805f
        val yVal = rL * 0.2126f + gL * 0.7152f + bL * 0.0722f
        val zVal = rL * 0.0193f + gL * 0.1192f + bL * 0.9505f

        val sum = xVal + yVal + zVal
        if (sum <= 0f) return Pair(5500, 0f)

        val cx = xVal / sum
        val cy = yVal / sum

        val n = (cx - 0.3320f) / (0.1858f - cy)
        val cct = 449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f
        val kelvin = cct.toInt().coerceIn(1667, 25000)

        val denom = -2f * cx + 12f * cy + 3f
        if (Math.abs(denom) < 0.0001f) return Pair(kelvin, 0f)
        val u = 4f * cx / denom
        val v = 6f * cy / denom

        val t = 1000.0f / kelvin
        val xp = if (kelvin <= 4000) {
            -0.2661239f * t * t * t - 0.2343580f * t * t + 0.8776956f * t + 0.179910f
        } else {
            -3.0258469f * t * t * t + 2.1070379f * t * t + 0.2226347f * t + 0.240390f
        }

        val yp = if (kelvin <= 6000) {
            -3.000f * xp * xp + 2.870f * xp - 0.275f
        } else {
            -1.4185f * xp * xp * xp - 1.359f * xp * xp + 1.185f * xp - 0.202f
        }

        val denomP = -2f * xp + 12f * yp + 3f
        if (Math.abs(denomP) < 0.0001f) return Pair(kelvin, 0f)
        val up = 4f * xp / denomP
        val vp = 6f * yp / denomP

        val dist = kotlin.math.sqrt(((u - up) * (u - up) + (v - vp) * (v - vp)).toDouble()).toFloat()
        val duv = if (v > vp) dist else -dist

        return Pair(kelvin, duv)
    }

    fun computeDynamicHighlightProtection(pixels: IntArray): Float {
        if (pixels.isEmpty()) { lastHighlightProtection = 0f; return 0f }
        var highlightsCount = 0
        val step = maxOf(1, pixels.size / 5000)
        var count = 0
        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (lum > 240) {
                highlightsCount++
            }
            count++
        }
        if (count == 0) { lastHighlightProtection = 0f; return 0f }
        val ratio = highlightsCount.toFloat() / count
        val evOffset = -(ratio * 5.0f).coerceIn(0f, 1.5f)
        lastHighlightProtection = evOffset
        return evOffset
    }
}
