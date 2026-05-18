package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.CameraPreset
import com.spectra.core.model.CameraSettings
import org.junit.Test

class TeachMeEngineTest {

    private val defaultSettings = CameraSettings()

    private fun lessons(
        preset: CameraPreset = CameraPreset.AUTO,
        iso: Int = 100,
        shutterSpeedNs: Long = 8_000_000L,
        faceCount: Int = 0,
        isLowLight: Boolean = false,
        motionLevel: Int = 0
    ) = TeachMeEngine.explainSettings(
        settings = defaultSettings,
        preset = preset,
        sceneLabel = "test",
        iso = iso,
        shutterSpeedNs = shutterSpeedNs,
        faceCount = faceCount,
        isLowLight = isLowLight,
        motionLevel = motionLevel
    )

    @Test
    fun `high ISO generates noise explanation`() {
        val result = lessons(iso = 1600, isLowLight = true)
        val isoLesson = result.find { it.topic.startsWith("ISO") }
        assertThat(isoLesson).isNotNull()
        assertThat(isoLesson!!.explanation).contains("grain")
        assertThat(isoLesson.explanation).contains("dark")
    }

    @Test
    fun `high ISO dim scene uses dim label`() {
        val result = lessons(iso = 800, isLowLight = false)
        val isoLesson = result.find { it.topic.startsWith("ISO") }
        assertThat(isoLesson).isNotNull()
        assertThat(isoLesson!!.explanation).contains("dim")
    }

    @Test
    fun `low ISO generates quality explanation`() {
        val result = lessons(iso = 100)
        val isoLesson = result.find { it.topic.startsWith("ISO") }
        assertThat(isoLesson).isNotNull()
        assertThat(isoLesson!!.explanation).contains("cleaner image")
        assertThat(isoLesson.tip).contains("low ISO")
    }

    @Test
    fun `ISO 200 still generates low ISO lesson`() {
        val result = lessons(iso = 200)
        val isoLesson = result.find { it.topic.startsWith("ISO") }
        assertThat(isoLesson).isNotNull()
        assertThat(isoLesson!!.explanation).contains("minimal grain")
    }

    @Test
    fun `ISO 400 generates no ISO lesson`() {
        val result = lessons(iso = 400)
        val isoLesson = result.find { it.topic.startsWith("ISO") }
        assertThat(isoLesson).isNull()
    }

    @Test
    fun `fast shutter generates motion explanation`() {
        // 1/1000s = 1_000_000 ns
        val result = lessons(shutterSpeedNs = 1_000_000L, motionLevel = 2)
        val shutterLesson = result.find { it.topic.startsWith("1/") }
        assertThat(shutterLesson).isNotNull()
        assertThat(shutterLesson!!.explanation).contains("freezes motion")
        assertThat(shutterLesson.explanation).contains("Movement was detected")
    }

    @Test
    fun `fast shutter without motion uses shake prevention text`() {
        val result = lessons(shutterSpeedNs = 1_000_000L, motionLevel = 0)
        val shutterLesson = result.find { it.topic.startsWith("1/") }
        assertThat(shutterLesson).isNotNull()
        assertThat(shutterLesson!!.explanation).contains("camera shake")
    }

    @Test
    fun `slow shutter with low light mentions compensation`() {
        // 1/15s = 66_666_666 ns
        val result = lessons(shutterSpeedNs = 66_666_666L, isLowLight = true)
        val shutterLesson = result.find { it.topic.startsWith("1/") }
        assertThat(shutterLesson).isNotNull()
        assertThat(shutterLesson!!.explanation).contains("compensating for low light")
    }

    @Test
    fun `NIGHT preset generates night mode lesson`() {
        val result = lessons(preset = CameraPreset.NIGHT)
        val nightLesson = result.find { it.topic == "Night Mode" }
        assertThat(nightLesson).isNotNull()
        assertThat(nightLesson!!.explanation).contains("multiple frames")
        assertThat(nightLesson.tip).contains("hold still")
    }

    @Test
    fun `FOOD preset generates food photography lesson`() {
        val result = lessons(preset = CameraPreset.FOOD)
        val foodLesson = result.find { it.topic == "Food Photography" }
        assertThat(foodLesson).isNotNull()
        assertThat(foodLesson!!.tip).contains("window light")
    }

    @Test
    fun `LANDSCAPE preset generates landscape lesson`() {
        val result = lessons(preset = CameraPreset.LANDSCAPE)
        val landscapeLesson = result.find { it.topic == "Landscape Mode" }
        assertThat(landscapeLesson).isNotNull()
        assertThat(landscapeLesson!!.tip).contains("horizon")
    }

    @Test
    fun `PORTRAIT with faces generates telephoto lens lesson`() {
        val result = lessons(preset = CameraPreset.PORTRAIT, faceCount = 1)
        val lensLesson = result.find { it.topic == "Telephoto lens" }
        assertThat(lensLesson).isNotNull()
        assertThat(lensLesson!!.explanation).contains("perspective")
    }

    @Test
    fun `PORTRAIT without faces does not generate telephoto lens lesson`() {
        val result = lessons(preset = CameraPreset.PORTRAIT, faceCount = 0)
        val lensLesson = result.find { it.topic == "Telephoto lens" }
        assertThat(lensLesson).isNull()
    }

    @Test
    fun `returns empty list when no interesting conditions`() {
        // ISO 400, mid shutter, AUTO preset, no faces
        val result = lessons(
            preset = CameraPreset.AUTO,
            iso = 400,
            shutterSpeedNs = 8_000_000L,
            faceCount = 0,
            isLowLight = false,
            motionLevel = 0
        )
        assertThat(result).isEmpty()
    }
}
