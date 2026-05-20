package com.spectra.core.model

data class CameraConstraints(
    val minShutterSpeedDenominator: Int = 0,
    val maxShutterSpeedDenominator: Int = 32000,
    val minIso: Int = 50,
    val maxIso: Int = 3200,
    val evTargetOffset: Float = 0f
)
