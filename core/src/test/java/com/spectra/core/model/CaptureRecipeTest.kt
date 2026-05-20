package com.spectra.core.model

import org.junit.Assert.*
import org.junit.Test

class CaptureRecipeTest {

    @Test
    fun `auto recipe in normal light captures single frame`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.AUTO, isLowLight = false, isStable = true, hasFaces = false, highContrast = false
        )
        assertEquals(1, recipe.baseFrameCount)
        assertFalse(recipe.useHdrBracket)
        assertFalse(recipe.useBurstMerge)
    }

    @Test
    fun `auto recipe in low light enables burst merge`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.AUTO, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        assertEquals(3, recipe.baseFrameCount)
        assertTrue(recipe.useBurstMerge)
    }

    @Test
    fun `auto recipe with high contrast enables HDR bracket`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.AUTO, isLowLight = false, isStable = true, hasFaces = false, highContrast = true
        )
        assertTrue(recipe.useHdrBracket)
    }

    @Test
    fun `portrait recipe always uses telephoto 3x`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.PORTRAIT, isLowLight = false, isStable = true, hasFaces = true, highContrast = false
        )
        assertEquals(LensId.TELEPHOTO_3X, recipe.preferredLens)
        assertTrue(recipe.useDepthBokeh)
        assertTrue(recipe.faceExposurePriority)
        assertTrue(recipe.skinHueProtection)
    }

    @Test
    fun `night recipe captures many frames`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.NIGHT, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        assertEquals(5, recipe.baseFrameCount)
        assertEquals(9, recipe.maxFrameCount)
        assertTrue(recipe.useBurstMerge)
        assertTrue(recipe.preserveAtmosphere)
    }

    @Test
    fun `action recipe favors fast shutter`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.ACTION, isLowLight = false, isStable = false, hasFaces = false, highContrast = false
        )
        assertEquals(1_000_000L, recipe.minShutterSpeedNs)
        assertFalse(recipe.useHdrBracket)
        assertFalse(recipe.useBurstMerge)
    }

    @Test
    fun `landscape recipe enables sky highlight protection`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.LANDSCAPE, isLowLight = false, isStable = true, hasFaces = false, highContrast = true
        )
        assertTrue(recipe.skyHighlightProtection)
        assertEquals(200, recipe.maxIso)
    }

    @Test
    fun `food recipe limits ISO`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.FOOD, isLowLight = false, isStable = true, hasFaces = false, highContrast = false
        )
        assertEquals(800, recipe.maxIso)
        assertFalse(recipe.useDepthBokeh)
    }

    @Test
    fun `macro recipe enables burst in stable conditions`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.MACRO, isLowLight = false, isStable = true, hasFaces = false, highContrast = false
        )
        assertTrue(recipe.useBurstMerge)
        assertEquals(3, recipe.baseFrameCount)
    }

    @Test
    fun `pro recipe is minimal processing`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.PRO, isLowLight = false, isStable = true, hasFaces = false, highContrast = false
        )
        assertEquals(1, recipe.baseFrameCount)
        assertEquals(1, recipe.maxFrameCount)
        assertFalse(recipe.useHdrBracket)
        assertFalse(recipe.useBurstMerge)
        assertEquals(0.1f, recipe.processingIntensity, 0.01f)
    }

    @Test
    fun `portrait recipe with faces enables skin hue protection`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.PORTRAIT, isLowLight = false, isStable = true, hasFaces = true, highContrast = false
        )
        assertTrue(recipe.skinHueProtection)
        assertTrue(recipe.faceExposurePriority)
    }

    @Test
    fun `auto recipe with faces enables face exposure priority`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.AUTO, isLowLight = false, isStable = true, hasFaces = true, highContrast = false
        )
        assertTrue(recipe.faceExposurePriority)
        assertTrue(recipe.skinHueProtection)
    }

    @Test
    fun `resolveFrameCount returns base when not stable`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.NIGHT, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        val count = recipe.resolveFrameCount(isStable = false, gyroMotion = 0.5f, iso = 3200)
        assertEquals(recipe.baseFrameCount, count)
    }

    @Test
    fun `resolveFrameCount returns max for stable high ISO night`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.NIGHT, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        val count = recipe.resolveFrameCount(isStable = true, gyroMotion = 0.01f, iso = 3200, lux = 5f)
        assertEquals(recipe.maxFrameCount, count)
    }

    @Test
    fun `resolveFrameCount returns mid for moderate ISO stable`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.AUTO, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        val count = recipe.resolveFrameCount(isStable = true, gyroMotion = 0.02f, iso = 1000, lux = 30f)
        assertTrue(count > recipe.baseFrameCount)
        assertTrue(count <= recipe.maxFrameCount)
    }

    @Test
    fun `resolveFrameCount respects thermal max`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.NIGHT, isLowLight = true, isStable = true, hasFaces = false, highContrast = false
        )
        val count = recipe.resolveFrameCount(isStable = true, gyroMotion = 0.01f, iso = 3200, lux = 5f, thermalMaxFrames = 3)
        assertTrue(count <= 3)
    }

    @Test
    fun `resolveFrameCount returns base when base equals max`() {
        val recipe = CaptureRecipe.forPreset(
            CameraPreset.PRO, isLowLight = false, isStable = true, hasFaces = false, highContrast = false
        )
        val count = recipe.resolveFrameCount(isStable = true, gyroMotion = 0.01f, iso = 3200)
        assertEquals(recipe.baseFrameCount, count)
    }
}
