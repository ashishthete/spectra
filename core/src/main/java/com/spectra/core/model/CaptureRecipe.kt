package com.spectra.core.model

data class CaptureRecipe(
    val preset: CameraPreset,
    val baseFrameCount: Int,
    val maxFrameCount: Int,
    val preferredLens: LensId,
    val minShutterSpeedNs: Long,
    val maxIso: Int,
    val useHdrBracket: Boolean,
    val useBurstMerge: Boolean,
    val useDepthBokeh: Boolean,
    val faceExposurePriority: Boolean,
    val skyHighlightProtection: Boolean,
    val skinHueProtection: Boolean,
    val preserveAtmosphere: Boolean,
    val processingIntensity: Float
) {
    fun resolveFrameCount(
        isStable: Boolean,
        gyroMotion: Float = 0f,
        iso: Int = 100,
        lux: Float = Float.MAX_VALUE,
        thermalMaxFrames: Int = Int.MAX_VALUE,
        hasFaceMotion: Boolean = false,
        highContrast: Boolean = false
    ): Int {
        if (maxFrameCount <= baseFrameCount) return baseFrameCount
        var frames = baseFrameCount

        if (isStable && gyroMotion < 0.05f && !hasFaceMotion) {
            if (iso > 1600 || lux < 10f) frames = maxFrameCount
            else if (iso > 800 || lux < 50f) frames = (baseFrameCount + maxFrameCount) / 2
            else if (highContrast) frames = baseFrameCount + 1
        } else if (isStable && gyroMotion < 0.1f) {
            if (iso > 1600) frames = (baseFrameCount + maxFrameCount) / 2
        }

        val upperBound = minOf(maxFrameCount, thermalMaxFrames)
        return if (upperBound < baseFrameCount) upperBound.coerceAtLeast(1)
               else frames.coerceIn(baseFrameCount, upperBound)
    }

    companion object {
        fun forPreset(
            preset: CameraPreset,
            isLowLight: Boolean,
            isStable: Boolean,
            hasFaces: Boolean,
            highContrast: Boolean
        ): CaptureRecipe = when (preset) {
            CameraPreset.AUTO -> CaptureRecipe(
                preset = preset,
                baseFrameCount = if (isLowLight) 3 else 1,
                maxFrameCount = if (isStable) 7 else 3,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = if (hasFaces) 8_000_000L else 4_000_000L,
                maxIso = if (isLowLight) 3200 else 800,
                useHdrBracket = highContrast,
                useBurstMerge = isLowLight,
                useDepthBokeh = false,
                faceExposurePriority = hasFaces,
                skyHighlightProtection = !hasFaces && highContrast,
                skinHueProtection = hasFaces,
                preserveAtmosphere = true,
                processingIntensity = 0.5f
            )

            CameraPreset.PORTRAIT -> CaptureRecipe(
                preset = preset,
                baseFrameCount = if (isLowLight) 3 else 1,
                maxFrameCount = 5,
                preferredLens = LensId.TELEPHOTO_3X,
                minShutterSpeedNs = 8_000_000L,
                maxIso = if (isLowLight) 1600 else 400,
                useHdrBracket = highContrast && isStable,
                useBurstMerge = isLowLight,
                useDepthBokeh = true,
                faceExposurePriority = true,
                skyHighlightProtection = false,
                skinHueProtection = true,
                preserveAtmosphere = false,
                processingIntensity = 0.6f
            )

            CameraPreset.NIGHT -> CaptureRecipe(
                preset = preset,
                baseFrameCount = 5,
                maxFrameCount = if (isStable) 9 else 5,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 4_000_000L,
                maxIso = 6400,
                useHdrBracket = false,
                useBurstMerge = true,
                useDepthBokeh = false,
                faceExposurePriority = hasFaces,
                skyHighlightProtection = true,
                skinHueProtection = hasFaces,
                preserveAtmosphere = true,
                processingIntensity = 0.4f
            )

            CameraPreset.FOOD -> CaptureRecipe(
                preset = preset,
                baseFrameCount = if (isLowLight) 3 else 1,
                maxFrameCount = 7,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 8_000_000L,
                maxIso = 800,
                useHdrBracket = highContrast,
                useBurstMerge = isLowLight,
                useDepthBokeh = false,
                faceExposurePriority = false,
                skyHighlightProtection = false,
                skinHueProtection = false,
                preserveAtmosphere = false,
                processingIntensity = 0.5f
            )

            CameraPreset.LANDSCAPE -> CaptureRecipe(
                preset = preset,
                baseFrameCount = if (isLowLight) 5 else 3,
                maxFrameCount = 9,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 4_000_000L,
                maxIso = 200,
                useHdrBracket = highContrast && isStable,
                useBurstMerge = isLowLight,
                useDepthBokeh = false,
                faceExposurePriority = false,
                skyHighlightProtection = true,
                skinHueProtection = false,
                preserveAtmosphere = true,
                processingIntensity = 0.5f
            )

            CameraPreset.ACTION -> CaptureRecipe(
                preset = preset,
                baseFrameCount = 1,
                maxFrameCount = 3,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 1_000_000L,
                maxIso = 3200,
                useHdrBracket = false,
                useBurstMerge = false,
                useDepthBokeh = false,
                faceExposurePriority = hasFaces,
                skyHighlightProtection = false,
                skinHueProtection = hasFaces,
                preserveAtmosphere = false,
                processingIntensity = 0.3f
            )

            CameraPreset.MACRO -> CaptureRecipe(
                preset = preset,
                baseFrameCount = if (isStable) 3 else 1,
                maxFrameCount = 5,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 4_000_000L,
                maxIso = 800,
                useHdrBracket = false,
                useBurstMerge = isStable,
                useDepthBokeh = false,
                faceExposurePriority = false,
                skyHighlightProtection = false,
                skinHueProtection = false,
                preserveAtmosphere = false,
                processingIntensity = 0.4f
            )

            CameraPreset.PRO -> CaptureRecipe(
                preset = preset,
                baseFrameCount = 1,
                maxFrameCount = 1,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 0L,
                maxIso = 12800,
                useHdrBracket = false,
                useBurstMerge = false,
                useDepthBokeh = false,
                faceExposurePriority = false,
                skyHighlightProtection = false,
                skinHueProtection = false,
                preserveAtmosphere = false,
                processingIntensity = 0.1f
            )

            CameraPreset.TRUE_SCENE -> CaptureRecipe(
                preset = preset,
                baseFrameCount = 1,
                maxFrameCount = 1,
                preferredLens = LensId.MAIN,
                minShutterSpeedNs = 0L,
                maxIso = 3200,
                useHdrBracket = false,
                useBurstMerge = false,
                useDepthBokeh = false,
                faceExposurePriority = false,
                skyHighlightProtection = false,
                skinHueProtection = false,
                preserveAtmosphere = true,
                processingIntensity = 0f
            )
        }
    }
}
