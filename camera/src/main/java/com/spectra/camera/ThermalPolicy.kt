package com.spectra.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThermalPolicy @Inject constructor(@ApplicationContext context: Context) {

    enum class QualityLevel { FULL, REDUCED, MINIMAL }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var lastThermalStatus = 0

    val qualityLevel: QualityLevel
        get() {
            val status = currentThermalStatus()
            if (status != lastThermalStatus) {
                Log.d("ThermalPolicy", "Thermal status changed: $lastThermalStatus -> $status, quality=${qualityForStatus(status)}")
                lastThermalStatus = status
            }
            return qualityForStatus(status)
        }

    val maxHdrFrames: Int
        get() = when (qualityLevel) {
            QualityLevel.FULL -> 5
            QualityLevel.REDUCED -> 3
            QualityLevel.MINIMAL -> 1
        }

    val maxBokehRadius: Int
        get() = when (qualityLevel) {
            QualityLevel.FULL -> 24
            QualityLevel.REDUCED -> 12
            QualityLevel.MINIMAL -> 6
        }

    val enableExpensiveDenoise: Boolean
        get() = qualityLevel != QualityLevel.MINIMAL

    val processingBackend: String
        get() = when (qualityLevel) {
            QualityLevel.FULL -> "CPU+AGSL+GLES"
            QualityLevel.REDUCED -> "CPU+AGSL"
            QualityLevel.MINIMAL -> "CPU"
        }

    val statusDescription: String
        get() = when (qualityLevel) {
            QualityLevel.FULL -> "Full quality"
            QualityLevel.REDUCED -> "Reduced (thermal)"
            QualityLevel.MINIMAL -> "Minimal (thermal critical)"
        }

    private fun currentThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return try {
            powerManager?.currentThermalStatus ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun qualityForStatus(status: Int): QualityLevel = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> QualityLevel.MINIMAL
        status >= PowerManager.THERMAL_STATUS_SEVERE -> QualityLevel.MINIMAL
        status >= PowerManager.THERMAL_STATUS_MODERATE -> QualityLevel.REDUCED
        else -> QualityLevel.FULL
    }
}
