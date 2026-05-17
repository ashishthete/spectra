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
fun CornerBrackets(modifier: Modifier = Modifier) {
    val color = HudColors.neonGreen
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 2.dp.toPx()
        val bracketLen = 32.dp.toPx()
        val margin = 16.dp.toPx()

        val corners = listOf(
            Pair(Offset(margin, margin), Pair(Offset(margin + bracketLen, margin), Offset(margin, margin + bracketLen))),
            Pair(Offset(size.width - margin, margin), Pair(Offset(size.width - margin - bracketLen, margin), Offset(size.width - margin, margin + bracketLen))),
            Pair(Offset(margin, size.height - margin), Pair(Offset(margin + bracketLen, size.height - margin), Offset(margin, size.height - margin - bracketLen))),
            Pair(Offset(size.width - margin, size.height - margin), Pair(Offset(size.width - margin - bracketLen, size.height - margin), Offset(size.width - margin, size.height - margin - bracketLen)))
        )

        for ((corner, lines) in corners) {
            drawLine(color, corner, lines.first, strokeWidth, StrokeCap.Butt)
            drawLine(color, corner, lines.second, strokeWidth, StrokeCap.Butt)
        }
    }
}
