package com.spectra.ai.model

import kotlin.math.abs

data class CompositionResult(
    val subjectCentroidX: Float = 0.5f,
    val subjectCentroidY: Float = 0.5f,
    val thirdsScore: Float = 0f,
    val suggestionText: String? = null,
    val suggestionArrow: ArrowDirection = ArrowDirection.NONE,
    val horizonTiltDegrees: Float = 0f
) {
    val needsLeveling: Boolean get() = abs(horizonTiltDegrees) > 2f
}
