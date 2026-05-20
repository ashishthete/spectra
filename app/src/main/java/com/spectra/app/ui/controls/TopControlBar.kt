package com.spectra.app.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Crop169
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.AspectRatio
import com.spectra.core.model.FlashMode

@Composable
fun TopControlBar(
    flashMode: FlashMode,
    timerSeconds: Int,
    aspectRatio: AspectRatio,
    megapixels: Int = 12,
    palmGestureEnabled: Boolean = false,
    onFlashToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onAspectToggle: () -> Unit,
    onMegapixelToggle: () -> Unit = {},
    onPalmGestureToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val flashIcon = when (flashMode) {
            FlashMode.AUTO -> Icons.Filled.FlashAuto
            FlashMode.ON -> Icons.Filled.FlashOn
            FlashMode.OFF -> Icons.Filled.FlashOff
        }
        TopIconButton(
            icon = flashIcon,
            contentDescription = "Flash: ${flashMode.name}",
            isActive = flashMode == FlashMode.ON,
            onClick = onFlashToggle
        )

        TopIconButton(
            icon = if (timerSeconds > 0) Icons.Filled.Timer else Icons.Filled.TimerOff,
            contentDescription = if (timerSeconds > 0) "Timer: ${timerSeconds}s" else "Timer off",
            isActive = timerSeconds > 0,
            onClick = onTimerToggle
        )

        val ratioIcon = when (aspectRatio) {
            AspectRatio.RATIO_16_9 -> Icons.Outlined.Crop169
            AspectRatio.RATIO_1_1 -> Icons.Outlined.CropSquare
            else -> Icons.Outlined.CropPortrait
        }
        TopIconButton(
            icon = ratioIcon,
            contentDescription = "Aspect ratio: ${aspectRatio.label}",
            isActive = aspectRatio != AspectRatio.RATIO_4_3,
            onClick = onAspectToggle
        )

        TopIconButton(
            icon = Icons.Filled.PhotoSizeSelectLarge,
            contentDescription = "${megapixels}MP",
            isActive = megapixels > 12,
            onClick = onMegapixelToggle
        )

        TopIconButton(
            icon = Icons.Filled.PanTool,
            contentDescription = "Palm gesture",
            isActive = palmGestureEnabled,
            onClick = onPalmGestureToggle
        )
    }
}

@Composable
private fun TopIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (isActive) HudColors.accent.copy(alpha = 0.35f)
                else HudColors.surfaceGlass
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) HudColors.accent else HudColors.textSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}
