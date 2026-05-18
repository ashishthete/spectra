package com.spectra.app.ui.pro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun Histogram(
    histogramData: IntArray,
    modifier: Modifier = Modifier
) {
    if (histogramData.size < 256) return

    Canvas(
        modifier = modifier
            .width(140.dp)
            .height(60.dp)
            .background(HudColors.background.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        val w = size.width
        val h = size.height
        val max = histogramData.max().coerceAtLeast(1)
        val binWidth = w / 256f

        val path = Path().apply {
            moveTo(0f, h)
            for (i in 0 until 256) {
                val barH = (histogramData[i].toFloat() / max) * h
                lineTo(i * binWidth, h - barH)
            }
            lineTo(w, h)
            close()
        }

        drawPath(
            path = path,
            color = HudColors.accent.copy(alpha = 0.6f),
            style = Fill
        )

        drawLine(
            color = Color.Red.copy(alpha = 0.3f),
            start = Offset(0f, h),
            end = Offset(0f, 0f),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.Red.copy(alpha = 0.3f),
            start = Offset(w, h),
            end = Offset(w, 0f),
            strokeWidth = 2f
        )
    }
}

fun computeHistogram(bitmap: android.graphics.Bitmap): IntArray {
    val histogram = IntArray(256)
    val w = bitmap.width
    val h = bitmap.height
    val stepX = maxOf(1, w / 64)
    val stepY = maxOf(1, h / 64)

    for (y in 0 until h step stepY) {
        for (x in 0 until w step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val lum = (((pixel shr 16) and 0xFF) * 77 +
                    ((pixel shr 8) and 0xFF) * 150 +
                    (pixel and 0xFF) * 29) shr 8
            histogram[lum.coerceIn(0, 255)]++
        }
    }
    return histogram
}
