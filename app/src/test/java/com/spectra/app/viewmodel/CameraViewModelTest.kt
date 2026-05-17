package com.spectra.app.viewmodel

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.CameraMode
import com.spectra.core.model.HudState
import com.spectra.core.model.LensId
import org.junit.Test

class CameraViewModelTest {

    @Test
    fun `initial hud state is PHOTO mode with MAIN lens`() {
        val state = HudState()
        assertThat(state.mode).isEqualTo(CameraMode.PHOTO)
        assertThat(state.activeLens).isEqualTo(LensId.MAIN)
        assertThat(state.isHudVisible).isTrue()
    }

    @Test
    fun `mode change updates hud state`() {
        val state = HudState().copy(mode = CameraMode.NIGHT)
        assertThat(state.mode).isEqualTo(CameraMode.NIGHT)
    }

    @Test
    fun `lens cycle wraps around`() {
        val lenses = LensId.entries.toList()
        val current = LensId.TELEPHOTO_5X
        val currentIndex = lenses.indexOf(current)
        val next = lenses[(currentIndex + 1) % lenses.size]
        assertThat(next).isEqualTo(LensId.ULTRAWIDE)
    }

    @Test
    fun `hud toggle flips visibility`() {
        val state = HudState(isHudVisible = true)
        val toggled = state.copy(isHudVisible = false)
        assertThat(toggled.isHudVisible).isFalse()
    }
}
