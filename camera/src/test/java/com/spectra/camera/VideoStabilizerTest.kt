package com.spectra.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoStabilizerTest {

    private lateinit var stabilizer: VideoStabilizer

    @Before
    fun setUp() {
        stabilizer = VideoStabilizer()
    }

    // ---- 1. Initial frame returns identity / zero correction ----

    @Test
    fun `first frame with zero motion returns zero correction`() {
        val correction = stabilizer.addFrame(VideoStabilizer.FrameTransform(0f, 0f, 0f))
        assertThat(correction.dx).isEqualTo(0f)
        assertThat(correction.dy).isEqualTo(0f)
        assertThat(correction.dAngle).isEqualTo(0f)
    }

    @Test
    fun `first frame with nonzero motion returns zero correction because trajectory equals smoothed`() {
        // With only one sample, movingAverage == the single cumulative value,
        // so correction = smooth - cumulative = 0.
        val correction = stabilizer.addFrame(VideoStabilizer.FrameTransform(5f, -3f, 0.1f))
        assertThat(correction.dx).isWithin(1e-6f).of(0f)
        assertThat(correction.dy).isWithin(1e-6f).of(0f)
        assertThat(correction.dAngle).isWithin(1e-6f).of(0f)
    }

    // ---- 2. Consistent gyro input -> trajectory smoothing produces smaller corrections ----

    @Test
    fun `consistent drift in one direction produces corrections opposing drift`() {
        // Feed a constant rightward drift of 2px per frame for many frames.
        // Smoothed trajectory should lag behind the cumulative trajectory,
        // so correction (smooth - cumulative) should be negative (opposing rightward drift).
        var lastCorrection = VideoStabilizer.FrameTransform()
        for (i in 0 until 60) {
            lastCorrection = stabilizer.addFrame(VideoStabilizer.FrameTransform(dx = 2f, dy = 0f, dAngle = 0f))
        }
        // After 60 frames of constant +2px drift, the cumulative is 120.
        // The moving average over the last 30 frames smooths the trajectory,
        // producing a correction that partially offsets the motion.
        assertThat(lastCorrection.dx).isLessThan(0f)
    }

    @Test
    fun `correction magnitude stabilizes when motion is consistent`() {
        val corrections = mutableListOf<Float>()
        for (i in 0 until 60) {
            val c = stabilizer.addFrame(VideoStabilizer.FrameTransform(dx = 1f, dy = 0f, dAngle = 0f))
            corrections.add(kotlin.math.abs(c.dx))
        }
        // Once the window is full (after 30 frames), correction magnitude should be constant
        val late1 = corrections[40]
        val late2 = corrections[50]
        assertThat(late2).isWithin(0.5f).of(late1)
    }

    @Test
    fun `gyro-based transform feeds into smoothing pipeline`() {
        val focalPx = 1000f
        val dt = 1f / 30f // 30 fps

        // Simulate steady gyro rotation around Z (yaw)
        for (i in 0 until 40) {
            val transform = stabilizer.estimateTransformFromGyro(
                gyroX = 0f, gyroY = 0.01f, gyroZ = 0f,
                dtSeconds = dt, focalLengthPx = focalPx
            )
            stabilizer.addFrame(transform)
        }

        // One more frame: correction should be finite and non-zero
        val transform = stabilizer.estimateTransformFromGyro(
            gyroX = 0f, gyroY = 0.01f, gyroZ = 0f,
            dtSeconds = dt, focalLengthPx = focalPx
        )
        val correction = stabilizer.addFrame(transform)
        assertThat(correction.dx).isFinite()
        assertThat(correction.dx).isNonZero()
    }

    // ---- 3. reset() clears accumulated state ----

    @Test
    fun `reset clears all accumulated state`() {
        // Build up some trajectory
        for (i in 0 until 20) {
            stabilizer.addFrame(VideoStabilizer.FrameTransform(dx = 3f, dy = -1f, dAngle = 0.05f))
        }

        stabilizer.reset()

        // After reset, the very first frame should behave identically to a fresh stabilizer:
        // single-sample moving average equals cumulative, so correction = 0.
        val correction = stabilizer.addFrame(VideoStabilizer.FrameTransform(dx = 3f, dy = -1f, dAngle = 0.05f))
        assertThat(correction.dx).isWithin(1e-6f).of(0f)
        assertThat(correction.dy).isWithin(1e-6f).of(0f)
        assertThat(correction.dAngle).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `reset followed by new frames matches fresh stabilizer`() {
        // Feed frames, reset, feed same frames again.
        val framesA = (1..10).map { VideoStabilizer.FrameTransform(dx = it.toFloat(), dy = -it * 0.5f, dAngle = 0.01f * it) }

        for (f in framesA) stabilizer.addFrame(f)
        stabilizer.reset()

        val fresh = VideoStabilizer()
        val correctionsAfterReset = framesA.map { stabilizer.addFrame(it) }
        val correctionsFresh = framesA.map { fresh.addFrame(it) }

        for (i in correctionsAfterReset.indices) {
            assertThat(correctionsAfterReset[i].dx).isWithin(1e-6f).of(correctionsFresh[i].dx)
            assertThat(correctionsAfterReset[i].dy).isWithin(1e-6f).of(correctionsFresh[i].dy)
            assertThat(correctionsAfterReset[i].dAngle).isWithin(1e-6f).of(correctionsFresh[i].dAngle)
        }
    }

    // ---- 4. Correction values are bounded (not infinite or NaN) ----

    @Test
    fun `corrections are finite and not NaN over many frames`() {
        for (i in 0 until 200) {
            val t = i * 0.1f
            val transform = VideoStabilizer.FrameTransform(
                dx = kotlin.math.sin(t) * 5f,
                dy = kotlin.math.cos(t) * 3f,
                dAngle = kotlin.math.sin(t * 0.3f) * 0.02f
            )
            val correction = stabilizer.addFrame(transform)
            assertThat(correction.dx).isFinite()
            assertThat(correction.dy).isFinite()
            assertThat(correction.dAngle).isFinite()
            assertThat(correction.dx.isNaN()).isFalse()
            assertThat(correction.dy.isNaN()).isFalse()
            assertThat(correction.dAngle.isNaN()).isFalse()
        }
    }

    @Test
    fun `zero gyro input produces zero transform`() {
        val transform = stabilizer.estimateTransformFromGyro(
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            dtSeconds = 1f / 30f, focalLengthPx = 1000f
        )
        assertThat(transform.dx).isWithin(1e-6f).of(0f)
        assertThat(transform.dy).isWithin(1e-6f).of(0f)
        assertThat(transform.dAngle).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `estimateTransformFromGyro produces finite values for large inputs`() {
        val transform = stabilizer.estimateTransformFromGyro(
            gyroX = 100f, gyroY = -100f, gyroZ = 50f,
            dtSeconds = 1f, focalLengthPx = 5000f
        )
        assertThat(transform.dx).isFinite()
        assertThat(transform.dy).isFinite()
        assertThat(transform.dAngle).isFinite()
    }

    @Test
    fun `estimateTransformFromGyro correctly maps axes`() {
        val focalPx = 1000f
        val dt = 0.01f

        val transform = stabilizer.estimateTransformFromGyro(
            gyroX = 1f, gyroY = 2f, gyroZ = 3f,
            dtSeconds = dt, focalLengthPx = focalPx
        )
        // dx = -gyroY * dt * focalPx = -2 * 0.01 * 1000 = -20
        assertThat(transform.dx).isWithin(1e-4f).of(-20f)
        // dy = gyroX * dt * focalPx = 1 * 0.01 * 1000 = 10
        assertThat(transform.dy).isWithin(1e-4f).of(10f)
        // dAngle = gyroZ * dt = 3 * 0.01 = 0.03
        assertThat(transform.dAngle).isWithin(1e-6f).of(0.03f)
    }

    // ---- 5. Smoothing window parameter affects output ----

    @Test
    fun `different smoothing windows produce different corrections`() {
        val smallWindow = VideoStabilizer(smoothingWindowSize = 5)
        val largeWindow = VideoStabilizer(smoothingWindowSize = 50)

        val rightFrames = List(20) { VideoStabilizer.FrameTransform(dx = 3f) }
        val leftFrames = List(10) { VideoStabilizer.FrameTransform(dx = -3f) }
        val allFrames = rightFrames + leftFrames

        var lastSmall = VideoStabilizer.FrameTransform()
        var lastLarge = VideoStabilizer.FrameTransform()
        for (f in allFrames) {
            lastSmall = smallWindow.addFrame(f)
            lastLarge = largeWindow.addFrame(f)
        }

        assertThat(lastSmall.dx).isNotEqualTo(lastLarge.dx)
    }

    @Test
    fun `window size 1 produces zero corrections`() {
        // With window size 1, movingAverage always returns the latest cumulative
        // value, so correction = smoothed - cumulative = 0 for every frame.
        val stabilizer1 = VideoStabilizer(smoothingWindowSize = 1)
        for (i in 0 until 30) {
            val correction = stabilizer1.addFrame(
                VideoStabilizer.FrameTransform(dx = (i * 0.5f), dy = (-i * 0.3f), dAngle = 0.01f)
            )
            assertThat(correction.dx).isWithin(1e-6f).of(0f)
            assertThat(correction.dy).isWithin(1e-6f).of(0f)
            assertThat(correction.dAngle).isWithin(1e-6f).of(0f)
        }
    }

    @Test
    fun `different window sizes produce different corrections for same input`() {
        val sizes = listOf(5, 15, 30)
        val frames = (1..40).map {
            VideoStabilizer.FrameTransform(
                dx = kotlin.math.sin(it * 0.2f) * 4f,
                dy = kotlin.math.cos(it * 0.15f) * 2f,
                dAngle = 0f
            )
        }

        val lastCorrections = sizes.map { windowSize ->
            val s = VideoStabilizer(smoothingWindowSize = windowSize)
            var last = VideoStabilizer.FrameTransform()
            for (f in frames) last = s.addFrame(f)
            last
        }

        // At least two of the three window sizes should yield distinct dx corrections.
        val distinctDx = lastCorrections.map { it.dx }.distinct()
        assertThat(distinctDx.size).isGreaterThan(1)
    }

    // ---- Bonus: getCropRect and applyCorrection sanity ----

    @Test
    fun `getCropRect returns inset rect`() {
        val stab = VideoStabilizer(cropMargin = 0.1f)
        val rect = stab.getCropRect(1000, 800)
        assertThat(rect.left).isEqualTo(100)
        assertThat(rect.top).isEqualTo(80)
        assertThat(rect.right).isEqualTo(900)
        assertThat(rect.bottom).isEqualTo(720)
    }

    @Test
    fun `applyCorrection with identity transform returns same pixels`() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { it * 11 + 7 }
        val result = stabilizer.applyCorrection(
            pixels, w, h,
            VideoStabilizer.FrameTransform(0f, 0f, 0f)
        )
        assertThat(result.toList()).isEqualTo(pixels.toList())
    }
}
