package com.spectra.core.model

data class ProcessingParams(
    val contrast: Int = 50,
    val saturation: Int = 50,
    val sharpness: Int = 50,
    val noiseReduction: Int = 50,
    val hdrStrength: Int = 50,
    val skinToneProcessing: Int = 0,
    val highlightProtection: Int = 50,
    val shadowRecovery: Int = 50
) {
    companion object {
        val NATURAL = ProcessingParams()
        val VIVID = ProcessingParams(contrast = 60, saturation = 65, sharpness = 55)
        val SOFT = ProcessingParams(contrast = 40, saturation = 45, sharpness = 40)
        val CINEMATIC = ProcessingParams(contrast = 55, saturation = 40, sharpness = 35, highlightProtection = 65)
    }
}
