package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.core.AgentManager
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.Plugin

class BodyListener(
    private val plugin: Plugin,
    private val platform: BukkitPlatform,
    private val manager: AgentManager,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val uuid = event.player.uniqueId
        val body = platform.body(uuid) ?: return
        body.failAction("Error: you died")
        val cause = event.deathMessage()?.let { PlainTextComponentSerializer.plainText().serialize(it) } ?: "unknown cause"
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val player = Bukkit.getPlayer(uuid) ?: return@Runnable
            if (player.isDead) player.spigot().respawn()
            val loc = player.location
            manager.onBodyEvent(uuid, "You died ($cause) and respawned at ${loc.blockX} ${loc.blockY} ${loc.blockZ}")
        }, 1L)
    }
}
