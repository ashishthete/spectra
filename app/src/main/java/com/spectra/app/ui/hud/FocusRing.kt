package com.spectra.app.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors

@Composable
fun FocusRing(
    focusX: Float,
    focusY: Float,
    isFocusing: Boolean,
    focusSuccess: Boolean,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(1.6f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(isFocusing, focusX, focusY) {
        if (isFocusing) {
            alpha.snapTo(1f)
            scale.snapTo(1.6f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
            )
        }
    }

    LaunchedEffect(focusSuccess) {
        if (focusSuccess && !isFocusing) {
            kotlinx.coroutines.delay(800)
            alpha.animateTo(0f, animationSpec = tween(300))
        }
    }

    if (alpha.value > 0f) {
        val ringColor = if (focusSuccess && !isFocusing) {
            HudColors.success.copy(alpha = alpha.value)
        } else {
            Color.White.copy(alpha = alpha.value * 0.9f)
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val cx = focusX
            val cy = focusY
            val radius = 36.dp.toPx() * scale.value

            drawCircle(
                color = ringColor,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )

            val tick = 6.dp.toPx()
            drawLine(ringColor, Offset(cx, cy - radius - tick), Offset(cx, cy - radius + tick), 1.dp.toPx())
            drawLine(ringColor, Offset(cx, cy + radius - tick), Offset(cx, cy + radius + tick), 1.dp.toPx())
            drawLine(ringColor, Offset(cx - radius - tick, cy), Offset(cx - radius + tick, cy), 1.dp.toPx())
            drawLine(ringColor, Offset(cx + radius - tick, cy), Offset(cx + radius + tick, cy), 1.dp.toPx())
        }
    }
}
