package com.spectra.camera

import kotlin.math.pow
import kotlin.math.roundToInt

object ColorSpaceUtils {

    fun rgbToYCbCr(r: Int, g: Int, b: Int): IntArray {
        val y  = ( 0.299  * r + 0.587  * g + 0.114  * b).roundToInt().coerceIn(0, 255)
        val cb = (128.0 - 0.169 * r - 0.331 * g + 0.500 * b).roundToInt().coerceIn(0, 255)
        val cr = (128.0 + 0.500 * r - 0.419 * g - 0.081 * b).roundToInt().coerceIn(0, 255)
        return intArrayOf(y, cb, cr)
    }

    fun ycbcrToRgb(y: Int, cb: Int, cr: Int): IntArray {
        val r = (y + 1.402 * (cr - 128)).roundToInt().coerceIn(0, 255)
        val g = (y - 0.344 * (cb - 128) - 0.714 * (cr - 128)).roundToInt().coerceIn(0, 255)
        val b = (y + 1.772 * (cb - 128)).roundToInt().coerceIn(0, 255)
        return intArrayOf(r, g, b)
    }

    fun rgbToLab(r: Int, g: Int, b: Int): FloatArray {
        val rl = srgbToLinear(r / 255.0)
        val gl = srgbToLinear(g / 255.0)
        val bl = srgbToLinear(b / 255.0)

        val x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl

        val xRef = 0.95047; val yRef = 1.00000; val zRef = 1.08883
        val fx = labF(x / xRef); val fy = labF(y / yRef); val fz = labF(z / zRef)
        val labL = (116.0 * fy - 16.0).toFloat()
        val labA = (500.0 * (fx - fy)).toFloat()
        val labB = (200.0 * (fy - fz)).toFloat()
        return floatArrayOf(labL.coerceAtLeast(0f), labA, labB)
    }

    fun labToRgb(l: Float, a: Float, b: Float): IntArray {
        val xRef = 0.95047; val yRef = 1.00000; val zRef = 1.08883
        val fy = (l + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        val x = xRef * labFInv(fx); val y = yRef * labFInv(fy); val z = zRef * labFInv(fz)
        val rl =  3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl =  0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        val r = (linearToSrgb(rl) * 255.0).roundToInt().coerceIn(0, 255)
        val g = (linearToSrgb(gl) * 255.0).roundToInt().coerceIn(0, 255)
        val bOut = (linearToSrgb(bl) * 255.0).roundToInt().coerceIn(0, 255)
        return intArrayOf(r, g, bOut)
    }

    fun isSkinPixelYCbCr(cb: Int, cr: Int): Boolean {
        return cb in 78..126 && cr in 134..172
    }

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double {
        val clamped = c.coerceIn(0.0, 1.0)
        return if (clamped <= 0.0031308) 12.92 * clamped else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
    }

    private fun labF(t: Double): Double {
        val delta = 6.0 / 29.0
        return if (t > delta * delta * delta) t.pow(1.0 / 3.0) else t / (3.0 * delta * delta) + 4.0 / 29.0
    }

    private fun labFInv(t: Double): Double {
        val delta = 6.0 / 29.0
        return if (t > delta) t * t * t else 3.0 * delta * delta * (t - 4.0 / 29.0)
    }
}
