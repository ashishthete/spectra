package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.CameraMode
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
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
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
        val hint = engine.generateCoaching(analysis, CameraMode.PORT)
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
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("BURST")
    }

    @Test
    fun `night mode static suggests stability`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.NIGHT,
            confidence = 0.88f,
            lighting = LightingCondition.LOW_LIGHT,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.FAR
        )
        val hint = engine.generateCoaching(analysis, CameraMode.NIGHT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.arrow).isEqualTo(ArrowDirection.STEADY)
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
        val hint = engine.generateCoaching(analysis, CameraMode.NIGHT)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("HOLD STEADY")
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
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
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
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text).contains("BACKLIT")
    }

    @Test
    fun `unstable analysis returns null`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.3f
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PHOTO)
        assertThat(hint).isNull()
    }

    @Test
    fun `PRO mode still generates coaching`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.GOLDEN_HOUR,
            motionLevel = MotionLevel.STATIC,
            distanceRange = DistanceRange.INFINITY
        )
        val hint = engine.generateCoaching(analysis, CameraMode.PRO)
        assertThat(hint).isNotNull()
    }
}
