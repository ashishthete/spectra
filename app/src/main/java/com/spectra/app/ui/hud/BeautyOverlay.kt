package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

@Composable
fun BeautyOverlay(
    beautyLevel: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = beautyLevel > 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alpha = when (beautyLevel) {
                1 -> 0.05f
                2 -> 0.10f
                3 -> 0.15f
                else -> 0f
            }
            // Warm skin-tone brightening overlay
            drawRect(
                color = Color(0xFFFFF5E6),
                alpha = alpha,
                blendMode = BlendMode.Screen
            )
            // Subtle softening glow
            drawRect(
                color = Color.White,
                alpha = alpha * 0.3f,
                blendMode = BlendMode.Lighten
            )
        }
    }
}
