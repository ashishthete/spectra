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
import com.spectra.core.model.ProcessingParams
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
        faceRects: List<RectF> = emptyList(),
        processing: ProcessingParams = ProcessingParams()
    ): SmartCaptureResult {
        val frame = captureInMemory(imageCapture)
        val uri = saveJpegToMediaStore(frame.first, frame.second)
        Log.d("CaptureManager", "Single-frame capture saved: ${frame.first.size} bytes")
        return SmartCaptureResult(bestOriginalUri = uri, allFrames = listOf(frame))
    }

    private fun burstMerge(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val refIdx = pickSharpestIdx(frames)
        val rotation = frames[refIdx].second

        val thumbOpts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val refThumb = BitmapFactory.decodeByteArray(
            frames[refIdx].first, 0, frames[refIdx].first.size, thumbOpts
        )
        val refGray = refThumb?.let { toGrayscale(it).also { _ -> refThumb.recycle() } }

        val refBitmap = BitmapFactory.decodeByteArray(
            frames[refIdx].first, 0, frames[refIdx].first.size
        ) ?: return frames[refIdx]
        val w = refBitmap.width
        val h = refBitmap.height
        val n = w * h

        val avgPixels = IntArray(n)
        refBitmap.getPixels(avgPixels, 0, w, 0, 0, w, h)
        refBitmap.recycle()
        var mergedCount = 1

        val framePixels = IntArray(n)
        for (i in frames.indices) {
            if (i == refIdx) continue

            var dx = 0
            var dy = 0
            if (refGray != null) {
                val altThumb = BitmapFactory.decodeByteArray(
                    frames[i].first, 0, frames[i].first.size, thumbOpts
                )
                if (altThumb != null) {
                    val altGray = toGrayscale(altThumb)
                    altThumb.recycle()
                    val shift = alignGlobal(refGray, altGray)
                    dx = shift.first * 8
                    dy = shift.second * 8
                    Log.d("CaptureManager", "Frame $i alignment: dx=$dx, dy=$dy")
                }
            }

            val bitmap = BitmapFactory.decodeByteArray(
                frames[i].first, 0, frames[i].first.size
            ) ?: continue
            if (bitmap.width != w || bitmap.height != h) {
                bitmap.recycle()
                continue
            }
            bitmap.getPixels(framePixels, 0, w, 0, 0, w, h)
            bitmap.recycle()

            val newCount = mergedCount + 1
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val srcRow = row + dy
                    val srcCol = col + dx
                    if (srcRow < 0 || srcRow >= h || srcCol < 0 || srcCol >= w) continue
                    val j = row * w + col
                    val srcJ = srcRow * w + srcCol
                    val aR = (avgPixels[j] shr 16) and 0xFF
                    val aG = (avgPixels[j] shr 8) and 0xFF
                    val aB = avgPixels[j] and 0xFF
                    val fR = (framePixels[srcJ] shr 16) and 0xFF
                    val fG = (framePixels[srcJ] shr 8) and 0xFF
                    val fB = framePixels[srcJ] and 0xFF
                    val r = (aR * mergedCount + fR) / newCount
                    val g = (aG * mergedCount + fG) / newCount
                    val b = (aB * mergedCount + fB) / newCount
                    avgPixels[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            mergedCount = newCount
        }

        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        merged.setPixels(avgPixels, 0, w, 0, 0, w, h)

        val stream = java.io.ByteArrayOutputStream()
        merged.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        merged.recycle()
        Log.d("CaptureManager", "Burst merged $mergedCount frames, ${w}x${h}")
        return Pair(stream.toByteArray(), rotation)
    }

    private fun toGrayscale(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return Triple(gray, w, h)
    }

    private fun alignGlobal(
        ref: Triple<IntArray, Int, Int>,
        alt: Triple<IntArray, Int, Int>
    ): Pair<Int, Int> {
        val searchRadius = 4
        val (refG, rw, rh) = ref
        val (altG, aw, ah) = alt
        if (rw != aw || rh != ah) return Pair(0, 0)

        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0L
                var count = 0
                val rowStart = kotlin.math.max(0, -dy)
                val rowEnd = kotlin.math.min(rh, rh - dy)
                val colStart = kotlin.math.max(0, -dx)
                val colEnd = kotlin.math.min(rw, rw - dx)
                for (row in rowStart until rowEnd) {
                    for (col in colStart until colEnd) {
                        sad += kotlin.math.abs(refG[row * rw + col] - altG[(row + dy) * rw + (col + dx)])
                        count++
                    }
                }
                if (count > 0) {
                    val avgSad = sad / count
                    if (avgSad < bestSad) {
                        bestSad = avgSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }
        return Pair(bestDx, bestDy)
    }

    private fun pickSharpestIdx(frames: List<Pair<ByteArray, Int>>): Int {
        var bestScore = -1f
        var bestIdx = 0
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        for (i in frames.indices) {
            val thumb = BitmapFactory.decodeByteArray(
                frames[i].first, 0, frames[i].first.size, opts
            ) ?: continue
            val score = measureSharpness(thumb)
            thumb.recycle()
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun pickSharpest(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        var bestScore = -1f
        var bestFrame = frames[0]
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        for (frame in frames) {
            val thumb = BitmapFactory.decodeByteArray(frame.first, 0, frame.first.size, opts) ?: continue
            val score = measureSharpness(thumb)
            thumb.recycle()
            if (score > bestScore) {
                bestScore = score
                bestFrame = frame
            }
        }
        return bestFrame
    }

    private fun measureSharpness(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var edgeSum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = lum(pixels[y * w + x])
                val t = lum(pixels[(y - 1) * w + x])
                val b = lum(pixels[(y + 1) * w + x])
                val l = lum(pixels[y * w + (x - 1)])
                val r = lum(pixels[y * w + (x + 1)])
                edgeSum += abs(4 * c - t - b - l - r)
                count++
            }
        }
        return if (count > 0) (edgeSum / count).toFloat() else 0f
    }

    private fun lum(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    suspend fun saveProcessedCopy(
        rawUri: String,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList(),
        isPortraitMode: Boolean = false,
        processing: ProcessingParams = ProcessingParams(),
        sceneContrast: Float = 0f,
        captureIso: Int = 100,
        preset: String = "AUTO"
    ): String {
        return withContext(Dispatchers.Default) {
            try {
                val sourceUri = Uri.parse(rawUri)
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext rawUri
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (original == null) return@withContext rawUri

                val params = ImageEnhancer.EnhanceParams.forPreset(preset, captureIso, sceneContrast)
                val origScore = ImageEnhancer.scoreQuality(original)
                val enhanced = ImageEnhancer.enhance(original, params)
                val enhScore = ImageEnhancer.scoreQuality(enhanced)
                Log.d("CaptureManager", "Quality scores - Original: %.3f, AI: %.3f, preset: %s".format(origScore, enhScore, preset))
                if (enhScore < origScore - 0.02f) {
                    Log.d("CaptureManager", "AI worse than original, keeping original")
                    enhanced.recycle()
                    original.recycle()
                    return@withContext rawUri
                }
                original.recycle()

                val stream = java.io.ByteArrayOutputStream()
                enhanced.compress(Bitmap.CompressFormat.JPEG, 97, stream)
                enhanced.recycle()

                val exifSrc = context.contentResolver.openInputStream(sourceUri)
                val rotation = if (exifSrc != null) {
                    val exif = ExifInterface(exifSrc)
                    val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    exifSrc.close()
                    when (orient) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } else 0

                val copyUri = saveJpegToMediaStore(stream.toByteArray(), rotation, "_AI")
                Log.d("CaptureManager", "Enhanced copy saved: $copyUri (${stream.size()} bytes)")
                copyUri.ifEmpty { rawUri }
            } catch (e: Exception) {
                Log.w("CaptureManager", "Enhancement failed, using original", e)
                rawUri
            } catch (oom: OutOfMemoryError) {
                Log.w("CaptureManager", "Enhancement OOM, using original")
                System.gc()
                rawUri
            }
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

        val allPixels = mutableListOf<IntArray>()
        for (bmp in bitmaps) {
            if (bmp.width != w || bmp.height != h) continue
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            allPixels.add(pixels)
        }

        if (allPixels.size < 2) {
            bitmaps.forEach { it.recycle() }
            val best = frames.maxBy { (jpegBytes, _) -> scoreSharpness(jpegBytes) }
            return best
        }

        val refPixels = allPixels[0]
        val aligner = TileAligner()
        val alignedFrames = mutableListOf(refPixels)
        for (i in 1 until allPixels.size) {
            val shifts = aligner.estimateTileShifts(refPixels, allPixels[i], w, h)
            alignedFrames.add(aligner.applyTileShifts(allPixels[i], shifts, w, h))
        }

        val validCount = alignedFrames.size
        val sumR = IntArray(w * h)
        val sumG = IntArray(w * h)
        val sumB = IntArray(w * h)
        for (pixels in alignedFrames) {
            for (i in pixels.indices) {
                sumR[i] += (pixels[i] shr 16) and 0xFF
                sumG[i] += (pixels[i] shr 8) and 0xFF
                sumB[i] += pixels[i] and 0xFF
            }
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

    private suspend fun saveJpegToMediaStore(jpegBytes: ByteArray, rotationDegrees: Int, suffix: String = ""): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_${timestamp}${suffix}")
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

    suspend fun saveRawCopy(jpegBytes: ByteArray, rotationDegrees: Int): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_RAW_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra/RAW")
        }
        return withContext(Dispatchers.IO) {
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return@withContext ""

            context.contentResolver.openOutputStream(uri)?.use { it.write(jpegBytes) }

            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    val exifOrientation = when (rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                    }
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "SPECTRA RAW - unprocessed")
                    exif.saveAttributes()
                }
            } catch (e: Exception) {
                Log.w("CaptureManager", "RAW EXIF write failed", e)
            }

            Log.d("CaptureManager", "RAW copy saved: $uri")
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
        isPortraitMode: Boolean = false,
        evBias: Float = 0f
    ): String {
        val brackets = if (evBias != 0f) {
            HdrProcessor.computeBracketExposuresForHighlights(baseExposureNs, baseIso, evBias)
        } else {
            HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
        }
        val frames = mutableListOf<Pair<ByteArray, Int>>()

        try {
            for ((exposureNs, iso) in brackets) {
                applyBracketSettings(exposureNs, iso)
                delay(300)
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
            val uri = saveJpegToMediaStore(frames[0].first, frames[0].second, "_HDR")
            return uri
        }

        val merged = withContext(Dispatchers.Default) {
            mergeHdrFrames(frames)
        }

        val uri = saveJpegToMediaStore(merged.first, merged.second, "_HDR")
        Log.d("CaptureManager", "HDR bracket: ${frames.size} frames merged")
        return uri
    }

    private fun mergeHdrFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val maxPixels = 4_000_000
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frames[0].first, 0, frames[0].first.size, boundsOpts)
        val imagePixels = boundsOpts.outWidth.toLong() * boundsOpts.outHeight
        val sampleSize = if (imagePixels > maxPixels) {
            var s = 1
            while (imagePixels / (s * s) > maxPixels) s *= 2
            s
        } else 1

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmaps = frames.mapNotNull { (bytes, _) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
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

    private fun applyShadowRecovery(bitmap: Bitmap, strength: Float) {
        if (strength <= 0f) return

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

            if (lum < 0.4f) {
                val shadowFactor = 1f - (lum / 0.4f)
                val boost = 1f + strength * shadowFactor * 0.6f
                val rOut = (r * boost * 255f).toInt().coerceIn(0, 255)
                val gOut = (g * boost * 255f).toInt().coerceIn(0, 255)
                val bOut = (b * boost * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d("CaptureManager", "Shadow recovery applied: strength=${"%.2f".format(strength)}")
    }

    private fun applyPostProcess(uri: Uri, beautyLevel: Int, style: PhotoStyle, isFrontCamera: Boolean = false, isHdr: Boolean = false, faceRects: List<RectF> = emptyList(), isPortraitMode: Boolean = false, sceneType: SceneType = SceneType.UNKNOWN, currentIso: Int = 100, processing: ProcessingParams = ProcessingParams(), sceneContrast: Float = 0f) {
        try {
            val exifStream = context.contentResolver.openInputStream(uri) ?: return
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()

            val boundsStream = context.contentResolver.openInputStream(uri) ?: return
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(boundsStream, null, boundsOpts)
            boundsStream.close()

            val maxPixels = 12_000_000
            val imagePixels = boundsOpts.outWidth.toLong() * boundsOpts.outHeight
            val sampleSize = if (imagePixels > maxPixels) {
                var s = 1
                while (imagePixels / (s * s) > maxPixels) s *= 2
                s
            } else 1

            val inputStream = context.contentResolver.openInputStream(uri) ?: return
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val original = try {
                BitmapFactory.decodeStream(inputStream, null, decodeOpts)
            } catch (e: OutOfMemoryError) {
                Log.e("CaptureManager", "OOM decoding image for post-process", e)
                inputStream.close()
                return
            }
            inputStream.close()
            if (original == null) return

            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            original.recycle()
            val canvas = Canvas(result)

            val pipelineStart = System.nanoTime()
            var cumulativeMs = 0L
            var nrMs = 0L
            var sharpMs = 0L
            var toneMs = 0L
            var shadowMs = 0L
            var beautyMs = 0L
            var highlightMs = 0L
            var bokehMs = 0L
            var vignetteMs = 0L

            if (style != PhotoStyle.NATURAL) {
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
            }

            if (isHdr) {
                try {
                    applyLocalToneMap(result, 0.5f)
                    Log.d("CaptureManager", "Local tone mapping (bilateral decomposition) applied")
                } catch (oom: OutOfMemoryError) {
                    Log.w("CaptureManager", "OOM in local tone map, skipping")
                    System.gc()
                }
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

            // Stage: Noise reduction (never skip)
            val nrStart = System.nanoTime()
            if (currentIso >= 400) {
                try {
                    NoiseReducer.apply(result, currentIso)
                } catch (oom: OutOfMemoryError) {
                    Log.w("CaptureManager", "OOM in noise reducer, skipping")
                    System.gc()
                }
            }
            nrMs = (System.nanoTime() - nrStart) / 1_000_000
            cumulativeMs += nrMs

            // Stage: Sharpening / Laplacian pyramid (never skip)
            val sharpStart = System.nanoTime()
            if (style != PhotoStyle.NATURAL) {
                try {
                    applySharpenLuminance(result, sceneType)
                } catch (oom: OutOfMemoryError) {
                    Log.w("CaptureManager", "OOM in sharpening, skipping")
                    System.gc()
                }
            }
            sharpMs = (System.nanoTime() - sharpStart) / 1_000_000
            cumulativeMs += sharpMs

            // Stage: Tone curve / 3D LUT (never skip)
            val toneStart = System.nanoTime()
            ToneCurveEngine.apply(result, style)
            toneMs = (System.nanoTime() - toneStart) / 1_000_000
            cumulativeMs += toneMs

            // Stage: Shadow recovery
            val shadowStart = System.nanoTime()
            if (sceneContrast > 0.15f) {
                val shadowStrength = HdrProcessor.computeShadowBoostStrength(sceneContrast)
                applyShadowRecovery(result, shadowStrength)
            }
            shadowMs = (System.nanoTime() - shadowStart) / 1_000_000
            cumulativeMs += shadowMs

            // Stage: Beauty
            if (cumulativeMs > PROCESSING_BUDGET_MS) {
                Log.w("Pipeline", "Budget exceeded after shadow recovery (${cumulativeMs}ms), skipping beauty, highlight rolloff, bokeh, vignette")
            } else {
                val beautyStart = System.nanoTime()
                if (beautyLevel > 0) {
                    try {
                        applyLabBeauty(result, canvas, beautyLevel, faceRects)
                    } catch (oom: OutOfMemoryError) {
                        Log.w("CaptureManager", "OOM in beauty processing, skipping")
                        System.gc()
                    }
                }
                beautyMs = (System.nanoTime() - beautyStart) / 1_000_000
                cumulativeMs += beautyMs

                // Stage: Highlight rolloff
                if (cumulativeMs > PROCESSING_BUDGET_MS) {
                    Log.w("Pipeline", "Budget exceeded after beauty (${cumulativeMs}ms), skipping highlight rolloff, bokeh, vignette")
                } else {
                    val highlightStart = System.nanoTime()
                    if (style != PhotoStyle.NATURAL) {
                        applyHighlightRolloffToBitmap(result, style)
                    }
                    highlightMs = (System.nanoTime() - highlightStart) / 1_000_000
                    cumulativeMs += highlightMs

                    // Stage: Bokeh
                    if (cumulativeMs > PROCESSING_BUDGET_MS) {
                        Log.w("Pipeline", "Budget exceeded after highlight rolloff (${cumulativeMs}ms), skipping bokeh, vignette")
                    } else {
                        val bokehStart = System.nanoTime()
                        if (isPortraitMode && faceRects.isNotEmpty()) {
                            try {
                                applyPortraitBokeh(result, canvas, faceRects)
                            } catch (oom: OutOfMemoryError) {
                                Log.w("CaptureManager", "OOM in bokeh, skipping")
                                System.gc()
                            }
                        }
                        bokehMs = (System.nanoTime() - bokehStart) / 1_000_000
                        cumulativeMs += bokehMs

                        // Stage: Vignette (lowest priority, skip first)
                        if (cumulativeMs > PROCESSING_BUDGET_MS) {
                            Log.w("Pipeline", "Budget exceeded after bokeh (${cumulativeMs}ms), skipping vignette")
                        } else {
                            val vignetteStart = System.nanoTime()
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
                            vignetteMs = (System.nanoTime() - vignetteStart) / 1_000_000
                            cumulativeMs += vignetteMs
                        }
                    }
                }
            }

            val totalMs = (System.nanoTime() - pipelineStart) / 1_000_000
            Log.d("Pipeline", "NR: ${nrMs}ms, Sharp: ${sharpMs}ms, Tone: ${toneMs}ms, Shadow: ${shadowMs}ms, Beauty: ${beautyMs}ms, Highlight: ${highlightMs}ms, Bokeh: ${bokehMs}ms, Vignette: ${vignetteMs}ms | Total: ${totalMs}ms")

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

            result.recycle()
            Log.d("CaptureManager", "Post-process applied: style=$style, beauty=$beautyLevel, front=$isFrontCamera")
        } catch (oom: OutOfMemoryError) {
            Log.e("CaptureManager", "OOM during post-processing, saving unprocessed", oom)
            System.gc()
        } catch (e: Exception) {
            Log.e("CaptureManager", "Post-process failed", e)
        }
    }

    private fun applyHighlightRolloffToBitmap(bitmap: Bitmap, style: PhotoStyle) {
        val params = ToneCurveEngine.styleHighlightParams(style)
        if (params.strength <= 0f) return

        val rolloff = ToneCurveEngine.buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = rolloff[(pixel shr 16) and 0xFF]
            val g = rolloff[(pixel shr 8) and 0xFF]
            val b = rolloff[pixel and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d("CaptureManager", "Highlight rolloff applied: style=$style, shoulder=${params.shoulderStart}, max=${params.maxOutput}")
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

        val maxDim = 960
        val needsDownsample = w > maxDim || h > maxDim
        val scaleFactor = if (needsDownsample) maxDim.toFloat() / maxOf(w, h) else 1f
        val sw = if (needsDownsample) (w * scaleFactor).toInt().coerceAtLeast(1) else w
        val sh = if (needsDownsample) (h * scaleFactor).toInt().coerceAtLeast(1) else h

        val smallBitmap = if (needsDownsample) {
            Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        } else null
        val smallPixels = IntArray(sw * sh)
        (smallBitmap ?: bitmap).getPixels(smallPixels, 0, sw, 0, 0, sw, sh)

        val luminance = FloatArray(sw * sh)
        val logLum = FloatArray(sw * sh)
        for (i in smallPixels.indices) {
            val r = ((smallPixels[i] shr 16) and 0xFF) / 255f
            val g = ((smallPixels[i] shr 8) and 0xFF) / 255f
            val b = (smallPixels[i] and 0xFF) / 255f
            luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).coerceAtLeast(0.001f)
            logLum[i] = kotlin.math.ln(luminance[i])
        }

        val base = FloatArray(sw * sh)
        System.arraycopy(logLum, 0, base, 0, logLum.size)
        bilateralApprox(base, logLum, sw, sh, spatialRadius = 5, rangeSigma = 0.4f)

        val detail = FloatArray(sw * sh)
        for (i in detail.indices) {
            detail[i] = logLum[i] - base[i]
        }

        val baseMean = base.average().toFloat()
        for (i in base.indices) {
            base[i] = baseMean + (base[i] - baseMean) * compressionFactor
        }

        val scaleMap = FloatArray(sw * sh)
        for (i in smallPixels.indices) {
            val newLum = kotlin.math.exp(base[i] + detail[i]).coerceIn(0.001f, 10f)
            scaleMap[i] = if (luminance[i] > 0.001f) (newLum / luminance[i]).coerceIn(0.2f, 5f) else 1f
        }

        smallBitmap?.recycle()

        val fullPixels = IntArray(w * h)
        bitmap.getPixels(fullPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val scale = if (needsDownsample) {
                    val sx = (x * scaleFactor).toInt().coerceIn(0, sw - 1)
                    val sy = (y * scaleFactor).toInt().coerceIn(0, sh - 1)
                    scaleMap[sy * sw + sx]
                } else {
                    scaleMap[i]
                }

                val r = ((fullPixels[i] shr 16) and 0xFF)
                val g = ((fullPixels[i] shr 8) and 0xFF)
                val b = (fullPixels[i] and 0xFF)

                val rOut = (r * scale).toInt().coerceIn(0, 255)
                val gOut = (g * scale).toInt().coerceIn(0, 255)
                val bOut = (b * scale).toInt().coerceIn(0, 255)

                fullPixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        bitmap.setPixels(fullPixels, 0, w, 0, 0, w, h)
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
        gaussianBlurPass(blurPixels, w, h, 15)

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

    private fun gaussianBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val kernel = generateGaussianKernel(radius)
        val temp = IntArray(pixels.size)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val p = pixels[y * w + sx]
                    val weight = kernel[k + radius]
                    sumR += ((p shr 16) and 0xFF) * weight
                    sumG += ((p shr 8) and 0xFF) * weight
                    sumB += (p and 0xFF) * weight
                }
                temp[y * w + x] = (0xFF shl 24) or
                    (sumR.toInt().coerceIn(0, 255) shl 16) or
                    (sumG.toInt().coerceIn(0, 255) shl 8) or
                    sumB.toInt().coerceIn(0, 255)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val p = temp[sy * w + x]
                    val weight = kernel[k + radius]
                    sumR += ((p shr 16) and 0xFF) * weight
                    sumG += ((p shr 8) and 0xFF) * weight
                    sumB += (p and 0xFF) * weight
                }
                pixels[y * w + x] = (0xFF shl 24) or
                    (sumR.toInt().coerceIn(0, 255) shl 16) or
                    (sumG.toInt().coerceIn(0, 255) shl 8) or
                    sumB.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun generateGaussianKernel(radius: Int): FloatArray {
        val sigma = radius / 2.5f
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in kernel.indices) {
            val x = (i - radius).toFloat()
            kernel[i] = kotlin.math.exp(-(x * x) / (2f * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
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
        private const val PROCESSING_BUDGET_MS = 2000L

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

        /**
         * 5x5 separable Gaussian blur on a float luminance channel.
         * Kernel: [1, 4, 6, 4, 1] / 16 per axis (sigma ~1.0).
         */
        private fun gaussianBlur5x5(input: FloatArray, w: Int, h: Int): FloatArray {
            val kernel = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)
            val temp = FloatArray(w * h)
            // Horizontal pass
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in -2..2) {
                        val sx = (x + k).coerceIn(0, w - 1)
                        sum += input[y * w + sx] * kernel[k + 2]
                    }
                    temp[y * w + x] = sum
                }
            }
            // Vertical pass
            val output = FloatArray(w * h)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var sum = 0f
                    for (k in -2..2) {
                        val sy = (y + k).coerceIn(0, h - 1)
                        sum += temp[sy * w + x] * kernel[k + 2]
                    }
                    output[y * w + x] = sum
                }
            }
            return output
        }

        /**
         * Downsample by 2x using 2x2 block averaging.
         * Returns (downsampled array, new width, new height).
         */
        private fun downsample2x(input: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
            val nw = w / 2
            val nh = h / 2
            val output = FloatArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    val sx = x * 2
                    val sy = y * 2
                    val a = input[sy * w + sx]
                    val b = if (sx + 1 < w) input[sy * w + sx + 1] else a
                    val c = if (sy + 1 < h) input[(sy + 1) * w + sx] else a
                    val d = if (sx + 1 < w && sy + 1 < h) input[(sy + 1) * w + sx + 1] else a
                    output[y * nw + x] = (a + b + c + d) * 0.25f
                }
            }
            return Triple(output, nw, nh)
        }

        /**
         * Bilinear upsample from (inW x inH) to (outW x outH).
         */
        private fun upsample2x(input: FloatArray, inW: Int, inH: Int, outW: Int, outH: Int): FloatArray {
            val output = FloatArray(outW * outH)
            val xRatio = if (outW > 1) (inW - 1).toFloat() / (outW - 1) else 0f
            val yRatio = if (outH > 1) (inH - 1).toFloat() / (outH - 1) else 0f
            for (y in 0 until outH) {
                val srcY = y * yRatio
                val y0 = srcY.toInt().coerceIn(0, inH - 1)
                val y1 = (y0 + 1).coerceIn(0, inH - 1)
                val fy = srcY - y0
                for (x in 0 until outW) {
                    val srcX = x * xRatio
                    val x0 = srcX.toInt().coerceIn(0, inW - 1)
                    val x1 = (x0 + 1).coerceIn(0, inW - 1)
                    val fx = srcX - x0
                    val topLeft = input[y0 * inW + x0]
                    val topRight = input[y0 * inW + x1]
                    val bottomLeft = input[y1 * inW + x0]
                    val bottomRight = input[y1 * inW + x1]
                    val top = topLeft + (topRight - topLeft) * fx
                    val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
                    output[y * outW + x] = top + (bottom - top) * fy
                }
            }
            return output
        }

        /**
         * 3-level Laplacian pyramid sharpening on the luminance channel.
         *
         * Pipeline: extract Y → build 3-level Laplacian pyramid →
         * boost fine (level 0) and mid (level 1) details → reconstruct →
         * write back Y and convert to RGB.
         */
        fun applySharpenLuminance(bitmap: android.graphics.Bitmap, sceneType: SceneType) {
            val strength = getSharpnessStrength(sceneType)
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            // --- Convert to YCbCr, extract channels ---
            val yChannel = FloatArray(w * h)
            val cbChannel = IntArray(w * h)
            val crChannel = IntArray(w * h)

            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val ycbcr = ColorSpaceUtils.rgbToYCbCr(r, g, b)
                yChannel[i] = ycbcr[0].toFloat()
                cbChannel[i] = ycbcr[1]
                crChannel[i] = ycbcr[2]
            }

            // --- Build 3-level Laplacian pyramid ---
            // Level 0: full resolution
            val blurred0 = gaussianBlur5x5(yChannel, w, h)
            val detail0 = FloatArray(w * h) { yChannel[it] - blurred0[it] }

            // Downsample blurred0 → level 1 input
            val (level1Input, w1, h1) = downsample2x(blurred0, w, h)

            // Level 1: half resolution
            val blurred1 = gaussianBlur5x5(level1Input, w1, h1)
            val detail1 = FloatArray(w1 * h1) { level1Input[it] - blurred1[it] }

            // Downsample blurred1 → level 2 (residual)
            val (level2Residual, w2, h2) = downsample2x(blurred1, w1, h1)

            // --- Boost detail levels scaled by scene strength ---
            // Normalize boosts relative to LANDSCAPE baseline (0.5)
            val baselineStrength = 0.5f
            val fineBoost = 0.12f * strength / baselineStrength
            val midBoost = 0.40f * strength / baselineStrength
            // Coarse (level 2): no boost

            // Apply boosts in place
            for (i in detail0.indices) {
                detail0[i] *= (1f + fineBoost)
            }
            for (i in detail1.indices) {
                detail1[i] *= (1f + midBoost)
            }

            // --- Reconstruct from pyramid ---
            // Upsample level 2 residual to level 1 size, add boosted detail 1
            val up2 = upsample2x(level2Residual, w2, h2, w1, h1)
            val reconstructed1 = FloatArray(w1 * h1) { up2[it] + detail1[it] }

            // Upsample reconstructed level 1 to level 0 size, add boosted detail 0
            val up1 = upsample2x(reconstructed1, w1, h1, w, h)
            val reconstructedY = FloatArray(w * h) { up1[it] + detail0[it] }

            // --- Write back to RGB ---
            for (i in pixels.indices) {
                val yVal = reconstructedY[i].toInt().coerceIn(0, 255)
                val rgb = ColorSpaceUtils.ycbcrToRgb(yVal, cbChannel[i], crChannel[i])
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
            if (beautyLevel <= 0) return
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val skinMask = buildSkinMask(pixels, w, h)

            // Extract luminance
            val luma = FloatArray(w * h) { i ->
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                0.299f * r + 0.587f * g + 0.114f * b
            }

            // Frequency separation
            val sigma = 5f + beautyLevel * 2f  // 7px at level 1, 9px at 2, 11px at 3
            val lowFreq = gaussianBlurLuma(luma, w, h, sigma)
            val highFreq = FloatArray(w * h) { luma[it] - lowFreq[it] }

            // Smooth only the low-frequency layer (removes blemishes, color unevenness)
            val smoothedLow = gaussianBlurLuma(lowFreq, w, h, sigma * 0.5f)

            // Recombine: smoothed low + original high (preserves texture)
            // Only apply to skin pixels
            for (i in pixels.indices) {
                if (!skinMask[i]) continue
                val newLuma = smoothedLow[i] + highFreq[i]
                val oldLuma = luma[i]
                if (oldLuma < 1f) continue
                val scale = (newLuma / oldLuma).coerceIn(0.7f, 1.3f)
                val r = (((pixels[i] shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((pixels[i] shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((pixels[i] and 0xFF) * scale).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun buildSkinMask(pixels: IntArray, w: Int, h: Int): BooleanArray {
            return BooleanArray(pixels.size) { i ->
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                // YCbCr skin detection thresholds
                val cb = (128f - 37.797f * r / 255f - 74.203f * g / 255f + 112f * b / 255f).toInt()
                val cr = (128f + 112f * r / 255f - 93.786f * g / 255f - 18.214f * b / 255f).toInt()
                cb in 77..127 && cr in 133..173
            }
        }

        private fun gaussianBlurLuma(input: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
            val radius = (sigma * 2.5f).toInt().coerceAtLeast(1)
            // Build 1D Gaussian kernel
            val kernel = FloatArray(radius * 2 + 1) { i ->
                val x = (i - radius).toFloat()
                kotlin.math.exp(-(x * x) / (2f * sigma * sigma)).toFloat()
            }
            val kernelSum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= kernelSum

            // Horizontal pass
            val temp = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in kernel.indices) {
                        val sx = (x + k - radius).coerceIn(0, w - 1)
                        sum += input[y * w + sx] * kernel[k]
                    }
                    temp[y * w + x] = sum
                }
            }

            // Vertical pass
            val output = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in kernel.indices) {
                        val sy = (y + k - radius).coerceIn(0, h - 1)
                        sum += temp[sy * w + x] * kernel[k]
                    }
                    output[y * w + x] = sum
                }
            }

            return output
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

    }
}
