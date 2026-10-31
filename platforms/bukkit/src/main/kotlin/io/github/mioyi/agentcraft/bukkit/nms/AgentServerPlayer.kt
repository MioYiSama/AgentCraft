package io.github.mioyi.agentcraft.bukkit.nms

import com.mojang.authlib.GameProfile
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * A player whose movement is simulated by the server instead of a client. Vanilla treats players (and the
 * vehicles they control) as client-authoritative, so the server would skip fall damage and never move a
 * ridden vehicle; there is no client here, so the server must be the authority. Paper reuses this object
 * on respawn.
 */
class AgentServerPlayer(server: MinecraftServer, level: ServerLevel, profile: GameProfile) :
    ServerPlayer(server, level, profile, ClientInformation.createDefault()) {

    override fun isClientAuthoritative() = false
}
