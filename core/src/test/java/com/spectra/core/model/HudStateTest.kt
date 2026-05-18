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
        assertThat(state.sceneLabel).isEqualTo("")
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

    @Test
    fun `isLowLight true when ISO above 800`() {
        val state = HudState(actualIso = 1600, actualShutterSpeedNs = 10_000_000L)
        assertThat(state.isLowLight).isTrue()
    }

    @Test
    fun `isLowLight true when shutter above 33ms`() {
        val state = HudState(actualIso = 100, actualShutterSpeedNs = 50_000_000L)
        assertThat(state.isLowLight).isTrue()
    }

    @Test
    fun `isLowLight false in daylight`() {
        val state = HudState(actualIso = 100, actualShutterSpeedNs = 5_000_000L)
        assertThat(state.isLowLight).isFalse()
    }

    @Test
    fun `default grid mode is THIRDS`() {
        val state = HudState()
        assertThat(state.gridMode).isEqualTo(com.spectra.core.model.GridMode.THIRDS)
    }

    @Test
    fun `focus peaking defaults to disabled`() {
        val state = HudState()
        assertThat(state.focusPeakingEnabled).isFalse()
        assertThat(state.zebraEnabled).isFalse()
    }

    @Test
    fun `beauty level defaults to zero`() {
        val state = HudState()
        assertThat(state.beautyLevel).isEqualTo(0)
    }

    @Test
    fun `zebra threshold defaults to 235`() {
        val state = HudState()
        assertThat(state.zebraThreshold).isEqualTo(235)
    }

    @Test
    fun `zebra threshold can be set to custom values`() {
        val state = HudState(zebraThreshold = 243)
        assertThat(state.zebraThreshold).isEqualTo(243)
    }

    @Test
    fun `copy preserves zebra threshold`() {
        val state = HudState(zebraThreshold = 250)
        val copied = state.copy(zebraEnabled = true)
        assertThat(copied.zebraThreshold).isEqualTo(250)
    }
}
