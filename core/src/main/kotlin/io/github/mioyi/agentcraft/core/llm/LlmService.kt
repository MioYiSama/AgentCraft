package io.github.mioyi.agentcraft.core.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.mioyi.agentcraft.core.LlmConfig

/** Koog executor + model bound to the OpenAI Responses API. */
class LlmService(config: LlmConfig) : AutoCloseable {

    /**
     * Koog discovers its HTTP client factory (and Ktor its engine) through [java.util.ServiceLoader] on the
     * thread context classloader, which on a server thread cannot see the plugin jar.
     */
    val executor: MultiLLMPromptExecutor = withContextClassLoader(LlmService::class.java.classLoader) {
        MultiLLMPromptExecutor(
            LLMProvider.OpenAI to OpenAILLMClient(
                apiKey = config.apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = config.baseUrl.trimEnd('/'),
                    responsesAPIPath = "responses",
                ),
            ),
        )
    }

    /** Responses-only capability + [OpenAIResponsesParams] forces Koog onto the Responses endpoint. */
    val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = config.model,
        capabilities = listOf(LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Responses),
    )

    fun params() = OpenAIResponsesParams(parallelToolCalls = false)

    /** Asks the model for a player name for [task]; null when the answer is not a valid, unrejected name. */
    suspend fun suggestName(task: String, rejected: List<String>): String? {
        val system = if (rejected.isEmpty()) NAME_PROMPT else "$NAME_PROMPT Do not use: ${rejected.joinToString(", ")}"
        val response = executor.execute(
            prompt("agentcraft-name", params = params()) {
                system(system)
                user(task)
            },
            model,
        )
        val name = response.textContent().trim()
            .trim('"', '\'', '`')
            .split(Regex("\\s+"))
            .first()
        return name.takeIf { NAME_REGEX.matches(it) && rejected.none { r -> r.equals(it, ignoreCase = true) } }
    }

    override fun close() = executor.close()

    private companion object {
        const val NAME_PROMPT = "Invent a Minecraft username for a bot that will perform the task below. " +
            "Reply with ONLY the username: 3-16 characters, letters/digits/underscore only, no spaces, no explanation."
        val NAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

        inline fun <T> withContextClassLoader(loader: ClassLoader, block: () -> T): T {
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = loader
            try {
                return block()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }
}
