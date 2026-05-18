package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.cloud.CloudCoachingManager
import com.spectra.ai.model.*
import com.spectra.ai.model.CompositionResult
import com.spectra.ai.model.LightingCondition
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId
import com.spectra.core.model.PresetProfile
import com.spectra.core.model.ProcessingParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameAnalysisPipeline @Inject constructor(
    private val sceneClassifier: SceneClassifier,
    private val lightingAnalyzer: LightingAnalyzer,
    private val motionDetector: MotionDetector,
    private val distanceEstimator: DistanceEstimator,
    val decisionEngine: DecisionEngine,
    private val presetEngine: PresetEngine,
    private val coachingEngine: CoachingEngine,
    private val compositionAnalyzer: CompositionAnalyzer,
    val cloudCoachingManager: CloudCoachingManager,
    val faceDetector: FaceDetectorWrapper
) {
    private val _analysis = MutableStateFlow(SceneAnalysis())
    val analysis: StateFlow<SceneAnalysis> = _analysis.asStateFlow()

    private val _lensRecommendation = MutableStateFlow(
        LensRecommendation(LensId.MAIN, emptyMap(), "")
    )
    val lensRecommendation: StateFlow<LensRecommendation> = _lensRecommendation.asStateFlow()

    private val _settingsProfile = MutableStateFlow(
        SettingsProfile(CameraSettings(), "")
    )
    val settingsProfile: StateFlow<SettingsProfile> = _settingsProfile.asStateFlow()

    private val _presetProfile = MutableStateFlow(
        PresetProfile(
            preset = CameraPreset.PORTRAIT,
            settings = CameraSettings(),
            processing = ProcessingParams()
        )
    )
    val presetProfile: StateFlow<PresetProfile> = _presetProfile.asStateFlow()

    private val _coachingHint = MutableStateFlow<CoachingHint?>(null)
    val coachingHint: StateFlow<CoachingHint?> = _coachingHint.asStateFlow()

    private val _compositionResult = MutableStateFlow(CompositionResult())
    val compositionResult: StateFlow<CompositionResult> = _compositionResult.asStateFlow()

    val cloudCoachingHint: StateFlow<CoachingHint?> = cloudCoachingManager.cloudHint

    private var initialized = false

    fun initialize() {
        if (initialized) return
        sceneClassifier.initialize()
        initialized = true
    }

    fun analyzeFrame(
        bitmap: Bitmap,
        mode: CameraMode = CameraMode.PHOTO,
        preset: CameraPreset = CameraPreset.PORTRAIT,
        isFrontCamera: Boolean = false,
        focusDistanceDiopters: Float = 0f,
        exposureTimeNs: Long = 0L,
        iso: Int = 100,
        colorTemperature: Int = 5500,
        gyroAngularVelocity: Float = 0f,
        gyroConsistentFrames: Int = 0,
        rollAngleDegrees: Float = 0f
    ) {
        val currentFaceData = faceDetector.faceData.value
        val (sceneType, confidence) = sceneClassifier.classify(bitmap, isFrontCamera, currentFaceData)

        val analysisSize = 320
        val scale = analysisSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(bitmap, sW, sH, true)
        val pixels = IntArray(sW * sH)
        sample.getPixels(pixels, 0, sW, 0, 0, sW, sH)
        sample.recycle()

        val avgBrightness = lightingAnalyzer.analyzeBrightness(pixels, sW, sH)
        val estimatedCt = if (colorTemperature == 5500) {
            lightingAnalyzer.estimateColorTemperature(pixels)
        } else {
            colorTemperature
        }
        val lighting = lightingAnalyzer.analyzeFromMetadata(avgBrightness, exposureTimeNs, iso, estimatedCt, lightingAnalyzer.lastBrightnessVariance)

        val faceRectsForLighting = if (currentFaceData.hasFaces) {
            currentFaceData.primaryFace?.bounds
        } else null
        val mixedLighting = lightingAnalyzer.detectMixedLighting(pixels, sW, sH, faceRectsForLighting)
        val finalLighting = if (mixedLighting.isMixed) LightingCondition.MIXED else lighting

        motionDetector.addBitmap(bitmap)
        motionDetector.updateGyro(gyroAngularVelocity, gyroConsistentFrames)
        val motionSource = motionDetector.currentMotionSource
        val motion = motionDetector.currentMotion

        val distance = distanceEstimator.estimateFromFocusDistance(focusDistanceDiopters)

        val faceRects = if (currentFaceData.hasFaces) {
            currentFaceData.faces.map { it.bounds }
        } else {
            emptyList()
        }
        val compositionResult = compositionAnalyzer.analyze(
            pixels, sW, sH,
            faceRects, rollAngleDegrees
        )
        _compositionResult.value = compositionResult

        val sceneAnalysis = SceneAnalysis(
            sceneType = sceneType,
            confidence = confidence,
            lighting = finalLighting,
            motionLevel = motion,
            motionSource = motionSource,
            distanceRange = distance,
            faceData = currentFaceData
        )
        _analysis.value = sceneAnalysis

        if (sceneAnalysis.isStable) {
            _coachingHint.value = coachingEngine.generateCoaching(sceneAnalysis, preset, compositionResult, rollAngleDegrees)
        }

        if (sceneAnalysis.isActionable) {
            _lensRecommendation.value = decisionEngine.recommendLens(sceneAnalysis)

            if (preset != CameraPreset.PRO) {
                val profile = presetEngine.buildProfile(preset, sceneAnalysis)
                _presetProfile.value = profile
                _settingsProfile.value = SettingsProfile(
                    profile.settings,
                    "${preset.label} · ${sceneAnalysis.lighting.label}"
                )
            } else {
                _settingsProfile.value = decisionEngine.optimizeSettings(sceneAnalysis)
            }
        }

        val thumbSize = 32
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbSize, thumbSize, true)
        val thumbPixels = IntArray(thumbSize * thumbSize)
        thumb.getPixels(thumbPixels, 0, thumbSize, 0, 0, thumbSize, thumbSize)
        thumb.recycle()
        cloudCoachingManager.onFrameAnalyzed(thumbPixels, sceneAnalysis.sceneType.label, sceneAnalysis.lighting.label)
    }

    fun onCoachingDismissed() {
        coachingEngine.onDismissed()
        _coachingHint.value = null
    }

    fun release() {
        sceneClassifier.release()
        motionDetector.reset()
        coachingEngine.reset()
        cloudCoachingManager.reset()
        faceDetector.release()
        initialized = false
    }
}
