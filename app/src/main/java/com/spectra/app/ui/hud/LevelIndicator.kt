package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LevelIndicator(
    angle: Float,
    modifier: Modifier = Modifier
) {
    val absAngle = abs(angle)
    val isLevel = absAngle < 2.0f
    val isTilted = absAngle >= 5.0f
    val displayAngle = if (isLevel) 0f else angle

    val amberWarning = HudColors.accent
    val lineColor = when {
        isLevel -> HudColors.success.copy(alpha = 0.8f)
        isTilted -> amberWarning.copy(alpha = 0.8f)
        else -> Color.White.copy(alpha = 0.4f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth(if (isTilted) 0.4f else 0.3f)
                .height(4.dp)
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val halfLen = size.width / 2
            val rad = Math.toRadians(displayAngle.toDouble().coerceIn(-15.0, 15.0))
            val dy = (sin(rad) * halfLen).toFloat()

            drawLine(
                color = lineColor,
                start = Offset(cx - halfLen, cy + dy),
                end = Offset(cx + halfLen, cy - dy),
                strokeWidth = if (isTilted) 3.dp.toPx() else if (isLevel) 2.dp.toPx() else 1.5f.dp.toPx(),
                cap = StrokeCap.Round
            )

            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        AnimatedVisibility(
            visible = isTilted,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "${absAngle.roundToInt()}°",
                color = amberWarning,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(HudColors.surfaceGlass, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun PitchIndicator(
    pitchAngle: Float,
    modifier: Modifier = Modifier
) {
    val absPitch = abs(pitchAngle)
    val isLevel = absPitch < 5.0f
    val isTilted = absPitch >= 15.0f

    val pitchAmber = HudColors.accent
    val barColor = when {
        isLevel -> HudColors.success.copy(alpha = 0.6f)
        isTilted -> pitchAmber.copy(alpha = 0.8f)
        else -> Color.White.copy(alpha = 0.35f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .width(4.dp)
                .height(80.dp)
        ) {
            val cx = size.width / 2
            val totalH = size.height

            // Background track
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(cx, 0f),
                end = Offset(cx, totalH),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Center tick mark (level reference)
            drawLine(
                color = barColor,
                start = Offset(0f, totalH / 2),
                end = Offset(size.width, totalH / 2),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Pitch indicator dot — moves up when tilted back, down when tilted forward
            val clampedPitch = pitchAngle.coerceIn(-45f, 45f)
            val normalized = clampedPitch / 45f
            val dotY = (totalH / 2) - (normalized * totalH / 2)
            drawCircle(
                color = barColor,
                radius = 5.dp.toPx(),
                center = Offset(cx, dotY)
            )
        }

        AnimatedVisibility(
            visible = isTilted,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "${absPitch.roundToInt()}°",
                color = pitchAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .background(HudColors.surfaceGlass, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}
