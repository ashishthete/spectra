package com.spectra.ai.model

import com.spectra.core.model.CameraPreset
import com.spectra.core.model.LensId

sealed class CoachingAction {
    abstract val label: String

    data class SwitchLens(val lensId: LensId) : CoachingAction() {
        override val label: String get() = "Switch to ${lensId.zoomLabel}"
    }

    data object EnableBurst : CoachingAction() {
        override val label: String get() = "Enable Burst"
    }

    data class SwitchPreset(val preset: CameraPreset) : CoachingAction() {
        override val label: String get() = "Switch to ${preset.label}"
    }
}
