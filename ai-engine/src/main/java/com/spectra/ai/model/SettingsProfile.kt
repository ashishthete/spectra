package com.spectra.ai.model

import com.spectra.core.model.CameraSettings
import com.spectra.core.model.CameraConstraints

data class SettingsProfile(
    val settings: CameraSettings,
    val reason: String,
    val constraints: CameraConstraints? = null
)
