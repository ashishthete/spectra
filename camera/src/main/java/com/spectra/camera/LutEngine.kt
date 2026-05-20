package com.spectra.camera

import kotlin.math.pow

/**
 * 3D LUT (Look-Up Table) color grading engine.
 *
 * Stores 17x17x17 3D LUTs as FloatArray(17*17*17*3) with trilinear interpolation.
 * Built-in LUTs are constructed programmatically from channel curves and color-balance
 * shifts -- no external .cube files required.
 *
 * Memory: ~15 KB per LUT (17^3 * 3 * 4 bytes = 58,956 bytes, but only allocated once).
 */
object LutEngine {

    private const val LUT_SIZE = 17
    private const val LUT_SIZE_M1 = LUT_SIZE - 1
    private const val LUT_ENTRIES = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3

    /** Registered LUTs: name -> FloatArray(LUT_SIZE^3 * 3) */
    private val luts = mutableMapOf<String, FloatArray>()

    init {
        luts["NEUTRAL"] = buildIdentityLut()
        luts["CINEMATIC"] = buildCinematicLut()
        luts["VIVID"] = buildVividLut()
        luts["MOODY"] = buildMoodyLut()
        luts["FILM"] = buildFilmLut()
    }

    /**
     * Available LUT names.
     */
    fun availableLuts(): List<String> = luts.keys.toList()

    /**
     * Apply a named LUT to an ARGB pixel array.
     *
     * @param pixels ARGB_8888 pixel data (modified in place and returned)
     * @param w image width
     * @param h image height
     * @param lutName one of NEUTRAL, CINEMATIC, VIVID, MOODY, FILM
     * @param strength blend factor 0..1 (0 = original, 1 = full LUT)
     * @return the same pixels array, modified
     */
    fun apply(
        pixels: IntArray,
        w: Int,
        h: Int,
        lutName: String,
        strength: Float = 1.0f
    ): IntArray {
        val lut = luts[lutName] ?: return pixels
        if (strength <= 0f) return pixels
        val s = strength.coerceIn(0f, 1f)
        val invS = 1f - s
        val scale = LUT_SIZE_M1 / 255f

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val origR = (p shr 16) and 0xFF
            val origG = (p shr 8) and 0xFF
            val origB = p and 0xFF

            // Map 0-255 into 0-(LUT_SIZE-1) continuous coordinates
            val rf = origR * scale
            val gf = origG * scale
            val bf = origB * scale

            // Trilinear interpolation
            val r0 = rf.toInt().coerceAtMost(LUT_SIZE - 2)
            val g0 = gf.toInt().coerceAtMost(LUT_SIZE - 2)
            val b0 = bf.toInt().coerceAtMost(LUT_SIZE - 2)
            val r1 = r0 + 1
            val g1 = g0 + 1
            val b1 = b0 + 1

            val fr = rf - r0
            val fg = gf - g0
            val fb = bf - b0
            val ifr = 1f - fr
            val ifg = 1f - fg
            val ifb = 1f - fb

            // 8 corner lookups
            val c000 = lutIndex(r0, g0, b0)
            val c100 = lutIndex(r1, g0, b0)
            val c010 = lutIndex(r0, g1, b0)
            val c110 = lutIndex(r1, g1, b0)
            val c001 = lutIndex(r0, g0, b1)
            val c101 = lutIndex(r1, g0, b1)
            val c011 = lutIndex(r0, g1, b1)
            val c111 = lutIndex(r1, g1, b1)

            // Interpolate R channel
            val lutR = (lut[c000] * ifr * ifg * ifb +
                    lut[c100] * fr * ifg * ifb +
                    lut[c010] * ifr * fg * ifb +
                    lut[c110] * fr * fg * ifb +
                    lut[c001] * ifr * ifg * fb +
                    lut[c101] * fr * ifg * fb +
                    lut[c011] * ifr * fg * fb +
                    lut[c111] * fr * fg * fb)

            // Interpolate G channel
            val lutG = (lut[c000 + 1] * ifr * ifg * ifb +
                    lut[c100 + 1] * fr * ifg * ifb +
                    lut[c010 + 1] * ifr * fg * ifb +
                    lut[c110 + 1] * fr * fg * ifb +
                    lut[c001 + 1] * ifr * ifg * fb +
                    lut[c101 + 1] * fr * ifg * fb +
                    lut[c011 + 1] * ifr * fg * fb +
                    lut[c111 + 1] * fr * fg * fb)

            // Interpolate B channel
            val lutB = (lut[c000 + 2] * ifr * ifg * ifb +
                    lut[c100 + 2] * fr * ifg * ifb +
                    lut[c010 + 2] * ifr * fg * ifb +
                    lut[c110 + 2] * fr * fg * ifb +
                    lut[c001 + 2] * ifr * ifg * fb +
                    lut[c101 + 2] * fr * ifg * fb +
                    lut[c011 + 2] * ifr * fg * fb +
                    lut[c111 + 2] * fr * fg * fb)

            // Blend with original based on strength
            val finalR = (origR * invS + lutR * s).coerceIn(0f, 255f).toInt()
            val finalG = (origG * invS + lutG * s).coerceIn(0f, 255f).toInt()
            val finalB = (origB * invS + lutB * s).coerceIn(0f, 255f).toInt()

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }
        return pixels
    }

    // ---- LUT index computation ----

    /** Returns the base index into the flat FloatArray for LUT entry at (r, g, b). */
    private fun lutIndex(r: Int, g: Int, b: Int): Int {
        return (r * LUT_SIZE * LUT_SIZE + g * LUT_SIZE + b) * 3
    }

    // ---- Identity LUT ----

    private fun buildIdentityLut(): FloatArray {
        val lut = FloatArray(LUT_ENTRIES)
        val step = 255f / LUT_SIZE_M1
        for (ri in 0 until LUT_SIZE) {
            for (gi in 0 until LUT_SIZE) {
                for (bi in 0 until LUT_SIZE) {
                    val idx = lutIndex(ri, gi, bi)
                    lut[idx] = ri * step
                    lut[idx + 1] = gi * step
                    lut[idx + 2] = bi * step
                }
            }
        }
        return lut
    }

    // ---- Channel curve helpers ----

    /**
     * S-curve: apply contrast around midpoint.
     * amount > 0 increases contrast, < 0 decreases.
     */
    private fun sCurve(value: Float, amount: Float): Float {
        val normalized = value / 255f
        // Attempt a smooth sigmoid-based S-curve
        val shifted = (normalized - 0.5f) * 2f // -1..1
        val curved = shifted * (1f + amount * (1f - shifted * shifted))
        return ((curved * 0.5f + 0.5f) * 255f).coerceIn(0f, 255f)
    }

    /**
     * Lift blacks: remap [0..255] to [liftTo..255].
     */
    private fun liftBlacks(value: Float, liftTo: Float): Float {
        return liftTo + value * (255f - liftTo) / 255f
    }

    /**
     * Compress highlights: pull values above threshold toward maxOut.
     */
    private fun compressHighlights(value: Float, threshold: Float, maxOut: Float): Float {
        if (value <= threshold) return value
        val excess = value - threshold
        val range = 255f - threshold
        val compressed = threshold + excess * (maxOut - threshold) / range
        return compressed.coerceIn(0f, 255f)
    }

    /**
     * Apply shadow tint: add color shift proportional to how dark the pixel is.
     */
    private fun shadowTint(value: Float, luminance: Float, tintAmount: Float): Float {
        val shadowWeight = (1f - luminance / 255f).coerceIn(0f, 1f)
        return (value + tintAmount * shadowWeight).coerceIn(0f, 255f)
    }

    /**
     * Apply highlight tint: add color shift proportional to how bright the pixel is.
     */
    private fun highlightTint(value: Float, luminance: Float, tintAmount: Float): Float {
        val highlightWeight = (luminance / 255f).coerceIn(0f, 1f)
        return (value + tintAmount * highlightWeight).coerceIn(0f, 255f)
    }

    /**
     * Desaturate toward luminance by a factor (0 = no change, 1 = fully desaturated).
     */
    private fun desaturateChannel(value: Float, luminance: Float, amount: Float): Float {
        return value + (luminance - value) * amount
    }

    // ---- Built-in LUT builders ----

    /**
     * CINEMATIC: Orange/teal split toning, lifted blacks, desaturated highlights.
     * - Shadows: warm orange push (R+12, G+4, B-10)
     * - Highlights: cool teal pull (R-5, G+2, B+8), slight desaturation
     * - Blacks lifted to ~10
     * - Mild S-curve contrast
     */
    private fun buildCinematicLut(): FloatArray {
        val lut = FloatArray(LUT_ENTRIES)
        val step = 255f / LUT_SIZE_M1
        for (ri in 0 until LUT_SIZE) {
            for (gi in 0 until LUT_SIZE) {
                for (bi in 0 until LUT_SIZE) {
                    val idx = lutIndex(ri, gi, bi)
                    var r = ri * step
                    var g = gi * step
                    var b = bi * step
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b

                    // Lift blacks
                    r = liftBlacks(r, 10f)
                    g = liftBlacks(g, 10f)
                    b = liftBlacks(b, 10f)

                    // Mild S-curve contrast
                    r = sCurve(r, 0.15f)
                    g = sCurve(g, 0.15f)
                    b = sCurve(b, 0.15f)

                    // Shadow tint: warm orange
                    r = shadowTint(r, lum, 12f)
                    g = shadowTint(g, lum, 4f)
                    b = shadowTint(b, lum, -10f)

                    // Highlight tint: cool teal
                    r = highlightTint(r, lum, -5f)
                    g = highlightTint(g, lum, 2f)
                    b = highlightTint(b, lum, 8f)

                    // Desaturate highlights
                    val newLum = 0.299f * r + 0.587f * g + 0.114f * b
                    val hlWeight = (newLum / 255f).coerceIn(0f, 1f)
                    val desatAmount = hlWeight * 0.2f
                    r = desaturateChannel(r, newLum, desatAmount)
                    g = desaturateChannel(g, newLum, desatAmount)
                    b = desaturateChannel(b, newLum, desatAmount)

                    // Compress highlights slightly
                    r = compressHighlights(r, 220f, 245f)
                    g = compressHighlights(g, 220f, 245f)
                    b = compressHighlights(b, 220f, 245f)

                    lut[idx] = r.coerceIn(0f, 255f)
                    lut[idx + 1] = g.coerceIn(0f, 255f)
                    lut[idx + 2] = b.coerceIn(0f, 255f)
                }
            }
        }
        return lut
    }

    /**
     * VIVID: Boosted saturation, slightly warmer shadows, punchier midtones.
     * - Strong S-curve for punch
     * - Saturation boost via pulling channels away from luminance
     * - Warm shadow push (R+6, B-4)
     */
    private fun buildVividLut(): FloatArray {
        val lut = FloatArray(LUT_ENTRIES)
        val step = 255f / LUT_SIZE_M1
        for (ri in 0 until LUT_SIZE) {
            for (gi in 0 until LUT_SIZE) {
                for (bi in 0 until LUT_SIZE) {
                    val idx = lutIndex(ri, gi, bi)
                    var r = ri * step
                    var g = gi * step
                    var b = bi * step
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b

                    // Stronger S-curve for punch
                    r = sCurve(r, 0.25f)
                    g = sCurve(g, 0.25f)
                    b = sCurve(b, 0.25f)

                    // Saturation boost: pull channels away from luminance
                    val newLum = 0.299f * r + 0.587f * g + 0.114f * b
                    val satBoost = 1.15f
                    r = newLum + (r - newLum) * satBoost
                    g = newLum + (g - newLum) * satBoost
                    b = newLum + (b - newLum) * satBoost

                    // Warm shadows slightly
                    r = shadowTint(r, lum, 6f)
                    b = shadowTint(b, lum, -4f)

                    lut[idx] = r.coerceIn(0f, 255f)
                    lut[idx + 1] = g.coerceIn(0f, 255f)
                    lut[idx + 2] = b.coerceIn(0f, 255f)
                }
            }
        }
        return lut
    }

    /**
     * MOODY: Cool shadows, desaturated midtones, crushed blacks.
     * - Blacks crushed (lifted to 0, but shadows sharply darkened)
     * - Cool blue shadow tint (R-8, B+12)
     * - Midtone desaturation
     * - Slight highlight compression
     */
    private fun buildMoodyLut(): FloatArray {
        val lut = FloatArray(LUT_ENTRIES)
        val step = 255f / LUT_SIZE_M1
        for (ri in 0 until LUT_SIZE) {
            for (gi in 0 until LUT_SIZE) {
                for (bi in 0 until LUT_SIZE) {
                    val idx = lutIndex(ri, gi, bi)
                    var r = ri * step
                    var g = gi * step
                    var b = bi * step
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b

                    // Crush blacks: gamma > 1 darkens shadows
                    r = (255f * (r / 255f).pow(1.15f))
                    g = (255f * (g / 255f).pow(1.15f))
                    b = (255f * (b / 255f).pow(1.15f))

                    // Cool shadow tint
                    r = shadowTint(r, lum, -8f)
                    g = shadowTint(g, lum, -2f)
                    b = shadowTint(b, lum, 12f)

                    // Desaturate midtones: strongest around lum ~128
                    val newLum = 0.299f * r + 0.587f * g + 0.114f * b
                    val midWeight = 1f - ((newLum - 128f) / 128f).let { it * it } // bell curve at 128
                    val desatAmount = midWeight * 0.25f
                    r = desaturateChannel(r, newLum, desatAmount)
                    g = desaturateChannel(g, newLum, desatAmount)
                    b = desaturateChannel(b, newLum, desatAmount)

                    // Slight highlight compression
                    r = compressHighlights(r, 210f, 240f)
                    g = compressHighlights(g, 210f, 240f)
                    b = compressHighlights(b, 210f, 240f)

                    lut[idx] = r.coerceIn(0f, 255f)
                    lut[idx + 1] = g.coerceIn(0f, 255f)
                    lut[idx + 2] = b.coerceIn(0f, 255f)
                }
            }
        }
        return lut
    }

    /**
     * FILM: Green-tinted shadows, muted highlights, lifted black point to ~15.
     * - Black point lifted to 15
     * - Shadow tint: slight green (G+8, R-3, B-2)
     * - Highlight muting: compress and desaturate
     * - Mild contrast reduction (inverse S-curve)
     */
    private fun buildFilmLut(): FloatArray {
        val lut = FloatArray(LUT_ENTRIES)
        val step = 255f / LUT_SIZE_M1
        for (ri in 0 until LUT_SIZE) {
            for (gi in 0 until LUT_SIZE) {
                for (bi in 0 until LUT_SIZE) {
                    val idx = lutIndex(ri, gi, bi)
                    var r = ri * step
                    var g = gi * step
                    var b = bi * step
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b

                    // Lift black point to 15
                    r = liftBlacks(r, 15f)
                    g = liftBlacks(g, 15f)
                    b = liftBlacks(b, 15f)

                    // Mild contrast reduction
                    r = sCurve(r, -0.1f)
                    g = sCurve(g, -0.1f)
                    b = sCurve(b, -0.1f)

                    // Shadow tint: green
                    r = shadowTint(r, lum, -3f)
                    g = shadowTint(g, lum, 8f)
                    b = shadowTint(b, lum, -2f)

                    // Mute highlights: compress and desaturate
                    r = compressHighlights(r, 200f, 235f)
                    g = compressHighlights(g, 200f, 235f)
                    b = compressHighlights(b, 200f, 235f)

                    val newLum = 0.299f * r + 0.587f * g + 0.114f * b
                    val hlWeight = (newLum / 255f).coerceIn(0f, 1f)
                    val desatAmount = hlWeight * 0.15f
                    r = desaturateChannel(r, newLum, desatAmount)
                    g = desaturateChannel(g, newLum, desatAmount)
                    b = desaturateChannel(b, newLum, desatAmount)

                    lut[idx] = r.coerceIn(0f, 255f)
                    lut[idx + 1] = g.coerceIn(0f, 255f)
                    lut[idx + 2] = b.coerceIn(0f, 255f)
                }
            }
        }
        return lut
    }

    /**
     * Map a PhotoStyle name to the corresponding LUT name.
     * Returns null for styles that don't have a LUT mapping.
     */
    fun lutForStyle(styleName: String): String? {
        return when (styleName) {
            "CINEMATIC" -> "CINEMATIC"
            "VIVID" -> "VIVID"
            "FILM" -> "FILM"
            "WARM" -> null  // Warm uses warmth shift, not a LUT
            "NATURAL" -> null  // No grading
            else -> null
        }
    }
}
