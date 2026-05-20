package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkySegmenterTest {

    @Test
    fun `blue sky in upper half is detected`() {
        val w = 100; val h = 100
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            if (y < 40) {
                (0xFF shl 24) or (100 shl 16) or (140 shl 8) or 220
            } else {
                (0xFF shl 24) or (50 shl 16) or (120 shl 8) or 30
            }
        }
        val mask = SkySegmenter.detectSkyMask(pixels, w, h)
        val fraction = SkySegmenter.computeSkyFraction(mask)
        assertThat(fraction).isGreaterThan(0.1f)
        assertThat(fraction).isLessThan(0.6f)
    }

    @Test
    fun `no sky in dark image`() {
        val w = 50; val h = 50
        val pixels = IntArray(w * h) { (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 25 }
        val mask = SkySegmenter.detectSkyMask(pixels, w, h)
        val fraction = SkySegmenter.computeSkyFraction(mask)
        assertThat(fraction).isEqualTo(0f)
    }

    @Test
    fun `GND filter reduces sky brightness`() {
        val w = 50; val h = 50
        val skyColor = (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 220
        val pixels = IntArray(w * h) { skyColor }
        val mask = BooleanArray(w * h) { (it / w) < 25 }

        val originalB = pixels[0] and 0xFF
        SkySegmenter.applyGndFilter(pixels, w, h, mask, 0.5f)

        val newB = pixels[0] and 0xFF
        assertThat(newB).isLessThan(originalB)
    }

    @Test
    fun `GND filter does not affect non-sky pixels`() {
        val w = 50; val h = 50
        val groundColor = (0xFF shl 24) or (50 shl 16) or (120 shl 8) or 30
        val pixels = IntArray(w * h) { groundColor }
        val mask = BooleanArray(w * h) { false }

        SkySegmenter.applyGndFilter(pixels, w, h, mask, 0.5f)
        assertThat(pixels[w * 30]).isEqualTo(groundColor)
    }

    @Test
    fun `sky recovery boosts dark foreground`() {
        val w = 50; val h = 50
        val darkPixel = (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30
        val pixels = IntArray(w * h) { darkPixel }
        val mask = BooleanArray(w * h) { false }

        val originalR = (pixels[0] shr 16) and 0xFF
        SkySegmenter.applySkyRecovery(pixels, w, h, mask, 0.3f)
        val newR = (pixels[0] shr 16) and 0xFF
        assertThat(newR).isGreaterThan(originalR)
    }

    @Test
    fun `overcast sky is detected`() {
        val w = 50; val h = 50
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            if (y < 20) {
                (0xFF shl 24) or (190 shl 16) or (195 shl 8) or 200
            } else {
                (0xFF shl 24) or (50 shl 16) or (80 shl 8) or 30
            }
        }
        val mask = SkySegmenter.detectSkyMask(pixels, w, h)
        val fraction = SkySegmenter.computeSkyFraction(mask)
        assertThat(fraction).isGreaterThan(0.05f)
    }

    @Test
    fun `refineMask removes sky below first non-sky row`() {
        val w = 10; val h = 10
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            if (y == 0 || y == 2) {
                (0xFF shl 24) or (100 shl 16) or (140 shl 8) or 220
            } else {
                (0xFF shl 24) or (50 shl 16) or (50 shl 8) or 30
            }
        }
        val mask = SkySegmenter.detectSkyMask(pixels, w, h)
        for (x in 0 until w) {
            assertThat(mask[2 * w + x]).isFalse()
        }
    }
}
