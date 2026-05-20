package com.spectra.camera

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaplacianSharpenerTest {

    @Test
    fun `high ISO produces no sharpening`() {
        val params = LaplacianSharpener.SharpParams.forIso(6400)
        assertEquals(1.0f, params.fineGain)
        assertEquals(1.0f, params.midGain)
        assertEquals(1.0f, params.coarseGain)
    }

    @Test
    fun `low ISO produces strongest sharpening`() {
        val params = LaplacianSharpener.SharpParams.forIso(100)
        assertTrue(params.fineGain > 1.2f)
        assertTrue(params.midGain > 1.1f)
    }

    @Test
    fun `mid ISO reduces sharpening strength`() {
        val low = LaplacianSharpener.SharpParams.forIso(100)
        val mid = LaplacianSharpener.SharpParams.forIso(1200)
        assertTrue(mid.fineGain < low.fineGain)
    }

    @Test
    fun `sharpen does not crash on small bitmap`() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(32 * 32) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        bmp.setPixels(pixels, 0, 32, 0, 0, 32, 32)
        LaplacianSharpener.sharpen(bmp)
        val output = IntArray(32 * 32)
        bmp.getPixels(output, 0, 32, 0, 0, 32, 32)
        for (p in output) {
            val r = (p shr 16) and 0xFF
            assertTrue("Red channel out of range: $r", r in 0..255)
        }
        bmp.recycle()
    }

    @Test
    fun `unity gains leave pixels unchanged`() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16 * 16) { i ->
            val v = (i * 15) % 256
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, 16, 0, 0, 16, 16)
        val before = pixels.copyOf()

        LaplacianSharpener.sharpen(bmp, LaplacianSharpener.SharpParams(
            fineGain = 1.0f, midGain = 1.0f, coarseGain = 1.0f
        ))

        val after = IntArray(16 * 16)
        bmp.getPixels(after, 0, 16, 0, 0, 16, 16)
        assertTrue("Unity gains should not change pixels", before.contentEquals(after))
        bmp.recycle()
    }

    @Test
    fun `sharpen increases contrast on edges`() {
        val w = 64; val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val v = if (x < w / 2) 60 else 200
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val beforePixels = pixels.copyOf()
        LaplacianSharpener.sharpen(bmp, LaplacianSharpener.SharpParams(
            fineGain = 1.8f, midGain = 1.4f, coarseGain = 1.0f, noiseThreshold = 2f
        ))

        val after = IntArray(w * h)
        bmp.getPixels(after, 0, w, 0, 0, w, h)

        val edgeX = w / 2
        val beforeEdgeDiff = kotlin.math.abs(
            ((beforePixels[h / 2 * w + edgeX - 1] shr 16) and 0xFF) -
            ((beforePixels[h / 2 * w + edgeX] shr 16) and 0xFF)
        )
        val afterEdgeDiff = kotlin.math.abs(
            ((after[h / 2 * w + edgeX - 1] shr 16) and 0xFF) -
            ((after[h / 2 * w + edgeX] shr 16) and 0xFF)
        )
        assertTrue("Edge contrast should increase: before=$beforeEdgeDiff, after=$afterEdgeDiff",
            afterEdgeDiff >= beforeEdgeDiff)
        bmp.recycle()
    }
}
