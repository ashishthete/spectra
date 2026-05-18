package com.spectra.app.ui.controls

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,
    onShutterTap: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstEnd: () -> Unit,
    onLensCycle: () -> Unit,
    onGalleryClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onRecordToggle: () -> Unit = {},
    onModeToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PHOTO",
                color = if (!isVideoMode) HudColors.accent else HudColors.textMuted,
                fontSize = 14.sp,
                fontWeight = if (!isVideoMode) FontWeight.Bold else FontWeight.Normal,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onModeToggle(false) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                text = "VIDEO",
                color = if (isVideoMode) Color.Red else HudColors.textMuted,
                fontSize = 14.sp,
                fontWeight = if (isVideoMode) FontWeight.Bold else FontWeight.Normal,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onModeToggle(true) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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

            if (isVideoMode) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .border(3.dp, if (isRecording) Color.Red else HudColors.accent, CircleShape)
                        .clip(CircleShape)
                        .clickable { onRecordToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color.Red, RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }
                }
            } else {
                ShutterButton(
                    onTap = onShutterTap,
                    onLongPressStart = onBurstStart,
                    onLongPressEnd = onBurstEnd
                )
            }

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
                        .size(if (isFrontCamera) 40.dp else 36.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, HudColors.borderLight, CircleShape)
                        .clickable { onFlipCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Flip camera",
                        tint = HudColors.accent,
                        modifier = Modifier.size(if (isFrontCamera) 22.dp else 18.dp)
                    )
                }
            }
        }
    }
}
