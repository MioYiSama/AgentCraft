package io.github.mioyi.agentcraft.bukkit.nms

import io.netty.channel.ChannelFutureListener
import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Game listener of a fake player. Plays the client's side of the protocol: confirms world loading and
 * teleports, applies knockback, remembers opened sign editors and keeps the chat the player would read.
 * Every other clientbound packet is dropped.
 */
class AgentPacketListener(
    server: MinecraftServer,
    connection: Connection,
    player: ServerPlayer,
    cookie: CommonListenerCookie,
) : ServerGamePacketListenerImpl(server, connection, player, cookie) {

    /** A sign the server opened an editor for: [pos] and whether the front side is edited. */
    data class SignEditor(val pos: BlockPos, val front: Boolean)

    private val messages = ConcurrentLinkedDeque<String>()

    /** Last teleport the server asked the client to confirm; main thread only. */
    private var pendingTeleport: Int? = null

    /** Velocity the server last told the client to take (knockback, explosions); main thread only. */
    private var pendingMotion: Vec3? = null

    @Volatile
    var signEditor: SignEditor? = null

    val unreadMessages: Int get() = messages.size

    /** Removes and returns everything received since the last call. */
    fun drainMessages(): List<String> = generateSequence { messages.pollFirst() }.toList()

    /**
     * What a client does at the start of each of its ticks, before input and physics: apply received
     * velocity, report the world as loaded and confirm teleports. Without the confirmation the server
     * ignores block interactions. Main thread only.
     */
    fun clientTick() {
        pendingMotion?.let {
            pendingMotion = null
            player.lerpMotion(it)
        }
        if (player.isAlive && !hasClientLoaded()) handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        pendingTeleport?.let { id ->
            pendingTeleport = null
            handleAcceptTeleportPacket(
                ServerboundAcceptTeleportationPacket(id, player.x, player.y, player.z, player.yRot, player.xRot),
            )
        }
    }

    override fun send(packet: Packet<*>) {
        when (packet) {
            is ClientboundBundlePacket -> packet.subPackets().forEach { send(it) }

            // Player.attack restores the victim's velocity right after sending knockback, so it is applied
            // on the next client tick, as a client would.
            is ClientboundSetEntityMotionPacket -> if (packet.id() == player.id) pendingMotion = packet.movement()

            is ClientboundRespawnPacket -> player.hasChangedDimension()
            is ClientboundPlayerPositionPacket -> pendingTeleport = packet.id()
            is ClientboundOpenSignEditorPacket ->
                signEditor = SignEditor(packet.pos(), packet.slot() == SignTextSlot.FRONT)

            is ClientboundSystemChatPacket ->
                receive(if (packet.overlay()) "[action bar]" else "[system]", packet.content().string)

            is ClientboundPlayerChatPacket -> if (packet.sender() != player.uuid) {
                val content = packet.unsignedContent().orElseGet {
                    net.minecraft.network.chat.Component.literal(packet.body().content())
                }
                receive("[chat]", packet.chatType().decorate(content).string)
            }

            is ClientboundDisguisedChatPacket -> receive("[chat]", packet.chatType().decorate(packet.message()).string)
            is ClientboundSetTitleTextPacket -> receive("[title]", packet.text().string)
            is ClientboundSetSubtitleTextPacket -> receive("[subtitle]", packet.text().string)
            is ClientboundSetActionBarTextPacket -> receive("[action bar]", packet.text().string)
        }
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) = send(packet)

    private fun receive(kind: String, text: String) {
        if (text.isBlank()) return
        messages.addLast("$kind ${text.take(MAX_MESSAGE)}")
        while (messages.size > MAX_MESSAGES) messages.pollFirst()
    }

    private companion object {
        const val MAX_MESSAGES = 100
        const val MAX_MESSAGE = 300
    }
}
