package com.spectra.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

object ImageEnhancer {

    data class EnhanceParams(
        val guidedFilterRadius: Int = 16,
        val guidedFilterEps: Float = 0.04f,
        val baseCompression: Float = 1.35f,
        val detailBoost: Float = 1.15f,
        val vibranceAmount: Float = 0.15f,
        val saturationScale: Float = 0.6f,
        val contrastCurveStrength: Float = 0.25f,
        val iso: Int = 100,
        val warmthShift: Float = 0f,
        val brightnessBoost: Float = 0f,
        val shadowProtection: Float = 0.3f,
        val hslStrength: Float = 0f
    ) {
        companion object {
            fun forPreset(preset: String, iso: Int = 100, sceneContrast: Float = 0f): EnhanceParams {
                return when (preset) {
                    "AUTO" -> EnhanceParams(
                        baseCompression = if (sceneContrast > 0.25f) 1.5f else 1.35f,
                        detailBoost = 1.15f,
                        vibranceAmount = 0.15f,
                        contrastCurveStrength = 0.25f,
                        shadowProtection = 0.3f,
                        hslStrength = 0.4f,
                        iso = iso
                    )
                    "PORTRAIT", "PORT" -> EnhanceParams(
                        baseCompression = 1.15f,
                        detailBoost = 1.02f,
                        vibranceAmount = 0.08f,
                        contrastCurveStrength = 0.15f,
                        warmthShift = 0.06f,
                        shadowProtection = 0.25f,
                        hslStrength = 0.6f,
                        iso = iso
                    )
                    "NIGHT", "NGHT" -> EnhanceParams(
                        baseCompression = 1.5f,
                        detailBoost = 1.08f,
                        vibranceAmount = 0.12f,
                        contrastCurveStrength = 0.20f,
                        warmthShift = -0.04f,
                        shadowProtection = 0.5f,
                        hslStrength = 0.3f,
                        iso = iso
                    )
                    "FOOD" -> EnhanceParams(
                        baseCompression = 1.35f,
                        detailBoost = 1.20f,
                        vibranceAmount = 0.17f,
                        contrastCurveStrength = 0.25f,
                        warmthShift = 0.08f,
                        brightnessBoost = 0.12f,
                        shadowProtection = 0.2f,
                        hslStrength = 0.5f,
                        iso = iso
                    )
                    "LANDSCAPE", "LNDS" -> EnhanceParams(
                        baseCompression = 1.45f,
                        detailBoost = 1.15f,
                        vibranceAmount = 0.18f,
                        contrastCurveStrength = 0.30f,
                        warmthShift = -0.03f,
                        shadowProtection = 0.4f,
                        hslStrength = 0.5f,
                        iso = iso
                    )
                    "ACTION", "ACTN" -> EnhanceParams(
                        baseCompression = 1.2f,
                        detailBoost = 1.08f,
                        vibranceAmount = 0.08f,
                        contrastCurveStrength = 0.20f,
                        shadowProtection = 0.2f,
                        hslStrength = 0.3f,
                        iso = iso
                    )
                    "MACRO", "MCRO" -> EnhanceParams(
                        baseCompression = 1.25f,
                        detailBoost = 1.25f,
                        vibranceAmount = 0.10f,
                        contrastCurveStrength = 0.20f,
                        shadowProtection = 0.2f,
                        hslStrength = 0.4f,
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

        if (strength > 0f && params.contrastCurveStrength > 0f) {
            applyLuminanceSCurve(pixels, params.contrastCurveStrength * strength)
        }

        if (strength > 0.3f && params.vibranceAmount > 0f) {
            applyHueAwareVibrance(pixels, params.vibranceAmount * strength)
        }

        if (strength > 0f && params.hslStrength > 0f) {
            applySonyCinetoneHsl(pixels, params.hslStrength * strength)
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun isoAdaptiveStrength(iso: Int): Float {
        if (iso <= 100) return 1.0f
        if (iso >= 6400) return 0f
        val logIso = kotlin.math.ln(iso.toFloat())
        val logMin = kotlin.math.ln(100f)
        val logMax = kotlin.math.ln(6400f)
        return (1f - (logIso - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
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

    private fun applyLuminanceSCurve(pixels: IntArray, strength: Float) {
        val gain = 5.0f * strength / 0.25f
        val lut = IntArray(256) { i ->
            val t = i / 255.0f
            if (gain < 0.1f) {
                i
            } else {
                val s = (1.0f / (1.0f + Math.exp((-gain * (t - 0.5f)).toDouble()))).toFloat()
                val s0 = (1.0f / (1.0f + Math.exp((gain * 0.5f).toDouble()))).toFloat()
                val s1 = (1.0f / (1.0f + Math.exp((-gain * 0.5f).toDouble()))).toFloat()
                val sigmoid = (s - s0) / (s1 - s0)
                val blended = t + (sigmoid - t)
                (blended * 255f).toInt().coerceIn(0, 255)
            }
        }
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            val r = ((pixels[i] shr 16) and 0xFF).toFloat()
            val g = ((pixels[i] shr 8) and 0xFF).toFloat()
            val b = (pixels[i] and 0xFF).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val newLuma = lut[luma.toInt().coerceIn(0, 255)].toFloat()
            if (luma < 1f) continue
            val ratio = newLuma / luma
            pixels[i] = (a shl 24) or
                ((r * ratio).coerceIn(0f, 255f).toInt() shl 16) or
                ((g * ratio).coerceIn(0f, 255f).toInt() shl 8) or
                (b * ratio).coerceIn(0f, 255f).toInt()
        }
    }

    private fun hueFromRgb(r: Float, g: Float, b: Float): Float {
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta < 1f) return 0f
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    private fun applySonyCinetoneHsl(pixels: IntArray, strength: Float) {
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            var r = ((pixels[i] shr 16) and 0xFF).toFloat()
            var g = ((pixels[i] shr 8) and 0xFF).toFloat()
            var b = (pixels[i] and 0xFF).toFloat()

            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC
            if (delta < 2f) {
                continue
            }

            val hue = hueFromRgb(r, g, b)
            val sat = delta / maxC
            val luma = 0.299f * r + 0.587f * g + 0.114f * b

            var hueShift = 0f
            var satScale = 1f
            var lumShift = 0f

            when {
                hue < 15f || hue >= 345f -> {
                    hueShift = 8f * strength
                    satScale = 1f - 0.10f * strength
                }
                hue in 15f..40f -> {
                    hueShift = -4f * strength
                    satScale = 1f - 0.08f * strength
                    lumShift = 12f * strength
                }
                hue in 40f..70f -> {
                    hueShift = -12f * strength
                    satScale = 1f - 0.20f * strength
                    lumShift = 5f * strength
                }
                hue in 70f..170f -> {
                    hueShift = 15f * strength
                    satScale = 1f - 0.15f * strength
                    lumShift = 2f * strength
                }
                hue in 170f..260f -> {
                    hueShift = -18f * strength
                    satScale = 1f + 0.08f * strength
                    lumShift = -8f * strength
                }
                hue in 260f..345f -> {
                    satScale = 1f - 0.25f * strength
                }
            }

            val newLuma = (luma + lumShift).coerceIn(0f, 255f)
            val lumaRatio = if (luma > 1f) newLuma / luma else 1f
            r *= lumaRatio; g *= lumaRatio; b *= lumaRatio

            if (satScale != 1f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * satScale
                g = l + (g - l) * satScale
                b = l + (b - l) * satScale
            }

            if (hueShift != 0f) {
                val cosH = Math.cos(Math.toRadians(hueShift.toDouble())).toFloat()
                val sinH = Math.sin(Math.toRadians(hueShift.toDouble())).toFloat()
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val cr = r - l; val cg = g - l; val cb = b - l
                val nr = cr * cosH + (0.701f * (cg * 0.587f / 0.299f - cb * 0.114f / 0.299f)) * sinH * 0.15f
                val ng = cg * cosH - (cr + cb) * sinH * 0.08f
                val nb = cb * cosH + (0.886f * (cr * 0.299f / 0.114f - cg * 0.587f / 0.114f)) * sinH * 0.15f
                r = l + nr; g = l + ng; b = l + nb
            }

            pixels[i] = (a shl 24) or
                (r.coerceIn(0f, 255f).toInt() shl 16) or
                (g.coerceIn(0f, 255f).toInt() shl 8) or
                b.coerceIn(0f, 255f).toInt()
        }
    }

    private fun applyHueAwareVibrance(pixels: IntArray, amount: Float) {
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            var r = ((pixels[i] shr 16) and 0xFF).toFloat()
            var g = ((pixels[i] shr 8) and 0xFF).toFloat()
            var b = (pixels[i] and 0xFF).toFloat()
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val sat = if (maxC > 0f) (maxC - minC) / maxC else 0f
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val lumNorm = luma / 255f

            val hue = hueFromRgb(r, g, b)
            val skinFactor = if (hue in 10f..50f) {
                val dist = abs(hue - 30f) / 20f
                0.3f + 0.7f * dist
            } else 1.0f

            val hueMul = when {
                hue < 15f || hue >= 345f -> 0.85f
                hue in 15f..50f -> 0.70f
                hue in 50f..70f -> 0.90f
                hue in 70f..170f -> 0.88f
                hue in 170f..200f -> 1.00f
                hue in 200f..260f -> 1.05f
                else -> 0.95f
            }

            val lumScale = when {
                lumNorm < 0.15f -> lumNorm / 0.15f
                lumNorm > 0.85f -> (1f - lumNorm) / 0.15f
                else -> 1.0f
            }

            val boost = amount * (1.0f - sat) * skinFactor * hueMul * lumScale
            val factor = 1.0f + boost
            r = (luma + (r - luma) * factor).coerceIn(0f, 255f)
            g = (luma + (g - luma) * factor).coerceIn(0f, 255f)
            b = (luma + (b - luma) * factor).coerceIn(0f, 255f)
            pixels[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
