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
    val accent = Color(0xFFF0A830)
    val accentDim = Color(0x99F0A830)
    val accentFaint = Color(0x44F0A830)
    val accentGhost = Color(0x22F0A830)

    val aiCyan = Color(0xFF70C0C0)
    val aiCyanDim = Color(0x6670C0C0)

    val background = Color(0xFF0A0A0A)
    val surfaceGlass = Color(0xD00A0A0A)
    val surfaceOverlay = Color(0x800A0A0A)

    val borderLight = Color(0x33FFFFFF)

    val textPrimary = Color(0xFFE0E0E0)
    val textSecondary = Color(0x99E0E0E0)
    val textMuted = Color(0x55E0E0E0)

    val red = Color(0xFFE87070)
    val warningAmber = Color(0xFFE8A040)
    val success = Color(0xFF66CC88)

    val white = Color(0xFFFFFFFF)

}

object HudTypography {
    private val sans = FontFamily.SansSerif
    private val mono = FontFamily.Monospace

    val readoutLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
        color = HudColors.textPrimary
    )

    val readoutSmall = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        color = HudColors.textSecondary
    )

    val label = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        color = HudColors.textMuted
    )

    val modeActive = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = HudColors.accent
    )

    val modeInactive = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = HudColors.textMuted
    )

    val coaching = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
        color = HudColors.textPrimary
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
