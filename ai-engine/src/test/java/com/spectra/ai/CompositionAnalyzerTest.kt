package com.spectra.ai

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CompositionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionAnalyzerTest {

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
}
