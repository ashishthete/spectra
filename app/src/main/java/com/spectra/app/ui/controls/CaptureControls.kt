package com.spectra.app.ui.controls

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(1.5.dp, HudColors.borderLight, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .clickable { onGalleryClick() },
            contentAlignment = Alignment.Center
        ) {
            if (lastCapturedUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(lastCapturedUri))
                        .size(96)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Last photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .border(1.dp, HudColors.accentGhost, RoundedCornerShape(3.dp))
                )
            }
        }

        ShutterButton(
            onTap = onShutterTap,
            onLongPressStart = onBurstStart,
            onLongPressEnd = onBurstEnd
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isFrontCamera) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, HudColors.borderLight, CircleShape)
                        .clickable { onLensCycle() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = activeLens.zoomLabel.uppercase(),
                        color = HudColors.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(if (isFrontCamera) 40.dp else 32.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, HudColors.borderLight, CircleShape)
                    .clickable { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⟲",
                    color = HudColors.accent,
                    fontSize = if (isFrontCamera) 20.sp else 16.sp
                )
            }
        }
    }
}
