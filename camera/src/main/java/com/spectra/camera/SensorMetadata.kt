package com.spectra.camera

data class SensorMetadata(
    val iso: Int = 0,
    val exposureTimeNs: Long = 0L,
    val focusDistanceDiopters: Float = 0f
) {
    val shutterSpeedDenominator: Int
        get() = if (exposureTimeNs > 0) {
            (1_000_000_000L / exposureTimeNs).toInt().coerceIn(1, 32000)
        } else 0

    val formattedIso: String get() = if (iso > 0) "ISO $iso" else "Auto"
    val formattedShutter: String
        get() = if (exposureTimeNs > 0) {
            val denom = shutterSpeedDenominator
            if (denom <= 1) "1s" else "1/${denom}s"
        } else "Auto"
}
