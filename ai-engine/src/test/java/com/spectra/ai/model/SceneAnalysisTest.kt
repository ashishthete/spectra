package com.spectra.ai.model

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.SceneType
import org.junit.Test

class SceneAnalysisTest {

    @Test
    fun `default analysis is UNKNOWN with zero confidence`() {
        val analysis = SceneAnalysis()
        assertThat(analysis.sceneType).isEqualTo(SceneType.UNKNOWN)
        assertThat(analysis.confidence).isEqualTo(0f)
        assertThat(analysis.lighting).isEqualTo(LightingCondition.UNKNOWN)
    }

    @Test
    fun `isStable returns true when confidence above threshold`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.85f
        )
        assertThat(analysis.isStable).isTrue()
    }

    @Test
    fun `isStable returns false when confidence below threshold`() {
        val analysis = SceneAnalysis(
            sceneType = SceneType.LANDSCAPE,
            confidence = 0.4f
        )
        assertThat(analysis.isStable).isFalse()
    }
}
