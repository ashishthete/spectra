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

        val faceCount = analysis.faceData.faceCount
        val isGroup = preset == CameraPreset.PORTRAIT && faceCount >= 3

        return PresetProfile(
            preset = preset,
            settings = settings,
            processing = processing,
            facePriority = preset in FACE_PRESETS,
            eyeAf = preset in EYE_AF_PRESETS && !isGroup,
            blinkDetection = isGroup,
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
            CameraPreset.AUTO -> autoSettings(light)
            CameraPreset.PORTRAIT -> portraitSettings(light)
            CameraPreset.NIGHT -> nightSettings(light)
            CameraPreset.FOOD -> foodSettings(light)
            CameraPreset.LANDSCAPE -> landscapeSettings(light)
            CameraPreset.ACTION -> actionSettings(light)
            CameraPreset.MACRO -> macroSettings(light)
            CameraPreset.PRO -> CameraSettings()
        }
        return adjustForMotion(base, motion, preset)
    }

    private fun autoSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4500, exposureCompensation = 0f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 200, whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0f
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
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4200, exposureCompensation = 0.5f
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

    private fun nightSettings(light: LightingCondition) = when (light) {
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 30, whiteBalanceKelvin = 3500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 15, whiteBalanceKelvin = 3800, exposureCompensation = 0f
        )
    }

    private fun foodSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.7f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL, LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
    }

    private fun landscapeSettings(light: LightingCondition) = when (light) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6000, exposureCompensation = -0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 7000, exposureCompensation = 0f
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

    private fun actionSettings(light: LightingCondition) = when (light) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 2000, whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 500, whiteBalanceKelvin = 4000, exposureCompensation = 0f
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
            CameraPreset.AUTO -> ProcessingParams(
                contrast = 50, saturation = 50, sharpness = 50,
                noiseReduction = if (isNight) 55 else 35
            )
            CameraPreset.PORTRAIT -> ProcessingParams(
                contrast = 42, saturation = 46, sharpness = 38,
                noiseReduction = if (isNight) 55 else 35,
                skinToneProcessing = 25, highlightProtection = 58
            )
            CameraPreset.NIGHT -> ProcessingParams(
                contrast = 45, saturation = 40, sharpness = 35,
                noiseReduction = 70, hdrStrength = 25,
                shadowRecovery = 40
            )
            CameraPreset.FOOD -> ProcessingParams(
                contrast = 52, saturation = 56, sharpness = 60,
                noiseReduction = 32, highlightProtection = 60,
                hdrStrength = 38
            )
            CameraPreset.LANDSCAPE -> ProcessingParams(
                contrast = 52, saturation = 54, sharpness = 55,
                noiseReduction = 30, hdrStrength = 55,
                highlightProtection = 68, shadowRecovery = 50
            )
            CameraPreset.ACTION -> ProcessingParams(
                contrast = 52, saturation = 53, sharpness = 52,
                noiseReduction = if (isNight) 50 else 30,
                hdrStrength = 35
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
        private val FACE_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val EYE_AF_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val BURST_PRESETS = setOf(CameraPreset.ACTION)
        private val STABILIZE_PRESETS = setOf(CameraPreset.NIGHT, CameraPreset.MACRO)
        private val TRACKING_PRESETS = setOf(CameraPreset.ACTION)
        private val NO_HDR_PRESETS = setOf(CameraPreset.NIGHT)
        private val BLUR_PRESETS = setOf(CameraPreset.PORTRAIT)
        private val FAST_SHUTTER_PRESETS = setOf(CameraPreset.ACTION)
    }
}
