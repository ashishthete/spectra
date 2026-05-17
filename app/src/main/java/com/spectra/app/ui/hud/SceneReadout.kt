package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

@Composable
fun SceneReadout(
    sceneLabel: String,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    if (sceneLabel.isBlank() || confidence < 0.45f) return

    Text(
        text = sceneLabel.lowercase().replaceFirstChar { it.uppercase() },
        color = HudColors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier
            .background(HudColors.surfaceGlass, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
