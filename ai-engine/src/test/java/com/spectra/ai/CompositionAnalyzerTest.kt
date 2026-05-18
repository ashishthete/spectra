package com.spectra.ai

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CompositionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionAnalyzerTest {

    private val analyzer = CompositionAnalyzer()

    @Test
    fun `CompositionResult defaults to centered subject with no suggestion`() {
        val result = CompositionResult()
        assertEquals(0.5f, result.subjectCentroidX, 0.01f)
        assertEquals(0.5f, result.subjectCentroidY, 0.01f)
        assertEquals(0f, result.thirdsScore, 0.01f)
        assertNull(result.suggestionText)
        assertEquals(ArrowDirection.NONE, result.suggestionArrow)
        assertEquals(0f, result.horizonTiltDegrees, 0.01f)
        assertFalse(result.needsLeveling)
    }

    @Test
    fun `CompositionResult needsLeveling when tilt exceeds 2 degrees`() {
        val result = CompositionResult(horizonTiltDegrees = 3.5f)
        assertTrue(result.needsLeveling)
    }

    @Test
    fun `CompositionResult does not need leveling when tilt under 2 degrees`() {
        val result = CompositionResult(horizonTiltDegrees = 1.5f)
        assertFalse(result.needsLeveling)
    }

    @Test
    fun `saliency map returns 64x64 array`() {
        val pixels = IntArray(128 * 128) { 0xFF202020.toInt() }
        for (y in 10..30) {
            for (x in 10..30) {
                pixels[y * 128 + x] = 0xFFFFFFFF.toInt()
            }
        }
        val saliency = analyzer.computeSaliencyMap(pixels, 128, 128)
        assertEquals(64 * 64, saliency.size)
        val brightRegionSaliency = saliency[8 * 64 + 8]
        val bgSaliency = saliency[50 * 64 + 50]
        assertTrue("Bright region should be more salient", brightRegionSaliency > bgSaliency)
    }

    @Test
    fun `subject on thirds intersection scores high`() {
        val result = analyzer.scoreThirdsPlacement(1f / 3f, 1f / 3f)
        assertTrue("Thirds score should be > 0.8 for perfect placement", result >= 0.8f)
    }

    @Test
    fun `subject dead center scores low`() {
        val result = analyzer.scoreThirdsPlacement(0.5f, 0.5f)
        assertTrue("Center placement should score < 0.5", result < 0.5f)
    }

    @Test
    fun `analyze generates leveling hint when horizon tilted`() {
        val pixels = IntArray(64 * 64) { 0xFF808080.toInt() }
        val result = analyzer.analyze(pixels, 64, 64, emptyList(), 4.5f)
        assertTrue(result.needsLeveling)
        assertTrue(result.suggestionText?.contains("horizon") == true || result.suggestionText?.contains("level") == true)
    }

    @Test
    fun `analyze generates move hint for off-thirds subject`() {
        val pixels = IntArray(128 * 128) { 0xFF101010.toInt() }
        for (y in 55..73) {
            for (x in 55..73) {
                pixels[y * 128 + x] = 0xFFFFFFFF.toInt()
            }
        }
        val result = analyzer.analyze(pixels, 128, 128, emptyList(), 0f)
        assertFalse(result.suggestionText.isNullOrEmpty())
    }
}
