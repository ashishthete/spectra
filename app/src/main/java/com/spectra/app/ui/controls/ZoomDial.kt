package com.spectra.app.ui.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

private val ZOOM_STOPS = floatArrayOf(1f, 2f, 5f, 10f)

@Composable
fun ZoomDial(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    val label = formatZoom(zoomRatio)

    if (expanded) {
        ExpandedZoomBar(
            zoomRatio = zoomRatio,
            maxZoomRatio = maxZoomRatio,
            onZoomChanged = onZoomChanged,
            onCollapse = { expanded = false },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(HudColors.surfaceGlass)
                .border(1.5.dp, HudColors.borderLight, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { expanded = true }
                    )
                }
                .pointerInput(maxZoomRatio) {
                    detectDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += -dragAmount.y
                            val step = dragAccumulator / 80f
                            if (step != 0f) {
                                val newZoom = (zoomRatio + step * 0.5f).coerceIn(1f, maxZoomRatio)
                                onZoomChanged(newZoom)
                                dragAccumulator = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (zoomRatio > 1.05f) HudColors.accent else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ExpandedZoomBar(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stops = ZOOM_STOPS.filter { it <= maxZoomRatio }

    Row(
        modifier = modifier
            .background(HudColors.surfaceGlass, RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEach { stop ->
            val isActive = isNearStop(zoomRatio, stop)
            val animatedSize by animateFloatAsState(
                targetValue = if (isActive) 38f else 32f,
                animationSpec = tween(150),
                label = "stopSize"
            )

            Box(
                modifier = Modifier
                    .size(animatedSize.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) HudColors.accent.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .border(
                        if (isActive) 1.5.dp else 0.5.dp,
                        if (isActive) HudColors.accent else HudColors.borderLight,
                        CircleShape
                    )
                    .pointerInput(stop) {
                        detectTapGestures {
                            onZoomChanged(stop)
                            onCollapse()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatZoom(stop),
                    color = if (isActive) HudColors.accent else Color.White,
                    fontSize = if (isActive) 11.sp else 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun isNearStop(current: Float, stop: Float): Boolean {
    val tolerance = if (stop < 1f) 0.15f else stop * 0.15f
    return kotlin.math.abs(current - stop) < tolerance
}

private fun formatZoom(ratio: Float): String = when {
    ratio < 1f -> "%.1f".format(ratio)
    ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
    else -> "%.1f".format(ratio)
}
