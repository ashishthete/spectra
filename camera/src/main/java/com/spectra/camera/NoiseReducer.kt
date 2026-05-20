package com.spectra.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

object NoiseReducer {
    private const val TAG = "NoiseReducer"
    private var gpuContext: com.spectra.camera.gpu.GpuContext? = null

    fun initGpu() {
        if (gpuContext != null) return
        val ctx = com.spectra.camera.gpu.GpuContext()
        if (ctx.init()) {
            gpuContext = ctx
            android.util.Log.d(TAG, "GPU bilateral filter available")
        }
    }

    fun releaseGpu() {
        gpuContext?.release()
        gpuContext = null
    }

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

    fun applySkinSelective(bitmap: Bitmap, iso: Int, faceRects: List<android.graphics.RectF>) {
        if (faceRects.isEmpty()) {
            apply(bitmap, iso)
            return
        }
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val sigma = computeSigma(iso)
        val spatialR = computeSpatialRadius(iso)

        val skinMask = BooleanArray(w * h)
        for (face in faceRects) {
            val pad = 0.15f
            val x0 = ((face.left - pad) * w).toInt().coerceIn(0, w - 1)
            val y0 = ((face.top - pad) * h).toInt().coerceIn(0, h - 1)
            val x1 = ((face.right + pad) * w).toInt().coerceIn(0, w)
            val y1 = ((face.bottom + pad) * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val p = pixels[y * w + x]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
                    if (ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])) {
                        skinMask[y * w + x] = true
                    }
                }
            }
        }

        val yChannel = IntArray(w * h)
        val cbChannel = IntArray(w * h)
        val crChannel = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF; val g = (pixels[i] shr 8) and 0xFF; val b = pixels[i] and 0xFF
            val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
            yChannel[i] = ycbcr[0]; cbChannel[i] = ycbcr[1]; crChannel[i] = ycbcr[2]
        }

        val yFiltered = tiledBilateralFilter(yChannel, w, h, spatialR, sigma * 0.7f)
        val cbFiltered = bilateralFilter(cbChannel, w, h, spatialR + 1, sigma * 1.5f)
        val crFiltered = bilateralFilter(crChannel, w, h, spatialR + 1, sigma * 1.5f)

        for (i in pixels.indices) {
            if (!skinMask[i]) continue
            val rgb = ColorSpaceUtils.ycbcrToRgb(yFiltered[i], cbFiltered[i], crFiltered[i])
            val blend = 0.65f
            val oR = (pixels[i] shr 16) and 0xFF
            val oG = (pixels[i] shr 8) and 0xFF
            val oB = pixels[i] and 0xFF
            val r = (rgb[0] * blend + oR * (1f - blend)).roundToInt().coerceIn(0, 255)
            val g = (rgb[1] * blend + oG * (1f - blend)).roundToInt().coerceIn(0, 255)
            val b = (rgb[2] * blend + oB * (1f - blend)).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d(TAG, "Skin-selective NR: ISO=$iso, sigma=$sigma, faces=${faceRects.size}")
    }

    fun apply(bitmap: Bitmap, iso: Int) {
        val w = bitmap.width; val h = bitmap.height
        val megapixels = (w.toLong() * h) / 1_000_000

        val (workW, workH, scale) = if (megapixels > 16) {
            val s = 2
            Triple(w / s, h / s, s)
        } else {
            Triple(w, h, 1)
        }

        val workBitmap = if (scale > 1) Bitmap.createScaledBitmap(bitmap, workW, workH, true) else bitmap
        val pixels = IntArray(workW * workH)
        workBitmap.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        if (scale > 1) workBitmap.recycle()

        val sigma = computeSigma(iso)
        val spatialR = computeSpatialRadius(iso)
        val yChannel = IntArray(workW * workH)
        val cbChannel = IntArray(workW * workH)
        val crChannel = IntArray(workW * workH)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF; val g = (pixels[i] shr 8) and 0xFF; val b = pixels[i] and 0xFF
            val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
            yChannel[i] = ycbcr[0]; cbChannel[i] = ycbcr[1]; crChannel[i] = ycbcr[2]
        }

        val yFiltered = tiledBilateralFilter(yChannel, workW, workH, spatialR, sigma * 0.9f)

        val halfW = workW / 2; val halfH = workH / 2
        val cbHalf = downsampleChannel(cbChannel, workW, workH, halfW, halfH)
        val crHalf = downsampleChannel(crChannel, workW, workH, halfW, halfH)
        val chromaRadius = spatialR + 2
        val cbHalfFiltered = bilateralFilter(cbHalf, halfW, halfH, chromaRadius, sigma * 2.5f)
        val crHalfFiltered = bilateralFilter(crHalf, halfW, halfH, chromaRadius, sigma * 2.5f)
        val cbFiltered = upsampleChannel(cbHalfFiltered, halfW, halfH, workW, workH)
        val crFiltered = upsampleChannel(crHalfFiltered, halfW, halfH, workW, workH)

        for (i in pixels.indices) {
            val rgb = ColorSpaceUtils.ycbcrToRgb(yFiltered[i], cbFiltered[i], crFiltered[i])
            pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        if (scale > 1) {
            val nrBitmap = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
            nrBitmap.setPixels(pixels, 0, workW, 0, 0, workW, workH)
            val upscaled = Bitmap.createScaledBitmap(nrBitmap, w, h, true)
            nrBitmap.recycle()

            val origPixels = IntArray(w * h)
            val nrPixels = IntArray(w * h)
            bitmap.getPixels(origPixels, 0, w, 0, 0, w, h)
            upscaled.getPixels(nrPixels, 0, w, 0, 0, w, h)
            upscaled.recycle()

            val blend = if (iso > 1600) 0.80f else 0.65f
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
            Log.d(TAG, "NR: ISO=$iso, sigma=$sigma, ${w}x${h} via ${workW}x${workH} bilateral, blend=$blend")
        } else {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            Log.d(TAG, "NR: ISO=$iso, sigma=$sigma, fullRes=${w}x${h}")
        }
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
        val gpu = gpuContext
        if (gpu != null) {
            val gpuResult = com.spectra.camera.gpu.BilateralFilterShader.filter(
                gpu, channel, w, h, spatialRadius, rangeSigma
            )
            if (gpuResult != null) return gpuResult
        }
        Log.w(TAG, "GPU bilateral filter unavailable, falling back to CPU bilateral")
        return cpuBilateral(channel, w, h, spatialRadius, rangeSigma)
    }



    private fun downsampleChannel(channel: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val result = IntArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            val sy0 = (y * scaleY).toInt().coerceIn(0, srcH - 1)
            val sy1 = (sy0 + 1).coerceAtMost(srcH - 1)
            for (x in 0 until dstW) {
                val sx0 = (x * scaleX).toInt().coerceIn(0, srcW - 1)
                val sx1 = (sx0 + 1).coerceAtMost(srcW - 1)
                result[y * dstW + x] = (channel[sy0 * srcW + sx0] + channel[sy0 * srcW + sx1] +
                    channel[sy1 * srcW + sx0] + channel[sy1 * srcW + sx1]) / 4
            }
        }
        return result
    }

    private fun upsampleChannel(channel: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val result = IntArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            val srcY = y * scaleY - 0.5f
            val y0 = srcY.toInt().coerceIn(0, srcH - 2)
            val fy = (srcY - y0).coerceIn(0f, 1f)
            for (x in 0 until dstW) {
                val srcX = x * scaleX - 0.5f
                val x0 = srcX.toInt().coerceIn(0, srcW - 2)
                val fx = (srcX - x0).coerceIn(0f, 1f)
                val v00 = channel[y0 * srcW + x0].toFloat()
                val v10 = channel[y0 * srcW + x0 + 1].toFloat()
                val v01 = channel[(y0 + 1) * srcW + x0].toFloat()
                val v11 = channel[(y0 + 1) * srcW + x0 + 1].toFloat()
                result[y * dstW + x] = (v00 * (1f - fx) * (1f - fy) + v10 * fx * (1f - fy) +
                    v01 * (1f - fx) * fy + v11 * fx * fy).roundToInt().coerceIn(0, 255)
            }
        }
        return result
    }

    internal fun bilateralFilter(channel: IntArray, w: Int, h: Int, spatialRadius: Int, rangeSigma: Float): IntArray {
        val gpu = gpuContext
        if (gpu != null) {
            val gpuResult = com.spectra.camera.gpu.BilateralFilterShader.filter(
                gpu, channel, w, h, spatialRadius, rangeSigma
            )
            if (gpuResult != null) return gpuResult
        }
        Log.w(TAG, "GPU chroma bilateral unavailable, falling back to CPU bilateral")
        return cpuBilateral(channel, w, h, spatialRadius, rangeSigma)
    }

    private fun cpuBilateral(channel: IntArray, w: Int, h: Int, radius: Int, rangeSigma: Float): IntArray {
        val result = IntArray(w * h)
        val rangeLut = IntArray(256)
        val rangeCoeff = -0.5f / (rangeSigma * rangeSigma)
        for (d in 0..255) rangeLut[d] = (exp(d.toFloat() * d * rangeCoeff) * 1024).toInt()

        val spatialLut = IntArray((2 * radius + 1) * (2 * radius + 1))
        val spatialSigma = radius * 0.5f
        val spatialCoeff = -0.5f / (spatialSigma * spatialSigma)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                spatialLut[(dy + radius) * (2 * radius + 1) + (dx + radius)] =
                    (exp((dx * dx + dy * dy).toFloat() * spatialCoeff) * 1024).toInt()
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = channel[y * w + x]
                var wSum = 0L
                var vSum = 0L
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val neighbor = channel[ny * w + nx]
                        val diff = if (center > neighbor) center - neighbor else neighbor - center
                        if (diff > 255) continue
                        val sw = spatialLut[(dy + radius) * (2 * radius + 1) + (dx + radius)]
                        val rw = rangeLut[diff]
                        val weight = (sw.toLong() * rw) shr 10
                        wSum += weight
                        vSum += neighbor * weight
                    }
                }
                result[y * w + x] = if (wSum > 0) (vSum / wSum).toInt().coerceIn(0, 255) else center
            }
        }
        return result
    }
}
