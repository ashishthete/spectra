package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.MotionLevel
import org.junit.Test

class MotionTypeTest {

    private val detector = MotionDetector()

    @Test
    fun `STABLE when gyro still and no frame diff`() {
        val frame = FloatArray(100) { 0.5f }
        detector.addFrame(frame)
        detector.addFrame(frame.clone())
        detector.updateGyro(0.1f, consistentFrames = 0)

        assertThat(detector.currentMotionSource).isEqualTo(MotionDetector.MotionSource.STABLE)
    }

    @Test
    fun `CAMERA_SHAKE when gyro moving and frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.8f, consistentFrames = 1)

        assertThat(detector.currentMotionSource).isEqualTo(MotionDetector.MotionSource.CAMERA_SHAKE)
    }

    @Test
    fun `SUBJECT_MOTION when gyro still but large frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.65f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.1f, consistentFrames = 0)

        assertThat(detector.currentMotionSource).isEqualTo(MotionDetector.MotionSource.SUBJECT_MOTION)
    }

    @Test
    fun `PANNING when gyro consistent direction and frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.4f, consistentFrames = 6)

        assertThat(detector.currentMotionSource).isEqualTo(MotionDetector.MotionSource.PANNING)
    }

    @Test
    fun `MotionSource has all expected values`() {
        val sources = MotionDetector.MotionSource.values()
        assertThat(sources.map { it.name }).containsExactly(
            "STABLE", "CAMERA_SHAKE", "SUBJECT_MOTION", "PANNING"
        )
    }

    @Test
    fun `existing MotionLevel still works`() {
        val frame1 = FloatArray(100) { 0f }
        val frame2 = FloatArray(100) { 1f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        assertThat(detector.currentMotion.barCount).isGreaterThan(2)
    }

    @Test
    fun `reset clears motion source`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.8f, consistentFrames = 1)
        assertThat(detector.currentMotionSource).isNotEqualTo(MotionDetector.MotionSource.STABLE)

        detector.reset()
        assertThat(detector.currentMotionSource).isEqualTo(MotionDetector.MotionSource.STABLE)
    }

    // --- Tests for the public classifyMotionSource method ---

    @Test
    fun `classifyMotionSource returns STABLE for low gyro and low diff`() {
        assertThat(detector.classifyMotionSource(0.1f, 0.02f))
            .isEqualTo(MotionDetector.MotionSource.STABLE)
    }

    @Test
    fun `classifyMotionSource returns CAMERA_SHAKE for high gyro and high diff`() {
        assertThat(detector.classifyMotionSource(0.6f, 0.1f))
            .isEqualTo(MotionDetector.MotionSource.CAMERA_SHAKE)
    }

    @Test
    fun `classifyMotionSource returns SUBJECT_MOTION for low gyro and high diff`() {
        assertThat(detector.classifyMotionSource(0.1f, 0.15f))
            .isEqualTo(MotionDetector.MotionSource.SUBJECT_MOTION)
    }

    @Test
    fun `classifyMotionSource returns PANNING for consistent directional gyro`() {
        assertThat(detector.classifyMotionSource(0.4f, 0.06f, consistentFrames = 6))
            .isEqualTo(MotionDetector.MotionSource.PANNING)
    }

    @Test
    fun `classifyMotionSource returns STABLE when gyro high but diff low and not consistent`() {
        // High gyro, low diff, not enough consistent frames -> STABLE
        assertThat(detector.classifyMotionSource(0.6f, 0.02f, consistentFrames = 2))
            .isEqualTo(MotionDetector.MotionSource.STABLE)
    }

    @Test
    fun `classifyMotionSource mid-range gyro without consistency falls to STABLE`() {
        // Gyro between 0.2 and 0.5, diff below 0.08 -> STABLE (falls through all branches)
        assertThat(detector.classifyMotionSource(0.3f, 0.04f, consistentFrames = 0))
            .isEqualTo(MotionDetector.MotionSource.STABLE)
    }
}
