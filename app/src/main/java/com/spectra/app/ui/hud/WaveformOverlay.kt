package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformOverlay(
    waveformData: IntArray?,
    waveformWidth: Int = 256,
    waveformHeight: Int = 128,
    modifier: Modifier = Modifier
) {
    if (waveformData == null || waveformData.isEmpty()) return

    val maxVal = waveformData.max().coerceAtLeast(1)

    Canvas(
        modifier = modifier
            .width(160.dp)
            .height(80.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
    ) {
        val sx = size.width / waveformWidth
        val sy = size.height / waveformHeight

        for (x in 0 until waveformWidth) {
            for (y in 0 until waveformHeight) {
                val count = waveformData[y * waveformWidth + x]
                if (count > 0) {
                    val alpha = (count.toFloat() / maxVal).coerceIn(0.1f, 1f)
                    val drawY = size.height - (y * sy)
                    drawCircle(
                        color = Color.Green.copy(alpha = alpha),
                        radius = 0.8f,
                        center = Offset(x * sx, drawY)
                    )
                }
            }
        }
    }
}
