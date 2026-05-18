package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

object NoiseReducer {
    private const val TAG = "NoiseReducer"

    fun computeSigma(iso: Int): Float = when {
        iso > 1600 -> 3.0f + (iso - 1600) / 800.0f
        iso > 800 -> 1.5f + (iso - 800) / 533.0f
        else -> 0.5f + iso / 400.0f
    }

    private fun computeSpatialRadius(iso: Int): Int = when {
        iso > 1600 -> 5
        iso > 800 -> 4
        else -> 3
    }

    fun apply(bitmap: Bitmap, iso: Int) {
        val w = bitmap.width; val h = bitmap.height
        val megapixels = (w.toLong() * h) / 1_000_000

        if (megapixels > 2) {
            applyDownsampled(bitmap, iso)
            return
        }

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val sigma = computeSigma(iso)
        val spatialR = computeSpatialRadius(iso)
        val yChannel = IntArray(w * h); val cbChannel = IntArray(w * h); val crChannel = IntArray(w * h)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF; val g = (pixels[i] shr 8) and 0xFF; val b = pixels[i] and 0xFF
            val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
            yChannel[i] = ycbcr[0]; cbChannel[i] = ycbcr[1]; crChannel[i] = ycbcr[2]
        }

        val yFiltered = tiledBilateralFilter(yChannel, w, h, spatialR, sigma * 0.9f)

        val halfW = w / 2; val halfH = h / 2
        val cbHalf = downsampleChannel(cbChannel, w, h, halfW, halfH)
        val crHalf = downsampleChannel(crChannel, w, h, halfW, halfH)
        val chromaRadius = spatialR + 2
        val cbHalfFiltered = bilateralFilter(cbHalf, halfW, halfH, chromaRadius, sigma * 2.5f)
        val crHalfFiltered = bilateralFilter(crHalf, halfW, halfH, chromaRadius, sigma * 2.5f)
        val cbFiltered = upsampleChannel(cbHalfFiltered, halfW, halfH, w, h)
        val crFiltered = upsampleChannel(crHalfFiltered, halfW, halfH, w, h)

        for (i in pixels.indices) {
            val rgb = ColorSpaceUtils.ycbcrToRgb(yFiltered[i], cbFiltered[i], crFiltered[i])
            pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        Log.d(TAG, "NR: ISO=$iso, sigma=$sigma, fullRes=${w}x${h}")
    }

    private fun applyDownsampled(bitmap: Bitmap, iso: Int) {
        val w = bitmap.width; val h = bitmap.height
        val scale = 4
        val sw = w / scale; val sh = h / scale
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val yChannel = IntArray(sw * sh); val cbChannel = IntArray(sw * sh); val crChannel = IntArray(sw * sh)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF; val g = (pixels[i] shr 8) and 0xFF; val b = pixels[i] and 0xFF
            val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
            yChannel[i] = ycbcr[0]; cbChannel[i] = ycbcr[1]; crChannel[i] = ycbcr[2]
        }

        val lumaRadius = if (iso > 1600) 2 else 1
        val chromaRadius = if (iso > 1600) 4 else 3
        boxBlurChannel(yChannel, sw, sh, lumaRadius)
        boxBlurChannel(cbChannel, sw, sh, chromaRadius)
        boxBlurChannel(crChannel, sw, sh, chromaRadius)

        for (i in pixels.indices) {
            val rgb = ColorSpaceUtils.ycbcrToRgb(yChannel[i], cbChannel[i], crChannel[i])
            pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        small.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        val upscaled = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()

        val origPixels = IntArray(w * h)
        val nrPixels = IntArray(w * h)
        bitmap.getPixels(origPixels, 0, w, 0, 0, w, h)
        upscaled.getPixels(nrPixels, 0, w, 0, 0, w, h)
        upscaled.recycle()

        val blend = if (iso > 1600) 0.75f else 0.55f
        for (i in origPixels.indices) {
            val oR = (origPixels[i] shr 16) and 0xFF; val nR = (nrPixels[i] shr 16) and 0xFF
            val oG = (origPixels[i] shr 8) and 0xFF; val nG = (nrPixels[i] shr 8) and 0xFF
            val oB = origPixels[i] and 0xFF; val nB = nrPixels[i] and 0xFF
            val r = (nR * blend + oR * (1f - blend)).roundToInt().coerceIn(0, 255)
            val g = (nG * blend + oG * (1f - blend)).roundToInt().coerceIn(0, 255)
            val b = (nB * blend + oB * (1f - blend)).roundToInt().coerceIn(0, 255)
            origPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(origPixels, 0, w, 0, 0, w, h)
        Log.d(TAG, "NR: ISO=$iso, downsampled ${w}x${h} -> ${sw}x${sh}, blend=$blend")
    }

    private fun boxBlurChannel(channel: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val temp = IntArray(channel.size)
        val kernelSize = 2 * radius + 1

        for (y in 0 until h) {
            var sum = 0
            for (x in 0..min(radius, w - 1)) sum += channel[y * w + x]
            val leftPad = channel[y * w]
            for (x in 0 until radius) sum += leftPad
            for (x in 0 until w) {
                temp[y * w + x] = sum / kernelSize
                val removeX = x - radius
                val addX = x + radius + 1
                sum -= if (removeX >= 0) channel[y * w + removeX] else leftPad
                sum += if (addX < w) channel[y * w + addX] else channel[y * w + w - 1]
            }
        }

        for (x in 0 until w) {
            var sum = 0
            for (y in 0..min(radius, h - 1)) sum += temp[y * w + x]
            val topPad = temp[x]
            for (y in 0 until radius) sum += topPad
            for (y in 0 until h) {
                channel[y * w + x] = sum / kernelSize
                val removeY = y - radius
                val addY = y + radius + 1
                sum -= if (removeY >= 0) temp[removeY * w + x] else topPad
                sum += if (addY < h) temp[addY * w + x] else temp[(h - 1) * w + x]
            }
        }
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
