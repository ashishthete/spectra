package com.spectra.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size

class CameraCapabilityMatrix(private val cameraManager: CameraManager) {

    data class LensCapabilities(
        val cameraId: String,
        val jpegSizes: List<Size>,
        val yuvSizes: List<Size>,
        val rawSizes: List<Size>,
        val maxJpegSize: Size?,
        val supportsRaw: Boolean,
        val supportsManualFocus: Boolean,
        val supportsManualExposure: Boolean,
        val hardwareLevel: Int,
        val maxStreamCombination: StreamCombination
    )

    data class StreamCombination(
        val canPreviewPlusStill: Boolean = true,
        val canPreviewPlusStillPlusAnalysis: Boolean = true,
        val canPreviewPlusStillPlusRaw: Boolean = false,
        val canPreviewPlusStillPlusAnalysisPlusRaw: Boolean = false
    )

    private val capabilityCache = mutableMapOf<String, LensCapabilities>()

    fun query(cameraId: String): LensCapabilities {
        capabilityCache[cameraId]?.let { return it }

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        val jpegSizes = configMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val yuvSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        val rawSizes = try {
            configMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val supportsManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val supportsManualPostProc = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities

        val streamCombo = inferStreamCombinations(hwLevel, supportsRaw, configMap)

        val result = LensCapabilities(
            cameraId = cameraId,
            jpegSizes = jpegSizes.sortedByDescending { it.width.toLong() * it.height },
            yuvSizes = yuvSizes.sortedByDescending { it.width.toLong() * it.height },
            rawSizes = rawSizes.sortedByDescending { it.width.toLong() * it.height },
            maxJpegSize = jpegSizes.maxByOrNull { it.width.toLong() * it.height },
            supportsRaw = supportsRaw,
            supportsManualFocus = supportsManualSensor,
            supportsManualExposure = supportsManualSensor,
            hardwareLevel = hwLevel,
            maxStreamCombination = streamCombo
        )

        capabilityCache[cameraId] = result
        Log.d(TAG, "Capabilities for $cameraId: JPEG=${jpegSizes.size} sizes, YUV=${yuvSizes.size}, RAW=${rawSizes.size}, hw=$hwLevel, manualFocus=$supportsManualSensor")
        return result
    }

    fun selectCaptureSize(cameraId: String, targetMegapixels: Int): Size {
        val caps = query(cameraId)
        val targetPixels = targetMegapixels.toLong() * 1_000_000
        return caps.jpegSizes.firstOrNull { it.width.toLong() * it.height <= targetPixels * 1.1 }
            ?: caps.maxJpegSize
            ?: Size(4032, 3024)
    }

    fun selectPreviewSize(cameraId: String, targetWidth: Int, targetHeight: Int): Size {
        val caps = query(cameraId)
        val targetRatio = targetWidth.toFloat() / targetHeight
        return caps.yuvSizes
            .filter { it.width <= targetWidth * 2 && it.height <= targetHeight * 2 }
            .minByOrNull {
                val ratio = it.width.toFloat() / it.height
                Math.abs(ratio - targetRatio) + Math.abs(it.width - targetWidth) / 10000f
            }
            ?: Size(targetWidth, targetHeight)
    }

    fun canSupportStreamCombo(cameraId: String, needsRaw: Boolean, needsAnalysis: Boolean): Boolean {
        val caps = query(cameraId)
        return when {
            needsRaw && needsAnalysis -> caps.maxStreamCombination.canPreviewPlusStillPlusAnalysisPlusRaw
            needsRaw -> caps.maxStreamCombination.canPreviewPlusStillPlusRaw
            needsAnalysis -> caps.maxStreamCombination.canPreviewPlusStillPlusAnalysis
            else -> caps.maxStreamCombination.canPreviewPlusStill
        }
    }

    private fun inferStreamCombinations(
        hwLevel: Int,
        supportsRaw: Boolean,
        configMap: StreamConfigurationMap?
    ): StreamCombination {
        val isFull = hwLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        val isLevel3 = hwLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
        return StreamCombination(
            canPreviewPlusStill = true,
            canPreviewPlusStillPlusAnalysis = isFull || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            canPreviewPlusStillPlusRaw = isFull && supportsRaw,
            canPreviewPlusStillPlusAnalysisPlusRaw = isLevel3 && supportsRaw
        )
    }

    companion object {
        private const val TAG = "CameraCapabilityMatrix"
    }
}
