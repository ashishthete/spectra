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
                kelvinToRggbCalibrated(camera, settings.whiteBalanceKelvin)
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
        aeCompensationRange: Range<Int> = Range(-20, 20),
        highlightProtection: Float = 0f
    ) = synchronized(lock) {
        val motionEvBias = when {
            motionLevel >= 4 && settings.shutterSpeedDenominator >= 500 -> -1.5f
            motionLevel >= 3 && settings.shutterSpeedDenominator >= 250 -> -1.0f
            motionLevel >= 2 && settings.shutterSpeedDenominator >= 125 -> -0.5f
            else -> 0f
        }
        val actualHighlightProtection = if (highlightProtection != 0f) highlightProtection else -0.3f
        val totalEv = (settings.exposureCompensation + motionEvBias + actualHighlightProtection).coerceIn(-2f, 0.3f)
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

        // Query smooth target FPS range that respects motion rules
        val smoothFps = getSmoothFpsRange(camera, motionLevel)
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            smoothFps
        )

        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        Log.d("SettingsApplier", "Auto-hints: EV=$evSteps(preset=${settings.exposureCompensation},motion=$motionEvBias,protect=$actualHighlightProtection), FPS=$smoothFps, WB=auto")

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
    fun applyBracketEv(
        camera: Camera,
        evOffset: Float,
        aeCompensationStep: Rational = Rational(1, 10),
        aeCompensationRange: Range<Int> = Range(-20, 20)
    ) = synchronized(lock) {
        val stepsPerEv = if (aeCompensationStep.toFloat() > 0f) (1.0f / aeCompensationStep.toFloat()).toInt() else 10
        val evSteps = (evOffset * stepsPerEv).toInt().coerceIn(aeCompensationRange.lower, aeCompensationRange.upper)
        try {
            camera.cameraControl.setExposureCompensationIndex(evSteps)
        } catch (_: Exception) { }

        val camera2Control = Camera2CameraControl.from(camera.cameraControl)
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "Bracket EV: offset=$evOffset, steps=$evSteps, AWB locked")
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun unlockAwb(camera: Camera) = synchronized(lock) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
        camera2Control.captureRequestOptions = builder.build()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyAutoWithConstraints(
        camera: Camera,
        settings: CameraSettings,
        constraints: com.spectra.core.model.CameraConstraints,
        motionLevel: Int = 0,
        aeCompensationStep: Rational = Rational(1, 10),
        aeCompensationRange: Range<Int> = Range(-20, 20),
        highlightProtection: Float = 0f
    ) = synchronized(lock) {
        val actualHighlightProtection = if (highlightProtection != 0f) highlightProtection else -0.3f
        val totalEv = (constraints.evTargetOffset + actualHighlightProtection).coerceIn(-2f, 0.3f)
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

        // Dynamic focal-length-aware dynamic minimum shutter speed floor calculation
        val focalLengthMm = try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val focalLengths = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            focalLengths?.firstOrNull() ?: 23f
        } catch (e: Exception) {
            23f
        }

        val cropFactor = try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val sensorSize = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (sensorSize != null) {
                val diag = kotlin.math.sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble())
                if (diag > 0) (43.27 / diag).toFloat() else 5.5f
            } else 5.5f
        } catch (e: Exception) {
            5.5f
        }

        val equivalentFocalLength = focalLengthMm * cropFactor
        val baseShutterFloor = equivalentFocalLength.coerceIn(10f, 300f)
        val motionScale = when (motionLevel) {
            4 -> 4.0f
            3 -> 2.5f
            2 -> 1.5f
            else -> 1.0f
        }
        val computedMinShutterDenominator = (baseShutterFloor * motionScale).coerceIn(30f, 1000f)

        // Enforce the computed minShutterSpeedDenominator by mapping it to AE target FPS range
        val targetMinFps = computedMinShutterDenominator.toInt().coerceIn(15, 60)

        val smoothFps = try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val ranges = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges != null && ranges.isNotEmpty()) {
                var bestRange = ranges[0]
                for (range in ranges) {
                    if (range.upper >= targetMinFps && (bestRange.upper < targetMinFps || range.lower > bestRange.lower)) {
                        bestRange = range
                    }
                }
                bestRange
            } else {
                Range(targetMinFps, 30.coerceAtLeast(targetMinFps))
            }
        } catch (e: Exception) {
            Range(targetMinFps, 30.coerceAtLeast(targetMinFps))
        }

        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, smoothFps)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "ConstrainedAuto: EV=$evSteps, FPS=$smoothFps, WB=auto")
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

        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        builder.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        builder.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        )

        camera2Control.captureRequestOptions = builder.build()
        Log.d("SettingsApplier", "SemiAuto: ISO=$clampedIso, exposure=${clampedExposureNs}ns, WB=auto")
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

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun kelvinToRggbCalibrated(camera: Camera, kelvin: Int): RggbChannelVector {
        val fallback = kelvinToRggb(kelvin)
        return try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val ct1 = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            val ct2 = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            if (ct1 == null) return fallback

            val t = 1000f / kelvin
            val xc = if (kelvin <= 4000) {
                -0.2661239f * t * t * t - 0.2343580f * t * t + 0.8776956f * t + 0.179910f
            } else {
                -3.0258469f * t * t * t + 2.1070379f * t * t + 0.2226347f * t + 0.240390f
            }
            val yc = if (kelvin <= 6000) {
                -3.000f * xc * xc + 2.870f * xc - 0.275f
            } else {
                -1.4185f * xc * xc * xc - 1.359f * xc * xc + 1.185f * xc - 0.202f
            }

            val z = 1.0f - xc - yc
            val xVal = xc / yc
            val yVal = 1.0f
            val zVal = z / yc

            val ill1 = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 1.toByte()
            val ill2 = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 21.toByte()
            val k1 = if (ill1.toInt() == 1) 2856f else 6504f
            val k2 = if (ill2.toInt() == 21) 6504f else 2856f
            
            val alpha = ((kelvin - k1) / (k2 - k1)).coerceIn(0f, 1f)
            
            val rgb = FloatArray(3)
            for (row in 0..2) {
                var sum = 0f
                for (col in 0..2) {
                    val val1 = ct1.getElement(col, row).toFloat()
                    val val2 = ct2?.getElement(col, row)?.toFloat() ?: val1
                    val m = val1 * (1f - alpha) + val2 * alpha
                    val xyz = if (col == 0) xVal else if (col == 1) yVal else zVal
                    sum += m * xyz
                }
                rgb[row] = sum
            }

            val rSensor = rgb[0].coerceAtLeast(0.001f)
            val gSensor = rgb[1].coerceAtLeast(0.001f)
            val bSensor = rgb[2].coerceAtLeast(0.001f)

            val rGain = (gSensor / rSensor).coerceIn(0.5f, 4.0f)
            val bGain = (gSensor / bSensor).coerceIn(0.5f, 4.0f)

            RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
        } catch (e: Exception) {
            fallback
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun getSmoothFpsRange(camera: Camera, motionLevel: Int): Range<Int> {
        return try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val ranges = c2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges != null && ranges.isNotEmpty()) {
                val minAllowedFps = if (motionLevel >= 3) 30 else 15
                var bestRange = ranges[0]
                for (range in ranges) {
                    if (range.upper > bestRange.upper) {
                        bestRange = range
                    } else if (range.upper == bestRange.upper) {
                        if (bestRange.lower < minAllowedFps && range.lower >= minAllowedFps) {
                            bestRange = range
                        }
                    }
                }
                bestRange
            } else {
                Range(30, 30)
            }
        } catch (e: Exception) {
            Range(30, 30)
        }
    }
}
