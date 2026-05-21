package com.spectra.ai.cloud

enum class AiProvider(val displayName: String, val defaultBaseUrl: String) {
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com"),
    OPENAI("OpenAI (GPT)", "https://api.openai.com"),
    GEMINI("Google (Gemini)", "https://generativelanguage.googleapis.com"),
    OPENAI_COMPATIBLE("Custom (Llama, Groq…)", "");

    companion object {
        fun modelsFor(provider: AiProvider): List<String> = when (provider) {
            ANTHROPIC -> listOf("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001")
            OPENAI -> listOf("gpt-4o", "gpt-4o-mini")
            GEMINI -> listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash")
            OPENAI_COMPATIBLE -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        }
    }
}

data class AiProviderConfig(
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = false
) {
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider.defaultBaseUrl }

    val effectiveModel: String
        get() = model.ifBlank { AiProvider.modelsFor(provider).first() }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()
}
