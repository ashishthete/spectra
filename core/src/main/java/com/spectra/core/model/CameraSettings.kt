package com.spectra.core.model

data class CameraSettings(
    iso: Int = 100,
    val shutterSpeedDenominator: Int = 125,
    whiteBalanceKelvin: Int = 5500,
    exposureCompensation: Float = 0f,
    val focusDistance: Float = 0f
) {
    val iso: Int = iso.coerceIn(50, 3200)
    val whiteBalanceKelvin: Int = whiteBalanceKelvin.coerceIn(2300, 10000)
    val exposureCompensation: Float = exposureCompensation.coerceIn(-3f, 3f)

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
}
