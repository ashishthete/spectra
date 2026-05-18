package com.spectra.ai

import com.spectra.core.model.SceneType

/**
 * Temporal scene smoothing to prevent HUD flicker.
 * Maintains a ring buffer of the last [windowSize] classifications
 * and only switches the reported scene when at least [threshold]
 * of the buffered classifications agree on a new scene type.
 */
class SceneHysteresis(
    private val windowSize: Int = 5,
    private val threshold: Int = 3
) {
    private val recentScenes = ArrayDeque<SceneType>(windowSize)
    private var stableScene: SceneType = SceneType.UNKNOWN

    fun smooth(rawScene: SceneType): SceneType {
        recentScenes.addLast(rawScene)
        if (recentScenes.size > windowSize) recentScenes.removeFirst()

        // Count occurrences of each scene in the buffer
        val counts = recentScenes.groupingBy { it }.eachCount()
        val majority = counts.maxByOrNull { it.value }

        // Require at least [threshold] of [windowSize] to switch
        if (majority != null && majority.value >= threshold && majority.key != stableScene) {
            stableScene = majority.key
        }

        return stableScene
    }

    /** Reset state (e.g., on camera switch). */
    fun reset() {
        recentScenes.clear()
        stableScene = SceneType.UNKNOWN
    }
}
