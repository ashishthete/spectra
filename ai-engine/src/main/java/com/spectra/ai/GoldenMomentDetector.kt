package com.spectra.ai

class GoldenMomentDetector {
    var isEnabled: Boolean = false
    private var lastTriggerTimeMs: Long = 0
    private val cooldownMs: Long = 3000

    data class QualityScore(
        val faceScore: Float = 0f,
        val compositionScore: Float = 0f,
        val stabilityScore: Float = 0f,
        val exposureScore: Float = 0f,
        val overall: Float = 0f
    )

    fun evaluate(
        faceSmileConfidence: Float,
        allEyesOpen: Boolean,
        faceCount: Int,
        compositionThirdsScore: Float,
        isStable: Boolean,
        stableDurationMs: Long,
        histogramData: IntArray,
        currentTimeMs: Long = System.currentTimeMillis()
    ): QualityScore {
        val hasFaces = faceCount > 0

        val faceScore = if (hasFaces) {
            val eyesBonus = if (allEyesOpen) 0.5f else 0f
            (faceSmileConfidence * 0.5f + eyesBonus).coerceIn(0f, 1f)
        } else 0f

        val compositionScore = compositionThirdsScore.coerceIn(0f, 1f)

        val stabilityScore = if (!isStable) 0f else
            (stableDurationMs / 500f).coerceIn(0f, 1f)

        val exposureScore = computeExposureScore(histogramData)

        val overall = if (hasFaces) {
            faceScore * 0.3f + compositionScore * 0.2f + stabilityScore * 0.3f + exposureScore * 0.2f
        } else {
            compositionScore * (0.2f / 0.7f) + stabilityScore * (0.3f / 0.7f) + exposureScore * (0.2f / 0.7f)
        }

        return QualityScore(
            faceScore = faceScore,
            compositionScore = compositionScore,
            stabilityScore = stabilityScore,
            exposureScore = exposureScore,
            overall = overall.coerceIn(0f, 1f)
        )
    }

    private fun computeExposureScore(histogram: IntArray): Float {
        if (histogram.isEmpty()) return 0.5f
        val total = histogram.sumOf { it.toLong() }.toFloat()
        if (total == 0f) return 0.5f

        val blown = histogram.takeLast(histogram.size - 250)
            .sumOf { it.toLong() }.toFloat()
        val crushed = histogram.take(6)
            .sumOf { it.toLong() }.toFloat()

        val blownRatio = blown / total
        val crushedRatio = crushed / total

        val penalty = (blownRatio * 2f + crushedRatio).coerceIn(0f, 1f)
        return (1f - penalty).coerceIn(0f, 1f)
    }

    fun shouldAutoCapture(score: QualityScore, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled) return false
        if (currentTimeMs - lastTriggerTimeMs < cooldownMs) return false
        if (score.overall >= 0.75f) {
            lastTriggerTimeMs = currentTimeMs
            return true
        }
        return false
    }

    fun reset() { lastTriggerTimeMs = 0 }
}
