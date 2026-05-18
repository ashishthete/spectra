package com.spectra.ai

import android.graphics.RectF
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

    // --- analyzeSubjectPosition tests ---

    @Test
    fun `analyzeSubjectPosition returns ON_THIRDS when no faces`() {
        val result = analyzer.analyzeSubjectPosition(emptyList())
        assertEquals(CompositionSuggestion.Direction.ON_THIRDS, result.direction)
        assertEquals(0f, result.distanceFromThirds, 0.01f)
    }

    @Test
    fun `analyzeSubjectPosition returns ON_THIRDS when face on thirds intersection`() {
        // Face centered at (1/3, 1/3) — exactly on a thirds point
        val face = RectF(0.28f, 0.28f, 0.39f, 0.39f) // center ≈ (0.335, 0.335)
        val result = analyzer.analyzeSubjectPosition(listOf(face))
        assertEquals(CompositionSuggestion.Direction.ON_THIRDS, result.direction)
        assertTrue(result.distanceFromThirds < 0.08f)
    }

    @Test
    fun `analyzeSubjectPosition suggests LEFT when face is right of nearest thirds`() {
        // Face centered at (0.8, 0.33) — to the right of (2/3, 1/3)
        val face = RectF(0.75f, 0.28f, 0.85f, 0.38f) // center = (0.8, 0.33)
        val result = analyzer.analyzeSubjectPosition(listOf(face))
        assertEquals(CompositionSuggestion.Direction.LEFT, result.direction)
        assertTrue(result.distanceFromThirds > 0.08f)
    }

    @Test
    fun `analyzeSubjectPosition suggests RIGHT when face is left of nearest thirds`() {
        // Face centered at (0.1, 0.33) — to the left of (1/3, 1/3)
        val face = RectF(0.05f, 0.28f, 0.15f, 0.38f) // center = (0.1, 0.33)
        val result = analyzer.analyzeSubjectPosition(listOf(face))
        assertEquals(CompositionSuggestion.Direction.RIGHT, result.direction)
        assertTrue(result.distanceFromThirds > 0.08f)
    }

    @Test
    fun `analyzeSubjectPosition suggests UP when face is below nearest thirds`() {
        // Face centered at (0.33, 0.9) — below (1/3, 2/3)
        val face = RectF(0.28f, 0.85f, 0.38f, 0.95f) // center = (0.33, 0.9)
        val result = analyzer.analyzeSubjectPosition(listOf(face))
        assertEquals(CompositionSuggestion.Direction.UP, result.direction)
        assertTrue(result.distanceFromThirds > 0.08f)
    }

    @Test
    fun `analyzeSubjectPosition suggests DOWN when face is above nearest thirds`() {
        // Face centered at (0.33, 0.1) — above (1/3, 1/3)
        val face = RectF(0.28f, 0.05f, 0.38f, 0.15f) // center = (0.33, 0.1)
        val result = analyzer.analyzeSubjectPosition(listOf(face))
        assertEquals(CompositionSuggestion.Direction.DOWN, result.direction)
        assertTrue(result.distanceFromThirds > 0.08f)
    }

    @Test
    fun `analyzeSubjectPosition uses largest face as primary`() {
        // Small face near thirds, large face far from thirds
        val smallFace = RectF(0.30f, 0.30f, 0.36f, 0.36f) // center = (0.33, 0.33)
        val largeFace = RectF(0.70f, 0.10f, 0.95f, 0.25f) // center = (0.825, 0.175)
        val result = analyzer.analyzeSubjectPosition(listOf(smallFace, largeFace))
        // Should analyze based on largeFace, which is right of (2/3, 1/3) → LEFT
        assertEquals(CompositionSuggestion.Direction.LEFT, result.direction)
    }
}
