package com.spectra.app.ui.controls

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import com.spectra.app.ui.theme.HudColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShutterButton(
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val shutterSound = remember {
        MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
    }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var isPressed by remember { mutableStateOf(false) }
    var showFlash by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "shutter_scale"
    )

    val flashAlpha by animateFloatAsState(
        targetValue = if (showFlash) 1f else 0f,
        animationSpec = tween(durationMillis = 80),
        label = "flash_alpha"
    )

    LaunchedEffect(showFlash) {
        if (showFlash) {
            delay(120)
            showFlash = false
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .border(2.5.dp, HudColors.accent.copy(alpha = 0.9f), CircleShape)
            .semantics { contentDescription = "Shutter" }
            .graphicsLayer(alpha = if (enabled) 1f else 0.4f)
            .combinedClickable(
                onClick = {
                    if (!enabled) return@combinedClickable
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
                    if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    }
                    showFlash = true
                    onTap()
                },
                onLongClick = {
                    if (!enabled) return@combinedClickable
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPressStart()
                }
            )
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
                .border(1.dp, HudColors.accentDim, CircleShape)
        )

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .border(
                    width = 3.dp,
                    color = Color.White.copy(alpha = flashAlpha * 0.9f),
                    shape = CircleShape
                )
        )
    }
}
