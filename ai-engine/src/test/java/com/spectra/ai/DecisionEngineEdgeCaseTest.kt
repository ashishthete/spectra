package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import org.junit.Test

class DecisionEngineEdgeCaseTest {

    private val engine = DecisionEngine()

    @Test
    fun `very fast motion always boosts ISO and shutter`() {
        for (scene in listOf(SceneType.LANDSCAPE, SceneType.PORTRAIT, SceneType.FOOD, SceneType.INDOOR)) {
            val analysis = SceneAnalysis(
                sceneType = scene,
                confidence = 0.8f,
                lighting = LightingCondition.BRIGHT_DAYLIGHT,
                motionLevel = MotionLevel.VERY_FAST,
                distanceRange = DistanceRange.MID
            )
            val settings = engine.optimizeSettings(analysis)
            assertThat(settings.settings.iso).isAtLeast(800)
            assertThat(settings.settings.shutterSpeedDenominator).isAtLeast(1000)
        }
    }

    @Test
    fun `static motion does not inflate ISO beyond base`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.iso).isAtMost(100)
    }

    @Test
    fun `macro distance sets focus distance`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.MACRO,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MACRO
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.focusDistance).isGreaterThan(0f)
    }

    @Test
    fun `far distance boosts telephoto 5x score`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val lens = engine.recommendLens(analysis)
        val baseScore = lens.scores[LensId.TELEPHOTO_5X] ?: 0f
        assertThat(baseScore).isGreaterThan(0.3f)
    }

    @Test
    fun `all scene types produce valid settings`() {
        for (scene in SceneType.entries) {
            for (lighting in LightingCondition.entries) {
                val analysis = SceneAnalysis(
                    sceneType = scene,
                    confidence = 0.8f,
                    lighting = lighting,
                    motionLevel = MotionLevel.STATIC,
                    distanceRange = DistanceRange.MID
                )
                val settings = engine.optimizeSettings(analysis)
                assertThat(settings.settings.iso).isIn(50..12800)
                assertThat(settings.settings.shutterSpeedDenominator).isIn(1..8000)
                assertThat(settings.settings.whiteBalanceKelvin).isIn(2300..10000)
            }
        }
    }

    @Test
    fun `action low light still ensures fast shutter`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.ACTION,
            confidence = 0.8f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.shutterSpeedDenominator).isAtLeast(500)
    }

    @Test
    fun `document scene has positive EV compensation`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.DOCUMENT,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.exposureCompensation).isAtLeast(0f)
    }

    @Test
    fun `backlit portrait has positive EV compensation`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BACKLIT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.exposureCompensation).isGreaterThan(0f)
    }

    @Test
    fun `low light heavily penalizes telephoto lenses`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.8f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID,
            ambientLux = 20f
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.scores[LensId.TELEPHOTO_5X]!!).isLessThan(0.1f)
        assertThat(lens.scores[LensId.TELEPHOTO_3X]!!).isLessThan(0.4f)
        assertThat(lens.recommended).isEqualTo(LensId.MAIN)
    }

    @Test
    fun `dim indoor moderately penalizes telephoto lenses`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.8f,
            lighting = LightingCondition.ARTIFICIAL,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID,
            ambientLux = 100f
        )
        val lens = engine.recommendLens(analysis)
        val brightAnalysis = analysis.copy(
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            ambientLux = 5000f
        )
        val brightLens = engine.recommendLens(brightAnalysis)
        assertThat(lens.scores[LensId.TELEPHOTO_3X]!!)
            .isLessThan(brightLens.scores[LensId.TELEPHOTO_3X]!!)
    }

    @Test
    fun `bright sunlight boosts telephoto lenses`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR,
            ambientLux = 15000f
        )
        val lens = engine.recommendLens(analysis)
        val indoorAnalysis = analysis.copy(
            lighting = LightingCondition.OVERCAST,
            ambientLux = 500f
        )
        val indoorLens = engine.recommendLens(indoorAnalysis)
        assertThat(lens.scores[LensId.TELEPHOTO_5X]!!)
            .isGreaterThan(indoorLens.scores[LensId.TELEPHOTO_5X]!!)
    }

    @Test
    fun `lux penalty reason includes lux value`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID,
            ambientLux = 8000f
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.reason).contains("8000 lux")
    }
}
