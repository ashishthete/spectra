package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.min
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

        // Y channel: tiled bilateral at full resolution to preserve luma detail
        val yFiltered = tiledBilateralFilter(yChannel, w, h, 3, sigma * 0.8f)

        // Cb/Cr channels: downsample 2x, filter, upsample — chroma doesn't need full res
        val halfW = w / 2; val halfH = h / 2
        val cbHalf = downsampleChannel(cbChannel, w, h, halfW, halfH)
        val crHalf = downsampleChannel(crChannel, w, h, halfW, halfH)
        val cbHalfFiltered = bilateralFilter(cbHalf, halfW, halfH, 5, sigma * 2.0f)
        val crHalfFiltered = bilateralFilter(crHalf, halfW, halfH, 5, sigma * 2.0f)
        val cbFiltered = upsampleChannel(cbHalfFiltered, halfW, halfH, w, h)
        val crFiltered = upsampleChannel(crHalfFiltered, halfW, halfH, w, h)

        for (i in pixels.indices) {
            val rgb = ColorSpaceUtils.ycbcrToRgb(yFiltered[i], cbFiltered[i], crFiltered[i])
            pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        Log.d(TAG, "NR: ISO=$iso, sigma=$sigma, fullRes=${w}x${h}")
    }

    internal fun tiledBilateralFilter(
        channel: IntArray, w: Int, h: Int,
        spatialRadius: Int, rangeSigma: Float,
        tileSize: Int = 512
    ): IntArray {
        val output = IntArray(channel.size)
        val overlap = spatialRadius + 1

        var ty = 0
        while (ty < h) {
            var tx = 0
            val coreH = min(tileSize, h - ty)
            while (tx < w) {
                val coreW = min(tileSize, w - tx)

                // Compute expanded tile bounds with overlap, clamped to image edges
                val exLeft = maxOf(0, tx - overlap)
                val exTop = maxOf(0, ty - overlap)
                val exRight = minOf(w, tx + coreW + overlap)
                val exBottom = minOf(h, ty + coreH + overlap)
                val exW = exRight - exLeft
                val exH = exBottom - exTop

                // Extract the expanded tile
                val tile = IntArray(exW * exH)
                for (row in 0 until exH) {
                    val srcOff = (exTop + row) * w + exLeft
                    val dstOff = row * exW
                    System.arraycopy(channel, srcOff, tile, dstOff, exW)
                }

                // Run bilateral filter on the expanded tile
                val filteredTile = bilateralFilter(tile, exW, exH, spatialRadius, rangeSigma)

                // Copy back only the core region
                val coreOffX = tx - exLeft
                val coreOffY = ty - exTop
                for (row in 0 until coreH) {
                    val srcOff = (coreOffY + row) * exW + coreOffX
                    val dstOff = (ty + row) * w + tx
                    System.arraycopy(filteredTile, srcOff, output, dstOff, coreW)
                }

                tx += tileSize
            }
            ty += tileSize
        }

        return output
    }

    private fun downsampleChannel(channel: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val result = IntArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            val sy = minOf((y * scaleY).toInt(), srcH - 1)
            for (x in 0 until dstW) {
                val sx = minOf((x * scaleX).toInt(), srcW - 1)
                result[y * dstW + x] = channel[sy * srcW + sx]
            }
        }
        return result
    }

    private fun upsampleChannel(channel: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val result = IntArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            val sy = minOf((y * scaleY).toInt(), srcH - 1)
            for (x in 0 until dstW) {
                val sx = minOf((x * scaleX).toInt(), srcW - 1)
                result[y * dstW + x] = channel[sy * srcW + sx]
            }
        }
        return result
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
