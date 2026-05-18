package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spectra.core.model.GridMode

@Composable
fun CrosshairAndGrid(gridMode: GridMode = GridMode.THIRDS, modifier: Modifier = Modifier) {
    if (gridMode == GridMode.OFF) return

    val gridColor = Color.White.copy(alpha = 0.10f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val stroke = 0.5.dp.toPx()

        when (gridMode) {
            GridMode.THIRDS -> {
                drawLine(gridColor, Offset(w / 3, 0f), Offset(w / 3, h), stroke)
                drawLine(gridColor, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), stroke)
                drawLine(gridColor, Offset(0f, h / 3), Offset(w, h / 3), stroke)
                drawLine(gridColor, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), stroke)
            }
            GridMode.GOLDEN -> {
                val phi = 0.618f
                val g1X = w * (1f - phi)
                val g2X = w * phi
                val g1Y = h * (1f - phi)
                val g2Y = h * phi
                drawLine(gridColor, Offset(g1X, 0f), Offset(g1X, h), stroke)
                drawLine(gridColor, Offset(g2X, 0f), Offset(g2X, h), stroke)
                drawLine(gridColor, Offset(0f, g1Y), Offset(w, g1Y), stroke)
                drawLine(gridColor, Offset(0f, g2Y), Offset(w, g2Y), stroke)
            }
            GridMode.DIAGONAL -> {
                drawLine(gridColor, Offset(0f, 0f), Offset(w, h), stroke)
                drawLine(gridColor, Offset(w, 0f), Offset(0f, h), stroke)
                drawLine(gridColor, Offset(w / 2, 0f), Offset(w / 2, h), stroke)
                drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), stroke)
            }
            GridMode.OFF -> {}
        }
    }
}
