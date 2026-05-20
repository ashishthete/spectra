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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.AspectRatio
import com.spectra.core.model.FlashMode

@Composable
fun TopControlBar(
    flashMode: FlashMode,
    timerSeconds: Int,
    aspectRatio: AspectRatio,
    megapixels: Int = 12,
    onFlashToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onAspectToggle: () -> Unit,
    onMegapixelToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopButton(
            icon = flashMode.icon,
            label = "Flash",
            isActive = flashMode == FlashMode.ON,
            onClick = onFlashToggle
        )

        TopButton(
            icon = if (timerSeconds > 0) "${timerSeconds}s" else "T",
            label = "Timer",
            isActive = timerSeconds > 0,
            onClick = onTimerToggle
        )

        TopButton(
            icon = aspectRatio.label,
            label = "Ratio",
            isActive = aspectRatio != AspectRatio.RATIO_4_3,
            onClick = onAspectToggle
        )

        TopButton(
            icon = "${megapixels}",
            label = "MP",
            isActive = megapixels > 12,
            onClick = onMegapixelToggle
        )
    }
}

@Composable
private fun TopButton(
    icon: String,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (isActive) HudColors.accent.copy(alpha = 0.15f)
                else HudColors.surfaceGlass
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            color = if (isActive) HudColors.accent else HudColors.textSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )
    }
}
