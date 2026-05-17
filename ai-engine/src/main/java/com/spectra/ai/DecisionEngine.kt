package com.spectra.ai

import com.spectra.ai.model.*
import com.spectra.core.model.CameraSettings
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DecisionEngine @Inject constructor() {

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

        val recommended = scores.maxByOrNull { it.value }?.key ?: LensId.MAIN
        val reason = "${analysis.sceneType.label} · ${analysis.distanceRange.label}"

        return LensRecommendation(recommended, scores, reason)
    }

    fun optimizeSettings(analysis: SceneAnalysis): SettingsProfile {
        val baseSettings = getBaseSettings(analysis.sceneType, analysis.lighting)
        val adjusted = adjustForMotion(baseSettings, analysis.motionLevel)

        val reason = "${analysis.sceneType.label} · ${analysis.lighting.label}"
        return SettingsProfile(adjusted, reason)
    }

    private fun getBaseSettings(scene: SceneType, lighting: LightingCondition): CameraSettings {
        return when (scene) {
            SceneType.LANDSCAPE -> when (lighting) {
                LightingCondition.GOLDEN_HOUR -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500, exposureCompensation = 0.3f)
                LightingCondition.HARSH_MIDDAY -> CameraSettings(iso = 100, shutterSpeedDenominator = 1000, whiteBalanceKelvin = 5200)
                LightingCondition.OVERCAST -> CameraSettings(iso = 200, shutterSpeedDenominator = 250, whiteBalanceKelvin = 6500)
                LightingCondition.LOW_LIGHT -> CameraSettings(iso = 800, shutterSpeedDenominator = 30, whiteBalanceKelvin = 4000)
                else -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            }
            SceneType.PORTRAIT -> when (lighting) {
                LightingCondition.BRIGHT_DAYLIGHT -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
                LightingCondition.GOLDEN_HOUR -> CameraSettings(iso = 100, shutterSpeedDenominator = 200, whiteBalanceKelvin = 5200, exposureCompensation = 0.3f)
                LightingCondition.LOW_LIGHT -> CameraSettings(iso = 400, shutterSpeedDenominator = 60, whiteBalanceKelvin = 4500)
                LightingCondition.ARTIFICIAL -> CameraSettings(iso = 400, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4000)
                else -> CameraSettings(iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 5500)
            }
            SceneType.FOOD -> CameraSettings(iso = 200, shutterSpeedDenominator = 125, whiteBalanceKelvin = 4500, exposureCompensation = 0.3f)
            SceneType.NIGHT -> CameraSettings(iso = 1600, shutterSpeedDenominator = 15, whiteBalanceKelvin = 4000)
            SceneType.MACRO -> CameraSettings(iso = 200, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            SceneType.ARCHITECTURE -> CameraSettings(iso = 100, shutterSpeedDenominator = 250, whiteBalanceKelvin = 5500)
            SceneType.PET, SceneType.ACTION -> CameraSettings(iso = 400, shutterSpeedDenominator = 500, whiteBalanceKelvin = 5500)
            else -> CameraSettings()
        }
    }

    private fun adjustForMotion(settings: CameraSettings, motion: MotionLevel): CameraSettings {
        return when (motion) {
            MotionLevel.FAST, MotionLevel.VERY_FAST -> CameraSettings(
                iso = maxOf(settings.iso, 800),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 1000),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation
            )
            MotionLevel.MODERATE -> CameraSettings(
                iso = maxOf(settings.iso, 400),
                shutterSpeedDenominator = maxOf(settings.shutterSpeedDenominator, 500),
                whiteBalanceKelvin = settings.whiteBalanceKelvin,
                exposureCompensation = settings.exposureCompensation
            )
            else -> settings
        }
    }
}
