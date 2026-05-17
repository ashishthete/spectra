package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.spectra.app.ui.theme.HudColors

@Composable
fun ScanLines(modifier: Modifier = Modifier) {
    val lineColor = HudColors.neonGreenScanLine
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineSpacing = 4f
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = lineColor,
                topLeft = Offset(0f, y),
                size = Size(size.width, 1f)
            )
            y += lineSpacing
        }
    }
}
