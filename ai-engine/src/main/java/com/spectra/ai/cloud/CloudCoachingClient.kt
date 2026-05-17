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

    private var apiKey: String? = null

    fun setApiKey(key: String) {
        apiKey = key
    }

    override suspend fun analyzeFrame(
        bitmap: Bitmap,
        sceneLabel: String,
        lightingLabel: String
    ): CoachingHint? {
        val key = apiKey ?: return null

        val base64 = bitmapToBase64(bitmap)
        val request = buildRequest(base64, sceneLabel, lightingLabel)

        return try {
            val response: VisionResponse = httpClient.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val text = response.content.firstOrNull { it.type == "text" }?.text ?: return null
            parseResponseText(text).firstOrNull()
        } catch (_: Exception) {
            null
        }
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

        fun buildRequest(base64Image: String, sceneLabel: String, lightingLabel: String): VisionRequest {
            return VisionRequest(
                messages = listOf(
                    VisionMessage(
                        content = listOf(
                            VisionContent(
                                type = "image",
                                source = ImageSource(data = base64Image)
                            ),
                            VisionContent(
                                type = "text",
                                text = buildPrompt(sceneLabel, lightingLabel)
                            )
                        )
                    )
                )
            )
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
