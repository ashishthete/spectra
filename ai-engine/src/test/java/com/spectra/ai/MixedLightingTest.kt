package com.spectra.ai

import com.spectra.ai.model.LightingCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedLightingTest {

    private val analyzer = LightingAnalyzer()

    @Test
    fun `MIXED lighting condition exists`() {
        val mixed = LightingCondition.MIXED
        assertNotNull(mixed)
        assertEquals("MIXED", mixed.label)
    }

    @Test
    fun `MIXED is distinct from other conditions`() {
        val conditions = LightingCondition.entries
        val mixedCount = conditions.count { it == LightingCondition.MIXED }
        assertEquals(1, mixedCount)
    }

    @Test
    fun `detectMixedLighting returns false for uniform color temperature`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = analyzer.detectMixedLighting(pixels, w, h)
        assertEquals(false, result.isMixed)
    }

    @Test
    fun `detectMixedLighting returns true for mixed warm and cool regions`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h)
        for (y in 0 until h / 2) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (200 shl 16) or (140 shl 8) or 80
            }
        }
        for (y in h / 2 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or (80 shl 16) or (140 shl 8) or 210
            }
        }
        val result = analyzer.detectMixedLighting(pixels, w, h)
        assertEquals(true, result.isMixed)
        assertTrue("CT variance ${result.ctVariance} should exceed 1500", result.ctVariance > 1500f)
    }

    @Test
    fun `detectMixedLighting returns 16 region CTs for 4x4 grid`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val result = analyzer.detectMixedLighting(pixels, w, h)
        assertEquals(16, result.regionCts.size)
    }
}
