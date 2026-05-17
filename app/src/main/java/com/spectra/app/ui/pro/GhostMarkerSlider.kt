package com.spectra.app.ui.pro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun GhostMarkerSlider(
    label: String,
    value: Float,
    ghostValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String,
    onValueChange: (Float) -> Unit,
    onGhostTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "$label: $displayText",
            style = HudTypography.readoutSmall
        )

        Box {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = HudColors.accent,
                    activeTrackColor = HudColors.accent.copy(alpha = 0.7f),
                    inactiveTrackColor = HudColors.accent.copy(alpha = 0.2f)
                )
            )

            val fraction = if (valueRange.endInclusive != valueRange.start) {
                ((ghostValue - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                    .coerceIn(0f, 1f)
            } else 0f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.Center)
            ) {
                val trackWidth = size.width - 40.dp.toPx()
                val xPos = 20.dp.toPx() + fraction * trackWidth

                drawCircle(
                    color = HudColors.aiCyan.copy(alpha = 0.5f),
                    radius = 8.dp.toPx(),
                    center = Offset(xPos, size.height / 2)
                )
                drawCircle(
                    color = HudColors.aiCyan.copy(alpha = 0.3f),
                    radius = 12.dp.toPx(),
                    center = Offset(xPos, size.height / 2)
                )
            }
        }
    }
}
