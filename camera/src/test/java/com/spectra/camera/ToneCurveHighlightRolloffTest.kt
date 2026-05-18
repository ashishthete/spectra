package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToneCurveHighlightRolloffTest {

    @Test
    fun `highlightShoulder compresses values above threshold`() {
        val result = ToneCurveEngine.highlightShoulder(240, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isLessThan(240)
        assertThat(result).isGreaterThan(200)
    }

    @Test
    fun `highlightShoulder passes through values below threshold`() {
        val result = ToneCurveEngine.highlightShoulder(150, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isEqualTo(150)
    }

    @Test
    fun `highlightShoulder at exactly threshold returns threshold`() {
        val result = ToneCurveEngine.highlightShoulder(200, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isEqualTo(200)
    }

    @Test
    fun `highlightShoulder at 255 is clamped to maxOutput`() {
        val result = ToneCurveEngine.highlightShoulder(255, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(result).isAtMost(250)
    }

    @Test
    fun `highlightShoulder preserves monotonicity`() {
        val results = (200..255).map { ToneCurveEngine.highlightShoulder(it, shoulderStart = 200, maxOutput = 250, strength = 1.0f) }
        for (i in 1 until results.size) {
            assertThat(results[i]).isAtLeast(results[i - 1])
        }
    }

    @Test
    fun `highlightShoulder with zero strength is identity`() {
        val result = ToneCurveEngine.highlightShoulder(240, shoulderStart = 200, maxOutput = 255, strength = 0.0f)
        assertThat(result).isEqualTo(240)
    }

    @Test
    fun `highlightShoulder with half strength is between identity and full compression`() {
        val full = ToneCurveEngine.highlightShoulder(240, shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        val half = ToneCurveEngine.highlightShoulder(240, shoulderStart = 200, maxOutput = 250, strength = 0.5f)
        assertThat(half).isGreaterThan(full)
        assertThat(half).isLessThan(240)
    }

    @Test
    fun `buildHighlightRolloffCurve returns 256 entries`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(curve).hasLength(256)
    }

    @Test
    fun `buildHighlightRolloffCurve identity region is unchanged`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        for (i in 0..200) {
            assertThat(curve[i]).isEqualTo(i)
        }
    }

    @Test
    fun `buildHighlightRolloffCurve highlight region is compressed`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        assertThat(curve[255]).isAtMost(250)
        assertThat(curve[230]).isLessThan(230)
    }

    @Test
    fun `buildHighlightRolloffCurve is monotonically increasing`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(shoulderStart = 200, maxOutput = 250, strength = 1.0f)
        for (i in 1 until 256) {
            assertThat(curve[i]).isAtLeast(curve[i - 1])
        }
    }

    @Test
    fun `NATURAL style has no highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.NATURAL)
        assertThat(params.strength).isEqualTo(0f)
    }

    @Test
    fun `FILM style has strong highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.FILM)
        assertThat(params.strength).isGreaterThan(0.5f)
        assertThat(params.maxOutput).isLessThan(255)
    }

    @Test
    fun `CINEMATIC style has highlight rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(com.spectra.core.model.PhotoStyle.CINEMATIC)
        assertThat(params.strength).isGreaterThan(0f)
    }
}
