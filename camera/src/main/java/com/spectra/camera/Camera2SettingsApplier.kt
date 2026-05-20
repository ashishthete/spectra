package com.spectra.camera

import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Rational
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import com.spectra.core.model.CameraSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Camera2SettingsApplier @Inject constructor() {

    private val lock = Any()

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyManual(camera: Camera, settings: CameraSettings) = synchronized(lock) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                settings.iso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                shutterDenominatorToNanos(settings.shutterSpeedDenominator)
            )

        if (settings.whiteBalanceKelvin != 5500) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggb(settings.whiteBalanceKelvin)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        if (settings.focusDistance > 0f) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                1f / settings.focusDistance
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        builder.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        )

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "Manual: ISO=${settings.iso}, shutter=1/${settings.shutterSpeedDenominator}, WB=${settings.whiteBalanceKelvin}K, focus=${settings.focusDistance}")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAutoWithHints(
        camera: Camera,
        settings: CameraSettings,
        motionLevel: Int = 0,
        currentIso: Int = 0,
        aeCompensationStep: Rational = Rational(1, 10),
        aeCompensationRange: Range<Int> = Range(-20, 20)
    ) = synchronized(lock) {
        val motionEvBias = when {
            motionLevel >= 4 && settings.shutterSpeedDenominator >= 500 -> -1.5f
            motionLevel >= 3 && settings.shutterSpeedDenominator >= 250 -> -1.0f
            motionLevel >= 2 && settings.shutterSpeedDenominator >= 125 -> -0.5f
            else -> 0f
        }
        val totalEv = settings.exposureCompensation + motionEvBias
        val stepsPerEv = if (aeCompensationStep.toFloat() > 0f) (1.0f / aeCompensationStep.toFloat()).toInt() else 10
        val evSteps = (totalEv * stepsPerEv).toInt().coerceIn(aeCompensationRange.lower, aeCompensationRange.upper)
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
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

        if (motionLevel >= 3) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(24, 30)
            )
        }

        val wbDrift = kotlin.math.abs(settings.whiteBalanceKelvin - 5500)
        if (wbDrift > 500) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggb(settings.whiteBalanceKelvin)
            )
            Log.d("SettingsApplier", "Auto-hints: EV=$evSteps(motion=$motionEvBias), WB=${settings.whiteBalanceKelvin}K(override)")
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            Log.d("SettingsApplier", "Auto-hints: EV=$evSteps(motion=$motionEvBias), WB=auto")
        }

        builder.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        )

        camera2Control.captureRequestOptions = builder.build()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAutoWithConstraints(
        camera: Camera,
        settings: CameraSettings,
        constraints: com.spectra.core.model.CameraConstraints,
        motionLevel: Int = 0,
        aeCompensationStep: Rational = Rational(1, 10),
        aeCompensationRange: Range<Int> = Range(-20, 20)
    ) = synchronized(lock) {
        val totalEv = constraints.evTargetOffset
        val stepsPerEv = if (aeCompensationStep.toFloat() > 0f) (1.0f / aeCompensationStep.toFloat()).toInt() else 10
        val evSteps = (totalEv * stepsPerEv).toInt().coerceIn(aeCompensationRange.lower, aeCompensationRange.upper)
        try {
            camera.cameraControl.setExposureCompensationIndex(evSteps)
        } catch (_: Exception) { }

        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        // Pass boundaries to AE via TARGET_FPS_RANGE to limit exposure times indirectly
        val fpsMax = if (constraints.minShutterSpeedDenominator > 0) {
            constraints.minShutterSpeedDenominator.coerceIn(15, 60)
        } else 30
        val fpsMin = (fpsMax / 2).coerceAtLeast(15)
        
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fpsMin, fpsMax))

        // We can't strictly cap ISO in pure Camera2 CONTROL_AE_MODE_ON without vendor tags, 
        // but we apply EV offset and FPS constraints. 

        val wbDrift = kotlin.math.abs(settings.whiteBalanceKelvin - 5500)
        if (wbDrift > 500) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(settings.whiteBalanceKelvin))
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "ConstrainedAuto: EV=$evSteps, FPS=($fpsMin-$fpsMax), maxISO=${constraints.maxIso}")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAuto(camera: Camera) = synchronized(lock) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        try {
            camera.cameraControl.setExposureCompensationIndex(0)
        } catch (_: Exception) { }

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            )
            .build()

        camera2Control.captureRequestOptions = options
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applySemiAuto(
        camera: Camera,
        settings: CameraSettings,
        isoRange: android.util.Range<Int> = Range(50, 3200),
        exposureRange: android.util.Range<Long> = Range(1_000_000L, 1_000_000_000L)
    ) = synchronized(lock) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val clampedIso = clampIso(settings.iso, isoRange.lower, isoRange.upper)
        val targetExposureNs = shutterDenominatorToNanos(settings.shutterSpeedDenominator)
        val clampedExposureNs = clampExposureNs(targetExposureNs, exposureRange.lower, exposureRange.upper)

        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                clampedIso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                clampedExposureNs
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

        val wbDrift = kotlin.math.abs(settings.whiteBalanceKelvin - 5500)
        if (wbDrift > 300) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggb(settings.whiteBalanceKelvin)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        builder.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        )

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "SemiAuto: ISO=$clampedIso, exposure=${clampedExposureNs}ns, WB=${settings.whiteBalanceKelvin}K")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyFaceMetering(camera: Camera, faceRects: List<RectF>, sensorArrayWidth: Int = 4000, sensorArrayHeight: Int = 3000) = synchronized(lock) {
        if (faceRects.isEmpty()) return@synchronized
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val meteringRegions = faceRects.take(3).map { face ->
            val left = (face.left * sensorArrayWidth).toInt().coerceIn(0, sensorArrayWidth - 1)
            val top = (face.top * sensorArrayHeight).toInt().coerceIn(0, sensorArrayHeight - 1)
            val right = (face.right * sensorArrayWidth).toInt().coerceIn(left + 1, sensorArrayWidth)
            val bottom = (face.bottom * sensorArrayHeight).toInt().coerceIn(top + 1, sensorArrayHeight)
            MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
        }.toTypedArray()

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, meteringRegions)
            .build()
        camera2Control.addCaptureRequestOptions(options)
        Log.d("SettingsApplier", "Face metering: ${meteringRegions.size} regions")
    }

    private fun clampIso(iso: Int, min: Int, max: Int): Int = iso.coerceIn(min, max)

    private fun clampExposureNs(ns: Long, min: Long, max: Long): Long = ns.coerceIn(min, max)

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
