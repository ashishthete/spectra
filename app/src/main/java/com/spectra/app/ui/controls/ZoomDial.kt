package com.spectra.app.ui.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun ZoomDial(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var lastAngle by remember { mutableFloatStateOf(0f) }

    val label = formatZoom(zoomRatio)

    val animatedSize by animateFloatAsState(
        targetValue = if (expanded) 140f else 44f,
        animationSpec = tween(200),
        label = "dialSize"
    )

    Box(
        modifier = modifier
            .size(animatedSize.dp)
            .clip(CircleShape)
            .background(HudColors.surfaceGlass)
            .border(
                if (expanded) 2.dp else 1.5.dp,
                if (expanded) HudColors.accent.copy(alpha = 0.6f) else HudColors.borderLight,
                CircleShape
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { expanded = !expanded }
                )
            }
            .pointerInput(expanded, maxZoomRatio) {
                if (!expanded) return@pointerInput
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                detectDragGestures(
                    onDragStart = { offset ->
                        lastAngle = atan2(
                            offset.y - centerY,
                            offset.x - centerX
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val currentAngle = atan2(
                            change.position.y - centerY,
                            change.position.x - centerX
                        )
                        var delta = currentAngle - lastAngle
                        if (delta > PI) delta -= (2 * PI).toFloat()
                        if (delta < -PI) delta += (2 * PI).toFloat()

                        val logMin = ln(1f)
                        val logMax = ln(maxZoomRatio)
                        val logCurrent = ln(zoomRatio)
                        val sensitivity = (logMax - logMin) / (1.5f * PI.toFloat())
                        val logNew = (logCurrent + delta * sensitivity).coerceIn(logMin, logMax)
                        onZoomChanged(kotlin.math.exp(logNew))

                        lastAngle = currentAngle
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (expanded) {
            DialRing(
                zoomRatio = zoomRatio,
                maxZoomRatio = maxZoomRatio,
                size = animatedSize
            )
        }

        Text(
            text = label,
            color = if (zoomRatio > 1.05f) HudColors.accent else Color.White,
            fontSize = if (expanded) 16.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DialRing(
    zoomRatio: Float,
    maxZoomRatio: Float,
    size: Float
) {
    val logMin = ln(1f)
    val logMax = ln(maxZoomRatio)
    val logCurrent = ln(zoomRatio)
    val fraction = if (logMax > logMin) (logCurrent - logMin) / (logMax - logMin) else 0f

    Canvas(modifier = Modifier.size(size.dp)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f - 12f

        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = radius,
            center = center,
            style = Stroke(width = 4f)
        )

        val totalSweep = 270f
        val startAngle = 135f
        drawArc(
            color = HudColors.accent,
            startAngle = startAngle,
            sweepAngle = totalSweep * fraction,
            useCenter = false,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )

        val tickCount = 12
        for (i in 0..tickCount) {
            val tickAngle = startAngle + (totalSweep * i / tickCount)
            val rad = Math.toRadians(tickAngle.toDouble())
            val isMajor = i % 3 == 0
            val innerR = if (isMajor) radius - 10f else radius - 6f
            val outerR = radius + 2f
            drawLine(
                color = Color.White.copy(alpha = if (isMajor) 0.5f else 0.25f),
                start = Offset(
                    center.x + innerR * kotlin.math.cos(rad).toFloat(),
                    center.y + innerR * kotlin.math.sin(rad).toFloat()
                ),
                end = Offset(
                    center.x + outerR * kotlin.math.cos(rad).toFloat(),
                    center.y + outerR * kotlin.math.sin(rad).toFloat()
                ),
                strokeWidth = if (isMajor) 2f else 1f
            )
        }

        val indicatorAngle = startAngle + totalSweep * fraction
        val indicatorRad = Math.toRadians(indicatorAngle.toDouble())
        drawCircle(
            color = HudColors.accent,
            radius = 5f,
            center = Offset(
                center.x + radius * kotlin.math.cos(indicatorRad).toFloat(),
                center.y + radius * kotlin.math.sin(indicatorRad).toFloat()
            )
        )
    }
}

private fun formatZoom(ratio: Float): String = when {
    ratio < 1f -> "%.1f".format(ratio)
    ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
    else -> "%.1fx".format(ratio)
}
