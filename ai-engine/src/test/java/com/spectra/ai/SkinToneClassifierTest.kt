package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkinToneClassifierTest {

    private val classifier = SkinToneClassifier()

    @Test
    fun `light skin maps to MST 1-2`() {
        val pixels = createSkinPixels(230, 190, 170)
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.tone.shade).isIn(1..2)
        assertThat(result.tone.isLight).isTrue()
    }

    @Test
    fun `medium skin maps to MST 3-6`() {
        val pixels = createSkinPixels(180, 140, 110)
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.tone.shade).isIn(3..6)
        assertThat(result.tone.isMedium).isTrue()
    }

    @Test
    fun `dark skin maps to MST 7-10`() {
        val pixels = createSkinPixels(90, 65, 50)
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.tone.shade).isIn(7..10)
        assertThat(result.tone.isDark).isTrue()
    }

    @Test
    fun `light skin gets negative AWB shift`() {
        val pixels = createSkinPixels(230, 190, 170)
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.awbShiftK).isLessThan(0)
    }

    @Test
    fun `dark skin gets positive AWB shift and EV boost`() {
        val pixels = createSkinPixels(90, 65, 50)
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.awbShiftK).isGreaterThan(0)
        assertThat(result.evCompensation).isGreaterThan(0f)
    }

    @Test
    fun `empty pixels return default MST 5`() {
        val result = classifier.classifyFromPixels(IntArray(0))
        assertThat(result.tone).isEqualTo(MonkSkinTone.MST_5)
    }

    @Test
    fun `non-skin pixels return default`() {
        val pixels = IntArray(100) { (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 255 }
        val result = classifier.classifyFromPixels(pixels)
        assertThat(result.tone).isEqualTo(MonkSkinTone.MST_5)
    }

    @Test
    fun `all 10 MST entries have valid Lab values`() {
        for (tone in MonkSkinTone.entries) {
            assertThat(tone.labL).isAtLeast(0f)
            assertThat(tone.labL).isAtMost(100f)
            assertThat(tone.shade).isIn(1..10)
        }
    }

    private fun createSkinPixels(r: Int, g: Int, b: Int, count: Int = 100): IntArray {
        return IntArray(count) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
    }
}
