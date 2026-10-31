package io.github.mioyi.agentcraft.core

import kotlinx.serialization.Serializable

enum class LogKind { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, EVENT, ERROR }

@Serializable
data class LogEntry(val time: Long, val kind: LogKind, val text: String)

/** Bounded, thread-safe activity log of one agent. */
class AgentLog(initial: List<LogEntry> = emptyList()) {
    private val entries = ArrayDeque(initial.takeLast(MAX_ENTRIES))

    fun add(kind: LogKind, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), kind, text.take(MAX_TEXT))
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun last(n: Int): List<LogEntry> = synchronized(entries) { entries.takeLast(n) }

    fun snapshot(): List<LogEntry> = synchronized(entries) { entries.toList() }

    private companion object {
        const val MAX_ENTRIES = 100
        const val MAX_TEXT = 2000
    }
}
