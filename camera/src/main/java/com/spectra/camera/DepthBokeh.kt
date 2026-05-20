// camera/src/main/java/com/spectra/camera/DepthBokeh.kt
package com.spectra.camera

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DepthBokeh {

    companion object {

        fun generateSubjectMask(
            depthMap: FloatArray,
            depthSize: Int,
            faceRects: List<RectF>,
            imageW: Int,
            imageH: Int,
            guidePixels: IntArray,
            depthThreshold: Float = 0.15f
        ): FloatArray {
            val focusDepth = selectFocusDepth(depthMap, depthSize, faceRects)
            val depthBokeh = DepthBokeh()
            val upsampled = depthBokeh.upsampleDepthWithGuidedFilter(
                depthMap, depthSize, depthSize,
                guidePixels, imageW, imageH
            )

            val mask = FloatArray(imageW * imageH)
            for (i in mask.indices) {
                val diff = abs(upsampled[i] - focusDepth)
                mask[i] = if (diff <= depthThreshold) 1f
                else (1f - ((diff - depthThreshold) / depthThreshold).coerceAtMost(1f))
            }
            return mask
        }

        fun computeBlurRadius(pixelDepth: Float, focusDepth: Float, maxRadius: Float): Float {
            val diff = abs(pixelDepth - focusDepth)
            return (maxRadius * diff).coerceAtMost(maxRadius)
        }

        fun selectFocusDepth(
            depthMap: FloatArray, depthSize: Int,
            faceRects: List<RectF>
        ): Float {
            if (faceRects.isNotEmpty()) {
                val face = faceRects[0]
                val cx = ((face.left + face.right) / 2f * depthSize).toInt().coerceIn(1, depthSize - 2)
                val cy = ((face.top + face.bottom) / 2f * depthSize).toInt().coerceIn(1, depthSize - 2)
                val samples = mutableListOf<Float>()
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        samples.add(depthMap[(cy + dy) * depthSize + (cx + dx)])
                    }
                }
                samples.sort()
                return samples[samples.size / 2]
            }
            return depthMap[depthSize / 2 * depthSize + depthSize / 2]
        }
    }

    fun upsampleDepthWithGuidedFilter(
        depthMap: FloatArray,
        depthW: Int, depthH: Int,
        guidePixels: IntArray,
        imageW: Int, imageH: Int,
        filterRadius: Int = 8,
        eps: Float = 0.01f
    ): FloatArray {
        val subW = imageW / 4; val subH = imageH / 4
        val subDepth = FloatArray(subW * subH)
        val scaleXd = depthW.toFloat() / subW
        val scaleYd = depthH.toFloat() / subH
        for (y in 0 until subH) {
            for (x in 0 until subW) {
                val dx = (x * scaleXd).toInt().coerceIn(0, depthW - 1)
                val dy = (y * scaleYd).toInt().coerceIn(0, depthH - 1)
                subDepth[y * subW + x] = depthMap[dy * depthW + dx]
            }
        }

        val subGuide = FloatArray(subW * subH)
        val scaleXg = imageW.toFloat() / subW
        val scaleYg = imageH.toFloat() / subH
        for (y in 0 until subH) {
            for (x in 0 until subW) {
                val gx = (x * scaleXg).toInt().coerceIn(0, imageW - 1)
                val gy = (y * scaleYg).toInt().coerceIn(0, imageH - 1)
                val pixel = guidePixels[gy * imageW + gx]
                val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF
                subGuide[y * subW + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        }

        val subRadius = filterRadius / 4
        val r = maxOf(subRadius, 2)
        val coeffs = guidedFilterCoefficients(subGuide, subDepth, subW, subH, r, eps)
        val meanA = coeffs.first
        val meanB = coeffs.second

        val fullGuide = FloatArray(imageW * imageH)
        for (i in guidePixels.indices) {
            val pr = (guidePixels[i] shr 16) and 0xFF; val pg = (guidePixels[i] shr 8) and 0xFF; val pb = guidePixels[i] and 0xFF
            fullGuide[i] = (0.299f * pr + 0.587f * pg + 0.114f * pb) / 255f
        }

        val result = FloatArray(imageW * imageH)
        for (y in 0 until imageH) {
            for (x in 0 until imageW) {
                val srcX = x.toFloat() / imageW * subW - 0.5f
                val srcY = y.toFloat() / imageH * subH - 0.5f
                val x0 = srcX.toInt().coerceIn(0, subW - 2)
                val y0 = srcY.toInt().coerceIn(0, subH - 2)
                val fx = (srcX - x0).coerceIn(0f, 1f)
                val fy = (srcY - y0).coerceIn(0f, 1f)
                val a00 = meanA[y0 * subW + x0]; val a10 = meanA[y0 * subW + x0 + 1]
                val a01 = meanA[(y0 + 1) * subW + x0]; val a11 = meanA[(y0 + 1) * subW + x0 + 1]
                val aUp = a00 * (1f - fx) * (1f - fy) + a10 * fx * (1f - fy) + a01 * (1f - fx) * fy + a11 * fx * fy
                val b00 = meanB[y0 * subW + x0]; val b10 = meanB[y0 * subW + x0 + 1]
                val b01 = meanB[(y0 + 1) * subW + x0]; val b11 = meanB[(y0 + 1) * subW + x0 + 1]
                val bUp = b00 * (1f - fx) * (1f - fy) + b10 * fx * (1f - fy) + b01 * (1f - fx) * fy + b11 * fx * fy
                result[y * imageW + x] = (aUp * fullGuide[y * imageW + x] + bUp).coerceIn(0f, 1f)
            }
        }
        return result
    }

    fun applyDepthBokeh(
        pixels: IntArray,
        depthMap: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        depthWidth: Int,
        depthHeight: Int,
        focusDepth: Float = 0.5f,
        maxBlurRadius: Float = 20f,
        faceRects: List<RectF> = emptyList()
    ): IntArray {
        val n = imageWidth * imageHeight

        val upsampledDepth = upsampleDepthWithGuidedFilter(
            depthMap, depthWidth, depthHeight,
            pixels, imageWidth, imageHeight
        )

        val result = IntArray(n)
        val cocMap = FloatArray(n)
        for (i in 0 until n) {
            cocMap[i] = computeBlurRadius(upsampledDepth[i], focusDepth, maxBlurRadius)
        }

        val numLevels = 5
        val baseRadius = (maxBlurRadius / (numLevels - 1)).toInt().coerceAtLeast(3)
        // Use separable Gaussian blur pyramid for smooth, natural bokeh
        val pyramid = buildBlurPyramid(pixels, imageWidth, imageHeight, numLevels, baseRadius)

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val i = y * imageWidth + x
                val coc = cocMap[i]

                if (coc < 1.0f) {
                    result[i] = pixels[i]
                    continue
                }

                val levelF = (coc / maxBlurRadius) * (numLevels - 1)
                val levelLow = levelF.toInt().coerceIn(0, numLevels - 2)
                val levelHigh = (levelLow + 1).coerceAtMost(numLevels - 1)
                val t = levelF - levelLow

                val pLow = pyramid[levelLow][i]
                val pHigh = pyramid[levelHigh][i]
                val r = lerp((pLow shr 16) and 0xFF, (pHigh shr 16) and 0xFF, t)
                val g = lerp((pLow shr 8) and 0xFF, (pHigh shr 8) and 0xFF, t)
                val b = lerp(pLow and 0xFF, pHigh and 0xFF, t)
                result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        applySpecularBloom(result, pixels, cocMap, imageWidth, imageHeight)
        applyCatEyeDistortion(result, cocMap, imageWidth, imageHeight)
        applyChromaticAberration(result, cocMap, imageWidth, imageHeight)

        return result
    }

    private fun applySpecularBloom(
        result: IntArray, original: IntArray, cocMap: FloatArray,
        w: Int, h: Int, threshold: Float = 0.9f, bloomPower: Float = 2.0f
    ) {
        val bloomRadius = 6
        val hotSpots = mutableListOf<Triple<Int, Int, Float>>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (cocMap[i] < 3f) continue
                val r = ((original[i] shr 16) and 0xFF) / 255f
                val g = ((original[i] shr 8) and 0xFF) / 255f
                val b = (original[i] and 0xFF) / 255f
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (lum > threshold) {
                    hotSpots.add(Triple(x, y, lum))
                }
            }
        }

        val bloomR2 = (bloomRadius + 0.5f) * (bloomRadius + 0.5f)
        for ((hx, hy, lum) in hotSpots) {
            val intensity = Math.pow(lum.toDouble(), bloomPower.toDouble()).toFloat().coerceAtMost(1.5f) - 1f
            if (intensity <= 0f) continue
            val spread = (cocMap[hy * w + hx] * 0.5f).toInt().coerceIn(2, bloomRadius)
            val srcR = ((original[hy * w + hx] shr 16) and 0xFF) / 255f
            val srcG = ((original[hy * w + hx] shr 8) and 0xFF) / 255f
            val srcB = (original[hy * w + hx] and 0xFF) / 255f

            for (dy in -spread..spread) {
                val sy = hy + dy
                if (sy < 0 || sy >= h) continue
                for (dx in -spread..spread) {
                    val sx = hx + dx
                    if (sx < 0 || sx >= w) continue
                    val d2 = (dx * dx + dy * dy).toFloat()
                    if (d2 > bloomR2) continue
                    val falloff = 1f - d2 / bloomR2
                    val contrib = intensity * falloff * 0.3f
                    val j = sy * w + sx
                    val curR = ((result[j] shr 16) and 0xFF) / 255f
                    val curG = ((result[j] shr 8) and 0xFF) / 255f
                    val curB = (result[j] and 0xFF) / 255f
                    val outR = (curR + srcR * contrib).coerceAtMost(1f)
                    val outG = (curG + srcG * contrib).coerceAtMost(1f)
                    val outB = (curB + srcB * contrib).coerceAtMost(1f)
                    result[j] = (0xFF shl 24) or
                        ((outR * 255).toInt().coerceIn(0, 255) shl 16) or
                        ((outG * 255).toInt().coerceIn(0, 255) shl 8) or
                        (outB * 255).toInt().coerceIn(0, 255)
                }
            }
        }
    }

    private fun applyCatEyeDistortion(
        pixels: IntArray, cocMap: FloatArray, w: Int, h: Int,
        strength: Float = 0.6f
    ) {
        val cx = w / 2f; val cy = h / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        val temp = pixels.copyOf()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (cocMap[i] < 2f) continue

                val dx = (x - cx) / maxDist
                val dy = (y - cy) / maxDist
                val dist = sqrt(dx * dx + dy * dy)
                val squeeze = 1f - strength * dist * dist

                if (squeeze >= 0.98f) continue

                val angle = atan2(dy, dx)
                val cosA = cos(angle); val sinA = kotlin.math.sin(angle)
                val offsetX = (cosA * (1f - squeeze) * cocMap[i] * 0.3f).toInt()
                val offsetY = (sinA * (1f - squeeze) * cocMap[i] * 0.3f).toInt()

                val sx = (x + offsetX).coerceIn(0, w - 1)
                val sy = (y + offsetY).coerceIn(0, h - 1)
                pixels[i] = temp[sy * w + sx]
            }
        }
    }

    private fun applyChromaticAberration(
        pixels: IntArray, cocMap: FloatArray, w: Int, h: Int,
        strength: Float = 1.5f
    ) {
        val cx = w / 2f; val cy = h / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        val original = pixels.copyOf()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (cocMap[i] < 3f) continue

                val dx = (x - cx) / maxDist
                val dy = (y - cy) / maxDist
                val dist = sqrt(dx * dx + dy * dy)

                if (dist < 0.15f) continue

                val shift = (strength * dist * cocMap[i] * 0.05f).toInt()
                if (shift < 1) continue

                val rxOff = (x + (dx * shift).toInt()).coerceIn(0, w - 1)
                val ryOff = (y + (dy * shift).toInt()).coerceIn(0, h - 1)
                val bxOff = (x - (dx * shift).toInt()).coerceIn(0, w - 1)
                val byOff = (y - (dy * shift).toInt()).coerceIn(0, h - 1)

                val rSrc = original[ryOff * w + rxOff]
                val bSrc = original[byOff * w + bxOff]
                val gVal = (original[i] shr 8) and 0xFF
                val rVal = (rSrc shr 16) and 0xFF
                val bVal = bSrc and 0xFF

                pixels[i] = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
            }
        }
    }

    private fun buildDiscBlurPyramid(pixels: IntArray, w: Int, h: Int, levels: Int, baseRadius: Int): List<IntArray> {
        val pyramid = mutableListOf(pixels.copyOf())
        for (level in 1 until levels) {
            val radius = baseRadius * level
            val blurred = discBlur(pixels, w, h, radius)
            pyramid.add(blurred)
        }
        return pyramid
    }

    private fun discBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val kernel = buildDiscKernel(r)
        val kSize = r * 2 + 1
        val result = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f; var weightSum = 0f
                for (ky in -r..r) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    for (kx in -r..r) {
                        val sx = (x + kx).coerceIn(0, w - 1)
                        val kw = kernel[(ky + r) * kSize + (kx + r)]
                        if (kw <= 0f) continue
                        val p = pixels[sy * w + sx]
                        sumR += ((p shr 16) and 0xFF) * kw
                        sumG += ((p shr 8) and 0xFF) * kw
                        sumB += (p and 0xFF) * kw
                        weightSum += kw
                    }
                }
                if (weightSum > 0f) {
                    val rr = (sumR / weightSum).toInt().coerceIn(0, 255)
                    val gg = (sumG / weightSum).toInt().coerceIn(0, 255)
                    val bb = (sumB / weightSum).toInt().coerceIn(0, 255)
                    result[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                } else {
                    result[y * w + x] = pixels[y * w + x]
                }
            }
        }
        return result
    }

    private fun buildDiscKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val kernel = FloatArray(size * size)
        val r2 = (radius + 0.5f) * (radius + 0.5f)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - radius).toFloat()
                val dy = (y - radius).toFloat()
                kernel[y * size + x] = if (dx * dx + dy * dy <= r2) 1f else 0f
            }
        }
        return kernel
    }

    fun buildBlurPyramid(pixels: IntArray, w: Int, h: Int, levels: Int, baseRadius: Int): List<IntArray> {
        val pyramid = mutableListOf<IntArray>()
        pyramid.add(pixels.copyOf())

        for (level in 1 until levels) {
            val radius = baseRadius * level
            val blurred = gaussianBlurSeparable(pixels, w, h, radius)
            pyramid.add(blurred)
        }

        return pyramid
    }

    fun guidedFilterCoefficients(guide: FloatArray, input: FloatArray, w: Int, h: Int, radius: Int, eps: Float): Pair<FloatArray, FloatArray> {
        val n = w * h
        val meanGuide = boxFilterFloat(guide, w, h, radius)
        val meanInput = boxFilterFloat(input, w, h, radius)

        val corrGuideInput = FloatArray(n) { guide[it] * input[it] }
        val meanCorrGuideInput = boxFilterFloat(corrGuideInput, w, h, radius)

        val corrGuideGuide = FloatArray(n) { guide[it] * guide[it] }
        val meanCorrGuideGuide = boxFilterFloat(corrGuideGuide, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val covGuideInput = meanCorrGuideInput[i] - meanGuide[i] * meanInput[i]
            val varGuide = meanCorrGuideGuide[i] - meanGuide[i] * meanGuide[i]
            a[i] = covGuideInput / (varGuide + eps)
            b[i] = meanInput[i] - a[i] * meanGuide[i]
        }

        return Pair(boxFilterFloat(a, w, h, radius), boxFilterFloat(b, w, h, radius))
    }

    fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanGuide = boxFilterFloat(guide, w, h, radius)
        val meanInput = boxFilterFloat(input, w, h, radius)

        val corrGuideInput = FloatArray(n) { guide[it] * input[it] }
        val meanCorrGuideInput = boxFilterFloat(corrGuideInput, w, h, radius)

        val corrGuideGuide = FloatArray(n) { guide[it] * guide[it] }
        val meanCorrGuideGuide = boxFilterFloat(corrGuideGuide, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val covGuideInput = meanCorrGuideInput[i] - meanGuide[i] * meanInput[i]
            val varGuide = meanCorrGuideGuide[i] - meanGuide[i] * meanGuide[i]
            a[i] = covGuideInput / (varGuide + eps)
            b[i] = meanInput[i] - a[i] * meanGuide[i]
        }

        val meanA = boxFilterFloat(a, w, h, radius)
        val meanB = boxFilterFloat(b, w, h, radius)

        val result = FloatArray(n)
        for (i in 0 until n) {
            result[i] = meanA[i] * guide[i] + meanB[i]
        }
        return result
    }

    private fun boxFilterFloat(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val output = FloatArray(input.size)
        val temp = FloatArray(input.size)
        val kernelSize = radius * 2 + 1

        for (y in 0 until h) {
            var sum = 0f
            for (kx in -radius..radius) {
                sum += input[y * w + kx.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                temp[y * w + x] = sum / kernelSize
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                sum += input[y * w + addX] - input[y * w + remX]
            }
        }

        for (x in 0 until w) {
            var sum = 0f
            for (ky in -radius..radius) {
                sum += temp[ky.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                output[y * w + x] = sum / kernelSize
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                sum += temp[addY * w + x] - temp[remY * w + x]
            }
        }

        return output
    }

    /**
     * Build a normalised 1-D Gaussian kernel for the given radius.
     * sigma = radius / 2.5 gives a smooth fall-off that reaches ~1 % at the edges.
     */
    private fun buildGaussianKernel1D(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val sigma = radius / 2.5f
        val twoSigmaSq = 2f * sigma * sigma
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            val w = exp(-(x * x) / twoSigmaSq)
            kernel[i] = w
            sum += w
        }
        // normalise so weights sum to 1
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    /**
     * Separable Gaussian blur: horizontal pass followed by vertical pass.
     * O(n * radius) per pixel instead of O(n * radius^2).
     */
    private fun gaussianBlurSeparable(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val kernel = buildGaussianKernel1D(r)
        val n = w * h

        // --- horizontal pass ---
        val temp = IntArray(n)
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f
                for (k in -r..r) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val p = pixels[rowOff + sx]
                    val weight = kernel[k + r]
                    sumR += ((p shr 16) and 0xFF) * weight
                    sumG += ((p shr 8) and 0xFF) * weight
                    sumB += (p and 0xFF) * weight
                }
                temp[rowOff + x] = (0xFF shl 24) or
                    (sumR.toInt().coerceIn(0, 255) shl 16) or
                    (sumG.toInt().coerceIn(0, 255) shl 8) or
                    sumB.toInt().coerceIn(0, 255)
            }
        }

        // --- vertical pass ---
        val result = IntArray(n)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f
                for (k in -r..r) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val p = temp[sy * w + x]
                    val weight = kernel[k + r]
                    sumR += ((p shr 16) and 0xFF) * weight
                    sumG += ((p shr 8) and 0xFF) * weight
                    sumB += (p and 0xFF) * weight
                }
                result[y * w + x] = (0xFF shl 24) or
                    (sumR.toInt().coerceIn(0, 255) shl 16) or
                    (sumG.toInt().coerceIn(0, 255) shl 8) or
                    sumB.toInt().coerceIn(0, 255)
            }
        }

        return result
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
}
