package com.spectra.camera

import org.junit.Assert.*
import org.junit.Test

class FocusStackerTest {
    private val stacker = FocusStacker()

    @Test
    fun `empty frames returns empty`() {
        val result = stacker.stack(emptyList(), 0, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `single frame returns copy`() {
        val pixels = IntArray(100) { 0xFF112233.toInt() }
        val result = stacker.stack(listOf(pixels), 10, 10)
        assertArrayEquals(pixels, result)
        assertNotSame(pixels, result) // must be a copy
    }

    @Test
    fun `picks sharper frame per region`() {
        val w = 64
        val h = 64
        val n = w * h

        val frame1 = IntArray(n) { i ->
            val x = i % w
            if (x < w / 2) {
                if (x % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            } else {
                0xFF808080.toInt()
            }
        }

        val frame2 = IntArray(n) { i ->
            val x = i % w
            if (x < w / 2) {
                0xFF808080.toInt()
            } else {
                if (x % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }

        val result = stacker.stack(listOf(frame1, frame2), w, h)

        var leftFromFrame1 = 0
        var rightFromFrame2 = 0
        val margin = 8
        for (y in margin until h - margin) {
            for (x in margin until w / 2 - margin) {
                if (result[y * w + x] == frame1[y * w + x]) leftFromFrame1++
            }
            for (x in w / 2 + margin until w - margin) {
                if (result[y * w + x] == frame2[y * w + x]) rightFromFrame2++
            }
        }

        val leftTotal = (h - 2 * margin) * (w / 2 - 2 * margin)
        val rightTotal = (h - 2 * margin) * (w / 2 - 2 * margin)

        assertTrue(
            "Left half should mostly come from frame1: $leftFromFrame1/$leftTotal",
            leftFromFrame1 > leftTotal * 0.7
        )
        assertTrue(
            "Right half should mostly come from frame2: $rightFromFrame2/$rightTotal",
            rightFromFrame2 > rightTotal * 0.7
        )
    }

    @Test
    fun `three frames picks sharpest per region`() {
        val w = 48
        val h = 24
        val n = w * h
        val third = w / 3

        // Frame 1: sharp in left third only
        val frame1 = IntArray(n) { i ->
            val x = i % w
            when {
                x < third -> if (x % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                else -> 0xFF808080.toInt()
            }
        }

        // Frame 2: sharp in middle third only
        val frame2 = IntArray(n) { i ->
            val x = i % w
            when {
                x in third until 2 * third -> if (x % 2 == 0) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
                else -> 0xFF808080.toInt()
            }
        }

        // Frame 3: sharp in right third only
        val frame3 = IntArray(n) { i ->
            val x = i % w
            when {
                x >= 2 * third -> if (x % 2 == 0) 0xFFDDDDDD.toInt() else 0xFF222222.toInt()
                else -> 0xFF808080.toInt()
            }
        }

        val result = stacker.stack(listOf(frame1, frame2, frame3), w, h)

        val margin = 6
        var leftOk = 0
        var midOk = 0
        var rightOk = 0

        for (y in margin until h - margin) {
            for (x in margin until third - margin) {
                if (result[y * w + x] == frame1[y * w + x]) leftOk++
            }
            for (x in third + margin until 2 * third - margin) {
                if (result[y * w + x] == frame2[y * w + x]) midOk++
            }
            for (x in 2 * third + margin until w - margin) {
                if (result[y * w + x] == frame3[y * w + x]) rightOk++
            }
        }

        val rows = h - 2 * margin
        val leftTotal = rows * (third - 2 * margin)
        val midTotal = rows * (third - 2 * margin)
        val rightTotal = rows * (w - 2 * third - 2 * margin)

        assertTrue("Left third from frame1: $leftOk/$leftTotal", leftOk > leftTotal * 0.7)
        assertTrue("Mid third from frame2: $midOk/$midTotal", midOk > midTotal * 0.7)
        assertTrue("Right third from frame3: $rightOk/$rightTotal", rightOk > rightTotal * 0.7)
    }
}
