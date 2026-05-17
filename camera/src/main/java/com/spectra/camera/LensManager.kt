package com.spectra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.spectra.core.model.LensId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LensManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lensMap = mutableMapOf<LensId, String>()

    fun initialize() {
        val cameraIds = cameraManager.cameraIdList
        for (id in cameraIds) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue
            val primaryFocal = focalLengths.firstOrNull() ?: continue

            val lensId = matchFocalToLens(primaryFocal)
            if (lensId != null && !lensMap.containsKey(lensId)) {
                lensMap[lensId] = id
            }
        }
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

    fun getNextLens(current: LensId): LensId {
        val available = getAvailableLenses()
        if (available.isEmpty()) return current
        val currentIndex = available.indexOf(current)
        return available[(currentIndex + 1) % available.size]
    }
}
