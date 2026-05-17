package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CrosshairAndGrid(modifier: Modifier = Modifier) {
    val gridColor = Color.White.copy(alpha = 0.10f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val third1X = size.width / 3
        val third2X = size.width * 2 / 3
        val third1Y = size.height / 3
        val third2Y = size.height * 2 / 3
        val stroke = 0.5.dp.toPx()

        drawLine(gridColor, Offset(third1X, 0f), Offset(third1X, size.height), stroke)
        drawLine(gridColor, Offset(third2X, 0f), Offset(third2X, size.height), stroke)
        drawLine(gridColor, Offset(0f, third1Y), Offset(size.width, third1Y), stroke)
        drawLine(gridColor, Offset(0f, third2Y), Offset(size.width, third2Y), stroke)
    }
}
