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

    companion object {
        const val SHOOT_NOW_TEXT = "Shoot now"
    }

    private var lastHint: CoachingHint? = null
    private var lastHintTimeMs: Long = 0L
    private val hintCooldownMs = 10000L
    private var hintsShownThisSession = 0
    private var lastDismissTimeMs: Long = 0L
    private var horizonDismissCount: Int = 0
    private var lastHorizonHintWasShown: Boolean = false

    private val dismissedHintTypes = mutableMapOf<String, Int>()
    private val followedHints = mutableSetOf<String>()
    private var shotsThisSession = 0
    private var shotsFollowingCoaching = 0

    fun onCaptured(wasCoached: Boolean) {
        shotsThisSession++
        if (wasCoached) shotsFollowingCoaching++
    }

    private fun isHintFatigued(hintKey: String): Boolean {
        val dismissals = dismissedHintTypes[hintKey] ?: 0
        return dismissals >= 3
    }

    fun generateCoaching(analysis: SceneAnalysis, preset: CameraPreset, composition: CompositionResult? = null, rollAngle: Float = 0f): CoachingHint? {
        if (!analysis.isStable) return null
        if (hintsShownThisSession > 20) return null

        val now = System.currentTimeMillis()
        if (now - lastDismissTimeMs < 15000L) return null

        if (preset != CameraPreset.PRO && preset != CameraPreset.TRUE_SCENE) {
            val shootNow = isShootNowMoment(analysis)
            if (shootNow && (lastHint?.text != SHOOT_NOW_TEXT || now - lastHintTimeMs > 5000L)) {
                val shootHint = CoachingHint(SHOOT_NOW_TEXT, ArrowDirection.NONE, priority = 10)
                lastHint = shootHint
                lastHintTimeMs = now
                return shootHint
            }
        }

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

        if (lastHint != null && now - lastHintTimeMs < hintCooldownMs) {
            val newPriority = hint?.priority ?: -1
            if (newPriority > (lastHint?.priority ?: 0)) {
                // High-priority hint preempts stale lower-priority hint
            } else {
                return lastHint
            }
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

        if (hint != null) {
            val hintKey = hint.text.take(20)
            if (isHintFatigued(hintKey)) {
                hint = null
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
        val hintKey = lastHint?.text?.take(20) ?: ""
        if (hintKey.isNotEmpty()) {
            dismissedHintTypes[hintKey] = (dismissedHintTypes[hintKey] ?: 0) + 1
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
            CameraPreset.TRUE_SCENE -> null
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
        val primary = faces.primaryFace
        val faceFillRatio = if (primary != null) primary.bounds.width() * primary.bounds.height() else 0f
        return when {
            faces.faceCount == 0 ->
                CoachingHint("Position your subject in the frame", ArrowDirection.NONE, priority = 7)
            faces.anyBlinking ->
                CoachingHint("Eyes closed — try again", ArrowDirection.NONE, priority = 8)
            faceFillRatio > 0.15f && analysis.distanceRange == DistanceRange.NEAR ->
                CoachingHint(
                    "Step back and use 3x — face will look more natural",
                    ArrowDirection.NONE,
                    priority = 9,
                    action = CoachingAction.SwitchLens(LensId.TELEPHOTO_3X)
                )
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
            analysis.lighting == LightingCondition.BACKLIT ->
                CoachingHint("Backlit — turn subject toward the light or use fill", ArrowDirection.NONE, priority = 6)
            analysis.lighting == LightingCondition.GOLDEN_HOUR ->
                CoachingHint("Beautiful light — angle face toward the sun", ArrowDirection.NONE, priority = 4)
            analysis.lighting == LightingCondition.LOW_LIGHT ->
                CoachingHint("Low light — have subject face the brightest source", ArrowDirection.NONE, priority = 6)
            faces.isCoupleShot ->
                CoachingHint("Get them close — touching shoulders looks natural", ArrowDirection.NONE, priority = 3)
            analysis.distanceRange == DistanceRange.MID ->
                CoachingHint("Move subject away from background for stronger separation", ArrowDirection.NONE, priority = 3)
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

    private fun isShootNowMoment(analysis: SceneAnalysis): Boolean {
        if (!analysis.isStable) return false
        if (analysis.lighting == LightingCondition.BACKLIT || analysis.lighting == LightingCondition.MIXED) return false
        var score = 0f
        val faces = analysis.faceData

        score += when (analysis.motionLevel) {
            MotionLevel.STATIC -> 0.2f
            MotionLevel.SLOW -> 0.15f
            MotionLevel.MODERATE -> 0.05f
            else -> 0f
        }

        if (faces.hasFaces) {
            if (faces.allEyesOpen) score += 0.15f
            if (faces.anyoneSmiling) score += 0.15f
            else if (!faces.anyBlinking) score += 0.05f
        }

        score += when (analysis.lighting) {
            LightingCondition.GOLDEN_HOUR -> 0.2f
            LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.OVERCAST -> 0.15f
            LightingCondition.ARTIFICIAL, LightingCondition.BLUE_HOUR -> 0.1f
            LightingCondition.LOW_LIGHT -> 0.05f
            else -> 0.1f
        }

        score += (analysis.confidence * 0.3f).coerceAtMost(0.3f)

        val threshold = if (System.currentTimeMillis() - lastHintTimeMs > 10000L) 0.5f else 0.6f
        return score >= threshold
    }

    fun generateVideoCoaching(
        isRecording: Boolean,
        motionLevel: MotionLevel,
        motionSource: MotionDetector.MotionSource,
        hasFaces: Boolean,
        recordingDurationMs: Long
    ): CoachingHint? {
        if (!isRecording) return null

        return when {
            motionSource == MotionDetector.MotionSource.CAMERA_SHAKE && motionLevel >= MotionLevel.MODERATE ->
                CoachingHint("Walking shake — hold phone with both hands", ArrowDirection.STEADY, priority = 9)
            recordingDurationMs < 3000L ->
                CoachingHint("Hold 3 seconds before moving", ArrowDirection.NONE, priority = 6)
            motionSource == MotionDetector.MotionSource.PANNING && motionLevel >= MotionLevel.FAST ->
                CoachingHint("Pan slower — smooth motion looks cinematic", ArrowDirection.NONE, priority = 7)
            hasFaces && motionLevel == MotionLevel.STATIC ->
                CoachingHint("Subject steady — tap face to lock tracking", ArrowDirection.NONE, priority = 4)
            else -> null
        }
    }

    fun generateCompositionCoachingForPeople(
        faceCount: Int,
        primaryFaceBounds: android.graphics.RectF?,
        imageWidth: Float,
        imageHeight: Float
    ): CoachingHint? {
        if (faceCount == 0 || primaryFaceBounds == null) return null

        val faceCenterY = (primaryFaceBounds.top + primaryFaceBounds.bottom) / 2f
        val headroomRatio = primaryFaceBounds.top

        if (headroomRatio < 0.05f) {
            return CoachingHint("Too little headroom — lower the camera", ArrowDirection.DOWN, priority = 5)
        }
        if (headroomRatio > 0.35f) {
            return CoachingHint("Too much headroom — raise the camera", ArrowDirection.UP, priority = 4)
        }

        val faceBottom = primaryFaceBounds.bottom
        if (faceBottom > 0.9f) {
            return CoachingHint("Face too low — tilt up slightly", ArrowDirection.UP, priority = 5)
        }

        return null
    }

    fun reset() {
        lastHint = null
        lastHintTimeMs = 0L
        hintsShownThisSession = 0
        lastDismissTimeMs = 0L
        horizonDismissCount = 0
        lastHorizonHintWasShown = false
        dismissedHintTypes.clear()
        followedHints.clear()
        shotsThisSession = 0
        shotsFollowingCoaching = 0
    }
}
