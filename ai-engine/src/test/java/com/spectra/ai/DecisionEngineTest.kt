package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.LensId
import com.spectra.core.model.SceneType
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine()

    @Test
    fun `landscape golden hour recommends main lens ISO 100`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val lens = engine.recommendLens(analysis)
        val settings = engine.optimizeSettings(analysis)

        assertThat(lens.recommended).isEqualTo(LensId.MAIN)
        assertThat(settings.settings.iso).isEqualTo(50)
        assertThat(settings.settings.whiteBalanceKelvin).isEqualTo(6000)
    }

    @Test
    fun `portrait static recommends telephoto 3x`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.TELEPHOTO_3X)
    }

    @Test
    fun `pet fast motion recommends main lens high shutter`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.shutterSpeedDenominator).isAtLeast(500)
        assertThat(settings.settings.iso).isAtLeast(400)
    }

    @Test
    fun `food near distance recommends main lens warm WB`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.FOOD,
            confidence = 0.9f,
            lighting = LightingCondition.ARTIFICIAL,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.whiteBalanceKelvin).isAtLeast(3500)
    }

    @Test
    fun `night scene recommends low shutter high ISO`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val settings = engine.optimizeSettings(analysis)
        assertThat(settings.settings.iso).isAtLeast(800)
        assertThat(settings.settings.shutterSpeedDenominator).isAtMost(30)
    }

    @Test
    fun `architecture recommends ultrawide`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.ARCHITECTURE,
            confidence = 0.82f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.ULTRAWIDE)
    }

    @Test
    fun `macro recommends main lens`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.MACRO,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MACRO
        )
        val lens = engine.recommendLens(analysis)
        assertThat(lens.recommended).isEqualTo(LensId.MAIN)
    }
}
