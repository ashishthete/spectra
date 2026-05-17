package com.spectra.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography
import com.spectra.core.model.LensId

@Composable
fun LensMatchBars(
    scores: Map<LensId, Float>,
    activeLens: LensId,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "LENS MATCH", style = HudTypography.label)
        LensId.entries.forEach { lens ->
            val score = scores[lens] ?: 0f
            val isActive = lens == activeLens
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isActive) "${lens.zoomLabel} ◄" else lens.zoomLabel,
                    style = if (isActive) HudTypography.readoutSmall.copy(fontWeight = FontWeight.Bold, color = HudColors.neonGreen)
                            else HudTypography.readoutSmall
                )
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HudColors.neonGreenGhost)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(score)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isActive) HudColors.neonGreen
                                else HudColors.neonGreenDim
                            )
                    )
                }
            }
        }
    }
}
