package com.spectra.ai.cloud

import com.spectra.ai.model.ArrowDirection
import com.spectra.ai.model.CoachingHint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisionRequest(
    val model: String = "claude-sonnet-4-20250514",
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val messages: List<VisionMessage>
)

@Serializable
data class VisionMessage(
    val role: String = "user",
    val content: List<VisionContent>
)

@Serializable
data class VisionContent(
    val type: String,
    val source: ImageSource? = null,
    val text: String? = null
)

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String
)

@Serializable
data class VisionResponse(
    val content: List<ResponseContent> = emptyList()
)

@Serializable
data class ResponseContent(
    val type: String,
    val text: String? = null
)

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
