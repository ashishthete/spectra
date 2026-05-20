package com.spectra.camera

import kotlin.math.abs

class TileAligner(
    private val tileSize: Int = 64,
    private val searchRadius: Int = 8,
    private val downsampleScale: Int = 4
) {

    fun estimateTileShifts(
        refPixels: IntArray,
        framePixels: IntArray,
        w: Int,
        h: Int
    ): Array<Pair<Int, Int>> {
        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize
        val shifts = Array(tilesX * tilesY) { Pair(0, 0) }
        val sTile = tileSize / downsampleScale

        val refLum = toLuminance(refPixels)
        val frameLum = toLuminance(framePixels)

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tileX = tx * tileSize
                val tileY = ty * tileSize
                var bestDx = 0
                var bestDy = 0
                var bestSad = Long.MAX_VALUE

                var bestCount = 0
                for (dy in -searchRadius..searchRadius) {
                    for (dx in -searchRadius..searchRadius) {
                        var sad = 0L
                        var count = 0
                        for (sy in 0 until sTile) {
                            val ry = tileY + sy * downsampleScale
                            val fy = ry + dy * downsampleScale
                            if (ry !in 0 until h || fy !in 0 until h) continue
                            for (sx in 0 until sTile) {
                                val rx = tileX + sx * downsampleScale
                                val fx = rx + dx * downsampleScale
                                if (rx !in 0 until w || fx !in 0 until w) continue
                                sad += abs(refLum[ry * w + rx] - frameLum[fy * w + fx])
                                count++
                            }
                        }
                        if (count > 0) {
                            val avgSad = sad.toDouble() / count
                            val bestAvg = if (bestCount > 0) bestSad.toDouble() / bestCount else Double.MAX_VALUE
                            if (avgSad < bestAvg || (avgSad == bestAvg && count > bestCount)) {
                                bestSad = sad
                                bestCount = count
                                bestDx = dx * downsampleScale
                                bestDy = dy * downsampleScale
                            }
                        }
                    }
                }
                shifts[ty * tilesX + tx] = Pair(bestDx, bestDy)
            }
        }
        return shifts
    }

    data class SubPixelShift(val dx: Float, val dy: Float)

    fun estimateSubPixelShifts(
        refPixels: IntArray,
        framePixels: IntArray,
        w: Int,
        h: Int
    ): Array<SubPixelShift> {
        val intShifts = estimateTileShifts(refPixels, framePixels, w, h)
        val tilesX = (w + tileSize - 1) / tileSize
        val refLum = toLuminance(refPixels)
        val frameLum = toLuminance(framePixels)

        return Array(intShifts.size) { idx ->
            val (bestDx, bestDy) = intShifts[idx]
            val tx = idx % tilesX
            val ty = idx / tilesX
            val tileX = tx * tileSize
            val tileY = ty * tileSize

            fun sadAt(dx: Int, dy: Int): Long {
                var sad = 0L
                for (py in 0 until tileSize step downsampleScale) {
                    val ry = tileY + py; val fy = ry + dy
                    if (ry !in 0 until h || fy !in 0 until h) continue
                    for (px in 0 until tileSize step downsampleScale) {
                        val rx = tileX + px; val fx = rx + dx
                        if (rx !in 0 until w || fx !in 0 until w) continue
                        sad += abs(refLum[ry * w + rx] - frameLum[fy * w + fx])
                    }
                }
                return sad
            }

            val cSad = sadAt(bestDx, bestDy).toFloat()
            val lSad = sadAt(bestDx - 1, bestDy).toFloat()
            val rSad = sadAt(bestDx + 1, bestDy).toFloat()
            val tSad = sadAt(bestDx, bestDy - 1).toFloat()
            val bSad = sadAt(bestDx, bestDy + 1).toFloat()

            val denomX = (lSad + rSad - 2f * cSad)
            val subDx = if (denomX > 1f) (lSad - rSad) / (2f * denomX) else 0f
            val denomY = (tSad + bSad - 2f * cSad)
            val subDy = if (denomY > 1f) (tSad - bSad) / (2f * denomY) else 0f

            SubPixelShift(bestDx + subDx.coerceIn(-0.5f, 0.5f), bestDy + subDy.coerceIn(-0.5f, 0.5f))
        }
    }

    fun applySubPixelShifts(
        pixels: IntArray,
        shifts: Array<SubPixelShift>,
        w: Int,
        h: Int
    ): IntArray {
        val tilesX = (w + tileSize - 1) / tileSize
        val result = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val tx = (x / tileSize).coerceAtMost(tilesX - 1)
                val ty = (y / tileSize).coerceAtMost(shifts.size / tilesX - 1)
                val shift = shifts[ty * tilesX + tx]
                val srcXf = x + shift.dx
                val srcYf = y + shift.dy
                result[y * w + x] = bilinearSample(pixels, w, h, srcXf, srcYf)
            }
        }
        return result
    }

    private fun bilinearSample(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 2)
        val y0 = y.toInt().coerceIn(0, h - 2)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val p00 = pixels[y0 * w + x0]; val p10 = pixels[y0 * w + x0 + 1]
        val p01 = pixels[(y0 + 1) * w + x0]; val p11 = pixels[(y0 + 1) * w + x0 + 1]
        fun lerp(c: Int): Int {
            val v00 = (p00 shr c) and 0xFF; val v10 = (p10 shr c) and 0xFF
            val v01 = (p01 shr c) and 0xFF; val v11 = (p11 shr c) and 0xFF
            val top = v00 + (v10 - v00) * fx
            val bot = v01 + (v11 - v01) * fx
            return (top + (bot - top) * fy).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    fun applyTileShifts(
        pixels: IntArray,
        shifts: Array<Pair<Int, Int>>,
        w: Int,
        h: Int
    ): IntArray {
        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = shifts.size / tilesX
        val result = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val tx = (x / tileSize).coerceAtMost(tilesX - 1)
                val ty = (y / tileSize).coerceAtMost(tilesY - 1)
                val (dx, dy) = shifts[ty * tilesX + tx]
                val srcX = (x + dx).coerceIn(0, w - 1)
                val srcY = (y + dy).coerceIn(0, h - 1)
                result[y * w + x] = pixels[srcY * w + srcX]
            }
        }
        return result
    }

    private fun toLuminance(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8
        }
}
