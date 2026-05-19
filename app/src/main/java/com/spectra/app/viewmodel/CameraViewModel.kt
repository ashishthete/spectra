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
import com.spectra.camera.LocationProvider
import com.spectra.camera.SpectraCameraController
import com.spectra.core.model.AspectRatio
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.FlashMode
import com.spectra.core.model.CaptureExplanation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val cameraController: SpectraCameraController,
    private val captureManager: CaptureManager,
    private val frameProvider: FrameProvider,
    private val pipeline: FrameAnalysisPipeline,
    private val tipsRepository: TipsRepository,
    private val settingsStore: SettingsStore,
    private val levelSensor: LevelSensor,
    private val locationProvider: LocationProvider
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
    private var hdrFrameCounter = 0
    private var recordingTimerJob: Job? = null
    private var lensHintJob: Job? = null
    private var lastRecommendedLens: LensId? = null
    private var lastDetectedFaceRects: List<android.graphics.RectF> = emptyList()
    private var smartCaptureFrames: List<Pair<ByteArray, Int>>? = null
    private var lastFaceMeteringMs: Long = 0L
    private var lastSettingsAppliedMs: Long = 0L
    private var lastAppliedSemiAuto: Boolean = false

    init {
        pipeline.initialize()
        levelSensor.start()
        locationProvider.startUpdates()

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
                val state = _hudState.value
                if (meta.iso != state.actualIso ||
                    meta.exposureTimeNs != state.actualShutterSpeedNs ||
                    meta.focusDistanceDiopters != state.actualFocusDistance ||
                    meta.colorTemperatureK != state.actualColorTemperature) {
                    _hudState.update { it.copy(
                        actualIso = meta.iso,
                        actualShutterSpeedNs = meta.exposureTimeNs,
                        actualFocusDistance = meta.focusDistanceDiopters,
                        actualColorTemperature = meta.colorTemperatureK
                    )}
                }
            }
        }

        viewModelScope.launch {
            cameraController.zoomRatio.collect { zoom ->
                if (zoom != _hudState.value.zoomRatio) {
                    _hudState.update { it.copy(zoomRatio = zoom) }
                }
            }
        }

        viewModelScope.launch {
            cameraController.maxZoomRatio.collect { max ->
                if (max != _hudState.value.maxZoomRatio) {
                    _hudState.update { it.copy(maxZoomRatio = max) }
                }
            }
        }

        viewModelScope.launch {
            levelSensor.rollAngle.collect { angle ->
                if (kotlin.math.abs(angle - _hudState.value.levelAngle) > 0.5f) {
                    _hudState.update { it.copy(levelAngle = angle) }
                }
            }
        }

        viewModelScope.launch {
            levelSensor.pitchAngle.collect { angle ->
                if (kotlin.math.abs(angle - _hudState.value.pitchAngle) > 0.5f) {
                    _hudState.update { it.copy(pitchAngle = angle) }
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            frameProvider.frames.collect { bitmap ->
                val meta = _hudState.value
                try {
                    pipeline.faceDetector.detectFaces(bitmap, bitmap.width, bitmap.height)
                    pipeline.analyzeFrame(
                        bitmap,
                        mode = meta.mode,
                        preset = meta.preset,
                        isFrontCamera = meta.isFrontCamera,
                        focusDistanceDiopters = meta.actualFocusDistance,
                        exposureTimeNs = meta.actualShutterSpeedNs,
                        iso = if (meta.actualIso > 0) meta.actualIso else 100,
                        colorTemperature = if (meta.actualColorTemperature > 0) meta.actualColorTemperature else 5500,
                        rollAngleDegrees = meta.levelAngle
                    )
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Frame analysis failed", e)
                    return@collect
                }
                hdrFrameCounter++
                if (hdrFrameCounter % 30 == 0) {
                    val contrast = analyzeContrastFromBitmap(bitmap)
                    val hdrActive = contrast > 0.40f
                    val state = _hudState.value
                    if (hdrActive != state.isHdrActive || kotlin.math.abs(contrast - state.sceneContrast) > 0.02f) {
                        val needsHistogram = meta.mode == CameraMode.PRO || meta.showMiniHistogram
                        if (needsHistogram) {
                            val hist = com.spectra.app.ui.pro.computeHistogram(bitmap)
                            _hudState.update { it.copy(isHdrActive = hdrActive, sceneContrast = contrast, histogramData = hist) }
                        } else {
                            _hudState.update { it.copy(isHdrActive = hdrActive, sceneContrast = contrast) }
                        }
                    }
                }
                if (meta.mode == CameraMode.PRO && (meta.focusPeakingEnabled || meta.zebraEnabled)) {
                    computeProOverlays(bitmap, meta.focusPeakingEnabled, meta.zebraEnabled)
                }
            }
        }

        viewModelScope.launch {
            pipeline.faceDetector.faceData.collect { faces ->
                lastDetectedFaceRects = faces.faces.map { it.bounds }
                val state = _hudState.value
                if (faces.faceCount != state.faceCount ||
                    faces.anyoneSmiling != state.anyoneSmiling ||
                    faces.allEyesOpen != state.allEyesOpen ||
                    faces.anyBlinking != state.anyBlinking) {
                    _hudState.update { it.copy(
                        faceCount = faces.faceCount,
                        anyoneSmiling = faces.anyoneSmiling,
                        allEyesOpen = faces.allEyesOpen,
                        anyBlinking = faces.anyBlinking
                    )}
                }
                val now = System.currentTimeMillis()
                if (faces.hasFaces && !_hudState.value.aeAfLocked && !_hudState.value.isFrontCamera && now - lastFaceMeteringMs > 2000L) {
                    lastFaceMeteringMs = now
                    cameraController.applyFaceMetering(faces.faces.map { it.bounds })

                    val primary = faces.primaryFace ?: return@collect
                    val focusX = primary.rightEyePosition?.x
                        ?: primary.leftEyePosition?.x
                        ?: (primary.bounds.left + primary.bounds.right) / 2f
                    val focusY = primary.rightEyePosition?.y
                        ?: primary.leftEyePosition?.y
                        ?: (primary.bounds.top + primary.bounds.bottom) / 2f

                    val dx = kotlin.math.abs(focusX - lastFaceFocusX)
                    val dy = kotlin.math.abs(focusY - lastFaceFocusY)
                    val moved = dx > 0.08f || dy > 0.08f
                    if (moved && now - lastFaceFocusMs > 3000L) {
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
                val newLabel = if (analysis.isStable) analysis.sceneType.label else ""
                val state = _hudState.value
                if (newLabel != state.sceneLabel ||
                    analysis.confidence != state.sceneConfidence ||
                    analysis.lighting.label != state.lightingLabel ||
                    analysis.motionLevel.barCount != state.motionLevel ||
                    analysis.distanceRange.label != state.distanceLabel) {
                    _hudState.update { it.copy(
                        sceneLabel = newLabel,
                        sceneConfidence = analysis.confidence,
                        lightingLabel = analysis.lighting.label,
                        motionLevel = analysis.motionLevel.barCount,
                        distanceLabel = analysis.distanceRange.label
                    )}
                }
            }
        }

        viewModelScope.launch {
            pipeline.lensRecommendation.collect { rec ->
                val state = _hudState.value
                val active = state.activeLens
                val isFront = state.isFrontCamera
                val changed = rec.recommended != lastRecommendedLens
                lastRecommendedLens = rec.recommended
                val hint = if (!isFront && changed && rec.recommended != active && rec.scores.getOrDefault(rec.recommended, 0f) > 0.85f) {
                    "Try ${rec.recommended.zoomLabel}"
                } else null
                if (rec.recommended != state.recommendedLens ||
                    rec.scores != state.lensMatchScores ||
                    hint != null) {
                    _hudState.update { it.copy(
                        recommendedLens = rec.recommended,
                        lensMatchScores = rec.scores,
                        lensHint = if (hint != null) hint else it.lensHint
                    )}
                }
                if (hint != null) {
                    lensHintJob?.cancel()
                    lensHintJob = viewModelScope.launch {
                        delay(4000)
                        _hudState.update { it.copy(lensHint = null) }
                    }
                }
            }
        }

        viewModelScope.launch {
            pipeline.settingsProfile.collect { profile ->
                val state = _hudState.value
                val needsUpdate = if (state.mode == CameraMode.PRO) {
                    profile.settings != state.aiRecommendedSettings
                } else {
                    profile.settings != state.settings || profile.settings != state.aiRecommendedSettings
                }
                if (needsUpdate) {
                    _hudState.update { s ->
                        if (s.mode == CameraMode.PRO) {
                            s.copy(aiRecommendedSettings = profile.settings)
                        } else {
                            s.copy(
                                settings = profile.settings,
                                aiRecommendedSettings = profile.settings
                            )
                        }
                    }
                }
                if (_hudState.value.mode != CameraMode.PRO && profile.settings != lastAppliedSettings) {
                    val now = System.currentTimeMillis()
                    val timeSinceLastApply = now - lastSettingsAppliedMs
                    if (timeSinceLastApply < 1500L) {
                        return@collect
                    }
                    lastAppliedSettings = profile.settings
                    lastSettingsAppliedMs = now
                    applySettingsToHardware(profile.settings, semiAuto = false)
                }
            }
        }

        viewModelScope.launch {
            pipeline.presetProfile.collect { profile ->
                if (profile.processing != _hudState.value.processing) {
                    _hudState.update { it.copy(processing = profile.processing) }
                }
            }
        }

        viewModelScope.launch {
            pipeline.coachingHint.collect { hint ->
                val newText = hint?.text
                val newArrow = hint?.arrow?.name ?: "NONE"
                val newActionLabel = hint?.action?.label
                val newActionType = when (hint?.action) {
                    is com.spectra.ai.model.CoachingAction.SwitchLens -> "SWITCH_LENS"
                    is com.spectra.ai.model.CoachingAction.EnableBurst -> "ENABLE_BURST"
                    is com.spectra.ai.model.CoachingAction.SwitchPreset -> "SWITCH_PRESET"
                    null -> null
                }
                val newPayload = when (val a = hint?.action) {
                    is com.spectra.ai.model.CoachingAction.SwitchLens -> a.lensId.name
                    is com.spectra.ai.model.CoachingAction.SwitchPreset -> a.preset.name
                    is com.spectra.ai.model.CoachingAction.EnableBurst -> null
                    null -> null
                }
                val state = _hudState.value
                if (newText != state.coachingText ||
                    newArrow != state.coachingArrow ||
                    newActionLabel != state.coachingActionLabel ||
                    newActionType != state.coachingActionType ||
                    newPayload != state.coachingActionPayload) {
                    _hudState.update { it.copy(
                        coachingText = newText,
                        coachingArrow = newArrow,
                        coachingActionLabel = newActionLabel,
                        coachingActionType = newActionType,
                        coachingActionPayload = newPayload
                    )}
                }
            }
        }

        viewModelScope.launch {
            pipeline.cloudCoachingHint.collect { hint ->
                val newText = hint?.text
                val newArrow = hint?.arrow?.name ?: "NONE"
                val state = _hudState.value
                if (newText != state.cloudCoachingText || newArrow != state.cloudCoachingArrow) {
                    _hudState.update { it.copy(
                        cloudCoachingText = newText,
                        cloudCoachingArrow = newArrow
                    )}
                }
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

        viewModelScope.launch {
            cameraController.videoEvent.collect { event ->
                when (event) {
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        val uri = event.outputResults.outputUri.toString()
                        _hudState.update { it.copy(isRecording = false, recordingDurationMs = 0L, lastCapturedUri = uri) }
                        recordingTimerJob?.cancel()
                        if (event.hasError()) {
                            _toastMessage.tryEmit("Recording failed")
                        } else {
                            _toastMessage.tryEmit("Video saved")
                        }
                    }
                    is androidx.camera.video.VideoRecordEvent.Status -> {
                        val durationNs = event.recordingStats.recordedDurationNanos
                        _hudState.update { it.copy(recordingDurationMs = durationNs / 1_000_000) }
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            captureManager.initDepthModel()
        }
    }


    private fun applySettingsToHardware(settings: CameraSettings, manual: Boolean = false, semiAuto: Boolean = false) {
        try {
            val motionLevel = _hudState.value.motionLevel
            cameraController.applySettings(settings, manual, motionLevel, semiAuto)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply camera settings", e)
        }
    }


    private fun resetHardwareToAuto() {
        try {
            cameraController.resetToAuto()
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to reset camera to auto", e)
        }
    }

    fun toggleSettingsDisplayMode() {
        val current = _hudState.value.settingsDisplayMode
        if (_hudState.value.mode == CameraMode.PRO) return
        val next = when (current) {
            com.spectra.core.model.SettingsDisplayMode.ACTUAL -> com.spectra.core.model.SettingsDisplayMode.SMART_AUTO
            com.spectra.core.model.SettingsDisplayMode.SMART_AUTO -> com.spectra.core.model.SettingsDisplayMode.ACTUAL
            com.spectra.core.model.SettingsDisplayMode.MANUAL -> com.spectra.core.model.SettingsDisplayMode.ACTUAL
        }
        _hudState.update { it.copy(settingsDisplayMode = next) }
        if (next == com.spectra.core.model.SettingsDisplayMode.ACTUAL) {
            resetHardwareToAuto()
        } else {
            val settings = _hudState.value.aiRecommendedSettings
            applySettingsToHardware(settings)
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
        if (previous == CameraMode.VIDEO && _hudState.value.isRecording) {
            stopRecording()
        }
        _hudState.update { it.copy(mode = mode, isManualOverride = false) }
        if (mode == CameraMode.VIDEO && previous != CameraMode.VIDEO) {
            cameraController.bindForVideo()
        } else if (mode != CameraMode.VIDEO && previous == CameraMode.VIDEO) {
            cameraController.rebindCamera()
        }
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
            val state = _hudState.value
            val actualSettings = CameraSettings.clamped(
                iso = if (state.actualIso > 0) state.actualIso else 400,
                shutterSpeedDenominator = if (state.actualShutterSpeedNs > 0)
                    (1_000_000_000L / state.actualShutterSpeedNs).toInt().coerceIn(1, 8000) else 60,
                whiteBalanceKelvin = 5500,
                exposureCompensation = 0f,
                focusDistance = 0f
            )
            _hudState.update { it.copy(
                preset = preset,
                mode = CameraMode.PRO,
                isManualOverride = false,
                settings = actualSettings,
                aiRecommendedSettings = actualSettings,
                settingsDisplayMode = com.spectra.core.model.SettingsDisplayMode.MANUAL
            ) }
            applySettingsToHardware(actualSettings, manual = true)
        } else {
            val mode = when (preset) {
                CameraPreset.NIGHT -> CameraMode.NIGHT
                CameraPreset.PORTRAIT -> CameraMode.PORT
                else -> CameraMode.PHOTO
            }
            _hudState.update { it.copy(
                preset = preset,
                mode = mode,
                isManualOverride = false,
                settingsDisplayMode = com.spectra.core.model.SettingsDisplayMode.ACTUAL
            ) }
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

    fun toggleMiniHistogram() {
        _hudState.update { it.copy(showMiniHistogram = !it.showMiniHistogram) }
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

    fun executeCoachingAction() {
        val state = _hudState.value
        val type = state.coachingActionType ?: return
        val payload = state.coachingActionPayload

        when (type) {
            "SWITCH_LENS" -> {
                val lens = payload?.let { name ->
                    try { LensId.valueOf(name) } catch (_: Exception) { null }
                }
                if (lens != null) switchLens(lens)
            }
            "ENABLE_BURST" -> startBurst()
            "SWITCH_PRESET" -> {
                val preset = payload?.let { name ->
                    try { CameraPreset.valueOf(name) } catch (_: Exception) { null }
                }
                if (preset != null) setPreset(preset)
            }
        }
        dismissCoaching()
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
        _hudState.update { it.copy(showCaptureFlash = true, isCapturing = true) }
        val explanation = buildCaptureExplanation()
        viewModelScope.launch {
            delay(120)
            _hudState.update { it.copy(showCaptureFlash = false) }
        }
        try {
            val state = _hudState.value
            val isHdr = false // HDR bracket produces dark/green results — disabled until debugged
            if (isHdr) {
                try {
                    Log.d("CameraViewModel", "HDR capture path")
                    val evBias = com.spectra.camera.HdrProcessor.computeHighlightEvBias(state.sceneContrast)
                    val hdrUri = captureManager.captureHdrBracket(
                        imageCapture,
                        baseExposureNs = state.actualShutterSpeedNs,
                        baseIso = state.actualIso,
                        applyBracketSettings = { exposureNs, iso ->
                            cameraController.applyBracketExposure(exposureNs, iso)
                        },
                        restoreAutoExposure = {
                            resetHardwareToAuto()
                        },
                        beautyLevel = state.beautyLevel,
                        style = state.photoStyle,
                        isFrontCamera = state.isFrontCamera,
                        faceRects = lastDetectedFaceRects,
                        isPortraitMode = state.mode == CameraMode.PORT,
                        evBias = evBias
                    )
                    _hudState.update { it.copy(
                        lastCapturedUri = hdrUri,
                        showCaptureFlash = false,
                        isCapturing = false,
                        showSmartReview = true,
                        bestOriginalUri = hdrUri,
                        aiEnhancedUri = null,
                        isEnhancing = true,
                        captureExplanation = explanation,
                        showAiExplainer = false
                    )}
                    val originalUri = hdrUri
                    Log.d("CameraViewModel", "HDR saved: $originalUri, starting enhancement...")
                    viewModelScope.launch {
                        System.gc()
                        try {
                            val processedUri = captureManager.saveProcessedCopy(
                                originalUri,
                                state.beautyLevel,
                                state.photoStyle,
                                state.isFrontCamera,
                                true,
                                lastDetectedFaceRects,
                                isPortraitMode = state.mode == CameraMode.PORT,
                                processing = state.processing,
                                sceneContrast = state.sceneContrast,
                                captureIso = state.actualIso,
                                preset = state.preset.name
                            )
                            Log.d("CameraViewModel", "HDR Enhancement done (${state.preset.name}): $processedUri")
                            _hudState.update { it.copy(
                                aiEnhancedUri = processedUri,
                                isEnhancing = false
                            )}
                        } catch (e: Exception) {
                            Log.w("CameraViewModel", "HDR processed copy failed", e)
                            _hudState.update { it.copy(aiEnhancedUri = originalUri, isEnhancing = false) }
                        } catch (oom: OutOfMemoryError) {
                            Log.e("CameraViewModel", "HDR OOM during processing", oom)
                            System.gc()
                            _hudState.update { it.copy(aiEnhancedUri = originalUri, isEnhancing = false) }
                        }
                    }
                    _captureInProgress.value = false
                    return
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "HDR bracket failed, falling back to normal capture", e)
                } catch (oom: OutOfMemoryError) {
                    Log.w("CameraViewModel", "HDR OOM, falling back to normal capture")
                    System.gc()
                }
            }

            Log.d("CameraViewModel", "Normal capture path (non-HDR)")
            val result = captureManager.captureSmartPhoto(
                imageCapture,
                state.beautyLevel,
                state.photoStyle,
                state.isFrontCamera,
                state.isHdrActive,
                lastDetectedFaceRects,
                processing = state.processing,
                currentIso = state.actualIso
            )

            if (state.settings.captureRaw && result.allFrames.isNotEmpty()) {
                viewModelScope.launch {
                    try {
                        val best = result.allFrames.maxBy { (jpeg, _) -> jpeg.size }
                        captureManager.saveRawCopy(best.first, best.second)
                    } catch (e: Exception) {
                        Log.w("CameraViewModel", "RAW save failed", e)
                    }
                }
            }

            _hudState.update { it.copy(
                lastCapturedUri = result.bestOriginalUri,
                showCaptureFlash = false,
                isCapturing = false,
                showSmartReview = true,
                bestOriginalUri = result.bestOriginalUri,
                aiEnhancedUri = null,
                isEnhancing = true,
                captureExplanation = explanation,
                showAiExplainer = false
            )}

            viewModelScope.launch {
                delay(1000)
                _hudState.update { it.copy(showAiExplainer = true) }
                delay(5000)
                _hudState.update { it.copy(showAiExplainer = false) }
            }

            val originalUri = result.bestOriginalUri
            Log.d("CameraViewModel", "Original saved: $originalUri, starting enhancement...")
            viewModelScope.launch {
                System.gc()
                try {
                    val processedUri = captureManager.saveProcessedCopy(
                        originalUri,
                        state.beautyLevel,
                        state.photoStyle,
                        state.isFrontCamera,
                        state.isHdrActive,
                        lastDetectedFaceRects,
                        isPortraitMode = state.mode == CameraMode.PORT,
                        processing = state.processing,
                        sceneContrast = state.sceneContrast,
                        captureIso = state.actualIso,
                        preset = state.preset.name
                    )
                    Log.d("CameraViewModel", "Enhancement done (${state.preset.name}): $processedUri")
                    _hudState.update { it.copy(
                        aiEnhancedUri = processedUri,
                        isEnhancing = false
                    )}
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Processed copy failed", e)
                    _hudState.update { it.copy(
                        aiEnhancedUri = originalUri,
                        isEnhancing = false
                    )}
                } catch (oom: OutOfMemoryError) {
                    Log.e("CameraViewModel", "OOM during processing", oom)
                    System.gc()
                    _hudState.update { it.copy(
                        aiEnhancedUri = originalUri,
                        isEnhancing = false
                    )}
                }
            }
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Capture failed", e)
            _toastMessage.tryEmit("Capture failed: ${e.message}")
            _hudState.update { it.copy(showCaptureFlash = false, isCapturing = false) }
        } catch (oom: OutOfMemoryError) {
            Log.e("CameraViewModel", "OOM during capture", oom)
            System.gc()
            _toastMessage.tryEmit("Out of memory — try again")
            _hudState.update { it.copy(showCaptureFlash = false, isCapturing = false) }
        } finally {
            _captureInProgress.value = false
        }
    }

    fun dismissReview() {
        reviewDismissJob?.cancel()
        _hudState.update { it.copy(showReview = false) }
    }

    fun dismissSmartReview() {
        smartCaptureFrames = null
        _hudState.update { it.copy(
            showSmartReview = false,
            bestOriginalUri = null,
            aiEnhancedUri = null,
            isEnhancing = false,
            captureExplanation = null,
            showAiExplainer = false
        )}
    }

    fun dismissAiExplainer() {
        _hudState.update { it.copy(showAiExplainer = false) }
    }

    fun saveOriginalOnly() {
        val enhanced = _hudState.value.aiEnhancedUri
        if (enhanced != null) captureManager.deletePhoto(enhanced)
        _toastMessage.tryEmit("Original saved")
        dismissSmartReview()
    }

    fun saveEnhancedOnly() {
        val original = _hudState.value.bestOriginalUri
        if (original != null) captureManager.deletePhoto(original)
        _hudState.update { it.copy(lastCapturedUri = _hudState.value.aiEnhancedUri) }
        _toastMessage.tryEmit("Enhanced saved")
        dismissSmartReview()
    }

    fun saveBoth() {
        _toastMessage.tryEmit("Both saved")
        dismissSmartReview()
    }

    fun discardSmartCapture() {
        val original = _hudState.value.bestOriginalUri
        val enhanced = _hudState.value.aiEnhancedUri
        if (original != null) captureManager.deletePhoto(original)
        if (enhanced != null) captureManager.deletePhoto(enhanced)
        _hudState.update { it.copy(lastCapturedUri = null) }
        _toastMessage.tryEmit("Photos discarded")
        dismissSmartReview()
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

    private var recordingStartTimeMs: Long = 0L

    fun startRecording() {
        if (_hudState.value.isRecording) return
        val started = cameraController.startRecording()
        if (started) {
            recordingStartTimeMs = System.currentTimeMillis()
            _hudState.update { it.copy(isRecording = true, recordingDurationMs = 0L) }
            recordingTimerJob = viewModelScope.launch {
                while (true) {
                    delay(100)
                    val elapsed = System.currentTimeMillis() - recordingStartTimeMs
                    _hudState.update { it.copy(recordingDurationMs = elapsed) }
                }
            }
        } else {
            _toastMessage.tryEmit("Could not start recording")
        }
    }

    fun stopRecording() {
        recordingTimerJob?.cancel()
        cameraController.stopRecording()
    }

    fun toggleRecording() {
        if (_hudState.value.isRecording) stopRecording() else startRecording()
    }

    fun toggleFocusPeaking() {
        _hudState.update { it.copy(focusPeakingEnabled = !it.focusPeakingEnabled) }
    }

    fun toggleZebra() {
        _hudState.update { it.copy(zebraEnabled = !it.zebraEnabled) }
    }

    fun toggleRaw() {
        _hudState.update { it.copy(
            settings = it.settings.copy(captureRaw = !it.settings.captureRaw)
        )}
    }

    fun cycleGridMode() {
        _hudState.update { state ->
            val modes = com.spectra.core.model.GridMode.entries
            val nextIdx = (modes.indexOf(state.gridMode) + 1) % modes.size
            state.copy(gridMode = modes[nextIdx])
        }
    }

    fun cycleZebraThreshold() {
        _hudState.update { state ->
            val next = when (state.zebraThreshold) {
                235 -> 243
                243 -> 250
                else -> 235
            }
            state.copy(zebraThreshold = next)
        }
    }

    private fun computeProOverlays(bitmap: android.graphics.Bitmap, peaking: Boolean, zebra: Boolean) {
        val sampleSize = 2
        val w = bitmap.width / sampleSize
        val h = bitmap.height / sampleSize
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        val edgeData = if (peaking) {
            val edges = IntArray(w * h)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val c = luminanceVal(pixels[idx])
                    val t = luminanceVal(pixels[(y - 1) * w + x])
                    val b = luminanceVal(pixels[(y + 1) * w + x])
                    val l = luminanceVal(pixels[y * w + (x - 1)])
                    val r = luminanceVal(pixels[y * w + (x + 1)])
                    edges[idx] = kotlin.math.abs(4 * c - t - b - l - r)
                }
            }
            edges
        } else null

        val zebraArr = if (zebra) {
            val threshold = _hudState.value.zebraThreshold
            val z = IntArray(w * h)
            for (i in pixels.indices) {
                val lum = luminanceVal(pixels[i])
                z[i] = if (lum > threshold) 1 else 0
            }
            z
        } else null

        _hudState.update { it.copy(
            focusPeakingData = edgeData,
            zebraData = zebraArr,
            analysisWidth = w,
            analysisHeight = h
        )}
    }

    private fun luminanceVal(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private fun analyzeContrastFromBitmap(bitmap: android.graphics.Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = maxOf(1, w / 32)
        val stepY = maxOf(1, h / 32)
        var darkCount = 0
        var brightCount = 0
        var total = 0
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (((pixel shr 16) and 0xFF) * 77 +
                        ((pixel shr 8) and 0xFF) * 150 +
                        (pixel and 0xFF) * 29) shr 8
                if (lum < 50) darkCount++
                if (lum > 200) brightCount++
                total++
            }
        }
        if (total == 0) return 0f
        val darkRatio = darkCount / total.toFloat()
        val brightRatio = brightCount / total.toFloat()
        return (darkRatio * brightRatio * 100f).coerceIn(0f, 1f)
    }

    private fun buildCaptureExplanation(): CaptureExplanation {
        val state = _hudState.value
        val reasons = mutableListOf<String>()

        if (state.sceneLabel.isNotEmpty() && state.sceneLabel != "READY") {
            reasons.add("${state.sceneLabel.lowercase()} scene detected")
        }

        if (state.lightingLabel.isNotEmpty() && state.lightingLabel != "—") {
            reasons.add("${state.lightingLabel.lowercase()} lighting")
        }

        if (state.motionLevel >= 3) {
            reasons.add("fast motion — high shutter speed selected")
        } else if (state.motionLevel >= 2) {
            reasons.add("moderate motion detected")
        }

        if (state.isHdrActive) {
            reasons.add("high contrast — HDR bracketing applied")
        }

        if (state.faceCount > 0) {
            reasons.add("${state.faceCount} face${if (state.faceCount > 1) "s" else ""} detected")
        }

        if (state.isLowLight) {
            reasons.add("low light — multi-frame noise reduction")
        }

        return CaptureExplanation(
            iso = if (state.actualIso > 0) state.actualIso else state.settings.iso,
            shutterSpeedNs = if (state.actualShutterSpeedNs > 0) state.actualShutterSpeedNs else
                (1_000_000_000L / state.settings.shutterSpeedDenominator.coerceAtLeast(1)),
            sceneLabel = state.sceneLabel,
            lightingLabel = state.lightingLabel,
            isHdrApplied = state.isHdrActive,
            isPortraitBokeh = state.mode == CameraMode.PORT && state.faceCount > 0,
            isNightMode = state.mode == CameraMode.NIGHT || state.isLowLight,
            reasons = reasons
        )
    }

    override fun onCleared() {
        super.onCleared()
        burstJob?.cancel()
        timerJob?.cancel()
        reviewDismissJob?.cancel()
        levelSensor.stop()
        locationProvider.stopUpdates()
        pipeline.release()
        cameraController.release()
        captureManager.releaseDepthModel()
    }
}
