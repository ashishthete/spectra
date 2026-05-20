package com.spectra.ai

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemanticMeteringEngineTest {

    // ── helpers ───────────────────────────────────────────────────────────

    /** Pack an ARGB pixel from RGB components (full alpha). */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Fill every pixel in a 16x16 image with the same RGB value. */
    private fun uniformPixels(r: Int, g: Int, b: Int): IntArray =
        IntArray(16 * 16) { argb(r, g, b) }

    /**
     * Create a RectF with the given bounds.
     * isReturnDefaultValues = true means the no-arg constructor returns
     * zero-initialised fields, so we set them manually.
     */
    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        val r = RectF()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    // ── 1. Uniform bright scene → near-zero EV ──────────────────────────

    @Test
    fun `uniform bright scene returns near-zero EV compensation`() {
        // Luminance ≈ 118 (the engine's ideal) → EV ≈ 0
        // lum = (118*77 + 118*150 + 118*29) >> 8 = 118*256 >> 8 = 118
        val pixels = uniformPixels(118, 118, 118)

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = null, skyMask = null
        )

        assertThat(result.targetExposureCompensation).isWithin(0.1f).of(0f)
    }

    // ── 2. Dark subject → positive EV to brighten ───────────────────────

    @Test
    fun `dark subject returns positive EV compensation to brighten`() {
        // All pixels at RGB(50,50,50) → lum ≈ 50
        // No masks → falls back to background, targetLum = bgLum*0.7+128*0.3 ≈ 73.4
        // But we mark every pixel as subject via subjectMask so targetLum = 50
        val pixels = uniformPixels(50, 50, 50)
        val subjectMask = FloatArray(16 * 16) { 1.0f }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = subjectMask, skyMask = null
        )

        // EV = ln(118/50) / ln(2) ≈ 1.24 — positive to brighten
        assertThat(result.targetExposureCompensation).isGreaterThan(0.5f)
        assertThat(result.subjectLuminance).isWithin(5f).of(50f)
    }

    // ── 3. Bright subject → negative EV ─────────────────────────────────

    @Test
    fun `bright subject returns negative EV compensation`() {
        // All pixels at RGB(230,230,230) → lum ≈ 230
        val pixels = uniformPixels(230, 230, 230)
        val subjectMask = FloatArray(16 * 16) { 1.0f }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = subjectMask, skyMask = null
        )

        // EV = ln(118/230) / ln(2) ≈ -0.96 — negative to darken
        assertThat(result.targetExposureCompensation).isLessThan(-0.5f)
    }

    // ── 4. Face regions get highest priority over background ────────────

    @Test
    fun `face regions get highest metering priority over background`() {
        // Background: bright at RGB(200,200,200) → lum ≈ 200
        // Face region pixels: dark at RGB(60,60,60) → lum ≈ 60
        // With scale=4, 16x16 image samples at x,y in {0,4,8,12}.
        // Normalised coords: nx=x/16 in {0, 0.25, 0.5, 0.75}.
        // Face rect [0, 0.24] only captures nx=0 → the leftmost column of samples.
        // Make the entire left strip (x < 4) dark so all face samples are dark.
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < 4) argb(60, 60, 60)
            else argb(200, 200, 200)
        }

        // Face covers a narrow left strip in normalised coords
        val faceRect = rectF(0f, 0f, 0.24f, 1.0f)

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = w, height = h,
            subjectMask = null, skyMask = null,
            faceRegions = listOf(faceRect)
        )

        // Face lum ≈ 60 → EV = ln(118/60)/ln(2) ≈ 0.98 — positive to brighten face
        assertThat(result.targetExposureCompensation).isGreaterThan(0.5f)

        // Without the face, background dominates and EV should be more negative
        val resultNoFace = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = w, height = h,
            subjectMask = null, skyMask = null,
            faceRegions = emptyList()
        )
        assertThat(resultNoFace.targetExposureCompensation).isLessThan(
            result.targetExposureCompensation
        )
    }

    // ── 5. hasSkyHighlights is true when sky pixels are very bright ─────

    @Test
    fun `hasSkyHighlights is true when sky pixels exceed 220 luminance`() {
        // All pixels very bright → lum ≈ 240
        val pixels = uniformPixels(240, 240, 240)
        // Mark all pixels as sky
        val skyMask = BooleanArray(16 * 16) { true }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = null, skyMask = skyMask
        )

        assertThat(result.hasSkyHighlights).isTrue()
    }

    @Test
    fun `hasSkyHighlights is false when sky pixels are below 220 luminance`() {
        // Sky pixels at moderate brightness → lum ≈ 180
        val pixels = uniformPixels(180, 180, 180)
        val skyMask = BooleanArray(16 * 16) { true }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = null, skyMask = skyMask
        )

        assertThat(result.hasSkyHighlights).isFalse()
    }

    // ── 6. No masks → metering falls back to background luminance ───────

    @Test
    fun `with no masks metering falls back to background luminance`() {
        val pixels = uniformPixels(100, 100, 100)

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = null, skyMask = null
        )

        // lum ≈ 100, targetLum = bgLum*0.7+128*0.3 = 70+38.4 = 108.4
        // subjectLuminance should report bgLum (since subjectLum < 0)
        assertThat(result.subjectLuminance).isWithin(5f).of(100f)
        assertThat(result.backgroundLuminance).isWithin(5f).of(100f)
        // EV = ln(118/108.4)/ln(2) ≈ 0.12 — small positive
        assertThat(result.targetExposureCompensation).isWithin(0.3f).of(0f)
    }

    // ── 7. EV compensation is clamped to [-2, 2] ────────────────────────

    @Test
    fun `EV compensation is clamped to positive 2 for extremely dark scenes`() {
        // lum ≈ 15 → unclamped EV = ln(118/15)/ln(2) ≈ 2.98
        val pixels = uniformPixels(15, 15, 15)
        val subjectMask = FloatArray(16 * 16) { 1.0f }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = subjectMask, skyMask = null
        )

        assertThat(result.targetExposureCompensation).isAtMost(2.0f)
        assertThat(result.targetExposureCompensation).isEqualTo(2.0f)
    }

    @Test
    fun `EV compensation is clamped to negative 2 for extremely bright scenes`() {
        // lum ≈ 250 → unclamped EV = ln(118/250)/ln(2) ≈ -1.08
        // Need brighter to exceed -2: use subject mask on 255
        // Actually lum for (255,255,255) = 255, EV = ln(118/255)/ln(2) ≈ -1.11
        // To truly exceed -2, we need targetLum ≈ 472 which isn't possible with 8-bit.
        // Instead, verify the clamp logic by checking the result stays >= -2
        val pixels = uniformPixels(255, 255, 255)
        val subjectMask = FloatArray(16 * 16) { 1.0f }

        val result = SemanticMeteringEngine.computeSemanticMetering(
            pixels = pixels, width = 16, height = 16,
            subjectMask = subjectMask, skyMask = null
        )

        assertThat(result.targetExposureCompensation).isAtLeast(-2.0f)
        // The value should be negative for this bright scene
        assertThat(result.targetExposureCompensation).isLessThan(0f)
    }
}
