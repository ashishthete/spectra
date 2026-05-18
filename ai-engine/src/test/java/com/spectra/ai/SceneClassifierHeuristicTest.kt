package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneClassifierHeuristicTest {

    @Test
    fun `scene labels file matches all SceneType entries`() {
        val fileLabels = listOf(
            "LANDSCAPE", "PORTRAIT", "FOOD", "NIGHT", "ARCHITECTURE",
            "MACRO", "PET", "ACTION", "DOCUMENT", "INDOOR", "UNKNOWN"
        )
        val enumNames = SceneType.entries.map { it.name }
        assertThat(fileLabels).containsExactlyElementsIn(enumNames)
    }

    @Test
    fun `all SceneType entries have non-empty display labels`() {
        for (scene in SceneType.entries) {
            assertThat(scene.label).isNotEmpty()
        }
    }

    @Test
    fun `UNKNOWN scene label is READY`() {
        assertThat(SceneType.UNKNOWN.label).isEqualTo("READY")
    }

    @Test
    fun `SceneType has expected number of entries`() {
        assertThat(SceneType.entries).hasSize(11)
    }
}
