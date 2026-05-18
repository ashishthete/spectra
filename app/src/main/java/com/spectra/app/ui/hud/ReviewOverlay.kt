package com.spectra.app.ui.hud

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.spectra.app.ui.theme.HudColors

@Composable
fun ReviewOverlay(
    imageUri: String?,
    isVisible: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible && imageUri != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(Uri.parse(imageUri ?: ""))
                    .crossfade(true)
                    .build(),
                contentDescription = "Captured photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            Text(
                text = "BEST OF 5",
                color = HudColors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 48.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReviewButton(icon = "✕", label = "Discard", onClick = onDelete)
                ReviewButton(icon = "✓", label = "Keep", isPrimary = true, onClick = onKeep)
            }
        }
    }
}

@Composable
fun SmartReviewOverlay(
    bestOriginalUri: String?,
    aiEnhancedUri: String?,
    isEnhancing: Boolean,
    isVisible: Boolean,
    onSaveOriginal: () -> Unit,
    onSaveEnhanced: () -> Unit,
    onSaveBoth: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible && bestOriginalUri != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(bestOriginalUri ?: ""))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Best original",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = "RAW · BEST OF 5",
                        color = HudColors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HudColors.accent.copy(alpha = 0.5f))
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (aiEnhancedUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(Uri.parse(aiEnhancedUri))
                                .crossfade(true)
                                .build(),
                            contentDescription = "AI enhanced",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF111111)),
                            contentAlignment = Alignment.Center
                        ) {
                            EnhancingShimmer()
                        }
                    }
                    Text(
                        text = if (isEnhancing) "PROCESSING..." else "PROCESSED",
                        color = if (isEnhancing) HudColors.textMuted else HudColors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReviewButton(icon = "✕", label = "Discard", onClick = onDiscard)
                ReviewButton(icon = "◯", label = "Raw", onClick = onSaveOriginal)
                if (aiEnhancedUri != null) {
                    ReviewButton(icon = "✦", label = "Processed", onClick = onSaveEnhanced)
                    ReviewButton(icon = "✓✓", label = "Both", isPrimary = true, onClick = onSaveBoth)
                }
            }
        }
    }
}

@Composable
private fun EnhancingShimmer() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "⟳",
            fontSize = 28.sp,
            color = HudColors.accent.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Processing...",
            color = HudColors.textMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ReviewButton(
    icon: String,
    label: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (isPrimary) 56.dp else 48.dp)
                .clip(CircleShape)
                .background(if (isPrimary) HudColors.accent else HudColors.surfaceGlass)
        ) {
            Text(
                text = icon,
                fontSize = if (isPrimary) 24.sp else 20.sp,
                color = if (isPrimary) Color.Black else Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = HudColors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
