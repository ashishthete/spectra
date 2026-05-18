package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LightingAnalyzerCtTest {

    private val analyzer = LightingAnalyzer()

    @Test
    fun `pixel CT estimates warm scene correctly`() {
        val pixels = IntArray(100) { pixel(200, 120, 80) }
        val ct = analyzer.estimateColorTemperature(pixels)
        assertThat(ct).isLessThan(4000)
    }

    @Test
    fun `pixel CT estimates daylight correctly`() {
        val pixels = IntArray(100) { pixel(180, 180, 180) }
        val ct = analyzer.estimateColorTemperature(pixels)
        assertThat(ct).isIn(4500..6500)
    }

    @Test
    fun `pixel CT estimates cool light correctly`() {
        val pixels = IntArray(100) { pixel(100, 140, 200) }
        val ct = analyzer.estimateColorTemperature(pixels)
        assertThat(ct).isGreaterThan(6500)
    }

    @Test
    fun `pixel CT interpolates between breakpoints`() {
        val warmPixels = IntArray(100) { pixel(220, 130, 80) }
        val neutralPixels = IntArray(100) { pixel(160, 160, 150) }
        val ctWarm = analyzer.estimateColorTemperature(warmPixels)
        val ctNeutral = analyzer.estimateColorTemperature(neutralPixels)
        assertThat(ctNeutral).isGreaterThan(ctWarm)
    }

    @Test
    fun `pixel CT handles empty array`() {
        val ct = analyzer.estimateColorTemperature(IntArray(0))
        assertThat(ct).isEqualTo(5500)
    }

    @Test
    fun `pixel CT ignores very dark and bright pixels`() {
        val mixed = IntArray(300)
        for (i in 0 until 100) mixed[i] = pixel(5, 5, 5)
        for (i in 100 until 200) mixed[i] = pixel(250, 250, 250)
        for (i in 200 until 300) mixed[i] = pixel(180, 180, 180)
        val ct = analyzer.estimateColorTemperature(mixed)
        assertThat(ct).isIn(4500..6500)
    }

    @Test
    fun `brightness analysis returns correct range`() {
        val brightPixels = IntArray(100) { pixel(220, 220, 220) }
        val brightness = analyzer.analyzeBrightness(brightPixels, 10, 10)
        assertThat(brightness).isGreaterThan(200f)
    }

    @Test
    fun `brightness analysis handles dark scene`() {
        val darkPixels = IntArray(100) { pixel(20, 20, 20) }
        val brightness = analyzer.analyzeBrightness(darkPixels, 10, 10)
        assertThat(brightness).isLessThan(30f)
    }

    @Test
    fun `brightness analysis handles empty array`() {
        val brightness = analyzer.analyzeBrightness(IntArray(0), 0, 0)
        assertThat(brightness).isEqualTo(0f)
    }

    private fun pixel(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
