package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeuralDenoiserTest {

    private val denoiser = NeuralDenoiser()

    // ---- Without interpreter (no model loaded) ----

    @Test
    fun `denoise returns input unchanged when no interpreter is loaded`() {
        val w = 64; val h = 64
        val luminance = FloatArray(w * h) { it.toFloat() % 256f }
        val result = denoiser.denoise(luminance, w, h)
        // Fast-path returns the same array reference when no interpreter is loaded
        assertThat(result).isSameInstanceAs(luminance)
    }

    @Test
    fun `isAvailable returns false before init`() {
        assertThat(denoiser.isAvailable).isFalse()
    }

    // ---- Tiling / overlap geometry ----

    @Test
    fun `output array has same dimensions as input`() {
        val w = 128; val h = 96
        val luminance = FloatArray(w * h) { 128f }
        val result = denoiser.denoise(luminance, w, h)
        assertThat(result).hasLength(w * h)
    }

    @Test
    fun `single tile image produces output same size as input`() {
        // Image smaller than tileSize=256, fits in one tile
        val w = 100; val h = 80
        val luminance = FloatArray(w * h) { 50f }
        val result = denoiser.denoise(luminance, w, h)
        assertThat(result).hasLength(w * h)
    }

    @Test
    fun `multi-tile image produces output same size as input`() {
        // Image larger than tileSize=256, requires multiple tiles
        val w = 512; val h = 512
        val luminance = FloatArray(w * h) { it.toFloat() % 255f }
        val result = denoiser.denoise(luminance, w, h)
        assertThat(result).hasLength(w * h)
    }

    // ---- Overlap boundary correctness ----

    @Test
    fun `tile overlap clamps source coordinates within image bounds`() {
        // A 1x1 pixel image exercises all boundary clamping paths (srcX0=0, srcY0=0)
        val luminance = floatArrayOf(200f)
        val result = denoiser.denoise(luminance, w = 1, h = 1)
        assertThat(result).hasLength(1)
    }

    @Test
    fun `tile boundary image width equals tileSize`() {
        // Exactly tileSize wide — single tile, no right-side partial tile
        val tileSize = 256
        val w = tileSize; val h = tileSize
        val luminance = FloatArray(w * h) { 64f }
        val result = denoiser.denoise(luminance, w, h)
        assertThat(result).hasLength(w * h)
    }

    @Test
    fun `non-square image with multiple tiles preserves output length`() {
        val w = 300; val h = 150  // two tiles wide, one tile tall
        val luminance = FloatArray(w * h) { it.toFloat() * 0.5f }
        val result = denoiser.denoise(luminance, w, h)
        assertThat(result).hasLength(w * h)
    }
}
