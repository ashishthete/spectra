package com.spectra.app.ui.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun CoachingDirective(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arrow")
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "arrowPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onDismiss() }
    ) {
        Text(
            text = "▲",
            color = HudColors.neonGreen,
            fontSize = 18.sp,
            modifier = Modifier.alpha(arrowAlpha)
        )
        Text(
            text = text,
            style = HudTypography.coaching,
            modifier = Modifier
                .border(1.dp, HudColors.borderGreen, RectangleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
