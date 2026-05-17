package com.spectra.core.model

enum class LensId(
    val displayName: String,
    val megapixels: Int,
    val focalLengthMm: Int,
    val zoomLabel: String,
    val zoomFactor: Float,
    val maxAperture: Float
) {
    ULTRAWIDE(
        displayName = "Ultrawide",
        megapixels = 12,
        focalLengthMm = 13,
        zoomLabel = "0.6x",
        zoomFactor = 0.6f,
        maxAperture = 2.2f
    ),
    MAIN(
        displayName = "Main",
        megapixels = 200,
        focalLengthMm = 23,
        zoomLabel = "1x",
        zoomFactor = 1.0f,
        maxAperture = 1.7f
    ),
    TELEPHOTO_3X(
        displayName = "Telephoto 3x",
        megapixels = 10,
        focalLengthMm = 69,
        zoomLabel = "3x",
        zoomFactor = 3.0f,
        maxAperture = 2.4f
    ),
    TELEPHOTO_5X(
        displayName = "Telephoto 5x",
        megapixels = 50,
        focalLengthMm = 115,
        zoomLabel = "5x",
        zoomFactor = 5.0f,
        maxAperture = 3.4f
    );
}
