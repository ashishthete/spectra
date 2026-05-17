package com.spectra.app.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.ai.model.ArrowDirection
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun CoachingDirective(
    text: String,
    arrowDirection: ArrowDirection,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "coaching")
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onDismiss() }
    ) {
        if (arrowDirection == ArrowDirection.UP) {
            ArrowText(symbol = "▲", alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (arrowDirection == ArrowDirection.LEFT) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowText(symbol = "◄", alpha = arrowAlpha)
                Spacer(modifier = Modifier.width(8.dp))
                CoachingBox(text = text)
                Spacer(modifier = Modifier.width(8.dp))
            }
        } else if (arrowDirection == ArrowDirection.RIGHT) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                CoachingBox(text = text)
                Spacer(modifier = Modifier.width(8.dp))
                ArrowText(symbol = "►", alpha = arrowAlpha)
            }
        } else if (arrowDirection == ArrowDirection.STEADY) {
            SteadyLabel(alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
            CoachingBox(text = text)
        } else {
            CoachingBox(text = text)
        }

        if (arrowDirection == ArrowDirection.DOWN) {
            Spacer(modifier = Modifier.height(4.dp))
            ArrowText(symbol = "▼", alpha = arrowAlpha)
        }
    }
}

@Composable
private fun ArrowText(symbol: String, alpha: Float) {
    Text(
        text = symbol,
        color = HudColors.neonGreen,
        fontSize = 16.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
private fun CoachingBox(text: String) {
    Text(
        text = text,
        style = HudTypography.coaching,
        modifier = Modifier
            .border(1.dp, HudColors.borderGreen, RectangleShape)
            .background(HudColors.background.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SteadyLabel(alpha: Float) {
    Text(
        text = "⊕ STEADY",
        color = HudColors.neonGreen,
        fontSize = 10.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.alpha(alpha)
    )
}
