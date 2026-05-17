package com.spectra.ai

import com.spectra.ai.model.DistanceRange
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DistanceEstimator @Inject constructor() {

    fun estimateFromFocusDistance(focusDistanceDiopters: Float): DistanceRange {
        if (focusDistanceDiopters <= 0f) return DistanceRange.INFINITY

        val distanceMeters = 1f / focusDistanceDiopters

        return when {
            distanceMeters < 0.1f -> DistanceRange.MACRO
            distanceMeters < 1f -> DistanceRange.NEAR
            distanceMeters < 5f -> DistanceRange.MID
            distanceMeters < 20f -> DistanceRange.FAR
            else -> DistanceRange.INFINITY
        }
    }
}
