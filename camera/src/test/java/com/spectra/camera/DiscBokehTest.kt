package com.spectra.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscBokehTest {

    private val bokeh = DepthBokeh()

    @Test
    fun `blur radius is zero when pixel is at focus depth`() {
        val radius = DepthBokeh.computeBlurRadius(0.5f, 0.5f, 20f)
        assertEquals(0f, radius, 0.001f)
    }

    @Test
    fun `blur radius increases with depth difference`() {
        val near = DepthBokeh.computeBlurRadius(0.3f, 0.5f, 20f)
        val far = DepthBokeh.computeBlurRadius(0.1f, 0.5f, 20f)
        assertTrue(far > near)
    }

    @Test
    fun `blur radius clamped to max`() {
        val radius = DepthBokeh.computeBlurRadius(0.0f, 1.0f, 15f)
        assertTrue(radius <= 15f)
    }

    @Test
    fun `focus depth from face rect samples center`() {
        val depthSize = 8
        val depthMap = FloatArray(depthSize * depthSize) { 0.8f }
        for (dy in -1..1) {
            for (dx in -1..1) {
                depthMap[(4 + dy) * depthSize + (4 + dx)] = 0.3f
            }
        }

        val faceRects = listOf(
            android.graphics.RectF(0.4f, 0.4f, 0.7f, 0.7f)
        )
        val focus = DepthBokeh.selectFocusDepth(depthMap, depthSize, faceRects)
        assertTrue(focus < 0.5f)
    }

    @Test
    fun `focus depth without faces uses center`() {
        val depthSize = 4
        val depthMap = FloatArray(depthSize * depthSize) { 0.9f }
        depthMap[2 * depthSize + 2] = 0.2f

        val focus = DepthBokeh.selectFocusDepth(depthMap, depthSize, emptyList())
        assertEquals(0.2f, focus, 0.001f)
    }

    @Test
    fun `applyDepthBokeh preserves in-focus pixels`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val depthMap = FloatArray(w * h) { 0.5f }
        val focusDepth = 0.5f

        val result = bokeh.applyDepthBokeh(
            pixels, depthMap, w, h, w, h,
            focusDepth = focusDepth, maxBlurRadius = 10f
        )

        val centerIdx = 8 * w + 8
        val rOrig = (pixels[centerIdx] shr 16) and 0xFF
        val rResult = (result[centerIdx] shr 16) and 0xFF
        assertTrue(kotlin.math.abs(rOrig - rResult) < 5)
    }

    @Test
    fun `guided filter coefficients are valid`() {
        val w = 8
        val h = 8
        val guide = FloatArray(w * h) { it.toFloat() / (w * h) }
        val input = FloatArray(w * h) { 0.5f + it.toFloat() / (w * h) * 0.3f }

        val coeffs = bokeh.guidedFilterCoefficients(guide, input, w, h, 2, 0.01f)
        assertEquals(w * h, coeffs.first.size)
        assertEquals(w * h, coeffs.second.size)

        for (v in coeffs.first) assertTrue(v.isFinite())
        for (v in coeffs.second) assertTrue(v.isFinite())
    }
}
