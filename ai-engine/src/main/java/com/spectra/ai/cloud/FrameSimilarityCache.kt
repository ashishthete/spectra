package com.spectra.ai.cloud

import com.spectra.ai.model.CoachingHint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameSimilarityCache(
    private val expiryMs: Long = 10_000L
) {
    @Inject constructor() : this(10_000L)

    private var cachedThumbnail: IntArray? = null
    private var cachedHint: CoachingHint? = null
    private var cachedAtMs: Long = 0L
    private val similarityThreshold = 0.70f

    fun getCached(thumbnail: IntArray): CoachingHint? {
        val stored = cachedThumbnail ?: return null
        val hint = cachedHint ?: return null

        if (System.currentTimeMillis() - cachedAtMs >= expiryMs) {
            clear()
            return null
        }

        val similarity = computeSimilarity(stored, thumbnail)
        return if (similarity >= similarityThreshold) hint else null
    }

    fun store(thumbnail: IntArray, hint: CoachingHint) {
        cachedThumbnail = thumbnail.copyOf()
        cachedHint = hint
        cachedAtMs = System.currentTimeMillis()
    }

    fun clear() {
        cachedThumbnail = null
        cachedHint = null
        cachedAtMs = 0L
    }

    private fun computeSimilarity(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var matchCount = 0
        for (i in a.indices) {
            val ar = (a[i] shr 16) and 0xFF
            val ag = (a[i] shr 8) and 0xFF
            val ab = a[i] and 0xFF
            val br = (b[i] shr 16) and 0xFF
            val bg = (b[i] shr 8) and 0xFF
            val bb = b[i] and 0xFF
            val diff = kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
            if (diff < 30) matchCount++
        }
        return matchCount.toFloat() / a.size
    }
}
