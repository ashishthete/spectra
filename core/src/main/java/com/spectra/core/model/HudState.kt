package com.spectra.core.model

data class HudState(
    val activeLens: LensId = LensId.MAIN,
    val settings: CameraSettings = CameraSettings(),
    val mode: CameraMode = CameraMode.PHOTO,
    val preset: CameraPreset = CameraPreset.AUTO,
    val processing: ProcessingParams = ProcessingParams(),
    val isHudVisible: Boolean = true,

    val sceneLabel: String = "",
    val sceneConfidence: Float = 0f,
    val lightingLabel: String = "",
    val motionLevel: Int = 0,
    val distanceLabel: String = "",

    val coachingText: String? = null,
    val coachingArrow: String = "NONE",
    val cloudCoachingText: String? = null,
    val cloudCoachingArrow: String = "NONE",
    val coachingActionLabel: String? = null,
    val coachingActionType: String? = null,
    val coachingActionPayload: String? = null,

    val lensMatchScores: Map<LensId, Float> = LensId.entries.associateWith {
        if (it == activeLens) 1.0f else 0f
    },
    val recommendedLens: LensId? = null,

    val isBurstActive: Boolean = false,
    val lastCapturedUri: String? = null,
    val showReferenceCard: Boolean = false,

    val aiRecommendedSettings: CameraSettings = CameraSettings(),
    val isManualOverride: Boolean = false,

    val isFrontCamera: Boolean = false,
    val beautyLevel: Int = 0,
    val cameraMegapixels: Int = 12,
    val cameraAperture: Float = 1.7f,

    val flashMode: FlashMode = FlashMode.OFF,
    val timerSeconds: Int = 0,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,

    val zoomRatio: Float = 1f,
    val maxZoomRatio: Float = 10f,

    val levelAngle: Float = 0f,
    val pitchAngle: Float = 0f,

    val isFocusing: Boolean = false,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val focusSuccess: Boolean = false,

    val actualIso: Int = 0,
    val actualShutterSpeedNs: Long = 0L,
    val actualFocusDistance: Float = 0f,
    val actualColorTemperature: Int = 0,
    val estimatedLux: Float = -1f,

    val timerCountdown: Int = 0,
    val showCaptureFlash: Boolean = false,
    val isCapturing: Boolean = false,

    val faceCount: Int = 0,
    val anyoneSmiling: Boolean = false,
    val allEyesOpen: Boolean = true,
    val anyBlinking: Boolean = false,

    val aeAfLocked: Boolean = false,
    val settingsDisplayMode: SettingsDisplayMode = SettingsDisplayMode.ACTUAL,

    val showReview: Boolean = false,
    val reviewUri: String? = null,
    val lensHint: String? = null,

    val showSmartReview: Boolean = false,
    val bestOriginalUri: String? = null,
    val aiEnhancedUri: String? = null,
    val isEnhancing: Boolean = false,

    val photoStyle: PhotoStyle = PhotoStyle.NATURAL,

    val isHdrActive: Boolean = false,
    val sceneContrast: Float = 0f,
    val histogramData: IntArray = IntArray(256),
    val showMiniHistogram: Boolean = false,

    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0L,

    val focusPeakingEnabled: Boolean = false,
    val zebraEnabled: Boolean = false,
    val zebraThreshold: Int = 235,
    val gridMode: GridMode = GridMode.THIRDS,
    val focusPeakingData: IntArray? = null,
    val peakingWidth: Int = 0,
    val peakingHeight: Int = 0,
    val zebraData: IntArray? = null,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,

    val captureExplanation: CaptureExplanation? = null,
    val showAiExplainer: Boolean = false,

    val userTier: UserTier = UserTier.EVERYDAY,
    val focusConfidence: Float = 1f,
    val eyeFocusActive: Boolean = false,

    val falseColorEnabled: Boolean = false,
    val falseColorData: IntArray? = null,
    val waveformData: IntArray? = null,

    val videoExposureSmoothing: Boolean = true,
    val videoStabilizationWarning: Boolean = false,
    val videoFocusTracking: Boolean = false,

    val lookParams: LookParams = LookParams(),
    val captureRecipe: CaptureRecipe? = null,

    val isHighlightClipped: Boolean = false,
    val isShadowClipped: Boolean = false,
    val highlightClipFraction: Float = 0f,
    val shadowClipFraction: Float = 0f,

    val cropSuggestions: List<CropSuggestionData> = emptyList(),

    val palmGestureEnabled: Boolean = true,
    val palmCountdown: Int = 0,
    val controlsVisible: Boolean = true,
) {
    val isLowLight: Boolean get() = actualIso > 800 || actualShutterSpeedNs > 33_000_000L

    val activeOverlay: OverlayPriority? get() = when {
        showCaptureFlash -> OverlayPriority.CAPTURE_FLASH
        showReview || showSmartReview -> OverlayPriority.REVIEW
        coachingText != null -> OverlayPriority.COACHING
        showAiExplainer -> OverlayPriority.AI_EXPLAINER
        beautyLevel > 0 -> OverlayPriority.BEAUTY
        else -> null
    }
}
