package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabBeautyProcessingTest {

    @Test
    fun `bilateralRadius scales with beauty level`() {
        assertThat(CaptureManager.beautyBilateralRadius(1)).isEqualTo(3)
        assertThat(CaptureManager.beautyBilateralRadius(2)).isEqualTo(5)
        assertThat(CaptureManager.beautyBilateralRadius(3)).isEqualTo(8)
    }

    @Test
    fun `bilateralRadius returns 0 for level 0`() {
        assertThat(CaptureManager.beautyBilateralRadius(0)).isEqualTo(0)
    }

    @Test
    fun `bilateralRadius returns 0 for negative level`() {
        assertThat(CaptureManager.beautyBilateralRadius(-1)).isEqualTo(0)
    }

    @Test
    fun `skin detection rejects blue sky in YCbCr`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(100, 150, 255)
        val isSkin = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])
        assertThat(isSkin).isFalse()
    }

    @Test
    fun `skin detection accepts typical skin tone`() {
        val ycbcr = ColorSpaceUtils.rgbToYCbCr(200, 150, 130)
        val isSkin = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])
        assertThat(isSkin).isTrue()
    }
}
