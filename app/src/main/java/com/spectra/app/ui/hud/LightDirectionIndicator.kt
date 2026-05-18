package com.spectra.app.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LightDirectionIndicator(
    angleDegrees: Float,
    strength: Float,
    modifier: Modifier = Modifier
) {
    if (strength < 0.15f) return

    val opacity = ((strength - 0.15f) / (0.8f - 0.15f)).coerceIn(0f, 1f)
    val arrowColor = HudColors.accent.copy(alpha = opacity)
    val bgColor = HudColors.surfaceGlass.copy(alpha = opacity * 0.85f)

    Canvas(
        modifier = modifier
            .size(32.dp)
            .background(bgColor, CircleShape)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shaftLen = size.width * 0.28f
        val headLen = size.width * 0.18f
        val headHalf = size.width * 0.10f

        rotate(degrees = -angleDegrees, pivot = Offset(cx, cy)) {
            val tipX = cx + shaftLen + headLen
            val tailX = cx - shaftLen

            val arrowPath = Path().apply {
                moveTo(tailX, cy)
                lineTo(cx + shaftLen, cy)
                moveTo(cx + shaftLen, cy - headHalf)
                lineTo(tipX, cy)
                lineTo(cx + shaftLen, cy + headHalf)
                close()
            }

            drawPath(path = arrowPath, color = arrowColor)
        }
    }
}
