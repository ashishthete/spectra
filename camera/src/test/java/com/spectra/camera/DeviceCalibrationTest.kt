package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class DeviceCalibrationTest {

    @After
    fun resetProfile() {
        // Reset the singleton so each test is independent
        val field = DeviceCalibration::class.java.getDeclaredField("profile")
        field.isAccessible = true
        field.set(DeviceCalibration, null)
    }

    @Test
    fun `CalibrationProfile stores all fields correctly`() {
        val profile = DeviceCalibration.CalibrationProfile(
            deviceModel = "samsung_sm-s928b",
            exposureCompensation = 0.2f,
            colorTemperatureOffset = -100,
            redGain = 0.98f,
            greenGain = 1.0f,
            blueGain = 1.02f,
            sharpnessMultiplier = 0.9f,
            noiseFloorOffset = 0.005f,
            vignetteStrength = 0.85f,
            wideDistortionK1 = -0.012f,
            maxUsableIso = 3200
        )

        assertThat(profile.deviceModel).isEqualTo("samsung_sm-s928b")
        assertThat(profile.exposureCompensation).isWithin(0.001f).of(0.2f)
        assertThat(profile.colorTemperatureOffset).isEqualTo(-100)
        assertThat(profile.redGain).isWithin(0.001f).of(0.98f)
        assertThat(profile.greenGain).isWithin(0.001f).of(1.0f)
        assertThat(profile.blueGain).isWithin(0.001f).of(1.02f)
        assertThat(profile.sharpnessMultiplier).isWithin(0.001f).of(0.9f)
        assertThat(profile.noiseFloorOffset).isWithin(0.0001f).of(0.005f)
        assertThat(profile.vignetteStrength).isWithin(0.001f).of(0.85f)
        assertThat(profile.wideDistortionK1).isWithin(0.0001f).of(-0.012f)
        assertThat(profile.maxUsableIso).isEqualTo(3200)
    }

    @Test
    fun `default profile has neutral values when no JSON loaded`() {
        // No profile loaded — getProfile returns a default CalibrationProfile
        val profile = DeviceCalibration.getProfile()

        assertThat(profile.exposureCompensation).isWithin(0.001f).of(0f)
        assertThat(profile.colorTemperatureOffset).isEqualTo(0)
        assertThat(profile.redGain).isWithin(0.001f).of(1f)
        assertThat(profile.greenGain).isWithin(0.001f).of(1f)
        assertThat(profile.blueGain).isWithin(0.001f).of(1f)
        assertThat(profile.sharpnessMultiplier).isWithin(0.001f).of(1f)
        assertThat(profile.noiseFloorOffset).isWithin(0.001f).of(0f)
        assertThat(profile.vignetteStrength).isWithin(0.001f).of(1f)
        assertThat(profile.wideDistortionK1).isWithin(0.001f).of(0f)
        assertThat(profile.maxUsableIso).isEqualTo(3200)
    }

    @Test
    fun `applyColorCorrection with unity gains is a no-op`() {
        // Set a profile with unity gains via reflection
        val unityProfile = DeviceCalibration.CalibrationProfile(
            deviceModel = "test",
            redGain = 1f,
            greenGain = 1f,
            blueGain = 1f
        )
        val field = DeviceCalibration::class.java.getDeclaredField("profile")
        field.isAccessible = true
        field.set(DeviceCalibration, unityProfile)

        val original = intArrayOf(
            (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200,
            (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 128
        )
        val pixels = original.copyOf()

        DeviceCalibration.applyColorCorrection(pixels)

        assertThat(pixels[0]).isEqualTo(original[0])
        assertThat(pixels[1]).isEqualTo(original[1])
    }

    @Test
    fun `applyColorCorrection scales channels correctly`() {
        val gainProfile = DeviceCalibration.CalibrationProfile(
            deviceModel = "test",
            redGain = 0.5f,
            greenGain = 2.0f,
            blueGain = 1.0f
        )
        val field = DeviceCalibration::class.java.getDeclaredField("profile")
        field.isAccessible = true
        field.set(DeviceCalibration, gainProfile)

        // pixel: R=100, G=50, B=200
        val pixels = intArrayOf((0xFF shl 24) or (100 shl 16) or (50 shl 8) or 200)
        DeviceCalibration.applyColorCorrection(pixels)

        val r = (pixels[0] shr 16) and 0xFF
        val g = (pixels[0] shr 8) and 0xFF
        val b = pixels[0] and 0xFF

        assertThat(r).isEqualTo(50)   // 100 * 0.5
        assertThat(g).isEqualTo(100)  // 50 * 2.0
        assertThat(b).isEqualTo(200)  // 200 * 1.0
    }

    @Test
    fun `applyColorCorrection clamps channels to 0-255`() {
        val gainProfile = DeviceCalibration.CalibrationProfile(
            deviceModel = "test",
            redGain = 3.0f,   // would overflow 255
            greenGain = 0.0f, // would go to 0
            blueGain = 1.0f
        )
        val field = DeviceCalibration::class.java.getDeclaredField("profile")
        field.isAccessible = true
        field.set(DeviceCalibration, gainProfile)

        val pixels = intArrayOf((0xFF shl 24) or (200 shl 16) or (128 shl 8) or 100)
        DeviceCalibration.applyColorCorrection(pixels)

        val r = (pixels[0] shr 16) and 0xFF
        val g = (pixels[0] shr 8) and 0xFF

        assertThat(r).isEqualTo(255)  // clamped from 600
        assertThat(g).isEqualTo(0)    // clamped from 0
    }

    @Test
    fun `applyColorCorrection does nothing when no profile loaded`() {
        // profile is null (reset in @After), so function is a no-op
        val pixels = intArrayOf(
            (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200
        )
        val original = pixels.copyOf()

        DeviceCalibration.applyColorCorrection(pixels)

        assertThat(pixels[0]).isEqualTo(original[0])
    }
}
