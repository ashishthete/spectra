package com.spectra.app.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

data class FeatureTooltip(
    val id: String,
    val text: String,
    val targetDescription: String
)

val featureTooltips = listOf(
    FeatureTooltip("preset_swipe", "Swipe left/right to change presets", "Preset bar"),
    FeatureTooltip("coaching_tap", "Tap coaching tips to apply the suggestion", "Coaching overlay"),
    FeatureTooltip("histogram_toggle", "Tap to show/hide the live histogram", "Mini histogram"),
    FeatureTooltip("long_press_burst", "Long-press shutter for burst mode", "Capture button"),
    FeatureTooltip("pro_raw", "Toggle RAW to save DNG files alongside JPEG", "PRO mode RAW toggle")
)

@Composable
fun FeatureTooltipOverlay(
    tooltip: FeatureTooltip?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = tooltip != null,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut(),
        modifier = modifier
    ) {
        tooltip?.let { tip ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .background(HudColors.surfaceGlass, RoundedCornerShape(12.dp))
                    .clickable { onDismiss() }
                    .padding(16.dp)
            ) {
                Text(
                    text = tip.text,
                    color = HudColors.accent,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
