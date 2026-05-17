// core/src/test/java/com/spectra/core/model/SettingsDisplayModeTest.kt
package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsDisplayModeTest {

    @Test
    fun `ACTUAL mode is default for non-PRO presets`() {
        assertThat(SettingsDisplayMode.ACTUAL.showsActualSensorValues).isTrue()
    }

    @Test
    fun `MANUAL mode is used for PRO preset`() {
        assertThat(SettingsDisplayMode.MANUAL.showsActualSensorValues).isFalse()
    }

    @Test
    fun `SMART_AUTO applies AI values to hardware`() {
        assertThat(SettingsDisplayMode.SMART_AUTO.appliesAiValues).isTrue()
    }

    @Test
    fun `ACTUAL mode does not apply AI values`() {
        assertThat(SettingsDisplayMode.ACTUAL.appliesAiValues).isFalse()
    }

    @Test
    fun `HudState defaults to ACTUAL display mode`() {
        val state = HudState()
        assertThat(state.settingsDisplayMode).isEqualTo(SettingsDisplayMode.ACTUAL)
    }
}
