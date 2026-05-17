package com.spectra.app.viewmodel

import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spectra.ai.FrameAnalysisPipeline
import com.spectra.ai.model.PhotoTip
import com.spectra.ai.tips.TipsRepository
import com.spectra.app.settings.SettingsStore
import com.spectra.camera.CaptureManager
import com.spectra.camera.FrameProvider
import com.spectra.camera.LevelSensor
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.AspectRatio
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.FlashMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val tipsRepository: TipsRepository,
    private val settingsStore: SettingsStore,
    private val levelSensor: LevelSensor
) : ViewModel() {

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    private val _currentTip = MutableStateFlow<PhotoTip?>(null)
    val currentTip: StateFlow<PhotoTip?> = _currentTip.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var lastStableScene: SceneType = SceneType.UNKNOWN
    private var stableSceneStartMs: Long = 0L
    private var burstJob: Job? = null
    private var timerJob: Job? = null
    private var lastFaceFocusMs: Long = 0L
    private var lastFaceFocusX: Float = -1f
    private var lastFaceFocusY: Float = -1f
    private var lastAppliedSettings: CameraSettings? = null
    private var reviewDismissJob: Job? = null

    init {
        pipeline.initialize()
        levelSensor.start()

        viewModelScope.launch {
            val mode = settingsStore.loadMode()
            val beauty = settingsStore.loadBeautyLevel()
            val proSettings = settingsStore.loadProSettings()
            _hudState.update { it.copy(
                mode = mode,
                beautyLevel = beauty,
                settings = proSettings,
                aiRecommendedSettings = proSettings
            )}
        }

        viewModelScope.launch {
            cameraController.isFrontCamera.collect { isFront ->
                val specs = if (isFront) {
                    cameraController.lensManager.getFrontCameraSpecs()
                } else {
                    cameraController.lensManager.getBackCameraSpecs(_hudState.value.activeLens)
                }
                _hudState.update { it.copy(
                    isFrontCamera = isFront,
                    cameraMegapixels = specs.megapixels,
                    cameraAperture = specs.aperture
                )}
            }
        }

        viewModelScope.launch {
            cameraController.activeLens.collect { lens ->
                val specs = cameraController.lensManager.getBackCameraSpecs(lens)
                _hudState.update { it.copy(
                    activeLens = lens,
                    cameraMegapixels = if (it.isFrontCamera) it.cameraMegapixels else specs.megapixels,
                    cameraAperture = if (it.isFrontCamera) it.cameraAperture else specs.aperture
                )}
            }
        }

        viewModelScope.launch {
            cameraController.sensorMetadata.collect { meta ->
                _hudState.update { it.copy(
                    actualIso = meta.iso,
                    actualShutterSpeedNs = meta.exposureTimeNs,
                    actualFocusDistance = meta.focusDistanceDiopters
                )}
            }
        }

        viewModelScope.launch {
            cameraController.zoomRatio.collect { zoom ->
                _hudState.update { it.copy(zoomRatio = zoom) }
            }
        }

        viewModelScope.launch {
            cameraController.maxZoomRatio.collect { max ->
                _hudState.update { it.copy(maxZoomRatio = max) }
            }
        }

        viewModelScope.launch {
            levelSensor.rollAngle.collect { angle ->
                _hudState.update { it.copy(levelAngle = angle) }
            }
        }

        viewModelScope.launch {
            levelSensor.pitchAngle.collect { angle ->
                _hudState.update { it.copy(pitchAngle = angle) }
            }
        }

        viewModelScope.launch {
            frameProvider.frames.collect { bitmap ->
                val meta = _hudState.value
                pipeline.faceDetector.detectFaces(bitmap, bitmap.width, bitmap.height)
                pipeline.analyzeFrame(
                    bitmap,
                    mode = meta.mode,
                    preset = meta.preset,
                    isFrontCamera = meta.isFrontCamera,
                    focusDistanceDiopters = meta.actualFocusDistance,
                    exposureTimeNs = meta.actualShutterSpeedNs,
                    iso = if (meta.actualIso > 0) meta.actualIso else 100,
                    colorTemperature = 5500
                )
            }
        }

        viewModelScope.launch {
            pipeline.faceDetector.faceData.collect { faces ->
                _hudState.update { it.copy(
                    faceCount = faces.faceCount,
                    anyoneSmiling = faces.anyoneSmiling,
                    allEyesOpen = faces.allEyesOpen,
                    anyBlinking = faces.anyBlinking
                )}
                if (faces.hasFaces && !_hudState.value.aeAfLocked && !_hudState.value.isFrontCamera) {
                    val primary = faces.primaryFace ?: return@collect
                    val focusX = primary.rightEyePosition?.x
                        ?: primary.leftEyePosition?.x
                        ?: (primary.bounds.left + primary.bounds.right) / 2f
                    val focusY = primary.rightEyePosition?.y
                        ?: primary.leftEyePosition?.y
                        ?: (primary.bounds.top + primary.bounds.bottom) / 2f

                    val now = System.currentTimeMillis()
                    val dx = kotlin.math.abs(focusX - lastFaceFocusX)
                    val dy = kotlin.math.abs(focusY - lastFaceFocusY)
                    val moved = dx > 0.08f || dy > 0.08f
                    if (moved && now - lastFaceFocusMs > 2000L) {
                        lastFaceFocusX = focusX
                        lastFaceFocusY = focusY
                        lastFaceFocusMs = now
                        cameraController.focusOnFace(focusX, focusY)
                    }
                }
            }
        }

        viewModelScope.launch {
            cameraController.aeAfLocked.collect { locked ->
                _hudState.update { it.copy(aeAfLocked = locked) }
            }
        }

        viewModelScope.launch {
            pipeline.analysis.collect { analysis ->
                checkTipsVisibility(analysis.sceneType, analysis.isStable)
                _hudState.update { it.copy(
                    sceneLabel = if (analysis.isStable) analysis.sceneType.label else "",
                    sceneConfidence = analysis.confidence,
                    lightingLabel = analysis.lighting.label,
                    motionLevel = analysis.motionLevel.barCount,
                    distanceLabel = analysis.distanceRange.label
                )}
            }
        }

        viewModelScope.launch {
            pipeline.lensRecommendation.collect { rec ->
                val active = _hudState.value.activeLens
                val isFront = _hudState.value.isFrontCamera
                val hint = if (!isFront && rec.recommended != active && rec.scores.getOrDefault(rec.recommended, 0f) > 0.7f) {
                    "Try ${rec.recommended.zoomLabel}"
                } else null
                _hudState.update { it.copy(
                    recommendedLens = rec.recommended,
                    lensMatchScores = rec.scores,
                    lensHint = hint
                )}
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
                if (_hudState.value.mode != CameraMode.PRO && profile.settings != lastAppliedSettings) {
                    lastAppliedSettings = profile.settings
                    applySettingsToHardware(profile.settings)
                }
            }
        }

        viewModelScope.launch {
            pipeline.presetProfile.collect { profile ->
                _hudState.update { it.copy(processing = profile.processing) }
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
                delay(500)
                if (pipeline.cloudCoachingManager.shouldQuery()) {
                    val currentFrame = frameProvider.latestFrame
                    if (currentFrame != null) {
                        pipeline.cloudCoachingManager.queryCloud(currentFrame)
                    }
                }
            }
        }
    }

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applySettingsToHardware(settings: CameraSettings, manual: Boolean = false) {
        try {
            cameraController.applySettings(settings, manual)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply camera settings", e)
        }
    }

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun resetHardwareToAuto() {
        try {
            cameraController.resetToAuto()
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to reset camera to auto", e)
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

    fun tapToFocus(pixelX: Float, pixelY: Float) {
        if (_hudState.value.aeAfLocked) {
            cameraController.unlockAeAf()
            return
        }
        _hudState.update { it.copy(
            isFocusing = true,
            focusX = pixelX,
            focusY = pixelY,
            focusSuccess = false
        )}
        cameraController.tapToFocus(pixelX, pixelY)
        viewModelScope.launch {
            delay(600)
            _hudState.update { it.copy(isFocusing = false, focusSuccess = true) }
        }
    }

    fun longPressToLock(pixelX: Float, pixelY: Float) {
        _hudState.update { it.copy(
            isFocusing = true,
            focusX = pixelX,
            focusY = pixelY,
            focusSuccess = false
        )}
        cameraController.lockAeAf(pixelX, pixelY)
        viewModelScope.launch {
            delay(600)
            _hudState.update { it.copy(isFocusing = false, focusSuccess = true) }
        }
    }

    fun setZoom(ratio: Float) {
        cameraController.setZoomRatio(ratio)
    }

    fun toggleFlash() {
        val current = _hudState.value.flashMode
        val next = when (current) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        _hudState.update { it.copy(flashMode = next) }
        val cameraFlash = when (next) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
        cameraController.setFlashMode(cameraFlash)
    }

    fun toggleTimer() {
        val current = _hudState.value.timerSeconds
        val next = when (current) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        _hudState.update { it.copy(timerSeconds = next) }
    }

    fun toggleAspectRatio() {
        val current = _hudState.value.aspectRatio
        val next = when (current) {
            AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_16_9
            AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_1_1
            AspectRatio.RATIO_1_1 -> AspectRatio.FULL
            AspectRatio.FULL -> AspectRatio.RATIO_4_3
        }
        _hudState.update { it.copy(aspectRatio = next) }
    }

    fun setPhotoStyle(style: com.spectra.core.model.PhotoStyle) {
        _hudState.update { it.copy(photoStyle = style) }
    }

    fun cycleBeauty() {
        _hudState.update { state ->
            val next = (state.beautyLevel + 1) % 4
            state.copy(beautyLevel = next)
        }
        viewModelScope.launch { settingsStore.saveBeautyLevel(_hudState.value.beautyLevel) }
    }

    fun cycleLens() { cameraController.cycleLens() }
    fun flipCamera() { cameraController.flipCamera() }
    fun switchLens(lens: LensId) { cameraController.switchLens(lens) }

    fun setMode(mode: CameraMode) {
        val previous = _hudState.value.mode
        _hudState.update { it.copy(mode = mode, isManualOverride = false) }
        if (mode == CameraMode.PRO && previous != CameraMode.PRO) {
            applySettingsToHardware(_hudState.value.settings, manual = true)
        } else if (mode != CameraMode.PRO && previous == CameraMode.PRO) {
            resetHardwareToAuto()
        }
        viewModelScope.launch { settingsStore.saveMode(mode) }
    }

    fun setPreset(preset: CameraPreset) {
        val previous = _hudState.value.preset
        if (preset == CameraPreset.PRO) {
            _hudState.update { it.copy(preset = preset, mode = CameraMode.PRO, isManualOverride = false) }
            applySettingsToHardware(_hudState.value.settings, manual = true)
        } else {
            val mode = when (preset) {
                CameraPreset.NIGHT -> CameraMode.NIGHT
                CameraPreset.PORTRAIT, CameraPreset.COUPLE, CameraPreset.SELFIE -> CameraMode.PORT
                else -> CameraMode.PHOTO
            }
            _hudState.update { it.copy(preset = preset, mode = mode, isManualOverride = false) }
            if (previous == CameraPreset.PRO) {
                resetHardwareToAuto()
            }
        }
        if (preset.isFrontCameraDefault && !_hudState.value.isFrontCamera) {
            cameraController.flipCamera()
        } else if (!preset.isFrontCameraDefault && _hudState.value.isFrontCamera && previous.isFrontCameraDefault) {
            cameraController.flipCamera()
        }
        viewModelScope.launch { settingsStore.saveMode(_hudState.value.mode) }
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

    fun dismissCoaching() {
        pipeline.onCoachingDismissed()
        _hudState.update { it.copy(
            coachingText = null,
            coachingArrow = "NONE",
            cloudCoachingText = null,
            cloudCoachingArrow = "NONE"
        )}
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
        if (_hudState.value.mode == CameraMode.PRO) {
            applySettingsToHardware(_hudState.value.settings, manual = true)
            viewModelScope.launch { settingsStore.saveProSettings(_hudState.value.settings) }
        }
    }

    fun snapToAiRecommendation() {
        _hudState.update { state ->
            state.copy(
                settings = state.aiRecommendedSettings,
                isManualOverride = false
            )
        }
        if (_hudState.value.mode == CameraMode.PRO) {
            applySettingsToHardware(_hudState.value.aiRecommendedSettings, manual = true)
        }
    }

    fun startBurst() {
        burstJob?.cancel()
        burstJob = viewModelScope.launch {
            _hudState.update { it.copy(isBurstActive = true) }
            while (true) {
                capturePhotoInternal()
                delay(200)
            }
        }
    }

    fun stopBurst() {
        burstJob?.cancel()
        burstJob = null
        _hudState.update { it.copy(isBurstActive = false) }
    }

    fun capturePhoto() {
        if (_captureInProgress.value) return

        val timer = _hudState.value.timerSeconds
        if (timer > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (i in timer downTo 1) {
                    _hudState.update { it.copy(timerCountdown = i) }
                    delay(1000)
                }
                _hudState.update { it.copy(timerCountdown = 0) }
                capturePhotoInternal()
            }
        } else {
            viewModelScope.launch { capturePhotoInternal() }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _hudState.update { it.copy(timerCountdown = 0) }
    }

    private suspend fun capturePhotoInternal() {
        val imageCapture = cameraController.getImageCapture()
        if (imageCapture == null) {
            _toastMessage.tryEmit("Camera not ready")
            return
        }
        if (_captureInProgress.value) return

        _captureInProgress.value = true
        _hudState.update { it.copy(showCaptureFlash = true) }
        try {
            val uri = captureManager.capturePhoto(imageCapture, _hudState.value.beautyLevel, _hudState.value.photoStyle, _hudState.value.isFrontCamera)
            _hudState.update { it.copy(
                lastCapturedUri = uri,
                showCaptureFlash = false,
                showReview = true,
                reviewUri = uri
            )}
            reviewDismissJob?.cancel()
            reviewDismissJob = viewModelScope.launch {
                delay(3000)
                dismissReview()
            }
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Capture failed", e)
            _toastMessage.tryEmit("Capture failed: ${e.message}")
            _hudState.update { it.copy(showCaptureFlash = false) }
        } finally {
            _captureInProgress.value = false
        }
    }

    fun dismissReview() {
        reviewDismissJob?.cancel()
        _hudState.update { it.copy(showReview = false) }
    }

    fun deleteReviewPhoto() {
        reviewDismissJob?.cancel()
        val uri = _hudState.value.reviewUri
        if (uri != null) {
            if (captureManager.deletePhoto(uri)) {
                _toastMessage.tryEmit("Photo deleted")
            } else {
                _toastMessage.tryEmit("Could not delete photo")
            }
        }
        _hudState.update { it.copy(showReview = false, reviewUri = null, lastCapturedUri = null) }
    }

    fun shareReviewPhoto() {
        reviewDismissJob?.cancel()
        _hudState.update { it.copy(showReview = false) }
    }

    override fun onCleared() {
        super.onCleared()
        burstJob?.cancel()
        timerJob?.cancel()
        reviewDismissJob?.cancel()
        levelSensor.stop()
        pipeline.release()
        cameraController.release()
    }
}
