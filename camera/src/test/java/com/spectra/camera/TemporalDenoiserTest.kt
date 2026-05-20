package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.roundToInt

class TemporalDenoiserTest {

    private fun packArgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun unpackR(px: Int): Int = (px shr 16) and 0xFF
    private fun unpackG(px: Int): Int = (px shr 8) and 0xFF
    private fun unpackB(px: Int): Int = px and 0xFF

    private fun solidFrame(w: Int, h: Int, r: Int, g: Int, b: Int): IntArray =
        IntArray(w * h) { packArgb(r, g, b) }

    // ---- 1. First frame passes through unchanged ----

    @Test fun `first frame passes through unchanged`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame = solidFrame(w, h, 100, 150, 200)
        val result = denoiser.processFrame(frame, w, h, iso = 1600)
        assertThat(result).isEqualTo(frame)
    }

    @Test fun `first frame passes through unchanged even at high ISO`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame = IntArray(w * h) { i -> packArgb(i % 256, (i * 3) % 256, (i * 7) % 256) }
        val result = denoiser.processFrame(frame, w, h, iso = 6400)
        assertThat(result).isEqualTo(frame)
    }

    // ---- 2. Static scene: second frame blends with first ----

    @Test fun `static scene second frame blends with first`() {
        val denoiser = TemporalDenoiser(blendStrength = 0.7f)
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 120, 120, 120)

        denoiser.processFrame(frame1, w, h, iso = 1600) // seed accumulated
        val result = denoiser.processFrame(frame2, w, h, iso = 1600) // blend

        // At ISO 1600 strength=1.0, blend = 0.7*1.0 = 0.7
        // result = acc + 0.7*(cur - acc) = 100 + 0.7*(120-100) = 114
        val expected = (100 + 0.7f * (120 - 100)).roundToInt()
        for (px in result) {
            assertThat(unpackR(px)).isEqualTo(expected)
            assertThat(unpackG(px)).isEqualTo(expected)
            assertThat(unpackB(px)).isEqualTo(expected)
        }
    }

    @Test fun `blended result lies between the two frames`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 120, 120, 120)

        denoiser.processFrame(frame1, w, h, iso = 3200)
        val result = denoiser.processFrame(frame2, w, h, iso = 3200)

        for (px in result) {
            val r = unpackR(px)
            assertThat(r).isAtLeast(100)
            assertThat(r).isAtMost(120)
        }
    }

    // ---- 3. ISO-adaptive: ISO <= 200 denoising is off ----

    @Test fun `ISO 100 output matches input exactly`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 50, 50, 50)
        val frame2 = solidFrame(w, h, 80, 80, 80)

        denoiser.processFrame(frame1, w, h, iso = 100)
        val result = denoiser.processFrame(frame2, w, h, iso = 100)

        assertThat(result).isEqualTo(frame2)
    }

    @Test fun `ISO 200 output matches input exactly`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 50, 50, 50)
        val frame2 = solidFrame(w, h, 80, 80, 80)

        denoiser.processFrame(frame1, w, h, iso = 200)
        val result = denoiser.processFrame(frame2, w, h, iso = 200)

        assertThat(result).isEqualTo(frame2)
    }

    // ---- 4. ISO-adaptive: ISO >= 800 denoising is at full strength ----

    @Test fun `ISO 800 applies full strength blending`() {
        val denoiser = TemporalDenoiser(blendStrength = 0.7f)
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 120, 120, 120)

        denoiser.processFrame(frame1, w, h, iso = 800)
        val result = denoiser.processFrame(frame2, w, h, iso = 800)

        // strength=1.0, blend=0.7*1.0=0.7, result = 100 + 0.7*20 = 114
        val expected = (100 + 0.7f * 20).roundToInt()
        for (px in result) {
            assertThat(unpackR(px)).isEqualTo(expected)
        }
    }

    @Test fun `ISO 3200 applies same full strength as ISO 800`() {
        val denoiser1 = TemporalDenoiser(blendStrength = 0.7f)
        val denoiser2 = TemporalDenoiser(blendStrength = 0.7f)
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 140, 140, 140)

        denoiser1.processFrame(frame1.copyOf(), w, h, iso = 800)
        val result800 = denoiser1.processFrame(frame2.copyOf(), w, h, iso = 800)

        denoiser2.processFrame(frame1.copyOf(), w, h, iso = 3200)
        val result3200 = denoiser2.processFrame(frame2.copyOf(), w, h, iso = 3200)

        assertThat(result3200).isEqualTo(result800)
    }

    @Test fun `mid-range ISO 500 applies partial strength`() {
        val denoiser = TemporalDenoiser(blendStrength = 0.7f)
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 120, 120, 120)

        denoiser.processFrame(frame1, w, h, iso = 500)
        val result = denoiser.processFrame(frame2, w, h, iso = 500)

        // strength = (500-200)/600 = 0.5, blend = 0.7*0.5 = 0.35
        // result = 100 + 0.35*20 = 107
        val expected = (100 + 0.35f * 20).roundToInt()
        for (px in result) {
            assertThat(unpackR(px)).isEqualTo(expected)
        }
    }

    // ---- 5. Motion regions pass through, static regions are blended ----

    @Test fun `motion pixels pass through unchanged while static pixels are blended`() {
        // Motion detection uses blocks of 4x4 with luminance threshold.
        // Build a 16x16 frame. Top-left 8x8 is static, bottom-right 8x8 has big motion.
        val denoiser = TemporalDenoiser(motionThreshold = 30, blendStrength = 0.7f)
        val w = 16; val h = 16

        val frame1 = IntArray(w * h) { packArgb(100, 100, 100) }
        val frame2 = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x >= 8 && y >= 8) {
                // Large change -- well above motionThreshold in luminance
                packArgb(255, 255, 255)
            } else {
                // Small change -- well below threshold
                packArgb(105, 105, 105)
            }
        }

        denoiser.processFrame(frame1, w, h, iso = 1600)
        val result = denoiser.processFrame(frame2, w, h, iso = 1600)

        // Static region (top-left area): should be blended, not equal to frame2
        val staticIdx = 0  // (0,0) -- definitely in static region
        assertThat(result[staticIdx]).isNotEqualTo(frame2[staticIdx])

        // The blended value: acc=100, cur=105, blend=0.7 -> 100 + 0.7*5 = 103.5 -> 104
        val expectedStatic = (100 + 0.7f * 5).roundToInt()
        assertThat(unpackR(result[staticIdx])).isEqualTo(expectedStatic)

        // Motion region (bottom-right): should pass through as-is from frame2
        val motionIdx = 12 * w + 12  // (12,12) -- well inside the motion block
        assertThat(result[motionIdx]).isEqualTo(frame2[motionIdx])
    }

    @Test fun `fully static scene has no motion pixels`() {
        val denoiser = TemporalDenoiser(motionThreshold = 30, blendStrength = 0.7f)
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 110, 110, 110)  // diff=10 << threshold=30

        denoiser.processFrame(frame1, w, h, iso = 1600)
        val result = denoiser.processFrame(frame2, w, h, iso = 1600)

        // All pixels should be blended (not equal to frame2)
        val expectedVal = (100 + 0.7f * 10).roundToInt()
        for (px in result) {
            assertThat(unpackR(px)).isEqualTo(expectedVal)
        }
    }

    // ---- 6. reset() clears accumulated frame state ----

    @Test fun `reset clears state so next frame passes through unchanged`() {
        val denoiser = TemporalDenoiser()
        val w = 16; val h = 16
        val frame1 = solidFrame(w, h, 100, 100, 100)
        val frame2 = solidFrame(w, h, 120, 120, 120)

        // Seed the state
        denoiser.processFrame(frame1, w, h, iso = 1600)

        // Reset
        denoiser.reset()

        // Next frame should behave as first frame (pass through unchanged)
        val result = denoiser.processFrame(frame2, w, h, iso = 1600)
        assertThat(result).isEqualTo(frame2)
    }

    @Test fun `reset then two frames produces correct blending`() {
        val denoiser = TemporalDenoiser(blendStrength = 0.7f)
        val w = 16; val h = 16

        // Process a few frames
        denoiser.processFrame(solidFrame(w, h, 50, 50, 50), w, h, iso = 1600)
        denoiser.processFrame(solidFrame(w, h, 60, 60, 60), w, h, iso = 1600)

        // Reset and start fresh
        denoiser.reset()

        val fresh1 = solidFrame(w, h, 200, 200, 200)
        val fresh2 = solidFrame(w, h, 220, 220, 220)

        val r1 = denoiser.processFrame(fresh1, w, h, iso = 1600)
        assertThat(r1).isEqualTo(fresh1)

        val r2 = denoiser.processFrame(fresh2, w, h, iso = 1600)
        // Blends from 200 toward 220: 200 + 0.7*20 = 214
        val expected = (200 + 0.7f * 20).roundToInt()
        for (px in r2) {
            assertThat(unpackR(px)).isEqualTo(expected)
        }
    }
}
