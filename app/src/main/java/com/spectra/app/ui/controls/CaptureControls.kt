package com.spectra.app.ui.controls

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.LensId

@Composable
fun CaptureControls(
    activeLens: LensId,
    lastCapturedUri: String?,
    isFrontCamera: Boolean,
    onShutterTap: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstEnd: () -> Unit,
    onLensCycle: () -> Unit,
    onGalleryClick: () -> Unit,
    onFlipCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(1.5.dp, HudColors.borderGreen, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .clickable { onGalleryClick() },
            contentAlignment = Alignment.Center
        ) {
            if (lastCapturedUri != null) {
                // Show a small indicator that photos exist
                Text(
                    text = "▶",
                    color = HudColors.neonGreen,
                    fontSize = 16.sp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .border(1.dp, HudColors.neonGreenGhost, RoundedCornerShape(3.dp))
                )
            }
        }

        // Shutter button
        ShutterButton(
            onTap = onShutterTap,
            onLongPressStart = onBurstStart,
            onLongPressEnd = onBurstEnd
        )

        // Right side: lens cycle + flip camera stacked
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Lens cycle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, HudColors.borderGreen, CircleShape)
                    .clickable { onLensCycle() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isFrontCamera) "1x" else activeLens.zoomLabel.uppercase(),
                    color = HudColors.neonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            // Flip camera button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, HudColors.borderGreen, CircleShape)
                    .clickable { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⟲",
                    color = HudColors.neonGreen,
                    fontSize = 16.sp
                )
            }
        }
    }
}
