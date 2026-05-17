package com.spectra.ai.cloud

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.CoachingHint
import org.junit.Test

class CloudCoachingManagerTest {

    private fun makePixels(brightness: Int): IntArray {
        val pixel = (0xFF shl 24) or (brightness shl 16) or (brightness shl 8) or brightness
        return IntArray(32 * 32) { pixel }
    }

    @Test
    fun shouldQuery_noStableFrames_returnsFalse() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        assertThat(manager.shouldQuery()).isFalse()
    }

    @Test
    fun shouldQuery_stableFor2Seconds_returnsTrue() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        val pixels = makePixels(128)
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.setStableStartMs(System.currentTimeMillis() - 2100)
        assertThat(manager.shouldQuery()).isTrue()
    }

    @Test
    fun shouldQuery_cachedResult_returnsFalse() {
        val cache = FrameSimilarityCache()
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("CACHED"))
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = cache
        )
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.setStableStartMs(System.currentTimeMillis() - 2100)
        assertThat(manager.shouldQuery()).isFalse()
    }

    @Test
    fun getCachedHint_afterStore_returnsCached() {
        val cache = FrameSimilarityCache()
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("FROM CACHE"))
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = cache
        )
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        assertThat(manager.getCachedHint()).isNotNull()
        assertThat(manager.getCachedHint()!!.text).isEqualTo("FROM CACHE")
    }

    @Test
    fun reset_clearsState() {
        val manager = CloudCoachingManager(
            client = FakeCloudCoachingClient(),
            cache = FrameSimilarityCache()
        )
        val pixels = makePixels(128)
        manager.onFrameAnalyzed(pixels, "LANDSCAPE", "GOLDEN_HOUR")
        manager.reset()
        assertThat(manager.shouldQuery()).isFalse()
    }

    private class FakeCloudCoachingClient : CloudCoachingClientInterface {
        override suspend fun analyzeFrame(
            bitmap: Bitmap,
            sceneLabel: String,
            lightingLabel: String
        ): CoachingHint? = CoachingHint("FAKE HINT")
    }
}
