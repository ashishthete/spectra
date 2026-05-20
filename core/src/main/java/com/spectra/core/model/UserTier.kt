package com.spectra.core.model

enum class UserTier {
    EVERYDAY,
    CREATOR,
    PRO;

    val showHistogram: Boolean get() = this != EVERYDAY
    val showRawToggle: Boolean get() = this == PRO
    val showFocusPeaking: Boolean get() = this == PRO
    val showZebra: Boolean get() = this == PRO
    val showWaveform: Boolean get() = this == PRO
    val showFalseColor: Boolean get() = this == PRO
    val showManualSettings: Boolean get() = this == PRO
    val showProcessingStrength: Boolean get() = this != EVERYDAY
    val showStyleSelector: Boolean get() = this != EVERYDAY
    val maxCoachingDirectives: Int get() = when (this) {
        EVERYDAY -> 1
        CREATOR -> 2
        PRO -> 0
    }
}
