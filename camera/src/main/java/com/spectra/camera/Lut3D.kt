package com.spectra.camera

import kotlin.math.roundToInt

/**
 * 3D LUT (Look-Up Table) utilities for color grading.
 *
 * A 3D LUT maps every (R,G,B) triplet to a new (R',G',B') triplet, allowing
 * cross-channel hue/saturation shifts that 1D per-channel curves cannot express.
 *
 * Internal storage: a flat FloatArray of size^3 * 3 entries.
 * Index layout: for grid indices (ri, gi, bi), the base offset is
 *   (ri * size * size + gi * size + bi) * 3
 * This matches the .cube file iteration order where B varies fastest,
 * then G, then R.
 */
object Lut3D {

    /**
     * Generate an identity LUT — every grid point maps to its own
     * normalised position so colours pass through unchanged.
     */
    fun generateIdentity(size: Int): FloatArray {
        val data = FloatArray(size * size * size * 3)
        val div = (size - 1).toFloat()
        for (ri in 0 until size) {
            for (gi in 0 until size) {
                for (bi in 0 until size) {
                    val idx = (ri * size * size + gi * size + bi) * 3
                    data[idx]     = ri / div   // R
                    data[idx + 1] = gi / div   // G
                    data[idx + 2] = bi / div   // B
                }
            }
        }
        return data
    }

    /**
     * Parse a .cube file.  Expects header line "LUT_3D_SIZE N" and then
     * N^3 lines of "R G B" floats (0.0-1.0).  Comment lines starting
     * with '#' and blank lines are skipped.
     *
     * @return (size, data) where data has size^3 * 3 floats.
     */
    fun parse(cubeText: String): Pair<Int, FloatArray> {
        var size = -1
        val values = mutableListOf<Float>()

        for (line in cubeText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("LUT_3D_SIZE")) {
                size = trimmed.substringAfter("LUT_3D_SIZE").trim().toInt()
                continue
            }

            // Skip other header keywords (TITLE, DOMAIN_MIN, DOMAIN_MAX, etc.)
            if (trimmed.firstOrNull()?.isLetter() == true) continue

            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size >= 3) {
                values.add(parts[0].toFloat())
                values.add(parts[1].toFloat())
                values.add(parts[2].toFloat())
            }
        }

        require(size > 0) { "LUT_3D_SIZE header not found" }
        require(values.size == size * size * size * 3) {
            "Expected ${size * size * size * 3} values but got ${values.size}"
        }
        return size to values.toFloatArray()
    }

    /**
     * Apply 3D LUT to a single pixel via trilinear interpolation.
     *
     * @param r input red   0-255
     * @param g input green 0-255
     * @param b input blue  0-255
     * @return (outR, outG, outB) each 0-255
     */
    fun apply(r: Int, g: Int, b: Int, lut: FloatArray, size: Int): Triple<Int, Int, Int> {
        val sizeM1 = size - 1

        // Map 0-255 to 0..(size-1) grid coordinates
        val rf = r * sizeM1 / 255f
        val gf = g * sizeM1 / 255f
        val bf = b * sizeM1 / 255f

        // Integer grid indices (low corners)
        val ri0 = rf.toInt().coerceIn(0, sizeM1 - 1)
        val gi0 = gf.toInt().coerceIn(0, sizeM1 - 1)
        val bi0 = bf.toInt().coerceIn(0, sizeM1 - 1)

        val ri1 = ri0 + 1
        val gi1 = gi0 + 1
        val bi1 = bi0 + 1

        // Fractional parts
        val dr = rf - ri0
        val dg = gf - gi0
        val db = bf - bi0

        // Helper to read a LUT entry
        fun lutAt(ri: Int, gi: Int, bi: Int, ch: Int): Float {
            return lut[(ri * size * size + gi * size + bi) * 3 + ch]
        }

        // Trilinear interpolation for each output channel
        fun interp(ch: Int): Float {
            val c000 = lutAt(ri0, gi0, bi0, ch)
            val c001 = lutAt(ri0, gi0, bi1, ch)
            val c010 = lutAt(ri0, gi1, bi0, ch)
            val c011 = lutAt(ri0, gi1, bi1, ch)
            val c100 = lutAt(ri1, gi0, bi0, ch)
            val c101 = lutAt(ri1, gi0, bi1, ch)
            val c110 = lutAt(ri1, gi1, bi0, ch)
            val c111 = lutAt(ri1, gi1, bi1, ch)

            // Interpolate along B axis
            val c00 = c000 + (c001 - c000) * db
            val c01 = c010 + (c011 - c010) * db
            val c10 = c100 + (c101 - c100) * db
            val c11 = c110 + (c111 - c110) * db

            // Interpolate along G axis
            val c0 = c00 + (c01 - c00) * dg
            val c1 = c10 + (c11 - c10) * dg

            // Interpolate along R axis
            return c0 + (c1 - c0) * dr
        }

        val outR = (interp(0) * 255f).roundToInt().coerceIn(0, 255)
        val outG = (interp(1) * 255f).roundToInt().coerceIn(0, 255)
        val outB = (interp(2) * 255f).roundToInt().coerceIn(0, 255)

        return Triple(outR, outG, outB)
    }

    /**
     * Apply 3D LUT to an entire ARGB pixel array in-place.
     * Alpha channel is preserved.
     */
    fun applyToPixels(pixels: IntArray, lut: FloatArray, size: Int) {
        val sizeM1 = size - 1
        val sizeSq = size * size

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = pixel and (0xFF shl 24)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Map 0-255 to grid coordinates
            val rf = r * sizeM1 / 255f
            val gf = g * sizeM1 / 255f
            val bf = b * sizeM1 / 255f

            val ri0 = rf.toInt().coerceIn(0, sizeM1 - 1)
            val gi0 = gf.toInt().coerceIn(0, sizeM1 - 1)
            val bi0 = bf.toInt().coerceIn(0, sizeM1 - 1)

            val ri1 = ri0 + 1
            val gi1 = gi0 + 1
            val bi1 = bi0 + 1

            val dr = rf - ri0
            val dg = gf - gi0
            val db = bf - bi0

            // Precompute base offsets for the 8 LUT vertices
            val base000 = (ri0 * sizeSq + gi0 * size + bi0) * 3
            val base001 = (ri0 * sizeSq + gi0 * size + bi1) * 3
            val base010 = (ri0 * sizeSq + gi1 * size + bi0) * 3
            val base011 = (ri0 * sizeSq + gi1 * size + bi1) * 3
            val base100 = (ri1 * sizeSq + gi0 * size + bi0) * 3
            val base101 = (ri1 * sizeSq + gi0 * size + bi1) * 3
            val base110 = (ri1 * sizeSq + gi1 * size + bi0) * 3
            val base111 = (ri1 * sizeSq + gi1 * size + bi1) * 3

            var outR = 0
            var outG = 0
            var outB = 0

            for (ch in 0..2) {
                val c000 = lut[base000 + ch]
                val c001 = lut[base001 + ch]
                val c010 = lut[base010 + ch]
                val c011 = lut[base011 + ch]
                val c100 = lut[base100 + ch]
                val c101 = lut[base101 + ch]
                val c110 = lut[base110 + ch]
                val c111 = lut[base111 + ch]

                val c00 = c000 + (c001 - c000) * db
                val c01 = c010 + (c011 - c010) * db
                val c10 = c100 + (c101 - c100) * db
                val c11 = c110 + (c111 - c110) * db

                val c0 = c00 + (c01 - c00) * dg
                val c1 = c10 + (c11 - c10) * dg

                val v = ((c0 + (c1 - c0) * dr) * 255f).roundToInt().coerceIn(0, 255)
                when (ch) {
                    0 -> outR = v
                    1 -> outG = v
                    2 -> outB = v
                }
            }

            pixels[i] = a or (outR shl 16) or (outG shl 8) or outB
        }
    }
}
