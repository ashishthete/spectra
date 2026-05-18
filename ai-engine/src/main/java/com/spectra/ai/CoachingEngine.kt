package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.ai.model.CompositionResult
import com.spectra.ai.model.CoachingAction
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId
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
    private var horizonDismissCount: Int = 0
    private var lastHorizonHintWasShown: Boolean = false

    fun generateCoaching(analysis: SceneAnalysis, preset: CameraPreset, composition: CompositionResult? = null, rollAngle: Float = 0f): CoachingHint? {
        if (!analysis.isStable) return null
        if (hintsShownThisSession > 20) return null

        val now = System.currentTimeMillis()
        if (now - lastDismissTimeMs < 15000L) return null
        if (lastHint != null && now - lastHintTimeMs < hintCooldownMs) return lastHint

        var hint = when {
            analysis.motionSource == MotionDetector.MotionSource.CAMERA_SHAKE ->
                cameraShakeHint(preset)
            analysis.motionSource == MotionDetector.MotionSource.SUBJECT_MOTION ->
                subjectMotionHint(analysis, preset)
            analysis.motionSource == MotionDetector.MotionSource.PANNING ->
                panMotionHint(preset)
            analysis.motionLevel == MotionLevel.FAST || analysis.motionLevel == MotionLevel.VERY_FAST ->
                motionHint(analysis, preset)
            analysis.lighting == LightingCondition.BACKLIT -> backlitHint(preset)
            analysis.lighting == LightingCondition.MIXED -> mixedLightingHint()
            else -> presetHint(preset, analysis)
        }

        // Composition/horizon coaching (lower priority than motion/backlit hints)
        if (hint == null && composition != null) {
            hint = compositionHint(composition)
        }

        // Horizon coaching from LevelSensor roll angle (lowest priority)
        if (hint == null) {
            val horizonHint = generateHorizonCoaching(rollAngle, horizonDismissCount)
            if (horizonHint != null) {
                hint = horizonHint
                lastHorizonHintWasShown = true
            } else {
                // User straightened the horizon — reset dismiss counter
                if (kotlin.math.abs(rollAngle) < 2f && horizonDismissCount > 0) {
                    horizonDismissCount = 0
                }
                lastHorizonHintWasShown = false
            }
        }

        if (hint != null && hint != lastHint) {
            lastHint = hint
            lastHintTimeMs = now
            hintsShownThisSession++
        }

        return hint
    }

    /**
     * Generate a coaching hint based on the device roll angle from LevelSensor.
     * Returns null if the horizon is level enough (<2 degrees) or if the user
     * has dismissed horizon coaching 3+ times (they want a dutch angle).
     */
    fun generateHorizonCoaching(rollAngle: Float, dismissCount: Int): CoachingHint? {
        if (dismissCount >= 3) return null  // user wants dutch angle
        val absRoll = kotlin.math.abs(rollAngle)
        if (absRoll < 2f) return null  // level enough
        val direction = if (rollAngle > 0) "left" else "right"
        val priority = if (absRoll > 5f) 6 else 4
        return CoachingHint(
            text = "Tilt $direction to level the horizon",
            arrow = if (rollAngle > 0) ArrowDirection.LEFT else ArrowDirection.RIGHT,
            priority = priority
        )
    }

    fun onDismissed() {
        if (lastHorizonHintWasShown) {
            horizonDismissCount++
        }
        lastDismissTimeMs = System.currentTimeMillis()
        lastHint = null
        lastHorizonHintWasShown = false
    }

    private fun motionHint(analysis: SceneAnalysis, preset: CameraPreset): CoachingHint {
        return when (preset) {
            CameraPreset.ACTION ->
                CoachingHint(
                    "Track the action — hold shutter for burst",
                    ArrowDirection.NONE,
                    priority = 8,
                    action = CoachingAction.EnableBurst
                )
            CameraPreset.MACRO ->
                CoachingHint("Too much movement for macro — stabilize first", ArrowDirection.STEADY, priority = 9)
            else ->
                CoachingHint(
                    "Movement detected — hold shutter for burst",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.EnableBurst
                )
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
                CoachingHint(
                    "Subject in motion — burst mode will capture the peak",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.EnableBurst
                )
            CameraPreset.PORTRAIT ->
                CoachingHint("Subject moving — ask them to hold still", ArrowDirection.NONE, priority = 7)
            else ->
                CoachingHint(
                    "Subject moving — switch to Action for burst",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.SwitchPreset(CameraPreset.ACTION)
                )
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
                CoachingHint(
                    "Low light — try Night mode for best results",
                    ArrowDirection.STEADY,
                    priority = 5,
                    action = CoachingAction.SwitchPreset(CameraPreset.NIGHT)
                )
            analysis.distanceRange == DistanceRange.MACRO || analysis.distanceRange == DistanceRange.NEAR ->
                autoMacroHint(analysis)
            analysis.sceneType == SceneType.LANDSCAPE ->
                CoachingHint(
                    "Landscape detected — try Landscape mode",
                    ArrowDirection.NONE,
                    priority = 4,
                    action = CoachingAction.SwitchPreset(CameraPreset.LANDSCAPE)
                )
            analysis.faceData.hasFaces ->
                CoachingHint(
                    "People detected — try Portrait mode",
                    ArrowDirection.NONE,
                    priority = 4,
                    action = CoachingAction.SwitchPreset(CameraPreset.PORTRAIT)
                )
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
                CoachingHint(
                    "Move closer — or try 3x telephoto",
                    ArrowDirection.NONE,
                    priority = 7,
                    action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
                )
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
                CoachingHint(
                    "Blue hour — try ultrawide to capture the full sky",
                    ArrowDirection.UP,
                    priority = 5,
                    action = CoachingAction.SwitchLens(LensId.ULTRAWIDE)
                )
            LightingCondition.HARSH_MIDDAY ->
                CoachingHint("Midday sun — look for shade or reflections", ArrowDirection.NONE, priority = 4)
            LightingCondition.OVERCAST ->
                CoachingHint(
                    "Overcast — ultrawide shows more sky and foreground",
                    ArrowDirection.NONE,
                    priority = 3,
                    action = CoachingAction.SwitchLens(LensId.ULTRAWIDE)
                )
            else -> CoachingHint(
                "Try ultrawide for a dramatic landscape perspective",
                ArrowDirection.NONE,
                priority = 3,
                action = CoachingAction.SwitchLens(LensId.ULTRAWIDE)
            )
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
                CoachingHint(
                    "Subject far — switch to 3x telephoto for more reach",
                    ArrowDirection.NONE,
                    priority = 5,
                    action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
                )
            else -> CoachingHint(
                "Hold shutter for burst — pick the best frame",
                ArrowDirection.NONE,
                priority = 4,
                action = CoachingAction.EnableBurst
            )
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

    private fun autoMacroHint(analysis: SceneAnalysis): CoachingHint? {
        if (analysis.distanceRange != DistanceRange.MACRO && analysis.distanceRange != DistanceRange.NEAR) return null
        return CoachingHint(
            "Subject is close — switch to Macro mode",
            ArrowDirection.NONE,
            priority = 6,
            action = CoachingAction.SwitchPreset(CameraPreset.MACRO)
        )
    }

    private fun mixedLightingHint(): CoachingHint {
        return CoachingHint(
            "Mixed lighting detected — WB matched to subject",
            ArrowDirection.NONE,
            priority = 5
        )
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

    /**
     * Generates a human-readable coaching string from a [CompositionSuggestion].
     * Returns null if the subject is already on thirds or close enough (< 0.12 normalized distance).
     */
    fun generateCompositionCoaching(suggestion: CompositionSuggestion): String? {
        if (suggestion.direction == CompositionSuggestion.Direction.ON_THIRDS) return null
        if (suggestion.distanceFromThirds < 0.12f) return null  // close enough

        val dirText = when (suggestion.direction) {
            CompositionSuggestion.Direction.LEFT -> "left"
            CompositionSuggestion.Direction.RIGHT -> "right"
            CompositionSuggestion.Direction.UP -> "up"
            CompositionSuggestion.Direction.DOWN -> "down"
            CompositionSuggestion.Direction.ON_THIRDS -> return null
        }
        return "Move camera $dirText to place subject on thirds"
    }

    fun reset() {
        lastHint = null
        lastHintTimeMs = 0L
        hintsShownThisSession = 0
        lastDismissTimeMs = 0L
        horizonDismissCount = 0
        lastHorizonHintWasShown = false
    }
}
