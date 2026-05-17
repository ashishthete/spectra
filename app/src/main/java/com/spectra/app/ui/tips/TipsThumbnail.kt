package com.spectra.app.ui.tips

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun TipsThumbnail(
    sceneLabel: String,
    isVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .border(1.dp, HudColors.borderGreen, RectangleShape)
                .background(HudColors.background.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "?",
                color = HudColors.neonGreen,
                fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "TIPS: $sceneLabel",
                style = HudTypography.readoutSmall
            )
        }
    }
}
