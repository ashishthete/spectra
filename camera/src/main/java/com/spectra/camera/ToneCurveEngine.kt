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
        return IntArray(256) { i ->
            val t = i / 255.0
            val smoothstep = t * t * (3.0 - 2.0 * t)
            val sCurved = t + strength * (smoothstep - t)
            (sCurved * 255.0).roundToInt().coerceIn(0, 255)
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

    fun hableFilmic(x: Float): Float {
        val A = 0.15f; val B = 0.50f; val C = 0.10f
        val D = 0.20f; val E = 0.02f; val F = 0.30f
        return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F
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
        if (style == PhotoStyle.NATURAL) return

        val startTime = System.nanoTime()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Prefer 3D LUT when loaded; fall back to 1D per-channel curves.
        val lutEntry = luts?.get(style)
        if (lutEntry != null) {
            val (lutSize, lutData) = lutEntry
            Lut3D.applyToPixels(pixels, lutData, lutSize)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            val elapsed = (System.nanoTime() - startTime) / 1_000_000
            Log.d(TAG, "3D LUT applied: style=$style, ${elapsed}ms")
        } else {
            val curves = getCurvesForStyle(style)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = curves.r[(pixel shr 16) and 0xFF]
                val g = curves.g[(pixel shr 8) and 0xFF]
                val b = curves.b[pixel and 0xFF]
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            val elapsed = (System.nanoTime() - startTime) / 1_000_000
            Log.d(TAG, "1D tone curve applied (fallback): style=$style, ${elapsed}ms")
        }
    }
}
