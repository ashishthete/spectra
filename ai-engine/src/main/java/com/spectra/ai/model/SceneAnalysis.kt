package com.spectra.ai.model

import com.spectra.ai.MotionDetector
import com.spectra.core.model.SceneType

data class SceneAnalysis(
    val sceneType: SceneType = SceneType.UNKNOWN,
    val confidence: Float = 0f,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
    val motionLevel: MotionLevel = MotionLevel.STATIC,
    val motionSource: MotionDetector.MotionSource = MotionDetector.MotionSource.STABLE,
    val distanceRange: DistanceRange = DistanceRange.INFINITY,
    val faceData: FaceData = FaceData.EMPTY,
    val ambientLux: Float = -1f,
    val semanticEvCompensation: Float = 0f,
    val hasSkyHighlights: Boolean = false,
    val highlightProtection: Float = 0f,
    val estimatedDuv: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isStable: Boolean get() = confidence >= 0.35f
    val isActionable: Boolean get() = confidence >= 0.70f
}
