package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.LightingCondition
import org.junit.Test

class LightingAnalyzerTest {

    private val analyzer = LightingAnalyzer()

    @Test
    fun `very low brightness maps to LOW_LIGHT`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 15f,
            exposureTimeNs = 100_000_000L,
            iso = 3200,
            colorTemperature = 3000
        )
        assertThat(result).isEqualTo(LightingCondition.LOW_LIGHT)
    }

    @Test
    fun `high brightness and warm temp maps to GOLDEN_HOUR`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 140f,
            exposureTimeNs = 5_000_000L,
            iso = 100,
            colorTemperature = 3500
        )
        assertThat(result).isEqualTo(LightingCondition.GOLDEN_HOUR)
    }

    @Test
    fun `very high brightness maps to HARSH_MIDDAY`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 230f,
            exposureTimeNs = 1_000_000L,
            iso = 50,
            colorTemperature = 5500
        )
        assertThat(result).isEqualTo(LightingCondition.HARSH_MIDDAY)
    }

    @Test
    fun `moderate brightness maps to BRIGHT_DAYLIGHT`() {
        val result = analyzer.analyzeFromMetadata(
            avgBrightness = 160f,
            exposureTimeNs = 3_000_000L,
            iso = 100,
            colorTemperature = 5600
        )
        assertThat(result).isEqualTo(LightingCondition.BRIGHT_DAYLIGHT)
    }
}
