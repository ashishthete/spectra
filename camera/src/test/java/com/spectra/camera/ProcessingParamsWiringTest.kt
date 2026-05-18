package com.spectra.camera

import com.spectra.core.model.ProcessingParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingParamsWiringTest {

    @Test
    fun `noiseReduction maps to bilateral sigma`() {
        val params = ProcessingParams(noiseReduction = 50)
        val sigma = CaptureManager.nrSigma(params.noiseReduction)
        assertEquals(2.0f, sigma, 0.1f)
    }

    @Test
    fun `noiseReduction 0 maps to minimum sigma`() {
        val sigma = CaptureManager.nrSigma(0)
        assertEquals(0.5f, sigma, 0.01f)
    }

    @Test
    fun `noiseReduction 100 maps to maximum sigma`() {
        val sigma = CaptureManager.nrSigma(100)
        assertEquals(3.5f, sigma, 0.01f)
    }

    @Test
    fun `sharpness maps to USM strength`() {
        val strength = CaptureManager.sharpnessFromParams(50)
        assertEquals(0.4f, strength, 0.01f)
    }

    @Test
    fun `saturation maps to multiplier`() {
        val multiplier = CaptureManager.saturationMultiplier(50)
        assertEquals(1.0f, multiplier, 0.01f)
    }

    @Test
    fun `contrast maps to scale`() {
        val scale = CaptureManager.contrastScale(50)
        assertEquals(1.0f, scale, 0.05f)
    }

    @Test
    fun `contrast at 100 produces boost`() {
        val scale = CaptureManager.contrastScale(100)
        assert(scale > 1.0f) { "Scale $scale should be > 1.0 at contrast=100" }
    }
}
