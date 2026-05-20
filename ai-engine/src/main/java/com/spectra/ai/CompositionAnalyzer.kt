package com.spectra.ai

import android.graphics.RectF
import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CompositionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

data class CompositionSuggestion(
    val direction: Direction,
    val distanceFromThirds: Float  // 0.0 = on thirds, 1.0 = centered
) {
    enum class Direction { LEFT, RIGHT, UP, DOWN, ON_THIRDS }
}

@Singleton
class CompositionAnalyzer @Inject constructor() {

    companion object {
        private const val SALIENCY_SIZE = 64
        private const val THIRDS_THRESHOLD = 0.15f
    }

    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        faceRects: List<RectF>,
        rollAngleDegrees: Float
    ): CompositionResult {
        val (cx, cy) = if (faceRects.isNotEmpty()) {
            val primary = faceRects.maxBy { it.width() * it.height() }
            val faceCx = (primary.left + primary.right) / 2f
            val faceCy = (primary.top + primary.bottom) / 2f
            Pair(faceCx, faceCy)
        } else {
            val saliency = computeSaliencyMap(pixels, width, height)
            findSaliencyPeak(saliency, SALIENCY_SIZE, SALIENCY_SIZE)
        }

        val thirdsScore = scoreThirdsPlacement(cx, cy)
        val horizonTilt = rollAngleDegrees
        val (text, arrow) = generateSuggestion(cx, cy, thirdsScore, horizonTilt)

        return CompositionResult(
            subjectCentroidX = cx,
            subjectCentroidY = cy,
            thirdsScore = thirdsScore,
            suggestionText = text,
            suggestionArrow = arrow,
            horizonTiltDegrees = horizonTilt
        )
    }

    fun computeSaliencyMap(pixels: IntArray, width: Int, height: Int): FloatArray {
        val s = SALIENCY_SIZE
        val saliency = FloatArray(s * s)

        val lum = FloatArray(s * s)
        val rg = FloatArray(s * s)
        val by = FloatArray(s * s)
        val scaleX = width.toFloat() / s
        val scaleY = height.toFloat() / s
        for (sy in 0 until s) {
            for (sx in 0 until s) {
                val srcX = (sx * scaleX).toInt().coerceIn(0, width - 1)
                val srcY = (sy * scaleY).toInt().coerceIn(0, height - 1)
                val pixel = pixels[srcY * width + srcX]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                lum[sy * s + sx] = 0.299f * r + 0.587f * g + 0.114f * b
                rg[sy * s + sx] = r - g
                by[sy * s + sx] = b - (r + g) / 2f
            }
        }

        val scales = intArrayOf(2, 4, 8)
        for (y in 0 until s) {
            for (x in 0 until s) {
                val idx = y * s + x
                val centerLum = lum[idx]
                val centerRg = rg[idx]
                val centerBy = by[idx]
                var totalContrast = 0f
                for (radius in scales) {
                    var sLum = 0f; var sRg = 0f; var sBy = 0f
                    var count = 0
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            if (dx == 0 && dy == 0) continue
                            if (abs(dx) < radius / 2 && abs(dy) < radius / 2) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until s && ny in 0 until s) {
                                val ni = ny * s + nx
                                sLum += lum[ni]; sRg += rg[ni]; sBy += by[ni]
                                count++
                            }
                        }
                    }
                    if (count > 0) {
                        val lumDiff = abs(centerLum - sLum / count)
                        val rgDiff = abs(centerRg - sRg / count)
                        val byDiff = abs(centerBy - sBy / count)
                        totalContrast += lumDiff + 0.5f * rgDiff + 0.5f * byDiff
                    }
                }
                saliency[idx] = totalContrast / scales.size
            }
        }

        return saliency
    }

    private fun findSaliencyPeak(saliency: FloatArray, w: Int, h: Int): Pair<Float, Float> {
        var maxVal = 0f
        var maxIdx = saliency.size / 2
        for (i in saliency.indices) {
            if (saliency[i] > maxVal) {
                maxVal = saliency[i]
                maxIdx = i
            }
        }
        val x = (maxIdx % w + 0.5f) / w
        val y = (maxIdx / w + 0.5f) / h
        return Pair(x, y)
    }

    fun scoreThirdsPlacement(cx: Float, cy: Float): Float {
        val thirds = listOf(
            Pair(1f / 3f, 1f / 3f),
            Pair(2f / 3f, 1f / 3f),
            Pair(1f / 3f, 2f / 3f),
            Pair(2f / 3f, 2f / 3f)
        )
        var minDist = Float.MAX_VALUE
        for ((tx, ty) in thirds) {
            val dx = cx - tx
            val dy = cy - ty
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < minDist) minDist = dist
        }
        val score = (1f - minDist / 0.47f).coerceIn(0f, 1f)
        return score
    }

    private fun generateSuggestion(
        cx: Float,
        cy: Float,
        thirdsScore: Float,
        horizonTilt: Float
    ): Pair<String?, ArrowDirection> {
        if (abs(horizonTilt) > 2f) {
            val direction = if (horizonTilt > 0) "right" else "left"
            val degrees = "%.0f".format(abs(horizonTilt))
            return Pair(
                "Level your horizon — tilted $degrees° $direction",
                ArrowDirection.STEADY
            )
        }

        if (thirdsScore >= 0.8f) {
            return Pair(null, ArrowDirection.NONE)
        }

        val isCenteredX = abs(cx - 0.5f) < 0.08f
        val isCenteredY = abs(cy - 0.5f) < 0.08f
        if (isCenteredX && isCenteredY) {
            return Pair(null, ArrowDirection.NONE)
        }

        val thirds = listOf(
            Pair(1f / 3f, 1f / 3f),
            Pair(2f / 3f, 1f / 3f),
            Pair(1f / 3f, 2f / 3f),
            Pair(2f / 3f, 2f / 3f)
        )
        var nearestThirdsX = 1f / 3f
        var nearestThirdsY = 1f / 3f
        var minDist = Float.MAX_VALUE
        for ((tx, ty) in thirds) {
            val dist = sqrt((cx - tx) * (cx - tx) + (cy - ty) * (cy - ty))
            if (dist < minDist) {
                minDist = dist
                nearestThirdsX = tx
                nearestThirdsY = ty
            }
        }

        if (minDist <= THIRDS_THRESHOLD) {
            return Pair(null, ArrowDirection.NONE)
        }

        val dx = nearestThirdsX - cx
        val dy = nearestThirdsY - cy

        val arrow = when {
            abs(dx) > abs(dy) && dx > 0 -> ArrowDirection.RIGHT
            abs(dx) > abs(dy) && dx < 0 -> ArrowDirection.LEFT
            dy > 0 -> ArrowDirection.DOWN
            dy < 0 -> ArrowDirection.UP
            else -> ArrowDirection.NONE
        }

        val directionText = when (arrow) {
            ArrowDirection.LEFT -> "left"
            ArrowDirection.RIGHT -> "right"
            ArrowDirection.UP -> "up"
            ArrowDirection.DOWN -> "down"
            else -> "off-center"
        }

        return Pair("Move subject slightly $directionText", arrow)
    }

    /**
     * Analyzes where the primary subject (face) is relative to rule-of-thirds
     * intersection points and returns a directional suggestion for the camera
     * to move. Face rects are expected in normalized [0,1] coordinates,
     * matching how [analyze] uses them.
     */
    fun analyzeSubjectPosition(faceRects: List<RectF>): CompositionSuggestion {
        if (faceRects.isEmpty()) {
            return CompositionSuggestion(CompositionSuggestion.Direction.ON_THIRDS, 0f)
        }

        val primaryFace = faceRects.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
            ?: return CompositionSuggestion(CompositionSuggestion.Direction.ON_THIRDS, 0f)

        val subjectX = (primaryFace.left + primaryFace.right) / 2f
        val subjectY = (primaryFace.top + primaryFace.bottom) / 2f

        // Nearest thirds intersection: (1/3,1/3), (1/3,2/3), (2/3,1/3), (2/3,2/3)
        val thirdsPoints = listOf(
            1f / 3f to 1f / 3f,
            1f / 3f to 2f / 3f,
            2f / 3f to 1f / 3f,
            2f / 3f to 2f / 3f
        )
        val nearest = thirdsPoints.minByOrNull { (tx, ty) ->
            val dx = subjectX - tx
            val dy = subjectY - ty
            dx * dx + dy * dy
        }!!

        val dx = subjectX - nearest.first
        val dy = subjectY - nearest.second
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < 0.08f) {
            return CompositionSuggestion(CompositionSuggestion.Direction.ON_THIRDS, distance)
        }

        // Direction the CAMERA should move (opposite to where the subject is offset)
        val direction = if (abs(dx) > abs(dy)) {
            if (dx > 0) CompositionSuggestion.Direction.LEFT else CompositionSuggestion.Direction.RIGHT
        } else {
            if (dy > 0) CompositionSuggestion.Direction.UP else CompositionSuggestion.Direction.DOWN
        }

        return CompositionSuggestion(direction, distance)
    }
}
