package com.spectra.ai

import android.graphics.Bitmap
import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId
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
    private val decisionEngine: DecisionEngine,
    private val coachingEngine: CoachingEngine
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

    private val _coachingHint = MutableStateFlow<CoachingHint?>(null)
    val coachingHint: StateFlow<CoachingHint?> = _coachingHint.asStateFlow()

    private var initialized = false

    fun initialize() {
        if (initialized) return
        sceneClassifier.initialize()
        initialized = true
    }

    fun analyzeFrame(
        bitmap: Bitmap,
        mode: CameraMode = CameraMode.PHOTO,
        focusDistanceDiopters: Float = 0f,
        exposureTimeNs: Long = 0L,
        iso: Int = 100,
        colorTemperature: Int = 5500
    ) {
        val (sceneType, confidence) = sceneClassifier.classify(bitmap)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val avgBrightness = lightingAnalyzer.analyzeBrightness(pixels, bitmap.width, bitmap.height)
        val lighting = lightingAnalyzer.analyzeFromMetadata(avgBrightness, exposureTimeNs, iso, colorTemperature)

        motionDetector.addBitmap(bitmap)
        val motion = motionDetector.currentMotion

        val distance = distanceEstimator.estimateFromFocusDistance(focusDistanceDiopters)

        val sceneAnalysis = SceneAnalysis(
            sceneType = sceneType,
            confidence = confidence,
            lighting = lighting,
            motionLevel = motion,
            distanceRange = distance
        )
        _analysis.value = sceneAnalysis
        _coachingHint.value = coachingEngine.generateCoaching(sceneAnalysis, mode)

        if (sceneAnalysis.isStable) {
            _lensRecommendation.value = decisionEngine.recommendLens(sceneAnalysis)
            _settingsProfile.value = decisionEngine.optimizeSettings(sceneAnalysis)
        }
    }

    fun release() {
        sceneClassifier.release()
        motionDetector.reset()
        coachingEngine.reset()
        initialized = false
    }
}
