package com.spectra.ai

import org.junit.Assert.*
import org.junit.Test

class LightingAnalyzerWbTest {
    private val analyzer = LightingAnalyzer()

    @Test
    fun `empty pixels returns empty`() {
        val result = analyzer.applyGraduatedWbCorrection(intArrayOf(), 0, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `uniform image is nearly unchanged`() {
        val w = 64; val h = 64
        val gray = 0xFF808080.toInt()
        val pixels = IntArray(w * h) { gray }
        val result = analyzer.applyGraduatedWbCorrection(pixels, w, h)
        // Every pixel should be very close to original
        for (i in result.indices) {
            val origR = (pixels[i] shr 16) and 0xFF
            val origB = pixels[i] and 0xFF
            val newR = (result[i] shr 16) and 0xFF
            val newB = result[i] and 0xFF
            assertTrue("R diff too large: $origR vs $newR", kotlin.math.abs(origR - newR) <= 5)
            assertTrue("B diff too large: $origB vs $newB", kotlin.math.abs(origB - newB) <= 5)
        }
    }

    @Test
    fun `mixed lighting correction brings halves closer`() {
        val w = 64; val h = 64
        // Left half: warm (high R, low B) = ~3000K
        // Right half: cool (low R, high B) = ~8000K
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) {
                0xFF_C0_80_40.toInt() // warm: R=192, G=128, B=64
            } else {
                0xFF_40_80_C0.toInt() // cool: R=64, G=128, B=192
            }
        }

        val result = analyzer.applyGraduatedWbCorrection(pixels, w, h)

        // After correction, the R/B ratio of left and right should be closer
        val leftOrigRB = 192f / 64f // = 3.0
        val rightOrigRB = 64f / 192f // = 0.33

        // Sample center of left half
        val leftIdx = (h / 2) * w + w / 4
        val leftNewR = (result[leftIdx] shr 16) and 0xFF
        val leftNewB = result[leftIdx] and 0xFF
        val leftNewRB = leftNewR.toFloat() / leftNewB.coerceAtLeast(1)

        // Sample center of right half
        val rightIdx = (h / 2) * w + 3 * w / 4
        val rightNewR = (result[rightIdx] shr 16) and 0xFF
        val rightNewB = result[rightIdx] and 0xFF
        val rightNewRB = rightNewR.toFloat() / rightNewB.coerceAtLeast(1)

        // The difference in R/B ratios should be smaller after correction
        val origDiff = leftOrigRB - rightOrigRB // 2.67
        val newDiff = leftNewRB - rightNewRB
        assertTrue("Correction should reduce R/B ratio difference: orig=$origDiff, new=$newDiff",
            kotlin.math.abs(newDiff) < kotlin.math.abs(origDiff))
    }
}
