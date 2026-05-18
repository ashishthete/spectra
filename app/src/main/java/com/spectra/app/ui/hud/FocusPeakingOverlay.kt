package com.spectra.app.ui.hud

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

@Composable
fun FocusPeakingOverlay(
    edgeData: IntArray?,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    if (edgeData == null || width <= 0 || height <= 0) return

    val peakBitmap = remember(edgeData, width, height) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in edgeData.indices) {
            val edge = edgeData[i]
            pixels[i] = if (edge > 30) {
                val alpha = (edge * 3).coerceAtMost(200)
                (alpha shl 24) or 0x00FF00
            } else {
                0
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        bmp
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = peakBitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
    }
}
