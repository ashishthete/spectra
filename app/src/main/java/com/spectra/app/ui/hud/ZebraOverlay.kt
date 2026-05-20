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
        // zebraData already contains pre-rendered ARGB pixels from ZebraProcessor
        bmp.setPixels(zebraData, 0, width, 0, 0, width, height)
        bmp
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = zebraBitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
    }
}
