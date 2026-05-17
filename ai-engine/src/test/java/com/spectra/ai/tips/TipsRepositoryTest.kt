package com.spectra.ai.tips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.spectra.core.model.SceneType
import org.junit.Test

class TipsRepositoryTest {

    private val repo = TipsRepository()

    @Test
    fun `landscape has tips`() {
        val tip = repo.getTip(SceneType.LANDSCAPE)
        assertThat(tip).isNotNull()
        assertThat(tip!!.tips).isNotEmpty()
        assertThat(tip.referenceImageAsset).contains("landscape")
    }

    @Test
    fun `all scene types except UNKNOWN have tips`() {
        SceneType.entries
            .filter { it != SceneType.UNKNOWN }
            .forEach { scene ->
                val tip = repo.getTip(scene)
                assertWithMessage("tip for $scene").that(tip).isNotNull()
            }
    }

    @Test
    fun `UNKNOWN returns null`() {
        val tip = repo.getTip(SceneType.UNKNOWN)
        assertThat(tip).isNull()
    }
}
