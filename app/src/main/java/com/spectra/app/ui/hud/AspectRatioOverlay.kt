package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.spectra.core.model.AspectRatio

@Composable
fun AspectRatioOverlay(
    aspectRatio: AspectRatio,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = aspectRatio != AspectRatio.FULL && aspectRatio != AspectRatio.RATIO_4_3,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewW = size.width
            val viewH = size.height

            val targetRatio = when (aspectRatio) {
                AspectRatio.RATIO_16_9 -> 9f / 16f
                AspectRatio.RATIO_1_1 -> 1f
                else -> return@Canvas
            }

            val viewRatio = viewW / viewH

            if (targetRatio < viewRatio) {
                val cropW = viewH * targetRatio
                val barW = (viewW - cropW) / 2f
                drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, Size(barW, viewH))
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(viewW - barW, 0f), Size(barW, viewH))
            } else {
                val cropH = viewW / targetRatio
                val barH = (viewH - cropH) / 2f
                drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, Size(viewW, barH))
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, viewH - barH), Size(viewW, barH))
            }
        }
    }
}
