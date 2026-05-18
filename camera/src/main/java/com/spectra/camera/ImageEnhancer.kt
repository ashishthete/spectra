package com.spectra.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

object ImageEnhancer {

    data class EnhanceParams(
        val guidedFilterRadius: Int = 8,
        val guidedFilterEps: Float = 0.04f,
        val baseCompression: Float = 1.3f,
        val detailBoost: Float = 1.1f,
        val vibranceAmount: Float = 0.08f,
        val saturationScale: Float = 0.6f,
        val iso: Int = 100,
        val warmthShift: Float = 0f,
        val brightnessBoost: Float = 0f,
        val shadowProtection: Float = 0.3f
    ) {
        companion object {
            fun forPreset(preset: String, iso: Int = 100, sceneContrast: Float = 0f): EnhanceParams {
                return when (preset) {
                    "AUTO" -> EnhanceParams(
                        baseCompression = if (sceneContrast > 0.25f) 1.5f else 1.3f,
                        detailBoost = 1.1f,
                        vibranceAmount = 0.10f,
                        shadowProtection = 0.3f,
                        iso = iso
                    )
                    "PORTRAIT", "PORT" -> EnhanceParams(
                        baseCompression = 1.2f,
                        detailBoost = 1.05f,
                        vibranceAmount = 0.06f,
                        warmthShift = 0.06f,
                        shadowProtection = 0.25f,
                        iso = iso
                    )
                    "NIGHT", "NGHT" -> EnhanceParams(
                        baseCompression = 1.6f,
                        detailBoost = 1.05f,
                        vibranceAmount = 0.18f,
                        warmthShift = -0.04f,
                        shadowProtection = 0.5f,
                        iso = iso
                    )
                    "FOOD" -> EnhanceParams(
                        baseCompression = 1.4f,
                        detailBoost = 1.2f,
                        vibranceAmount = 0.15f,
                        warmthShift = 0.08f,
                        brightnessBoost = 0.12f,
                        shadowProtection = 0.2f,
                        iso = iso
                    )
                    "LANDSCAPE", "LNDS" -> EnhanceParams(
                        baseCompression = 1.6f,
                        detailBoost = 1.15f,
                        vibranceAmount = 0.14f,
                        warmthShift = -0.03f,
                        shadowProtection = 0.4f,
                        iso = iso
                    )
                    "ACTION", "ACTN" -> EnhanceParams(
                        baseCompression = 1.2f,
                        detailBoost = 1.08f,
                        vibranceAmount = 0.06f,
                        shadowProtection = 0.2f,
                        iso = iso
                    )
                    "MACRO", "MCRO" -> EnhanceParams(
                        baseCompression = 1.25f,
                        detailBoost = 1.3f,
                        vibranceAmount = 0.07f,
                        shadowProtection = 0.2f,
                        iso = iso
                    )
                    else -> EnhanceParams(iso = iso)
                }
            }
        }
    }

    fun scoreQuality(bitmap: Bitmap): Float {
        val scale = 8
        val w = bitmap.width / scale
        val h = bitmap.height / scale
        val thumb = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        thumb.getPixels(pixels, 0, w, 0, 0, w, h)
        thumb.recycle()

        var lumSum = 0f
        var lumSqSum = 0f
        var satSum = 0f
        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF).toFloat()
            val g = ((pixels[i] shr 8) and 0xFF).toFloat()
            val b = (pixels[i] and 0xFF).toFloat()
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            lum[i] = l
            lumSum += l
            lumSqSum += l * l
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx > 0f) satSum += (mx - mn) / mx
        }
        val n = pixels.size.toFloat()
        val meanLum = lumSum / n
        val contrast = kotlin.math.sqrt(lumSqSum / n - meanLum * meanLum)
        val meanSat = satSum / n

        var sharpness = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val lap = lum[(y - 1) * w + x] + lum[(y + 1) * w + x] +
                    lum[y * w + x - 1] + lum[y * w + x + 1] - 4f * lum[y * w + x]
                sharpness += lap * lap
            }
        }
        sharpness /= ((w - 2) * (h - 2)).toFloat()

        val brightScore = 1f - abs(meanLum - 120f) / 120f
        val contrastScore = min(contrast / 60f, 1f)
        val satScore = min(meanSat / 0.35f, 1f)
        val sharpScore = min(sharpness / 500f, 1f)

        return brightScore * 0.25f + contrastScore * 0.25f + satScore * 0.25f + sharpScore * 0.25f
    }

    fun enhance(bitmap: Bitmap, params: EnhanceParams = EnhanceParams()): Bitmap {
        val strength = isoAdaptiveStrength(params.iso)
        if (strength <= 0f) return bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val halfW = w / 2
        val halfH = h / 2
        val halfLum = FloatArray(halfW * halfH)
        downsampleLum(pixels, w, h, halfLum, halfW, halfH)

        val dsScale = 2
        val dsW = halfW / dsScale
        val dsH = halfH / dsScale
        val dsLum = downsample(halfLum, halfW, halfH, dsScale)
        for (i in dsLum.indices) dsLum[i] /= 255f

        val dsBase = guidedFilter(
            dsLum, dsLum, dsW, dsH,
            max(2, params.guidedFilterRadius / (dsScale * 2)),
            params.guidedFilterEps
        )

        val gamma = 1f + (params.baseCompression - 1f) * strength
        val dBoost = 1f + (params.detailBoost - 1f) * strength
        val noiseFloor = isoAdaptiveNoiseFloor(params.iso)
        val shadowProt = params.shadowProtection * strength

        val halfRatio = computeRatioMap(
            halfLum, halfW, halfH, dsBase, dsW, dsH, dsScale,
            gamma, dBoost, noiseFloor, shadowProt
        )

        applyRatioFullRes(pixels, w, h, halfRatio, halfW, halfH)

        if (strength > 0f && params.brightnessBoost > 0f) {
            applyBrightnessBoost(pixels, params.brightnessBoost * strength)
        }

        if (strength > 0f && params.warmthShift != 0f) {
            applyWarmthShift(pixels, params.warmthShift * strength)
        }

        if (strength > 0.3f && params.vibranceAmount > 0f) {
            applyVibrance(pixels, params.vibranceAmount * strength)
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun isoAdaptiveStrength(iso: Int): Float {
        return when {
            iso <= 200 -> 1.0f
            iso <= 400 -> 0.8f
            iso <= 800 -> 0.5f
            iso <= 1600 -> 0.25f
            iso <= 3200 -> 0.1f
            else -> 0f
        }
    }

    private fun isoAdaptiveNoiseFloor(iso: Int): Float {
        return (0.01f + iso / 40000f).coerceAtMost(0.06f)
    }

    private fun downsampleLum(pixels: IntArray, w: Int, h: Int, out: FloatArray, hw: Int, hh: Int) {
        for (dy in 0 until hh) {
            val y0 = dy * 2
            val y1 = min(y0 + 1, h - 1)
            for (dx in 0 until hw) {
                val x0 = dx * 2
                val x1 = min(x0 + 1, w - 1)
                var sum = 0f
                for (py in intArrayOf(y0, y1)) {
                    for (px in intArrayOf(x0, x1)) {
                        val p = pixels[py * w + px]
                        sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                    }
                }
                out[dy * hw + dx] = sum * 0.25f
            }
        }
    }

    private fun downsample(data: FloatArray, w: Int, h: Int, scale: Int): FloatArray {
        val dw = w / scale
        val dh = h / scale
        val out = FloatArray(dw * dh)
        val s2 = scale * scale
        for (dy in 0 until dh) {
            for (dx in 0 until dw) {
                var sum = 0f
                for (sy in 0 until scale) {
                    for (sx in 0 until scale) {
                        sum += data[(dy * scale + sy) * w + (dx * scale + sx)]
                    }
                }
                out[dy * dw + dx] = sum / s2
            }
        }
        return out
    }

    private fun bilinearSample(data: FloatArray, dw: Int, dh: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, dw - 2)
        val y0 = y.toInt().coerceIn(0, dh - 2)
        val fx = x - x0
        val fy = y - y0
        val v00 = data[y0 * dw + x0]
        val v10 = data[y0 * dw + x0 + 1]
        val v01 = data[(y0 + 1) * dw + x0]
        val v11 = data[(y0 + 1) * dw + x0 + 1]
        return v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
               v01 * (1 - fx) * fy + v11 * fx * fy
    }

    private fun computeRatioMap(
        halfLum: FloatArray,
        halfW: Int,
        halfH: Int,
        dsBase: FloatArray,
        dsW: Int,
        dsH: Int,
        dsScale: Int,
        gamma: Float,
        detailBoost: Float,
        noiseFloor: Float,
        shadowProtection: Float
    ): FloatArray {
        val invGamma = 1f / gamma
        val ratio = FloatArray(halfW * halfH) { 1f }

        val gammaLut = FloatArray(256) { i ->
            if (i == 0) 0f else (i / 255f).pow(invGamma)
        }

        val shadowThreshold = 0.12f
        val highlightThreshold = 0.88f

        for (i in halfLum.indices) {
            val normalized = halfLum[i] / 255f
            if (normalized < 0.004f) continue

            val row = i / halfW
            val col = i % halfW
            val dsX = (col.toFloat() / dsScale - 0.5f).coerceIn(0f, (dsW - 1.01f))
            val dsY = (row.toFloat() / dsScale - 0.5f).coerceIn(0f, (dsH - 1.01f))
            val base = bilinearSample(dsBase, dsW, dsH, dsX, dsY).coerceIn(0.001f, 1f)

            val detail = normalized - base
            val baseIdx = (base * 255f).toInt().coerceIn(0, 255)
            var compressedBase = gammaLut[baseIdx]

            if (compressedBase < shadowThreshold && shadowProtection > 0f) {
                val deficit = shadowThreshold - compressedBase
                compressedBase += deficit * shadowProtection
            }

            if (compressedBase > highlightThreshold) {
                val excess = compressedBase - highlightThreshold
                compressedBase = highlightThreshold + excess * 0.3f
            }

            val absDetail = abs(detail)
            val softGate = if (absDetail <= noiseFloor) 0f
            else ((absDetail - noiseFloor) / noiseFloor).coerceAtMost(1f)
            val boostedDetail = detail * (1f + (detailBoost - 1f) * softGate)

            val newNorm = (compressedBase + boostedDetail).coerceIn(0f, 1f)
            ratio[i] = (newNorm * 255f) / halfLum[i]
        }
        return ratio
    }

    private fun applyRatioFullRes(
        pixels: IntArray,
        w: Int,
        h: Int,
        halfRatio: FloatArray,
        halfW: Int,
        halfH: Int
    ) {
        for (y in 0 until h) {
            val hy = (y * 0.5f - 0.25f).coerceIn(0f, (halfH - 1.01f))
            val hy0 = hy.toInt()
            val hy1 = min(hy0 + 1, halfH - 1)
            val fy = hy - hy0
            val ify = 1f - fy
            for (x in 0 until w) {
                val hx = (x * 0.5f - 0.25f).coerceIn(0f, (halfW - 1.01f))
                val hx0 = hx.toInt()
                val hx1 = min(hx0 + 1, halfW - 1)
                val fx = hx - hx0
                val r = halfRatio[hy0 * halfW + hx0] * (1f - fx) * ify +
                        halfRatio[hy0 * halfW + hx1] * fx * ify +
                        halfRatio[hy1 * halfW + hx0] * (1f - fx) * fy +
                        halfRatio[hy1 * halfW + hx1] * fx * fy

                val idx = y * w + x
                val p = pixels[idx]
                val a = (p shr 24) and 0xFF
                val pr = ((p shr 16) and 0xFF) * r
                val pg = ((p shr 8) and 0xFF) * r
                val pb = (p and 0xFF) * r
                pixels[idx] = (a shl 24) or
                    (pr.coerceIn(0f, 255f).toInt() shl 16) or
                    (pg.coerceIn(0f, 255f).toInt() shl 8) or
                    pb.coerceIn(0f, 255f).toInt()
            }
        }
    }

    /**
     * O(1) guided filter via integral images.
     * q_i = mean_a * I_i + mean_b
     * where a_k = cov(I,P) / (var(I) + eps), b_k = mean_P - a_k * mean_I
     */
    private fun guidedFilter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float
    ): FloatArray {
        val n = guide.size
        val meanI = boxFilter(guide, width, height, radius)
        val meanP = boxFilter(input, width, height, radius)

        val corrII = FloatArray(n) { guide[it] * guide[it] }
        val corrIP = FloatArray(n) { guide[it] * input[it] }
        val meanII = boxFilter(corrII, width, height, radius)
        val meanIP = boxFilter(corrIP, width, height, radius)

        for (i in 0 until n) {
            val varI = meanII[i] - meanI[i] * meanI[i]
            val covIP = meanIP[i] - meanI[i] * meanP[i]
            corrII[i] = covIP / (varI + eps)
            corrIP[i] = meanP[i] - corrII[i] * meanI[i]
        }

        val meanA = boxFilter(corrII, width, height, radius)
        val meanB = boxFilter(corrIP, width, height, radius)

        val result = FloatArray(n)
        for (i in 0 until n) {
            result[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }
        return result
    }

    private fun boxFilter(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val n = data.size
        val iw = width + 1
        val ih = height + 1
        val integral = FloatArray(iw * ih)

        for (row in 0 until height) {
            for (col in 0 until width) {
                integral[(row + 1) * iw + (col + 1)] =
                    data[row * width + col] +
                    integral[row * iw + (col + 1)] +
                    integral[(row + 1) * iw + col] -
                    integral[row * iw + col]
            }
        }

        val result = FloatArray(n)
        for (row in 0 until height) {
            val r1 = max(0, row - radius)
            val r2 = min(height - 1, row + radius)
            for (col in 0 until width) {
                val c1 = max(0, col - radius)
                val c2 = min(width - 1, col + radius)
                val count = (r2 - r1 + 1) * (c2 - c1 + 1)
                val sum = integral[(r2 + 1) * iw + (c2 + 1)] -
                          integral[r1 * iw + (c2 + 1)] -
                          integral[(r2 + 1) * iw + c1] +
                          integral[r1 * iw + c1]
                result[row * width + col] = sum / count
            }
        }
        return result
    }

    private fun applySaturationCorrection(pixels: IntArray, scale: Float) {
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            val r = ((pixels[i] shr 16) and 0xFF).toFloat()
            val g = ((pixels[i] shr 8) and 0xFF).toFloat()
            val b = (pixels[i] and 0xFF).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = (luma + (r - luma) * scale).coerceIn(0f, 255f).toInt()
            val ng = (luma + (g - luma) * scale).coerceIn(0f, 255f).toInt()
            val nb = (luma + (b - luma) * scale).coerceIn(0f, 255f).toInt()
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun applyWarmthShift(pixels: IntArray, shift: Float) {
        val rGain = 1f + shift * 0.5f
        val bGain = 1f - shift * 0.5f
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            val r = (((pixels[i] shr 16) and 0xFF) * rGain).coerceIn(0f, 255f).toInt()
            val g = (pixels[i] shr 8) and 0xFF
            val b = ((pixels[i] and 0xFF) * bGain).coerceIn(0f, 255f).toInt()
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyBrightnessBoost(pixels: IntArray, boost: Float) {
        val factor = 1f + boost
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            val r = (((pixels[i] shr 16) and 0xFF) * factor).coerceIn(0f, 255f).toInt()
            val g = (((pixels[i] shr 8) and 0xFF) * factor).coerceIn(0f, 255f).toInt()
            val b = ((pixels[i] and 0xFF) * factor).coerceIn(0f, 255f).toInt()
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyVibrance(pixels: IntArray, amount: Float) {
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            var r = ((pixels[i] shr 16) and 0xFF).toFloat()
            var g = ((pixels[i] shr 8) and 0xFF).toFloat()
            var b = (pixels[i] and 0xFF).toFloat()
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val sat = if (maxC > 0f) (maxC - minC) / maxC else 0f
            val boost = amount * (1.0f - sat)
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val factor = 1.0f + boost
            r = (luma + (r - luma) * factor).coerceIn(0f, 255f)
            g = (luma + (g - luma) * factor).coerceIn(0f, 255f)
            b = (luma + (b - luma) * factor).coerceIn(0f, 255f)
            pixels[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
