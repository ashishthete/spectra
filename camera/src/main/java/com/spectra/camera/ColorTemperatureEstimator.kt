package com.spectra.camera

object ColorTemperatureEstimator {

    private val breakpoints = floatArrayOf(0.55f, 0.70f, 0.95f, 1.20f, 1.50f, 1.80f, 2.20f)
    private val kelvinValues = intArrayOf(8500, 7500, 6500, 5500, 4000, 3200, 2800, 2400)

    fun fromGainRatio(rGain: Float, bGain: Float): Int {
        val ratio = rGain / bGain.coerceAtLeast(0.01f)
        for (i in breakpoints.indices) {
            if (ratio < breakpoints[i]) return kelvinValues[i]
            if (i < breakpoints.size - 1 && ratio < breakpoints[i + 1]) {
                val t = (ratio - breakpoints[i]) / (breakpoints[i + 1] - breakpoints[i])
                return (kelvinValues[i] + t * (kelvinValues[i + 1] - kelvinValues[i])).toInt()
            }
        }
        return kelvinValues.last()
    }
}
