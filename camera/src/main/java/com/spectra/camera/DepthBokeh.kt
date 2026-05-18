// camera/src/main/java/com/spectra/camera/DepthBokeh.kt
package com.spectra.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DepthBokeh {

    companion object {
        fun computeBlurRadius(pixelDepth: Float, focusDepth: Float, maxRadius: Float): Float {
            val diff = abs(pixelDepth - focusDepth)
            return (maxRadius * diff).coerceAtMost(maxRadius)
        }
    }

    fun applyDepthBokeh(
        pixels: IntArray,
        depthMap: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        depthWidth: Int,
        depthHeight: Int,
        focusDepth: Float = 0.5f,
        maxBlurRadius: Float = 15f,
        numLevels: Int = 4
    ): IntArray {
        val n = imageWidth * imageHeight
        val baseRadius = (maxBlurRadius / (numLevels - 1)).toInt().coerceAtLeast(3)
        val pyramid = buildBlurPyramid(pixels, imageWidth, imageHeight, numLevels, baseRadius)

        val result = IntArray(n)
        val scaleX = depthWidth.toFloat() / imageWidth
        val scaleY = depthHeight.toFloat() / imageHeight

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val i = y * imageWidth + x
                val dx = (x * scaleX).toInt().coerceIn(0, depthWidth - 1)
                val dy = (y * scaleY).toInt().coerceIn(0, depthHeight - 1)
                val depth = depthMap[dy * depthWidth + dx]

                val blurRadius = computeBlurRadius(depth, focusDepth, maxBlurRadius)
                val levelF = (blurRadius / maxBlurRadius) * (numLevels - 1)
                val levelLow = levelF.toInt().coerceIn(0, numLevels - 2)
                val levelHigh = (levelLow + 1).coerceAtMost(numLevels - 1)
                val t = levelF - levelLow

                if (t < 0.01f) {
                    result[i] = pyramid[levelLow][i]
                } else if (t > 0.99f) {
                    result[i] = pyramid[levelHigh][i]
                } else {
                    val pLow = pyramid[levelLow][i]
                    val pHigh = pyramid[levelHigh][i]
                    val r = lerp((pLow shr 16) and 0xFF, (pHigh shr 16) and 0xFF, t)
                    val g = lerp((pLow shr 8) and 0xFF, (pHigh shr 8) and 0xFF, t)
                    val b = lerp(pLow and 0xFF, pHigh and 0xFF, t)
                    result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return result
    }

    fun buildBlurPyramid(pixels: IntArray, w: Int, h: Int, levels: Int, baseRadius: Int): List<IntArray> {
        val pyramid = mutableListOf<IntArray>()
        pyramid.add(pixels.copyOf())

        var current = pixels.copyOf()
        for (level in 1 until levels) {
            val blurred = IntArray(w * h)
            System.arraycopy(current, 0, blurred, 0, current.size)
            val radius = baseRadius * level
            for (pass in 0 until 2) {
                boxBlurPass(blurred, w, h, radius)
            }
            pyramid.add(blurred)
            current = blurred
        }

        return pyramid
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

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val kernelSize = radius * 2 + 1

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (kx in -radius..radius) {
                val x = kx.coerceIn(0, w - 1)
                val p = pixels[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                temp[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                val addP = pixels[y * w + addX]
                val remP = pixels[y * w + remX]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (ky in -radius..radius) {
                val y = ky.coerceIn(0, h - 1)
                val p = temp[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                val addP = temp[addY * w + x]
                val remP = temp[remY * w + x]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
}
