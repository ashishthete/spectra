package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoachingEngine @Inject constructor() {

    private var lastHintText: String? = null
    private var lastHintTimeMs: Long = 0L
    private val hintCooldownMs = 5000L

    fun generateCoaching(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        if (!analysis.isStable) return null

        val hint = when {
            mode == CameraMode.NIGHT -> nightModeHint(analysis)
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis)
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(analysis)
            else -> sceneHint(analysis, mode)
        }

        if (hint != null && hint.text == lastHintText &&
            System.currentTimeMillis() - lastHintTimeMs < hintCooldownMs) {
            return hint
        }

        if (hint != null) {
            lastHintText = hint.text
            lastHintTimeMs = System.currentTimeMillis()
        }

        return hint
    }

    private fun nightModeHint(analysis: SceneAnalysis): CoachingHint {
        return when {
            analysis.motionLevel >= MotionLevel.MODERATE ->
                CoachingHint("HOLD STEADY · LONG EXPOSURE ACTIVE", ArrowDirection.STEADY, priority = 10)
            analysis.motionLevel == MotionLevel.SLOW ->
                CoachingHint("STABILIZE · BRACE AGAINST SURFACE", ArrowDirection.STEADY, priority = 8)
            else ->
                CoachingHint("STEADY · CAPTURING LIGHT", ArrowDirection.STEADY, priority = 5)
        }
    }

    private fun motionHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.sceneType) {
            SceneType.PET ->
                CoachingHint("FAST SUBJECT · USE BURST MODE", ArrowDirection.NONE, priority = 8)
            SceneType.ACTION ->
                CoachingHint("PAN WITH SUBJECT · BURST RECOMMENDED", ArrowDirection.NONE, priority = 8)
            else ->
                CoachingHint("MOTION DETECTED · BURST RECOMMENDED", ArrowDirection.NONE, priority = 7)
        }
    }

    private fun backlitHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("BACKLIT · REPOSITION OR TAP SUBJECT", ArrowDirection.NONE, priority = 9)
    }

    private fun sceneHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        return when (analysis.sceneType) {
            SceneType.LANDSCAPE -> landscapeHint(analysis)
            SceneType.PORTRAIT -> portraitHint(analysis, mode)
            SceneType.FOOD -> foodHint(analysis)
            SceneType.ARCHITECTURE -> architectureHint(analysis)
            SceneType.MACRO -> macroHint(analysis)
            SceneType.PET -> petHint(analysis)
            SceneType.ACTION -> actionHint(analysis)
            SceneType.DOCUMENT -> documentHint(analysis)
            SceneType.INDOOR -> indoorHint(analysis)
            else -> null
        }
    }

    private fun landscapeHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.GOLDEN_HOUR ->
                CoachingHint("GOLDEN HOUR · HORIZON ON LOWER THIRD", ArrowDirection.DOWN, priority = 5)
            LightingCondition.BLUE_HOUR ->
                CoachingHint("BLUE HOUR · INCLUDE SKY GRADIENT", ArrowDirection.UP, priority = 5)
            LightingCondition.HARSH_MIDDAY ->
                CoachingHint("HARSH LIGHT · FIND SHADOWS OR WAIT", ArrowDirection.NONE, priority = 4)
            else ->
                CoachingHint("FIND LEADING LINES · RULE OF THIRDS", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun portraitHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint {
        return if (mode == CameraMode.PORT) {
            when (analysis.distanceRange) {
                DistanceRange.FAR ->
                    CoachingHint("MOVE CLOSER · FILL FRAME WITH SUBJECT", ArrowDirection.NONE, priority = 7)
                DistanceRange.NEAR, DistanceRange.MACRO ->
                    CoachingHint("STEP BACK · HEAD AND SHOULDERS", ArrowDirection.NONE, priority = 6)
                else ->
                    CoachingHint("FOCUS ON EYES · FACE THE LIGHT", ArrowDirection.NONE, priority = 4)
            }
        } else {
            CoachingHint("EYES IN FOCUS · NATURAL LIGHT", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun foodHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.distanceRange) {
            DistanceRange.MACRO, DistanceRange.NEAR ->
                CoachingHint("TRY 45° OR OVERHEAD ANGLE", ArrowDirection.DOWN, priority = 4)
            else ->
                CoachingHint("MOVE CLOSER · FILL THE FRAME", ArrowDirection.NONE, priority = 5)
        }
    }

    private fun architectureHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("STRAIGHTEN VERTICALS · FIND SYMMETRY", ArrowDirection.UP, priority = 4)
    }

    private fun macroHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("HOLD BREATH · MINIMIZE MOVEMENT", ArrowDirection.STEADY, priority = 6)
    }

    private fun petHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("EYE LEVEL WITH SUBJECT", ArrowDirection.DOWN, priority = 4)
    }

    private fun actionHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("PRE-FOCUS · PAN WITH SUBJECT", ArrowDirection.RIGHT, priority = 5)
    }

    private fun documentHint(analysis: SceneAnalysis): CoachingHint {
        return CoachingHint("SHOOT OVERHEAD · EVEN LIGHTING", ArrowDirection.DOWN, priority = 4)
    }

    private fun indoorHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.LOW_LIGHT ->
                CoachingHint("FIND WINDOW LIGHT · STABILIZE", ArrowDirection.STEADY, priority = 5)
            LightingCondition.ARTIFICIAL ->
                CoachingHint("CHECK WHITE BALANCE · WINDOW LIGHT BETTER", ArrowDirection.NONE, priority = 3)
            else ->
                CoachingHint("USE NATURAL LIGHT SOURCE", ArrowDirection.NONE, priority = 3)
        }
    }

    fun reset() {
        lastHintText = null
        lastHintTimeMs = 0L
    }
}
