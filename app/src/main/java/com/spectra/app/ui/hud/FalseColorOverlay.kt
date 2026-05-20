package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap

@Composable
fun FalseColorOverlay(
    falseColorData: IntArray?,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    if (falseColorData == null || width <= 0 || height <= 0) return

    val imageBitmap: ImageBitmap = android.graphics.Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).let { bmp ->
        bmp.setPixels(falseColorData, 0, width, 0, 0, width, height)
        val result = bmp.asImageBitmap()
        result
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = imageBitmap,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            alpha = 0.6f
        )
    }
}
