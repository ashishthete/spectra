package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun MiniHistogram(
    histogramData: IntArray,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (histogramData.isEmpty()) return
    val maxVal = histogramData.max().coerceAtLeast(1)

    Canvas(
        modifier = modifier
            .width(64.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onClick() }
    ) {
        val w = size.width
        val h = size.height
        val binWidth = w / 256f

        for (i in 0 until 256) {
            val barH = (histogramData[i].toFloat() / maxVal) * h
            drawLine(
                color = HudColors.accent.copy(alpha = 0.8f),
                start = Offset(i * binWidth, h),
                end = Offset(i * binWidth, h - barH),
                strokeWidth = binWidth.coerceAtLeast(0.5f)
            )
        }
    }
}
