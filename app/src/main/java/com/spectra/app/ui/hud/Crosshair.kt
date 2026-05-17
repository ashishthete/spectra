package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun CrosshairAndGrid(modifier: Modifier = Modifier) {
    val gridColor = HudColors.neonGreenGhost
    val crosshairColor = HudColors.neonGreenFaint
    val dotColor = HudColors.neonGreen

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val crossLen = 24.dp.toPx()

        val third1X = size.width / 3
        val third2X = size.width * 2 / 3
        val third1Y = size.height / 3
        val third2Y = size.height * 2 / 3

        drawLine(gridColor, Offset(third1X, 0f), Offset(third1X, size.height), 1.dp.toPx())
        drawLine(gridColor, Offset(third2X, 0f), Offset(third2X, size.height), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, third1Y), Offset(size.width, third1Y), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, third2Y), Offset(size.width, third2Y), 1.dp.toPx())

        drawLine(crosshairColor, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), 1.dp.toPx(), StrokeCap.Butt)
        drawLine(crosshairColor, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), 1.dp.toPx(), StrokeCap.Butt)

        drawCircle(dotColor, radius = 4.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f.dp.toPx()))
    }
}
