package com.spectra.app.ui.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
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
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onDismiss() }
    ) {
        if (arrowDirection == ArrowDirection.UP) {
            ArrowText(symbol = "↑", alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (arrowDirection == ArrowDirection.STEADY) {
            SteadyLabel(alpha = arrowAlpha)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (arrowDirection == ArrowDirection.LEFT) {
                ArrowText(symbol = "←", alpha = arrowAlpha)
                Spacer(modifier = Modifier.width(6.dp))
            }

            CoachingBox(text = text)

            if (arrowDirection == ArrowDirection.RIGHT) {
                Spacer(modifier = Modifier.width(6.dp))
                ArrowText(symbol = "→", alpha = arrowAlpha)
            }
        }

        if (arrowDirection == ArrowDirection.DOWN) {
            Spacer(modifier = Modifier.height(4.dp))
            ArrowText(symbol = "↓", alpha = arrowAlpha)
        }
    }
}

@Composable
private fun ArrowText(symbol: String, alpha: Float) {
    Text(
        text = symbol,
        color = HudColors.accent,
        fontSize = 18.sp,
        fontFamily = FontFamily.SansSerif,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
private fun CoachingBox(text: String) {
    Text(
        text = text,
        style = HudTypography.coaching,
        modifier = Modifier
            .background(HudColors.surfaceGlass, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun SteadyLabel(alpha: Float) {
    Text(
        text = "● Hold steady",
        color = HudColors.accent,
        fontSize = 10.sp,
        fontFamily = FontFamily.SansSerif,
        modifier = Modifier.alpha(alpha)
    )
}
