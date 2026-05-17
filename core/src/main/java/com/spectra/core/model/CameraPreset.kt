package com.spectra.core.model

enum class CameraPreset(
    val label: String,
    val icon: String,
    val preferredLens: LensId = LensId.MAIN,
    val isFrontCameraDefault: Boolean = false
) {
    SELFIE("Selfie", "🤳", preferredLens = LensId.MAIN, isFrontCameraDefault = true),
    GROUP("Group", "👥", preferredLens = LensId.ULTRAWIDE),
    PORTRAIT("Portrait", "👤", preferredLens = LensId.TELEPHOTO_3X),
    COUPLE("Couple", "💑", preferredLens = LensId.MAIN),
    KIDS("Kids", "🧒", preferredLens = LensId.MAIN),
    PETS("Pets", "🐾", preferredLens = LensId.MAIN),
    FOOD("Food", "🍽", preferredLens = LensId.MAIN),
    PRODUCT("Product", "📦", preferredLens = LensId.MAIN),
    LANDSCAPE("Landscape", "🏞", preferredLens = LensId.MAIN),
    SUNSET("Sunset", "🌅", preferredLens = LensId.MAIN),
    NIGHT("Night", "🌙", preferredLens = LensId.MAIN),
    STREET("Street", "🏙", preferredLens = LensId.MAIN),
    CINEMATIC("Cinematic", "🎬", preferredLens = LensId.MAIN),
    ACTION("Action", "⚡", preferredLens = LensId.MAIN),
    MACRO("Macro", "🔍", preferredLens = LensId.MAIN),
    PRO("Pro", "⚙", preferredLens = LensId.MAIN);
}
