package io.github.mioyi.agentcraft.bukkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin

/** Runs [block] on the server thread (inline when already there) and returns its result. */
suspend fun <T> Plugin.onMain(block: () -> T): T {
    if (Bukkit.isPrimaryThread()) return block()
    if (!isEnabled) throw CancellationException("plugin disabled")
    return suspendCancellableCoroutine { cont ->
        try {
            Bukkit.getScheduler().runTask(this, Runnable {
                if (cont.isActive) cont.resumeWith(runCatching(block))
            })
        } catch (_: IllegalPluginAccessException) {
            cont.cancel(CancellationException("plugin disabled"))
        }
    }
}
