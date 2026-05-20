package com.spectra.camera

import kotlin.math.abs
import kotlin.math.roundToInt

class TemporalDenoiser(
    private val motionThreshold: Int = 30,
    private val blendStrength: Float = 0.7f
) {

    private var previousFrame: IntArray? = null
    private var accumulatedFrame: IntArray? = null

    fun processFrame(pixels: IntArray, w: Int, h: Int, iso: Int): IntArray {
        val prev = accumulatedFrame
        if (prev == null || prev.size != pixels.size) {
            accumulatedFrame = pixels.copyOf()
            previousFrame = pixels.copyOf()
            return pixels
        }

        val strength = isoAdaptiveStrength(iso)
        if (strength <= 0f) {
            accumulatedFrame = pixels.copyOf()
            previousFrame = pixels.copyOf()
            return pixels
        }

        val result = IntArray(pixels.size)
        val motionMap = detectMotion(previousFrame!!, pixels, w, h)
        val blend = blendStrength * strength

        for (i in pixels.indices) {
            if (motionMap[i]) {
                result[i] = pixels[i]
                accumulatedFrame!![i] = pixels[i]
            } else {
                val curR = (pixels[i] shr 16) and 0xFF
                val curG = (pixels[i] shr 8) and 0xFF
                val curB = pixels[i] and 0xFF
                val accR = (prev[i] shr 16) and 0xFF
                val accG = (prev[i] shr 8) and 0xFF
                val accB = prev[i] and 0xFF
                val r = (accR + blend * (curR - accR)).roundToInt().coerceIn(0, 255)
                val g = (accG + blend * (curG - accG)).roundToInt().coerceIn(0, 255)
                val b = (accB + blend * (curB - accB)).roundToInt().coerceIn(0, 255)
                result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                accumulatedFrame!![i] = result[i]
            }
        }

        previousFrame = pixels.copyOf()
        return result
    }

    private fun detectMotion(prev: IntArray, curr: IntArray, w: Int, h: Int): BooleanArray {
        val motion = BooleanArray(prev.size)
        val scale = 4
        for (y in 0 until h step scale) {
            for (x in 0 until w step scale) {
                val i = y * w + x
                if (i >= prev.size) continue
                val prevLum = ((prev[i] shr 16) and 0xFF) * 77 + ((prev[i] shr 8) and 0xFF) * 150 + (prev[i] and 0xFF) * 29
                val currLum = ((curr[i] shr 16) and 0xFF) * 77 + ((curr[i] shr 8) and 0xFF) * 150 + (curr[i] and 0xFF) * 29
                val diff = abs(prevLum - currLum) shr 8
                if (diff > motionThreshold) {
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            val px = x + dx; val py = y + dy
                            if (px < w && py < h) motion[py * w + px] = true
                        }
                    }
                }
            }
        }
        return motion
    }

    private fun isoAdaptiveStrength(iso: Int): Float = when {
        iso <= 200 -> 0f
        iso <= 800 -> (iso - 200f) / 600f
        else -> 1f
    }

    fun reset() {
        previousFrame = null
        accumulatedFrame = null
    }
}
