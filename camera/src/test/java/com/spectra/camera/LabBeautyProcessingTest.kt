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
    fun `bilateralRadius returns zero for level zero`() {
        assertThat(CaptureManager.beautyBilateralRadius(0)).isEqualTo(0)
    }

    @Test
    fun `bilateralRadius returns zero for negative level`() {
        assertThat(CaptureManager.beautyBilateralRadius(-1)).isEqualTo(0)
    }

    @Test
    fun `bilateralRadius increases with level`() {
        val r1 = CaptureManager.beautyBilateralRadius(1)
        val r2 = CaptureManager.beautyBilateralRadius(2)
        val r3 = CaptureManager.beautyBilateralRadius(3)
        assertThat(r2).isGreaterThan(r1)
        assertThat(r3).isGreaterThan(r2)
    }
}
