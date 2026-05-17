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
import com.spectra.ai.model.PhotoTip
import com.spectra.app.ui.theme.HudColors
import com.spectra.app.ui.theme.HudTypography

@Composable
fun ReferenceCard(
    tip: PhotoTip,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HudColors.background.copy(alpha = 0.85f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .border(1.dp, HudColors.neonGreen, RectangleShape)
                    .background(HudColors.background)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ ${tip.title} ]",
                        style = HudTypography.readoutLarge
                    )
                    Text(
                        text = "✕",
                        color = HudColors.neonGreenDim,
                        fontSize = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HudColors.borderGreen)
                )

                Spacer(modifier = Modifier.height(16.dp))

                tip.tips.forEachIndexed { index, tipText ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = "0${index + 1}",
                            style = HudTypography.label,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = tipText,
                            style = HudTypography.readoutSmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HudColors.borderGreen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TAP ANYWHERE TO DISMISS",
                    style = HudTypography.label
                )
            }
        }
    }
}
