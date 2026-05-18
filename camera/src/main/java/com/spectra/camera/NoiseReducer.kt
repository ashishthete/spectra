package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.roundToInt

object NoiseReducer {
    private const val TAG = "NoiseReducer"

    fun computeSigma(iso: Int): Float = 0.5f + iso / 400.0f

    fun apply(bitmap: Bitmap, iso: Int) {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val sigma = computeSigma(iso)
        val yChannel = IntArray(w * h); val cbChannel = IntArray(w * h); val crChannel = IntArray(w * h)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF; val g = (pixels[i] shr 8) and 0xFF; val b = pixels[i] and 0xFF
            val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
            yChannel[i] = ycbcr[0]; cbChannel[i] = ycbcr[1]; crChannel[i] = ycbcr[2]
        }

        val cbFiltered = bilateralFilter(cbChannel, w, h, 5, sigma * 2.0f)
        val crFiltered = bilateralFilter(crChannel, w, h, 5, sigma * 2.0f)
        val yFiltered = bilateralFilter(yChannel, w, h, 3, sigma * 0.8f)

        for (i in pixels.indices) {
            val rgb = ColorSpaceUtils.ycbcrToRgb(yFiltered[i], cbFiltered[i], crFiltered[i])
            pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d(TAG, "NR: ISO=$iso, sigma=$sigma")
    }

    internal fun bilateralFilter(channel: IntArray, w: Int, h: Int, spatialRadius: Int, rangeSigma: Float): IntArray {
        val output = IntArray(channel.size)
        val rangeSigmaSq2 = 2.0f * rangeSigma * rangeSigma
        val spatialSigma = spatialRadius / 2.0f
        val spatialSigmaSq2 = 2.0f * spatialSigma * spatialSigma

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x; val centerVal = channel[idx]
                var weightSum = 0.0f; var valueSum = 0.0f

                for (ny in maxOf(0, y - spatialRadius)..minOf(h - 1, y + spatialRadius)) {
                    for (nx in maxOf(0, x - spatialRadius)..minOf(w - 1, x + spatialRadius)) {
                        val nVal = channel[ny * w + nx]
                        val dx = (nx - x).toFloat(); val dy = (ny - y).toFloat()
                        val spatialWeight = exp(-(dx * dx + dy * dy) / spatialSigmaSq2)
                        val rangeDiff = (nVal - centerVal).toFloat()
                        val rangeWeight = exp(-(rangeDiff * rangeDiff) / rangeSigmaSq2)
                        val weight = spatialWeight * rangeWeight
                        weightSum += weight; valueSum += nVal * weight
                    }
                }

                output[idx] = if (weightSum > 0f) (valueSum / weightSum).roundToInt().coerceIn(0, 255) else centerVal
            }
        }

        return output
    }
}
