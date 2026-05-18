package com.spectra.app.ui.hud

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize

@Composable
fun ZebraOverlay(
    zebraData: IntArray?,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    if (zebraData == null || width <= 0 || height <= 0) return

    val zebraBitmap = remember(zebraData, width, height) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (zebraData[i] > 0) {
                    val stripe = ((x + y) / 4) % 2 == 0
                    pixels[i] = if (stripe) 0x80FF0000.toInt() else 0x40FF0000.toInt()
                } else {
                    pixels[i] = 0
                }
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        bmp
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = zebraBitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
    }
}
