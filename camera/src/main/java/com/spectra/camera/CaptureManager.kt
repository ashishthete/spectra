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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.spectra.core.model.PhotoStyle
import com.spectra.core.model.SceneType
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
import kotlin.math.min
import kotlin.math.max

@Singleton
class CaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val hdrProcessor = HdrProcessor()
    private var depthEstimator: DepthEstimator? = null
    private val depthBokeh = DepthBokeh()

    fun initDepthModel() {
        val estimator = DepthEstimator(context)
        if (estimator.initialize()) {
            depthEstimator = estimator
            Log.d("CaptureManager", "Depth model loaded")
        }
    }

    fun releaseDepthModel() {
        depthEstimator?.release()
        depthEstimator = null
    }

    suspend fun capturePhoto(
        imageCapture: ImageCapture,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        currentIso: Int = 0,
        currentExposureNs: Long = 0,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList()
    ): String {
        val isLowLight = currentIso > 800 || currentExposureNs > 33_000_000L
        val frameCount = when {
            isLowLight && !isFrontCamera -> 5
            isLowLight && isFrontCamera -> 4
            isFrontCamera -> 3
            else -> 2
        }
        val useAveraging = isLowLight && frameCount > 2
        Log.d("CaptureManager", "Capture: lowLight=$isLowLight, hdr=$isHdr, frames=$frameCount, avg=$useAveraging, ISO=$currentIso")
        return captureMultiFrame(imageCapture, frameCount, beautyLevel, style, isFrontCamera, useAveraging, isHdr, faceRects)
    }

    data class SmartCaptureResult(
        val bestOriginalUri: String,
        val allFrames: List<Pair<ByteArray, Int>>
    )

    suspend fun captureSmartPhoto(
        imageCapture: ImageCapture,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList()
    ): SmartCaptureResult {
        Log.d("CaptureManager", "Smart capture: 5 frames")
        val frames = mutableListOf<Pair<ByteArray, Int>>()

        for (i in 0 until 5) {
            try {
                val frame = captureInMemory(imageCapture)
                frames.add(frame)
                if (i < 4) delay(30)
            } catch (e: Exception) {
                Log.w("CaptureManager", "Smart frame $i failed", e)
            }
        }

        if (frames.isEmpty()) throw ImageCaptureException(0, "All frames failed", null)

        val scored = withContext(Dispatchers.Default) {
            frames.map { (jpeg, rot) ->
                val score = scoreFrame(jpeg, faceRects)
                Triple(jpeg, rot, score)
            }
        }

        val best = scored.maxBy { it.third }
        Log.d("CaptureManager", "Best frame score: ${best.third} out of ${scored.size} frames")

        val rawUri = saveJpegToMediaStore(best.first, best.second)

        return SmartCaptureResult(bestOriginalUri = rawUri, allFrames = frames)
    }

    suspend fun saveProcessedCopy(
        rawUri: String,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList(),
        isPortraitMode: Boolean = false
    ): String {
        return withContext(Dispatchers.IO) {
            val sourceUri = Uri.parse(rawUri)

            val exifStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext ""
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext ""
            val rawBytes = inputStream.readBytes()
            inputStream.close()

            val copyUri = saveJpegToMediaStore(rawBytes, rotation)
            if (copyUri.isNotEmpty()) {
                applyPostProcess(Uri.parse(copyUri), beautyLevel, style, isFrontCamera, isHdr, faceRects, isPortraitMode)
            }
            Log.d("CaptureManager", "Processed copy saved: $copyUri")
            copyUri
        }
    }

    suspend fun generateAiEnhanced(
        frames: List<Pair<ByteArray, Int>>,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList()
    ): String {
        return withContext(Dispatchers.Default) {
            val enhanced = stackAndEnhance(frames)
            val uri = saveJpegToMediaStore(enhanced.first, enhanced.second)
            if (uri.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    applyPostProcess(Uri.parse(uri), beautyLevel, style, isFrontCamera, true, faceRects)
                }
            }
            Log.d("CaptureManager", "AI enhanced photo saved: $uri")
            uri
        }
    }

    private fun scoreFrame(jpegBytes: ByteArray, faceRects: List<RectF>): Float {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val small = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return 0f
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        var laplacianSum = 0.0
        var lapCount = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = luminance(pixels[y * w + x])
                val top = luminance(pixels[(y - 1) * w + x])
                val bottom = luminance(pixels[(y + 1) * w + x])
                val left = luminance(pixels[y * w + (x - 1)])
                val right = luminance(pixels[y * w + (x + 1)])
                val lap = abs(4 * center - top - bottom - left - right)
                laplacianSum += lap
                lapCount++
            }
        }
        val sharpness = if (lapCount > 0) (laplacianSum / lapCount).toFloat() else 0f

        var lumSum = 0L
        for (pixel in pixels) lumSum += luminance(pixel)
        val avgLum = lumSum / pixels.size.toFloat()
        val exposureScore = 1f - abs(avgLum - 128f) / 128f

        var faceSharpness = 0f
        if (faceRects.isNotEmpty()) {
            var faceTotal = 0.0
            var facePixelCount = 0
            for (face in faceRects) {
                val fx0 = (face.left * w).toInt().coerceIn(1, w - 2)
                val fy0 = (face.top * h).toInt().coerceIn(1, h - 2)
                val fx1 = (face.right * w).toInt().coerceIn(1, w - 2)
                val fy1 = (face.bottom * h).toInt().coerceIn(1, h - 2)
                for (y in fy0..fy1) {
                    for (x in fx0..fx1) {
                        val center = luminance(pixels[y * w + x])
                        val top2 = luminance(pixels[(y - 1) * w + x])
                        val bottom2 = luminance(pixels[(y + 1) * w + x])
                        val left2 = luminance(pixels[y * w + max(0, x - 1)])
                        val right2 = luminance(pixels[y * w + min(w - 1, x + 1)])
                        faceTotal += abs(4 * center - top2 - bottom2 - left2 - right2)
                        facePixelCount++
                    }
                }
            }
            faceSharpness = if (facePixelCount > 0) (faceTotal / facePixelCount).toFloat() else 0f
        }

        small.recycle()

        val score = sharpness * 0.4f + exposureScore * 10f * 0.2f + faceSharpness * 0.4f
        return if (faceRects.isEmpty()) sharpness * 0.7f + exposureScore * 10f * 0.3f else score
    }

    private fun stackAndEnhance(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val bestIdx = frames.indices.maxBy { scoreSharpness(frames[it].first) }
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frames[bestIdx].first, 0, frames[bestIdx].first.size, probe)
        val sampleSize = if (probe.outWidth * probe.outHeight > 8_000_000) 2 else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val refBmp = BitmapFactory.decodeByteArray(frames[bestIdx].first, 0, frames[bestIdx].first.size, decodeOpts)
            ?: return frames[bestIdx]

        val w = refBmp.width
        val h = refBmp.height
        val refPixels = IntArray(w * h)
        refBmp.getPixels(refPixels, 0, w, 0, 0, w, h)
        refBmp.recycle()

        val sumR = IntArray(w * h)
        val sumG = IntArray(w * h)
        val sumB = IntArray(w * h)
        val counts = IntArray(w * h)
        for (i in refPixels.indices) {
            sumR[i] = (refPixels[i] shr 16) and 0xFF
            sumG[i] = (refPixels[i] shr 8) and 0xFF
            sumB[i] = refPixels[i] and 0xFF
            counts[i] = 1
        }

        val motionThreshold = 30
        val tempPixels = IntArray(w * h)
        for (k in frames.indices) {
            if (k == bestIdx) continue
            val bmp = BitmapFactory.decodeByteArray(frames[k].first, 0, frames[k].first.size, decodeOpts) ?: continue
            if (bmp.width != w || bmp.height != h) { bmp.recycle(); continue }
            bmp.getPixels(tempPixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (i in tempPixels.indices) {
                val r = (tempPixels[i] shr 16) and 0xFF
                val g = (tempPixels[i] shr 8) and 0xFF
                val b = tempPixels[i] and 0xFF
                val refR = (refPixels[i] shr 16) and 0xFF
                val refG = (refPixels[i] shr 8) and 0xFF
                val refB = refPixels[i] and 0xFF
                val diff = abs(r - refR) + abs(g - refG) + abs(b - refB)
                if (diff < motionThreshold) {
                    sumR[i] += r
                    sumG[i] += g
                    sumB[i] += b
                    counts[i]++
                }
            }
        }

        val outPixels = IntArray(w * h)
        var mergedCount = 0
        for (i in outPixels.indices) {
            val c = counts[i]
            if (c > 1) mergedCount++
            val r = (sumR[i] / c).coerceIn(0, 255)
            val g = (sumG[i] / c).coerceIn(0, 255)
            val b = (sumB[i] / c).coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)

        applyLocalToneMap(result, 0.5f)
        applySharpen(result)
        applyEnhanceColors(result)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val jpegBytes = stream.toByteArray()

        result.recycle()
        val pct = if (w * h > 0) (mergedCount * 100 / (w * h)) else 0
        Log.d("CaptureManager", "AI stack+enhance: ${frames.size} frames, $pct% pixels denoised (sample=$sampleSize)")
        return Pair(jpegBytes, frames[bestIdx].second)
    }

    private fun applyEnhanceColors(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            val sat = ColorMatrix().apply { setSaturation(1.15f) }
            val contrast = ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, -13f,
                0f, 1.1f, 0f, 0f, -13f,
                0f, 0f, 1.1f, 0f, -13f,
                0f, 0f, 0f, 1f, 0f
            ))
            sat.postConcat(contrast)
            colorFilter = ColorMatrixColorFilter(sat)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    private fun applySharpen(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val output = IntArray(w * h)
        System.arraycopy(pixels, 0, output, 0, pixels.size)

        val strength = 0.3f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val cR = (pixels[idx] shr 16) and 0xFF
                val cG = (pixels[idx] shr 8) and 0xFF
                val cB = pixels[idx] and 0xFF

                var sumR = 0; var sumG = 0; var sumB = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dy == 0 && dx == 0) continue
                        val nIdx = (y + dy) * w + (x + dx)
                        sumR += (pixels[nIdx] shr 16) and 0xFF
                        sumG += (pixels[nIdx] shr 8) and 0xFF
                        sumB += pixels[nIdx] and 0xFF
                    }
                }
                val avgR = sumR / 8; val avgG = sumG / 8; val avgB = sumB / 8
                val rOut = (cR + (cR - avgR) * strength).toInt().coerceIn(0, 255)
                val gOut = (cG + (cG - avgG) * strength).toInt().coerceIn(0, 255)
                val bOut = (cB + (cB - avgB) * strength).toInt().coerceIn(0, 255)
                output[idx] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }
        bitmap.setPixels(output, 0, w, 0, 0, w, h)
    }

    private suspend fun captureMultiFrame(
        imageCapture: ImageCapture,
        frameCount: Int,
        beautyLevel: Int,
        style: PhotoStyle,
        isFrontCamera: Boolean,
        useAveraging: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList()
    ): String {
        Log.d("CaptureManager", "Multi-frame capture: $frameCount frames, averaging=$useAveraging")
        val frames = mutableListOf<Pair<ByteArray, Int>>()

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

        val finalJpeg: ByteArray
        val finalRotation: Int

        if (useAveraging && frames.size >= 2) {
            val result = withContext(Dispatchers.Default) { averageFrames(frames) }
            finalJpeg = result.first
            finalRotation = result.second
            Log.d("CaptureManager", "Night mode: averaged ${frames.size} frames")
        } else {
            val best = withContext(Dispatchers.Default) {
                frames.maxBy { (jpegBytes, _) -> scoreSharpness(jpegBytes) }
            }
            finalJpeg = best.first
            finalRotation = best.second
            Log.d("CaptureManager", "Best frame selected from ${frames.size} frames")
        }

        val savedUri = saveJpegToMediaStore(finalJpeg, finalRotation)

        if (savedUri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                applyPostProcess(Uri.parse(savedUri), beautyLevel, style, isFrontCamera, isHdr, faceRects)
            }
        }
        return savedUri
    }

    private fun averageFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val bitmaps = frames.mapNotNull { (bytes, _) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (bitmaps.size < 2) {
            val best = frames.maxBy { (jpegBytes, _) -> scoreSharpness(jpegBytes) }
            bitmaps.forEach { it.recycle() }
            return best
        }

        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val sumR = IntArray(w * h)
        val sumG = IntArray(w * h)
        val sumB = IntArray(w * h)
        var validCount = 0

        for (bmp in bitmaps) {
            if (bmp.width != w || bmp.height != h) continue
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                sumR[i] += (pixels[i] shr 16) and 0xFF
                sumG[i] += (pixels[i] shr 8) and 0xFF
                sumB[i] += pixels[i] and 0xFF
            }
            validCount++
        }

        if (validCount < 2) {
            bitmaps.forEach { it.recycle() }
            val best = frames.maxBy { (jpegBytes, _) -> scoreSharpness(jpegBytes) }
            return best
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(w * h)
        for (i in outPixels.indices) {
            val r = (sumR[i] / validCount).coerceIn(0, 255)
            val g = (sumG[i] / validCount).coerceIn(0, 255)
            val b = (sumB[i] / validCount).coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(outPixels, 0, w, 0, 0, w, h)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val jpegBytes = stream.toByteArray()

        bitmaps.forEach { it.recycle() }
        result.recycle()

        return Pair(jpegBytes, frames[0].second)
    }

    private suspend fun captureInMemory(imageCapture: ImageCapture): Pair<ByteArray, Int> {
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (continuation.isCancelled) {
                        image.close()
                        return
                    }
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
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(exception)
                    }
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
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                    }
                    val location = locationProvider.getLastLocation()
                    if (location != null) {
                        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, decimalToDms(location.latitude))
                        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (location.latitude >= 0) "N" else "S")
                        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, decimalToDms(location.longitude))
                        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (location.longitude >= 0) "E" else "W")
                    }
                    exif.saveAttributes()
                }
            } catch (e: Exception) {
                Log.w("CaptureManager", "EXIF write failed", e)
            }

            Log.d("CaptureManager", "Multi-frame photo saved: $uri")
            uri.toString()
        }
    }

    suspend fun captureHdrBracket(
        imageCapture: ImageCapture,
        baseExposureNs: Long,
        baseIso: Int,
        applyBracketSettings: suspend (exposureNs: Long, iso: Int) -> Unit,
        restoreAutoExposure: suspend () -> Unit,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        faceRects: List<android.graphics.RectF> = emptyList(),
        isPortraitMode: Boolean = false
    ): String {
        val brackets = HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
        val frames = mutableListOf<Pair<ByteArray, Int>>()

        try {
            for ((exposureNs, iso) in brackets) {
                applyBracketSettings(exposureNs, iso)
                delay(50)
                try {
                    val frame = captureInMemory(imageCapture)
                    frames.add(frame)
                } catch (e: Exception) {
                    Log.w("CaptureManager", "HDR bracket frame failed", e)
                }
            }
        } finally {
            restoreAutoExposure()
        }

        if (frames.isEmpty()) throw androidx.camera.core.ImageCaptureException(0, "All HDR frames failed", null)

        if (frames.size < 2) {
            val uri = saveJpegToMediaStore(frames[0].first, frames[0].second)
            if (uri.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    applyPostProcess(android.net.Uri.parse(uri), beautyLevel, style, isFrontCamera, true, faceRects, isPortraitMode)
                }
            }
            return uri
        }

        val merged = withContext(Dispatchers.Default) {
            mergeHdrFrames(frames)
        }

        val uri = saveJpegToMediaStore(merged.first, merged.second)
        if (uri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                applyPostProcess(android.net.Uri.parse(uri), beautyLevel, style, isFrontCamera, false, faceRects, isPortraitMode)
            }
        }
        Log.d("CaptureManager", "HDR bracket: ${frames.size} frames merged")
        return uri
    }

    private fun mergeHdrFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val bitmaps = frames.mapNotNull { (bytes, _) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (bitmaps.size < 2) {
            bitmaps.forEach { it.recycle() }
            return frames.first()
        }

        val w = bitmaps[0].width
        val h = bitmaps[0].height

        val pixelArrays = bitmaps.mapIndexed { index, bmp ->
            if (bmp.width != w || bmp.height != h) {
                bmp.recycle()
                null
            } else {
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()
                pixels
            }
        }.filterNotNull()

        if (pixelArrays.size < 2) return frames.first()

        val refPixels = pixelArrays[0]
        val alignedFrames = mutableListOf(refPixels)
        for (i in 1 until pixelArrays.size) {
            alignedFrames.add(hdrProcessor.alignFrame(refPixels, pixelArrays[i], w, h))
        }

        val merged = hdrProcessor.mertensFusion(alignedFrames, w, h)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(merged, 0, w, 0, 0, w, h)

        applySharpen(result)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val jpegBytes = stream.toByteArray()
        result.recycle()

        Log.d("CaptureManager", "HDR merge: ${pixelArrays.size} frames fused")
        return Pair(jpegBytes, frames[0].second)
    }

    fun deletePhoto(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false, sceneType: SceneType = SceneType.UNKNOWN, currentIso: Int = 100) {
        try {
            val exifStream = context.contentResolver.openInputStream(uri) ?: return
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()

            val inputStream = context.contentResolver.openInputStream(uri) ?: return
            val original = try {
                BitmapFactory.decodeStream(inputStream)
            } catch (e: OutOfMemoryError) {
                Log.e("CaptureManager", "OOM decoding image for post-process", e)
                inputStream.close()
                return
            }
            inputStream.close()
            if (original == null) return

            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)

            val enhancePaint = Paint().apply {
                val contrast = ColorMatrix(floatArrayOf(
                    1.03f, 0f, 0f, 0f, -4f,
                    0f, 1.03f, 0f, 0f, -4f,
                    0f, 0f, 1.03f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val saturation = ColorMatrix().apply { setSaturation(1.05f) }
                contrast.postConcat(saturation)
                colorFilter = ColorMatrixColorFilter(contrast)
            }
            canvas.drawBitmap(result, 0f, 0f, enhancePaint)

            if (isHdr) {
                applyLocalToneMap(result, 0.5f)
                Log.d("CaptureManager", "Local tone mapping (bilateral decomposition) applied")
            }

            if (isFrontCamera) {
                val warmPaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        1.02f, 0.01f, 0f, 0f, 3f,
                        0f, 1.01f, 0f, 0f, 2f,
                        0f, 0f, 0.99f, 0f, -1f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                canvas.drawBitmap(result, 0f, 0f, warmPaint)
            }

            NoiseReducer.apply(result, currentIso)

            ToneCurveEngine.apply(result, style)

            if (beautyLevel > 0) {
                applyLabBeauty(result, canvas, beautyLevel, faceRects)
            }

            if (isPortraitMode && faceRects.isNotEmpty()) {
                applyPortraitBokeh(result, canvas, faceRects)
            }

            applySharpenLuminance(result, sceneType)

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
            result.compress(Bitmap.CompressFormat.JPEG, 98, outputStream)
            outputStream.close()

            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != 0) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                        val exifWrite = ExifInterface(fd.fileDescriptor)
                        exifWrite.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        exifWrite.saveAttributes()
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

    private fun applyHdrToneMap(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val lum = 0.299f * r + 0.587f * g + 0.114f * b

            // Reinhard-inspired tone curve: boost shadows, compress highlights
            val mapped = if (lum < 0.01f) lum else {
                val shadowBoost = 1.0f + 0.4f * (1.0f - lum) * (1.0f - lum)
                val highlightCompress = 1.0f / (1.0f + lum * 0.3f)
                lum * shadowBoost * highlightCompress
            }

            val scale = if (lum < 0.001f) 1.0f else (mapped / lum).coerceIn(0.5f, 2.5f)
            val rOut = (r * scale * 255f).toInt().coerceIn(0, 255)
            val gOut = (g * scale * 255f).toInt().coerceIn(0, 255)
            val bOut = (b * scale * 255f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applyLocalToneMap(bitmap: android.graphics.Bitmap, compressionFactor: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val luminance = FloatArray(w * h)
        val logLum = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255f
            val g = ((pixels[i] shr 8) and 0xFF) / 255f
            val b = (pixels[i] and 0xFF) / 255f
            luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).coerceAtLeast(0.001f)
            logLum[i] = kotlin.math.ln(luminance[i])
        }

        val base = FloatArray(w * h)
        System.arraycopy(logLum, 0, base, 0, logLum.size)
        bilateralApprox(base, logLum, w, h, spatialRadius = 5, rangeSigma = 0.4f)

        val detail = FloatArray(w * h)
        for (i in detail.indices) {
            detail[i] = logLum[i] - base[i]
        }

        val baseMean = base.average().toFloat()
        for (i in base.indices) {
            base[i] = baseMean + (base[i] - baseMean) * compressionFactor
        }

        for (i in pixels.indices) {
            val newLum = kotlin.math.exp(base[i] + detail[i]).coerceIn(0.001f, 10f)
            val scale = if (luminance[i] > 0.001f) (newLum / luminance[i]).coerceIn(0.2f, 5f) else 1f

            val r = ((pixels[i] shr 16) and 0xFF)
            val g = ((pixels[i] shr 8) and 0xFF)
            val b = (pixels[i] and 0xFF)

            val rOut = (r * scale).toInt().coerceIn(0, 255)
            val gOut = (g * scale).toInt().coerceIn(0, 255)
            val bOut = (b * scale).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun analyzeSceneContrast(jpegBytes: ByteArray): Float {
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val small = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return 0f
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        var darkCount = 0
        var brightCount = 0
        for (pixel in pixels) {
            val lum = (((pixel shr 16) and 0xFF) * 77 +
                    ((pixel shr 8) and 0xFF) * 150 +
                    (pixel and 0xFF) * 29) shr 8
            if (lum < 50) darkCount++
            if (lum > 200) brightCount++
        }

        val total = pixels.size.toFloat()
        val darkRatio = darkCount / total
        val brightRatio = brightCount / total
        return (darkRatio * brightRatio * 100f).coerceIn(0f, 1f)
    }

    private fun applyFaceAwareBeauty(result: Bitmap, canvas: Canvas, beautyLevel: Int, faceRects: List<RectF>) {
        val w = result.width
        val h = result.height
        val blurScale = when (beautyLevel) { 1 -> 6; 2 -> 8; 3 -> 12; else -> 6 }
        val blendAlpha = when (beautyLevel) { 1 -> 0.10f; 2 -> 0.18f; 3 -> 0.28f; else -> 0f }
        val brighten = when (beautyLevel) { 1 -> 2; 2 -> 4; 3 -> 7; else -> 0 }

        if (faceRects.isNotEmpty()) {
            for (faceNorm in faceRects) {
                val faceRect = Rect(
                    (faceNorm.left * w).toInt().coerceIn(0, w),
                    (faceNorm.top * h).toInt().coerceIn(0, h),
                    (faceNorm.right * w).toInt().coerceIn(0, w),
                    (faceNorm.bottom * h).toInt().coerceIn(0, h)
                )
                val expandX = (faceRect.width() * 0.15f).toInt()
                val expandY = (faceRect.height() * 0.15f).toInt()
                faceRect.inset(-expandX, -expandY)
                if (!faceRect.intersect(0, 0, w, h)) continue

                val fw = faceRect.width()
                val fh = faceRect.height()
                if (fw <= 0 || fh <= 0) continue

                val faceBitmap = Bitmap.createBitmap(result, faceRect.left, faceRect.top, fw, fh)
                val smallW = maxOf(1, fw / blurScale)
                val smallH = maxOf(1, fh / blurScale)
                val small = Bitmap.createScaledBitmap(faceBitmap, smallW, smallH, true)
                val blurred = Bitmap.createScaledBitmap(small, fw, fh, true)
                small.recycle()

                val blendPaint = Paint().apply { alpha = (blendAlpha * 255).toInt() }
                canvas.drawBitmap(blurred, faceRect.left.toFloat(), faceRect.top.toFloat(), blendPaint)

                val brightPaint = Paint().apply {
                    color = Color.WHITE; alpha = brighten
                }
                canvas.drawRect(faceRect.left.toFloat(), faceRect.top.toFloat(),
                    faceRect.right.toFloat(), faceRect.bottom.toFloat(), brightPaint)

                faceBitmap.recycle()
                blurred.recycle()
            }
            Log.d("CaptureManager", "Face-aware beauty applied to ${faceRects.size} faces, level=$beautyLevel")
        } else {
            val alpha = when (beautyLevel) { 1 -> 0.03f; 2 -> 0.05f; 3 -> 0.08f; else -> 0f }
            val brightPaint = Paint().apply {
                color = Color.WHITE; this.alpha = (alpha * 255).toInt()
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), brightPaint)
            Log.d("CaptureManager", "Global beauty fallback (no faces), level=$beautyLevel")
        }
    }

    private fun applyPortraitBokeh(bitmap: Bitmap, canvas: Canvas, faceRects: List<RectF>) {
        val estimator = depthEstimator
        if (estimator != null && estimator.isAvailable()) {
            applyDepthMapBokeh(bitmap, canvas, estimator, faceRects)
        } else {
            applyFallbackEllipseBokeh(bitmap, canvas, faceRects)
        }
    }

    private fun applyDepthMapBokeh(bitmap: Bitmap, canvas: Canvas, estimator: DepthEstimator, faceRects: List<RectF>) {
        val w = bitmap.width
        val h = bitmap.height
        val depthInputSize = estimator.getInputSize()

        val depthMap = estimator.estimateDepth(bitmap)
        if (depthMap == null) {
            applyFallbackEllipseBokeh(bitmap, canvas, faceRects)
            return
        }

        val focusDepth = if (faceRects.isNotEmpty()) {
            val face = faceRects[0]
            val cx = ((face.left + face.right) / 2f * depthInputSize).toInt().coerceIn(0, depthInputSize - 1)
            val cy = ((face.top + face.bottom) / 2f * depthInputSize).toInt().coerceIn(0, depthInputSize - 1)
            depthMap[cy * depthInputSize + cx]
        } else {
            depthMap[depthInputSize / 2 * depthInputSize + depthInputSize / 2]
        }

        val lumGuide = FloatArray(depthInputSize * depthInputSize)
        val smallBmp = Bitmap.createScaledBitmap(bitmap, depthInputSize, depthInputSize, true)
        val smallPixels = IntArray(depthInputSize * depthInputSize)
        smallBmp.getPixels(smallPixels, 0, depthInputSize, 0, 0, depthInputSize, depthInputSize)
        smallBmp.recycle()
        for (i in smallPixels.indices) {
            val r = (smallPixels[i] shr 16) and 0xFF
            val g = (smallPixels[i] shr 8) and 0xFF
            val b = smallPixels[i] and 0xFF
            lumGuide[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        val refinedDepth = depthBokeh.guidedFilter(lumGuide, depthMap, depthInputSize, depthInputSize, radius = 4, eps = 0.01f)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val result = depthBokeh.applyDepthBokeh(
            pixels, refinedDepth, w, h,
            depthInputSize, depthInputSize,
            focusDepth = focusDepth,
            maxBlurRadius = 15f
        )

        bitmap.setPixels(result, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        Log.d("CaptureManager", "Depth-map bokeh applied, focus=${"%.2f".format(focusDepth)}")
    }

    private fun applyFallbackEllipseBokeh(bitmap: Bitmap, canvas: Canvas, faceRects: List<RectF>) {
        val w = bitmap.width
        val h = bitmap.height

        val sharpPixels = IntArray(w * h)
        bitmap.getPixels(sharpPixels, 0, w, 0, 0, w, h)

        val blurPixels = IntArray(w * h)
        System.arraycopy(sharpPixels, 0, blurPixels, 0, sharpPixels.size)
        for (pass in 0 until 3) {
            boxBlurPass(blurPixels, w, h, 7)
        }

        val mask = FloatArray(w * h)
        for (faceNorm in faceRects) {
            val cx = ((faceNorm.left + faceNorm.right) / 2f) * w
            val faceCy = ((faceNorm.top + faceNorm.bottom) / 2f) * h
            val faceW = (faceNorm.right - faceNorm.left) * w
            val faceH = (faceNorm.bottom - faceNorm.top) * h

            val bodyExtend = faceH * 2.5f
            val cy = faceCy + bodyExtend * 0.3f
            val rx = faceW * 1.2f
            val ry = (faceH + bodyExtend) * 0.6f
            val feather = maxOf(rx, ry) * 0.4f

            val x0 = maxOf(0, (cx - rx - feather).toInt())
            val x1 = minOf(w - 1, (cx + rx + feather).toInt())
            val y0 = maxOf(0, (cy - ry - feather).toInt())
            val y1 = minOf(h - 1, (cy + ry + feather).toInt())

            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = (x - cx) / rx
                    val dy = (y - cy) / ry
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val alpha = when {
                        dist <= 1.0f -> 1.0f
                        dist >= 1.0f + feather / maxOf(rx, ry) -> 0.0f
                        else -> {
                            val t = (dist - 1.0f) / (feather / maxOf(rx, ry))
                            1.0f - t * t * (3f - 2f * t)
                        }
                    }
                    val idx = y * w + x
                    if (alpha > mask[idx]) mask[idx] = alpha
                }
            }
        }

        val outPixels = IntArray(w * h)
        for (i in outPixels.indices) {
            val a = mask[i]
            if (a >= 0.999f) {
                outPixels[i] = sharpPixels[i]
            } else if (a <= 0.001f) {
                outPixels[i] = blurPixels[i]
            } else {
                val sR = (sharpPixels[i] shr 16) and 0xFF
                val sG = (sharpPixels[i] shr 8) and 0xFF
                val sB = sharpPixels[i] and 0xFF
                val bR = (blurPixels[i] shr 16) and 0xFF
                val bG = (blurPixels[i] shr 8) and 0xFF
                val bB = blurPixels[i] and 0xFF
                val r = (sR * a + bR * (1f - a)).toInt()
                val g = (sG * a + bG * (1f - a)).toInt()
                val b = (sB * a + bB * (1f - a)).toInt()
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        Log.d("CaptureManager", "Portrait bokeh applied, ${faceRects.size} face regions, feathered mask")
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val kernelSize = radius * 2 + 1
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (kx in -radius..radius) {
                val x = kx.coerceIn(0, w - 1)
                val p = pixels[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                temp[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                val addP = pixels[y * w + addX]
                val remP = pixels[y * w + remX]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (ky in -radius..radius) {
                val y = ky.coerceIn(0, h - 1)
                val p = temp[y * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or
                    ((sumR / kernelSize) shl 16) or
                    ((sumG / kernelSize) shl 8) or
                    (sumB / kernelSize)
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                val addP = temp[addY * w + x]
                val remP = temp[remY * w + x]
                sumR += ((addP shr 16) and 0xFF) - ((remP shr 16) and 0xFF)
                sumG += ((addP shr 8) and 0xFF) - ((remP shr 8) and 0xFF)
                sumB += (addP and 0xFF) - (remP and 0xFF)
            }
        }
    }

    private fun decimalToDms(decimal: Double): String {
        val absDecimal = kotlin.math.abs(decimal)
        val degrees = absDecimal.toInt()
        val minutesDecimal = (absDecimal - degrees) * 60
        val minutes = minutesDecimal.toInt()
        val seconds = ((minutesDecimal - minutes) * 60 * 10000).toInt()
        return "$degrees/1,$minutes/1,$seconds/10000"
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

    companion object {
        fun getSharpnessStrength(sceneType: SceneType): Float {
            return when (sceneType) {
                SceneType.PORTRAIT -> 0.2f
                SceneType.LANDSCAPE -> 0.5f
                SceneType.MACRO -> 0.6f
                SceneType.NIGHT -> 0.15f
                SceneType.ACTION -> 0.4f
                SceneType.PET -> 0.35f
                SceneType.FOOD -> 0.45f
                SceneType.ARCHITECTURE -> 0.5f
                SceneType.DOCUMENT -> 0.55f
                SceneType.INDOOR -> 0.35f
                SceneType.UNKNOWN -> 0.35f
            }
        }

        fun nrSigma(nr: Int): Float = 0.5f + (nr / 100f) * 3f

        fun sharpnessFromParams(sharpness: Int): Float = 0.1f + (sharpness / 100f) * 0.6f

        fun saturationMultiplier(sat: Int): Float = 0.5f + (sat / 100f) * 1.0f

        fun contrastScale(contrast: Int): Float {
            val normalized = (contrast - 50) / 50f
            return 1.0f + normalized * 0.2f
        }

        fun contrastOffset(contrast: Int): Float {
            val scale = contrastScale(contrast)
            return (-128f * (scale - 1f))
        }

        fun applySharpenLuminance(bitmap: android.graphics.Bitmap, sceneType: SceneType) {
            val strength = getSharpnessStrength(sceneType)
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val yChannel = IntArray(w * h)
            val cbChannel = IntArray(w * h)
            val crChannel = IntArray(w * h)

            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
                yChannel[i] = ycbcr[0]
                cbChannel[i] = ycbcr[1]
                crChannel[i] = ycbcr[2]
            }

            val blurredY = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    var sum = 0
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until h && nx in 0 until w) {
                                sum += yChannel[ny * w + nx]
                                count++
                            }
                        }
                    }
                    blurredY[idx] = sum / count
                }
            }

            val edgeMask = FloatArray(w * h)
            var maxEdge = 0f
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val center = yChannel[idx]
                    val top = yChannel[(y - 1) * w + x]
                    val bottom = yChannel[(y + 1) * w + x]
                    val left = yChannel[y * w + (x - 1)]
                    val right = yChannel[y * w + (x + 1)]
                    val laplacian = kotlin.math.abs(4 * center - top - bottom - left - right).toFloat()
                    edgeMask[idx] = laplacian
                    if (laplacian > maxEdge) maxEdge = laplacian
                }
            }

            val edgeThreshold = maxEdge * 0.1f
            if (maxEdge > 0f) {
                for (i in edgeMask.indices) {
                    edgeMask[i] = if (edgeMask[i] < edgeThreshold) 0f
                    else (edgeMask[i] / maxEdge).coerceIn(0f, 1f)
                }
            }

            val sharpenedY = IntArray(w * h)
            for (i in yChannel.indices) {
                val detail = yChannel[i] - blurredY[i]
                val edgeWeight = edgeMask[i]
                val sharpened = yChannel[i] + (strength * detail * edgeWeight).toInt()
                sharpenedY[i] = sharpened.coerceIn(0, 255)
            }

            for (i in pixels.indices) {
                val rgb = ColorSpaceUtils.ycbcrToRgb(sharpenedY[i], cbChannel[i], crChannel[i])
                pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        fun beautyBilateralRadius(beautyLevel: Int): Int {
            return when (beautyLevel) {
                1 -> 3
                2 -> 5
                3 -> 8
                else -> 0
            }
        }

        fun applyLabBeauty(
            bitmap: android.graphics.Bitmap,
            canvas: android.graphics.Canvas,
            beautyLevel: Int,
            faceRects: List<android.graphics.RectF>
        ) {
            if (beautyLevel <= 0 || faceRects.isEmpty()) return

            val w = bitmap.width
            val h = bitmap.height
            val radius = beautyBilateralRadius(beautyLevel)
            val rangeSigma = when (beautyLevel) {
                1 -> 10.0f
                2 -> 15.0f
                3 -> 20.0f
                else -> 10.0f
            }

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            for (faceNorm in faceRects) {
                val x0 = (faceNorm.left * w).toInt().coerceIn(0, w - 1)
                val y0 = (faceNorm.top * h).toInt().coerceIn(0, h - 1)
                val x1 = (faceNorm.right * w).toInt().coerceIn(0, w - 1)
                val y1 = (faceNorm.bottom * h).toInt().coerceIn(0, h - 1)
                val fw = x1 - x0
                val fh = y1 - y0
                if (fw <= 0 || fh <= 0) continue

                val skinMask = BooleanArray(fw * fh)
                val labL = FloatArray(fw * fh)
                val labA = FloatArray(fw * fh)
                val labB = FloatArray(fw * fh)

                for (fy in 0 until fh) {
                    for (fx in 0 until fw) {
                        val globalIdx = (y0 + fy) * w + (x0 + fx)
                        val pixel = pixels[globalIdx]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
                        skinMask[fy * fw + fx] = ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])

                        val lab = ColorSpaceUtils.rgbToLab(r, g, b)
                        val localIdx = fy * fw + fx
                        labL[localIdx] = lab[0]
                        labA[localIdx] = lab[1]
                        labB[localIdx] = lab[2]
                    }
                }

                val filteredA = bilateralFilterFloat(labA, fw, fh, radius, rangeSigma)
                val filteredB = bilateralFilterFloat(labB, fw, fh, radius, rangeSigma)

                for (fy in 0 until fh) {
                    for (fx in 0 until fw) {
                        val localIdx = fy * fw + fx
                        if (!skinMask[localIdx]) continue

                        val rgb = ColorSpaceUtils.labToRgb(labL[localIdx], filteredA[localIdx], filteredB[localIdx])
                        val globalIdx = (y0 + fy) * w + (x0 + fx)
                        pixels[globalIdx] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                    }
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, android.graphics.Paint())
        }

        fun bilateralApprox(
            output: FloatArray,
            guide: FloatArray,
            w: Int,
            h: Int,
            spatialRadius: Int,
            rangeSigma: Float
        ) {
            val temp = FloatArray(output.size)
            val rangeVar = 2f * rangeSigma * rangeSigma

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val centerVal = guide[idx]
                    var weightedSum = 0f
                    var weightSum = 0f

                    val y0 = maxOf(0, y - spatialRadius)
                    val y1 = minOf(h - 1, y + spatialRadius)
                    val x0 = maxOf(0, x - spatialRadius)
                    val x1 = minOf(w - 1, x + spatialRadius)

                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            val nIdx = ny * w + nx
                            val diff = guide[nIdx] - centerVal
                            val rangeWeight = kotlin.math.exp(-(diff * diff) / rangeVar)
                            weightedSum += output[nIdx] * rangeWeight
                            weightSum += rangeWeight
                        }
                    }

                    temp[idx] = if (weightSum > 0f) weightedSum / weightSum else output[idx]
                }
            }

            System.arraycopy(temp, 0, output, 0, output.size)
        }

        private fun bilateralFilterFloat(
            channel: FloatArray,
            w: Int,
            h: Int,
            spatialRadius: Int,
            rangeSigma: Float
        ): FloatArray {
            val output = FloatArray(channel.size)
            val rangeSigmaSq2 = 2.0f * rangeSigma * rangeSigma
            val spatialSigma = spatialRadius / 2.0f
            val spatialSigmaSq2 = 2.0f * spatialSigma * spatialSigma

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val centerVal = channel[idx]
                    var weightSum = 0.0f
                    var valueSum = 0.0f

                    val y0 = maxOf(0, y - spatialRadius)
                    val y1 = minOf(h - 1, y + spatialRadius)
                    val x0 = maxOf(0, x - spatialRadius)
                    val x1 = minOf(w - 1, x + spatialRadius)

                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            val nIdx = ny * w + nx
                            val nVal = channel[nIdx]

                            val dx = (nx - x).toFloat()
                            val dy = (ny - y).toFloat()
                            val spatialWeight = kotlin.math.exp(-(dx * dx + dy * dy) / spatialSigmaSq2)

                            val rangeDiff = nVal - centerVal
                            val rangeWeight = kotlin.math.exp(-(rangeDiff * rangeDiff) / rangeSigmaSq2)

                            val weight = spatialWeight * rangeWeight
                            weightSum += weight
                            valueSum += nVal * weight
                        }
                    }

                    output[idx] = if (weightSum > 0f) valueSum / weightSum else centerVal
                }
            }
            return output
        }
    }
}
