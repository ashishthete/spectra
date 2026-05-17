package com.spectra.app.ui.pro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.CameraSettings

@Composable
fun ProModePanel(
    isVisible: Boolean,
    settings: CameraSettings,
    aiSettings: CameraSettings,
    isManualOverride: Boolean,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onWbChange: (Int) -> Unit,
    onEvChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onSnapToAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.background.copy(alpha = 0.85f))
                .padding(vertical = 8.dp)
        ) {
            if (isManualOverride) {
                Text(
                    text = "MANUAL OVERRIDE",
                    style = HudTypography.readoutLarge,
                    color = HudColors.warningAmber,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                        .clickable { onSnapToAi() }
                )
            }

            GhostMarkerSlider(
                label = "ISO",
                value = settings.iso.toFloat(),
                ghostValue = aiSettings.iso.toFloat(),
                valueRange = 50f..3200f,
                displayText = settings.formattedIso,
                onValueChange = { onIsoChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "SHUTTER",
                value = settings.shutterSpeedDenominator.toFloat(),
                ghostValue = aiSettings.shutterSpeedDenominator.toFloat(),
                valueRange = 1f..8000f,
                displayText = settings.formattedShutterSpeed,
                onValueChange = { onShutterChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "WB",
                value = settings.whiteBalanceKelvin.toFloat(),
                ghostValue = aiSettings.whiteBalanceKelvin.toFloat(),
                valueRange = 2300f..10000f,
                displayText = settings.formattedWb,
                onValueChange = { onWbChange(it.toInt()) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "EV",
                value = settings.exposureCompensation,
                ghostValue = aiSettings.exposureCompensation,
                valueRange = -3f..3f,
                displayText = settings.formattedEv,
                onValueChange = { onEvChange(it) },
                onGhostTap = onSnapToAi
            )

            GhostMarkerSlider(
                label = "FOCUS",
                value = settings.focusDistance,
                ghostValue = aiSettings.focusDistance,
                valueRange = 0f..15f,
                displayText = "${"%.1f".format(settings.focusDistance)}m",
                onValueChange = { onFocusChange(it) },
                onGhostTap = onSnapToAi
            )
        }
    }
}
