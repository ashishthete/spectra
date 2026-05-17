package com.spectra.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object HudColors {
    val neonGreen = Color(0xFF00FF88)
    val neonGreenDim = Color(0x9900FF88)
    val neonGreenFaint = Color(0x4400FF88)
    val neonGreenGhost = Color(0x2200FF88)
    val neonGreenScanLine = Color(0x0600FF88)
    val background = Color.Black
    val surfaceGlass = Color(0x0AFFFFFF)
    val borderGreen = Color(0x4400FF88)
    val textPrimary = Color(0xFF00FF88)
    val textSecondary = Color(0x9900FF88)
    val textMuted = Color(0x5500FF88)
    val red = Color(0xFFFF4444)
    val warningAmber = Color(0xFFFFAA00)
}

object HudTypography {
    private val mono = FontFamily.Monospace

    val readoutLarge = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        color = HudColors.neonGreen
    )

    val readoutSmall = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = HudColors.textSecondary
    )

    val label = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 2.sp,
        color = HudColors.textMuted
    )

    val modeActive = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreen
    )

    val modeInactive = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreenFaint
    )

    val coaching = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = HudColors.neonGreen
    )
}

val LocalHudColors = staticCompositionLocalOf { HudColors }
val LocalHudTypography = staticCompositionLocalOf { HudTypography }

@Composable
fun SpectraTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalHudColors provides HudColors,
        LocalHudTypography provides HudTypography,
        content = content
    )
}
