package com.spectra.app.ui.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val LENS_STOPS = floatArrayOf(0.6f, 1f, 2f, 5f, 10f)

@Composable
fun ZoomDial(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val stops = LENS_STOPS.filter { it <= maxZoomRatio }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    val trackHeightDp = 260.dp
    val trackHeightPx = with(LocalDensity.current) { trackHeightDp.toPx() }

    Box(
        modifier = modifier
            .width(52.dp)
            .height(trackHeightDp),
        contentAlignment = Alignment.Center
    ) {
        // Vertical drag track — the full height is draggable
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(trackHeightDp)
                .clip(RoundedCornerShape(26.dp))
                .background(HudColors.surfaceGlass)
                .border(1.dp, HudColors.borderLight, RoundedCornerShape(26.dp))
                .pointerInput(maxZoomRatio) {
                    detectDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Drag up = zoom in, drag down = zoom out
                            dragAccumulator += -dragAmount.y
                            val logMin = ln(0.6f)
                            val logMax = ln(maxZoomRatio)
                            val logRange = logMax - logMin
                            val logStep = (dragAccumulator / trackHeightPx) * logRange
                            if (kotlin.math.abs(logStep) > 0.005f) {
                                val logCur = ln(zoomRatio)
                                val logNew = (logCur + logStep).coerceIn(logMin, logMax)
                                onZoomChanged(exp(logNew))
                                dragAccumulator = 0f
                            }
                        }
                    )
                }
                .pointerInput(maxZoomRatio) {
                    detectTapGestures { offset ->
                        // Tap on track to set zoom by position
                        val fraction = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        val logMin = ln(0.6f)
                        val logMax = ln(maxZoomRatio)
                        val logNew = logMin + fraction * (logMax - logMin)
                        onZoomChanged(exp(logNew))
                    }
                }
        )

        // Lens stop buttons arranged vertically
        Column(
            modifier = Modifier
                .height(trackHeightDp)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Reverse: highest zoom at top, lowest at bottom
            stops.reversed().forEach { stop ->
                val isActive = isNearStop(zoomRatio, stop)
                LensStopButton(
                    label = formatStop(stop),
                    isActive = isActive,
                    onClick = { onZoomChanged(stop) }
                )
            }
        }

        // Current zoom indicator — floating label next to the track
        val logMin = ln(0.6f)
        val logMax = ln(maxZoomRatio)
        val fraction = if (logMax > logMin) (ln(zoomRatio) - logMin) / (logMax - logMin) else 0f
        val offsetY = ((1f - fraction) - 0.5f) * (trackHeightDp.value - 40f)
        val isAtStop = stops.any { isNearStop(zoomRatio, it) }

        if (!isAtStop) {
            Box(
                modifier = Modifier
                    .offset(x = (-42).dp, y = offsetY.dp)
                    .background(HudColors.accent.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatZoom(zoomRatio),
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LensStopButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) HudColors.accent else Color.Transparent,
        animationSpec = tween(150),
        label = "lensBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) Color.Black else Color.White,
        animationSpec = tween(150),
        label = "lensText"
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (!isActive) Modifier.border(0.5.dp, Color(0xFF555555), CircleShape)
                else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun isNearStop(current: Float, stop: Float): Boolean {
    val tolerance = if (stop < 1f) 0.08f else stop * 0.08f
    return kotlin.math.abs(current - stop) < tolerance
}

private fun formatStop(ratio: Float): String = when {
    ratio < 1f -> ".${(ratio * 10).roundToInt()}"
    ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
    else -> "%.1f".format(ratio)
}

private fun formatZoom(ratio: Float): String = when {
    ratio < 1f -> "%.1fx".format(ratio)
    ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
    else -> "%.1fx".format(ratio)
}
