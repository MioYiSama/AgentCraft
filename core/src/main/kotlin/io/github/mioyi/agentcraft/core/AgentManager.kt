package io.github.mioyi.agentcraft.core

import io.github.mioyi.agentcraft.core.llm.LlmService
import io.github.mioyi.agentcraft.core.llm.runAgent
import io.github.mioyi.agentcraft.core.store.AgentRecord
import io.github.mioyi.agentcraft.core.store.AgentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

/** Owns all agents. Command-facing methods run on the server thread and return reply lines. */
class AgentManager(private val platform: Platform, private val config: LlmConfig) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("AgentCraft"))
    private val agents = ConcurrentHashMap<Int, Agent>()
    private val store = AgentStore(platform.dataDir.resolve("agents.json"), platform::warn)

    @Volatile
    private var llm: LlmService? = null

    private fun llm(): LlmService = llm ?: synchronized(this) {
        llm ?: LlmService(config).also { llm = it }
    }

    private fun notConfigured(): String? =
        config.validate()?.let { "AgentCraft is not configured: $it (plugins/AgentCraft/config.yml)" }

    fun ids(): List<Int> = agents.keys.sorted()

    fun start(prompt: String, spawn: SpawnPoint, requester: String, requesterLocation: SpawnPoint?): String {
        notConfigured()?.let { return it }
        var id: Int
        do {
            id = Random.nextInt(1000, 10000)
        } while (agents.putIfAbsent(id, Agent(id, null, null, emptyList(), AgentLog(), AgentState.SPAWNING)) != null)
        val agent = agents.getValue(id)
        val input = formatInput(prompt, requester, requesterLocation)
        agent.log.add(LogKind.USER, input)
        scope.launch { spawnNew(agent, prompt, spawn, input) }
        return "Agent #$id created; choosing a name and spawning…"
    }

    private suspend fun spawnNew(agent: Agent, task: String, spawn: SpawnPoint, input: String) {
        try {
            val name = chooseName(agent, task)
            agent.name = name
            val uuid = UUID.nameUUIDFromBytes("AgentCraft:${agent.id}:$name:${System.nanoTime()}".toByteArray())
            agent.uuid = uuid
            val body = withContext(NonCancellable) { platform.spawnBody(BodySpec(uuid, name, spawn)) }
            synchronized(agent.lock) {
                if (agent.destroyed) {
                    platform.runOnMain {
                        body.despawn()
                        platform.deleteBodyData(uuid)
                    }
                    return
                }
                agent.body = body
                platform.notifyOperators("Agent #${agent.id} spawned as $name")
                saveAsync()
                startRun(agent, input)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            agent.log.add(LogKind.ERROR, "Spawn failed: ${e.message}")
            agents.remove(agent.id, agent)
            platform.notifyOperators("Agent #${agent.id} failed to spawn: ${e.message}")
        }
    }

    private suspend fun chooseName(agent: Agent, task: String): String {
        val rejected = ArrayList<String>()
        try {
            repeat(3) {
                val candidate = llm().suggestName(task, rejected) ?: return@repeat
                val taken = agents.values.any { it !== agent && it.name.equals(candidate, ignoreCase = true) }
                if (!taken && platform.isNameAvailable(candidate)) return candidate
                rejected += candidate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            agent.log.add(LogKind.ERROR, "Name suggestion failed: ${e.message}")
        }
        return "Agent_${agent.id}"
    }

    fun chat(id: Int, prompt: String, requester: String, requesterLocation: SpawnPoint?): String {
        val agent = agents[id] ?: return "No agent with id $id"
        val input = formatInput(prompt, requester, requesterLocation)
        synchronized(agent.lock) {
            when (agent.state) {
                AgentState.SPAWNING, AgentState.WORKING -> {
                    agent.inbox.addLast(input)
                    agent.log.add(LogKind.USER, input)
                    return "Instruction queued for agent #$id"
                }

                AgentState.IDLE -> {
                    if (agent.body == null) return "Agent #$id has no body in the world"
                    notConfigured()?.let { return it }
                    agent.log.add(LogKind.USER, input)
                    startRun(agent, input)
                    return "Agent #$id resumed"
                }
            }
        }
    }

    fun list(): List<String> {
        if (agents.isEmpty()) return listOf("No agents.")
        return agents.values.sortedBy { it.id }.map { agent ->
            val where = agent.body?.location()?.let { "${it.world} ${floor(it.x).toInt()} ${floor(it.y).toInt()} ${floor(it.z).toInt()}" }
                ?: "offline"
            val lastUser = agent.log.snapshot().lastOrNull { it.kind == LogKind.USER }?.text?.take(40) ?: ""
            "#${agent.id} ${agent.name ?: "(naming)"} [${agent.state}] $where — $lastUser"
        }
    }

    fun view(id: Int): List<String> {
        val agent = agents[id] ?: return listOf("No agent with id $id")
        val header = "Agent #${agent.id} ${agent.name ?: "(naming)"} [${agent.state}]"
        return listOf(header) + agent.log.last(10).map {
            val time = TIME.format(Instant.ofEpochMilli(it.time))
            "[$time] ${it.kind} ${it.text.take(300).replace('\n', ' ')}"
        }
    }

    fun stop(id: Int, requester: String): String {
        val agent = agents[id] ?: return "No agent with id $id"
        synchronized(agent.lock) {
            when (agent.state) {
                AgentState.SPAWNING -> return "Agent #$id is still spawning"
                AgentState.IDLE -> return "Agent #$id is already idle"
                AgentState.WORKING -> {
                    agent.inbox.clear()
                    agent.state = AgentState.IDLE
                    agent.job?.cancel()
                    agent.body?.halt()
                    agent.log.add(LogKind.EVENT, "Stopped by $requester")
                }
            }
        }
        saveAsync()
        return "Agent #$id stopped"
    }

    fun destroy(id: Int, requester: String): String {
        val agent = agents[id] ?: return "No agent with id $id"
        val body = synchronized(agent.lock) {
            agent.destroyed = true
            agent.job?.cancel()
            agents.remove(id)
            agent.body
        }
        if (body != null) {
            body.despawn()
            platform.deleteBodyData(body.uuid)
        }
        platform.log("Agent #$id destroyed by $requester")
        saveAsync()
        return "Agent #$id destroyed"
    }

    /** Called with [Agent.lock] held, or from the spawn job before the agent is visible as working. */
    private fun startRun(agent: Agent, input: String) {
        val body = agent.body ?: return
        val service = llm()
        agent.state = AgentState.WORKING
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            try {
                val result = runAgent(agent, body, input, service, config.maxIterations)
                if (agent.job === self) {
                    agent.state = AgentState.IDLE
                    platform.notifyOperators("Agent #${agent.id} (${agent.name}) finished: ${result.take(200)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                agent.log.add(LogKind.ERROR, e.message ?: e.toString())
                if (agent.job === self) {
                    agent.state = AgentState.IDLE
                    platform.notifyOperators("Agent #${agent.id} (${agent.name}) failed: ${(e.message ?: e.toString()).take(200)}")
                }
            } finally {
                body.halt()
                saveAsync()
            }
        }
        agent.job = job
        job.start()
    }

    fun saveAsync() = platform.runOnMain { saveNow() }

    /** Main thread only. */
    fun saveNow() {
        val records = agents.values.sortedBy { it.id }.mapNotNull { agent ->
            val name = agent.name ?: return@mapNotNull null
            val uuid = agent.uuid ?: return@mapNotNull null
            AgentRecord(
                id = agent.id,
                name = name,
                uuid = uuid.toString(),
                location = agent.body?.location(),
                messages = agent.history,
                log = agent.log.snapshot(),
            )
        }
        try {
            store.save(records)
        } catch (e: Exception) {
            platform.warn("Could not save agents: ${e.message}")
        }
    }

    /** Main thread, once after enable: re-creates saved agents as idle and respawns their bodies. */
    fun restoreAll() {
        for (record in store.load()) {
            val uuid = UUID.fromString(record.uuid)
            val agent = Agent(record.id, record.name, uuid, record.messages, AgentLog(record.log), AgentState.IDLE)
            agents[record.id] = agent
            scope.launch {
                try {
                    val body = platform.spawnBody(BodySpec(uuid, record.name, record.location))
                    synchronized(agent.lock) {
                        if (agent.destroyed) {
                            platform.runOnMain {
                                body.despawn()
                                platform.deleteBodyData(uuid)
                            }
                            return@launch
                        }
                        agent.body = body
                    }
                    platform.log("Agent #${record.id} restored as ${record.name}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    agent.log.add(LogKind.ERROR, "Restore failed: ${e.message}")
                    platform.warn("Agent #${record.id} (${record.name}) could not be restored: ${e.message}")
                }
            }
        }
    }

    /** Something happened to a body (death, ...): logged, and forwarded to the model while working. */
    fun onBodyEvent(uuid: UUID, text: String) {
        val agent = agents.values.firstOrNull { it.uuid == uuid } ?: return
        agent.log.add(LogKind.EVENT, text)
        synchronized(agent.lock) {
            if (agent.state == AgentState.WORKING) agent.inbox.addLast("[event] $text")
        }
    }

    /** Main thread, from onDisable. */
    fun shutdown() {
        saveNow()
        scope.cancel()
        for (agent in agents.values) agent.body?.despawn()
        llm?.close()
    }

    private fun formatInput(prompt: String, requester: String, at: SpawnPoint?): String =
        if (at == null) {
            "[from $requester] $prompt"
        } else {
            "[from $requester at ${at.world} ${floor(at.x).toInt()} ${floor(at.y).toInt()} ${floor(at.z).toInt()}] $prompt"
        }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
