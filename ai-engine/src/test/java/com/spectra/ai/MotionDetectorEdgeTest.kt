package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.MotionLevel
import org.junit.Test

class MotionDetectorEdgeTest {

    @Test
    fun `reset clears motion state`() {
        val detector = MotionDetector()
        val frame1 = FloatArray(100) { 0f }
        val frame2 = FloatArray(100) { 1f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion).isNotEqualTo(MotionLevel.STATIC)

        detector.reset()
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `moderate motion threshold at 0_05 to 0_12`() {
        val detector = MotionDetector()
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.5f + 0.08f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.MODERATE)
    }

    @Test
    fun `slow motion threshold at 0_02 to 0_05`() {
        val detector = MotionDetector()
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.5f + 0.03f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.SLOW)
    }

    @Test
    fun `single frame returns STATIC`() {
        val detector = MotionDetector()
        detector.addFrame(FloatArray(100) { 0.5f })
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `empty frames returns STATIC`() {
        val detector = MotionDetector()
        detector.addFrame(FloatArray(0))
        detector.addFrame(FloatArray(0))
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `three frames uses latest pair`() {
        val detector = MotionDetector()
        val still = FloatArray(100) { 0.5f }
        val moved = FloatArray(100) { 1.0f }
        detector.addFrame(still)
        detector.addFrame(moved)
        assertThat(detector.currentMotion.barCount).isGreaterThan(2)

        detector.addFrame(still.clone())
        assertThat(detector.currentMotion.barCount).isGreaterThan(2)
    }

    @Test
    fun `motion level bar counts are monotonically increasing`() {
        val levels = MotionLevel.entries
        for (i in 1 until levels.size) {
            assertThat(levels[i].barCount).isGreaterThan(levels[i - 1].barCount)
        }
    }

    @Test
    fun `fast motion threshold at 0_12 to 0_25`() {
        val detector = MotionDetector()
        val frame1 = FloatArray(100) { 0.3f }
        val frame2 = FloatArray(100) { 0.3f + 0.18f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.FAST)
    }
}
