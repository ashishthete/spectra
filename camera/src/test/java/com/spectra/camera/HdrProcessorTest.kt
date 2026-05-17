package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HdrProcessorTest {

    private val processor = HdrProcessor()

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

    @Test
    fun `mertensFusion with single frame returns copy`() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = processor.mertensFusion(listOf(pixels), w, h)
        assertThat(result).hasLength(w * h)
        for (i in result.indices) {
            assertThat(result[i]).isEqualTo(pixels[i])
        }
    }

    @Test
    fun `mertensFusion with empty list returns empty`() {
        val result = processor.mertensFusion(emptyList(), 0, 0)
        assertThat(result).hasLength(0)
    }

    @Test
    fun `mertensFusion of three frames produces valid pixels`() {
        val w = 8; val h = 8; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 240 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        assertThat(result).hasLength(n)
        for (pixel in result) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertThat(r).isIn(0..255)
            assertThat(g).isIn(0..255)
            assertThat(b).isIn(0..255)
        }
    }

    @Test
    fun `mertensFusion prefers well-exposed mid frame`() {
        val w = 4; val h = 4; val n = w * h
        val dark = IntArray(n) { (0xFF shl 24) or (10 shl 16) or (10 shl 8) or 10 }
        val mid = IntArray(n) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val bright = IntArray(n) { (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250 }
        val result = processor.mertensFusion(listOf(dark, mid, bright), w, h)
        val avgR = result.map { (it shr 16) and 0xFF }.average()
        assertThat(avgR).isGreaterThan(50.0)
        assertThat(avgR).isLessThan(200.0)
    }

    @Test
    fun `alignFrame with no motion returns same pixels`() {
        val w = 16; val h = 16; val n = w * h
        val frame = IntArray(n) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x * 16) shl 16) or ((y * 16) shl 8) or 128
        }
        val aligned = processor.alignFrame(frame, frame, w, h)
        for (i in aligned.indices) {
            assertThat(aligned[i]).isEqualTo(frame[i])
        }
    }
}
