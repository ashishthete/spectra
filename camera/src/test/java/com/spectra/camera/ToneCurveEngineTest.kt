package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import com.spectra.core.model.PhotoStyle
import org.junit.Test

class ToneCurveEngineTest {

    @Test
    fun `identity curve returns same values`() {
        val curve = ToneCurveEngine.identityCurve()
        assertThat(curve.size).isEqualTo(256)
        for (i in 0..255) {
            assertThat(curve[i]).isEqualTo(i)
        }
    }

    @Test
    fun `S-curve boosts midtones and compresses extremes`() {
        val curve = ToneCurveEngine.sCurve(0.5f)
        assertThat(curve[0]).isEqualTo(0)
        assertThat(curve[255]).isEqualTo(255)
        // Midpoint is unchanged (128 maps to 128)
        assertThat(curve[128]).isEqualTo(128)
        // Shadows lifted above identity
        assertThat(curve[64]).isGreaterThan(64)
        // Highlights compressed below identity
        assertThat(curve[192]).isLessThan(192)
    }

    @Test
    fun `NATURAL style returns identity curves`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.NATURAL)
        val identity = ToneCurveEngine.identityCurve()
        assertThat(curves.r.toList()).isEqualTo(identity.toList())
        assertThat(curves.g.toList()).isEqualTo(identity.toList())
        assertThat(curves.b.toList()).isEqualTo(identity.toList())
    }

    @Test
    fun `VIVID style has S-curve on all channels`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.VIVID)
        // S-curve lifts shadows above identity
        assertThat(curves.r[64]).isGreaterThan(64)
        assertThat(curves.g[64]).isGreaterThan(64)
        // S-curve compresses highlights below identity
        assertThat(curves.r[192]).isLessThan(192)
        assertThat(curves.g[192]).isLessThan(192)
        // Blue channel has extra boost in shadows vs red
        assertThat(curves.b[32]).isAtLeast(curves.r[32])
    }

    @Test
    fun `FILM style has lifted blacks`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.FILM)
        // Black point is lifted (floor > 5)
        assertThat(curves.r[0]).isGreaterThan(5)
        assertThat(curves.g[0]).isGreaterThan(5)
        // White point is compressed
        assertThat(curves.r[255]).isLessThan(250)
    }

    @Test
    fun `CINEMATIC style has teal shadows`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.CINEMATIC)
        // Blue channel should be higher than red in shadows (teal look)
        assertThat(curves.b[32]).isGreaterThan(curves.r[32])
    }

    @Test
    fun `WARM style has red boost and blue reduction`() {
        val curves = ToneCurveEngine.getCurvesForStyle(PhotoStyle.WARM)
        // Red shifted up, blue shifted down
        assertThat(curves.r[128]).isGreaterThan(curves.b[128])
    }

    @Test
    fun `liftedCurve maps to floor-ceiling range`() {
        val curve = ToneCurveEngine.liftedCurve(10, 240)
        assertThat(curve[0]).isEqualTo(10)
        assertThat(curve[255]).isEqualTo(240)
    }

    @Test
    fun `blendCurves interpolates between curves`() {
        val identity = ToneCurveEngine.identityCurve()
        val constant = IntArray(256) { 200 }
        val blended = ToneCurveEngine.blendCurves(identity, constant, 0.5f)

        // At index 100: identity=100, constant=200, 50% blend => ~150
        val expected = ((1f - 0.5f) * 100 + 0.5f * 200).toInt()
        assertThat(blended[100]).isEqualTo(expected)

        // At index 0: identity=0, constant=200, 50% blend => 100
        val expected0 = ((1f - 0.5f) * 0 + 0.5f * 200).toInt()
        assertThat(blended[0]).isEqualTo(expected0)
    }
}
