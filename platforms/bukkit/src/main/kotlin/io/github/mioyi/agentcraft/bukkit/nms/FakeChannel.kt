package io.github.mioyi.agentcraft.bukkit.nms

import io.netty.channel.AbstractChannel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.DefaultEventLoop
import io.netty.channel.EventLoop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

/** A permanently open channel that discards everything written to it. */
class FakeChannel : AbstractChannel(null) {
    private val config = DefaultChannelConfig(this)

    override fun config(): ChannelConfig = config
    override fun isOpen() = true
    override fun isActive() = true
    override fun metadata() = METADATA
    override fun eventLoop(): EventLoop = EVENT_LOOP

    override fun localAddress0(): SocketAddress = ADDRESS
    override fun remoteAddress0(): SocketAddress = ADDRESS

    override fun doWrite(buf: ChannelOutboundBuffer) {
        while (buf.current() != null) buf.remove()
    }

    override fun doBind(localAddress: SocketAddress) {}
    override fun doDisconnect() {}
    override fun doClose() {}
    override fun doBeginRead() {}
    override fun isCompatible(loop: EventLoop) = true

    override fun newUnsafe(): AbstractUnsafe = object : AbstractUnsafe() {
        override fun connect(remote: SocketAddress?, local: SocketAddress?, promise: ChannelPromise) {
            promise.setSuccess()
        }
    }

    private companion object {
        val METADATA = ChannelMetadata(true)
        val ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)
        val EVENT_LOOP = DefaultEventLoop()
    }
}
