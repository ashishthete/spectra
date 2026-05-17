package com.spectra.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class HdrProcessor {

    companion object {
        fun computeBracketExposures(baseExposureNs: Long, baseIso: Int): List<Pair<Long, Int>> {
            val underExposure = baseExposureNs / 4
            val overExposure = baseExposureNs * 4
            return listOf(
                Pair(underExposure, baseIso),
                Pair(baseExposureNs, baseIso),
                Pair(overExposure, baseIso)
            )
        }

        fun contrastWeight(luminance: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
            val idx = y * w + x
            val center = luminance[idx]
            var sum = 0f
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dy == 0 && dx == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        sum += abs(luminance[ny * w + nx] - center)
                        count++
                    }
                }
            }
            return if (count > 0) sum / count else 0f
        }

        fun wellExposednessWeight(value: Float): Float {
            val diff = value - 0.5f
            return exp((-12.5f * diff * diff).toDouble()).toFloat()
        }

        fun saturationWeight(r: Float, g: Float, b: Float): Float {
            val mean = (r + g + b) / 3f
            val variance = ((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f
            return kotlin.math.sqrt(variance.toDouble()).toFloat()
        }
    }

    fun mertensFusion(frames: List<IntArray>, width: Int, height: Int): IntArray {
        val numFrames = frames.size
        if (numFrames == 0) return IntArray(0)
        if (numFrames == 1) return frames[0].copyOf()

        val n = width * height
        val weightMaps = Array(numFrames) { FloatArray(n) }

        for (f in 0 until numFrames) {
            val pixels = frames[f]
            val luminance = FloatArray(n)
            for (i in 0 until n) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val r = ((pixels[i] shr 16) and 0xFF) / 255f
                    val g = ((pixels[i] shr 8) and 0xFF) / 255f
                    val b = (pixels[i] and 0xFF) / 255f

                    val contrast = if (x in 1 until width - 1 && y in 1 until height - 1) {
                        contrastWeight(luminance, width, height, x, y)
                    } else 0f
                    val saturation = saturationWeight(r, g, b)
                    val exposure = wellExposednessWeight(luminance[i])

                    weightMaps[f][i] = (contrast + 0.001f) * (saturation + 0.001f) * (exposure + 0.001f)
                }
            }
        }

        for (i in 0 until n) {
            var total = 0f
            for (f in 0 until numFrames) total += weightMaps[f][i]
            if (total > 0f) {
                for (f in 0 until numFrames) weightMaps[f][i] /= total
            } else {
                for (f in 0 until numFrames) weightMaps[f][i] = 1f / numFrames
            }
        }

        val result = IntArray(n)
        for (i in 0 until n) {
            var rSum = 0f; var gSum = 0f; var bSum = 0f
            for (f in 0 until numFrames) {
                val w = weightMaps[f][i]
                val pixel = frames[f][i]
                rSum += w * ((pixel shr 16) and 0xFF)
                gSum += w * ((pixel shr 8) and 0xFF)
                bSum += w * (pixel and 0xFF)
            }
            val rOut = rSum.toInt().coerceIn(0, 255)
            val gOut = gSum.toInt().coerceIn(0, 255)
            val bOut = bSum.toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        return result
    }

    fun alignFrame(reference: IntArray, target: IntArray, width: Int, height: Int, tileSize: Int = 16, searchRadius: Int = 4): IntArray {
        val aligned = IntArray(width * height)
        System.arraycopy(target, 0, aligned, 0, target.size)

        val refLum = IntArray(width * height)
        val tgtLum = IntArray(width * height)
        for (i in reference.indices) {
            refLum[i] = luminance(reference[i])
            tgtLum[i] = luminance(target[i])
        }

        val tilesX = width / tileSize
        val tilesY = height / tileSize

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tileX = tx * tileSize
                val tileY = ty * tileSize

                var bestDx = 0
                var bestDy = 0
                var bestSad = Long.MAX_VALUE

                for (dy in -searchRadius..searchRadius) {
                    for (dx in -searchRadius..searchRadius) {
                        var sad = 0L
                        for (py in 0 until tileSize) {
                            for (px in 0 until tileSize) {
                                val rx = tileX + px
                                val ry = tileY + py
                                val sx = rx + dx
                                val sy = ry + dy
                                if (sx < 0 || sx >= width || sy < 0 || sy >= height) {
                                    sad += 128
                                    continue
                                }
                                sad += abs(refLum[ry * width + rx] - tgtLum[sy * width + sx])
                            }
                        }
                        if (sad < bestSad) {
                            bestSad = sad
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }

                for (py in 0 until tileSize) {
                    for (px in 0 until tileSize) {
                        val dstX = tileX + px
                        val dstY = tileY + py
                        val srcX = dstX + bestDx
                        val srcY = dstY + bestDy
                        if (srcX in 0 until width && srcY in 0 until height) {
                            aligned[dstY * width + dstX] = target[srcY * width + srcX]
                        }
                    }
                }
            }
        }

        return aligned
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
