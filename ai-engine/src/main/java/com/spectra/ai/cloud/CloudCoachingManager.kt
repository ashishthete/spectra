package com.spectra.ai.cloud

import android.graphics.Bitmap
import com.spectra.ai.model.CoachingHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudCoachingManager @Inject constructor(
    private val client: CloudCoachingClientInterface,
    private val cache: FrameSimilarityCache
) {
    private val pauseThresholdMs = 2000L
    private var stableStartMs: Long = 0L
    private var lastThumbnail: IntArray? = null
    private var lastSceneLabel: String = ""
    private var lastLightingLabel: String = ""
    private var queryInProgress = false

    private val _cloudHint = MutableStateFlow<CoachingHint?>(null)
    val cloudHint: StateFlow<CoachingHint?> = _cloudHint.asStateFlow()

    fun onFrameAnalyzed(thumbnailPixels: IntArray, sceneLabel: String, lightingLabel: String) {
        val prev = lastThumbnail
        lastThumbnail = thumbnailPixels
        lastSceneLabel = sceneLabel
        lastLightingLabel = lightingLabel

        if (prev == null) {
            stableStartMs = System.currentTimeMillis()
            return
        }

        val similarity = computeQuickSimilarity(prev, thumbnailPixels)
        if (similarity < 0.85f) {
            stableStartMs = System.currentTimeMillis()
            _cloudHint.value = null
        }

        val cached = cache.getCached(thumbnailPixels)
        if (cached != null) {
            _cloudHint.value = cached
        }
    }

    fun shouldQuery(): Boolean {
        val thumb = lastThumbnail ?: return false
        if (queryInProgress) return false
        if (System.currentTimeMillis() - stableStartMs < pauseThresholdMs) return false
        if (cache.getCached(thumb) != null) return false
        return true
    }

    suspend fun queryCloud(bitmap: Bitmap) {
        if (!shouldQuery()) return
        queryInProgress = true
        try {
            val hint = client.analyzeFrame(bitmap, lastSceneLabel, lastLightingLabel)
            if (hint != null) {
                lastThumbnail?.let { cache.store(it, hint) }
                _cloudHint.value = hint
            }
        } finally {
            queryInProgress = false
        }
    }

    fun getCachedHint(): CoachingHint? {
        val thumb = lastThumbnail ?: return null
        return cache.getCached(thumb)
    }

    fun setStableStartMs(ms: Long) {
        stableStartMs = ms
    }

    fun reset() {
        stableStartMs = 0L
        lastThumbnail = null
        lastSceneLabel = ""
        lastLightingLabel = ""
        queryInProgress = false
        cache.clear()
        _cloudHint.value = null
    }

    private fun computeQuickSimilarity(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val step = maxOf(1, a.size / 256)
        var match = 0
        var total = 0
        for (i in a.indices step step) {
            val ar = (a[i] shr 16) and 0xFF
            val br = (b[i] shr 16) and 0xFF
            if (kotlin.math.abs(ar - br) < 20) match++
            total++
        }
        return match.toFloat() / total
    }
}
