package com.spectra.ai

import com.spectra.ai.model.MotionLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VideoCoachingTest {

    private lateinit var engine: CoachingEngine

    @Before
    fun setUp() {
        engine = CoachingEngine()
    }

    @Test
    fun `no coaching when not recording`() {
        val hint = engine.generateVideoCoaching(
            isRecording = false,
            motionLevel = MotionLevel.FAST,
            motionSource = MotionDetector.MotionSource.CAMERA_SHAKE,
            hasFaces = false,
            recordingDurationMs = 5000
        )
        assertNull(hint)
    }

    @Test
    fun `warns about walking shake during recording`() {
        val hint = engine.generateVideoCoaching(
            isRecording = true,
            motionLevel = MotionLevel.MODERATE,
            motionSource = MotionDetector.MotionSource.CAMERA_SHAKE,
            hasFaces = false,
            recordingDurationMs = 5000
        )
        assertNotNull(hint)
        assertTrue(hint!!.text.contains("shake") || hint.text.contains("hands"))
    }

    @Test
    fun `suggests hold at start of recording`() {
        val hint = engine.generateVideoCoaching(
            isRecording = true,
            motionLevel = MotionLevel.STATIC,
            motionSource = MotionDetector.MotionSource.STABLE,
            hasFaces = false,
            recordingDurationMs = 1000
        )
        assertNotNull(hint)
        assertTrue(hint!!.text.contains("3 seconds") || hint.text.contains("Hold"))
    }

    @Test
    fun `suggests slow pan during fast panning`() {
        val hint = engine.generateVideoCoaching(
            isRecording = true,
            motionLevel = MotionLevel.FAST,
            motionSource = MotionDetector.MotionSource.PANNING,
            hasFaces = false,
            recordingDurationMs = 5000
        )
        assertNotNull(hint)
        assertTrue(hint!!.text.lowercase().contains("pan") || hint.text.lowercase().contains("smooth"))
    }

    @Test
    fun `suggests face tracking when face steady`() {
        val hint = engine.generateVideoCoaching(
            isRecording = true,
            motionLevel = MotionLevel.STATIC,
            motionSource = MotionDetector.MotionSource.STABLE,
            hasFaces = true,
            recordingDurationMs = 5000
        )
        assertNotNull(hint)
        assertTrue(hint!!.text.contains("face") || hint.text.contains("tracking") || hint.text.contains("tap"))
    }
}
