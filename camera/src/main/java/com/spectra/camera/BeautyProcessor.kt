package com.spectra.camera

import android.util.Log

/**
 * Frequency-separation beauty processor for portrait skin smoothing.
 *
 * Splits the image into low-frequency (color/tone) and high-frequency (texture/detail)
 * layers, smooths only the low-frequency layer, then recombines. This preserves pores
 * and fine skin detail while removing blemishes and uneven tones.
 *
 * Designed for captured stills at full resolution.
 */
object BeautyProcessor {

    private const val TAG = "BeautyProcessor"

    /**
     * Apply frequency-separation beauty smoothing to an ARGB pixel array.
     *
     * @param pixels ARGB_8888 pixel array (modified in-place, also returned)
     * @param w image width
     * @param h image height
     * @param skinMask optional per-pixel float mask (0.0 = no smoothing, 1.0 = full smoothing).
     *                 If null, smoothing is applied uniformly to all pixels.
     * @param strength smoothing strength in 0.0..1.0 range (default 0.5).
     *                 Controls blur radius: radius = 5 + strength * 15, yielding 5-20px.
     * @return the modified pixel array (same reference as input)
     */
    fun process(
        pixels: IntArray,
        w: Int,
        h: Int,
        skinMask: FloatArray? = null,
        strength: Float = 0.5f
    ): IntArray {
        if (w <= 0 || h <= 0 || pixels.size != w * h) return pixels
        val clampedStrength = strength.coerceIn(0f, 1f)
        if (clampedStrength <= 0f) return pixels

        val n = w * h

        // Step a: Extract luminance channel from pixels
        val originalLum = FloatArray(n)
        val rChannel = IntArray(n)
        val gChannel = IntArray(n)
        val bChannel = IntArray(n)

        for (i in 0 until n) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            rChannel[i] = r
            gChannel[i] = g
            bChannel[i] = b
            // BT.601 luminance
            originalLum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Step b: Create low-frequency layer via box blur
        val radius = (5 + clampedStrength * 15).toInt().coerceIn(5, 20)
        val lowFreq = boxBlur(originalLum, w, h, radius)

        // Step c: Compute high-frequency detail = original_lum - blurred_lum + 128
        // We store centered at 128 but keep as float for precision
        val detail = FloatArray(n) { i -> originalLum[i] - lowFreq[i] + 128f }

        // Step d: Smooth the low-frequency layer again with gentler blur (radius/2)
        val gentleRadius = (radius / 2).coerceAtLeast(2)
        val smoothedLow = boxBlur(lowFreq, w, h, gentleRadius)

        // Step e: Reconstruct: output_lum = smoothed_low + (detail - 128)
        val outputLum = FloatArray(n) { i ->
            (smoothedLow[i] + (detail[i] - 128f)).coerceIn(0f, 255f)
        }

        // Steps f & g: Blend with skin mask and apply luminance change preserving color ratios
        for (i in 0 until n) {
            val maskWeight = skinMask?.get(i) ?: 1f
            if (maskWeight <= 0.001f) continue // skip non-skin pixels entirely

            val origL = originalLum[i].coerceAtLeast(1f)
            val newL = outputLum[i]

            // Blend between original and processed based on mask
            val blendedL = origL + (newL - origL) * maskWeight

            // Apply luminance change back to RGB while preserving color ratios
            val scale = blendedL / origL
            val newR = (rChannel[i] * scale).toInt().coerceIn(0, 255)
            val newG = (gChannel[i] * scale).toInt().coerceIn(0, 255)
            val newB = (bChannel[i] * scale).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        Log.d(TAG, "Frequency-separation beauty: ${w}x${h}, strength=${"%.2f".format(clampedStrength)}, radius=$radius, mask=${skinMask != null}")
        return pixels
    }

    /**
     * Build a float skin mask (0.0-1.0) from face rectangles and CbCr skin-tone detection.
     * Pixels inside face rects that match skin color get 1.0; pixels outside face rects
     * or non-skin-colored pixels get 0.0. A feathered edge is applied at face boundaries.
     */
    fun buildSkinMask(
        pixels: IntArray,
        w: Int,
        h: Int,
        faceRects: List<android.graphics.RectF>
    ): FloatArray {
        val n = w * h
        val mask = FloatArray(n) // defaults to 0.0

        if (faceRects.isEmpty()) {
            // No face rects: use pure color-based skin detection with soft confidence
            for (i in 0 until n) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                mask[i] = skinConfidence(r, g, b)
            }
            return mask
        }

        // With face rects: combine spatial (inside face region) + color-based skin detection
        for (face in faceRects) {
            val padX = face.width() * 0.2f
            val padY = face.height() * 0.2f
            val feather = face.width() * 0.15f

            val left = (face.left - padX) * w
            val top = (face.top - padY) * h
            val right = (face.right + padX) * w
            val bottom = (face.bottom + padY) * h

            val featherPx = (feather * w).coerceAtLeast(3f)

            val y0 = (top - featherPx).toInt().coerceIn(0, h - 1)
            val y1 = (bottom + featherPx).toInt().coerceIn(0, h)
            val x0 = (left - featherPx).toInt().coerceIn(0, w - 1)
            val x1 = (right + featherPx).toInt().coerceIn(0, w)

            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    // Compute feathered spatial weight
                    val dx = when {
                        x < left -> left - x
                        x > right -> x - right
                        else -> 0f
                    }
                    val dy = when {
                        y < top -> top - y
                        y > bottom -> y - bottom
                        else -> 0f
                    }
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val spatialWeight = if (dist <= 0f) 1f
                    else (1f - (dist / featherPx)).coerceIn(0f, 1f)

                    if (spatialWeight <= 0f) continue

                    val idx = y * w + x
                    val r = (pixels[idx] shr 16) and 0xFF
                    val g = (pixels[idx] shr 8) and 0xFF
                    val b = pixels[idx] and 0xFF
                    val colorWeight = skinConfidence(r, g, b)

                    // Combine: inside face region with skin color = high confidence
                    val combined = spatialWeight * colorWeight
                    if (combined > mask[idx]) {
                        mask[idx] = combined
                    }
                }
            }
        }

        return mask
    }

    /**
     * Returns a 0.0-1.0 skin-color confidence based on CbCr chrominance values.
     * Uses the standard skin-tone detection ranges in YCbCr space with soft edges.
     */
    private fun skinConfidence(r: Int, g: Int, b: Int): Float {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cb = 128f - 37.797f * rf - 74.203f * gf + 112f * bf
        val cr = 128f + 112f * rf - 93.786f * gf - 18.214f * bf

        // Skin-tone ranges: Cb in [70, 135], Cr in [125, 180]
        // Compute soft membership with 10-unit ramp at edges
        val cbConf = softRange(cb, 70f, 135f, 10f)
        val crConf = softRange(cr, 125f, 180f, 10f)
        return cbConf * crConf
    }

    /**
     * Soft range membership: 1.0 inside [lo, hi], ramps down over `ramp` units outside.
     */
    private fun softRange(value: Float, lo: Float, hi: Float, ramp: Float): Float {
        return when {
            value < lo - ramp -> 0f
            value < lo -> (value - (lo - ramp)) / ramp
            value > hi + ramp -> 0f
            value > hi -> ((hi + ramp) - value) / ramp
            else -> 1f
        }
    }

    /**
     * Separable box blur on a float luminance array.
     * Uses a running-sum approach for O(n) per pass regardless of radius.
     */
    private fun boxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val temp = FloatArray(w * h)
        val output = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var sum = 0f
            val kernelSize = radius * 2 + 1

            // Initialize sum for first pixel
            for (k in -radius..radius) {
                val sx = k.coerceIn(0, w - 1)
                sum += input[rowOffset + sx]
            }
            temp[rowOffset] = sum / kernelSize

            for (x in 1 until w) {
                // Add new right pixel, remove old left pixel
                val addX = (x + radius).coerceAtMost(w - 1)
                val removeX = (x - radius - 1).coerceAtLeast(0)
                sum += input[rowOffset + addX] - input[rowOffset + removeX]
                temp[rowOffset + x] = sum / kernelSize
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sum = 0f
            val kernelSize = radius * 2 + 1

            // Initialize sum for first pixel
            for (k in -radius..radius) {
                val sy = k.coerceIn(0, h - 1)
                sum += temp[sy * w + x]
            }
            output[x] = sum / kernelSize

            for (y in 1 until h) {
                val addY = (y + radius).coerceAtMost(h - 1)
                val removeY = (y - radius - 1).coerceAtLeast(0)
                sum += temp[addY * w + x] - temp[removeY * w + x]
                output[y * w + x] = sum / kernelSize
            }
        }

        return output
    }
}
