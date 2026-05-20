package com.spectra.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PortraitLighting {

    enum class LightingMode {
        NATURAL,
        STUDIO,
        CONTOUR,
        STAGE,
        HIGH_KEY
    }

    fun apply(
        pixels: IntArray,
        w: Int,
        h: Int,
        subjectMask: FloatArray,
        depthMap: FloatArray?,
        mode: LightingMode
    ) {
        when (mode) {
            LightingMode.NATURAL -> return
            LightingMode.STUDIO -> applyStudio(pixels, w, h, subjectMask)
            LightingMode.CONTOUR -> applyContour(pixels, w, h, subjectMask)
            LightingMode.STAGE -> applyStage(pixels, w, h, subjectMask)
            LightingMode.HIGH_KEY -> applyHighKey(pixels, w, h, subjectMask)
        }
    }

    private fun applyStudio(pixels: IntArray, w: Int, h: Int, mask: FloatArray) {
        val cx = w * 0.4f
        val cy = h * 0.3f
        val radius = w * 0.6f
        val radiusSq = radius * radius

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val maskIdx = (i * mask.size.toLong() / pixels.size).toInt().coerceIn(0, mask.size - 1)
                if (mask[maskIdx] < 0.5f) continue

                val dx = x - cx; val dy = y - cy
                val distSq = dx * dx + dy * dy
                val falloff = (1f - distSq / radiusSq).coerceIn(0.2f, 1f)
                val brightBoost = 1f + (falloff - 0.5f) * 0.3f

                val r = (((pixels[i] shr 16) and 0xFF) * brightBoost).roundToInt().coerceIn(0, 255)
                val g = (((pixels[i] shr 8) and 0xFF) * brightBoost).roundToInt().coerceIn(0, 255)
                val b = ((pixels[i] and 0xFF) * brightBoost).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun applyContour(pixels: IntArray, w: Int, h: Int, mask: FloatArray) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val maskIdx = (i * mask.size.toLong() / pixels.size).toInt().coerceIn(0, mask.size - 1)
                if (mask[maskIdx] < 0.5f) continue

                val lum = (((pixels[i] shr 16) and 0xFF) * 77 + ((pixels[i] shr 8) and 0xFF) * 150 + (pixels[i] and 0xFF) * 29) shr 8
                val factor = if (lum < 100) 0.85f else 1.05f

                val r = (((pixels[i] shr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                val g = (((pixels[i] shr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                val b = ((pixels[i] and 0xFF) * factor).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun applyStage(pixels: IntArray, w: Int, h: Int, mask: FloatArray) {
        for (i in pixels.indices) {
            val maskIdx = (i * mask.size.toLong() / pixels.size).toInt().coerceIn(0, mask.size - 1)
            if (mask[maskIdx] < 0.5f) {
                pixels[i] = (0xFF shl 24)
            }
        }
    }

    private fun applyHighKey(pixels: IntArray, w: Int, h: Int, mask: FloatArray) {
        for (i in pixels.indices) {
            val maskIdx = (i * mask.size.toLong() / pixels.size).toInt().coerceIn(0, mask.size - 1)
            if (mask[maskIdx] < 0.5f) {
                pixels[i] = (0xFF shl 24) or (0xF0 shl 16) or (0xF0 shl 8) or 0xF0
            } else {
                val r = (((pixels[i] shr 16) and 0xFF) * 1.08f).roundToInt().coerceIn(0, 255)
                val g = (((pixels[i] shr 8) and 0xFF) * 1.08f).roundToInt().coerceIn(0, 255)
                val b = ((pixels[i] and 0xFF) * 1.08f).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
