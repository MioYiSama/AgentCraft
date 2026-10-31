package io.github.mioyi.agentcraft.core.llm

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Answers tool calls of the last assistant message that never got a result (the run was stopped or the
 * server shut down mid-action); providers reject histories with unanswered calls.
 */
fun closeDanglingToolCalls(p: Prompt): Prompt {
    val answered = p.messages.flatMap { it.parts }
        .filterIsInstance<MessagePart.Tool.Result>()
        .mapTo(HashSet()) { it.id }
    val last = p.messages.lastOrNull { it is Message.Assistant } ?: return p
    val dangling = last.parts.filterIsInstance<MessagePart.Tool.Call>().filter { it.id !in answered }
    if (dangling.isEmpty()) return p
    return prompt(p) {
        user {
            dangling.forEach {
                toolResult(
                    id = it.id,
                    tool = it.tool,
                    output = "Cancelled: the action was interrupted before it finished.",
                    isError = true,
                )
            }
        }
    }
}
