package io.github.mioyi.agentcraft.core.store

import ai.koog.prompt.message.Message
import io.github.mioyi.agentcraft.core.LogEntry
import io.github.mioyi.agentcraft.core.SpawnPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persisted agent; [messages] never contains the system message. */
@Serializable
data class AgentRecord(
    val id: Int,
    val name: String,
    val uuid: String,
    val location: SpawnPoint?,
    val messages: List<Message>,
    val log: List<LogEntry>,
)

@Serializable
data class StoreFile(val agents: List<AgentRecord>)

class AgentStore(private val file: Path, private val warn: (String) -> Unit = {}) {

    fun load(): List<AgentRecord> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(StoreFile.serializer(), Files.readString(file)).agents
        } catch (e: Exception) {
            val broken = file.resolveSibling("${file.fileName}.broken-${System.currentTimeMillis()}")
            warn("Could not parse $file (${e.message}); moved it to ${broken.fileName}")
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING)
            emptyList()
        }
    }

    fun save(records: List<AgentRecord>) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(StoreFile.serializer(), StoreFile(records)))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
