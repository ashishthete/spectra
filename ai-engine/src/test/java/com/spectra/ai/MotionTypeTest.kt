package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.MotionLevel
import org.junit.Test

class MotionTypeTest {

    private val detector = MotionDetector()

    @Test
    fun `STATIC when gyro still and no frame diff`() {
        val frame = FloatArray(100) { 0.5f }
        detector.addFrame(frame)
        detector.addFrame(frame.clone())
        detector.updateGyro(0.1f, consistentFrames = 0)

        assertThat(detector.currentMotionType).isEqualTo(MotionDetector.MotionType.STATIC)
    }

    @Test
    fun `CAMERA_SHAKE when gyro moving and frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.8f, consistentFrames = 1)

        assertThat(detector.currentMotionType).isEqualTo(MotionDetector.MotionType.CAMERA_SHAKE)
    }

    @Test
    fun `SUBJECT_MOTION when gyro still but large frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.65f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.1f, consistentFrames = 0)

        assertThat(detector.currentMotionType).isEqualTo(MotionDetector.MotionType.SUBJECT_MOTION)
    }

    @Test
    fun `PAN when gyro consistent direction and frame diff`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.4f, consistentFrames = 6)

        assertThat(detector.currentMotionType).isEqualTo(MotionDetector.MotionType.PAN)
    }

    @Test
    fun `MotionType has all expected values`() {
        val types = MotionDetector.MotionType.values()
        assertThat(types.map { it.name }).containsExactly(
            "STATIC", "CAMERA_SHAKE", "SUBJECT_MOTION", "PAN"
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
    fun `reset clears motion type`() {
        val frame1 = FloatArray(100) { 0.5f }
        val frame2 = FloatArray(100) { 0.6f }
        detector.addFrame(frame1)
        detector.addFrame(frame2)
        detector.updateGyro(0.8f, consistentFrames = 1)
        assertThat(detector.currentMotionType).isNotEqualTo(MotionDetector.MotionType.STATIC)

        detector.reset()
        assertThat(detector.currentMotionType).isEqualTo(MotionDetector.MotionType.STATIC)
    }
}
