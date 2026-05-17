package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoachingEngine @Inject constructor() {

    private var lastHint: CoachingHint? = null
    private var lastHintTimeMs: Long = 0L
    private val hintCooldownMs = 10000L
    private var hintsShownThisSession = 0
    private var lastDismissTimeMs: Long = 0L

    fun generateCoaching(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        if (!analysis.isStable) return null
        if (hintsShownThisSession > 12) return null

        val now = System.currentTimeMillis()
        if (now - lastDismissTimeMs < 15000L) return null
        if (lastHint != null && now - lastHintTimeMs < hintCooldownMs) return lastHint

        val hint = when {
            mode == CameraMode.NIGHT -> nightHint(analysis)
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis)
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint()
            else -> sceneHint(analysis, mode)
        }

        if (hint != null && hint != lastHint) {
            lastHint = hint
            lastHintTimeMs = now
            hintsShownThisSession++
        }

        return hint
    }

    fun onDismissed() {
        lastDismissTimeMs = System.currentTimeMillis()
        lastHint = null
    }

    private fun nightHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.motionLevel >= MotionLevel.MODERATE ->
                CoachingHint("Hold very still — long exposure active", ArrowDirection.STEADY, priority = 10)
            analysis.motionLevel == MotionLevel.SLOW ->
                CoachingHint("Rest your phone against something solid", ArrowDirection.STEADY, priority = 8)
            else -> null
        }
    }

    private fun motionHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.sceneType) {
            SceneType.PET ->
                CoachingHint("Moving subject — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            SceneType.ACTION ->
                CoachingHint("Follow the action — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            else ->
                CoachingHint("Movement detected — hold shutter for burst", ArrowDirection.NONE, priority = 7)
        }
    }

    private fun backlitHint(): CoachingHint {
        return CoachingHint("Strong backlight — tap your subject to brighten it", ArrowDirection.NONE, priority = 9)
    }

    private fun sceneHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint? {
        return when (analysis.sceneType) {
            SceneType.LANDSCAPE -> landscapeHint(analysis)
            SceneType.PORTRAIT -> portraitHint(analysis, mode)
            SceneType.FOOD -> foodHint(analysis)
            SceneType.ARCHITECTURE -> architectureHint()
            SceneType.MACRO -> macroHint()
            SceneType.PET -> petHint()
            SceneType.ACTION -> actionHint()
            SceneType.DOCUMENT -> documentHint()
            SceneType.INDOOR -> indoorHint(analysis)
            else -> null
        }
    }

    private fun landscapeHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Beautiful light — tilt down to show more sky", ArrowDirection.DOWN, priority = 5)
            LightingCondition.BLUE_HOUR ->
                CoachingHint("Blue hour — include the sky gradient", ArrowDirection.UP, priority = 5)
            LightingCondition.HARSH_MIDDAY ->
                CoachingHint("Harsh sunlight — try finding a shaded area", ArrowDirection.NONE, priority = 4)
            else ->
                CoachingHint("Place your subject slightly off-center", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun portraitHint(analysis: SceneAnalysis, mode: CameraMode): CoachingHint {
        return if (mode == CameraMode.PORT) {
            when (analysis.distanceRange) {
                DistanceRange.FAR ->
                    CoachingHint("Take two steps closer to your subject", ArrowDirection.NONE, priority = 7)
                DistanceRange.NEAR, DistanceRange.MACRO ->
                    CoachingHint("Step back a little — show head and shoulders", ArrowDirection.NONE, priority = 6)
                else ->
                    CoachingHint("Tap the eyes to focus there", ArrowDirection.NONE, priority = 4)
            }
        } else {
            CoachingHint("Face your subject toward the light", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun foodHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.distanceRange) {
            DistanceRange.MACRO, DistanceRange.NEAR ->
                CoachingHint("Try shooting from above or at 45 degrees", ArrowDirection.DOWN, priority = 4)
            else ->
                CoachingHint("Get closer — fill the frame with the dish", ArrowDirection.NONE, priority = 5)
        }
    }

    private fun architectureHint(): CoachingHint {
        return CoachingHint("Keep your phone level — look for symmetry", ArrowDirection.STEADY, priority = 4)
    }

    private fun macroHint(): CoachingHint {
        return CoachingHint("Hold your breath — tiny movements blur close-ups", ArrowDirection.STEADY, priority = 6)
    }

    private fun petHint(): CoachingHint {
        return CoachingHint("Get down to their eye level", ArrowDirection.DOWN, priority = 4)
    }

    private fun actionHint(): CoachingHint {
        return CoachingHint("Tap where the action will happen, then hold shutter", ArrowDirection.NONE, priority = 5)
    }

    private fun documentHint(): CoachingHint {
        return CoachingHint("Hold directly overhead — make sure lighting is even", ArrowDirection.DOWN, priority = 4)
    }

    private fun indoorHint(analysis: SceneAnalysis): CoachingHint {
        return when (analysis.lighting) {
            LightingCondition.LOW_LIGHT ->
                CoachingHint("Move toward a window for better light", ArrowDirection.STEADY, priority = 5)
            LightingCondition.ARTIFICIAL ->
                CoachingHint("Window light gives more natural colors", ArrowDirection.NONE, priority = 3)
            else ->
                CoachingHint("Face your subject toward the light source", ArrowDirection.NONE, priority = 3)
        }
    }

    fun reset() {
        lastHint = null
        lastHintTimeMs = 0L
        hintsShownThisSession = 0
        lastDismissTimeMs = 0L
    }
}
