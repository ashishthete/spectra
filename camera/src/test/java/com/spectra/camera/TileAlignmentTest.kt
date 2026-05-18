package com.spectra.camera

import org.junit.Assert.*
import org.junit.Test

class TileAlignmentTest {
    private val aligner = TileAligner()

    @Test
    fun `zero shift when frames are identical`() {
        val w = 128
        val h = 128
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 2).coerceIn(0, 255)
            val g = (y * 2).coerceIn(0, 255)
            val b = (x + y).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val shifts = aligner.estimateTileShifts(pixels, pixels, w, h)
        for ((idx, s) in shifts.withIndex()) {
            assertEquals("Tile $idx dx should be 0", 0, s.first)
            assertEquals("Tile $idx dy should be 0", 0, s.second)
        }
    }

    @Test
    fun `detects known global shift`() {
        val w = 256
        val h = 256
        val ref = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = x.coerceIn(0, 255)
            val g = y.coerceIn(0, 255)
            val b = ((x + y) / 2).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val shiftX = 8
        val shiftY = 4
        val shifted = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val srcX = (x - shiftX).coerceIn(0, w - 1)
            val srcY = (y - shiftY).coerceIn(0, h - 1)
            ref[srcY * w + srcX]
        }
        val shifts = aligner.estimateTileShifts(ref, shifted, w, h)
        var closeCount = 0
        for (s in shifts) {
            if (kotlin.math.abs(s.first - shiftX) <= 12 &&
                kotlin.math.abs(s.second - shiftY) <= 12) {
                closeCount++
            }
        }
        assertTrue("Most tiles should detect shift near ($shiftX,$shiftY): $closeCount/${shifts.size}",
            closeCount > shifts.size / 2)
    }

    @Test
    fun `applyTileShifts with zero shifts returns same pixels`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFF112233.toInt() }
        val shifts = Array(1) { Pair(0, 0) }
        val result = aligner.applyTileShifts(pixels, shifts, w, h)
        assertArrayEquals(pixels, result)
    }

    @Test
    fun `applyTileShifts shifts pixels correctly`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (x shl 16) or (y shl 8) or 0
        }
        val shifts = Array(1) { Pair(4, 4) }
        val result = aligner.applyTileShifts(pixels, shifts, w, h)
        val centerIdx = 32 * w + 32
        val srcX = (32 + 4).coerceIn(0, w - 1)
        val srcY = (32 + 4).coerceIn(0, h - 1)
        assertEquals(pixels[srcY * w + srcX], result[centerIdx])
    }
}
