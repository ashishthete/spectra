package com.spectra.ai.model

import com.spectra.core.model.SceneType

data class SceneAnalysis(
    val sceneType: SceneType = SceneType.UNKNOWN,
    val confidence: Float = 0f,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
    val motionLevel: MotionLevel = MotionLevel.STATIC,
    val distanceRange: DistanceRange = DistanceRange.INFINITY,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isStable: Boolean get() = confidence >= 0.7f
}
