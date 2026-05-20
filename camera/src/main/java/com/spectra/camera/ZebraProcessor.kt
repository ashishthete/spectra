package com.spectra.camera

import android.graphics.Bitmap

/**
 * Computes a zebra-stripe highlight warning overlay.
 *
 * Pixels whose luminance exceeds the threshold are marked with a 45-degree
 * diagonal stripe pattern (alternating semi-transparent red / lighter red,
 * stripe width ~4 px at processing resolution). All other pixels are
 * transparent.
 *
 * The input bitmap is downscaled to 1/4 resolution for performance.
 * The returned [Result] contains the ARGB pixel array and the dimensions
 * of the downscaled image so the caller can render it as an overlay.
 */
class ZebraProcessor(
    private val threshold: Int = 235,
    private val stripeWidth: Int = 4
) {

    data class Result(
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    /**
     * Process the given [bitmap] and return zebra overlay pixels.
     * Pixels with luminance > [threshold] get a diagonal stripe pattern;
     * all others are fully transparent.
     */
    fun process(bitmap: Bitmap): Result {
        val scale = 4
        val w = (bitmap.width / scale).coerceAtLeast(1)
        val h = (bitmap.height / scale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val src = IntArray(w * h)
        small.getPixels(src, 0, w, 0, 0, w, h)
        small.recycle()

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val pixel = src[i]
                val lum = luminance(pixel)
                if (lum > threshold) {
                    // 45-degree diagonal stripe: (x + y) / stripeWidth toggles
                    val stripe = ((x + y) / stripeWidth) % 2 == 0
                    out[i] = if (stripe) STRIPE_HI else STRIPE_LO
                }
                // else out[i] stays 0 (transparent)
            }
        }
        return Result(out, w, h)
    }

    /**
     * Lightweight variant that operates on pre-extracted pixel array
     * (already downscaled). Avoids a second downscale when the caller
     * has already done it for other overlays (focus peaking, etc.).
     */
    fun processPixels(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val lum = luminance(pixels[i])
                if (lum > threshold) {
                    val stripe = ((x + y) / stripeWidth) % 2 == 0
                    out[i] = if (stripe) STRIPE_HI else STRIPE_LO
                }
            }
        }
        return out
    }

    companion object {
        /** Semi-transparent red for the "on" stripe band. */
        private const val STRIPE_HI = 0x80FF0000.toInt() // alpha 128, red
        /** Lighter red for the "off" stripe band (still visible). */
        private const val STRIPE_LO = 0x40FF0000.toInt() // alpha 64, red

        private fun luminance(pixel: Int): Int {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // BT.601 fast integer approximation
            return (r * 77 + g * 150 + b * 29) shr 8
        }
    }
}
