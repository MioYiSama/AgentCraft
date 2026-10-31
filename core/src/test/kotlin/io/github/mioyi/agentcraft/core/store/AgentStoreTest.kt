package io.github.mioyi.agentcraft.core.store

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import io.github.mioyi.agentcraft.core.LogEntry
import io.github.mioyi.agentcraft.core.LogKind
import io.github.mioyi.agentcraft.core.SpawnPoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AgentStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `round-trips messages with tool calls and results`() {
        val messages = prompt("t") {
            user("hi")
            assistant(
                listOf(
                    MessagePart.Text("walking"),
                    MessagePart.Tool.Call(id = "c1", tool = "walk_to", args = "{\"x\":1}"),
                ),
            )
            user { toolResult(id = "c1", tool = "walk_to", output = "Arrived at 1 64 0") }
        }.messages
        val record = AgentRecord(
            id = 1234,
            name = "Bot",
            uuid = "00000000-0000-0000-0000-000000000001",
            location = SpawnPoint("minecraft:overworld", 1.5, 64.0, -2.5, 90f, 0f),
            messages = messages,
            log = listOf(LogEntry(1L, LogKind.USER, "hi")),
        )
        val store = AgentStore(dir.resolve("agents.json"))

        store.save(listOf(record))

        assertEquals(listOf(record), store.load())
    }

    @Test
    fun `unparseable file is moved aside and yields no agents`() {
        val file = dir.resolve("agents.json")
        file.writeText("{not json")

        assertEquals(emptyList(), AgentStore(file).load())
        assertTrue(Files.notExists(file))
        assertTrue(dir.listDirectoryEntries().any { it.name.startsWith("agents.json.broken-") })
    }
}
