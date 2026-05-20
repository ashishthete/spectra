package com.spectra.app.ui.hud

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize

/**
 * Renders a focus-peaking overlay on top of the camera preview.
 *
 * [edgeData] is an IntArray of pre-rendered ARGB pixels produced by
 * [com.spectra.camera.FocusPeakingProcessor]. In-focus edges are colored
 * (with alpha), and everything else is transparent (0x00000000).
 * The overlay is stretched to fill the available space.
 */
@Composable
fun FocusPeakingOverlay(
    edgeData: IntArray?,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    if (edgeData == null || width <= 0 || height <= 0) return
    if (edgeData.size != width * height) return

    val peakBitmap = remember(edgeData, width, height) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(edgeData, 0, width, 0, 0, width, height)
        bmp
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = peakBitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )
    }
}
