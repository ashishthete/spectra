package com.spectra.app.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraMode

@Composable
fun ModeSelector(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = CameraMode.entries
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(currentMode) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        val threshold = 80f
                        if (dragAccumulator > threshold) {
                            dragAccumulator = 0f
                            val idx = modes.indexOf(currentMode)
                            if (idx > 0) onModeSelected(modes[idx - 1])
                        } else if (dragAccumulator < -threshold) {
                            dragAccumulator = 0f
                            val idx = modes.indexOf(currentMode)
                            if (idx < modes.lastIndex) onModeSelected(modes[idx + 1])
                        }
                    }
                )
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { mode ->
            val isActive = mode == currentMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = mode.label,
                    style = if (isActive) HudTypography.modeActive else HudTypography.modeInactive
                )
                if (isActive) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .width(20.dp)
                            .height(1.dp)
                    ) {
                        drawRect(HudColors.accent)
                    }
                }
            }
        }
    }
}
