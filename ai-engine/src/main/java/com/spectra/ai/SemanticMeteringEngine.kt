package com.spectra.ai

import android.util.Log

object SemanticMeteringEngine {

    private const val TAG = "SemanticMetering"

    data class MeteringResult(
        val targetExposureCompensation: Float,
        val subjectLuminance: Float,
        val backgroundLuminance: Float,
        val hasSkyHighlights: Boolean
    )

    fun computeSemanticMetering(
        pixels: IntArray,
        width: Int,
        height: Int,
        subjectMask: FloatArray?,
        skyMask: BooleanArray?,
        faceRegions: List<android.graphics.RectF> = emptyList()
    ): MeteringResult {
        var subjectLumSum = 0f; var subjectCount = 0
        var bgLumSum = 0f; var bgCount = 0
        var skyLumSum = 0f; var skyCount = 0
        var faceLumSum = 0f; var faceCount = 0

        val scale = 4
        val sw = width / scale
        val sh = height / scale

        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                val x = sx * scale; val y = sy * scale
                val idx = y * width + x
                if (idx >= pixels.size) continue
                val p = pixels[idx]
                val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

                val isSubject = subjectMask?.let { mask ->
                    val aspectRatio = width.toFloat() / height
                    val maskH = kotlin.math.sqrt(mask.size / aspectRatio).toInt().coerceAtLeast(1)
                    val maskW = (maskH * aspectRatio).toInt().coerceAtLeast(1)
                    val maskIdx = if (mask.size == pixels.size) idx
                    else {
                        val mx = (sx * maskW / sw).coerceIn(0, maskW - 1)
                        val my = (sy * maskH / sh).coerceIn(0, maskH - 1)
                        my * maskW + mx
                    }
                    if (maskIdx in mask.indices) mask[maskIdx] > 0.5f else false
                } ?: false

                val isSky = skyMask?.let { if (idx < it.size) it[idx] else false } ?: false

                val isFace = faceRegions.any { rect ->
                    val nx = x.toFloat() / width; val ny = y.toFloat() / height
                    nx in rect.left..rect.right && ny in rect.top..rect.bottom
                }

                when {
                    isFace -> { faceLumSum += lum; faceCount++ }
                    isSubject -> { subjectLumSum += lum; subjectCount++ }
                    isSky -> { skyLumSum += lum; skyCount++ }
                    else -> { bgLumSum += lum; bgCount++ }
                }
            }
        }

        val faceLum = if (faceCount > 0) faceLumSum / faceCount else -1f
        val subjectLum = if (subjectCount > 0) subjectLumSum / subjectCount else -1f
        val bgLum = if (bgCount > 0) bgLumSum / bgCount else 128f
        val skyLum = if (skyCount > 0) skyLumSum / skyCount else -1f

        // Split metering: blend face with sky to avoid blowing out highlights
        val targetLum = when {
            faceLum > 0 && skyLum > 220f -> faceLum * 0.4f + skyLum * 0.6f
            faceLum > 0 && skyLum > 0 -> faceLum * 0.6f + skyLum * 0.4f
            faceLum > 0 -> faceLum
            subjectLum > 0 -> subjectLum
            else -> (bgLum * 0.7f + 128f * 0.3f)
        }

        val idealLum = 118f
        val rawEv = if (targetLum > 10f) {
            val ratio = idealLum / targetLum
            (kotlin.math.ln(ratio.toDouble()) / kotlin.math.ln(2.0)).toFloat().coerceIn(-2f, 2f)
        } else 0f
        // Cap positive EV when sky is near clipping to protect highlights
        val evCompensation = if (skyLum > 220f && rawEv > 0.5f) 0.5f else rawEv

        Log.d(TAG, "Semantic metering: face=${"%.0f".format(faceLum)} subj=${"%.0f".format(subjectLum)} bg=${"%.0f".format(bgLum)} sky=${"%.0f".format(skyLum)} ev=${"%.2f".format(evCompensation)}")

        return MeteringResult(
            targetExposureCompensation = evCompensation,
            subjectLuminance = if (subjectLum > 0) subjectLum else bgLum,
            backgroundLuminance = bgLum,
            hasSkyHighlights = skyLum > 220f
        )
    }
}
