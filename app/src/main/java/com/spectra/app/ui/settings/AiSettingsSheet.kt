package com.spectra.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.ai.cloud.AiProvider
import com.spectra.ai.cloud.AiProviderConfig
import com.spectra.app.ui.theme.HudColors

@Composable
fun AiSettingsSheet(
    config: AiProviderConfig,
    onConfigChanged: (AiProviderConfig) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedProviderIndex by remember(config.provider) {
        mutableIntStateOf(AiProvider.entries.indexOf(config.provider))
    }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var selectedModelIndex by remember(config.model, config.provider) {
        val models = AiProvider.modelsFor(config.provider)
        mutableIntStateOf(models.indexOf(config.model).coerceAtLeast(0))
    }
    var customBaseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    var showKey by remember { mutableStateOf(false) }

    val providers = AiProvider.entries
    val currentProvider = providers[selectedProviderIndex]
    val models = AiProvider.modelsFor(currentProvider)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(HudColors.background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI COACHING",
                color = HudColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = HudColors.textSecondary,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onDismiss() }
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Send viewfinder snapshots to an AI model for real-time composition coaching.",
            color = HudColors.textMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable", color = HudColors.textPrimary, fontSize = 13.sp)
            Switch(
                checked = enabled,
                onCheckedChange = { newEnabled ->
                    enabled = newEnabled
                    emitConfig(
                        onConfigChanged, currentProvider, apiKey,
                        models, selectedModelIndex, customBaseUrl, newEnabled
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HudColors.accent,
                    checkedTrackColor = HudColors.accentFaint,
                    uncheckedThumbColor = HudColors.textMuted,
                    uncheckedTrackColor = HudColors.surfaceGlass
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionLabel("PROVIDER")
        Spacer(Modifier.height(6.dp))
        ChipRow(
            items = providers.map { it.displayName },
            selectedIndex = selectedProviderIndex,
            onSelected = { index ->
                selectedProviderIndex = index
                selectedModelIndex = 0
                val newProvider = providers[index]
                val newModels = AiProvider.modelsFor(newProvider)
                emitConfig(
                    onConfigChanged, newProvider, apiKey,
                    newModels, 0, customBaseUrl, enabled
                )
            }
        )

        AnimatedVisibility(visible = currentProvider == AiProvider.OPENAI_COMPATIBLE) {
            Column {
                Spacer(Modifier.height(12.dp))
                SectionLabel("BASE URL")
                Spacer(Modifier.height(6.dp))
                SettingsTextField(
                    value = customBaseUrl,
                    placeholder = "https://api.groq.com/openai",
                    onValueChange = { url ->
                        customBaseUrl = url
                        emitConfig(
                            onConfigChanged, currentProvider, apiKey,
                            models, selectedModelIndex, url, enabled
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionLabel("API KEY")
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsTextField(
                    value = apiKey,
                    placeholder = "Paste your API key",
                    onValueChange = { key ->
                        apiKey = key
                        emitConfig(
                            onConfigChanged, currentProvider, key,
                            models, selectedModelIndex, customBaseUrl, enabled
                        )
                    },
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (showKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = "Toggle key visibility",
                tint = HudColors.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showKey = !showKey }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your key is stored locally on this device only.",
            color = HudColors.textMuted,
            fontSize = 10.sp
        )

        Spacer(Modifier.height(16.dp))

        SectionLabel("MODEL")
        Spacer(Modifier.height(6.dp))
        ChipRow(
            items = models,
            selectedIndex = selectedModelIndex,
            onSelected = { index ->
                selectedModelIndex = index
                emitConfig(
                    onConfigChanged, currentProvider, apiKey,
                    models, index, customBaseUrl, enabled
                )
            }
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "Low-res thumbnails (512×384) are sent for analysis. Full photos never leave your device.",
            color = HudColors.textMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        Spacer(Modifier.height(16.dp))
    }
}

private fun emitConfig(
    onConfigChanged: (AiProviderConfig) -> Unit,
    provider: AiProvider,
    apiKey: String,
    models: List<String>,
    modelIndex: Int,
    baseUrl: String,
    enabled: Boolean
) {
    onConfigChanged(AiProviderConfig(
        provider = provider,
        apiKey = apiKey,
        model = models.getOrElse(modelIndex) { "" },
        baseUrl = baseUrl,
        enabled = enabled
    ))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = HudColors.textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun ChipRow(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { colIdx, label ->
                    val index = rowIdx * 2 + colIdx
                    val isSelected = index == selectedIndex
                    Text(
                        text = label,
                        color = if (isSelected) HudColors.accent else HudColors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) HudColors.accentFaint
                                else HudColors.surfaceGlass
                            )
                            .clickable { onSelected(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = HudColors.textPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(HudColors.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HudColors.surfaceGlass)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = HudColors.textMuted, fontSize = 12.sp)
                }
                innerTextField()
            }
        }
    )
}
