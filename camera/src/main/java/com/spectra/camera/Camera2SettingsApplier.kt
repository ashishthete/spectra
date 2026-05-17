package com.spectra.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import com.spectra.core.model.CameraSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Camera2SettingsApplier @Inject constructor() {

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyManual(camera: Camera, settings: CameraSettings) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                settings.iso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                shutterDenominatorToNanos(settings.shutterSpeedDenominator)
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggb(settings.whiteBalanceKelvin)
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                if (settings.focusDistance > 0f) 1f / settings.focusDistance else 0f
            )
            .build()

        camera2Control.captureRequestOptions = options
        Log.d("SettingsApplier", "Manual: ISO=${settings.iso}, shutter=1/${settings.shutterSpeedDenominator}, WB=${settings.whiteBalanceKelvin}K")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAutoWithHints(camera: Camera, settings: CameraSettings) {
        val evSteps = (settings.exposureCompensation * 6).toInt().coerceIn(-12, 12)
        try {
            camera.cameraControl.setExposureCompensationIndex(evSteps)
        } catch (_: Exception) { }

        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "Auto-hints: EV=$evSteps, WB=${settings.whiteBalanceKelvin}K(auto)")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAuto(camera: Camera) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            .build()

        camera2Control.captureRequestOptions = options
    }

    private fun shutterDenominatorToNanos(denominator: Int): Long {
        if (denominator <= 0) return 1_000_000_000L
        return 1_000_000_000L / denominator
    }

    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val temp = kelvin / 100.0
        val red: Double
        val green: Double
        val blue: Double

        if (temp <= 66) {
            red = 255.0
            green = 99.4708025861 * Math.log(temp) - 161.1195681661
            blue = if (temp <= 19) 0.0
                   else 138.5177312231 * Math.log(temp - 10) - 305.0447927307
        } else {
            red = 329.698727446 * Math.pow(temp - 60, -0.1332047592)
            green = 288.1221695283 * Math.pow(temp - 60, -0.0755148492)
            blue = 255.0
        }

        val rNorm = (red.coerceIn(1.0, 255.0) / 255.0).toFloat()
        val gNorm = (green.coerceIn(1.0, 255.0) / 255.0).toFloat()
        val bNorm = (blue.coerceIn(1.0, 255.0) / 255.0).toFloat()

        // Invert: gains compensate the illuminant color, not apply it
        val rGain = (gNorm / rNorm).coerceIn(0.5f, 4.0f)
        val bGain = (gNorm / bNorm).coerceIn(0.5f, 4.0f)

        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }
}
