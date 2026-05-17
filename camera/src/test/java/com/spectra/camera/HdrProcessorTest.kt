package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HdrProcessorTest {

    @Test
    fun `computeBracketExposures returns 3 values`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result).hasSize(3)
    }

    @Test
    fun `bracket exposures are ordered underexposed to overexposed`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 200)
        assertThat(result[0].first).isLessThan(result[1].first)
        assertThat(result[1].first).isLessThan(result[2].first)
    }

    @Test
    fun `bracket middle exposure matches base`() {
        val baseNs = 10_000_000L
        val baseIso = 200
        val result = HdrProcessor.computeBracketExposures(baseNs, baseIso)
        assertThat(result[1].first).isEqualTo(baseNs)
        assertThat(result[1].second).isEqualTo(baseIso)
    }

    @Test
    fun `bracket under-exposure is base divided by 4`() {
        val baseNs = 40_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(result[0].first).isEqualTo(10_000_000L)
    }

    @Test
    fun `bracket over-exposure is base multiplied by 4`() {
        val baseNs = 10_000_000L
        val result = HdrProcessor.computeBracketExposures(baseNs, 200)
        assertThat(result[2].first).isEqualTo(40_000_000L)
    }

    @Test
    fun `bracket keeps ISO constant`() {
        val result = HdrProcessor.computeBracketExposures(10_000_000L, 400)
        assertThat(result[0].second).isEqualTo(400)
        assertThat(result[1].second).isEqualTo(400)
        assertThat(result[2].second).isEqualTo(400)
    }

    @Test
    fun `computeWeight contrast enhances edges`() {
        val flat = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val edgy = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f)
        val flatWeight = HdrProcessor.contrastWeight(flat, 3, 3, 1, 1)
        val edgyWeight = HdrProcessor.contrastWeight(edgy, 3, 3, 1, 1)
        assertThat(edgyWeight).isGreaterThan(flatWeight)
    }

    @Test
    fun `wellExposedness peaks at 0_5`() {
        val at05 = HdrProcessor.wellExposednessWeight(0.5f)
        val at01 = HdrProcessor.wellExposednessWeight(0.1f)
        val at09 = HdrProcessor.wellExposednessWeight(0.9f)
        assertThat(at05).isGreaterThan(at01)
        assertThat(at05).isGreaterThan(at09)
    }

    @Test
    fun `saturationWeight is higher for colorful pixels`() {
        val colorful = HdrProcessor.saturationWeight(0.8f, 0.2f, 0.1f)
        val gray = HdrProcessor.saturationWeight(0.5f, 0.5f, 0.5f)
        assertThat(colorful).isGreaterThan(gray)
    }
}
