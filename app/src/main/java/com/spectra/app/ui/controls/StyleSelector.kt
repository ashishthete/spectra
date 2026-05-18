package com.spectra.app.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors
import com.spectra.core.model.PhotoStyle

@Composable
fun StyleSelector(
    currentStyle: PhotoStyle,
    onStyleSelected: (PhotoStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhotoStyle.entries.forEach { style ->
            val isActive = style == currentStyle
            Text(
                text = style.label,
                color = if (isActive) HudColors.accent else HudColors.textMuted,
                fontSize = if (isActive) 13.sp else 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onStyleSelected(style) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
