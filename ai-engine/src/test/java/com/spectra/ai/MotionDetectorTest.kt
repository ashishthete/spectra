package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.MotionLevel
import org.junit.Test

class MotionDetectorTest {

    private val detector = MotionDetector()

    @Test
    fun `no frames returns STATIC`() {
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `identical frames returns STATIC`() {
        val frame = FloatArray(100) { 0.5f }
        detector.addFrame(frame)
        detector.addFrame(frame.clone())
        assertThat(detector.currentMotion).isEqualTo(MotionLevel.STATIC)
    }

    @Test
    fun `very different frames returns high motion`() {
        val frame1 = FloatArray(100) { 0f }
        val frame2 = FloatArray(100) { 1f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion.barCount).isGreaterThan(2)
    }
}
