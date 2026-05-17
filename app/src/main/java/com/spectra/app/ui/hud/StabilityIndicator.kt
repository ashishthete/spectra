package com.spectra.app.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun StabilityIndicator(
    motionLevel: Int,
    isNightMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isNightMode) return

    val infiniteTransition = rememberInfiniteTransition(label = "stability")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stabilityPulse"
    )

    val isStable = motionLevel <= 1
    val color = if (isStable) HudColors.neonGreen else HudColors.red
    val label = if (isStable) "STABLE" else "UNSTABLE"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "STABILITY",
            style = HudTypography.label
        )
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .width(60.dp)
                .height(6.dp)
        ) {
            val barWidth = size.width
            val barHeight = size.height
            drawRect(
                color = HudColors.borderGreen,
                size = Size(barWidth, barHeight),
                style = Stroke(width = 1f)
            )
            val fillRatio = when (motionLevel) {
                0 -> 1.0f
                1 -> 0.75f
                2 -> 0.5f
                3 -> 0.25f
                else -> 0.1f
            }
            val displayAlpha = if (!isStable) pulseAlpha else 1f
            drawRect(
                color = color.copy(alpha = displayAlpha),
                topLeft = Offset.Zero,
                size = Size(barWidth * fillRatio, barHeight)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = HudTypography.label,
            color = color
        )
    }
}
