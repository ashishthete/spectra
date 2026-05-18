package com.spectra.app.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        val warmth = when (beautyLevel) {
            1 -> 0.02f
            2 -> 0.04f
            3 -> 0.06f
            else -> 0f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFFF5E6).copy(alpha = warmth))
        )
    }
}
