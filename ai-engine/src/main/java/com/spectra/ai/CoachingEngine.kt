package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.ai.model.CompositionResult
import com.spectra.core.model.CameraPreset
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

    fun generateCoaching(analysis: SceneAnalysis, preset: CameraPreset, composition: CompositionResult? = null): CoachingHint? {
        if (!analysis.isStable) return null
        if (hintsShownThisSession > 20) return null

        val now = System.currentTimeMillis()
        if (now - lastDismissTimeMs < 15000L) return null
        if (lastHint != null && now - lastHintTimeMs < hintCooldownMs) return lastHint

        var hint = when {
            analysis.motionType == MotionDetector.MotionType.CAMERA_SHAKE ->
                cameraShakeHint(preset)
            analysis.motionType == MotionDetector.MotionType.SUBJECT_MOTION ->
                subjectMotionHint(analysis, preset)
            analysis.motionType == MotionDetector.MotionType.PAN ->
                panMotionHint(preset)
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis, preset)
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(preset)
            else -> presetHint(preset, analysis)
        }

        // Composition/horizon coaching (lower priority than motion/backlit hints)
        if (hint == null && composition != null) {
            hint = compositionHint(composition)
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

    private fun motionHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.ACTION ->
                CoachingHint("Track the action — hold shutter for burst", ArrowDirection.NONE, priority = 8)
            CameraPreset.MACRO ->
                CoachingHint("Too much movement for macro — stabilize first", ArrowDirection.STEADY, priority = 9)
            else ->
                CoachingHint("Movement detected — hold shutter for burst", ArrowDirection.NONE, priority = 7)
        }
    }

    private fun cameraShakeHint(preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.MACRO ->
                CoachingHint("Camera shaking — brace your elbows or use a surface", ArrowDirection.STEADY, priority = 10)
            CameraPreset.NIGHT ->
                CoachingHint("Hands shaking — lean against something solid", ArrowDirection.STEADY, priority = 10)
            else ->
                CoachingHint("Hold steady — your hands are shaking", ArrowDirection.STEADY, priority = 8)
        }
    }

    private fun subjectMotionHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.ACTION ->
                CoachingHint("Subject in motion — burst mode will capture the peak", ArrowDirection.NONE, priority = 7)
            CameraPreset.PORTRAIT ->
                CoachingHint("Subject moving — ask them to hold still", ArrowDirection.NONE, priority = 7)
            else ->
                CoachingHint("Movement detected — hold shutter for burst", ArrowDirection.NONE, priority = 7)
        }
    }

    private fun panMotionHint(preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.ACTION ->
                CoachingHint("Nice panning — track the subject smoothly", ArrowDirection.NONE, priority = 5)
            CameraPreset.LANDSCAPE ->
                CoachingHint("Panning detected — smooth and steady for best results", ArrowDirection.STEADY, priority = 5)
            else ->
                CoachingHint("Panning — follow through smoothly for motion blur", ArrowDirection.NONE, priority = 5)
        }
    }

    private fun backlitHint(preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.PORTRAIT ->
                CoachingHint("Backlit subject — tap face to brighten", ArrowDirection.NONE, priority = 9)
            CameraPreset.LANDSCAPE ->
                CoachingHint("Great backlight — try a silhouette", ArrowDirection.NONE, priority = 5)
            else ->
                CoachingHint("Strong backlight — tap subject to brighten", ArrowDirection.NONE, priority = 9)
        }
    }

    private fun presetHint(preset: CameraPreset, analysis: SceneAnalysis): CoachingHint? {
        return when (preset) {
            CameraPreset.AUTO -> autoHint(analysis)
            CameraPreset.PORTRAIT -> portraitHint(analysis)
            CameraPreset.NIGHT -> nightHint(analysis)
            CameraPreset.FOOD -> foodHint(analysis)
            CameraPreset.LANDSCAPE -> landscapeHint(analysis)
            CameraPreset.ACTION -> actionHint(analysis)
            CameraPreset.MACRO -> macroHint(analysis)
            CameraPreset.PRO -> null
        }
    }

    private fun autoHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.lighting == LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Golden hour — look for long shadows and warm tones", ArrowDirection.NONE, priority = 4)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — hold steady for best results", ArrowDirection.STEADY, priority = 5)
            else -> null
        }
    }

    private fun portraitHint(analysis: SceneAnalysis): CoachingHint? {
        val faces = analysis.faceData
        return when {
            faces.faceCount == 0 ->
                CoachingHint("Position your subject in the frame", ArrowDirection.NONE, priority = 7)
            faces.anyBlinking ->
                CoachingHint("Eyes closed — try again", ArrowDirection.NONE, priority = 8)
            faces.isGroupShot && !faces.allEyesOpen ->
                CoachingHint("Check that everyone's eyes are open", ArrowDirection.NONE, priority = 6)
            faces.isGroupShot ->
                CoachingHint("Make sure no one is cut off at the edges", ArrowDirection.NONE, priority = 3)
            analysis.distanceRange == DistanceRange.FAR ->
                CoachingHint("Move closer — show head and shoulders", ArrowDirection.NONE, priority = 7)
            analysis.distanceRange == DistanceRange.MACRO || analysis.distanceRange == DistanceRange.NEAR ->
                CoachingHint("Step back slightly for a flattering perspective", ArrowDirection.NONE, priority = 6)
            analysis.lighting == LightingCondition.HARSH_MIDDAY ->
                CoachingHint("Harsh light — find open shade for softer look", ArrowDirection.NONE, priority = 5)
            analysis.lighting == LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Beautiful light — angle face toward the sun", ArrowDirection.NONE, priority = 4)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — have subject face the brightest source", ArrowDirection.NONE, priority = 6)
            faces.isCoupleShot ->
                CoachingHint("Get them close — touching shoulders looks natural", ArrowDirection.NONE, priority = 3)
            else -> CoachingHint("Tap the eyes to lock focus there", ArrowDirection.NONE, priority = 3)
        }
    }

    private fun foodHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.distanceRange == DistanceRange.FAR || analysis.distanceRange == DistanceRange.INFINITY ->
                CoachingHint("Get closer — fill the frame with the dish", ArrowDirection.NONE, priority = 6)
            analysis.lighting == LightingCondition.ARTIFICIAL ->
                CoachingHint("Natural window light makes food look best", ArrowDirection.NONE, priority = 5)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Find more light — shadows hide texture", ArrowDirection.NONE, priority = 6)
            analysis.distanceRange == DistanceRange.NEAR || analysis.distanceRange == DistanceRange.MACRO ->
                CoachingHint("Try overhead or 45° angle for best look", ArrowDirection.DOWN, priority = 4)
            else -> CoachingHint("Shoot from above or at a 45° angle", ArrowDirection.DOWN, priority = 3)
        }
    }

    private fun landscapeHint(analysis: SceneAnalysis): CoachingHint? {
        return when (analysis.lighting) {
            LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Golden hour — include foreground interest", ArrowDirection.DOWN, priority = 5)
            LightingCondition.BLUE_HOUR ->
                CoachingHint("Blue hour — include the sky gradient", ArrowDirection.UP, priority = 5)
            LightingCondition.HARSH_MIDDAY ->
                CoachingHint("Midday sun — look for shade or reflections", ArrowDirection.NONE, priority = 4)
            LightingCondition.OVERCAST ->
                CoachingHint("Overcast light — great for waterfalls and forests", ArrowDirection.NONE, priority = 3)
            else -> CoachingHint("Level the horizon — find a strong foreground", ArrowDirection.STEADY, priority = 3)
        }
    }

    private fun nightHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.motionLevel >= MotionLevel.MODERATE ->
                CoachingHint("Hold very still — long exposure active", ArrowDirection.STEADY, priority = 10)
            analysis.motionLevel == MotionLevel.SLOW ->
                CoachingHint("Lean against something solid for stability", ArrowDirection.STEADY, priority = 8)
            else -> null
        }
    }

    private fun actionHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — action may blur, find brighter area", ArrowDirection.NONE, priority = 7)
            analysis.distanceRange == DistanceRange.FAR || analysis.distanceRange == DistanceRange.INFINITY ->
                CoachingHint("Pre-focus where the action will happen", ArrowDirection.NONE, priority = 5)
            else -> CoachingHint("Hold shutter for burst — pick the best frame", ArrowDirection.NONE, priority = 4)
        }
    }

    private fun macroHint(analysis: SceneAnalysis): CoachingHint? {
        return when {
            analysis.motionLevel >= MotionLevel.SLOW ->
                CoachingHint("Hold your breath — tiny movements blur close-ups", ArrowDirection.STEADY, priority = 7)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Macro needs light — use a lamp or move to window", ArrowDirection.NONE, priority = 6)
            else -> CoachingHint("Get as close as possible — let autofocus lock", ArrowDirection.NONE, priority = 4)
        }
    }

    private fun compositionHint(composition: CompositionResult): CoachingHint? {
        if (composition.needsLeveling && composition.suggestionText != null) {
            return CoachingHint(composition.suggestionText, composition.suggestionArrow, priority = 6)
        }
        if (composition.thirdsScore < 0.5f && composition.suggestionText != null) {
            return CoachingHint(composition.suggestionText, composition.suggestionArrow, priority = 3)
        }
        return null
    }

    fun reset() {
        lastHint = null
        lastHintTimeMs = 0L
        hintsShownThisSession = 0
        lastDismissTimeMs = 0L
    }
}
