package com.spectra.camera

import com.spectra.core.model.PhotoStyle
import org.junit.Assert.*
import org.junit.Test

class ToneCurveColorAppearanceTest {

    @Test
    fun `natural style with no flags is identity`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.NATURAL)
        for (i in 0..255) {
            assertEquals(i, curves.r[i])
            assertEquals(i, curves.g[i])
            assertEquals(i, curves.b[i])
        }
    }

    @Test
    fun `vivid style increases contrast`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.VIVID)
        assertTrue("Vivid darks crushed", curves.r[64] < 64)
        assertTrue("Vivid highlights boosted", curves.r[192] > 192)
        assertTrue(curves.r[240] > curves.r[128])
    }

    @Test
    fun `film style lifts blacks`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.FILM)
        assertTrue(curves.r[0] > 0)
        assertTrue(curves.g[0] > 0)
    }

    @Test
    fun `cinematic style crushes blacks`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.CINEMATIC)
        assertTrue(curves.r[0] > 0)
    }

    @Test
    fun `highlight shoulder compresses highlights`() {
        val raw = ToneCurveEngine.highlightShoulder(250, 200, 240, 0.8f)
        assertTrue(raw < 250)
        assertTrue(raw >= 200)
        assertTrue(raw <= 240)
    }

    @Test
    fun `highlight shoulder is identity below start`() {
        val raw = ToneCurveEngine.highlightShoulder(180, 200, 240, 0.8f)
        assertEquals(180, raw)
    }

    @Test
    fun `highlight rolloff curve is monotonic`() {
        val curve = ToneCurveEngine.buildHighlightRolloffCurve(190, 240, 0.7f)
        for (i in 1..255) {
            assertTrue(curve[i] >= curve[i - 1])
        }
    }

    @Test
    fun `film highlight params have strong rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(PhotoStyle.FILM)
        assertTrue(params.strength >= 0.7f)
        assertTrue(params.maxOutput < 250)
    }

    @Test
    fun `natural highlight params have no rolloff`() {
        val params = ToneCurveEngine.styleHighlightParams(PhotoStyle.NATURAL)
        assertEquals(0f, params.strength, 0.01f)
        assertEquals(255, params.maxOutput)
    }

    @Test
    fun `sCurve with zero strength is identity`() {
        val curve = ToneCurveEngine.sCurve(0f)
        for (i in 0..255) {
            assertEquals(i, curve[i])
        }
    }

    @Test
    fun `hable filmic is monotonically increasing`() {
        var prev = ToneCurveEngine.hableFilmic(0f)
        for (i in 1..100) {
            val t = i / 100f
            val curr = ToneCurveEngine.hableFilmic(t)
            assertTrue(curr >= prev)
            prev = curr
        }
    }

    @Test
    fun `blend curves interpolate correctly`() {
        val a = IntArray(256) { 0 }
        val b = IntArray(256) { 255 }
        val blended = ToneCurveEngine.blendCurves(a, b, 0.5f)
        assertEquals(128, blended[128])
        assertEquals(128, blended[0])
    }
}
