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

    private val _fistDetected = MutableStateFlow(false)
    val fistDetected: StateFlow<Boolean> = _fistDetected.asStateFlow()

    private var palmStartMs = 0L
    private var fistStartMs = 0L
    private val palmRequiredMs = 5000L
    private val fistRequiredMs = 2000L

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
            val now = System.currentTimeMillis()

            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                val extended = countExtendedFingers(landmarks)
                if (extended >= 4) {
                    if (palmStartMs == 0L) palmStartMs = now
                    fistStartMs = 0L
                    _fistDetected.value = false
                    if (now - palmStartMs >= palmRequiredMs) {
                        _palmDetected.value = true
                    }
                } else if (extended <= 1) {
                    if (fistStartMs == 0L) fistStartMs = now
                    palmStartMs = 0L
                    _palmDetected.value = false
                    if (now - fistStartMs >= fistRequiredMs) {
                        _fistDetected.value = true
                    }
                } else {
                    palmStartMs = 0L
                    fistStartMs = 0L
                    _palmDetected.value = false
                    _fistDetected.value = false
                }
            } else {
                palmStartMs = 0L
                fistStartMs = 0L
                _palmDetected.value = false
                _fistDetected.value = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hand detection failed", e)
        } finally {
            processing.set(false)
        }
    }

    private fun countExtendedFingers(landmarks: List<NormalizedLandmark>): Int {
        if (landmarks.size < 21) return -1

        val wrist = landmarks[0]
        val middleBase = landmarks[9]
        val palmSize = dist(wrist.x(), wrist.y(), middleBase.x(), middleBase.y())
        if (palmSize < 0.05f) return -1

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
        return extendedCount
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun resetDetection() {
        palmStartMs = 0L
        fistStartMs = 0L
        _palmDetected.value = false
        _fistDetected.value = false
    }

    fun release() {
        handLandmarker?.close()
        handLandmarker = null
    }

    companion object {
        private const val TAG = "PalmGestureDetector"
    }
}
