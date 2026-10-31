package io.github.mioyi.agentcraft.core

import ai.koog.prompt.message.Message
import kotlinx.coroutines.Job
import java.util.UUID

enum class AgentState { SPAWNING, WORKING, IDLE }

class Agent(
    val id: Int,
    @Volatile var name: String?,
    @Volatile var uuid: UUID?,
    history: List<Message>,
    val log: AgentLog,
    state: AgentState,
) {
    @Volatile
    var state: AgentState = state

    @Volatile
    var body: AgentBody? = null

    @Volatile
    var job: Job? = null

    /** Instructions received while working; guarded by [lock]. */
    val inbox = ArrayDeque<String>()

    /** Conversation without the system message. */
    @Volatile
    var history: List<Message> = history

    @Volatile
    var destroyed = false

    val lock = Any()

    /**
     * Drains the inbox; when it is empty the agent becomes [AgentState.IDLE] in the same critical section,
     * so a concurrent chat either lands in this run or starts a new one.
     */
    fun takeInboxOrFinish(): List<String> = synchronized(lock) {
        if (inbox.isEmpty()) {
            state = AgentState.IDLE
            emptyList()
        } else {
            inbox.toList().also { inbox.clear() }
        }
    }
}
