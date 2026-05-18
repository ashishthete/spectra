package com.spectra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoldenMomentDetectorTest {

    private val detector = GoldenMomentDetector()

    private fun flatHistogram(value: Int = 10): IntArray = IntArray(256) { value }

    @Test
    fun `perfect conditions score above threshold`() {
        val histogram = flatHistogram()
        val score = detector.evaluate(
            faceSmileConfidence = 1f,
            allEyesOpen = true,
            faceCount = 1,
            compositionThirdsScore = 1f,
            isStable = true,
            stableDurationMs = 600,
            histogramData = histogram
        )
        assertThat(score.overall).isGreaterThan(0.75f)
    }

    @Test
    fun `all bad conditions score below threshold`() {
        val badHistogram = IntArray(256).also { h ->
            h[255] = 1000
        }
        val score = detector.evaluate(
            faceSmileConfidence = 0f,
            allEyesOpen = false,
            faceCount = 1,
            compositionThirdsScore = 0f,
            isStable = false,
            stableDurationMs = 0,
            histogramData = badHistogram
        )
        assertThat(score.overall).isLessThan(0.3f)
    }

    @Test
    fun `cooldown prevents double fire`() {
        detector.isEnabled = true
        val histogram = flatHistogram()
        val score = detector.evaluate(
            faceSmileConfidence = 1f,
            allEyesOpen = true,
            faceCount = 1,
            compositionThirdsScore = 1f,
            isStable = true,
            stableDurationMs = 600,
            histogramData = histogram
        )
        val now = System.currentTimeMillis()
        val first = detector.shouldAutoCapture(score, now)
        val second = detector.shouldAutoCapture(score, now + 100)
        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `disabled detector never fires`() {
        detector.isEnabled = false
        val histogram = flatHistogram()
        val score = detector.evaluate(
            faceSmileConfidence = 1f,
            allEyesOpen = true,
            faceCount = 1,
            compositionThirdsScore = 1f,
            isStable = true,
            stableDurationMs = 600,
            histogramData = histogram
        )
        assertThat(detector.shouldAutoCapture(score)).isFalse()
    }

    @Test
    fun `no faces redistributes weight and can still score high`() {
        val histogram = flatHistogram()
        val score = detector.evaluate(
            faceSmileConfidence = 0f,
            allEyesOpen = false,
            faceCount = 0,
            compositionThirdsScore = 1f,
            isStable = true,
            stableDurationMs = 600,
            histogramData = histogram
        )
        assertThat(score.faceScore).isEqualTo(0f)
        assertThat(score.overall).isGreaterThan(0.7f)
    }
}
