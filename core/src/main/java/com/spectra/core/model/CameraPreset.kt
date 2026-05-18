package com.spectra.core.model

enum class CameraPreset(
    val label: String,
    val icon: String,
    val preferredLens: LensId = LensId.MAIN,
    val isFrontCameraDefault: Boolean = false
) {
    AUTO("Auto", "✨", preferredLens = LensId.MAIN),
    PORTRAIT("Portrait", "👤", preferredLens = LensId.TELEPHOTO_3X),
    NIGHT("Night", "🌙", preferredLens = LensId.MAIN),
    FOOD("Food", "🍽", preferredLens = LensId.MAIN),
    LANDSCAPE("Landscape", "🏞", preferredLens = LensId.MAIN),
    ACTION("Action", "⚡", preferredLens = LensId.MAIN),
    MACRO("Macro", "🔍", preferredLens = LensId.MAIN),
    PRO("Pro", "⚙", preferredLens = LensId.MAIN);
}
