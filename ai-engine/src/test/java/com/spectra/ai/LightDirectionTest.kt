package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class LightDirectionTest {

    private lateinit var analyzer: LightingAnalyzer

    @Before
    fun setup() {
        analyzer = LightingAnalyzer()
    }

    private fun makePixels(width: Int, height: Int, brightness: (x: Int, y: Int) -> Int): IntArray {
        return IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            val v = brightness(x, y).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test
    fun `bright on right side yields angle near 0 degrees`() {
        val width = 64; val height = 64
        val pixels = makePixels(width, height) { x, _ -> (x * 255 / width) }
        val result = analyzer.computeLightDirection(pixels, width, height)
        assertThat(result.angleDegrees).isWithin(30f).of(0f)
        assertThat(result.strength).isGreaterThan(0.15f)
    }

    @Test
    fun `bright on top yields angle near 90 degrees`() {
        val width = 64; val height = 64
        val pixels = makePixels(width, height) { _, y -> (255 - y * 255 / height) }
        val result = analyzer.computeLightDirection(pixels, width, height)
        assertThat(result.angleDegrees).isWithin(30f).of(90f)
        assertThat(result.strength).isGreaterThan(0.15f)
    }

    @Test
    fun `uniform brightness yields low strength`() {
        val width = 64; val height = 64
        val pixels = makePixels(width, height) { _, _ -> 128 }
        val result = analyzer.computeLightDirection(pixels, width, height)
        assertThat(result.strength).isLessThan(0.2f)
    }

    @Test
    fun `empty pixels returns zero strength`() {
        val result = analyzer.computeLightDirection(IntArray(0), 0, 0)
        assertThat(result.strength).isEqualTo(0f)
    }
}
