package com.spectra.ai

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.spectra.ai.model.DetectedFace
import com.spectra.ai.model.FaceData
import com.spectra.ai.model.PointF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceDetectorWrapper @Inject constructor() {

    private val _faceData = MutableStateFlow(FaceData.EMPTY)
    val faceData: StateFlow<FaceData> = _faceData.asStateFlow()

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    private var processing = false

    suspend fun detectFaces(bitmap: Bitmap, imageWidth: Int, imageHeight: Int) {
        if (processing) return
        processing = true

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val mlFaces = detector.process(inputImage).await()

            val faces = mlFaces.map { face ->
                val b = face.boundingBox
                DetectedFace(
                    bounds = RectF(
                        b.left.toFloat() / imageWidth,
                        b.top.toFloat() / imageHeight,
                        b.right.toFloat() / imageWidth,
                        b.bottom.toFloat() / imageHeight
                    ),
                    leftEyePosition = face.getLandmarkPosition(FaceLandmark.LEFT_EYE, imageWidth, imageHeight),
                    rightEyePosition = face.getLandmarkPosition(FaceLandmark.RIGHT_EYE, imageWidth, imageHeight),
                    nosePosition = face.getLandmarkPosition(FaceLandmark.NOSE_BASE, imageWidth, imageHeight),
                    smilingProbability = face.smilingProbability ?: -1f,
                    leftEyeOpenProbability = face.leftEyeOpenProbability ?: -1f,
                    rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
                    headEulerAngleX = face.headEulerAngleX,
                    headEulerAngleY = face.headEulerAngleY,
                    headEulerAngleZ = face.headEulerAngleZ,
                    trackingId = face.trackingId
                )
            }

            _faceData.value = FaceData(faceCount = faces.size, faces = faces)
        } catch (_: Exception) {
            _faceData.value = FaceData.EMPTY
        } finally {
            processing = false
        }
    }

    private fun com.google.mlkit.vision.face.Face.getLandmarkPosition(
        type: Int,
        imageWidth: Int,
        imageHeight: Int
    ): PointF? {
        val landmark = getLandmark(type) ?: return null
        return PointF(
            landmark.position.x / imageWidth,
            landmark.position.y / imageHeight
        )
    }

    fun release() {
        _faceData.value = FaceData.EMPTY
        detector.close()
    }
}
