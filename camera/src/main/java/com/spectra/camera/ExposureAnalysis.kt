package com.spectra.camera

import android.graphics.Bitmap

object ExposureAnalysis {

    fun computeFalseColor(bitmap: Bitmap): IntArray {
        val w = bitmap.width / 2
        val h = bitmap.height / 2
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        val result = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            result[i] = when {
                lum < 5 -> 0xFF800080.toInt()
                lum < 20 -> 0xFF0000FF.toInt()
                lum < 50 -> 0xFF00FFFF.toInt()
                lum < 90 -> 0xFF008000.toInt()
                lum < 170 -> 0xFF808080.toInt()
                lum < 210 -> 0xFF00FF00.toInt()
                lum < 235 -> 0xFFFFFF00.toInt()
                lum < 250 -> 0xFFFF8000.toInt()
                else -> 0xFFFF0000.toInt()
            }
        }
        return result
    }

    data class ClippingInfo(
        val highlightFraction: Float,
        val shadowFraction: Float,
        val isHighlightClipped: Boolean,
        val isShadowClipped: Boolean
    )

    fun computeHighlightClipping(bitmap: Bitmap, highlightThreshold: Int = 250, shadowThreshold: Int = 5): ClippingInfo {
        val scale = 4
        val w = bitmap.width / scale
        val h = bitmap.height / scale
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        var highlightCount = 0
        var shadowCount = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            if (maxChannel >= highlightThreshold) highlightCount++
            if (minChannel <= shadowThreshold) shadowCount++
        }
        val total = pixels.size.toFloat()
        val hFrac = highlightCount / total
        val sFrac = shadowCount / total
        return ClippingInfo(
            highlightFraction = hFrac,
            shadowFraction = sFrac,
            isHighlightClipped = hFrac > 0.03f,
            isShadowClipped = sFrac > 0.05f
        )
    }

    fun computeWaveform(bitmap: Bitmap, outputWidth: Int = 256, outputHeight: Int = 128): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val waveform = IntArray(outputWidth * outputHeight)
        val colStep = maxOf(1, w / outputWidth)

        for (outX in 0 until outputWidth) {
            val srcX = (outX * w / outputWidth).coerceIn(0, w - 1)
            for (srcY in 0 until h) {
                val pixel = pixels[srcY * w + srcX]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                val outY = outputHeight - 1 - (lum * (outputHeight - 1) / 255)
                waveform[outY * outputWidth + outX]++
            }
        }
        return waveform
    }
}
