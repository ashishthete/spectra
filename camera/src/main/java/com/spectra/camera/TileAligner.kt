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
