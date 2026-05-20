package com.spectra.camera

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.spectra.core.model.PhotoStyle
import kotlin.math.roundToInt

object ToneCurveEngine {

    private const val TAG = "ToneCurveEngine"

    /** Loaded 3D LUTs keyed by PhotoStyle.  Null until [loadLuts] is called. */
    private var luts: Map<PhotoStyle, Pair<Int, FloatArray>>? = null

    /**
     * Load .cube LUT files from the assets directory.
     * Call once during app initialisation (e.g. from Application.onCreate).
     */
    fun loadLuts(assetManager: AssetManager) {
        val mapping = mapOf(
            PhotoStyle.VIVID     to "lut_vivid.cube",
            PhotoStyle.WARM      to "lut_warm.cube",
            PhotoStyle.FILM      to "lut_film.cube",
            PhotoStyle.CINEMATIC to "lut_cinematic.cube"
        )
        val loaded = mutableMapOf<PhotoStyle, Pair<Int, FloatArray>>()
        for ((style, filename) in mapping) {
            try {
                val cubeText = assetManager.open(filename).bufferedReader().use { it.readText() }
                loaded[style] = Lut3D.parse(cubeText)
                Log.d(TAG, "Loaded 3D LUT for $style from $filename (size=${loaded[style]!!.first})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load LUT $filename for $style, will fall back to 1D curves", e)
            }
        }
        luts = loaded
    }

    data class ChannelCurves(
        val r: IntArray,
        val g: IntArray,
        val b: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChannelCurves) return false
            return r.contentEquals(other.r) && g.contentEquals(other.g) && b.contentEquals(other.b)
        }
        override fun hashCode(): Int {
            var result = r.contentHashCode()
            result = 31 * result + g.contentHashCode()
            result = 31 * result + b.contentHashCode()
            return result
        }
    }

    fun identityCurve(): IntArray = IntArray(256) { it }

    fun sCurve(strength: Float): IntArray {
        val k = strength * 12.0
        return IntArray(256) { i ->
            val t = i / 255.0
            if (k < 0.01) {
                i
            } else {
                val s = 1.0 / (1.0 + Math.exp(-k * (t - 0.5)))
                val s0 = 1.0 / (1.0 + Math.exp(k * 0.5))
                val s1 = 1.0 / (1.0 + Math.exp(-k * 0.5))
                val sigmoid = (s - s0) / (s1 - s0)
                val blended = t + strength * (sigmoid - t)
                (blended * 255.0).roundToInt().coerceIn(0, 255)
            }
        }
    }

    fun liftedCurve(floor: Int, ceiling: Int): IntArray {
        val range = (ceiling - floor).coerceAtLeast(1)
        return IntArray(256) { i ->
            (floor + i * range / 255).coerceIn(0, 255)
        }
    }

    fun shiftCurve(baseCurve: IntArray, offset: Int): IntArray {
        return IntArray(256) { i ->
            (baseCurve[i] + offset).coerceIn(0, 255)
        }
    }

    fun blendCurves(curveA: IntArray, curveB: IntArray, blend: Float): IntArray {
        return IntArray(256) { i ->
            ((1f - blend) * curveA[i] + blend * curveB[i]).roundToInt().coerceIn(0, 255)
        }
    }

    fun getCurvesForStyle(style: PhotoStyle): ChannelCurves {
        return when (style) {
            PhotoStyle.NATURAL -> ChannelCurves(
                r = identityCurve(),
                g = identityCurve(),
                b = identityCurve()
            )

            PhotoStyle.VIVID -> {
                val base = sCurve(0.5f)
                val blueBoost = IntArray(256) { i ->
                    if (i < 80) {
                        (base[i] + 5).coerceIn(0, 255)
                    } else {
                        base[i]
                    }
                }
                ChannelCurves(r = base.clone(), g = base.clone(), b = blueBoost)
            }

            PhotoStyle.WARM -> {
                val gentle = sCurve(0.25f)
                ChannelCurves(
                    r = shiftCurve(gentle, 8),
                    g = gentle.clone(),
                    b = shiftCurve(gentle, -10)
                )
            }

            PhotoStyle.FILM -> {
                val filmBase = liftedCurve(15, 240)
                val filmR = blendCurves(filmBase, sCurve(0.2f), 0.3f)
                val filmG = filmBase.clone()
                val filmB = IntArray(256) { i ->
                    if (i < 64) {
                        (filmBase[i] + 5).coerceIn(0, 255)
                    } else {
                        filmBase[i]
                    }
                }
                ChannelCurves(r = filmR, g = filmG, b = filmB)
            }

            PhotoStyle.CINEMATIC -> {
                val crushedBase = liftedCurve(20, 255)
                val cinematicR = IntArray(256) { i ->
                    if (i < 128) {
                        (crushedBase[i] - 8).coerceIn(0, 255)
                    } else {
                        (crushedBase[i] + 6).coerceIn(0, 255)
                    }
                }
                val cinematicG = crushedBase.clone()
                val cinematicB = IntArray(256) { i ->
                    if (i < 128) {
                        (crushedBase[i] + 12).coerceIn(0, 255)
                    } else {
                        (crushedBase[i] - 4).coerceIn(0, 255)
                    }
                }
                ChannelCurves(r = cinematicR, g = cinematicG, b = cinematicB)
            }
        }
    }

    data class HighlightParams(
        val shoulderStart: Int = 200,
        val maxOutput: Int = 255,
        val strength: Float = 0f
    )

    fun styleHighlightParams(style: com.spectra.core.model.PhotoStyle): HighlightParams {
        return when (style) {
            com.spectra.core.model.PhotoStyle.NATURAL -> HighlightParams(shoulderStart = 200, maxOutput = 255, strength = 0f)
            com.spectra.core.model.PhotoStyle.VIVID -> HighlightParams(shoulderStart = 210, maxOutput = 252, strength = 0.3f)
            com.spectra.core.model.PhotoStyle.WARM -> HighlightParams(shoulderStart = 205, maxOutput = 250, strength = 0.4f)
            com.spectra.core.model.PhotoStyle.FILM -> HighlightParams(shoulderStart = 190, maxOutput = 240, strength = 0.8f)
            com.spectra.core.model.PhotoStyle.CINEMATIC -> HighlightParams(shoulderStart = 195, maxOutput = 242, strength = 0.7f)
        }
    }

    fun hableFilmic(x: Float): Float = hableFilmicAdaptive(x, sceneKey = 0.18f)

    fun hableFilmicAdaptive(x: Float, sceneKey: Float = 0.18f): Float {
        val keyScale = (0.18f / sceneKey.coerceIn(0.04f, 0.8f))
        val scaled = x * keyScale
        val A = 0.15f; val B = 0.50f; val C = 0.10f
        val D = 0.20f; val E = 0.02f; val F = 0.30f
        return ((scaled * (A * scaled + C * B) + D * E) / (scaled * (A * scaled + B) + D * F)) - E / F
    }

    fun highlightShoulder(input: Int, shoulderStart: Int, maxOutput: Int, strength: Float): Int {
        if (strength <= 0f || input <= shoulderStart) return input
        val range = (255 - shoulderStart).coerceAtLeast(1)
        val t = (input - shoulderStart).toFloat() / range
        val whiteScale = hableFilmic(1f)
        val tFilmic = hableFilmic(t) / whiteScale
        val shoulderOutput = shoulderStart + (maxOutput - shoulderStart) * tFilmic
        val result = input + strength * (shoulderOutput - input)
        return result.toInt().coerceIn(shoulderStart, maxOutput)
    }

    fun buildHighlightRolloffCurve(shoulderStart: Int, maxOutput: Int, strength: Float): IntArray {
        return IntArray(256) { i -> highlightShoulder(i, shoulderStart, maxOutput, strength) }
    }

    fun apply(bitmap: Bitmap, style: PhotoStyle) {
        apply(bitmap, style, skinHueProtection = false, chromaCompression = 0f)
    }

    fun apply(bitmap: Bitmap, style: PhotoStyle, skinHueProtection: Boolean, chromaCompression: Float = 0f, faceRects: List<android.graphics.RectF> = emptyList()) {
        if (style == PhotoStyle.NATURAL && !skinHueProtection && chromaCompression <= 0f) return

        val startTime = System.nanoTime()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lutEntry = luts?.get(style)
        if (style != PhotoStyle.NATURAL) {
            if (lutEntry != null) {
                val (lutSize, lutData) = lutEntry
                srgbToLinearPixels(pixels)
                Lut3D.applyToPixels(pixels, lutData, lutSize)
                linearToSrgbPixels(pixels)
            } else {
                val curves = getCurvesForStyle(style)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = curves.r[(pixel shr 16) and 0xFF]
                    val g = curves.g[(pixel shr 8) and 0xFF]
                    val b = curves.b[pixel and 0xFF]
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        if (skinHueProtection || chromaCompression > 0f) {
            applyColorAppearance(pixels, w, h, skinHueProtection, chromaCompression, faceRects)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Tone applied: style=$style, skin=$skinHueProtection, chroma=$chromaCompression, ${elapsed}ms")
    }

    private fun applyColorAppearance(pixels: IntArray, width: Int, height: Int, skinProtect: Boolean, chromaCompress: Float, faceRects: List<android.graphics.RectF>) {
        for (i in pixels.indices) {
            val pixel = pixels[i]
            var r = ((pixel shr 16) and 0xFF).toFloat()
            var g = ((pixel shr 8) and 0xFF).toFloat()
            var b = (pixel and 0xFF).toFloat()

            if (chromaCompress > 0f) {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val lumNorm = lum / 255f
                val compress = if (lumNorm > 0.85f || lumNorm < 0.15f) chromaCompress else chromaCompress * 0.3f
                r = lum + (r - lum) * (1f - compress)
                g = lum + (g - lum) * (1f - compress)
                b = lum + (b - lum) * (1f - compress)
            }

            if (skinProtect) {
                val x = i % width
                val y = i / width
                val nx = x.toFloat() / width
                val ny = y.toFloat() / height
                val inFaceRegion = faceRects.isEmpty() || faceRects.any { nx in it.left..it.right && ny in it.top..it.bottom }

                if (inFaceRegion) {
                    val ri = r.toInt().coerceIn(0, 255)
                    val gi = g.toInt().coerceIn(0, 255)
                    val bi = b.toInt().coerceIn(0, 255)
                    val ycbcr = ColorSpaceUtils.rgbToYCbCr(ri, gi, bi)
                    if (ColorSpaceUtils.isSkinPixelYCbCr(ycbcr[1], ycbcr[2])) {
                        val lab = ColorSpaceUtils.rgbToLab(ri, gi, bi)
                        val corrected = ColorSpaceUtils.correctSkinToneLab(lab[0], lab[1], lab[2])
                        val rgb = ColorSpaceUtils.labToRgb(corrected[0], corrected[1], corrected[2])
                        r = rgb[0].toFloat()
                        g = rgb[1].toFloat()
                        b = rgb[2].toFloat()
                    }
                }
            }

            pixels[i] = (0xFF shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }
    }

    private val srgbToLinearLut = FloatArray(256) { i ->
        val c = i / 255.0
        val linear = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        (linear * 255.0).toFloat()
    }

    private val linearToSrgbLut = FloatArray(256) { i ->
        val c = i / 255.0
        val srgb = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
        (srgb * 255.0).toFloat()
    }

    private val bayerDither = floatArrayOf(-0.375f, 0.125f, 0.375f, -0.125f)

    private fun srgbToLinearPixels(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val d = bayerDither[i and 3]
            val r = (srgbToLinearLut[(p shr 16) and 0xFF] + d).roundToInt().coerceIn(0, 255)
            val g = (srgbToLinearLut[(p shr 8) and 0xFF] + d).roundToInt().coerceIn(0, 255)
            val b = (srgbToLinearLut[p and 0xFF] + d).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun linearToSrgbPixels(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val d = bayerDither[i and 3]
            val r = (linearToSrgbLut[(p shr 16) and 0xFF] + d).roundToInt().coerceIn(0, 255)
            val g = (linearToSrgbLut[(p shr 8) and 0xFF] + d).roundToInt().coerceIn(0, 255)
            val b = (linearToSrgbLut[p and 0xFF] + d).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun skinHue(r: Float, g: Float, b: Float): Float {
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta < 1f) return 0f
        val hue = when (maxC) {
            r -> 60f * ((g - b) / delta % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }
}
