package com.spectra.camera

import android.hardware.camera2.CaptureRequest
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
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                if (settings.focusDistance > 0f) 1f / settings.focusDistance else 0f
            )
            .build()

        camera2Control.captureRequestOptions = options
        Log.d("SettingsApplier", "Manual: ISO=${settings.iso}, shutter=1/${settings.shutterSpeedDenominator}")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAutoWithHints(camera: Camera, settings: CameraSettings) {
        val evSteps = (settings.exposureCompensation * 6).toInt().coerceIn(-12, 12)
        try {
            camera.cameraControl.setExposureCompensationIndex(evSteps)
        } catch (_: Exception) { }

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
        Log.d("SettingsApplier", "Auto-hints: EV=$evSteps")
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
}
