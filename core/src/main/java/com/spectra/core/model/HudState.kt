package com.spectra.core.model

data class HudState(
    val activeLens: LensId = LensId.MAIN,
    val settings: CameraSettings = CameraSettings(),
    val mode: CameraMode = CameraMode.PHOTO,
    val isHudVisible: Boolean = true,
    val sceneLabel: String = "READY",
    val sceneConfidence: Float = 0f,
    val lightingLabel: String = "—",
    val motionLevel: Int = 0,
    val distanceLabel: String = "—",
    val coachingText: String? = null,
    val coachingArrow: String = "NONE",
    val lensMatchScores: Map<LensId, Float> = LensId.entries.associateWith {
        if (it == activeLens) 1.0f else 0f
    },
    val isBurstActive: Boolean = false,
    val lastCapturedUri: String? = null,
    val showTipsThumbnail: Boolean = false,
    val showReferenceCard: Boolean = false,
    val cloudCoachingText: String? = null,
    val cloudCoachingArrow: String = "NONE",
    val aiRecommendedSettings: CameraSettings = CameraSettings(),
    val isManualOverride: Boolean = false
)
