package com.spectra.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameProvider @Inject constructor() : ImageAnalysis.Analyzer {

    private val _frames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private var frameCount = 0
    private val analyzeEveryN = 5

    override fun analyze(image: ImageProxy) {
        frameCount++
        if (frameCount % analyzeEveryN == 0) {
            val bitmap = image.toBitmap()
            if (bitmap != null) {
                _frames.tryEmit(bitmap)
            }
        }
        image.close()
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
