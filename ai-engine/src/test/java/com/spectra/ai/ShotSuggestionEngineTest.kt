package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.CameraPreset
import org.junit.Test

class ShotSuggestionEngineTest {

    @Test
    fun `returns suggestion for known preset`() {
        val suggestion = ShotSuggestionEngine.getSuggestion(CameraPreset.PORTRAIT)
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.text).isNotEmpty()
    }

    @Test
    fun `returns null for AUTO preset`() {
        val suggestion = ShotSuggestionEngine.getSuggestion(CameraPreset.AUTO)
        assertThat(suggestion).isNull()
    }

    @Test
    fun `filters stability hint when already stable`() {
        val stableSuggestion = ShotSuggestionEngine.getSuggestion(
            preset = CameraPreset.NIGHT,
            isStable = true
        )
        assertThat(stableSuggestion).isNotNull()
        assertThat(stableSuggestion!!.text.lowercase()).doesNotContain("brace")
    }

    @Test
    fun `stability hint surfaced when not stable`() {
        val suggestion = ShotSuggestionEngine.getSuggestion(
            preset = CameraPreset.NIGHT,
            isStable = false
        )
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.text.lowercase()).contains("brace")
    }

    @Test
    fun `returns highest priority suggestion`() {
        val suggestion = ShotSuggestionEngine.getSuggestion(CameraPreset.PORTRAIT)
        assertThat(suggestion!!.priority).isEqualTo(2)
        assertThat(suggestion.text).contains("off-center")
    }

    @Test
    fun `returns suggestion for macro preset`() {
        val suggestion = ShotSuggestionEngine.getSuggestion(CameraPreset.MACRO)
        assertThat(suggestion).isNotNull()
    }
}
