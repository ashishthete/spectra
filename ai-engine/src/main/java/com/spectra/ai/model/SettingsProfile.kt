package com.spectra.ai.model

import com.spectra.core.model.CameraSettings

data class SettingsProfile(
    val settings: CameraSettings,
    val reason: String
)
