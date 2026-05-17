package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import com.spectra.ai.model.MotionLevel
import com.spectra.ai.model.SceneAnalysis
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.PresetProfile
import com.spectra.core.model.ProcessingParams
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetEngine @Inject constructor() {

    fun buildProfile(preset: CameraPreset, analysis: SceneAnalysis): PresetProfile {
        val isNight = analysis.lighting == LightingCondition.LOW_LIGHT ||
                analysis.lighting == LightingCondition.ARTIFICIAL
        val settings = getSettings(preset, analysis.lighting, analysis.motionLevel)
        val processing = getProcessing(preset, isNight)

        return PresetProfile(
            preset = preset,
            settings = settings,
            processing = processing,
            facePriority = preset in FACE_PRESETS,
            eyeAf = preset in EYE_AF_PRESETS,
            blinkDetection = preset == CameraPreset.GROUP,
            burstEnabled = preset in BURST_PRESETS,
            motionStabilization = preset in STABILIZE_PRESETS,
            focusTracking = preset in TRACKING_PRESETS,
            multiFrameHdr = preset !in NO_HDR_PRESETS && !isNight,
            multiFrameStacking = preset == CameraPreset.NIGHT,
            backgroundBlur = preset in BLUR_PRESETS
        )
    }

    private fun getSettings(preset: CameraPreset, light: LightingCondition, motion: MotionLevel): CameraSettings {
        val base = when (preset) {
            CameraPreset.SELFIE -> selfieSettings(light)
            CameraPreset.GROUP -> groupSettings(light)
            CameraPreset.PORTRAIT -> portraitSettings(light)
            CameraPreset.COUPLE -> coupleSettings(light)
            CameraPreset.KIDS -> kidsSettings(light)
            CameraPreset.PETS -> petsSettings(light)
            CameraPreset.FOOD -> foodSettings(light)
            CameraPreset.PRODUCT -> productSettings(light)
            CameraPreset.LANDSCAPE -> landscapeSettings(light)
            CameraPreset.SUNSET -> sunsetSettings(light)
            CameraPreset.NIGHT -> nightSettings(light)
            CameraPreset.STREET -> streetSettings(light)
            CameraPreset.CINEMATIC -> cinematicSettings(light)
            CameraPreset.ACTION -> actionSettings(light)
            CameraPreset.MACRO -> macroSettings(light)
            CameraPreset.PRO -> CameraSettings()
        }
        return adjustForMotion(base, motion, preset)
    }

    private fun selfieSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5400, exposureCompensation = 0.5f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.5f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4200, exposureCompensation = 1.0f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4000, exposureCompensation = 0.7f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5300, exposureCompensation = 0.5f
        )
    }

    private fun groupSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5300, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4200, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 125, whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
    }

    private fun portraitSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5300, exposureCompensation = 0.3f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 6200, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4200, exposureCompensation = 0.3f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
    }

    private fun coupleSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 80, whiteBalanceKelvin = 4200, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5400, exposureCompensation = 0.3f
        )
    }

    private fun kidsSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5300, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 800, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun petsSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5300, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 800, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun foodSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.7f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 100, whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
    }

    private fun productSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
        LightingCondition.ARTIFICIAL, LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun landscapeSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 60, whiteBalanceKelvin = 7500, exposureCompensation = 0.3f
        )
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5000, exposureCompensation = -0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6200, exposureCompensation = 0f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun sunsetSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6000, exposureCompensation = -0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 7000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5800, exposureCompensation = -0.3f
        )
    }

    private fun nightSettings(light: LightingCondition) = when (light) {
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 30, whiteBalanceKelvin = 3500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 15, whiteBalanceKelvin = 3800, exposureCompensation = 0f
        )
    }

    private fun streetSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun cinematicSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4200, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5300, exposureCompensation = 0f
        )
    }

    private fun actionSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 2000, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun macroSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f, focusDistance = 0.1f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 250, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f, focusDistance = 0.1f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 320, whiteBalanceKelvin = 5500, exposureCompensation = 0f, focusDistance = 0.1f
        )
    }

    private fun getProcessing(preset: CameraPreset, isNight: Boolean): ProcessingParams {
        return when (preset) {
            CameraPreset.SELFIE -> ProcessingParams(
                contrast = 40, saturation = 48, sharpness = 35,
                noiseReduction = if (isNight) 65 else 45,
                skinToneProcessing = 30, highlightProtection = 55
            )
            CameraPreset.GROUP -> ProcessingParams(
                contrast = 50, saturation = 50, sharpness = 50,
                noiseReduction = if (isNight) 60 else 40,
                skinToneProcessing = 15, highlightProtection = 55
            )
            CameraPreset.PORTRAIT -> ProcessingParams(
                contrast = 42, saturation = 45, sharpness = 38,
                noiseReduction = if (isNight) 55 else 35,
                skinToneProcessing = 25, highlightProtection = 60
            )
            CameraPreset.COUPLE -> ProcessingParams(
                contrast = 42, saturation = 48, sharpness = 40,
                noiseReduction = if (isNight) 55 else 38,
                skinToneProcessing = 20, highlightProtection = 55
            )
            CameraPreset.KIDS -> ProcessingParams(
                contrast = 50, saturation = 55, sharpness = 45,
                noiseReduction = if (isNight) 55 else 35,
                hdrStrength = 35
            )
            CameraPreset.PETS -> ProcessingParams(
                contrast = 50, saturation = 52, sharpness = 55,
                noiseReduction = if (isNight) 50 else 30
            )
            CameraPreset.FOOD -> ProcessingParams(
                contrast = 50, saturation = 58, sharpness = 55,
                noiseReduction = 35, highlightProtection = 60,
                hdrStrength = 40
            )
            CameraPreset.PRODUCT -> ProcessingParams(
                contrast = 55, saturation = 50, sharpness = 65,
                noiseReduction = 30, hdrStrength = 35
            )
            CameraPreset.LANDSCAPE -> ProcessingParams(
                contrast = 50, saturation = 52, sharpness = 55,
                noiseReduction = 30, hdrStrength = 60,
                highlightProtection = 65, shadowRecovery = 55
            )
            CameraPreset.SUNSET -> ProcessingParams(
                contrast = 55, saturation = 55, sharpness = 45,
                hdrStrength = 30, highlightProtection = 70
            )
            CameraPreset.NIGHT -> ProcessingParams(
                contrast = 45, saturation = 40, sharpness = 35,
                noiseReduction = 70, hdrStrength = 25,
                shadowRecovery = 40
            )
            CameraPreset.STREET -> ProcessingParams(
                contrast = 55, saturation = 48, sharpness = 50,
                noiseReduction = 35
            )
            CameraPreset.CINEMATIC -> ProcessingParams(
                contrast = 55, saturation = 40, sharpness = 30,
                highlightProtection = 65, shadowRecovery = 35
            )
            CameraPreset.ACTION -> ProcessingParams(
                contrast = 55, saturation = 52, sharpness = 55,
                noiseReduction = 30, hdrStrength = 35
            )
            CameraPreset.MACRO -> ProcessingParams(
                contrast = 50, saturation = 50, sharpness = 65,
                noiseReduction = 25
            )
            CameraPreset.PRO -> ProcessingParams.NATURAL
        }
    }

    private fun adjustForMotion(settings: CameraSettings, motion: MotionLevel, preset: CameraPreset): CameraSettings {
        if (preset in FAST_SHUTTER_PRESETS) return settings

        return when (motion) {
            MotionLevel.FAST, MotionLevel.VERY_FAST -> CameraSettings.clamped(
                iso = maxOf(settings.iso, 800),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 1000),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation,
                focusDistance = settings.focusDistance
            )
            MotionLevel.MODERATE -> CameraSettings.clamped(
                iso = maxOf(settings.iso, 400),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 500),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation,
                focusDistance = settings.focusDistance
            )
            else -> settings
        }
    }

    companion object {
        private val FACE_PRESETS = setOf(
            CameraPreset.SELFIE, CameraPreset.GROUP, CameraPreset.PORTRAIT,
            CameraPreset.COUPLE, CameraPreset.KIDS
        )
        private val EYE_AF_PRESETS = setOf(
            CameraPreset.SELFIE, CameraPreset.PORTRAIT, CameraPreset.COUPLE
        )
        private val BURST_PRESETS = setOf(
            CameraPreset.KIDS, CameraPreset.PETS, CameraPreset.ACTION
        )
        private val STABILIZE_PRESETS = setOf(
            CameraPreset.NIGHT, CameraPreset.MACRO, CameraPreset.CINEMATIC
        )
        private val TRACKING_PRESETS = setOf(
            CameraPreset.KIDS, CameraPreset.PETS, CameraPreset.ACTION
        )
        private val NO_HDR_PRESETS = setOf(
            CameraPreset.NIGHT, CameraPreset.SUNSET, CameraPreset.CINEMATIC
        )
        private val BLUR_PRESETS = setOf(
            CameraPreset.PORTRAIT, CameraPreset.COUPLE, CameraPreset.SELFIE
        )
        private val FAST_SHUTTER_PRESETS = setOf(
            CameraPreset.KIDS, CameraPreset.PETS, CameraPreset.ACTION
        )
    }
}
