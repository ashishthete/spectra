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
import com.spectra.core.model.CaptureRecipe
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
    private val focusStacker = FocusStacker()
    val zslBuffer = ZslRingBuffer(capacity = 5)
    private var neuralDenoiser: NeuralDenoiser? = null
    private var gpuContext: com.spectra.camera.gpu.GpuContext? = null

    fun initGpu() {
        if (gpuContext != null) return
        val ctx = com.spectra.camera.gpu.GpuContext()
        if (ctx.init()) {
            gpuContext = ctx
            hdrProcessor.setGpuContext(ctx)
            com.spectra.camera.gpu.GpuMertensFusion.loadShader(context.assets)
            NoiseReducer.initGpu()
            Log.d("CaptureManager", "GPU compute context ready, Mertens shader loaded")
        }
    }

    fun releaseGpu() {
        hdrProcessor.setGpuContext(null)
        NoiseReducer.releaseGpu()
        gpuContext?.release()
        gpuContext = null
    }

    fun initDepthModel() {
        val estimator = DepthEstimator(context)
        if (estimator.initialize()) {
            depthEstimator = estimator
            Log.d("CaptureManager", "Depth model loaded")
        } else {
            Log.w("CaptureManager", "Depth model unavailable — portrait bokeh will use gradient fallback")
        }
    }

    fun initNeuralDenoiser() {
        val denoiser = NeuralDenoiser()
        if (denoiser.init(context.assets)) {
            neuralDenoiser = denoiser
            Log.d("CaptureManager", "Neural denoiser loaded")
        } else {
            Log.w("CaptureManager", "Neural denoiser unavailable — using bilateral filter for noise reduction")
        }
    }

    fun releaseNeuralDenoiser() {
        neuralDenoiser?.release()
        neuralDenoiser = null
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

    data class CaptureResultMetadata(
        val actualFrameCount: Int,
        val didBurstMerge: Boolean,
        val didHdr: Boolean,
        val hdrFrameCount: Int = 0,
        val didFocusStack: Boolean = false,
        val didNeuralDenoise: Boolean = false,
        val fallbackReason: String? = null,
        val focusSweepPositions: List<Float> = emptyList()
    )

    data class HdrCaptureResult(
        val uri: String,
        val metadata: CaptureResultMetadata
    )

    data class ProcessingResult(
        val uri: String,
        val appliedStages: List<String> = emptyList(),
        val didNeuralDenoise: Boolean = false
    )

    data class SmartCaptureResultWithMeta(
        val result: SmartCaptureResult,
        val metadata: CaptureResultMetadata
    )

    suspend fun captureSmartPhoto(
        imageCapture: ImageCapture,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        isHdr: Boolean = false,
        faceRects: List<RectF> = emptyList(),
        processing: ProcessingParams = ProcessingParams(),
        currentIso: Int = 0,
        recipe: CaptureRecipe? = null,
        isStable: Boolean = false,
        gyroMotion: Float = 0f,
        lux: Float = Float.MAX_VALUE,
        thermalMaxFrames: Int = Int.MAX_VALUE,
        hasFaceMotion: Boolean = false,
        highContrast: Boolean = false,
        setFocusDistance: (suspend (Float) -> Unit)? = null,
        restoreAutoFocus: (suspend () -> Unit)? = null
    ): SmartCaptureResultWithMeta {
        val frameCount = when {
            recipe != null -> recipe.resolveFrameCount(
                isStable = isStable,
                gyroMotion = gyroMotion,
                iso = currentIso,
                lux = lux,
                thermalMaxFrames = thermalMaxFrames,
                hasFaceMotion = hasFaceMotion,
                highContrast = highContrast
            ).coerceAtLeast(1)
            isFrontCamera -> 1
            currentIso > 1200 -> 3
            else -> 1
        }
        val shouldBurstMerge = recipe?.useBurstMerge ?: (currentIso > 1200)
        Log.d("CaptureManager", "Resolved frame count: $frameCount (base=${recipe?.baseFrameCount}, max=${recipe?.maxFrameCount}, stable=$isStable, gyro=$gyroMotion, ISO=$currentIso)")

        if (frameCount <= 1) {
            val zslFrame = zslBuffer.getLatest()
            val zslAgeMs = if (zslFrame != null) (System.nanoTime() - zslFrame.timestampNs) / 1_000_000 else Long.MAX_VALUE
            val frame = if (zslFrame != null && zslFrame.jpegBytes != null && zslFrame.jpegBytes.isNotEmpty() && zslAgeMs in 0..1000) {
                Log.d("CaptureManager", "ZSL: using buffered frame (age=${zslAgeMs}ms)")
                Pair(zslFrame.jpegBytes, 0)
            } else {
                if (zslFrame != null) Log.d("CaptureManager", "ZSL: skipping stale frame (age=${zslAgeMs}ms), using live capture")
                captureInMemory(imageCapture)
            }
            val uri = saveJpegToMediaStore(frame.first, frame.second)
            Log.d("CaptureManager", "Single-frame capture saved: ${frame.first.size} bytes, ISO=$currentIso, recipe=${recipe?.preset}")
            return SmartCaptureResultWithMeta(
                result = SmartCaptureResult(bestOriginalUri = uri, allFrames = listOf(frame)),
                metadata = CaptureResultMetadata(actualFrameCount = 1, didBurstMerge = false, didHdr = false)
            )
        }

        val targetFrames = frameCount
        val isMacroSweep = recipe?.preset == com.spectra.core.model.CameraPreset.MACRO
                && targetFrames > 1 && setFocusDistance != null
        val focusSweep = if (isMacroSweep) {
            generateMacroFocusPositions(targetFrames)
        } else emptyList()

        val frames = mutableListOf<Pair<ByteArray, Int>>()
        try {
            for (i in 0 until targetFrames) {
                try {
                    if (isMacroSweep && i < focusSweep.size) {
                        setFocusDistance!!(focusSweep[i])
                        delay(150)
                    }
                    frames.add(captureInMemory(imageCapture))
                    if (i < targetFrames - 1 && !isMacroSweep) delay(60)
                } catch (e: Exception) {
                    Log.w("CaptureManager", "Smart capture frame $i failed", e)
                }
            }
        } finally {
            if (isMacroSweep) {
                restoreAutoFocus?.invoke()
            }
        }
        if (isMacroSweep) {
            Log.d("CaptureManager", "Macro focus sweep: ${focusSweep.size} positions, ${frames.size} captured")
        }
        if (frames.isEmpty()) {
            val frame = captureInMemory(imageCapture)
            val uri = saveJpegToMediaStore(frame.first, frame.second)
            return SmartCaptureResultWithMeta(
                result = SmartCaptureResult(bestOriginalUri = uri, allFrames = listOf(frame)),
                metadata = CaptureResultMetadata(actualFrameCount = 1, didBurstMerge = false, didHdr = false, fallbackReason = "all burst frames failed")
            )
        }

        val isMacroStack = recipe?.preset == com.spectra.core.model.CameraPreset.MACRO && frames.size > 1
        val didMerge = shouldBurstMerge && frames.size > 1
        var didFocusStack = false

        val merged = if (isMacroStack) {
            try {
                val stacked = withContext(Dispatchers.Default) { focusStackFrames(frames) }
                didFocusStack = true
                Log.d("CaptureManager", "Macro focus stack: ${frames.size} frames stacked")
                stacked
            } catch (e: Exception) {
                Log.w("CaptureManager", "Focus stack failed, falling back to burst merge", e)
                if (didMerge) {
                    try { withContext(Dispatchers.Default) { burstMerge(frames) } }
                    catch (_: OutOfMemoryError) { System.gc(); frames.maxBy { (jpeg, _) -> jpeg.size } }
                } else frames.maxBy { (jpeg, _) -> jpeg.size }
            }
        } else if (didMerge) {
            try {
                withContext(Dispatchers.Default) { burstMerge(frames) }
            } catch (oom: OutOfMemoryError) {
                Log.w("CaptureManager", "OOM during burst merge, falling back to best single frame")
                System.gc()
                frames.maxBy { (jpeg, _) -> jpeg.size }
            }
        } else {
            frames.maxBy { (jpeg, _) -> jpeg.size }
        }
        val uri = saveJpegToMediaStore(merged.first, merged.second)
        Log.d("CaptureManager", "Capture: ${frames.size} frames, merged=$didMerge, focusStack=$didFocusStack, recipe=${recipe?.preset}, ISO=$currentIso")
        return SmartCaptureResultWithMeta(
            result = SmartCaptureResult(bestOriginalUri = uri, allFrames = frames),
            metadata = CaptureResultMetadata(
                actualFrameCount = frames.size, didBurstMerge = didMerge, didHdr = false,
                didFocusStack = didFocusStack,
                focusSweepPositions = if (isMacroSweep) focusSweep.take(frames.size) else emptyList()
            )
        )
    }

    private fun generateMacroFocusPositions(frameCount: Int): List<Float> {
        val nearM = 0.04f
        val farM = 0.15f
        if (frameCount <= 1) return listOf((nearM + farM) / 2f)
        return (0 until frameCount).map { i ->
            nearM + (farM - nearM) * i / (frameCount - 1)
        }
    }

    private fun focusStackFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val bitmaps = frames.map { (jpeg, _) ->
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val pixelArrays = bitmaps.map { bmp ->
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            pixels
        }

        val stacked = focusStacker.stack(pixelArrays, w, h)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(stacked, 0, w, 0, 0, w, h)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        result.recycle()
        return Pair(stream.toByteArray(), frames[0].second)
    }

    private fun burstMerge(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        val refIdx = pickSharpestIdx(frames)
        val rotation = frames[refIdx].second

        val refBitmap = BitmapFactory.decodeByteArray(
            frames[refIdx].first, 0, frames[refIdx].first.size
        ) ?: return frames[refIdx]
        val w = refBitmap.width
        val h = refBitmap.height
        val n = w * h

        val refPixels = IntArray(n)
        refBitmap.getPixels(refPixels, 0, w, 0, 0, w, h)
        refBitmap.recycle()

        val refGray = IntArray(n)
        for (i in 0 until n) {
            val r = (refPixels[i] shr 16) and 0xFF
            val g = (refPixels[i] shr 8) and 0xFF
            val b = refPixels[i] and 0xFF
            refGray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        val tileSize = 32
        val tilesX = w / tileSize
        val tilesY = h / tileSize

        val sumR = FloatArray(n)
        val sumG = FloatArray(n)
        val sumB = FloatArray(n)
        val weightSum = FloatArray(n)
        for (i in 0 until n) {
            sumR[i] = ((refPixels[i] shr 16) and 0xFF).toFloat()
            sumG[i] = ((refPixels[i] shr 8) and 0xFF).toFloat()
            sumB[i] = (refPixels[i] and 0xFF).toFloat()
            weightSum[i] = 1f
        }

        val framePixels = IntArray(n)
        val frameGray = IntArray(n)
        for (i in frames.indices) {
            if (i == refIdx) continue

            val bitmap = BitmapFactory.decodeByteArray(
                frames[i].first, 0, frames[i].first.size
            ) ?: continue
            if (bitmap.width != w || bitmap.height != h) {
                bitmap.recycle()
                continue
            }
            bitmap.getPixels(framePixels, 0, w, 0, 0, w, h)
            bitmap.recycle()

            for (j in 0 until n) {
                val r = (framePixels[j] shr 16) and 0xFF
                val g = (framePixels[j] shr 8) and 0xFF
                val b = framePixels[j] and 0xFF
                frameGray[j] = (r * 77 + g * 150 + b * 29) shr 8
            }

            val tileOffsets = Array(max(tilesY, 1)) { IntArray(max(tilesX, 1) * 2) }
            val tileConfidence = Array(max(tilesY, 1)) { FloatArray(max(tilesX, 1)) }

            for (ty in 0 until max(tilesY, 1)) {
                for (tx in 0 until max(tilesX, 1)) {
                    val result = alignTile(refGray, frameGray, w, h, tx * tileSize, ty * tileSize, tileSize)
                    tileOffsets[ty][tx * 2] = result.first
                    tileOffsets[ty][tx * 2 + 1] = result.second
                    tileConfidence[ty][tx] = result.third
                }
            }

            for (row in 0 until h) {
                for (col in 0 until w) {
                    val tx = min(col / tileSize, max(tilesX - 1, 0))
                    val ty = min(row / tileSize, max(tilesY - 1, 0))
                    val conf = tileConfidence[ty][tx]
                    if (conf < 0.3f) continue

                    val dx = tileOffsets[ty][tx * 2]
                    val dy = tileOffsets[ty][tx * 2 + 1]
                    val srcRow = row + dy
                    val srcCol = col + dx
                    if (srcRow < 0 || srcRow >= h || srcCol < 0 || srcCol >= w) continue

                    val j = row * w + col
                    val srcJ = srcRow * w + srcCol

                    val lum = refGray[j]
                    val motionThreshold = when {
                        lum < 40 -> 45
                        lum < 100 -> 35
                        else -> 25
                    }
                    val lumDiff = abs(refGray[j] - frameGray[srcJ])
                    if (lumDiff > motionThreshold) continue

                    sumR[j] += ((framePixels[srcJ] shr 16) and 0xFF) * conf
                    sumG[j] += ((framePixels[srcJ] shr 8) and 0xFF) * conf
                    sumB[j] += (framePixels[srcJ] and 0xFF) * conf
                    weightSum[j] += conf
                }
            }
            Log.d("CaptureManager", "Frame $i: tile-aligned ${max(tilesX,1)}x${max(tilesY,1)} tiles")
        }

        val avgPixels = IntArray(n)
        for (i in 0 until n) {
            val w2 = weightSum[i]
            val r = (sumR[i] / w2 + 0.5f).toInt().coerceIn(0, 255)
            val g = (sumG[i] / w2 + 0.5f).toInt().coerceIn(0, 255)
            val b = (sumB[i] / w2 + 0.5f).toInt().coerceIn(0, 255)
            avgPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        merged.setPixels(avgPixels, 0, w, 0, 0, w, h)

        val stream = java.io.ByteArrayOutputStream()
        merged.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        merged.recycle()
        Log.d("CaptureManager", "Burst merged ${frames.size} frames (tile-aligned), ${w}x${h}")
        return Pair(stream.toByteArray(), rotation)
    }

    private fun alignTile(
        refGray: IntArray, altGray: IntArray,
        w: Int, h: Int,
        tileX: Int, tileY: Int, tileSize: Int
    ): Triple<Int, Int, Float> {
        val searchRadius = 4
        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE
        var secondBestSad = Long.MAX_VALUE

        val rowEnd = min(tileY + tileSize, h)
        val colEnd = min(tileX + tileSize, w)

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0L
                var count = 0
                for (row in tileY until rowEnd) {
                    val srcRow = row + dy
                    if (srcRow < 0 || srcRow >= h) continue
                    for (col in tileX until colEnd) {
                        val srcCol = col + dx
                        if (srcCol < 0 || srcCol >= w) continue
                        sad += abs(refGray[row * w + col] - altGray[srcRow * w + srcCol])
                        count++
                    }
                }
                if (count > 0) {
                    val avgSad = sad / count
                    if (avgSad < bestSad) {
                        secondBestSad = bestSad
                        bestSad = avgSad
                        bestDx = dx
                        bestDy = dy
                    } else if (avgSad < secondBestSad) {
                        secondBestSad = avgSad
                    }
                }
            }
        }

        val confidence = if (bestSad < 1) 1f
        else if (secondBestSad <= bestSad) 0f
        else (1f - bestSad.toFloat() / secondBestSad.toFloat()).coerceIn(0f, 1f)

        return Triple(bestDx, bestDy, confidence)
    }

    private fun sigmaClippedMean(vals: IntArray, count: Int): Int {
        if (count <= 2) {
            var s = 0
            for (i in 0 until count) s += vals[i]
            return (s / count).coerceIn(0, 255)
        }
        vals.sort(0, count)
        val median = vals[count / 2]
        var sum = 0
        var n = 0
        for (i in 0 until count) {
            if (abs(vals[i] - median) <= 40) {
                sum += vals[i]
                n++
            }
        }
        return if (n > 0) (sum / n).coerceIn(0, 255) else median.coerceIn(0, 255)
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

    fun pickBestBurstFrame(frames: List<Pair<ByteArray, Int>>, faceRects: List<RectF> = emptyList()): Int {
        if (frames.size <= 1) return 0
        var bestScore = -1f
        var bestIdx = 0
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        for (i in frames.indices) {
            val thumb = BitmapFactory.decodeByteArray(
                frames[i].first, 0, frames[i].first.size, opts
            ) ?: continue
            var score = measureSharpness(thumb)
            if (faceRects.isNotEmpty()) {
                val faceSharpness = measureFaceSharpness(thumb, faceRects)
                score = score * 0.4f + faceSharpness * 0.6f
            }
            thumb.recycle()
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun measureFaceSharpness(bitmap: Bitmap, faceRects: List<RectF>): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var totalEdge = 0.0
        var totalCount = 0
        for (face in faceRects) {
            val left = (face.left * w).toInt().coerceIn(1, w - 2)
            val top = (face.top * h).toInt().coerceIn(1, h - 2)
            val right = (face.right * w).toInt().coerceIn(left + 1, w - 1)
            val bottom = (face.bottom * h).toInt().coerceIn(top + 1, h - 1)
            for (y in top until bottom) {
                for (x in left until right) {
                    val c = lum(pixels[y * w + x])
                    val t = lum(pixels[(y - 1) * w + x])
                    val b = lum(pixels[(y + 1) * w + x])
                    val l = lum(pixels[y * w + (x - 1)])
                    val r = lum(pixels[y * w + (x + 1)])
                    totalEdge += abs(4 * c - t - b - l - r)
                    totalCount++
                }
            }
        }
        return if (totalCount > 0) (totalEdge / totalCount).toFloat() else 0f
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
        preset: String = "AUTO",
        enableNeuralDenoise: Boolean = false,
        portraitLightingMode: PortraitLighting.LightingMode = PortraitLighting.LightingMode.NATURAL
    ): ProcessingResult {
        if (preset == "TRUE_SCENE") {
            Log.d("Pipeline", "TRUE_SCENE mode — skipping all AI processing")
            return ProcessingResult(rawUri)
        }
        return withContext(Dispatchers.Default) {
            try {
                val pipelineStart = System.nanoTime()
                val sourceUri = Uri.parse(rawUri)

                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext ProcessingResult(rawUri)
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (original == null) return@withContext ProcessingResult(rawUri)
                Log.d("Pipeline", "DECODE: ${(System.nanoTime() - pipelineStart) / 1_000_000}ms, ${original.width}x${original.height}")

                val origScore = ImageEnhancer.scoreQuality(original)

                val stages = mutableListOf<String>()

                if (captureIso >= 400) {
                    try {
                        if (faceRects.isNotEmpty() && (isPortraitMode || preset == "PORTRAIT" || preset == "PORT")) {
                            NoiseReducer.applySkinSelective(original, captureIso, faceRects)
                        } else {
                            NoiseReducer.apply(original, captureIso)
                        }
                        stages.add("noise_reduction")
                    } catch (_: OutOfMemoryError) { System.gc() }
                }

                val params = ImageEnhancer.EnhanceParams.forPreset(preset, captureIso, sceneContrast, isFrontCamera)
                val enhanced = ImageEnhancer.enhance(original, params)
                original.recycle()

                val canvas = Canvas(enhanced)

                if (enableNeuralDenoise && captureIso >= 800 && neuralDenoiser?.isAvailable == true) {
                    try {
                        val w = enhanced.width; val h = enhanced.height
                        val pixels = IntArray(w * h)
                        enhanced.getPixels(pixels, 0, w, 0, 0, w, h)
                        val lum = FloatArray(w * h) { i ->
                            val r = (pixels[i] shr 16) and 0xFF
                            val g = (pixels[i] shr 8) and 0xFF
                            val b = pixels[i] and 0xFF
                            0.299f * r + 0.587f * g + 0.114f * b
                        }
                        val noiseLevel = (captureIso / 12800f).coerceIn(0.02f, 0.15f)
                        val denoised = neuralDenoiser!!.denoise(lum, w, h, noiseLevel)
                        for (i in pixels.indices) {
                            val origLum = lum[i].coerceAtLeast(1f)
                            val scale = denoised[i] / origLum
                            val r = ((pixels[i] shr 16 and 0xFF) * scale).toInt().coerceIn(0, 255)
                            val g = ((pixels[i] shr 8 and 0xFF) * scale).toInt().coerceIn(0, 255)
                            val b = ((pixels[i] and 0xFF) * scale).toInt().coerceIn(0, 255)
                            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        enhanced.setPixels(pixels, 0, w, 0, 0, w, h)
                        stages.add("neural_denoise")
                        Log.d("Pipeline", "Neural denoise applied: ISO=$captureIso, noise=$noiseLevel")
                    } catch (e: Exception) {
                        Log.w("Pipeline", "Neural denoise failed", e)
                    } catch (_: OutOfMemoryError) { System.gc() }
                }

                val hasFaces = faceRects.isNotEmpty()
                if (!isHdr) {
                    ToneCurveEngine.apply(enhanced, style, skinHueProtection = hasFaces, chromaCompression = if (isHdr) 0.3f else 0f, faceRects = faceRects)
                    stages.add("tone_curve")
                } else {
                    try {
                        LocalToneMapper.apply(enhanced, strength = 0.7f, gamma = 0.85f, shadowLift = 0.1f)
                        stages.add("local_tone_map")
                        ToneCurveEngine.apply(enhanced, style, skinHueProtection = hasFaces, chromaCompression = 0.3f, faceRects = faceRects)
                    }
                    catch (_: OutOfMemoryError) { System.gc() }
                }

                if (sceneContrast > 0.15f) {
                    val shadowStrength = HdrProcessor.computeShadowBoostStrength(sceneContrast)
                    applyShadowRecovery(enhanced, shadowStrength)
                    stages.add("shadow_recovery")
                }

                if (sceneContrast > 0.2f && !isPortraitMode) {
                    try {
                        val sw = enhanced.width; val sh = enhanced.height
                        val skyPixels = IntArray(sw * sh)
                        enhanced.getPixels(skyPixels, 0, sw, 0, 0, sw, sh)
                        val skyMask = SkySegmenter.detectSkyMask(skyPixels, sw, sh)
                        val skyFraction = SkySegmenter.computeSkyFraction(skyMask)
                        if (skyFraction in 0.05f..0.7f) {
                            SkySegmenter.applyGndFilter(skyPixels, sw, sh, skyMask, (sceneContrast * 0.8f).coerceIn(0.2f, 0.6f))
                            SkySegmenter.applySkyRecovery(skyPixels, sw, sh, skyMask)
                            enhanced.setPixels(skyPixels, 0, sw, 0, 0, sw, sh)
                            stages.add("sky_gnd")
                        }
                    } catch (_: Exception) {}
                }

                if (beautyLevel > 0 && faceRects.isNotEmpty()) {
                    try {
                        if (isPortraitMode) {
                            // Frequency-separation beauty: preserves pores/detail, smooths blemishes
                            val bw = enhanced.width; val bh = enhanced.height
                            val bPixels = IntArray(bw * bh)
                            enhanced.getPixels(bPixels, 0, bw, 0, 0, bw, bh)
                            val bSkinMask = BeautyProcessor.buildSkinMask(bPixels, bw, bh, faceRects)
                            val bStrength = beautyLevel / 3f  // 1->0.33, 2->0.67, 3->1.0
                            BeautyProcessor.process(bPixels, bw, bh, bSkinMask, bStrength)
                            enhanced.setPixels(bPixels, 0, bw, 0, 0, bw, bh)
                            stages.add("freq_sep_beauty")
                        } else {
                            applyLabBeauty(enhanced, canvas, beautyLevel, faceRects)
                            stages.add("beauty")
                        }
                    } catch (_: OutOfMemoryError) { System.gc() }
                }

                if (style != PhotoStyle.NATURAL) {
                    applyHighlightRolloffToBitmap(enhanced, style)
                    stages.add("highlight_rolloff")
                }

                // LUT color grading: from ProcessingParams or auto-mapped from PhotoStyle
                val lutName = processing.colorGradingLut ?: LutEngine.lutForStyle(style.name)
                if (lutName != null && lutName != "NEUTRAL") {
                    try {
                        val lw = enhanced.width; val lh = enhanced.height
                        val lutPixels = IntArray(lw * lh)
                        enhanced.getPixels(lutPixels, 0, lw, 0, 0, lw, lh)
                        LutEngine.apply(lutPixels, lw, lh, lutName, strength = 0.6f)
                        enhanced.setPixels(lutPixels, 0, lw, 0, 0, lw, lh)
                        stages.add("lut_$lutName")
                        Log.d("Pipeline", "LUT applied: $lutName @ 0.6 strength")
                    } catch (_: OutOfMemoryError) { System.gc() }
                }

                if (captureIso < 3200) {
                    try {
                        val sharpParams = LaplacianSharpener.SharpParams.forIso(captureIso)
                        LaplacianSharpener.sharpen(enhanced, sharpParams)
                        stages.add("laplacian_sharpen")
                    } catch (_: OutOfMemoryError) { System.gc() }
                }

                if (isPortraitMode && faceRects.isNotEmpty()) {
                    try { applyPortraitBokeh(enhanced, canvas, faceRects); stages.add("portrait_bokeh") }
                    catch (_: OutOfMemoryError) { System.gc() }

                    val estimator = depthEstimator
                    if (estimator != null && estimator.isAvailable()) {
                        try {
                            val ew = enhanced.width; val eh = enhanced.height
                            val epixels = IntArray(ew * eh)
                            enhanced.getPixels(epixels, 0, ew, 0, 0, ew, eh)
                            val depthMap = estimator.estimateDepth(enhanced)
                            if (depthMap != null) {
                                val inputSize = estimator.getInputSize()
                                val subjectMask = DepthBokeh.generateSubjectMask(
                                    depthMap, inputSize, faceRects, ew, eh, epixels
                                )
                                applyDepthSelectiveWarmth(epixels, subjectMask, ew, eh)
                                if (portraitLightingMode != PortraitLighting.LightingMode.NATURAL) {
                                    PortraitLighting.apply(epixels, ew, eh, subjectMask, depthMap, portraitLightingMode)
                                    stages.add("portrait_lighting")
                                }
                                enhanced.setPixels(epixels, 0, ew, 0, 0, ew, eh)
                                stages.add("depth_mask")
                            }
                        } catch (e: Exception) {
                            Log.w("Pipeline", "Semantic depth masking failed", e)
                        } catch (_: OutOfMemoryError) { System.gc() }
                    }
                }

                val enhScore = ImageEnhancer.scoreQuality(enhanced)
                Log.d("Pipeline", "Quality: orig=%.3f, ai=%.3f, stages=${stages.joinToString(",")}, total=${(System.nanoTime() - pipelineStart) / 1_000_000}ms".format(origScore, enhScore))

                if (enhScore < origScore - 0.02f) {
                    Log.d("Pipeline", "AI worse than original, keeping original (skipped: ${stages.joinToString(",")})")
                    enhanced.recycle()
                    return@withContext ProcessingResult(rawUri)
                }

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
                Log.d("Pipeline", "SAVE: done, total=${(System.nanoTime() - pipelineStart) / 1_000_000}ms, ${stream.size()} bytes")
                val didNeural = "neural_denoise" in stages
                ProcessingResult(
                    uri = copyUri.ifEmpty { rawUri },
                    appliedStages = stages.toList(),
                    didNeuralDenoise = didNeural
                )
            } catch (e: Exception) {
                Log.w("Pipeline", "Enhancement FAILED: ${e.message}", e)
                ProcessingResult(rawUri)
            } catch (oom: OutOfMemoryError) {
                Log.w("Pipeline", "Enhancement OOM")
                System.gc()
                ProcessingResult(rawUri)
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

        LocalToneMapper.apply(result, strength = 0.7f, gamma = 0.85f, shadowLift = 0.1f)
        applyEnhanceColors(result)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 97, stream)
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
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(w * h)
        val rVals = IntArray(validCount)
        val gVals = IntArray(validCount)
        val bVals = IntArray(validCount)
        for (i in 0 until w * h) {
            for (f in alignedFrames.indices) {
                rVals[f] = (alignedFrames[f][i] shr 16) and 0xFF
                gVals[f] = (alignedFrames[f][i] shr 8) and 0xFF
                bVals[f] = alignedFrames[f][i] and 0xFF
            }
            outPixels[i] = (0xFF shl 24) or
                    (sigmaClippedMean(rVals, validCount) shl 16) or
                    (sigmaClippedMean(gVals, validCount) shl 8) or
                    sigmaClippedMean(bVals, validCount)
        }
        result.setPixels(outPixels, 0, w, 0, 0, w, h)

        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        val jpegBytes = stream.toByteArray()

        bitmaps.forEach { it.recycle() }
        result.recycle()

        return Pair(jpegBytes, frames[0].second)
    }

    suspend fun captureInMemory(imageCapture: ImageCapture): Pair<ByteArray, Int> {
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
                        val ts = image.imageInfo.timestamp
                        image.close()
                        zslBuffer.push(ZslRingBuffer.ZslFrame(
                            timestampNs = System.nanoTime(), iso = 0, exposureNs = 0,
                            jpegBytes = bytes, yuvBytes = null, width = 0, height = 0
                        ))
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

    suspend fun saveOriginalJpegFallback(jpegBytes: ByteArray, rotationDegrees: Int): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SPECTRA_ORIGINAL_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Spectra/Originals")
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
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "SPECTRA Original JPEG — RAW unavailable on this lens")
                    exif.saveAttributes()
                }
            } catch (e: Exception) {
                Log.w("CaptureManager", "Original EXIF write failed", e)
            }

            Log.d("CaptureManager", "Original JPEG fallback saved: $uri")
            uri.toString()
        }
    }

    suspend fun captureHdrBracketEv(
        imageCapture: ImageCapture,
        evOffsets: List<Float>,
        applyEvOffset: suspend (Float) -> Unit,
        restoreAutoExposure: suspend () -> Unit,
        beautyLevel: Int = 0,
        style: PhotoStyle = PhotoStyle.NATURAL,
        isFrontCamera: Boolean = false,
        faceRects: List<android.graphics.RectF> = emptyList(),
        isPortraitMode: Boolean = false
    ): HdrCaptureResult {
        val frames = mutableListOf<Pair<ByteArray, Int>>()
        try {
            for (ev in evOffsets) {
                applyEvOffset(ev)
                delay(300)
                try {
                    val frame = captureInMemory(imageCapture)
                    frames.add(frame)
                } catch (e: Exception) {
                    Log.w("CaptureManager", "HDR EV bracket frame failed (EV=$ev)", e)
                }
            }
        } finally {
            restoreAutoExposure()
        }

        if (frames.isEmpty()) throw androidx.camera.core.ImageCaptureException(0, "All HDR frames failed", null)

        if (frames.size < 2) {
            val uri = saveJpegToMediaStore(frames[0].first, frames[0].second, "_HDR")
            return HdrCaptureResult(uri, CaptureResultMetadata(
                actualFrameCount = frames.size, didBurstMerge = false, didHdr = false,
                hdrFrameCount = 0, fallbackReason = "only ${frames.size} of ${evOffsets.size} bracket frames captured"
            ))
        }

        val merged = withContext(Dispatchers.Default) { mergeHdrFrames(frames) }
        val suffix = if (evOffsets.size >= 5) "_HDR5" else "_HDR"
        val uri = saveJpegToMediaStore(merged.first, merged.second, suffix)
        Log.d("CaptureManager", "HDR EV bracket: ${frames.size}/${evOffsets.size} frames merged")
        return HdrCaptureResult(uri, CaptureResultMetadata(
            actualFrameCount = frames.size, didBurstMerge = false, didHdr = true,
            hdrFrameCount = frames.size
        ))
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
    ): HdrCaptureResult {
        val brackets = if (evBias != 0f) {
            HdrProcessor.computeBracketExposuresForHighlights(baseExposureNs, baseIso, evBias)
        } else {
            HdrProcessor.computeBracketExposures(baseExposureNs, baseIso)
        }
        var frames = mutableListOf<Pair<ByteArray, Int>>()

        // ZSL frames are all captured at the same auto-exposure, so they cannot be used
        // as bracket frames with different exposures. Always capture fresh bracket frames.
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
            return HdrCaptureResult(uri, CaptureResultMetadata(
                actualFrameCount = frames.size, didBurstMerge = false, didHdr = false,
                hdrFrameCount = 0, fallbackReason = "only ${frames.size} of ${brackets.size} bracket frames captured"
            ))
        }

        val merged = withContext(Dispatchers.Default) {
            mergeHdrFrames(frames)
        }

        val uri = saveJpegToMediaStore(merged.first, merged.second, "_HDR")
        Log.d("CaptureManager", "HDR bracket: ${frames.size}/${brackets.size} frames merged")
        return HdrCaptureResult(uri, CaptureResultMetadata(
            actualFrameCount = frames.size, didBurstMerge = false, didHdr = true,
            hdrFrameCount = frames.size
        ))
    }

    suspend fun captureHdrBracket5Frame(
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
    ): HdrCaptureResult {
        val brackets = HdrProcessor.computeBracketExposures5Frame(baseExposureNs, baseIso)
        var frames = mutableListOf<Pair<ByteArray, Int>>()

        // ZSL frames are all same-exposure — cannot substitute for bracket frames.
        try {
            for ((exposureNs, iso) in brackets) {
                applyBracketSettings(exposureNs, iso)
                delay(300)
                try {
                    val frame = captureInMemory(imageCapture)
                    frames.add(frame)
                } catch (e: Exception) {
                    Log.w("CaptureManager", "HDR 5-frame bracket frame failed", e)
                }
            }
        } finally {
            restoreAutoExposure()
        }

        if (frames.isEmpty()) throw androidx.camera.core.ImageCaptureException(0, "All HDR frames failed", null)

        if (frames.size < 2) {
            val uri = saveJpegToMediaStore(frames[0].first, frames[0].second, "_HDR5")
            return HdrCaptureResult(uri, CaptureResultMetadata(
                actualFrameCount = frames.size, didBurstMerge = false, didHdr = false,
                hdrFrameCount = 0, fallbackReason = "only ${frames.size} of ${brackets.size} bracket frames captured"
            ))
        }

        val merged = withContext(Dispatchers.Default) {
            mergeHdrFrames(frames)
        }

        val uri = saveJpegToMediaStore(merged.first, merged.second, "_HDR5")
        Log.d("CaptureManager", "HDR 5-frame bracket: ${frames.size}/${brackets.size} frames merged")
        return HdrCaptureResult(uri, CaptureResultMetadata(
            actualFrameCount = frames.size, didBurstMerge = false, didHdr = true,
            hdrFrameCount = frames.size
        ))
    }

    private fun mergeHdrFrames(frames: List<Pair<ByteArray, Int>>): Pair<ByteArray, Int> {
        if (frames.size < 2) return frames.first()

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frames[0].first, 0, frames[0].first.size, boundsOpts)
        val fullW = boundsOpts.outWidth
        val fullH = boundsOpts.outHeight
        val imagePixels = fullW.toLong() * fullH

        val maxFusionPixels = 6_000_000L
        val sampleSize = if (imagePixels > maxFusionPixels) {
            var s = 1
            while (imagePixels / (s * s) > maxFusionPixels) s *= 2
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

        val subW = bitmaps[0].width
        val subH = bitmaps[0].height

        val hdrProcessor = HdrProcessor()
        val pixelArrays = bitmaps.map { bmp ->
            val px = IntArray(subW * subH)
            bmp.getPixels(px, 0, subW, 0, 0, subW, subH)
            px
        }
        bitmaps.forEach { it.recycle() }

        val refIdx = pixelArrays.size / 2
        val aligned = pixelArrays.mapIndexed { idx, px ->
            if (idx == refIdx) px
            else hdrProcessor.alignFrame(pixelArrays[refIdx], px, subW, subH)
        }

        val fused = hdrProcessor.semanticMertensFusion(aligned, subW, subH)

        if (sampleSize <= 1) {
            val result = Bitmap.createBitmap(subW, subH, Bitmap.Config.ARGB_8888)
            result.setPixels(fused, 0, subW, 0, 0, subW, subH)
            val stream = java.io.ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 97, stream)
            result.recycle()
            Log.d("CaptureManager", "HDR Mertens fusion: ${aligned.size} frames, ${subW}x${subH}")
            return Pair(stream.toByteArray(), frames[0].second)
        }

        val baseRef = aligned[refIdx]
        val ratioR = FloatArray(subW * subH)
        val ratioG = FloatArray(subW * subH)
        val ratioB = FloatArray(subW * subH)
        for (i in 0 until subW * subH) {
            val br = ((baseRef[i] shr 16) and 0xFF).coerceAtLeast(1)
            val bg = ((baseRef[i] shr 8) and 0xFF).coerceAtLeast(1)
            val bb = (baseRef[i] and 0xFF).coerceAtLeast(1)
            ratioR[i] = (((fused[i] shr 16) and 0xFF).toFloat() / br).coerceIn(0f, 4f)
            ratioG[i] = (((fused[i] shr 8) and 0xFF).toFloat() / bg).coerceIn(0f, 4f)
            ratioB[i] = ((fused[i] and 0xFF).toFloat() / bb).coerceIn(0f, 4f)
        }

        val fullBase = BitmapFactory.decodeByteArray(frames[refIdx].first, 0, frames[refIdx].first.size)
            ?: return Pair(frames[refIdx].first, frames[refIdx].second)
        val fullPixels = IntArray(fullW * fullH)
        fullBase.getPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
        fullBase.recycle()

        for (y in 0 until fullH) {
            for (x in 0 until fullW) {
                val srcX = x.toFloat() / fullW * subW - 0.5f
                val srcY = y.toFloat() / fullH * subH - 0.5f
                val x0 = srcX.toInt().coerceIn(0, subW - 2)
                val y0 = srcY.toInt().coerceIn(0, subH - 2)
                val fx = (srcX - x0).coerceIn(0f, 1f)
                val fy = (srcY - y0).coerceIn(0f, 1f)

                fun bilinear(arr: FloatArray): Float {
                    val v00 = arr[y0 * subW + x0]; val v10 = arr[y0 * subW + x0 + 1]
                    val v01 = arr[(y0 + 1) * subW + x0]; val v11 = arr[(y0 + 1) * subW + x0 + 1]
                    return v00 * (1f - fx) * (1f - fy) + v10 * fx * (1f - fy) + v01 * (1f - fx) * fy + v11 * fx * fy
                }

                val i = y * fullW + x
                val origR = (fullPixels[i] shr 16) and 0xFF
                val origG = (fullPixels[i] shr 8) and 0xFF
                val origB = fullPixels[i] and 0xFF
                val r = (origR * bilinear(ratioR)).toInt().coerceIn(0, 255)
                val g = (origG * bilinear(ratioG)).toInt().coerceIn(0, 255)
                val b = (origB * bilinear(ratioB)).toInt().coerceIn(0, 255)
                fullPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val result = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        result.setPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
        val stream = java.io.ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        result.recycle()

        Log.d("CaptureManager", "HDR ratio-map fusion: ${aligned.size} frames, ${fullW}x${fullH} (fused at ${subW}x${subH})")
        return Pair(stream.toByteArray(), frames[0].second)
    }

    suspend fun mergeAndSaveHdr(frames: List<Pair<ByteArray, Int>>): String {
        return withContext(Dispatchers.Default) {
            val merged = mergeHdrFrames(frames)
            val uri = saveJpegToMediaStore(merged.first, merged.second, "_HDR")
            Log.d("CaptureManager", "HDR EV bracket: ${frames.size} frames merged")
            uri
        }
    }

    suspend fun saveJpegFrame(frame: Pair<ByteArray, Int>, suffix: String = ""): String {
        return saveJpegToMediaStore(frame.first, frame.second, suffix)
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

        if (com.spectra.camera.gpu.ShaderPipeline.applyShadowRecovery(bitmap, strength)) {
            Log.d("CaptureManager", "Shadow recovery (AGSL GPU): strength=${"%.2f".format(strength)}")
            return
        }

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
        Log.d("CaptureManager", "Shadow recovery (CPU): strength=${"%.2f".format(strength)}")
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
                    LocalToneMapper.apply(result, strength = 0.7f, gamma = 0.85f, shadowLift = 0.1f)
                    Log.d("CaptureManager", "Local tone mapping applied")
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

            // Stage: Tone curve / 3D LUT (skip for HDR — already local-tone-mapped)
            val toneStart = System.nanoTime()
            if (!isHdr) {
                ToneCurveEngine.apply(result, style)
            }
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

            // Stage: Sky GND filter
            if (sceneContrast > 0.2f && !isPortraitMode) {
                try {
                    val sw = result.width; val sh = result.height
                    val skyPixels = IntArray(sw * sh)
                    result.getPixels(skyPixels, 0, sw, 0, 0, sw, sh)
                    val skyMask = SkySegmenter.detectSkyMask(skyPixels, sw, sh)
                    val skyFraction = SkySegmenter.computeSkyFraction(skyMask)
                    if (skyFraction in 0.05f..0.7f) {
                        val gndStrength = (sceneContrast * 0.8f).coerceIn(0.2f, 0.6f)
                        SkySegmenter.applyGndFilter(skyPixels, sw, sh, skyMask, gndStrength)
                        SkySegmenter.applySkyRecovery(skyPixels, sw, sh, skyMask)
                        result.setPixels(skyPixels, 0, sw, 0, 0, sw, sh)
                    }
                } catch (e: Exception) {
                    Log.w("CaptureManager", "Sky GND filter failed", e)
                }
            }

            // Stage: Beauty
            if (cumulativeMs > PROCESSING_BUDGET_MS) {
                Log.w("Pipeline", "Budget exceeded after shadow recovery (${cumulativeMs}ms), skipping beauty, highlight rolloff, bokeh, vignette")
            } else {
                val beautyStart = System.nanoTime()
                if (beautyLevel > 0) {
                    try {
                        if (isPortraitMode && faceRects.isNotEmpty()) {
                            // Frequency-separation beauty for portrait: preserves skin detail
                            val bw = result.width; val bh = result.height
                            val bPixels = IntArray(bw * bh)
                            result.getPixels(bPixels, 0, bw, 0, 0, bw, bh)
                            val bSkinMask = BeautyProcessor.buildSkinMask(bPixels, bw, bh, faceRects)
                            val bStrength = beautyLevel / 3f
                            BeautyProcessor.process(bPixels, bw, bh, bSkinMask, bStrength)
                            result.setPixels(bPixels, 0, bw, 0, 0, bw, bh)
                        } else {
                            applyLabBeauty(result, canvas, beautyLevel, faceRects)
                        }
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

                    // Stage: LUT color grading
                    val ppLutName = processing.colorGradingLut ?: LutEngine.lutForStyle(style.name)
                    if (ppLutName != null && ppLutName != "NEUTRAL") {
                        try {
                            val lw = result.width; val lh = result.height
                            val lutPixels = IntArray(lw * lh)
                            result.getPixels(lutPixels, 0, lw, 0, 0, lw, lh)
                            LutEngine.apply(lutPixels, lw, lh, ppLutName, strength = 0.6f)
                            result.setPixels(lutPixels, 0, lw, 0, 0, lw, lh)
                            Log.d("CaptureManager", "LUT color grading applied: $ppLutName")
                        } catch (_: OutOfMemoryError) { System.gc() }
                    }

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

        if (com.spectra.camera.gpu.ShaderPipeline.applyHighlightRolloff(
                bitmap, params.shoulderStart.toFloat(), params.maxOutput.toFloat(), params.strength)) {
            Log.d("CaptureManager", "Highlight rolloff (AGSL GPU): style=$style")
            return
        }

        val rolloff = ToneCurveEngine.buildHighlightRolloffCurve(params.shoulderStart, params.maxOutput, params.strength)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val newLum = rolloff[lum]
            if (lum > 0 && newLum != lum) {
                val scale = newLum.toFloat() / lum
                val rOut = (r * scale).toInt().coerceIn(0, 255)
                val gOut = (g * scale).toInt().coerceIn(0, 255)
                val bOut = (b * scale).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        Log.d("CaptureManager", "Highlight rolloff (CPU): style=$style, shoulder=${params.shoulderStart}, max=${params.maxOutput}")
    }

    private fun applyHdrToneMap(bitmap: Bitmap) {
        if (com.spectra.camera.gpu.ShaderPipeline.applyToneMap(bitmap, 0.4f, 0.3f)) {
            Log.d("CaptureManager", "HDR tone map (AGSL GPU) applied")
            return
        }

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

    fun analyzeSceneContrast(jpegBytes: ByteArray): Float {
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val small = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return 0f
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        return HdrProcessor.computeDrd(pixels)
    }

    fun shouldAutoHdr(jpegBytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val small = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return false
        val w = small.width; val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        return HdrProcessor.shouldTriggerHdr(pixels)
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

    private fun applyDepthSelectiveWarmth(pixels: IntArray, mask: FloatArray, w: Int, h: Int) {
        for (i in pixels.indices) {
            val subjectWeight = mask[i]
            val bgWeight = 1f - subjectWeight
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // Subject: slight warmth (+3R, +1G, -2B) scaled by mask
            // Background: slight cool shift (-1R, 0G, +2B) scaled by inverse mask
            val newR = (r + (3f * subjectWeight - 1f * bgWeight)).toInt().coerceIn(0, 255)
            val newG = (g + (1f * subjectWeight)).toInt().coerceIn(0, 255)
            val newB = (b + (-2f * subjectWeight + 2f * bgWeight)).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
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

        val focusDepth = DepthBokeh.selectFocusDepth(depthMap, depthInputSize, faceRects)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val result = depthBokeh.applyDepthBokeh(
            pixels, depthMap, w, h,
            depthInputSize, depthInputSize,
            focusDepth = focusDepth,
            maxBlurRadius = 20f,
            faceRects = faceRects
        )

        bitmap.setPixels(result, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        Log.d("CaptureManager", "Depth-map bokeh applied, focus=${"%.2f".format(focusDepth)}, guided filter upsampling")
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

            val skinMask = buildSkinMask(pixels, w, h, faceRects)

            val labL = FloatArray(w * h)
            val labA = FloatArray(w * h)
            val labB = FloatArray(w * h)
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val lab = ColorSpaceUtils.rgbToLab(r, g, b)
                labL[i] = lab[0]; labA[i] = lab[1]; labB[i] = lab[2]
            }

            val sigma = 5f + beautyLevel * 2f
            val lowFreq = gaussianBlurLuma(labL, w, h, sigma)
            val highFreq = FloatArray(w * h) { labL[it] - lowFreq[it] }
            val smoothedLow = gaussianBlurLuma(lowFreq, w, h, sigma * 0.5f)

            for (i in pixels.indices) {
                if (!skinMask[i]) continue
                val newL = (smoothedLow[i] + highFreq[i]).coerceIn(0f, 100f)
                val rgb = ColorSpaceUtils.labToRgb(newL, labA[i], labB[i])
                pixels[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun buildSkinMask(
            pixels: IntArray, w: Int, h: Int,
            faceRects: List<android.graphics.RectF> = emptyList()
        ): BooleanArray {
            val mask = BooleanArray(pixels.size)

            if (faceRects.isNotEmpty()) {
                for (face in faceRects) {
                    val padX = (face.width() * 0.15f)
                    val padY = (face.height() * 0.15f)
                    val left = ((face.left - padX) * w).toInt().coerceIn(0, w - 1)
                    val top = ((face.top - padY) * h).toInt().coerceIn(0, h - 1)
                    val right = ((face.right + padX) * w).toInt().coerceIn(0, w)
                    val bottom = ((face.bottom + padY) * h).toInt().coerceIn(0, h)
                    for (y in top until bottom) {
                        for (x in left until right) {
                            mask[y * w + x] = true
                        }
                    }
                }
                return mask
            }

            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val cb = (128f - 37.797f * r / 255f - 74.203f * g / 255f + 112f * b / 255f).toInt()
                val cr = (128f + 112f * r / 255f - 93.786f * g / 255f - 18.214f * b / 255f).toInt()
                mask[i] = cb in 70..135 && cr in 125..180
            }
            return mask
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
            val spatialSigma = spatialRadius / 2f
            val spatialVar = 2f * spatialSigma * spatialSigma

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
                            val dx = (nx - x).toFloat()
                            val dy = (ny - y).toFloat()
                            val spatialWeight = kotlin.math.exp(-(dx * dx + dy * dy) / spatialVar)
                            val diff = guide[nIdx] - centerVal
                            val rangeWeight = kotlin.math.exp(-(diff * diff) / rangeVar)
                            val weight = spatialWeight * rangeWeight
                            weightedSum += output[nIdx] * weight
                            weightSum += weight
                        }
                    }

                    temp[idx] = if (weightSum > 0f) weightedSum / weightSum else output[idx]
                }
            }

            System.arraycopy(temp, 0, output, 0, output.size)
        }

    }
}
