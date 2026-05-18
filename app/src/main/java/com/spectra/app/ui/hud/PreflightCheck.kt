package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

data class PreflightStatus(
    val isLevel: Boolean,
    val isFocused: Boolean,
    val isExposureGood: Boolean,
    val isCompositionOk: Boolean
)

@Composable
fun PreflightCheck(
    status: PreflightStatus,
    modifier: Modifier = Modifier
) {
    val allGood = status.isLevel && status.isFocused && status.isExposureGood && status.isCompositionOk
    if (allGood) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(HudColors.surfaceGlass, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        PreflightItem(label = "LEVEL", ok = status.isLevel)
        PreflightItem(label = "FOCUS", ok = status.isFocused)
        PreflightItem(label = "EXPO", ok = status.isExposureGood)
        PreflightItem(label = "COMP", ok = status.isCompositionOk)
    }
}

@Composable
private fun PreflightItem(label: String, ok: Boolean) {
    Text(
        text = label,
        color = if (ok) HudColors.success else Color(0xFFFF9800),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        letterSpacing = 0.5.sp
    )
}
