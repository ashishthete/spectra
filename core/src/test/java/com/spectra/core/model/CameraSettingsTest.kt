package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraSettingsTest {

    @Test
    fun `default settings are sensible`() {
        val settings = CameraSettings()
        assertThat(settings.iso).isEqualTo(100)
        assertThat(settings.shutterSpeedDenominator).isEqualTo(125)
        assertThat(settings.whiteBalanceKelvin).isEqualTo(5500)
        assertThat(settings.exposureCompensation).isEqualTo(0f)
    }

    @Test
    fun `iso clamps to valid range`() {
        val settings = CameraSettings(iso = 50000)
        assertThat(settings.iso).isEqualTo(3200)
    }

    @Test
    fun `iso clamps lower bound`() {
        val settings = CameraSettings(iso = 10)
        assertThat(settings.iso).isEqualTo(50)
    }

    @Test
    fun `white balance clamps to valid range`() {
        val settings = CameraSettings(whiteBalanceKelvin = 20000)
        assertThat(settings.whiteBalanceKelvin).isEqualTo(10000)
    }

    @Test
    fun `exposure compensation clamps`() {
        val settings = CameraSettings(exposureCompensation = 5f)
        assertThat(settings.exposureCompensation).isEqualTo(3f)
    }

    @Test
    fun `formatted shutter speed`() {
        val settings = CameraSettings(shutterSpeedDenominator = 250)
        assertThat(settings.formattedShutterSpeed).isEqualTo("1/250s")
    }

    @Test
    fun `formatted shutter speed for long exposure`() {
        val settings = CameraSettings(shutterSpeedDenominator = 1)
        assertThat(settings.formattedShutterSpeed).isEqualTo("1s")
    }
}
