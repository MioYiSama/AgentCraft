package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.bukkit.nms.FakePlayers
import io.github.mioyi.agentcraft.core.AgentBody
import io.github.mioyi.agentcraft.core.BodySpec
import io.github.mioyi.agentcraft.core.Platform
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class BukkitPlatform(private val plugin: JavaPlugin) : Platform {
    private val bodies = ConcurrentHashMap<UUID, BukkitAgentBody>()
    private var ticks = 0L

    override val dataDir: Path = plugin.dataFolder.toPath()

    override fun log(message: String) = plugin.logger.info(message)

    override fun warn(message: String) = plugin.logger.warning(message)

    override fun notifyOperators(message: String) {
        val text = Component.text("[AgentCraft] ", NamedTextColor.AQUA).append(Component.text(message))
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().filter { it.isOp }.forEach { it.sendMessage(text) }
        } else {
            runOnMain { Bukkit.getOnlinePlayers().filter { it.isOp }.forEach { it.sendMessage(text) } }
        }
        log(message)
    }

    override fun runOnMain(task: () -> Unit) {
        if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(task))
    }

    override suspend fun isNameAvailable(name: String): Boolean = plugin.onMain {
        Bukkit.getPlayerExact(name) == null && Bukkit.getOfflinePlayerIfCached(name) == null
    }

    override suspend fun spawnBody(spec: BodySpec): AgentBody = plugin.onMain {
        FakePlayers.spawn(spec)
        BukkitAgentBody(plugin, spec.uuid, spec.name) { bodies.remove(it) }.also { bodies[spec.uuid] = it }
    }

    override fun deleteBodyData(uuid: UUID) {
        for (file in FakePlayers.dataFiles(uuid)) {
            try {
                Files.delete(file)
                log("Deleted $file")
            } catch (e: Exception) {
                warn("Could not delete $file: ${e.message}")
            }
        }
    }

    fun body(uuid: UUID): BukkitAgentBody? = bodies[uuid]

    /** Advances every body once per server tick. */
    fun startTicker() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            ticks++
            for (body in bodies.values) {
                try {
                    body.tick(ticks)
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Ticking ${body.name} failed", e)
                }
            }
        }, 1L, 1L)
    }
}
