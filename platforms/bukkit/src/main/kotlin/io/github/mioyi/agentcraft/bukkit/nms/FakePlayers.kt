package io.github.mioyi.agentcraft.bukkit.nms

import com.mojang.authlib.GameProfile
import io.github.mioyi.agentcraft.core.BodySpec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.TagValueInput
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Creates and removes logged-in NMS players without a client. Main thread only. */
object FakePlayers {
    private val server get() = (Bukkit.getServer() as CraftServer).server

    fun spawn(spec: BodySpec): Player {
        check(Bukkit.getPlayer(spec.uuid) == null) { "a player with uuid ${spec.uuid} is already online" }
        val server = server
        val level = (Bukkit.getWorlds()[0] as CraftWorld).handle
        val profile = GameProfile(spec.uuid, spec.name)
        val handle = AgentServerPlayer(server, level, profile)
        server.playerList.playerIo.load(handle.nameAndId()).ifPresent { tag ->
            handle.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag))
        }
        handle.bukkitEntity.isPersistent = true

        val connection = FakeConnection()
        val cookie = CommonListenerCookie.createInitial(profile, false)
        server.playerList.placeNewPlayer(connection, handle, cookie)
        handle.connection = AgentPacketListener(server, connection, handle, cookie)

        val player = handle.bukkitEntity
        spec.spawn?.let { spawn ->
            val world = NamespacedKey.fromString(spawn.world)?.let(Bukkit::getWorld) ?: Bukkit.getWorlds()[0]
            player.teleport(
                Location(world, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch),
                PlayerTeleportEvent.TeleportCause.PLUGIN,
            )
        }
        return player
    }

    /** Logs the player out, saving its player data, and broadcasts the quit message. */
    fun remove(uuid: UUID) {
        val player = server.playerList.getPlayer(uuid) ?: return
        server.playerList.remove(player)?.let { Bukkit.getServer().sendMessage(it) }
    }

    /** Player data, stats and advancement files of [uuid]. */
    fun dataFiles(uuid: UUID): List<Path> = listOf(
        LevelResource.PLAYER_DATA_DIR,
        LevelResource.PLAYER_OLD_DATA_DIR,
        LevelResource.PLAYER_STATS_DIR,
        LevelResource.PLAYER_ADVANCEMENTS_DIR,
    ).map { server.getWorldPath(it) }.distinct().filter { Files.isDirectory(it) }.flatMap { dir ->
        Files.list(dir).use { files -> files.filter { it.fileName.toString().startsWith(uuid.toString()) }.toList() }
    }

    /** Respawning replaces the NMS player object, so always resolve it by uuid. */
    fun handle(uuid: UUID): ServerPlayer? = (Bukkit.getPlayer(uuid) as? CraftPlayer)?.handle
}
