package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun MotionIndicator(
    motionLevel: Int,
    distanceLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "MOTION", style = HudTypography.label)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(
                            if (index < motionLevel) HudColors.neonGreen
                            else HudColors.neonGreenGhost
                        )
                )
            }
        }
        Text(text = "DIST", style = HudTypography.label)
        Text(text = distanceLabel, style = HudTypography.readoutSmall)
    }
}
