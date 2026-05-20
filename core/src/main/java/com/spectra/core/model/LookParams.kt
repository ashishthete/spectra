package com.spectra.core.model

data class LookParams(
    val style: PhotoStyle = PhotoStyle.NATURAL,
    val preset: CameraPreset = CameraPreset.AUTO,
    val whiteBalanceKelvin: Int = 5500,
    val tintShift: Float = 0f,

    val contrastStrength: Float = 0f,
    val saturationScale: Float = 1f,
    val highlightShoulder: Float = 0f,
    val shadowLift: Float = 0f,

    val skinHueProtection: Boolean = false,
    val chromaCompression: Float = 0f,

    val isHdrActive: Boolean = false,
    val hdrStrength: Float = 0.5f,

    val isPortraitMode: Boolean = false,
    val bokehEnabled: Boolean = false,
    val beautyLevel: Int = 0
) {
    companion object {
        fun fromState(
            style: PhotoStyle,
            preset: CameraPreset,
            wbKelvin: Int,
            isHdr: Boolean,
            isPortrait: Boolean,
            beautyLevel: Int,
            sceneContrast: Float,
            faceCount: Int
        ): LookParams {
            val hasFaces = faceCount > 0
            return LookParams(
                style = style,
                preset = preset,
                whiteBalanceKelvin = wbKelvin,

                contrastStrength = when (style) {
                    PhotoStyle.VIVID -> 0.15f
                    PhotoStyle.CINEMATIC -> 0.10f
                    PhotoStyle.FILM -> 0.05f
                    else -> 0f
                },
                saturationScale = when (style) {
                    PhotoStyle.VIVID -> 1.15f
                    PhotoStyle.WARM -> 1.05f
                    PhotoStyle.CINEMATIC -> 0.90f
                    PhotoStyle.FILM -> 0.95f
                    else -> 1.0f
                },
                highlightShoulder = when (style) {
                    PhotoStyle.FILM -> 0.8f
                    PhotoStyle.CINEMATIC -> 0.7f
                    PhotoStyle.WARM -> 0.4f
                    PhotoStyle.VIVID -> 0.3f
                    else -> 0f
                },
                shadowLift = when (style) {
                    PhotoStyle.FILM -> 15f / 255f
                    PhotoStyle.CINEMATIC -> 20f / 255f
                    else -> 0f
                },

                skinHueProtection = hasFaces,
                chromaCompression = if (isHdr && sceneContrast > 0.2f) 0.3f else 0f,

                isHdrActive = isHdr,
                hdrStrength = if (isHdr) sceneContrast.coerceIn(0.3f, 0.8f) else 0f,

                isPortraitMode = isPortrait,
                bokehEnabled = isPortrait && hasFaces,
                beautyLevel = beautyLevel
            )
        }
    }
}
