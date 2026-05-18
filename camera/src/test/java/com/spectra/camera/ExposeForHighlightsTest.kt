package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExposeForHighlightsTest {

    @Test
    fun `computeBracketExposuresForHighlights biases base exposure down`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -1.0f)
        val baseFrame = brackets[1]
        assertThat(baseFrame.first).isLessThan(baseNs)
    }

    @Test
    fun `highlight bias of -1 EV halves base exposure`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -1.0f)
        val baseFrame = brackets[1]
        assertThat(baseFrame.first).isEqualTo(5_000_000L)
    }

    @Test
    fun `highlight bias of -0_5 EV reduces base by sqrt(2)`() {
        val baseNs = 10_000_000L
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = -0.5f)
        val baseFrame = brackets[1]
        val expected = (baseNs / Math.pow(2.0, 0.5)).toLong()
        assertThat(baseFrame.first).isWithin(100_000L).of(expected)
    }

    @Test
    fun `highlight brackets still ordered underexposed to overexposed`() {
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(10_000_000L, 200, evBias = -1.0f)
        assertThat(brackets[0].first).isLessThan(brackets[1].first)
        assertThat(brackets[1].first).isLessThan(brackets[2].first)
    }

    @Test
    fun `highlight brackets keep ISO constant`() {
        val brackets = HdrProcessor.computeBracketExposuresForHighlights(10_000_000L, 400, evBias = -0.5f)
        for (bracket in brackets) {
            assertThat(bracket.second).isEqualTo(400)
        }
    }

    @Test
    fun `zero bias matches normal bracket computation`() {
        val baseNs = 10_000_000L
        val biased = HdrProcessor.computeBracketExposuresForHighlights(baseNs, 200, evBias = 0f)
        val normal = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(biased[0].first).isEqualTo(normal[0].first)
        assertThat(biased[1].first).isEqualTo(normal[1].first)
        assertThat(biased[2].first).isEqualTo(normal[2].first)
    }

    @Test
    fun `computeHighlightEvBias returns negative bias for high contrast`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.5f)
        assertThat(bias).isLessThan(0f)
    }

    @Test
    fun `computeHighlightEvBias returns zero for low contrast`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.05f)
        assertThat(bias).isEqualTo(0f)
    }

    @Test
    fun `computeHighlightEvBias is clamped to -1 EV`() {
        val bias = HdrProcessor.computeHighlightEvBias(sceneContrast = 1.0f)
        assertThat(bias).isAtLeast(-1.0f)
    }

    @Test
    fun `computeHighlightEvBias scales with contrast`() {
        val lowBias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.2f)
        val highBias = HdrProcessor.computeHighlightEvBias(sceneContrast = 0.8f)
        assertThat(highBias).isLessThan(lowBias)
    }

    @Test
    fun `computeShadowBoostStrength is zero for low contrast`() {
        val boost = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.05f)
        assertThat(boost).isEqualTo(0f)
    }

    @Test
    fun `computeShadowBoostStrength increases with contrast`() {
        val low = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.2f)
        val high = HdrProcessor.computeShadowBoostStrength(sceneContrast = 0.7f)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `computeShadowBoostStrength is clamped to 1`() {
        val boost = HdrProcessor.computeShadowBoostStrength(sceneContrast = 1.0f)
        assertThat(boost).isAtMost(1.0f)
    }
}
