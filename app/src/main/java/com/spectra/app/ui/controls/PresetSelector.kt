package com.spectra.app.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.CameraPreset

@Composable
fun PresetSelector(
    currentPreset: CameraPreset,
    onPresetSelected: (CameraPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = CameraPreset.entries
    val scrollState = rememberScrollState()

    LaunchedEffect(currentPreset) {
        val idx = presets.indexOf(currentPreset)
        if (idx >= 0) {
            scrollState.animateScrollTo(maxOf(0, idx * 200 - 400))
        }
    }

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        presets.forEach { preset ->
            val isActive = preset == currentPreset
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onPresetSelected(preset) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = preset.icon,
                    fontSize = if (isActive) 20.sp else 16.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = preset.label,
                    color = if (isActive) HudColors.accent else HudColors.textMuted,
                    fontSize = if (isActive) 10.sp else 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (isActive) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(16.dp)
                            .height(1.dp)
                    ) {
                        drawRect(HudColors.accent)
                    }
                }
            }
        }
    }
}
