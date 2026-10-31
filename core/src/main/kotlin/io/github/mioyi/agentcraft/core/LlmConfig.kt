package io.github.mioyi.agentcraft.core

data class LlmConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val maxIterations: Int,
) {
    /** Returns the first configuration problem, or null when the config is usable. */
    fun validate(): String? = when {
        apiKey.isBlank() -> "llm.api-key is empty"
        baseUrl.isBlank() -> "llm.base-url is empty"
        baseUrl.contains("openai.azure.com") ->
            "Azure endpoints are not supported (Koog forces chat completions for Azure)"
        model.isBlank() -> "llm.model is empty"
        maxIterations !in 1..1000 -> "agent.max-iterations must be 1..1000"
        else -> null
    }
}
