// camera/src/test/java/com/spectra/camera/DepthBokehTest.kt
package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DepthBokehTest {

    private val bokeh = DepthBokeh()

    @Test
    fun `computeBlurRadius returns 0 at focus depth`() {
        val radius = DepthBokeh.computeBlurRadius(0.5f, 0.5f, 15f)
        assertThat(radius).isEqualTo(0f)
    }

    @Test
    fun `computeBlurRadius increases with depth difference`() {
        val near = DepthBokeh.computeBlurRadius(0.3f, 0.5f, 15f)
        val far = DepthBokeh.computeBlurRadius(0.1f, 0.5f, 15f)
        assertThat(far).isGreaterThan(near)
    }

    @Test
    fun `computeBlurRadius is clamped to maxRadius`() {
        val radius = DepthBokeh.computeBlurRadius(0.0f, 1.0f, 15f)
        assertThat(radius).isAtMost(15f)
    }

    @Test
    fun `guidedFilter preserves edges`() {
        val w = 8; val h = 8; val n = w * h
        val guide = FloatArray(n) { i -> if (i % w < w / 2) 0.2f else 0.8f }
        val input = FloatArray(n) { i -> if (i % w < w / 2) 0.25f else 0.75f }
        val filtered = bokeh.guidedFilter(guide, input, w, h, radius = 2, eps = 0.01f)
        assertThat(filtered).hasLength(n)
        val leftAvg = (0 until n).filter { it % w < w / 4 }.map { filtered[it] }.average()
        val rightAvg = (0 until n).filter { it % w >= w * 3 / 4 }.map { filtered[it] }.average()
        assertThat(leftAvg).isLessThan(rightAvg)
    }

    @Test
    fun `buildBlurPyramid produces correct number of levels`() {
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        val pyramid = bokeh.buildBlurPyramid(pixels, w, h, levels = 4, baseRadius = 5)
        assertThat(pyramid).hasSize(4)
        for (level in pyramid) {
            assertThat(level).hasLength(w * h)
        }
    }

    @Test
    fun `buildBlurPyramid level 0 is sharp original`() {
        val w = 8; val h = 8
        val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 3) shl 16) or ((i * 2) shl 8) or i }
        val pyramid = bokeh.buildBlurPyramid(pixels, w, h, levels = 3, baseRadius = 3)
        for (i in pixels.indices) {
            assertThat(pyramid[0][i]).isEqualTo(pixels[i])
        }
    }

    @Test
    fun `applyDepthBokeh with uniform depth returns near-original`() {
        val w = 8; val h = 8; val n = w * h
        val pixels = IntArray(n) { (0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200 }
        val depthMap = FloatArray(n) { 0.5f }
        val result = bokeh.applyDepthBokeh(pixels, depthMap, w, h, w, h, focusDepth = 0.5f)
        assertThat(result).hasLength(n)
        for (i in result.indices) {
            assertThat((result[i] shr 16) and 0xFF).isWithin(2).of(100)
        }
    }
}
