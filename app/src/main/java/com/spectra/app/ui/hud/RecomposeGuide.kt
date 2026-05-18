package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.spectra.app.ui.theme.HudColors

@Composable
fun RecomposeGuide(
    isLocked: Boolean,
    lockPointX: Float,
    lockPointY: Float,
    safeRadiusFraction: Float = 0.15f,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = lockPointX * size.width
            val centerY = lockPointY * size.height
            val radius = safeRadiusFraction * minOf(size.width, size.height)

            drawCircle(
                color = HudColors.accent.copy(alpha = 0.4f),
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            )

            drawCircle(
                color = HudColors.accent,
                radius = 6f,
                center = Offset(centerX, centerY)
            )
        }
    }
}

object RecomposeCalculator {
    fun safeRadiusFraction(
        focusDistanceM: Float,
        focalLengthMm: Float = 6.4f,
        aperture: Float = 1.7f
    ): Float {
        val dofFactor = (focusDistanceM * focusDistanceM * aperture) / (focalLengthMm * 0.001f)
        return (dofFactor * 0.01f).coerceIn(0.05f, 0.25f)
    }
}
