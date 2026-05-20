package com.spectra.core.model

data class CameraSettings(
    val iso: Int = 100,
    val shutterSpeedDenominator: Int = 125,
    val whiteBalanceKelvin: Int = 5500,
    val exposureCompensation: Float = 0f,
    val focusDistance: Float = 0f,
    val captureRaw: Boolean = false
) {
    val formattedShutterSpeed: String
        get() = if (shutterSpeedDenominator <= 1) "1s"
                else "1/${shutterSpeedDenominator}s"

    val formattedIso: String get() = "ISO $iso"
    val formattedWb: String get() = "${whiteBalanceKelvin}K"
    val formattedEv: String
        get() = when {
            exposureCompensation > 0 -> "EV +${"%.1f".format(exposureCompensation)}"
            exposureCompensation < 0 -> "EV ${"%.1f".format(exposureCompensation)}"
            else -> "EV 0"
        }

    companion object {
        fun clamped(
            iso: Int = 100,
            shutterSpeedDenominator: Int = 125,
            whiteBalanceKelvin: Int = 5500,
            exposureCompensation: Float = 0f,
            focusDistance: Float = 0f,
            maxIso: Int = 3200
        ) = CameraSettings(
            iso = iso.coerceIn(50, maxIso),
            shutterSpeedDenominator = shutterSpeedDenominator,
            whiteBalanceKelvin = whiteBalanceKelvin.coerceIn(2300, 10000),
            exposureCompensation = exposureCompensation.coerceIn(-3f, 3f),
            focusDistance = focusDistance
        )
    }
}
