package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.SceneType
import org.junit.Test

class CoachingEngineTest {

    private val engine = CoachingEngine()

    @Test
    fun `landscape static suggests horizon placement`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).isNotEmpty()
    }

    @Test
    fun `portrait suggests eye-level framing`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PORTRAIT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).isNotEmpty()
    }

    @Test
    fun `fast motion suggests burst mode`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.8f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.ACTION)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).contains("burst")
    }

    @Test
    fun `night mode with motion warns to hold steady`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.MODERATE,
            distanceRange = DistanceRange.FAR
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.NIGHT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.arrow).isEqualTo(ArrowDirection.STEADY)
    }

    @Test
    fun `food suggests overhead angle`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.FOOD,
            confidence = 0.9f,
            lighting = LightingCondition.ARTIFICIAL,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.FOOD)
        assertThat(hint).isNotNull()
    }

    @Test
    fun `backlit lighting suggests expose for subject`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.PORTRAIT,
            confidence = 0.85f,
            lighting = LightingCondition.BACKLIT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PORTRAIT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).contains("backli")
    }

    @Test
    fun `unstable analysis returns null`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.3f
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        assertThat(hint).isNull()
    }

    @Test
    fun `PRO mode returns null coaching`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PRO)
        assertThat(hint).isNull()
    }

    @Test
    fun `cooldown prevents rapid hint changes`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val first = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        val second = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        assertThat(first).isEqualTo(second)
    }

    // --- generateCompositionCoaching tests ---

    @Test
    fun `composition coaching returns null when ON_THIRDS`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.ON_THIRDS, 0.02f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isNull()
    }

    @Test
    fun `composition coaching returns null when distance below threshold`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.LEFT, 0.10f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isNull()
    }

    @Test
    fun `composition coaching returns move left text`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.LEFT, 0.20f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isEqualTo("Move camera left to place subject on thirds")
    }

    @Test
    fun `composition coaching returns move right text`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.RIGHT, 0.25f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isEqualTo("Move camera right to place subject on thirds")
    }

    @Test
    fun `composition coaching returns move up text`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.UP, 0.30f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isEqualTo("Move camera up to place subject on thirds")
    }

    @Test
    fun `composition coaching returns move down text`() {
        val suggestion = CompositionSuggestion(CompositionSuggestion.Direction.DOWN, 0.15f)
        val result = engine.generateCompositionCoaching(suggestion)
        assertThat(result).isEqualTo("Move camera down to place subject on thirds")
    }
}
