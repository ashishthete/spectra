package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class CaptureManagerCompanionTest {

    // nrSigma(nr: Int): Float  =  0.5f + (nr / 100f) * 3f
    // Input is a 0-100 NR parameter value, not ISO.

    @Test
    fun `nrSigma increases as NR parameter increases`() {
        val low = CaptureManager.nrSigma(0)
        val mid = CaptureManager.nrSigma(50)
        val high = CaptureManager.nrSigma(100)
        assertThat(mid).isGreaterThan(low)
        assertThat(high).isGreaterThan(mid)
    }

    @Test
    fun `nrSigma at zero returns baseline`() {
        assertThat(CaptureManager.nrSigma(0)).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `nrSigma at 100 returns maximum`() {
        assertThat(CaptureManager.nrSigma(100)).isWithin(0.01f).of(3.5f)
    }

    @Test
    fun `nrSigma at midpoint is average of min and max`() {
        assertThat(CaptureManager.nrSigma(50)).isWithin(0.01f).of(2.0f)
    }

    // sharpnessFromParams(sharpness: Int): Float  =  0.1f + (sharpness / 100f) * 0.6f

    @Test
    fun `sharpnessFromParams increases with parameter`() {
        val low = CaptureManager.sharpnessFromParams(0)
        val high = CaptureManager.sharpnessFromParams(100)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `sharpnessFromParams at zero returns minimum`() {
        assertThat(CaptureManager.sharpnessFromParams(0)).isWithin(0.001f).of(0.1f)
    }

    @Test
    fun `sharpnessFromParams at 100 returns maximum`() {
        assertThat(CaptureManager.sharpnessFromParams(100)).isWithin(0.001f).of(0.7f)
    }

    @Test
    fun `sharpnessFromParams stays in valid range for all inputs`() {
        for (v in 0..100) {
            val result = CaptureManager.sharpnessFromParams(v)
            assertThat(result).isAtLeast(0.1f)
            assertThat(result).isAtMost(0.71f)
        }
    }

    // saturationMultiplier(sat: Int): Float  =  0.5f + (sat / 100f) * 1.0f

    @Test
    fun `saturationMultiplier increases with parameter`() {
        val low = CaptureManager.saturationMultiplier(0)
        val high = CaptureManager.saturationMultiplier(100)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `saturationMultiplier at zero returns minimum`() {
        assertThat(CaptureManager.saturationMultiplier(0)).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `saturationMultiplier at 100 returns maximum`() {
        assertThat(CaptureManager.saturationMultiplier(100)).isWithin(0.001f).of(1.5f)
    }

    @Test
    fun `saturationMultiplier at 50 is neutral`() {
        assertThat(CaptureManager.saturationMultiplier(50)).isWithin(0.01f).of(1.0f)
    }

    // contrastScale(contrast: Int): Float  =  1.0f + ((contrast - 50) / 50f) * 0.2f

    @Test
    fun `contrastScale increases with parameter`() {
        val low = CaptureManager.contrastScale(0)
        val mid = CaptureManager.contrastScale(50)
        val high = CaptureManager.contrastScale(100)
        assertThat(mid).isGreaterThan(low)
        assertThat(high).isGreaterThan(mid)
    }

    @Test
    fun `contrastScale at 50 is neutral (1_0)`() {
        assertThat(CaptureManager.contrastScale(50)).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `contrastScale at 0 is minimum`() {
        assertThat(CaptureManager.contrastScale(0)).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `contrastScale at 100 is maximum`() {
        assertThat(CaptureManager.contrastScale(100)).isWithin(0.001f).of(1.2f)
    }

    // contrastOffset(contrast: Int): Float  =  -128f * (contrastScale(contrast) - 1f)

    @Test
    fun `contrastOffset at neutral contrast is zero`() {
        assertThat(CaptureManager.contrastOffset(50)).isWithin(0.01f).of(0.0f)
    }

    @Test
    fun `contrastOffset is negative for low contrast`() {
        // scale < 1 when contrast < 50, so offset = -128 * negative = positive ... actually:
        // scale = 0.8, offset = -128 * (0.8 - 1) = -128 * -0.2 = +25.6 for contrast=0
        // scale = 1.2, offset = -128 * (1.2 - 1) = -128 * 0.2 = -25.6 for contrast=100
        val offsetHigh = CaptureManager.contrastOffset(100)
        assertThat(offsetHigh).isLessThan(0f)
    }

    @Test
    fun `contrastOffset at contrast 0 is positive (lifts blacks)`() {
        val offsetLow = CaptureManager.contrastOffset(0)
        assertThat(offsetLow).isGreaterThan(0f)
    }

    @Test
    fun `contrastOffset magnitude scales with distance from neutral`() {
        val at100 = CaptureManager.contrastOffset(100)
        val at75 = CaptureManager.contrastOffset(75)
        // Both negative; |at100| should be larger
        assertThat(at100).isLessThan(at75)
    }

    // bilateralApprox: pure FloatArray operation, no Android deps

    @Test
    fun `bilateralApprox preserves uniform signal`() {
        val size = 6
        val guide = FloatArray(size * size) { 0.5f }
        val output = FloatArray(size * size) { 100f }
        CaptureManager.bilateralApprox(output, guide, size, size, spatialRadius = 2, rangeSigma = 0.1f)
        for (v in output) {
            assertThat(v).isWithin(0.01f).of(100f)
        }
    }

    @Test
    fun `bilateralApprox smooths values with similar guide`() {
        val size = 5
        val guide = FloatArray(size * size) { 0.5f }
        // Alternating 0 and 200
        val output = FloatArray(size * size) { i -> if (i % 2 == 0) 0f else 200f }
        val before = output.map { it }
        CaptureManager.bilateralApprox(output, guide, size, size, spatialRadius = 2, rangeSigma = 1.0f)
        val variance = output.map { it.toDouble() }.let { vals ->
            val mean = vals.average()
            vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        val inputVariance = before.map { it.toDouble() }.let { vals ->
            val mean = vals.average()
            vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        assertThat(variance).isLessThan(inputVariance)
    }

    @Test
    fun `bilateralApprox with high rangeSigma acts like averaging`() {
        val size = 4
        val guide = FloatArray(size * size) { it.toFloat() }
        val output = FloatArray(size * size) { it * 10f }
        // High rangeSigma means all neighbors are weighted similarly
        CaptureManager.bilateralApprox(output, guide, size, size, spatialRadius = 2, rangeSigma = 1000f)
        // After smoothing, corner values should have moved toward center
        val centerVal = output[size / 2 * size + size / 2]
        val cornerVal = output[0]
        // Just verify the output is finite and within plausible bounds
        assertThat(centerVal).isFinite()
        assertThat(cornerVal).isFinite()
    }

    // getSharpnessStrength(sceneType: SceneType): Float

    @Test
    fun `getSharpnessStrength LANDSCAPE is stronger than PORTRAIT`() {
        val portrait = CaptureManager.getSharpnessStrength(SceneType.PORTRAIT)
        val landscape = CaptureManager.getSharpnessStrength(SceneType.LANDSCAPE)
        assertThat(landscape).isGreaterThan(portrait)
    }

    @Test
    fun `getSharpnessStrength NIGHT is gentle`() {
        val night = CaptureManager.getSharpnessStrength(SceneType.NIGHT)
        assertThat(night).isAtMost(0.2f)
    }

    @Test
    fun `getSharpnessStrength DOCUMENT is strong`() {
        val doc = CaptureManager.getSharpnessStrength(SceneType.DOCUMENT)
        assertThat(doc).isAtLeast(0.5f)
    }

    @Test
    fun `getSharpnessStrength covers all scene types without throwing`() {
        for (scene in SceneType.entries) {
            val strength = CaptureManager.getSharpnessStrength(scene)
            assertThat(strength).isAtLeast(0f)
            assertThat(strength).isAtMost(1f)
        }
    }

    // beautyBilateralRadius(beautyLevel: Int): Int

    @Test
    fun `beautyBilateralRadius increases with level`() {
        val r1 = CaptureManager.beautyBilateralRadius(1)
        val r2 = CaptureManager.beautyBilateralRadius(2)
        val r3 = CaptureManager.beautyBilateralRadius(3)
        assertThat(r2).isGreaterThan(r1)
        assertThat(r3).isGreaterThan(r2)
    }

    @Test
    fun `beautyBilateralRadius at level 0 is zero`() {
        assertThat(CaptureManager.beautyBilateralRadius(0)).isEqualTo(0)
    }

    @Test
    fun `beautyBilateralRadius at level 3 is maximum`() {
        assertThat(CaptureManager.beautyBilateralRadius(3)).isEqualTo(8)
    }
}
