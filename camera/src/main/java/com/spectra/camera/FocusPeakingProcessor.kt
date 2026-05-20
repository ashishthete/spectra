package com.spectra.camera

import android.graphics.Bitmap

/**
 * Computes a focus-peaking overlay from a camera preview frame.
 *
 * Uses a 3x3 Laplacian kernel [0,-1,0,-1,4,-1,0,-1,0] on the luminance channel
 * to detect high-frequency edges (in-focus regions). Pixels whose absolute Laplacian
 * response exceeds [threshold] are marked with [peakColor]; everything else is
 * transparent (0x00000000).
 *
 * Processing runs on a 1/4-resolution copy for performance.
 *
 * Usage:
 *   val processor = FocusPeakingProcessor()
 *   val result = processor.process(bitmap)
 *   // result.pixels is IntArray of ARGB, result.width / result.height are the overlay dims
 */
class FocusPeakingProcessor(
    private val peakColor: Int = 0xFFFF0000.toInt(),
    private val threshold: Int = 25,
    private val downscaleFactor: Int = 4
) {

    data class Result(
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    /**
     * Process a preview [bitmap] and return an ARGB overlay where in-focus edges
     * are highlighted in [peakColor] and everything else is transparent.
     */
    fun process(bitmap: Bitmap): Result {
        val w = bitmap.width / downscaleFactor
        val h = bitmap.height / downscaleFactor
        if (w < 3 || h < 3) {
            return Result(IntArray(0), 0, 0)
        }

        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val srcPixels = IntArray(w * h)
        small.getPixels(srcPixels, 0, w, 0, 0, w, h)
        small.recycle()

        // Pre-compute luminance for all pixels
        val lum = IntArray(w * h)
        for (i in srcPixels.indices) {
            lum[i] = luminance(srcPixels[i])
        }

        // Apply Laplacian kernel: [0,-1,0, -1,4,-1, 0,-1,0]
        val overlay = IntArray(w * h) // defaults to 0x00000000 (transparent)
        for (y in 1 until h - 1) {
            val rowOffset = y * w
            val rowAbove = (y - 1) * w
            val rowBelow = (y + 1) * w
            for (x in 1 until w - 1) {
                val center = lum[rowOffset + x]
                val top = lum[rowAbove + x]
                val bottom = lum[rowBelow + x]
                val left = lum[rowOffset + x - 1]
                val right = lum[rowOffset + x + 1]

                val laplacian = kotlin.math.abs(4 * center - top - bottom - left - right)

                if (laplacian > threshold) {
                    // Scale alpha by edge strength for a softer overlay
                    val alpha = (laplacian * 3).coerceAtMost(255)
                    // Combine alpha with the peak color (replace the color's alpha channel)
                    overlay[rowOffset + x] = (alpha shl 24) or (peakColor and 0x00FFFFFF)
                }
            }
        }

        return Result(overlay, w, h)
    }

    /**
     * Fast BT.601 luminance: (77*R + 150*G + 29*B) >> 8
     */
    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
