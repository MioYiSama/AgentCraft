package io.github.mioyi.agentcraft.bukkit.nms

import io.netty.channel.ChannelFutureListener
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/** A server-side connection with no client behind it; every outgoing packet is dropped. */
class FakeConnection : Connection(PacketFlow.SERVERBOUND) {
    init {
        channel = FakeChannel()
        address = channel.remoteAddress()
        configureSerialization(channel.pipeline(), PacketFlow.SERVERBOUND, false, null)
    }

    override fun isConnected() = true

    override fun send(packet: Packet<*>) {}

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) {}

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {}
}
