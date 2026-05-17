package com.spectra.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HudStateTest {

    @Test
    fun `default hud state has sensible values`() {
        val state = HudState()
        assertThat(state.activeLens).isEqualTo(LensId.MAIN)
        assertThat(state.mode).isEqualTo(CameraMode.PHOTO)
        assertThat(state.isHudVisible).isTrue()
        assertThat(state.sceneLabel).isEqualTo("READY")
    }

    @Test
    fun `lens match scores default to zero except active lens`() {
        val state = HudState(activeLens = LensId.MAIN)
        assertThat(state.lensMatchScores[LensId.MAIN]).isEqualTo(1.0f)
    }

    @Test
    fun `toggleHud flips visibility`() {
        val state = HudState(isHudVisible = true)
        val toggled = state.copy(isHudVisible = !state.isHudVisible)
        assertThat(toggled.isHudVisible).isFalse()
    }
}
