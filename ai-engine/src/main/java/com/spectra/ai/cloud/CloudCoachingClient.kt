package com.spectra.ai.cloud

import android.graphics.Bitmap
import android.util.Base64
import com.spectra.ai.model.CoachingHint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

interface CloudCoachingClientInterface {
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        sceneLabel: String,
        lightingLabel: String
    ): CoachingHint?
}

@Singleton
class CloudCoachingClient @Inject constructor() : CloudCoachingClientInterface {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var config: AiProviderConfig = AiProviderConfig()

    fun configure(newConfig: AiProviderConfig) {
        config = newConfig
    }

    fun isEnabled(): Boolean = config.enabled && config.isConfigured

    override suspend fun analyzeFrame(
        bitmap: Bitmap,
        sceneLabel: String,
        lightingLabel: String
    ): CoachingHint? {
        if (!isEnabled()) return null

        val base64 = bitmapToBase64(bitmap)
        val prompt = buildPrompt(sceneLabel, lightingLabel)

        return try {
            val text = when (config.provider) {
                AiProvider.ANTHROPIC -> queryAnthropic(base64, prompt)
                AiProvider.OPENAI -> queryOpenAi(base64, prompt, config.effectiveBaseUrl)
                AiProvider.GEMINI -> queryGemini(base64, prompt)
                AiProvider.OPENAI_COMPATIBLE -> queryOpenAi(base64, prompt, config.effectiveBaseUrl)
            }
            if (text != null) parseResponseText(text).firstOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun queryAnthropic(base64: String, prompt: String): String? {
        val response: AnthropicResponse = httpClient.post("${config.effectiveBaseUrl}/v1/messages") {
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(AnthropicRequest(
                model = config.effectiveModel,
                messages = listOf(AnthropicMessage(content = listOf(
                    AnthropicContent(type = "image", source = AnthropicImageSource(data = base64)),
                    AnthropicContent(type = "text", text = prompt)
                )))
            ))
        }.body()
        return response.content.firstOrNull { it.type == "text" }?.text
    }

    private suspend fun queryOpenAi(base64: String, prompt: String, baseUrl: String): String? {
        val response: OpenAiResponse = httpClient.post("$baseUrl/v1/chat/completions") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(OpenAiRequest(
                model = config.effectiveModel,
                messages = listOf(OpenAiMessage(content = listOf(
                    OpenAiContent(
                        type = "image_url",
                        imageUrl = OpenAiImageUrl(url = "data:image/jpeg;base64,$base64")
                    ),
                    OpenAiContent(type = "text", text = prompt)
                )))
            ))
        }.body()
        return response.choices.firstOrNull()?.message?.content
    }

    private suspend fun queryGemini(base64: String, prompt: String): String? {
        val url = "${config.effectiveBaseUrl}/v1beta/models/${config.effectiveModel}:generateContent?key=${config.apiKey}"
        val response: GeminiResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(
                    GeminiPart(inlineData = GeminiInlineData(data = base64)),
                    GeminiPart(text = prompt)
                )))
            ))
        }.body()
        return response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 384, true)
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun release() {
        httpClient.close()
    }

    companion object {
        fun buildPrompt(sceneLabel: String, lightingLabel: String): String {
            return "Analyze this camera viewfinder frame for composition improvement. " +
                "Scene: $sceneLabel. Lighting: $lightingLabel. " +
                "Respond with 1-2 short directive lines in this format: " +
                "ACTION DIRECTION AMOUNT · COMPOSITION TECHNIQUE. " +
                "Examples: TILT UP 10° · GOLDEN RATIO ALIGN, " +
                "STEP LEFT 0.5M · LEADING LINES. " +
                "Be specific and actionable. Each directive should be under 50 characters."
        }

        fun parseResponseText(text: String): List<CoachingHint> {
            if (text.isBlank()) return emptyList()
            return text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("-") }
                .take(2)
                .map { CloudDirectiveParser.parse(it) }
        }
    }
}
