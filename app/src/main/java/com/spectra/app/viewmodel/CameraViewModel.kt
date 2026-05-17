package com.spectra.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectra.ai.FrameAnalysisPipeline
import com.spectra.ai.model.PhotoTip
import com.spectra.ai.tips.TipsRepository
import com.spectra.camera.CaptureManager
import com.spectra.camera.FrameProvider
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val cameraController: SpectraCameraController,
    private val captureManager: CaptureManager,
    private val frameProvider: FrameProvider,
    private val pipeline: FrameAnalysisPipeline,
    private val tipsRepository: TipsRepository
) : ViewModel() {

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    private val _currentTip = MutableStateFlow<PhotoTip?>(null)
    val currentTip: StateFlow<PhotoTip?> = _currentTip.asStateFlow()

    private var lastStableScene: SceneType = SceneType.UNKNOWN
    private var stableSceneStartMs: Long = 0L

    init {
        pipeline.initialize()

        viewModelScope.launch {
            cameraController.isFrontCamera.collect { isFront ->
                _hudState.update { it.copy(isFrontCamera = isFront) }
            }
        }

        viewModelScope.launch {
            cameraController.activeLens.collect { lens ->
                _hudState.update { it.copy(
                    activeLens = lens,
                    lensMatchScores = LensId.entries.associateWith {
                        if (it == lens) 1.0f else 0f
                    }
                )}
            }
        }

        viewModelScope.launch {
            frameProvider.frames.collect { bitmap ->
                pipeline.analyzeFrame(bitmap, mode = _hudState.value.mode)
            }
        }

        viewModelScope.launch {
            pipeline.analysis.collect { analysis ->
                val showTips = checkTipsVisibility(analysis.sceneType, analysis.isStable)
                _hudState.update { it.copy(
                    sceneLabel = analysis.sceneType.label,
                    sceneConfidence = analysis.confidence,
                    lightingLabel = analysis.lighting.label,
                    motionLevel = analysis.motionLevel.barCount,
                    distanceLabel = analysis.distanceRange.label,
                    showTipsThumbnail = showTips
                )}
            }
        }

        viewModelScope.launch {
            pipeline.lensRecommendation.collect { rec ->
                if (_hudState.value.mode != CameraMode.PRO) {
                    cameraController.switchLens(rec.recommended)
                }
                _hudState.update { it.copy(lensMatchScores = rec.scores) }
            }
        }

        viewModelScope.launch {
            pipeline.settingsProfile.collect { profile ->
                _hudState.update { state ->
                    if (state.mode == CameraMode.PRO) {
                        state.copy(aiRecommendedSettings = profile.settings)
                    } else {
                        state.copy(
                            settings = profile.settings,
                            aiRecommendedSettings = profile.settings
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            pipeline.coachingHint.collect { hint ->
                _hudState.update { it.copy(
                    coachingText = hint?.text,
                    coachingArrow = hint?.arrow?.name ?: "NONE"
                )}
            }
        }

        viewModelScope.launch {
            pipeline.cloudCoachingHint.collect { hint ->
                _hudState.update { it.copy(
                    cloudCoachingText = hint?.text,
                    cloudCoachingArrow = hint?.arrow?.name ?: "NONE"
                )}
            }
        }

        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (pipeline.cloudCoachingManager.shouldQuery()) {
                    val currentFrame = frameProvider.latestFrame
                    if (currentFrame != null) {
                        pipeline.cloudCoachingManager.queryCloud(currentFrame)
                    }
                }
            }
        }
    }

    private fun checkTipsVisibility(scene: SceneType, isStable: Boolean): Boolean {
        if (!isStable || scene == SceneType.UNKNOWN) {
            lastStableScene = SceneType.UNKNOWN
            stableSceneStartMs = 0L
            return false
        }
        if (scene != lastStableScene) {
            lastStableScene = scene
            stableSceneStartMs = System.currentTimeMillis()
            _currentTip.value = tipsRepository.getTip(scene)
            return false
        }
        return System.currentTimeMillis() - stableSceneStartMs >= 3000L
    }

    fun cycleLens() { cameraController.cycleLens() }
    fun flipCamera() { cameraController.flipCamera() }
    fun switchLens(lens: LensId) { cameraController.switchLens(lens) }

    fun setMode(mode: CameraMode) {
        _hudState.update { it.copy(mode = mode) }
    }

    fun toggleHud() {
        _hudState.update { it.copy(isHudVisible = !it.isHudVisible) }
    }

    fun showReferenceCard() {
        _hudState.update { it.copy(showReferenceCard = true) }
    }

    fun dismissReferenceCard() {
        _hudState.update { it.copy(showReferenceCard = false) }
    }

    fun updateProSetting(
        iso: Int? = null,
        shutterSpeedDenominator: Int? = null,
        whiteBalanceKelvin: Int? = null,
        exposureCompensation: Float? = null,
        focusDistance: Float? = null
    ) {
        _hudState.update { state ->
            val current = state.settings
            val updated = CameraSettings.clamped(
                iso = iso ?: current.iso,
                shutterSpeedDenominator = shutterSpeedDenominator ?: current.shutterSpeedDenominator,
                whiteBalanceKelvin = whiteBalanceKelvin ?: current.whiteBalanceKelvin,
                exposureCompensation = exposureCompensation ?: current.exposureCompensation,
                focusDistance = focusDistance ?: current.focusDistance
            )
            val isOverride = updated != state.aiRecommendedSettings
            state.copy(settings = updated, isManualOverride = isOverride)
        }
    }

    fun snapToAiRecommendation() {
        _hudState.update { state ->
            state.copy(
                settings = state.aiRecommendedSettings,
                isManualOverride = false
            )
        }
    }

    fun capturePhoto() {
        val imageCapture = cameraController.getImageCapture() ?: return
        if (_captureInProgress.value) return

        viewModelScope.launch {
            _captureInProgress.value = true
            try {
                val uri = captureManager.capturePhoto(imageCapture)
                _hudState.update { it.copy(lastCapturedUri = uri) }
            } catch (_: Exception) {
            } finally {
                _captureInProgress.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.release()
        cameraController.release()
    }
}
