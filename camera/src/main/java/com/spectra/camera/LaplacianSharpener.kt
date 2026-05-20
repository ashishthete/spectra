package com.spectra.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

object LaplacianSharpener {

    data class SharpParams(
        val levels: Int = 3,
        val fineGain: Float = 1.9f,
        val midGain: Float = 1.5f,
        val coarseGain: Float = 1.15f,
        val noiseThreshold: Float = 7f,
        val edgeAware: Boolean = true,
        val edgeProtectStrength: Float = 0.7f
    ) {
        companion object {
            fun forIso(iso: Int): SharpParams {
                if (iso > 3200) return SharpParams(
                    fineGain = 1.0f, midGain = 1.0f, coarseGain = 1.0f
                )
                if (iso > 1600) return SharpParams(
                    fineGain = 1.2f, midGain = 1.15f, coarseGain = 1.0f,
                    noiseThreshold = 10f
                )
                if (iso > 800) return SharpParams(
                    fineGain = 1.5f, midGain = 1.3f, coarseGain = 1.05f,
                    noiseThreshold = 8f
                )
                return SharpParams()
            }
        }
    }

    fun sharpen(bitmap: Bitmap, params: SharpParams = SharpParams()) {
        if (params.fineGain <= 1f && params.midGain <= 1f && params.coarseGain <= 1f) return

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val gains = floatArrayOf(params.fineGain, params.midGain, params.coarseGain)
        val actualLevels = min(params.levels, gains.size)

        val gaussPyramid = buildGaussianPyramid(lum, w, h, actualLevels)
        val laplacianPyramid = buildLaplacianPyramid(gaussPyramid, w, h)

        var curW = w
        var curH = h
        for (lev in 0 until laplacianPyramid.size) {
            val gain = if (lev < gains.size) gains[lev] else 1f
            if (gain <= 1f) { curW /= 2; curH /= 2; continue }
            val lap = laplacianPyramid[lev]
            val threshold = params.noiseThreshold
            val edgeMask = if (params.edgeAware && lev == 0) computeEdgeMask(gaussPyramid[0], curW, curH) else null
            for (i in lap.indices) {
                if (abs(lap[i]) > threshold) {
                    val edgeFactor = if (edgeMask != null) {
                        1f - edgeMask[i] * params.edgeProtectStrength
                    } else 1f
                    lap[i] *= 1f + (gain - 1f) * edgeFactor
                }
            }
            curW /= 2; curH /= 2
        }

        val sharpLum = reconstructFromLaplacian(laplacianPyramid, gaussPyramid.last(), w, h)

        for (i in pixels.indices) {
            val origLum = lum[i]
            if (origLum < 1f) continue
            val ratio = (sharpLum[i] / origLum).coerceIn(0.5f, 2f)
            val r = (((pixels[i] shr 16) and 0xFF) * ratio).toInt().coerceIn(0, 255)
            val g = (((pixels[i] shr 8) and 0xFF) * ratio).toInt().coerceIn(0, 255)
            val b = ((pixels[i] and 0xFF) * ratio).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun computeEdgeMask(lum: FloatArray, w: Int, h: Int): FloatArray {
        val mask = FloatArray(w * h)
        var maxGrad = 1f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = lum[y * w + x + 1] - lum[y * w + x - 1]
                val gy = lum[(y + 1) * w + x] - lum[(y - 1) * w + x]
                val grad = kotlin.math.sqrt(gx * gx + gy * gy)
                mask[y * w + x] = grad
                if (grad > maxGrad) maxGrad = grad
            }
        }
        val invMax = 1f / maxGrad
        for (i in mask.indices) mask[i] = (mask[i] * invMax).coerceIn(0f, 1f)
        return mask
    }

    private fun buildGaussianPyramid(data: FloatArray, w: Int, h: Int, levels: Int): List<FloatArray> {
        val pyramid = mutableListOf(data)
        var curW = w; var curH = h
        var current = data
        for (l in 0 until levels) {
            val nextW = curW / 2; val nextH = curH / 2
            if (nextW < 2 || nextH < 2) break
            val next = FloatArray(nextW * nextH)
            for (y in 0 until nextH) {
                for (x in 0 until nextW) {
                    val sx = x * 2; val sy = y * 2
                    var sum = current[sy * curW + sx]
                    var count = 1
                    if (sx + 1 < curW) { sum += current[sy * curW + sx + 1]; count++ }
                    if (sy + 1 < curH) { sum += current[(sy + 1) * curW + sx]; count++ }
                    if (sx + 1 < curW && sy + 1 < curH) { sum += current[(sy + 1) * curW + sx + 1]; count++ }
                    next[y * nextW + x] = sum / count
                }
            }
            pyramid.add(next)
            current = next; curW = nextW; curH = nextH
        }
        return pyramid
    }

    private fun buildLaplacianPyramid(gaussPyramid: List<FloatArray>, origW: Int, origH: Int): List<FloatArray> {
        val laplacian = mutableListOf<FloatArray>()
        var curW = origW; var curH = origH
        for (l in 0 until gaussPyramid.size - 1) {
            val nextW = curW / 2; val nextH = curH / 2
            val upsampled = upsample(gaussPyramid[l + 1], nextW, nextH, curW, curH)
            val lap = FloatArray(curW * curH) { gaussPyramid[l][it] - upsampled[it] }
            laplacian.add(lap)
            curW = nextW; curH = nextH
        }
        return laplacian
    }

    private fun reconstructFromLaplacian(
        laplacian: List<FloatArray>,
        coarsest: FloatArray,
        origW: Int,
        origH: Int
    ): FloatArray {
        var curW = origW
        var curH = origH
        val dims = mutableListOf(Pair(curW, curH))
        for (l in 0 until laplacian.size) {
            curW /= 2; curH /= 2
            dims.add(Pair(curW, curH))
        }

        var current = coarsest
        var cW = dims.last().first
        var cH = dims.last().second

        for (lev in laplacian.size - 1 downTo 0) {
            val targetW = dims[lev].first
            val targetH = dims[lev].second
            val upsampled = upsample(current, cW, cH, targetW, targetH)
            current = FloatArray(targetW * targetH) {
                (upsampled[it] + laplacian[lev][it]).coerceIn(0f, 255f)
            }
            cW = targetW; cH = targetH
        }
        return current
    }

    private fun upsample(data: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val result = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val srcY = y.toFloat() / dstH * srcH - 0.5f
            val y0 = srcY.toInt().coerceIn(0, max(0, srcH - 2))
            val fy = (srcY - y0).coerceIn(0f, 1f)
            val y1 = min(y0 + 1, srcH - 1)
            for (x in 0 until dstW) {
                val srcX = x.toFloat() / dstW * srcW - 0.5f
                val x0 = srcX.toInt().coerceIn(0, max(0, srcW - 2))
                val fx = (srcX - x0).coerceIn(0f, 1f)
                val x1 = min(x0 + 1, srcW - 1)
                result[y * dstW + x] = data[y0 * srcW + x0] * (1f - fx) * (1f - fy) +
                    data[y0 * srcW + x1] * fx * (1f - fy) +
                    data[y1 * srcW + x0] * (1f - fx) * fy +
                    data[y1 * srcW + x1] * fx * fy
            }
        }
        return result
    }
}
