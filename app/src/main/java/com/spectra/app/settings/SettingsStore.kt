package com.spectra.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spectra.ai.cloud.AiProvider
import com.spectra.ai.cloud.AiProviderConfig
import com.spectra.core.model.CameraMode
import com.spectra.core.model.CameraSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spectra_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val MODE = stringPreferencesKey("mode")
    private val BEAUTY = intPreferencesKey("beauty_level")
    private val ISO = intPreferencesKey("pro_iso")
    private val SHUTTER = intPreferencesKey("pro_shutter")
    private val WB = intPreferencesKey("pro_wb")
    private val EV = floatPreferencesKey("pro_ev")
    private val FOCUS = floatPreferencesKey("pro_focus")
    private val AI_PROVIDER = stringPreferencesKey("ai_provider")
    private val AI_API_KEY = stringPreferencesKey("ai_api_key")
    private val AI_MODEL = stringPreferencesKey("ai_model")
    private val AI_BASE_URL = stringPreferencesKey("ai_base_url")
    private val AI_ENABLED = stringPreferencesKey("ai_enabled")

    suspend fun saveMode(mode: CameraMode) {
        context.dataStore.edit { it[MODE] = mode.name }
    }

    suspend fun saveBeautyLevel(level: Int) {
        context.dataStore.edit { it[BEAUTY] = level }
    }

    suspend fun saveProSettings(settings: CameraSettings) {
        context.dataStore.edit {
            it[ISO] = settings.iso
            it[SHUTTER] = settings.shutterSpeedDenominator
            it[WB] = settings.whiteBalanceKelvin
            it[EV] = settings.exposureCompensation
            it[FOCUS] = settings.focusDistance
        }
    }

    suspend fun loadMode(): CameraMode {
        val prefs = context.dataStore.data.first()
        val name = prefs[MODE] ?: return CameraMode.PHOTO
        return try { CameraMode.valueOf(name) } catch (_: Exception) { CameraMode.PHOTO }
    }

    suspend fun loadBeautyLevel(): Int {
        return context.dataStore.data.first()[BEAUTY] ?: 0
    }

    suspend fun loadProSettings(): CameraSettings {
        val prefs = context.dataStore.data.first()
        return CameraSettings.clamped(
            iso = prefs[ISO] ?: 100,
            shutterSpeedDenominator = prefs[SHUTTER] ?: 125,
            whiteBalanceKelvin = prefs[WB] ?: 5500,
            exposureCompensation = prefs[EV] ?: 0f,
            focusDistance = prefs[FOCUS] ?: 0f
        )
    }

    suspend fun saveAiConfig(config: AiProviderConfig) {
        context.dataStore.edit {
            it[AI_PROVIDER] = config.provider.name
            it[AI_API_KEY] = config.apiKey
            it[AI_MODEL] = config.model
            it[AI_BASE_URL] = config.baseUrl
            it[AI_ENABLED] = if (config.enabled) "true" else "false"
        }
    }

    suspend fun loadAiConfig(): AiProviderConfig {
        val prefs = context.dataStore.data.first()
        val providerName = prefs[AI_PROVIDER] ?: return AiProviderConfig()
        val provider = try { AiProvider.valueOf(providerName) } catch (_: Exception) { AiProvider.ANTHROPIC }
        return AiProviderConfig(
            provider = provider,
            apiKey = prefs[AI_API_KEY] ?: "",
            model = prefs[AI_MODEL] ?: "",
            baseUrl = prefs[AI_BASE_URL] ?: "",
            enabled = prefs[AI_ENABLED] == "true"
        )
    }
}
