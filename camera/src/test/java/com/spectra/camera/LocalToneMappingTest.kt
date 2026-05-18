package com.spectra.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp

class LocalToneMappingTest {

    @Test
    fun `bilateral filter preserves edges`() {
        // 8x8 image: left half dark (lum=0.1), right half bright (lum=0.9)
        val w = 8
        val h = 8
        val guide = FloatArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) ln(0.1f) else ln(0.9f)
        }
        val input = guide.clone()
        val output = FloatArray(w * h)
        System.arraycopy(input, 0, output, 0, input.size)

        CaptureManager.bilateralApprox(output, guide, w, h, spatialRadius = 2, rangeSigma = 0.4f)

        // Edge pixels (x=3 and x=4) should still have a significant difference
        val leftEdge = output[0 * w + 3]
        val rightEdge = output[0 * w + 4]
        assertTrue("Edge should be preserved: left=$leftEdge, right=$rightEdge",
            abs(rightEdge - leftEdge) > 0.5f)
    }

    @Test
    fun `bilateral filter smooths same-luminance region`() {
        // 8x8 uniform region with one noisy pixel
        val w = 8
        val h = 8
        val baseVal = ln(0.5f)
        val guide = FloatArray(w * h) { baseVal }
        guide[4 * w + 4] = ln(0.55f) // slight noise
        val input = guide.clone()
        val output = FloatArray(w * h)
        System.arraycopy(input, 0, output, 0, input.size)

        CaptureManager.bilateralApprox(output, guide, w, h, spatialRadius = 2, rangeSigma = 0.4f)

        // The noisy pixel should be smoothed closer to surrounding values
        val smoothedNoise = output[4 * w + 4]
        assertTrue("Noisy pixel should be smoothed toward neighbors",
            abs(smoothedNoise - baseVal) < abs(ln(0.55f) - baseVal))
    }

    @Test
    fun `base layer compression reduces dynamic range`() {
        // Simulate the tone mapping pipeline on float arrays
        val w = 8
        val h = 8
        val logLum = FloatArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) ln(0.05f) else ln(0.95f) // high contrast
        }

        // Get base via bilateral
        val base = logLum.clone()
        CaptureManager.bilateralApprox(base, logLum, w, h, spatialRadius = 2, rangeSigma = 0.4f)

        // Compress base
        val baseMean = base.average().toFloat()
        val compressionFactor = 0.5f
        val compressedBase = FloatArray(w * h) { i ->
            baseMean + (base[i] - baseMean) * compressionFactor
        }

        // Detail = logLum - base
        val detail = FloatArray(w * h) { i -> logLum[i] - base[i] }

        // Reconstruct
        val darkReconstructed = exp(compressedBase[0] + detail[0])
        val brightReconstructed = exp(compressedBase[w - 1] + detail[w - 1])

        // Original: 0.05 and 0.95 — compressed should be closer together
        assertTrue("Dark side ($darkReconstructed) should be lifted above 0.05",
            darkReconstructed > 0.05f)
        assertTrue("Bright side ($brightReconstructed) should be compressed below 0.95",
            brightReconstructed < 0.95f)
        assertTrue("Should maintain some contrast",
            brightReconstructed > darkReconstructed)
    }
}
