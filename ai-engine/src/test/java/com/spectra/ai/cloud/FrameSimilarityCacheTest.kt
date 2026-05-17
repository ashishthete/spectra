package com.spectra.ai.cloud

import com.google.common.truth.Truth.assertThat
import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CoachingHint
import org.junit.Test

class FrameSimilarityCacheTest {

    private val cache = FrameSimilarityCache()

    private fun makePixels(brightness: Int, size: Int = 32 * 32): IntArray {
        val r = brightness
        val g = brightness
        val b = brightness
        val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return IntArray(size) { pixel }
    }

    @Test
    fun getCached_emptyCache_returnsNull() {
        val pixels = makePixels(128)
        assertThat(cache.getCached(pixels)).isNull()
    }

    @Test
    fun getCached_identicalFrame_returnsCachedHint() {
        val pixels = makePixels(128)
        val hint = CoachingHint("TEST HINT", ArrowDirection.UP)
        cache.store(pixels, hint)
        assertThat(cache.getCached(pixels)).isEqualTo(hint)
    }

    @Test
    fun getCached_similarFrame_above70Percent_returnsCached() {
        val stored = makePixels(128)
        val hint = CoachingHint("TEST HINT")
        cache.store(stored, hint)

        val query = stored.copyOf()
        val changeCount = (query.size * 0.25).toInt()
        for (i in 0 until changeCount) {
            query[i] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }
        assertThat(cache.getCached(query)).isEqualTo(hint)
    }

    @Test
    fun getCached_dissimilarFrame_below70Percent_returnsNull() {
        val stored = makePixels(0)
        val hint = CoachingHint("TEST HINT")
        cache.store(stored, hint)

        val query = stored.copyOf()
        val changeCount = (query.size * 0.5).toInt()
        for (i in 0 until changeCount) {
            query[i] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }
        assertThat(cache.getCached(query)).isNull()
    }

    @Test
    fun getCached_expiredEntry_returnsNull() {
        val cache = FrameSimilarityCache(expiryMs = 0L)
        val pixels = makePixels(128)
        val hint = CoachingHint("TEST HINT")
        cache.store(pixels, hint)
        assertThat(cache.getCached(pixels)).isNull()
    }

    @Test
    fun clear_removesCachedEntry() {
        val pixels = makePixels(128)
        cache.store(pixels, CoachingHint("TEST"))
        cache.clear()
        assertThat(cache.getCached(pixels)).isNull()
    }
}
