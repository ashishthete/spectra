package com.spectra.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.spectra.core.model.PhotoStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

@Singleton
class CaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun capturePhoto(
        imageCapture: ImageCapture,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false
    ): String {
        val frameCount = if (isFrontCamera) 3 else 2
        return captureMultiFrame(imageCapture, frameCount, beautyLevel, style, isFrontCamera)
    }

    private suspend fun captureMultiFrame(
        imageCapture: ImageCapture,
        frameCount: Int,
        beautyLevel: Int,
        style: PhotoStyle,
        isFrontCamera: Boolean
    ): String {
        Log.d("CaptureManager", "Multi-frame capture: $frameCount frames")
        val frames = mutableListOf<Pair<ByteArray, Int>>() // jpeg bytes + rotation

        for (i in 0 until frameCount) {
            try {
                val frame = captureInMemory(imageCapture)
                frames.add(frame)
                if (i < frameCount - 1) delay(80)
            } catch (e: Exception) {
                Log.w("CaptureManager", "Frame $i failed", e)
            }
        }

        if (frames.isEmpty()) throw ImageCaptureException(0, "All frames failed", null)

        val best = withContext(Dispatchers.Default) {
            frames.maxBy { (jpegBytes, _) -> scoreSharpness(jpegBytes) }
        }
        Log.d("CaptureManager", "Best frame selected from ${frames.size} frames")

        val savedUri = saveJpegToMediaStore(best.first, best.second)

        if (savedUri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                applyPostProcess(Uri.parse(savedUri), beautyLevel, style, isFrontCamera)
            }
        }
        return savedUri
    }

    private suspend fun captureInMemory(imageCapture: ImageCapture): Pair<ByteArray, Int> {
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        continuation.resume(Pair(bytes, rotation))
                    } catch (e: Exception) {
                        image.close()
                        continuation.resumeWithException(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            })
        }
    }

    private fun scoreSharpness(jpegBytes: ByteArray): Float {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8
        }
        val small = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return 0f
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        var laplacianSum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = luminance(pixels[y * w + x])
                val top = luminance(pixels[(y - 1) * w + x])
                val bottom = luminance(pixels[(y + 1) * w + x])
                val left = luminance(pixels[y * w + (x - 1)])
                val right = luminance(pixels[y * w + (x + 1)])
                val lap = abs(4 * center - top - bottom - left - right)
                laplacianSum += lap
                count++
            }
        }
        val score = if (count > 0) (laplacianSum / count).toFloat() else 0f
        Log.d("CaptureManager", "Sharpness score: $score")
        return score
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private suspend fun saveJpegToMediaStore(jpegBytes: ByteArray, rotationDegrees: Int): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra")
        }
        return withContext(Dispatchers.IO) {
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return@withContext ""

            context.contentResolver.openOutputStream(uri)?.use { it.write(jpegBytes) }

            // Set EXIF orientation
            val exifOrientation = when (rotationDegrees) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
                try {
                    val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (fd != null) {
                        val exif = ExifInterface(fd.fileDescriptor)
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                        exif.saveAttributes()
                        fd.close()
                    }
                } catch (e: Exception) {
                    Log.w("CaptureManager", "EXIF write failed", e)
                }
            }

            Log.d("CaptureManager", "Multi-frame photo saved: $uri")
            uri.toString()
        }
    }

    fun deletePhoto(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false) {
        try {
            val exifStream = context.contentResolver.openInputStream(uri) ?: return
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()

            val inputStream = context.contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return

            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)

            // Front camera: warm/brighten skin tones to match Samsung quality
            if (isFrontCamera) {
                val warmPaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        1.05f, 0.02f, 0f, 0f, 10f,
                        0f, 1.03f, 0f, 0f, 6f,
                        0f, 0f, 0.98f, 0f, -2f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                canvas.drawBitmap(result, 0f, 0f, warmPaint)
            }

            if (style != PhotoStyle.NATURAL) {
                val stylePaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(getStyleMatrix(style))
                }
                canvas.drawBitmap(result, 0f, 0f, stylePaint)
            }

            if (style == PhotoStyle.FILM) {
                val liftPaint = Paint().apply { color = Color.argb(18, 40, 35, 50) }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), liftPaint)
            }
            if (style == PhotoStyle.CINEMATIC) {
                val tealPaint = Paint().apply { color = Color.argb(12, 0, 60, 70) }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), tealPaint)
            }

            if (beautyLevel > 0) {
                val alpha = when (beautyLevel) {
                    1 -> 0.05f; 2 -> 0.10f; 3 -> 0.15f; else -> 0f
                }
                val brightPaint = Paint().apply {
                    color = Color.WHITE; this.alpha = (alpha * 255).toInt()
                }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), brightPaint)
                val cx = result.width / 2f
                val cy = result.height / 2f
                val radius = maxOf(cx, cy)
                val vignettePaint = Paint().apply {
                    shader = RadialGradient(cx, cy, radius,
                        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((alpha * 0.5f * 255).toInt(), 0, 0, 0)),
                        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), vignettePaint)
            }

            if (style == PhotoStyle.CINEMATIC || style == PhotoStyle.FILM) {
                val cx = result.width / 2f
                val cy = result.height / 2f
                val radius = maxOf(cx, cy)
                val vignettePaint = Paint().apply {
                    shader = RadialGradient(cx, cy, radius,
                        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(50, 0, 0, 0)),
                        floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), vignettePaint)
            }

            val outputStream = context.contentResolver.openOutputStream(uri, "w") ?: return
            result.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()

            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != 0) {
                try {
                    val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (fd != null) {
                        val exifWrite = ExifInterface(fd.fileDescriptor)
                        exifWrite.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        exifWrite.saveAttributes()
                        fd.close()
                    }
                } catch (_: Exception) { }
            }

            original.recycle()
            result.recycle()
            Log.d("CaptureManager", "Post-process applied: style=$style, beauty=$beautyLevel, front=$isFrontCamera")
        } catch (e: Exception) {
            Log.e("CaptureManager", "Post-process failed", e)
        }
    }

    private fun getStyleMatrix(style: PhotoStyle): ColorMatrix {
        return when (style) {
            PhotoStyle.NATURAL -> ColorMatrix()
            PhotoStyle.VIVID -> {
                val sat = ColorMatrix().apply { setSaturation(1.4f) }
                val contrast = ColorMatrix(floatArrayOf(
                    1.15f, 0f, 0f, 0f, -20f, 0f, 1.15f, 0f, 0f, -20f,
                    0f, 0f, 1.15f, 0f, -20f, 0f, 0f, 0f, 1f, 0f
                ))
                sat.postConcat(contrast); sat
            }
            PhotoStyle.WARM -> {
                val sat = ColorMatrix().apply { setSaturation(0.95f) }
                val warm = ColorMatrix(floatArrayOf(
                    1.08f, 0.05f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.92f, 0f, -5f, 0f, 0f, 0f, 1f, 0f
                ))
                sat.postConcat(warm); sat
            }
            PhotoStyle.FILM -> {
                val sat = ColorMatrix().apply { setSaturation(0.7f) }
                val lifted = ColorMatrix(floatArrayOf(
                    0.95f, 0.05f, 0.02f, 0f, 12f, 0.02f, 0.95f, 0.03f, 0f, 10f,
                    0.03f, 0.03f, 0.90f, 0f, 18f, 0f, 0f, 0f, 1f, 0f
                ))
                sat.postConcat(lifted); sat
            }
            PhotoStyle.CINEMATIC -> {
                val sat = ColorMatrix().apply { setSaturation(0.85f) }
                val tealOrange = ColorMatrix(floatArrayOf(
                    1.1f, 0f, -0.05f, 0f, 5f, -0.02f, 1.0f, 0.05f, 0f, -3f,
                    -0.05f, 0.05f, 1.12f, 0f, -8f, 0f, 0f, 0f, 1f, 0f
                ))
                val crushBlacks = ColorMatrix(floatArrayOf(
                    1.1f, 0f, 0f, 0f, -15f, 0f, 1.1f, 0f, 0f, -15f,
                    0f, 0f, 1.1f, 0f, -15f, 0f, 0f, 0f, 1f, 0f
                ))
                sat.postConcat(tealOrange); sat.postConcat(crushBlacks); sat
            }
        }
    }
}
