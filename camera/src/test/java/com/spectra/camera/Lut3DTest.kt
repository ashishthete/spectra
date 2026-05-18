package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Lut3DTest {
    @Test
    fun `identity 3D LUT passes values unchanged`() {
        val size = 33
        val lut = Lut3D.generateIdentity(size)
        for (r in 0..255 step 17) {
            for (g in 0..255 step 51) {
                for (b in 0..255 step 51) {
                    val (outR, outG, outB) = Lut3D.apply(r, g, b, lut, size)
                    assertThat(outR).isWithin(1).of(r)
                    assertThat(outG).isWithin(1).of(g)
                    assertThat(outB).isWithin(1).of(b)
                }
            }
        }
    }

    @Test
    fun `generateIdentity has correct size`() {
        val size = 33
        val lut = Lut3D.generateIdentity(size)
        assertThat(lut.size).isEqualTo(size * size * size * 3)
    }

    @Test
    fun `parse cube format produces correct size`() {
        val cube = buildString {
            appendLine("LUT_3D_SIZE 4")
            for (r in 0..3) for (g in 0..3) for (b in 0..3) {
                appendLine("${r/3f} ${g/3f} ${b/3f}")
            }
        }
        val (size, data) = Lut3D.parse(cube)
        assertThat(size).isEqualTo(4)
        assertThat(data.size).isEqualTo(4 * 4 * 4 * 3)
    }

    @Test
    fun `apply with small LUT interpolates correctly`() {
        val size = 2
        val lut = Lut3D.generateIdentity(size)
        val (outR, outG, outB) = Lut3D.apply(128, 128, 128, lut, size)
        assertThat(outR).isWithin(2).of(128)
        assertThat(outG).isWithin(2).of(128)
        assertThat(outB).isWithin(2).of(128)
    }

    @Test
    fun `applyToPixels modifies all pixels`() {
        val size = 33
        // Create a LUT that inverts colors
        val lut = FloatArray(size * size * size * 3)
        for (ri in 0 until size) for (gi in 0 until size) for (bi in 0 until size) {
            val idx = (ri * size * size + gi * size + bi) * 3
            lut[idx] = 1f - ri.toFloat() / (size - 1)
            lut[idx + 1] = 1f - gi.toFloat() / (size - 1)
            lut[idx + 2] = 1f - bi.toFloat() / (size - 1)
        }
        val pixels = intArrayOf(
            (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100,
            (0xFF shl 24) or (200 shl 16) or (50 shl 8) or 50
        )
        Lut3D.applyToPixels(pixels, lut, size)
        val r0 = (pixels[0] shr 16) and 0xFF
        assertThat(r0).isWithin(5).of(155) // ~255 - 100
    }
}
