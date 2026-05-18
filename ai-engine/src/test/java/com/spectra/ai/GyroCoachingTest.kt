package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.*
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.SceneType
import org.junit.Test

class GyroCoachingTest {

    @Test
    fun `camera shake gives hold steady coaching`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.MODERATE,
            motionType = MotionDetector.MotionType.CAMERA_SHAKE,
            distanceRange = DistanceRange.FAR
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.LANDSCAPE)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).contains("steady")
    }

    @Test
    fun `subject motion gives burst coaching`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.PET,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.FAST,
            motionType = MotionDetector.MotionType.SUBJECT_MOTION,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.PETS)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).contains("burst")
    }

    @Test
    fun `pan motion gives panning coaching`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.ACTION,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.MODERATE,
            motionType = MotionDetector.MotionType.PAN,
            distanceRange = DistanceRange.MID
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.ACTION)
        assertThat(hint).isNotNull()
        assertThat(hint!!.text.lowercase()).contains("track")
    }

    @Test
    fun `static motion type does not override preset hints`() {
        val engine = CoachingEngine()
        val analysis = SceneAnalysis(
            sceneType = SceneType.FOOD,
            confidence = 0.9f,
            lighting = LightingCondition.BRIGHT_DAYLIGHT,
            motionLevel = MotionLevel.STATIC,
            motionType = MotionDetector.MotionType.STATIC,
            distanceRange = DistanceRange.NEAR
        )
        val hint = engine.generateCoaching(analysis, CameraPreset.FOOD)
        assertThat(hint).isNotNull()
    }
}
