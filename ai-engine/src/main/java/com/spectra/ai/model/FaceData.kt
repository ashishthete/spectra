package com.spectra.ai.model

import android.graphics.RectF

data class FaceData(
    val faceCount: Int = 0,
    val faces: List<DetectedFace> = emptyList()
) {
    val hasFaces: Boolean get() = faceCount > 0
    val primaryFace: DetectedFace? get() = faces.maxByOrNull { it.bounds.width() * it.bounds.height() }

    val isGroupShot: Boolean get() = faceCount >= 3
    val isCoupleShot: Boolean get() = faceCount == 2
    val isSingleFace: Boolean get() = faceCount == 1

    val allEyesOpen: Boolean get() = faces.all { it.leftEyeOpenProbability > 0.5f && it.rightEyeOpenProbability > 0.5f }
    val anyoneSmiling: Boolean get() = faces.any { it.smilingProbability > 0.5f }
    val anyBlinking: Boolean get() = faces.any { it.leftEyeOpenProbability < 0.3f || it.rightEyeOpenProbability < 0.3f }

    companion object {
        val EMPTY = FaceData()
    }
}

data class DetectedFace(
    val bounds: RectF,
    val leftEyePosition: PointF? = null,
    val rightEyePosition: PointF? = null,
    val nosePosition: PointF? = null,
    val smilingProbability: Float = -1f,
    val leftEyeOpenProbability: Float = -1f,
    val rightEyeOpenProbability: Float = -1f,
    val headEulerAngleX: Float = 0f,
    val headEulerAngleY: Float = 0f,
    val headEulerAngleZ: Float = 0f,
    val trackingId: Int? = null,
    val skinToneShade: Int = 0,
    val skinToneAwbShiftK: Int = 0,
    val skinToneEvComp: Float = 0f
)

data class PointF(val x: Float, val y: Float)
