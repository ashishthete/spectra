package com.spectra.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class PalmGestureDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var handLandmarker: HandLandmarker? = null
    private val processing = AtomicBoolean(false)

    private val _palmDetected = MutableStateFlow(false)
    val palmDetected: StateFlow<Boolean> = _palmDetected.asStateFlow()

    private var consecutivePalmFrames = 0
    private val requiredFrames = 3

    fun initialize() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "Hand landmarker initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Hand landmarker init failed", e)
            handLandmarker = null
        }
    }

    fun detect(bitmap: Bitmap) {
        if (handLandmarker == null) return
        if (!processing.compareAndSet(false, true)) return

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker!!.detect(mpImage)

            val isOpenPalm = if (result.landmarks().isNotEmpty()) {
                isOpenPalm(result.landmarks()[0])
            } else false

            if (isOpenPalm) {
                consecutivePalmFrames++
                if (consecutivePalmFrames >= requiredFrames) {
                    _palmDetected.value = true
                }
            } else {
                consecutivePalmFrames = 0
                _palmDetected.value = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hand detection failed", e)
        } finally {
            processing.set(false)
        }
    }

    private fun isOpenPalm(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false

        val wrist = landmarks[0]
        val middleBase = landmarks[9]
        val palmSize = dist(wrist.x(), wrist.y(), middleBase.x(), middleBase.y())
        if (palmSize < 0.05f) return false

        val tips = intArrayOf(4, 8, 12, 16, 20)
        val bases = intArrayOf(2, 5, 9, 13, 17)
        val thresholds = floatArrayOf(1.1f, 1.2f, 1.2f, 1.2f, 1.2f)

        var extendedCount = 0
        for (i in tips.indices) {
            val tip = landmarks[tips[i]]
            val base = landmarks[bases[i]]
            val tipDist = dist(tip.x(), tip.y(), wrist.x(), wrist.y())
            val baseDist = dist(base.x(), base.y(), wrist.x(), wrist.y())
            if (tipDist > baseDist * thresholds[i]) extendedCount++
        }
        return extendedCount >= 4
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun resetDetection() {
        consecutivePalmFrames = 0
        _palmDetected.value = false
    }

    fun release() {
        handLandmarker?.close()
        handLandmarker = null
    }

    companion object {
        private const val TAG = "PalmGestureDetector"
    }
}
