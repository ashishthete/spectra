package com.spectra.camera

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExposureAnalysisTest {

    @Test
    fun `false color maps underexposed to cool colors`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF050505.toInt())

        val result = ExposureAnalysis.computeFalseColor(bitmap)
        assertTrue(result.isNotEmpty())
        val firstPixel = result[0]
        val b = firstPixel and 0xFF
        assertTrue("Dark pixels should map to cool colors", b > 0)
        bitmap.recycle()
    }

    @Test
    fun `false color maps overexposed to warm colors`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFEFEFE.toInt())

        val result = ExposureAnalysis.computeFalseColor(bitmap)
        assertTrue(result.isNotEmpty())
        val firstPixel = result[0]
        val r = (firstPixel shr 16) and 0xFF
        assertTrue("Bright pixels should map to red", r > 200)
        bitmap.recycle()
    }

    @Test
    fun `false color maps midtones to neutral`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())

        val result = ExposureAnalysis.computeFalseColor(bitmap)
        assertTrue(result.isNotEmpty())
        val firstPixel = result[0]
        val r = (firstPixel shr 16) and 0xFF
        val g = (firstPixel shr 8) and 0xFF
        val b = firstPixel and 0xFF
        assertTrue("Midtones should be gray-ish", r == g && g == b)
        bitmap.recycle()
    }

    @Test
    fun `waveform output has correct dimensions`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())

        val waveform = ExposureAnalysis.computeWaveform(bitmap, 256, 128)
        assertEquals(256 * 128, waveform.size)
        bitmap.recycle()
    }

    @Test
    fun `waveform has non-zero values for uniform image`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())

        val waveform = ExposureAnalysis.computeWaveform(bitmap, 256, 128)
        val sum = waveform.sum()
        assertTrue("Waveform should have counts", sum > 0)
        bitmap.recycle()
    }
}
