package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Local tone mapper for post-HDR processing.
 *
 * Decomposes the image into a low-frequency base (blurred luminance) and a
 * high-frequency detail layer, remaps the base through a filmic curve with
 * configurable gamma / shadow lift, then recombines. The result lifts shadows
 * and controls highlights *locally*, producing a natural-looking HDR result
 * that a global tone curve alone cannot achieve.
 *
 * Usage:
 *   LocalToneMapper.apply(bitmap, strength = 0.7f, gamma = 0.85f, shadowLift = 0.1f)
 */
object LocalToneMapper {

    private const val TAG = "LocalToneMapper"

    /**
     * Apply local tone mapping in-place to [bitmap].
     *
     * @param strength  0 = no effect, 1 = full effect
     * @param gamma     gamma applied to the base layer (< 1 lifts shadows)
     * @param shadowLift additive lift for the darkest tones (0-1, applied before gamma)
     */
    fun apply(
        bitmap: Bitmap,
        strength: Float = 0.7f,
        gamma: Float = 0.85f,
        shadowLift: Float = 0.1f
    ) {
        if (strength <= 0f) return
        val effectiveStrength = strength.coerceIn(0f, 1f)

        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        if (n == 0) return

        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val result = applyToPixels(pixels, w, h, effectiveStrength, gamma, shadowLift)

        bitmap.setPixels(result, 0, w, 0, 0, w, h)
        Log.d(TAG, "Local tone map applied: ${w}x${h}, strength=$effectiveStrength, gamma=$gamma, shadowLift=$shadowLift")
    }

    /**
     * Pure-function variant: takes ARGB pixel array, returns tone-mapped ARGB pixel array.
     */
    fun applyToPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        strength: Float = 0.7f,
        gamma: Float = 0.85f,
        shadowLift: Float = 0.1f
    ): IntArray {
        val n = w * h
        if (n == 0 || strength <= 0f) return pixels.copyOf()

        // ---- 1. Extract luminance ----
        val lum = FloatArray(n)
        for (i in 0 until n) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            // Rec. 601 luminance, in 0..255 range
            lum[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        // ---- 2. Large-radius box blur to get the low-frequency base ----
        val radius = max(w / 20, 4) // ~5% of image width
        val blurred = boxBlur(lum, w, h, radius)

        // ---- 3. Detail layer: original / blurred ----
        val epsilon = 1f // avoids division by zero, keeps detail ratio stable in dark areas
        val detail = FloatArray(n)
        for (i in 0 until n) {
            detail[i] = lum[i] / (blurred[i] + epsilon)
        }

        // ---- 4. Remap blurred base through filmic curve with gamma + shadow lift ----
        val remappedBase = FloatArray(n)
        for (i in 0 until n) {
            // Normalise to 0..1
            var v = blurred[i] / 255f

            // Shadow lift: raise the floor
            v = v + shadowLift * (1f - v)

            // Filmic: x / (x + 1) mapped so that 1 -> 0.5; we scale to restore range
            // We use the normalised value scaled by 2 so filmic(1.0) ~ 0.667 which is reasonable
            val filmic = (v * 2f) / (v * 2f + 1f) // range 0..0.667 for input 0..1

            // Gamma for additional shadow lift
            val curved = filmic.coerceIn(0f, 1f).pow(gamma)

            // Scale back to 0..255 (filmic saturates at ~0.667 for input=1, so rescale)
            // Max filmic output for v=1 is 2/(2+1)=0.667, after gamma still ~0.667^0.85 ≈ 0.714
            // We want input=1 to map to 255, so divide by the filmic(1) value
            val filmicAtOne = (2f / 3f).pow(gamma) // filmic(1.0)^gamma
            remappedBase[i] = (curved / filmicAtOne * 255f).coerceIn(0f, 255f)
        }

        // ---- 5. Reconstruct luminance: remapped_base * detail ----
        val newLum = FloatArray(n)
        for (i in 0 until n) {
            newLum[i] = (remappedBase[i] * detail[i]).coerceIn(0f, 255f)
        }

        // ---- 6. Apply luminance change to RGB while preserving colour ratios ----
        val output = IntArray(n)
        for (i in 0 until n) {
            val origLum = lum[i].coerceAtLeast(1f)
            // Blend between original and tone-mapped based on strength
            val targetLum = lum[i] + (newLum[i] - lum[i]) * strength
            val scale = targetLum / origLum

            val r = ((pixels[i] shr 16 and 0xFF) * scale).toInt().coerceIn(0, 255)
            val g = ((pixels[i] shr 8 and 0xFF) * scale).toInt().coerceIn(0, 255)
            val b = ((pixels[i] and 0xFF) * scale).toInt().coerceIn(0, 255)
            val a = pixels[i] and (0xFF shl 24)
            output[i] = a or (r shl 16) or (g shl 8) or b
        }

        return output
    }

    // ---- Separable box blur (horizontal then vertical) using integral sums ----

    private fun boxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val temp = FloatArray(input.size)
        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            // Running sum for this row
            var sum = 0f
            var count = 0
            // Initialise window: [0, radius]
            for (x in 0..min(radius, w - 1)) {
                sum += input[rowOffset + x]
                count++
            }
            for (x in 0 until w) {
                temp[rowOffset + x] = sum / count
                // Expand right edge
                val addX = x + radius + 1
                if (addX < w) {
                    sum += input[rowOffset + addX]
                    count++
                }
                // Shrink left edge
                val removeX = x - radius
                if (removeX >= 0) {
                    sum -= input[rowOffset + removeX]
                    count--
                }
            }
        }

        val output = FloatArray(input.size)
        // Vertical pass
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (y in 0..min(radius, h - 1)) {
                sum += temp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                output[y * w + x] = sum / count
                val addY = y + radius + 1
                if (addY < h) {
                    sum += temp[addY * w + x]
                    count++
                }
                val removeY = y - radius
                if (removeY >= 0) {
                    sum -= temp[removeY * w + x]
                    count--
                }
            }
        }

        return output
    }
}
