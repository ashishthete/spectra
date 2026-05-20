package com.spectra.ai

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

enum class MonkSkinTone(val shade: Int, val labL: Float, val labA: Float, val labB: Float) {
    MST_1(1, 91.4f, 3.8f, 14.5f),    // #f6ede4
    MST_2(2, 83.1f, 7.2f, 18.9f),    // #f3e7db
    MST_3(3, 74.8f, 10.8f, 24.6f),   // #f7d7c4
    MST_4(4, 66.3f, 14.0f, 29.0f),   // #eaceb0
    MST_5(5, 56.8f, 15.8f, 28.2f),   // #d7a86e
    MST_6(6, 47.2f, 15.2f, 24.8f),   // #a07444
    MST_7(7, 39.2f, 14.0f, 20.6f),   // #764d2e
    MST_8(8, 32.4f, 12.6f, 15.8f),   // #5c3920
    MST_9(9, 25.4f, 10.8f, 10.4f),   // #3a2118
    MST_10(10, 18.6f, 8.4f, 5.6f);   // #292420

    val isLight: Boolean get() = shade <= 2
    val isMedium: Boolean get() = shade in 3..6
    val isDark: Boolean get() = shade >= 7
}

data class SkinToneResult(
    val tone: MonkSkinTone,
    val deltaE: Float,
    val awbShiftK: Int,
    val evCompensation: Float
)

@Singleton
class SkinToneClassifier @Inject constructor() {

    fun classifyFromBitmap(bitmap: Bitmap, faceRect: RectF): SkinToneResult {
        val w = bitmap.width; val h = bitmap.height
        val left = (faceRect.left * w).toInt().coerceIn(0, w - 1)
        val top = (faceRect.top * h).toInt().coerceIn(0, h - 1)
        val right = (faceRect.right * w).toInt().coerceIn(0, w)
        val bottom = (faceRect.bottom * h).toInt().coerceIn(0, h)

        val cheekTop = top + ((bottom - top) * 0.45f).toInt()
        val cheekBottom = top + ((bottom - top) * 0.75f).toInt()
        val cheekLeft = left + ((right - left) * 0.2f).toInt()
        val cheekRight = left + ((right - left) * 0.8f).toInt()

        val cW = (cheekRight - cheekLeft).coerceAtLeast(1)
        val cH = (cheekBottom - cheekTop).coerceAtLeast(1)
        val regionPixels = IntArray(cW * cH)
        bitmap.getPixels(regionPixels, 0, cW, cheekLeft, cheekTop, cW, cH)

        return classifyFromPixels(regionPixels)
    }

    fun classifyFromPixels(skinPixels: IntArray): SkinToneResult {
        if (skinPixels.isEmpty()) return defaultResult()

        var sumL = 0.0; var sumA = 0.0; var sumB = 0.0
        var count = 0
        for (pixel in skinPixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (!isSkinPixel(r, g, b)) continue
            val lab = rgbToLabFast(r, g, b)
            sumL += lab[0]; sumA += lab[1]; sumB += lab[2]
            count++
        }

        if (count < 10) return defaultResult()

        val meanL = (sumL / count).toFloat()
        val meanA = (sumA / count).toFloat()
        val meanB = (sumB / count).toFloat()

        return matchToMST(meanL, meanA, meanB)
    }

    private fun matchToMST(l: Float, a: Float, b: Float): SkinToneResult {
        var bestTone = MonkSkinTone.MST_5
        var bestDeltaE = Float.MAX_VALUE

        for (tone in MonkSkinTone.entries) {
            val dE = ciede2000(l, a, b, tone.labL, tone.labA, tone.labB)
            if (dE < bestDeltaE) {
                bestDeltaE = dE
                bestTone = tone
            }
        }

        val strategy = getTonalStrategy(bestTone)
        return SkinToneResult(bestTone, bestDeltaE, strategy.first, strategy.second)
    }

    private fun getTonalStrategy(tone: MonkSkinTone): Pair<Int, Float> {
        return when {
            tone.isLight -> Pair(-100, 0.3f)
            tone.isMedium -> Pair(200, 0.0f)
            tone.isDark -> Pair(300, 0.3f)
            else -> Pair(0, 0.0f)
        }
    }

    private fun ciede2000(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val c1 = sqrt((a1 * a1 + b1 * b1).toDouble())
        val c2 = sqrt((a2 * a2 + b2 * b2).toDouble())
        val cAvg = (c1 + c2) / 2.0
        val cAvg7 = cAvg.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(cAvg7 / (cAvg7 + 6103515625.0))) // 25^7
        val a1p = a1 * (1.0 + g)
        val a2p = a2 * (1.0 + g)
        val c1p = sqrt(a1p * a1p + b1 * b1.toDouble())
        val c2p = sqrt(a2p * a2p + b2 * b2.toDouble())
        var h1p = Math.toDegrees(atan2(b1.toDouble(), a1p))
        if (h1p < 0) h1p += 360.0
        var h2p = Math.toDegrees(atan2(b2.toDouble(), a2p))
        if (h2p < 0) h2p += 360.0

        val dLp = l2 - l1.toDouble()
        val dCp = c2p - c1p
        var dhp = h2p - h1p
        if (abs(dhp) > 180.0) {
            if (h2p <= h1p) dhp += 360.0 else dhp -= 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lAvg = (l1 + l2) / 2.0
        val cPAvg = (c1p + c2p) / 2.0
        var hPAvg = (h1p + h2p) / 2.0
        if (abs(h1p - h2p) > 180.0) {
            hPAvg += if (hPAvg < 180.0) 180.0 else -180.0
        }

        val t = 1.0 - 0.17 * cos(Math.toRadians(hPAvg - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hPAvg)) +
            0.32 * cos(Math.toRadians(3.0 * hPAvg + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hPAvg - 63.0))

        val sl = 1.0 + 0.015 * (lAvg - 50.0).pow(2.0) / sqrt(20.0 + (lAvg - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * cPAvg
        val sh = 1.0 + 0.015 * cPAvg * t

        val cPAvg7 = cPAvg.pow(7.0)
        val rt = -2.0 * sqrt(cPAvg7 / (cPAvg7 + 6103515625.0)) *
            sin(Math.toRadians(60.0 * exp(-((hPAvg - 275.0) / 25.0).pow(2.0))))

        val termL = dLp / sl
        val termC = dCp / sc
        val termH = dHp / sh

        return sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH).toFloat()
    }

    private fun isSkinPixel(r: Int, g: Int, b: Int): Boolean {
        val cb = (128f - 37.797f * r / 255f - 74.203f * g / 255f + 112f * b / 255f).toInt()
        val cr = (128f + 112f * r / 255f - 93.786f * g / 255f - 18.214f * b / 255f).toInt()
        val cbCenter = 108f; val crCenter = 152f
        val cbRadius = 22f; val crRadius = 28f
        val dx = (cb - cbCenter) / cbRadius
        val dy = (cr - crCenter) / crRadius
        return dx * dx + dy * dy <= 1.0f
    }

    private fun rgbToLabFast(r: Int, g: Int, b: Int): FloatArray {
        val rl = srgbToLinear(r / 255.0)
        val gl = srgbToLinear(g / 255.0)
        val bl = srgbToLinear(b / 255.0)
        val x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl
        val fx = labF(x / 0.95047); val fy = labF(y / 1.0); val fz = labF(z / 1.08883)
        return floatArrayOf(
            (116.0 * fy - 16.0).toFloat().coerceAtLeast(0f),
            (500.0 * (fx - fy)).toFloat(),
            (200.0 * (fy - fz)).toFloat()
        )
    }

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun labF(t: Double): Double {
        val delta = 6.0 / 29.0
        return if (t > delta * delta * delta) t.pow(1.0 / 3.0) else t / (3.0 * delta * delta) + 4.0 / 29.0
    }

    private fun defaultResult() = SkinToneResult(MonkSkinTone.MST_5, Float.MAX_VALUE, 0, 0f)
}
