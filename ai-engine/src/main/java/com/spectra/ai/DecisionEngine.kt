package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.CameraConstraints
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

data class ExposureStrategy(
    val useSemiAuto: Boolean,
    val minShutterDenom: Int = 60,
    val maxIso: Int = 3200,
    val reason: String = ""
)

@Singleton
class DecisionEngine @Inject constructor() {

    fun getExposureStrategy(preset: com.spectra.core.model.CameraPreset, sceneConfidence: Float): ExposureStrategy {
        if (sceneConfidence < 0.7f || preset == com.spectra.core.model.CameraPreset.AUTO) {
            return ExposureStrategy(useSemiAuto = false, reason = "AUTO — low confidence or auto preset")
        }
        return when (preset) {
            com.spectra.core.model.CameraPreset.PORTRAIT -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 125, maxIso = 800,
                reason = "PORTRAIT — moderate shutter, low noise"
            )
            com.spectra.core.model.CameraPreset.ACTION -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 500, maxIso = 3200,
                reason = "ACTION — fast shutter priority"
            )
            com.spectra.core.model.CameraPreset.NIGHT -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 8, maxIso = 6400,
                reason = "NIGHT — long exposure, higher ISO ceiling"
            )
            com.spectra.core.model.CameraPreset.LANDSCAPE -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 125, maxIso = 200,
                reason = "LANDSCAPE — base ISO priority"
            )
            com.spectra.core.model.CameraPreset.FOOD -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 100, maxIso = 400,
                reason = "FOOD — low noise, moderate shutter"
            )
            com.spectra.core.model.CameraPreset.MACRO -> ExposureStrategy(
                useSemiAuto = true, minShutterDenom = 250, maxIso = 400,
                reason = "MACRO — fast shutter to prevent micro-shake"
            )
            com.spectra.core.model.CameraPreset.PRO -> ExposureStrategy(
                useSemiAuto = false, reason = "PRO — manual control"
            )
            else -> ExposureStrategy(useSemiAuto = false, reason = "Fallback auto")
        }
    }

    fun recommendLens(analysis: SceneAnalysis): LensRecommendation {
        val scores = mutableMapOf<LensId, Float>()

        when (analysis.sceneType) {
            SceneType.LANDSCAPE -> {
                scores[LensId.MAIN] = 0.9f
                scores[LensId.ULTRAWIDE] = 0.7f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.3f
            }
            SceneType.PORTRAIT -> {
                scores[LensId.TELEPHOTO_3X] = 0.95f
                scores[LensId.MAIN] = 0.5f
                scores[LensId.TELEPHOTO_5X] = 0.4f
                scores[LensId.ULTRAWIDE] = 0.1f
            }
            SceneType.FOOD -> {
                scores[LensId.MAIN] = 0.9f
                scores[LensId.TELEPHOTO_3X] = 0.5f
                scores[LensId.ULTRAWIDE] = 0.1f
                scores[LensId.TELEPHOTO_5X] = 0.2f
            }
            SceneType.ARCHITECTURE -> {
                scores[LensId.ULTRAWIDE] = 0.9f
                scores[LensId.MAIN] = 0.6f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            SceneType.MACRO -> {
                scores[LensId.MAIN] = 0.95f
                scores[LensId.TELEPHOTO_3X] = 0.3f
                scores[LensId.ULTRAWIDE] = 0.05f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            SceneType.PET, SceneType.ACTION -> {
                scores[LensId.MAIN] = 0.8f
                scores[LensId.TELEPHOTO_3X] = 0.5f
                scores[LensId.TELEPHOTO_5X] = 0.3f
                scores[LensId.ULTRAWIDE] = 0.2f
            }
            SceneType.NIGHT -> {
                scores[LensId.MAIN] = 0.95f
                scores[LensId.ULTRAWIDE] = 0.4f
                scores[LensId.TELEPHOTO_3X] = 0.2f
                scores[LensId.TELEPHOTO_5X] = 0.1f
            }
            else -> {
                scores[LensId.MAIN] = 0.7f
                scores[LensId.ULTRAWIDE] = 0.3f
                scores[LensId.TELEPHOTO_3X] = 0.3f
                scores[LensId.TELEPHOTO_5X] = 0.2f
            }
        }

        if (analysis.distanceRange == DistanceRange.FAR || analysis.distanceRange == DistanceRange.INFINITY) {
            scores[LensId.TELEPHOTO_5X] = (scores[LensId.TELEPHOTO_5X] ?: 0f) + 0.2f
        }

        applyLuxPenalty(scores, analysis)

        val recommended = scores.maxByOrNull { it.value }?.key ?: LensId.MAIN
        val luxSuffix = if (analysis.ambientLux >= 0f) " · ${analysis.ambientLux.toInt()} lux" else ""
        val reason = "${analysis.sceneType.label} · ${analysis.distanceRange.label}$luxSuffix"

        return LensRecommendation(recommended, scores, reason)
    }

    private fun applyLuxPenalty(scores: MutableMap<LensId, Float>, analysis: SceneAnalysis) {
        val isLowLight = analysis.lighting == LightingCondition.LOW_LIGHT ||
                analysis.lighting == LightingCondition.BLUE_HOUR ||
                (analysis.ambientLux in 0f..50f)

        val isDimIndoor = analysis.lighting == LightingCondition.ARTIFICIAL ||
                (analysis.ambientLux in 50f..200f)

        if (isLowLight) {
            // Telephoto lenses have smaller apertures: 3X f/2.4, 5X f/3.4
            // Main lens f/1.7 gathers 2x more light than 3X, 4x more than 5X
            scores[LensId.TELEPHOTO_5X] = (scores[LensId.TELEPHOTO_5X] ?: 0f) * 0.2f
            scores[LensId.TELEPHOTO_3X] = (scores[LensId.TELEPHOTO_3X] ?: 0f) * 0.4f
            scores[LensId.MAIN] = (scores[LensId.MAIN] ?: 0f) + 0.3f
        } else if (isDimIndoor) {
            scores[LensId.TELEPHOTO_5X] = (scores[LensId.TELEPHOTO_5X] ?: 0f) * 0.5f
            scores[LensId.TELEPHOTO_3X] = (scores[LensId.TELEPHOTO_3X] ?: 0f) * 0.7f
            scores[LensId.MAIN] = (scores[LensId.MAIN] ?: 0f) + 0.15f
        }

        if (analysis.ambientLux > 10000f) {
            scores[LensId.TELEPHOTO_3X] = (scores[LensId.TELEPHOTO_3X] ?: 0f) + 0.1f
            scores[LensId.TELEPHOTO_5X] = (scores[LensId.TELEPHOTO_5X] ?: 0f) + 0.1f
        }
    }

    fun optimizeSettings(analysis: SceneAnalysis): SettingsProfile {
        val baseSettings = getBaseSettings(analysis.sceneType, analysis.lighting)
        val adjusted = adjustForMotion(baseSettings, analysis.motionLevel)
        val distanceAdjusted = adjustForDistance(adjusted, analysis.distanceRange)

        val constraints = getConstraints(distanceAdjusted, analysis.sceneType, analysis.motionLevel)

        val reason = "${analysis.sceneType.label} · ${analysis.lighting.label}"
        return SettingsProfile(distanceAdjusted, reason, constraints)
    }

    /**
     * Whether HDR bracketing should be used given the current motion level.
     * SHAKING (FAST/VERY_FAST) causes bracket frames to misalign, producing
     * ghosting artifacts — fall back to single-frame capture instead.
     */
    fun shouldUseHdr(motionLevel: MotionLevel): Boolean {
        return motionLevel.ordinal < MotionLevel.FAST.ordinal
    }

    /**
     * Whether night mode long-exposure is viable.
     * STATIONARY (STATIC) allows longer exposures; anything more than SLOW
     * needs faster shutter to prevent motion blur.
     */
    fun allowNightLongExposure(motionLevel: MotionLevel): Boolean {
        return motionLevel.ordinal <= MotionLevel.SLOW.ordinal
    }

    private fun getConstraints(settings: CameraSettings, scene: SceneType, motionLevel: MotionLevel = MotionLevel.STATIC): CameraConstraints {
        val baseMinShutter = when (scene) {
            SceneType.ACTION, SceneType.PET -> 500
            SceneType.PORTRAIT -> 125
            SceneType.LANDSCAPE -> 60
            SceneType.NIGHT -> 8
            else -> 60
        }
        // Raise shutter floor when device is shaking to avoid motion blur
        val motionMinShutter = when (motionLevel) {
            MotionLevel.VERY_FAST -> 1000
            MotionLevel.FAST -> 500
            MotionLevel.MODERATE -> 250
            else -> baseMinShutter
        }
        val minShutter = maxOf(baseMinShutter, motionMinShutter)

        // STATIONARY on night scenes: allow slower shutter floor
        val finalMinShutter = if (scene == SceneType.NIGHT && motionLevel == MotionLevel.STATIC) {
            baseMinShutter  // keep the scene-based floor (1/8s)
        } else {
            minShutter
        }

        val maxIso = when (scene) {
            SceneType.ACTION -> 3200
            SceneType.NIGHT -> 6400
            SceneType.LANDSCAPE -> 800
            else -> 1600
        }
        return CameraConstraints(
            minShutterSpeedDenominator = finalMinShutter,
            maxShutterSpeedDenominator = 32000,
            minIso = 50,
            maxIso = maxIso,
            evTargetOffset = settings.exposureCompensation
        )
    }

    private fun getBaseSettings(scene: SceneType, lighting: LightingCondition): CameraSettings {
        return when (scene) {
            SceneType.LANDSCAPE -> landscapeSettings(lighting)
            SceneType.PORTRAIT -> portraitSettings(lighting)
            SceneType.FOOD -> foodSettings(lighting)
            SceneType.NIGHT -> nightSettings(lighting)
            SceneType.MACRO -> macroSettings(lighting)
            SceneType.ARCHITECTURE -> architectureSettings(lighting)
            SceneType.PET -> petSettings(lighting)
            SceneType.ACTION -> actionSettings(lighting)
            SceneType.DOCUMENT -> documentSettings(lighting)
            SceneType.INDOOR -> indoorSettings(lighting)
            else -> autoSettings(lighting)
        }
    }

    private fun landscapeSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200,
            whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 7500, exposureCompensation = 0.3f
        )
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 1000,
            whiteBalanceKelvin = 5000, exposureCompensation = -0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 6200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 30,
            whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 320,
            whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun portraitSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 320,
            whiteBalanceKelvin = 5300, exposureCompensation = 0.3f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200,
            whiteBalanceKelvin = 5000, exposureCompensation = 0.3f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160,
            whiteBalanceKelvin = 6200, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 4200, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        LightingCondition.STUDIO -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
        LightingCondition.BACKLIT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5500, exposureCompensation = 1.0f
        )
        LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 5200, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160,
            whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
    }

    private fun foodSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 200,
            whiteBalanceKelvin = 5000, exposureCompensation = 0.7f
        )
        LightingCondition.OVERCAST -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 5800, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 100,
            whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 4800, exposureCompensation = 0.3f
        )
    }

    private fun nightSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 30,
            whiteBalanceKelvin = 3500, exposureCompensation = 0.3f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 6400, shutterSpeedDenominator = 8,
            whiteBalanceKelvin = 3800, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 3200, shutterSpeedDenominator = 15,
            whiteBalanceKelvin = 3800, exposureCompensation = 0f
        )
    }

    private fun macroSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 5200, exposureCompensation = 0f,
            focusDistance = 0.1f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 4500, exposureCompensation = 0.3f,
            focusDistance = 0.1f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 320,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f,
            focusDistance = 0.1f
        )
    }

    private fun architectureSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
        LightingCondition.BLUE_HOUR -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 7000, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT, LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 4500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun petSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 1000,
            whiteBalanceKelvin = 5300, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 4000, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 800,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun actionSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT, LightingCondition.HARSH_MIDDAY -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 2000,
            whiteBalanceKelvin = 5200, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 1600, shutterSpeedDenominator = 500,
            whiteBalanceKelvin = 4500, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 400, shutterSpeedDenominator = 1000,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
    }

    private fun documentSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 4200, exposureCompensation = 0.3f
        )
        else -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 160,
            whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
    }

    private fun indoorSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.ARTIFICIAL -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 3800, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 4000, exposureCompensation = 0.3f
        )
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 200,
            whiteBalanceKelvin = 5500, exposureCompensation = 0f
        )
        else -> CameraSettings(
            iso = 200, shutterSpeedDenominator = 125,
            whiteBalanceKelvin = 4800, exposureCompensation = 0f
        )
    }

    private fun autoSettings(lighting: LightingCondition) = when (lighting) {
        LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(
            iso = 50, shutterSpeedDenominator = 250,
            whiteBalanceKelvin = 5300, exposureCompensation = 0f
        )
        LightingCondition.LOW_LIGHT -> CameraSettings(
            iso = 800, shutterSpeedDenominator = 60,
            whiteBalanceKelvin = 4200, exposureCompensation = 0f
        )
        LightingCondition.GOLDEN_HOUR -> CameraSettings(
            iso = 100, shutterSpeedDenominator = 200,
            whiteBalanceKelvin = 5500, exposureCompensation = 0.3f
        )
        else -> CameraSettings(iso = 100, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5500)
    }

    private fun adjustForMotion(settings: CameraSettings, motion: MotionLevel): CameraSettings {
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

    private fun adjustForDistance(settings: CameraSettings, distance: DistanceRange): CameraSettings {
        return when (distance) {
            DistanceRange.MACRO -> settings.copy(focusDistance = maxOf(settings.focusDistance, 0.1f))
            DistanceRange.NEAR -> settings.copy(focusDistance = maxOf(settings.focusDistance, 0.5f))
            else -> settings
        }
    }
}
