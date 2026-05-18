package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemanticSegmenterTest {

    // All pure logic lives in the companion object — no ML Kit init needed in unit tests.

    // ---- boxBlur tests ----

    @Test
    fun `boxBlur preserves uniform color`() {
        val w = 8; val h = 8
        val color = (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200
        val pixels = IntArray(w * h) { color }
        val result = SemanticSegmenter.boxBlur(pixels, w, h, radius = 3)
        for (i in result.indices) {
            assertThat((result[i] shr 16) and 0xFF).isWithin(1).of(100)
            assertThat((result[i] shr 8) and 0xFF).isWithin(1).of(150)
            assertThat(result[i] and 0xFF).isWithin(1).of(200)
        }
    }

    @Test
    fun `boxBlur output has same length as input`() {
        val w = 16; val h = 12
        val pixels = IntArray(w * h) { (0xFF shl 24) or (it and 0xFF) }
        val result = SemanticSegmenter.boxBlur(pixels, w, h, radius = 2)
        assertThat(result).hasLength(w * h)
    }

    @Test
    fun `boxBlur smooths high-frequency pattern toward mid-grey`() {
        val w = 16; val h = 16
        // Alternating black and white pixels — blur should produce mid-grey in the interior
        val pixels = IntArray(w * h) { i ->
            if (i % 2 == 0) (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
            else (0xFF shl 24)
        }
        val result = SemanticSegmenter.boxBlur(pixels, w, h, radius = 4)
        val centerR = (result[h / 2 * w + w / 2] shr 16) and 0xFF
        // Interior pixels should be averaged toward mid-grey (not pure black or white)
        assertThat(centerR).isGreaterThan(50)
        assertThat(centerR).isLessThan(210)
    }

    @Test
    fun `boxBlur does not modify input array`() {
        val w = 8; val h = 8
        val pixels = IntArray(w * h) { (0xFF shl 24) or (it and 0xFF) }
        val original = pixels.copyOf()
        SemanticSegmenter.boxBlur(pixels, w, h, radius = 2)
        assertThat(pixels).isEqualTo(original)
    }

    // ---- applySemanticBokeh mask scaling tests ----

    @Test
    fun `applySemanticBokeh with full-person mask preserves all pixels`() {
        val w = 4; val h = 4; val n = w * h
        val color = (0xFF shl 24) or (80 shl 16) or (160 shl 8) or 240
        val pixels = IntArray(n) { color }
        // mask = all 1.0 (all person) — nothing should be blurred
        val mask = FloatArray(n) { 1.0f }

        SemanticSegmenter.applySemanticBokeh(pixels, w, h, mask, w, h, blurRadius = 2)

        for (i in pixels.indices) {
            assertThat((pixels[i] shr 16) and 0xFF).isEqualTo(80)
            assertThat((pixels[i] shr 8) and 0xFF).isEqualTo(160)
            assertThat(pixels[i] and 0xFF).isEqualTo(240)
        }
    }

    @Test
    fun `applySemanticBokeh with smaller mask scales and preserves person pixels`() {
        val w = 8; val h = 8; val n = w * h
        // Left half bright, right half dark
        val pixels = IntArray(n) { i ->
            val x = i % w
            if (x < w / 2) (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            else (0xFF shl 24) or (50 shl 16) or (50 shl 8) or 50
        }

        // Half-resolution mask: left half = person (1.0), right half = background (0.0)
        val maskW = 4; val maskH = 4
        val mask = FloatArray(maskW * maskH) { i ->
            if (i % maskW < maskW / 2) 1.0f else 0.0f
        }

        val pixelsCopy = pixels.copyOf()
        SemanticSegmenter.applySemanticBokeh(pixelsCopy, w, h, mask, maskW, maskH, blurRadius = 1)

        // Left half (person, conf=1.0) should be unchanged
        for (y in 0 until h) {
            for (x in 0 until w / 2) {
                assertThat((pixelsCopy[y * w + x] shr 16) and 0xFF).isEqualTo(200)
            }
        }
        // Result array must still have the original length
        assertThat(pixelsCopy).hasLength(n)
    }

    @Test
    fun `applySemanticBokeh with same-size mask does not alter person pixels`() {
        val w = 6; val h = 6; val n = w * h
        val pixels = IntArray(n) { (0xFF shl 24) or (120 shl 16) or (120 shl 8) or 120 }
        val mask = FloatArray(n) { 1.0f }

        SemanticSegmenter.applySemanticBokeh(pixels, w, h, mask, w, h, blurRadius = 1)

        for (i in pixels.indices) {
            assertThat((pixels[i] shr 16) and 0xFF).isEqualTo(120)
        }
    }

    @Test
    fun `applySemanticBokeh background pixels are blended toward blur`() {
        val w = 8; val h = 8; val n = w * h
        // All pixels are bright red; mask is all-background (0.0)
        val pixels = IntArray(n) { (0xFF shl 24) or (200 shl 16) }
        val mask = FloatArray(n) { 0.0f }

        // With a uniform image the blur equals the original, so blending won't change pixels.
        // Instead use a non-uniform image and verify the result is not identical to input.
        val varyingPixels = IntArray(n) { i ->
            val v = if (i % 2 == 0) 200 else 50
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val original = varyingPixels.copyOf()
        SemanticSegmenter.applySemanticBokeh(varyingPixels, w, h, mask, w, h, blurRadius = 3)

        // At least some pixels should have changed due to blending
        var changed = 0
        for (i in varyingPixels.indices) {
            if (varyingPixels[i] != original[i]) changed++
        }
        assertThat(changed).isGreaterThan(0)
    }

    // ---- SegmentationResult equals/hashCode ----

    @Test
    fun `SegmentationResult equals uses array content`() {
        val a = SemanticSegmenter.SegmentationResult(floatArrayOf(0f, 1f, 0.5f), 3, 1)
        val b = SemanticSegmenter.SegmentationResult(floatArrayOf(0f, 1f, 0.5f), 3, 1)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `SegmentationResult not equal when mask differs`() {
        val a = SemanticSegmenter.SegmentationResult(floatArrayOf(0f, 1f), 2, 1)
        val b = SemanticSegmenter.SegmentationResult(floatArrayOf(1f, 0f), 2, 1)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `SegmentationResult not equal when dimensions differ`() {
        val a = SemanticSegmenter.SegmentationResult(floatArrayOf(0.5f, 0.5f), 2, 1)
        val b = SemanticSegmenter.SegmentationResult(floatArrayOf(0.5f, 0.5f), 1, 2)
        assertThat(a).isNotEqualTo(b)
    }
}
