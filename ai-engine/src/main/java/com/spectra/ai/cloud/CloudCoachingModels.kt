package com.spectra.ai.cloud

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CoachingHint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Anthropic ---

@Serializable
data class AnthropicRequest(
    val model: String = "claude-sonnet-4-20250514",
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val messages: List<AnthropicMessage>
)

@Serializable
data class AnthropicMessage(
    val role: String = "user",
    val content: List<AnthropicContent>
)

@Serializable
data class AnthropicContent(
    val type: String,
    val source: AnthropicImageSource? = null,
    val text: String? = null
)

@Serializable
data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicResponseContent> = emptyList()
)

@Serializable
data class AnthropicResponseContent(
    val type: String,
    val text: String? = null
)

// --- OpenAI (and OpenAI-compatible: Groq, Together, Ollama) ---

@Serializable
data class OpenAiRequest(
    val model: String = "gpt-4o",
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val messages: List<OpenAiMessage>
)

@Serializable
data class OpenAiMessage(
    val role: String = "user",
    val content: List<OpenAiContent>
)

@Serializable
data class OpenAiContent(
    val type: String,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null,
    val text: String? = null
)

@Serializable
data class OpenAiImageUrl(
    val url: String,
    val detail: String = "low"
)

@Serializable
data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList()
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiChoiceMessage? = null
)

@Serializable
data class OpenAiChoiceMessage(
    val content: String? = null
)

// --- Google Gemini ---

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
    val text: String? = null
)

@Serializable
data class GeminiInlineData(
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    val data: String
)

@Serializable
data class GeminiGenerationConfig(
    val maxOutputTokens: Int = 256
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
data class GeminiCandidate(
    val content: GeminiCandidateContent? = null
)

@Serializable
data class GeminiCandidateContent(
    val parts: List<GeminiResponsePart> = emptyList()
)

@Serializable
data class GeminiResponsePart(
    val text: String? = null
)

// --- Legacy aliases for backward compatibility ---

typealias VisionRequest = AnthropicRequest
typealias VisionMessage = AnthropicMessage
typealias VisionContent = AnthropicContent
typealias ImageSource = AnthropicImageSource
typealias VisionResponse = AnthropicResponse
typealias ResponseContent = AnthropicResponseContent

// --- Directive parser (provider-agnostic) ---

object CloudDirectiveParser {

    private val directionKeywords = mapOf(
        ArrowDirection.UP to listOf("tilt up", "raise", "look up", "higher", "upward"),
        ArrowDirection.DOWN to listOf("tilt down", "lower", "look down", "overhead", "downward"),
        ArrowDirection.LEFT to listOf("step left", "move left", "shift left", "pan left"),
        ArrowDirection.RIGHT to listOf("step right", "move right", "shift right", "pan right"),
        ArrowDirection.STEADY to listOf("hold steady", "stay still", "don't move", "stabilize")
    )

    fun parse(text: String): CoachingHint {
        val lower = text.lowercase()
        val arrow = directionKeywords.entries
            .firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }
            ?.key ?: ArrowDirection.NONE
        return CoachingHint(text = text, arrow = arrow, priority = 15)
    }
}
