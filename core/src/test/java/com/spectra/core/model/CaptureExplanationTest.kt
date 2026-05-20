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

    @Test
    fun `stage labels map correctly`() {
        val explanation = CaptureExplanation(
            iso = 400,
            shutterSpeedNs = 8_000_000L,
            sceneLabel = "PORTRAIT",
            lightingLabel = "INDOOR",
            appliedStages = listOf("noise_reduction", "tone_curve", "portrait_bokeh", "skin_protection"),
            hdrFrameCount = 0
        )
        val labels = explanation.stageLabels
        assertThat(labels).hasSize(4)
        assertThat(labels[0]).contains("noise")
        assertThat(labels[1]).contains("tone")
        assertThat(labels[2]).contains("depth")
        assertThat(labels[3]).contains("skin")
    }

    @Test
    fun `hdr stage label includes frame count`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 4_000_000L,
            sceneLabel = "LANDSCAPE",
            lightingLabel = "BRIGHT",
            appliedStages = listOf("hdr_bracket"),
            hdrFrameCount = 5
        )
        assertThat(explanation.stageLabels[0]).contains("5-frame")
    }

    @Test
    fun `processing badge includes active features`() {
        val explanation = CaptureExplanation(
            iso = 800,
            shutterSpeedNs = 16_000_000L,
            sceneLabel = "PORTRAIT",
            lightingLabel = "LOW",
            isHdrApplied = true,
            isPortraitBokeh = true,
            beautyApplied = true
        )
        assertThat(explanation.processingBadge).contains("HDR")
        assertThat(explanation.processingBadge).contains("Portrait")
        assertThat(explanation.processingBadge).contains("Beauty")
    }

    @Test
    fun `empty processing badge shows Auto`() {
        val explanation = CaptureExplanation(
            iso = 100,
            shutterSpeedNs = 4_000_000L,
            sceneLabel = "",
            lightingLabel = ""
        )
        assertThat(explanation.processingBadge).isEqualTo("Auto")
    }
}
