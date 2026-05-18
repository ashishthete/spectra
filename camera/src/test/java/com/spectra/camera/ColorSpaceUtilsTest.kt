package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorSpaceUtilsTest {

    @Test
    fun `rgbToYCbCr converts pure white correctly`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(255, 255, 255)
        assertThat(ycbcr[0]).isEqualTo(255)
        assertThat(ycbcr[1]).isEqualTo(128)
        assertThat(ycbcr[2]).isEqualTo(128)
    }

    @Test
    fun `rgbToYCbCr converts pure black correctly`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(0, 0, 0)
        assertThat(ycbcr[0]).isEqualTo(0)
        assertThat(ycbcr[1]).isEqualTo(128)
        assertThat(ycbcr[2]).isEqualTo(128)
    }

    @Test
    fun `rgbToYCbCr converts pure red correctly`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(255, 0, 0)
        assertThat(ycbcr[0]).isEqualTo(76)
        assertThat(ycbcr[1]).isEqualTo(85)
        assertThat(ycbcr[2]).isEqualTo(255)
    }

    @Test
    fun `ycbcrToRgb roundtrips with rgbToYCbCr`() {
        val r = 120; val g = 80; val b = 200
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
        val rgb = ColorSpaceUtils.ycbcrToRgb(ycbcr[0], ycbcr[1], ycbcr[2])
        assertThat(rgb[0]).isWithin(1).of(r)
        assertThat(rgb[1]).isWithin(1).of(g)
        assertThat(rgb[2]).isWithin(1).of(b)
    }

    @Test
    fun `rgbToLab converts pure white correctly`() {
        val lab = ColorSpaceUtils.rgbToLab(255, 255, 255)
        assertThat(lab[0]).isWithin(1.0f).of(100f)
        assertThat(lab[1]).isWithin(1.0f).of(0f)
        assertThat(lab[2]).isWithin(1.0f).of(0f)
    }

    @Test
    fun `rgbToLab converts pure black correctly`() {
        val lab = ColorSpaceUtils.rgbToLab(0, 0, 0)
        assertThat(lab[0]).isWithin(0.1f).of(0f)
    }

    @Test
    fun `labToRgb roundtrips with rgbToLab`() {
        val r = 150; val g = 100; val b = 50
        val lab = ColorSpaceUtils.rgbToLab(r, g, b)
        val rgb = ColorSpaceUtils.labToRgb(lab[0], lab[1], lab[2])
        assertThat(rgb[0]).isWithin(2).of(r)
        assertThat(rgb[1]).isWithin(2).of(g)
        assertThat(rgb[2]).isWithin(2).of(b)
    }

    @Test
    fun `isSkinPixelYCbCr detects typical skin tone`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(200, 150, 130)
        val isSkin = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])
        assertThat(isSkin).isTrue()
    }

    @Test
    fun `isSkinPixelYCbCr rejects blue sky`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(100, 150, 255)
        val isSkin = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])
        assertThat(isSkin).isFalse()
    }

    @Test
    fun `isSkinPixelYCbCr detects darker skin tone`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(140, 90, 70)
        val isSkin = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])
        assertThat(isSkin).isTrue()
    }
}
