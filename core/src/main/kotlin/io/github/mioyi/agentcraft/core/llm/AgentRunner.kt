package io.github.mioyi.agentcraft.core.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import io.github.mioyi.agentcraft.core.Agent
import io.github.mioyi.agentcraft.core.AgentBody
import io.github.mioyi.agentcraft.core.AgentState
import io.github.mioyi.agentcraft.core.LogKind
import io.github.mioyi.agentcraft.core.tools.agentTools

/**
 * Runs one agent turn: sends [input], executes tool calls until the model answers without any, folding in
 * instructions that arrive meanwhile. The history is published to [Agent.history] after every change.
 */
suspend fun runAgent(agent: Agent, body: AgentBody, input: String, service: LlmService, maxIterations: Int): String {
    val initial = closeDanglingToolCalls(
        prompt("agent-${agent.id}", params = service.params()) {
            system(systemPrompt(agent.id, body.name))
        }.withMessages { it + agent.history },
    )

    fun publish(p: Prompt) {
        agent.history = p.messages.filter { it !is Message.System }
    }

    val strategy = functionalStrategy<String, String>("agentcraft") { firstInput ->
        llm.writeSession { appendPrompt { user(firstInput) } }
        publish(llm.readSession { prompt })
        var response = llm.writeSession { requestLLM() }
        publish(llm.readSession { prompt })
        var requests = 1
        while (true) {
            response.textContent().takeIf { it.isNotBlank() }?.let { agent.log.add(LogKind.ASSISTANT, it) }
            val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            val results = if (calls.isEmpty()) emptyList() else executeTools(calls)
            val pending = if (calls.isEmpty()) {
                agent.takeInboxOrFinish()
            } else {
                synchronized(agent.lock) { agent.inbox.toList().also { agent.inbox.clear() } }
            }
            if (calls.isEmpty() && pending.isEmpty()) return@functionalStrategy response.textContent()
            if (requests >= maxIterations) {
                agent.log.add(LogKind.EVENT, "Iteration limit ($maxIterations) reached")
                agent.state = AgentState.IDLE
                return@functionalStrategy ""
            }
            llm.writeSession {
                appendPrompt {
                    if (results.isNotEmpty()) user { results.forEach { toolResult(it.toMessagePart()) } }
                    pending.forEach { user(it) }
                }
            }
            publish(llm.readSession { prompt })
            response = llm.writeSession { requestLLM() }
            publish(llm.readSession { prompt })
            requests++
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    val config = AIAgentConfig(prompt = initial, model = service.model, maxAgentIterations = maxIterations)
    return AIAgent(
        promptExecutor = service.executor,
        agentConfig = config,
        strategy = strategy,
        toolRegistry = agentTools(body, agent.log),
    ).run(input)
}
