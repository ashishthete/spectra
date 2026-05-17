package com.spectra.core.model

data class PresetProfile(
    val preset: CameraPreset,
    val settings: CameraSettings,
    val processing: ProcessingParams,
    val facePriority: Boolean = false,
    val eyeAf: Boolean = false,
    val blinkDetection: Boolean = false,
    val burstEnabled: Boolean = false,
    val motionStabilization: Boolean = false,
    val focusTracking: Boolean = false,
    val multiFrameHdr: Boolean = false,
    val multiFrameStacking: Boolean = false,
    val backgroundBlur: Boolean = false
)
