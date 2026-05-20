package com.spectra.camera

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

object CropSuggestionEngine {

    data class CropSuggestion(
        val rect: Rect,
        val aspectRatio: String,
        val reason: String,
        val score: Float
    )

    fun suggest(
        imageWidth: Int,
        imageHeight: Int,
        faceRects: List<RectF> = emptyList(),
        saliencyCenter: Pair<Float, Float>? = null
    ): List<CropSuggestion> {
        val suggestions = mutableListOf<CropSuggestion>()
        val focusX: Float
        val focusY: Float

        if (faceRects.isNotEmpty()) {
            val primary = faceRects.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }!!
            focusX = (primary.left + primary.right) / 2f
            focusY = (primary.top + primary.bottom) / 2f
        } else if (saliencyCenter != null) {
            focusX = saliencyCenter.first
            focusY = saliencyCenter.second
        } else {
            focusX = 0.5f
            focusY = 0.5f
        }

        val ratios = listOf(
            Pair(1f, 1f) to "1:1",
            Pair(4f, 5f) to "4:5",
            Pair(9f, 16f) to "9:16",
            Pair(16f, 9f) to "16:9",
            Pair(3f, 2f) to "3:2"
        )

        for ((ratio, label) in ratios) {
            val crop = computeCropForRatio(
                imageWidth, imageHeight, ratio.first / ratio.second, focusX, focusY
            )
            val score = scoreCrop(crop, imageWidth, imageHeight, faceRects, focusX, focusY)
            suggestions.add(CropSuggestion(crop, label, reasonForCrop(label, faceRects.isNotEmpty()), score))
        }

        suggestions.sortByDescending { it.score }
        return suggestions.take(3)
    }

    private fun computeCropForRatio(
        imgW: Int, imgH: Int,
        targetRatio: Float,
        focusX: Float, focusY: Float
    ): Rect {
        val imgRatio = imgW.toFloat() / imgH
        val cropW: Int
        val cropH: Int
        if (targetRatio > imgRatio) {
            cropW = imgW
            cropH = (imgW / targetRatio).roundToInt().coerceAtMost(imgH)
        } else {
            cropH = imgH
            cropW = (imgH * targetRatio).roundToInt().coerceAtMost(imgW)
        }

        val centerX = (focusX * imgW).roundToInt()
        val centerY = (focusY * imgH).roundToInt()
        val left = (centerX - cropW / 2).coerceIn(0, imgW - cropW)
        val top = (centerY - cropH / 2).coerceIn(0, imgH - cropH)

        return Rect(left, top, left + cropW, top + cropH)
    }

    private fun scoreCrop(
        crop: Rect, imgW: Int, imgH: Int,
        faces: List<RectF>, focusX: Float, focusY: Float
    ): Float {
        var score = 0f
        val cropArea = (crop.width().toLong() * crop.height()) / (imgW.toLong() * imgH).toFloat()
        score += cropArea * 0.3f

        val cropCx = (crop.left + crop.right) / 2f / imgW
        val cropCy = (crop.top + crop.bottom) / 2f / imgH
        val thirdX = listOf(1f / 3f, 2f / 3f)
        val thirdY = listOf(1f / 3f, 2f / 3f)
        val minThirdDist = thirdX.minOf { tx -> thirdY.minOf { ty ->
            val dx = focusX - tx; val dy = focusY - ty
            kotlin.math.sqrt(dx * dx + dy * dy)
        }}
        score += (1f - minThirdDist.coerceAtMost(0.5f) * 2f) * 0.3f

        if (faces.isNotEmpty()) {
            val allContained = faces.all { face ->
                face.left * imgW >= crop.left && face.right * imgW <= crop.right &&
                face.top * imgH >= crop.top && face.bottom * imgH <= crop.bottom
            }
            if (allContained) score += 0.4f
            else score += 0.1f
        } else {
            score += 0.2f
        }

        return score.coerceIn(0f, 1f)
    }

    private fun reasonForCrop(label: String, hasFaces: Boolean): String = when (label) {
        "1:1" -> if (hasFaces) "Square crop centered on face" else "Square crop for Instagram"
        "4:5" -> if (hasFaces) "Portrait crop for social media" else "4:5 vertical crop"
        "9:16" -> "Story/Reels format"
        "16:9" -> "Cinematic widescreen crop"
        "3:2" -> "Classic photo ratio"
        else -> "Suggested crop"
    }
}
