package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureExplanationTest {

    @Test
    fun `CaptureExplanation formats ISO and shutter correctly`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 2_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            reasons = listOf("bright daylight", "fast subject detected")
        )
        assertThat(explanation.isoLabel).isEqualTo("ISO 100")
        assertThat(explanation.shutterLabel).isEqualTo("1/500s")
    }

    @Test
    fun `shutterLabel formats slow shutter correctly`() {
        val explanation = CaptureExplanation(
            iso = 800,
            shutterSpeedNs = 500_000_000L,
            sceneLabel = "NIGHT SCENE",
            lightingLabel = "LOW LIGHT",
            reasons = listOf("low light detected", "long exposure stacking")
        )
        assertThat(explanation.shutterLabel).isEqualTo("1/2s")
    }

    @Test
    fun `shutterLabel handles 1 second exposure`() {
        val explanation = CaptureExplanation(
            iso = 400,
            shutterSpeedNs = 1_000_000_000L,
            sceneLabel = "NIGHT SCENE",
            lightingLabel = "LOW LIGHT",
            reasons = listOf("night mode active")
        )
        assertThat(explanation.shutterLabel).isEqualTo("1s")
    }

    @Test
    fun `summary returns first 3 reasons only`() {
        val explanation = CaptureExplanation(
            iso = 200,
            shutterSpeedNs = 10_000_000L,
            sceneLabel = "PORTRAIT",
            lightingLabel = "GOLDEN HOUR",
            reasons = listOf("golden hour light", "face detected", "shallow depth", "extra reason")
        )
        assertThat(explanation.summaryReasons).hasSize(3)
        assertThat(explanation.summaryReasons).doesNotContain("extra reason")
    }

    @Test
    fun `headline formats as expected`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 2_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            reasons = listOf("bright daylight")
        )
        assertThat(explanation.headline).contains("ISO 100")
        assertThat(explanation.headline).contains("1/500s")
    }

    @Test
    fun `empty reasons produces empty summary`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 10_000_000L,
            sceneLabel = "UNKNOWN",
            lightingLabel = "",
            reasons = emptyList()
        )
        assertThat(explanation.summaryReasons).isEmpty()
    }

    @Test
    fun `HudState defaults to null captureExplanation`() {
        val state = HudState()
        assertThat(state.captureExplanation).isNull()
    }

    @Test
    fun `isHdrApplied included in explanation`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 5_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "DAYLIGHT",
            isHdrApplied = true,
            reasons = listOf("high contrast scene", "HDR bracketing applied")
        )
        assertThat(explanation.isHdrApplied).isTrue()
    }
}
