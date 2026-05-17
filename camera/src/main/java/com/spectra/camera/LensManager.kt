package com.spectra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CameraSpecs(
    val megapixels: Int,
    val aperture: Float
)

@Singleton
class LensManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lensMap = mutableMapOf<LensId, String>()
    private var frontCameraId: String? = null
    private val specsMap = mutableMapOf<String, CameraSpecs>()

    fun initialize() {
        val cameraIds = cameraManager.cameraIdList
        for (id in cameraIds) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            val specs = readSpecs(chars)
            specsMap[id] = specs

            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue
            val primaryFocal = focalLengths.firstOrNull() ?: continue

            val lensId = matchFocalToLens(primaryFocal)
            if (lensId != null && !lensMap.containsKey(lensId)) {
                lensMap[lensId] = id
            }
        }

        for (id in cameraIds) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCameraId = id
                break
            }
        }
    }

    private fun readSpecs(chars: CameraCharacteristics): CameraSpecs {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
        val maxSize = sizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
        val mp = ((maxSize.width.toLong() * maxSize.height) / 1_000_000).toInt()

        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val aperture = apertures?.firstOrNull() ?: 2.0f

        return CameraSpecs(megapixels = mp, aperture = aperture)
    }

    private fun matchFocalToLens(focalLength: Float): LensId? = when {
        focalLength < 3f -> LensId.ULTRAWIDE
        focalLength in 3f..7f -> LensId.MAIN
        focalLength in 7f..12f -> LensId.TELEPHOTO_3X
        focalLength > 12f -> LensId.TELEPHOTO_5X
        else -> null
    }

    fun getCameraId(lens: LensId): String? = lensMap[lens]

    fun getAvailableLenses(): List<LensId> = lensMap.keys.sortedBy { it.zoomFactor }

    fun getFrontCameraId(): String? = frontCameraId
    fun hasFrontCamera(): Boolean = frontCameraId != null

    fun getSpecs(cameraId: String?): CameraSpecs {
        return specsMap[cameraId] ?: CameraSpecs(12, 2.0f)
    }

    fun getFrontCameraSpecs(): CameraSpecs {
        return specsMap[frontCameraId] ?: CameraSpecs(12, 2.2f)
    }

    fun getBackCameraSpecs(lens: LensId): CameraSpecs {
        val id = lensMap[lens] ?: return CameraSpecs(lens.megapixels, lens.maxAperture)
        return specsMap[id] ?: CameraSpecs(lens.megapixels, lens.maxAperture)
    }

    fun getNextLens(current: LensId): LensId {
        val available = getAvailableLenses()
        if (available.isEmpty()) return current
        val currentIndex = available.indexOf(current)
        return available[(currentIndex + 1) % available.size]
    }
}
