package com.spectra.core.model

enum class CameraPreset(
    val label: String,
    val icon: String,
    val preferredLens: LensId = LensId.MAIN,
    val isFrontCameraDefault: Boolean = false
) {
    AUTO("Auto", "AUTO", preferredLens = LensId.MAIN),
    PORTRAIT("Portrait", "PORT", preferredLens = LensId.TELEPHOTO_3X),
    NIGHT("Night", "NGHT", preferredLens = LensId.MAIN),
    FOOD("Food", "FOOD", preferredLens = LensId.MAIN),
    LANDSCAPE("Landscape", "LNDS", preferredLens = LensId.MAIN),
    ACTION("Action", "ACTN", preferredLens = LensId.MAIN),
    MACRO("Macro", "MCRO", preferredLens = LensId.MAIN),
    PRO("Pro", "PRO", preferredLens = LensId.MAIN),
    TRUE_SCENE("True Scene", "TRUE", preferredLens = LensId.MAIN);
}
