package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.LightingCondition
import org.junit.Before
import org.junit.Test

class LightingAnalyzerTest {

    private lateinit var analyzer: LightingAnalyzer

    @Before
    fun setup() {
        analyzer = LightingAnalyzer()
    }

    private fun stabilize(brightness: Float, exposureNs: Long, iso: Int, ct: Int): LightingCondition {
        var result = LightingCondition.UNKNOWN
        repeat(6) {
            result = analyzer.analyzeFromMetadata(brightness, exposureNs, iso, ct)
        }
        return result
    }

    @Test
    fun `very low brightness maps to LOW_LIGHT`() {
        val result = stabilize(15f, 100_000_000L, 3200, 3000)
        assertThat(result).isEqualTo(LightingCondition.LOW_LIGHT)
    }

    @Test
    fun `high brightness and warm temp maps to GOLDEN_HOUR`() {
        val result = stabilize(140f, 5_000_000L, 100, 3500)
        assertThat(result).isEqualTo(LightingCondition.GOLDEN_HOUR)
    }

    @Test
    fun `very high brightness maps to HARSH_MIDDAY`() {
        val result = stabilize(230f, 1_000_000L, 50, 5500)
        assertThat(result).isEqualTo(LightingCondition.HARSH_MIDDAY)
    }

    @Test
    fun `moderate brightness maps to BRIGHT_DAYLIGHT`() {
        val result = stabilize(160f, 3_000_000L, 100, 5600)
        assertThat(result).isEqualTo(LightingCondition.BRIGHT_DAYLIGHT)
    }
}
