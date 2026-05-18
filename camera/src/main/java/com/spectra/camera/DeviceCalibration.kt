package com.spectra.camera

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

object DeviceCalibration {
    private val TAG = "DeviceCalibration"
    private var profile: CalibrationProfile? = null

    data class CalibrationProfile(
        val deviceModel: String,
        val exposureCompensation: Float = 0f,    // EV offset (e.g., +0.3 if device underexposes)
        val colorTemperatureOffset: Int = 0,      // Kelvin offset for WB
        val redGain: Float = 1f,                  // RGB channel gain corrections
        val greenGain: Float = 1f,
        val blueGain: Float = 1f,
        val sharpnessMultiplier: Float = 1f,      // Lens-specific sharpening adjustment
        val noiseFloorOffset: Float = 0f,         // ISO-based noise floor correction
        val vignetteStrength: Float = 1f,         // Lens vignette correction strength
        val wideDistortionK1: Float = 0f,         // Barrel distortion coefficient for ultrawide
        val maxUsableIso: Int = 3200              // Device-specific ISO ceiling
    )

    fun load(assetManager: AssetManager) {
        val deviceModel = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_").lowercase()
        val filename = "calibration/$deviceModel.json"
        try {
            val json = assetManager.open(filename).bufferedReader().use { it.readText() }
            profile = parseProfile(json, deviceModel)
            Log.d(TAG, "Loaded calibration for $deviceModel")
        } catch (e: Exception) {
            // Try generic manufacturer profile
            val mfgFile = "calibration/${Build.MANUFACTURER.lowercase()}_default.json"
            try {
                val json = assetManager.open(mfgFile).bufferedReader().use { it.readText() }
                profile = parseProfile(json, deviceModel)
                Log.d(TAG, "Loaded manufacturer-default calibration for ${Build.MANUFACTURER}")
            } catch (e2: Exception) {
                profile = CalibrationProfile(deviceModel = deviceModel)
                Log.d(TAG, "No calibration profile found, using defaults for $deviceModel")
            }
        }
    }

    internal fun parseProfile(json: String, deviceModel: String): CalibrationProfile {
        val obj = JSONObject(json)
        return CalibrationProfile(
            deviceModel = deviceModel,
            exposureCompensation = obj.optDouble("exposureCompensation", 0.0).toFloat(),
            colorTemperatureOffset = obj.optInt("colorTemperatureOffset", 0),
            redGain = obj.optDouble("redGain", 1.0).toFloat(),
            greenGain = obj.optDouble("greenGain", 1.0).toFloat(),
            blueGain = obj.optDouble("blueGain", 1.0).toFloat(),
            sharpnessMultiplier = obj.optDouble("sharpnessMultiplier", 1.0).toFloat(),
            noiseFloorOffset = obj.optDouble("noiseFloorOffset", 0.0).toFloat(),
            vignetteStrength = obj.optDouble("vignetteStrength", 1.0).toFloat(),
            wideDistortionK1 = obj.optDouble("wideDistortionK1", 0.0).toFloat(),
            maxUsableIso = obj.optInt("maxUsableIso", 3200)
        )
    }

    fun getProfile(): CalibrationProfile = profile ?: CalibrationProfile(
        deviceModel = "${Build.MANUFACTURER}_${Build.MODEL}"
    )

    fun applyColorCorrection(pixels: IntArray) {
        val p = profile ?: return
        if (p.redGain == 1f && p.greenGain == 1f && p.blueGain == 1f) return
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16 and 0xFF) * p.redGain).toInt().coerceIn(0, 255)
            val g = ((pixels[i] shr 8 and 0xFF) * p.greenGain).toInt().coerceIn(0, 255)
            val b = ((pixels[i] and 0xFF) * p.blueGain).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
