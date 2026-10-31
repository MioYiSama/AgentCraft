package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.bukkit.nms.AgentPacketListener
import io.github.mioyi.agentcraft.bukkit.nms.FakePlayers
import io.github.mioyi.agentcraft.core.AgentBody
import io.github.mioyi.agentcraft.core.ClickMode
import io.github.mioyi.agentcraft.core.Face
import io.github.mioyi.agentcraft.core.Hand
import io.github.mioyi.agentcraft.core.SpawnPoint
import io.github.mioyi.agentcraft.core.WaitUntil
import io.github.mioyi.agentcraft.core.nav.BlockPos3
import io.github.mioyi.agentcraft.core.nav.PathFinder
import io.papermc.paper.inventory.tooltip.TooltipContext
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HasCustomInventoryScreen
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.BeaconMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.CrafterMenu
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.item.crafting.display.RecipeDisplayId
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Keyed
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.craftbukkit.util.CraftNamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.CraftingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView
import org.bukkit.inventory.view.BeaconView
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.lang.reflect.Field
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Controls one fake player the way a client would: every action is a serverbound packet handed to the
 * player's own packet listener, and movement is held keys applied through [Controls]. Long actions
 * (walking, mining, holding use) are advanced by [tick], which the platform calls every server tick before
 * the player's physics run.
 */
class BukkitAgentBody(
    private val plugin: Plugin,
    override val uuid: UUID,
    override val name: String,
    private val onDespawn: (UUID) -> Unit,
) : AgentBody {

    private sealed class BodyAction(val cont: CancellableContinuation<String>) {
        val cancelled = AtomicBoolean()
        var ticks = 0

        init {
            cont.invokeOnCancellation { cancelled.set(true) }
        }

        class Walk(
            var path: List<BlockPos3>,
            val goal: BlockPos3,
            val within: Double,
            val sprint: Boolean,
            var timeoutTicks: Int,
            cont: CancellableContinuation<String>,
        ) : BodyAction(cont) {
            var index = 0
            var best = Double.MAX_VALUE
            var sinceBest = 0
            var replans = 0
        }

        class Move(
            val forward: Int,
            val strafe: Int,
            val jump: Boolean,
            val sprint: Boolean,
            val duration: Int,
            cont: CancellableContinuation<String>,
        ) : BodyAction(cont)

        class Break(val pos: BlockPos, val face: Direction, val type: Material, cont: CancellableContinuation<String>) :
            BodyAction(cont) {
            var progress = 0f
            var started = false
            var stoppedAt = -1
        }

        class Hold(var remaining: Int, val hand: InteractionHand, val used: Material, cont: CancellableContinuation<String>) :
            BodyAction(cont)

        class Jump(cont: CancellableContinuation<String>) : BodyAction(cont)

        class TakeOff(cont: CancellableContinuation<String>) : BodyAction(cont)

        class Dismount(cont: CancellableContinuation<String>) : BodyAction(cont)

        class Wait(val duration: Int, val until: WaitUntil?, val health: Double, cont: CancellableContinuation<String>) :
            BodyAction(cont)

        class Fish(val duration: Int, val hand: InteractionHand, cont: CancellableContinuation<String>) : BodyAction(cont) {
            var reeledAt = -1
            var before: Map<Material, Int> = emptyMap()
        }

        /** Answers with [then] after [remaining] ticks, for server handlers that complete asynchronously. */
        class Delay(var remaining: Int, val then: (Player, ServerPlayer) -> String, cont: CancellableContinuation<String>) :
            BodyAction(cont)
    }

    private sealed interface Step {
        class Done(val text: String) : Step
        class Start(val create: (CancellableContinuation<String>) -> BodyAction) : Step
    }

    /** Main-thread owned; read from other threads only by [halt]. */
    @Volatile
    private var action: BodyAction? = null

    private val controls = Controls()

    /** Prediction sequence of block interactions, as a client numbers them. */
    private var sequence = 0

    // ---------------------------------------------------------------- ticking (main thread)

    fun tick(n: Long) {
        val handle = FakePlayers.handle(uuid) ?: return
        if (handle.isRemoved) return
        val listener = handle.connection as AgentPacketListener
        listener.clientTick()
        controls.frame.clear()
        action?.let { a ->
            if (a.cancelled.get() || !a.cont.isActive) {
                finish(a, "Cancelled")
            } else {
                try {
                    advance(a, handle)
                } catch (e: Exception) {
                    finish(a, "Error: ${e.message}")
                }
            }
        }
        controls.apply(handle, listener)
        handle.doTick()
        if (n % 10 == 0L) handle.level().chunkSource.move(handle)
    }

    /** Ends the current action with [result] (main thread). */
    fun failAction(result: String) {
        action?.let { finish(it, result) }
    }

    private fun finish(a: BodyAction, result: String) {
        if (action === a) action = null
        FakePlayers.handle(uuid)?.let { h -> release(a, h) }
        if (a.cont.isActive) a.cont.resumeWith(Result.success(result))
    }

    /** Lets go of whatever [a] was holding down. */
    private fun release(a: BodyAction, h: ServerPlayer) {
        when (a) {
            is BodyAction.Break -> if (a.started && a.stoppedAt < 0 && !h.level().getBlockState(a.pos).isAir) {
                playerAction(h, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, a.pos, a.face)
            }

            is BodyAction.Hold -> if (h.isUsingItem) releaseUse(h)
            else -> {}
        }
    }

    private fun advance(a: BodyAction, h: ServerPlayer) {
        a.ticks++
        val frame = controls.frame
        when (a) {
            is BodyAction.Walk -> advanceWalk(a, h)
            is BodyAction.Break -> advanceBreak(a, h)
            is BodyAction.Move -> {
                frame.forward = a.forward
                frame.strafe = a.strafe
                frame.jump = a.jump
                frame.sprint = a.sprint
                if (a.ticks >= a.duration) finish(a, "Moved for ${a.duration} ticks; now at ${positionText(h.bukkitEntity)}")
            }

            is BodyAction.Hold -> {
                if (!h.isUsingItem) return finish(a, usedText(a.used, "done", h.bukkitEntity))
                if (--a.remaining <= 0) {
                    releaseUse(h)
                    finish(a, usedText(a.used, "released after ${a.ticks} ticks", h.bukkitEntity))
                }
            }

            is BodyAction.Jump -> {
                frame.jump = a.ticks == 1
                if (a.ticks >= 10) finish(a, "Jumped; now at ${positionText(h.bukkitEntity)}")
            }

            is BodyAction.TakeOff -> {
                frame.jump = a.ticks == 1
                if (!h.onGround()) {
                    Controls.setFlying(listener(h), true)
                    finish(a, flightText(h.bukkitEntity, true))
                } else if (a.ticks >= 10) {
                    finish(a, "Error: could not leave the ground to start flying")
                }
            }

            is BodyAction.Dismount -> {
                frame.sneak = true
                if (!h.isPassenger) return finish(a, "Dismounted at ${positionText(h.bukkitEntity)}")
                if (a.ticks >= 10) finish(a, "Error: still riding (dismounting was denied)")
            }

            is BodyAction.Wait -> {
                val player = h.bukkitEntity
                when {
                    a.until == WaitUntil.MESSAGE && unreadMessages() > 0 -> finish(a, "A message arrived after ${a.ticks / 20.0} s")
                    a.until == WaitUntil.HURT && player.health < a.health ->
                        finish(a, "Hurt after ${a.ticks / 20.0} s: health %.1f -> %.1f".format(a.health, player.health))

                    a.ticks >= a.duration -> finish(a, "Waited ${a.duration / 20} s")
                }
            }

            is BodyAction.Fish -> advanceFish(a, h)
            is BodyAction.Delay -> if (--a.remaining <= 0) finish(a, a.then(h.bukkitEntity, h))
        }
    }

    private fun advanceWalk(a: BodyAction.Walk, h: ServerPlayer) {
        val player = h.bukkitEntity
        if (a.ticks > a.timeoutTicks) return finish(a, "Error: timed out at ${blockText(player.location)}")
        while (a.index < a.path.size) {
            val wp = a.path[a.index]
            val horizontal = hypot(wp.x + 0.5 - h.x, wp.z + 0.5 - h.z)
            // Intermediate waypoints may be passed mid-jump or mid-fall; the last one only once standing on it.
            val settled = if (a.index == a.path.lastIndex) {
                abs(h.y - wp.y) < 0.6 && (h.onGround() || h.isInWater)
            } else {
                abs(h.y - wp.y) < 1.2
            }
            if (horizontal < 0.35 && settled) {
                a.index++
                a.best = Double.MAX_VALUE
                a.sinceBest = 0
            } else {
                break
            }
        }
        if (a.index >= a.path.size) return finish(a, "Arrived at ${blockText(player.location)}")

        val wp = a.path[a.index]
        val dx = wp.x + 0.5 - h.x
        val dz = wp.z + 0.5 - h.z
        val dy = wp.y - h.y
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < a.best - 0.05) {
            a.best = distance
            a.sinceBest = 0
        } else if (++a.sinceBest >= 30) {
            if (a.replans >= 2) {
                return finish(a, "Error: stuck at ${blockText(player.location)} after ${a.index}/${a.path.size} waypoints")
            }
            a.replans++
            val path = PathFinder(BukkitBlockView(player.world)).find(feet(player.location), a.goal, a.within)
                ?: return finish(a, "Error: stuck at ${blockText(player.location)}; no path found to ${posText(a.goal)}")
            if (path.isEmpty()) return finish(a, "Arrived at ${blockText(player.location)}")
            a.path = path
            a.index = 0
            a.best = Double.MAX_VALUE
            a.sinceBest = 0
            a.timeoutTicks = a.ticks + 20 * (path.size + 10)
            return
        }

        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        rotate(h, yaw, 0f)
        val frame = controls.frame
        frame.forward = 1
        // Sprinting overshoots the final waypoint.
        frame.sprint = a.sprint && (a.index < a.path.lastIndex || hypot(dx, dz) > 2.0)
        val feetY = floor(h.y + 0.01).toInt()
        frame.jump = wp.y > feetY || (h.horizontalCollision && h.onGround()) || (h.isInWater && wp.y >= feetY)
    }

    private fun advanceBreak(a: BodyAction.Break, h: ServerPlayer) {
        val player = h.bukkitEntity
        val level = h.level()
        val state = level.getBlockState(a.pos)
        val block = player.world.getBlockAt(a.pos.x, a.pos.y, a.pos.z)
        if (block.type != a.type) {
            return finish(
                a,
                if (a.started) brokeText(a, h) else "Error: block at ${blockText(block)} changed to ${block.type.typeKey}",
            )
        }
        if (a.stoppedAt >= 0) {
            // The server finishes a little later when our count ran ahead of its own.
            if (a.ticks - a.stoppedAt > 10) finish(a, "Error: breaking ${a.type.typeKey} at ${blockText(block)} was denied")
            return
        }
        blockReachError(player, block)?.let { return finish(a, it) }
        face(h, player.eyeLocation, block.location.add(0.5, 0.5, 0.5))
        if (!a.started) {
            a.started = true
            playerAction(h, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, a.pos, a.face)
            listener(h).handlePunch(ServerboundPunchPacket())
            if (block.type != a.type) finish(a, brokeText(a, h))
            return
        }
        listener(h).handlePunch(ServerboundPunchPacket())
        a.progress += state.getDestroyProgress(h, level, a.pos)
        if (a.progress >= 1f) {
            playerAction(h, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, a.pos, a.face)
            a.stoppedAt = a.ticks
            if (block.type != a.type) finish(a, brokeText(a, h))
            return
        }
        if (a.ticks > MAX_BREAK_TICKS) finish(a, "Error: gave up breaking ${a.type.typeKey} after ${a.ticks / 20} s")
    }

    private fun brokeText(a: BodyAction.Break, h: ServerPlayer) =
        "Broke ${a.type.typeKey} at ${a.pos.x} ${a.pos.y} ${a.pos.z} in ${a.ticks} ticks; now holding ${itemText(h.bukkitEntity.inventory.itemInMainHand)}"

    private fun advanceFish(a: BodyAction.Fish, h: ServerPlayer) {
        val player = h.bukkitEntity
        if (a.reeledAt >= 0) {
            if (a.ticks - a.reeledAt < 30) return
            val gained = counts(player).mapNotNull { (m, n) -> (n - (a.before[m] ?: 0)).takeIf { it > 0 }?.let { "${m.typeKey} x$it" } }
            return finish(a, if (gained.isEmpty()) "Reeled in; nothing arrived in your inventory" else "Reeled in and caught: ${gained.joinToString(", ")}")
        }
        val hook = h.fishing
        if (hook == null) {
            if (a.ticks > 1) return finish(a, "Error: the bobber is gone (it broke or hooked something)")
            useItemPacket(h, a.hand)
            if (h.fishing == null) finish(a, "Error: casting failed")
            return
        }
        val biting = BITING.getBoolean(hook)
        if (biting || a.ticks >= a.duration) {
            a.before = counts(player)
            useItemPacket(h, a.hand)
            a.reeledAt = a.ticks
            if (!biting) finish(a, "Nothing bit within ${a.duration / 20} s; reeled in")
        }
    }

    // ---------------------------------------------------------------- scheduling helpers

    /**
     * Runs [setup] on the main thread with the live player; it either answers immediately or starts a long
     * action that answers when [tick] completes it.
     */
    private suspend fun perform(setup: (Player, ServerPlayer) -> Step): String {
        if (!plugin.isEnabled) throw CancellationException("plugin disabled")
        return suspendCancellableCoroutine { cont ->
            val task = Runnable {
                if (!cont.isActive) return@Runnable
                val handle = FakePlayers.handle(uuid)
                if (handle == null || handle.isRemoved) {
                    cont.resumeWith(Result.success("Error: your body is not in the world"))
                    return@Runnable
                }
                val step = try {
                    setup(handle.bukkitEntity, handle)
                } catch (e: Exception) {
                    Step.Done("Error: ${e.message ?: e.toString()}")
                }
                when (step) {
                    is Step.Done -> cont.resumeWith(Result.success(step.text))
                    is Step.Start -> {
                        action?.let { finish(it, "Error: interrupted by a new action") }
                        action = step.create(cont)
                    }
                }
            }
            try {
                Bukkit.getScheduler().runTask(plugin, task)
            } catch (_: IllegalPluginAccessException) {
                cont.cancel(CancellationException("plugin disabled"))
            }
        }
    }

    private suspend fun now(block: (Player, ServerPlayer) -> String) = perform { p, h -> Step.Done(block(p, h)) }

    private fun later(ticks: Int, then: (Player, ServerPlayer) -> String) = Step.Start { BodyAction.Delay(ticks, then, it) }

    // ---------------------------------------------------------------- observation

    override suspend fun status(): String = now { p, h ->
        val loc = p.location
        val world = p.world
        val time = world.time
        val flags = listOfNotNull(
            "sneaking".takeIf { p.isSneaking },
            "sprinting".takeIf { p.isSprinting },
            "swimming".takeIf { p.isSwimming },
            "flying".takeIf { p.isFlying },
            "gliding".takeIf { p.isGliding },
            "sleeping".takeIf { p.isSleeping },
            "blocking".takeIf { p.isBlocking },
            "using ${p.activeItem.type.typeKey}".takeIf { p.hasActiveItem() },
            "in water".takeIf { p.isInWater },
            "on fire".takeIf { p.fireTicks > 0 },
            "on ground".takeIf { h.onGround() },
            "may fly".takeIf { p.allowFlight },
        )
        buildString {
            appendLine(
                "Position: world=%s x=%.2f y=%.2f z=%.2f yaw=%.0f pitch=%.0f facing=%s".format(
                    world.key.asString(), loc.x, loc.y, loc.z, loc.yaw, loc.pitch, p.facing.name.lowercase(),
                ),
            )
            appendLine("Health: %.1f/%.1f, armor %.0f, air %d/%d".format(
                p.health, p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0, p.getAttribute(Attribute.ARMOR)?.value ?: 0.0,
                p.remainingAir, p.maximumAir,
            ))
            appendLine("Food: ${p.foodLevel} (saturation %.1f)".format(p.saturation))
            appendLine("Experience: level ${p.level}, %.0f%% to next".format(p.exp * 100))
            appendLine("GameMode: ${p.gameMode.name.lowercase()}")
            appendLine("Held: main ${itemText(p.inventory.itemInMainHand)} (hotbar ${p.inventory.heldItemSlot}), offhand ${itemText(p.inventory.itemInOffHand)}")
            appendLine("Attack strength: %.0f%%".format(p.attackCooldown * 100))
            appendLine("State: ${flags.ifEmpty { listOf("standing") }.joinToString(", ")}")
            p.vehicle?.let { appendLine("Riding: ${it.type.key.asString()} ${it.uniqueId}") }
            val effects = p.activePotionEffects
            if (effects.isNotEmpty()) {
                appendLine("Effects: " + effects.joinToString(", ") { "${it.type.key.key} ${it.amplifier + 1} (${it.duration / 20}s)" })
            }
            if (h.containerMenu !== h.inventoryMenu) appendLine("Open container: ${p.openInventory.topInventory.type.name.lowercase()} (get_container)")
            listener(h).signEditor?.let { appendLine("Sign editor open at ${it.pos.x} ${it.pos.y} ${it.pos.z} (edit_sign)") }
            p.respawnLocation?.let { appendLine("Spawn point: ${blockText(it)} in ${it.world.key.asString()}") }
            val feetBlock = loc.block
            appendLine("Biome: ${world.getBiome(loc).key.asString()}, light ${feetBlock.lightLevel}")
            appendLine("World time: $time (${if (time % 24000 in 0..12999) "day" else "night"}), difficulty ${world.difficulty.name.lowercase()}")
            appendLine("Weather: ${if (world.isThundering) "thunder" else if (world.hasStorm()) "rain" else "clear"}")
            val on = h.onPos
            append("Standing on: ${world.getBlockAt(on.x, on.y, on.z).type.typeKey} at ${on.x} ${on.y} ${on.z}")
        }
    }

    override suspend fun inventory(): String = now { p, _ ->
        val inv = p.inventory
        val lines = ArrayList<String>()
        for (i in 0..40) inv.getItem(i)?.let { if (!it.type.isAir) lines += "slot $i [${inventorySlotName(i)}]: ${itemText(it)}" }
        if (lines.isEmpty()) "Inventory is empty" else (lines + "Selected hotbar slot: ${inv.heldItemSlot}").joinToString("\n")
    }

    override suspend fun inspectItem(slot: Int): String = now { p, _ ->
        if (slot !in 0..40) return@now "Error: slot must be 0..40"
        val item = p.inventory.getItem(slot)
        if (item == null || item.type.isAir) return@now "Slot $slot is empty"
        buildString {
            appendLine("Slot $slot [${inventorySlotName(slot)}]: ${itemText(item)}")
            appendLine("Tooltip:")
            item.computeTooltipLines(TooltipContext.create(true, p.gameMode == GameMode.CREATIVE), p)
                .forEach { appendLine("  ${plain(it)}") }
            item.itemMeta?.asComponentString?.takeIf { it.length > 2 }?.let { appendLine("Components: ${it.take(1500)}") }
            bookText(item)?.let { append(it) }
        }.trimEnd()
    }

    override suspend fun getBlock(x: Int, y: Int, z: Int): String = now { p, _ ->
        val block = p.world.getBlockAt(x, y, z)
        buildString {
            append("$x $y $z: ${block.type.typeKey} ${block.blockData.asString}")
            when (val state = block.state) {
                is Sign -> {
                    append("\nFront text: ${state.getSide(Side.FRONT).lines().joinToString(" | ") { plain(it) }}")
                    append("\nBack text: ${state.getSide(Side.BACK).lines().joinToString(" | ") { plain(it) }}")
                    if (state.isWaxed) append("\nThe sign is waxed (cannot be edited)")
                }

                is Container -> append("\nIt has an inventory; open it with interact_block to see the contents")
                else -> {}
            }
        }
    }

    override suspend fun scanArea(radius: Int): String = now { p, _ ->
        val world = p.world
        val center = feet(p.location)
        val found = HashMap<Material, MutableList<Pair<Double, BlockPos3>>>()
        for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
            val pos = center.offset(x, y, z)
            if (pos.y < world.minHeight || pos.y >= world.maxHeight) continue
            val type = world.getBlockAt(pos.x, pos.y, pos.z).type
            if (type.isAir) continue
            found.getOrPut(type) { ArrayList() } += center.distanceTo(pos) to pos
        }
        if (found.isEmpty()) {
            "Only air within $radius blocks of ${posText(center)}"
        } else {
            "Blocks within $radius of ${posText(center)}:\n" + found.entries
                .sortedByDescending { it.value.size }
                .joinToString("\n") { (type, list) ->
                    val nearest = list.sortedBy { it.first }.take(5).joinToString(", ") { "(${posText(it.second)})" }
                    "${type.typeKey} x${list.size}: nearest $nearest"
                }
        }
    }

    override suspend fun findBlocks(type: String, radius: Int, limit: Int): String = now { p, _ ->
        val material = Material.matchMaterial(type)?.takeIf { it.isBlock } ?: return@now "Error: unknown block type $type"
        val world = p.world
        val eye = p.eyeLocation
        val center = feet(p.location)
        val hits = ArrayList<Pair<Double, BlockPos3>>()
        for (x in center.x - radius..center.x + radius) for (z in center.z - radius..center.z + radius) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            for (y in maxOf(center.y - radius, world.minHeight)..minOf(center.y + radius, world.maxHeight - 1)) {
                if (world.getBlockAt(x, y, z).type != material) continue
                val d = eye.distance(Location(world, x + 0.5, y + 0.5, z + 0.5))
                if (d <= radius + 1) hits += d to BlockPos3(x, y, z)
            }
        }
        if (hits.isEmpty()) {
            "No ${material.typeKey} within $radius blocks"
        } else {
            hits.sortedBy { it.first }.take(limit).joinToString("\n") { "(${posText(it.second)}) dist=%.1f".format(it.first) }
        }
    }

    override suspend fun nearbyEntities(radius: Int): String = now { p, _ ->
        val loc = p.location
        val entities = p.getNearbyEntities(radius.toDouble(), radius.toDouble(), radius.toDouble())
            .sortedBy { it.location.distanceSquared(loc) }
            .take(20)
        if (entities.isEmpty()) "No entities within $radius blocks" else entities.joinToString("\n") { entityLine(it, loc) }
    }

    override suspend fun lookingAt(): String = now { p, _ ->
        val eye = p.eyeLocation
        val range = maxOf(
            p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value ?: 4.5,
            p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value ?: 3.0,
        )
        val hit = p.world.rayTrace(eye, eye.direction, range, FluidCollisionMode.NEVER, false, 0.0) { it != p }
        val entity = hit?.hitEntity
        val block = hit?.hitBlock
        when {
            entity != null -> "Looking at entity ${entityLine(entity, p.location)}"
            block != null -> "Looking at ${block.type.typeKey} at ${blockText(block)}, ${hit.hitBlockFace?.name?.lowercase()} face, " +
                "distance %.1f".format(hit.hitPosition.distance(eye.toVector()))

            else -> "Looking at nothing within %.1f blocks (yaw %.0f pitch %.0f)".format(range, eye.yaw, eye.pitch)
        }
    }

    override suspend fun readMessages(): String = now { _, h ->
        val messages = listener(h).drainMessages()
        if (messages.isEmpty()) "No new messages" else messages.joinToString("\n")
    }

    override fun unreadMessages(): Int = (FakePlayers.handle(uuid)?.connection as? AgentPacketListener)?.unreadMessages ?: 0

    override suspend fun container(): String = now { p, h -> describeContainer(p, h) }

    override suspend fun recipes(item: String): String = now { p, _ ->
        val material = Material.matchMaterial(item) ?: return@now "Error: unknown item $item"
        describeRecipes(p, material)
    }

    // ---------------------------------------------------------------- movement

    override suspend fun walkTo(x: Int, y: Int, z: Int, within: Double, sprint: Boolean): String = perform { p, _ ->
        val loc = p.location
        val goal = BlockPos3(x, y, z)
        val horizontal = hypot(x + 0.5 - loc.x, z + 0.5 - loc.z)
        if (horizontal > 64) {
            return@perform Step.Done("Error: target is ${horizontal.toInt()} blocks away; walk_to supports at most 64 per call")
        }
        val path = PathFinder(BukkitBlockView(p.world)).find(feet(loc), goal, within)
            ?: return@perform Step.Done("Error: no path found to $x $y $z")
        if (path.isEmpty()) return@perform Step.Done("Already there")
        Step.Start { BodyAction.Walk(path, goal, within, sprint, 20 * (path.size + 10), it) }
    }

    override suspend fun move(forward: Int, strafe: Int, jump: Boolean, sprint: Boolean, ticks: Int): String = perform { _, _ ->
        Step.Start { BodyAction.Move(forward.coerceIn(-1, 1), strafe.coerceIn(-1, 1), jump, sprint, ticks, it) }
    }

    override suspend fun look(yaw: Float, pitch: Float): String = now { _, h ->
        rotate(h, yaw, pitch.coerceIn(-90f, 90f))
        "Looking at yaw %.0f pitch %.0f".format(h.yRot, h.xRot)
    }

    override suspend fun lookAt(x: Double, y: Double, z: Double): String = now { p, h ->
        face(h, p.eyeLocation, Location(p.world, x, y, z))
        "Looking at %.1f %.1f %.1f (yaw %.0f pitch %.0f)".format(x, y, z, h.yRot, h.xRot)
    }

    override suspend fun jump(): String = perform { _, _ -> Step.Start { BodyAction.Jump(it) } }

    override suspend fun setSneaking(sneaking: Boolean): String = now { _, _ ->
        controls.sneakLocked = sneaking
        if (sneaking) "Sneaking (held until you stop)" else "Stopped sneaking"
    }

    override suspend fun setFlying(flying: Boolean): String = perform { p, h ->
        if (!p.allowFlight) return@perform Step.Done("Error: you are not allowed to fly in this game mode")
        if (p.isFlying == flying) return@perform Step.Done(if (flying) "Already flying" else "Not flying")
        // Like double-tapping jump: flight only sticks once off the ground, landing ends it.
        if (flying && h.onGround()) return@perform Step.Start { BodyAction.TakeOff(it) }
        Controls.setFlying(listener(h), flying)
        Step.Done(flightText(p, flying))
    }

    private fun flightText(p: Player, flying: Boolean) = when {
        p.isFlying != flying -> "Error: toggling flight was denied"
        flying -> "Flying at ${positionText(p)}; rise with move jump=true, descend while sneaking"
        else -> "Stopped flying"
    }

    override suspend fun dismount(): String = perform { _, h ->
        if (!h.isPassenger) Step.Done("Error: you are not riding anything") else Step.Start { BodyAction.Dismount(it) }
    }

    override suspend fun leaveBed(): String = now { p, h ->
        if (!h.isSleeping) return@now "Error: you are not in a bed"
        listener(h).handlePlayerCommand(ServerboundPlayerCommandPacket(h, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING))
        if (h.isSleeping) "Error: leaving the bed was denied" else "Left the bed at ${positionText(p)}"
    }

    override suspend fun openVehicleInventory(): String = now { p, h ->
        if (h.vehicle !is HasCustomInventoryScreen) return@now "Error: you are not riding anything with an inventory"
        listener(h).handlePlayerCommand(ServerboundPlayerCommandPacket(h, ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY))
        if (h.containerMenu === h.inventoryMenu) "Error: the inventory did not open" else describeContainer(p, h)
    }

    // ---------------------------------------------------------------- world interaction

    override suspend fun breakBlock(x: Int, y: Int, z: Int): String = perform { p, h ->
        val block = p.world.getBlockAt(x, y, z)
        val type = block.type
        if (type.isAir) return@perform Step.Done("Error: nothing to break at $x $y $z")
        blockReachError(p, block)?.let { return@perform Step.Done(it) }
        val pos = BlockPos(x, y, z)
        val speed = h.level().getBlockState(pos).getDestroyProgress(h, h.level(), pos)
        if (speed <= 0f && !h.abilities.instabuild) return@perform Step.Done("Error: ${type.typeKey} cannot be broken")
        val ticks = if (speed > 0f) ceil(1.0 / speed).toInt() else 0
        if (ticks > MAX_BREAK_TICKS && !h.abilities.instabuild) {
            return@perform Step.Done("Error: would take ${ticks / 20}s with ${itemText(p.inventory.itemInMainHand)}; select a better tool")
        }
        Step.Start { BodyAction.Break(pos, facingSide(p, pos), type, it) }
    }

    override suspend fun placeBlock(x: Int, y: Int, z: Int, item: String, against: Face?, hand: Hand): String = now { p, h ->
        val material = Material.matchMaterial(item) ?: return@now "Error: unknown item $item"
        if (!material.isItem) return@now "Error: ${material.typeKey} is not an item"
        val nmsHand = hand.nms
        if (nmsHand == InteractionHand.MAIN_HAND) {
            select(p, h, material)?.let { return@now it }
        } else if (p.inventory.itemInOffHand.type != material) {
            return@now "Error: your offhand holds ${itemText(p.inventory.itemInOffHand)}, not ${material.typeKey}"
        }
        val block = p.world.getBlockAt(x, y, z)
        if (!block.isReplaceable) return@now "Error: $x $y $z is occupied by ${block.type.typeKey}"
        blockReachError(p, block)?.let { return@now it }
        if (p.boundingBox.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))) {
            return@now "Error: you are standing in that space"
        }
        val level = h.level()
        val pos = BlockPos(x, y, z)
        val support = if (against != null) {
            Direction.valueOf(against.name).takeIf { !level.getBlockState(pos.relative(it)).isAir }
                ?: return@now "Error: there is nothing to click on the ${against.name.lowercase()} side of $x $y $z"
        } else {
            PLACE_ORDER.firstOrNull { dir ->
                val state = level.getBlockState(pos.relative(dir))
                !state.isAir && !state.canBeReplaced()
            } ?: return@now "Error: no solid block next to $x $y $z to place against"
        }
        val hitPoint = Vec3.atCenterOf(pos).add(support.stepX * 0.5, support.stepY * 0.5, support.stepZ * 0.5)
        face(h, p.eyeLocation, Location(p.world, hitPoint.x, hitPoint.y, hitPoint.z))
        useItemOn(h, nmsHand, BlockHitResult(hitPoint, support.opposite, pos.relative(support), false))
        if (block.isReplaceable) {
            "Error: placement failed (blocked by an entity, protected area, or the item cannot be placed there)"
        } else {
            "Placed ${block.type.typeKey} at $x $y $z (${block.blockData.asString})" + afterInteraction(p, h)
        }
    }

    override suspend fun interactBlock(x: Int, y: Int, z: Int, face: Face?, hand: Hand): String = now { p, h ->
        val block = p.world.getBlockAt(x, y, z)
        blockReachError(p, block)?.let { return@now it }
        val pos = BlockPos(x, y, z)
        val side = face?.let { Direction.valueOf(it.name) } ?: facingSide(p, pos)
        val hitPoint = Vec3.atCenterOf(pos).add(side.stepX * 0.5, side.stepY * 0.5, side.stepZ * 0.5)
        face(h, p.eyeLocation, Location(p.world, hitPoint.x, hitPoint.y, hitPoint.z))
        val type = block.type
        val held = itemText(handItem(p, hand))
        useItemOn(h, hand.nms, BlockHitResult(hitPoint, side, pos, false))
        "Right-clicked ${type.typeKey} at $x $y $z (${side.name.lowercase()} face) holding $held; block is now ${block.blockData.asString}" +
            afterInteraction(p, h)
    }

    override suspend fun useItem(holdTicks: Int, hand: Hand): String = perform { p, h ->
        val nmsHand = hand.nms
        val used = handItem(p, hand).type
        if (used.isAir) return@perform Step.Done("Error: your ${hand.name.lowercase()} hand is empty")
        useItemPacket(h, nmsHand)
        if (h.isUsingItem) {
            Step.Start { BodyAction.Hold(if (holdTicks == 0) MAX_HOLD_TICKS else holdTicks, nmsHand, used, it) }
        } else {
            Step.Done(usedText(used, "used", p) + afterInteraction(p, h))
        }
    }

    override suspend fun attack(entityUuid: String): String = now { p, h ->
        val target = entity(p, entityUuid) ?: return@now "Error: entity not found"
        entityReachError(p, target)?.let { return@now it }
        face(h, p.eyeLocation, target.boundingBox.center.toLocation(p.world))
        val strength = p.attackCooldown
        val listener = listener(h)
        if (h.mainHandItem.has(DataComponents.PIERCING_WEAPON)) {
            playerAction(h, ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN)
        } else {
            listener.handleAttack(ServerboundAttackPacket(target.entityId))
            listener.handlePunch(ServerboundPunchPacket())
        }
        val type = target.type.key.asString()
        val health = (target as? LivingEntity)?.let { if (it.isDead) "; it died" else "; its health is now %.1f".format(it.health) } ?: ""
        "Attacked $type at %.0f%% strength$health".format(strength * 100)
    }

    override suspend fun interactEntity(entityUuid: String, hand: Hand): String = now { p, h ->
        val target = entity(p, entityUuid) ?: return@now "Error: entity not found"
        entityReachError(p, target)?.let { return@now it }
        val box = target.boundingBox
        face(h, p.eyeLocation, box.center.toLocation(p.world))
        val held = itemText(handItem(p, hand))
        val hit = Vec3(0.0, box.height / 2, 0.0)
        listener(h).handleInteract(ServerboundInteractPacket(target.entityId, hand.nms, hit, h.isShiftKeyDown))
        "Right-clicked ${target.type.key.asString()} \"${target.name}\" holding $held" + afterInteraction(p, h)
    }

    override suspend fun swingHand(): String = now { _, h ->
        listener(h).handlePunch(ServerboundPunchPacket())
        "Swung your main hand"
    }

    override suspend fun pickBlock(x: Int, y: Int, z: Int): String = now { p, h ->
        val block = p.world.getBlockAt(x, y, z)
        blockReachError(p, block)?.let { return@now it }
        listener(h).handlePickItemFromBlock(ServerboundPickItemFromBlockPacket(BlockPos(x, y, z), false))
        "Picked ${block.type.typeKey}; now holding ${itemText(p.inventory.itemInMainHand)} (hotbar ${p.inventory.heldItemSlot})"
    }

    override suspend fun pickEntity(entityUuid: String): String = now { p, h ->
        val target = entity(p, entityUuid) ?: return@now "Error: entity not found"
        listener(h).handlePickItemFromEntity(ServerboundPickItemFromEntityPacket(target.entityId, false))
        "Picked ${target.type.key.asString()}; now holding ${itemText(p.inventory.itemInMainHand)} (hotbar ${p.inventory.heldItemSlot})"
    }

    override suspend fun fish(maxSeconds: Int): String = perform { p, _ ->
        val hand = when (Material.FISHING_ROD) {
            p.inventory.itemInMainHand.type -> InteractionHand.MAIN_HAND
            p.inventory.itemInOffHand.type -> InteractionHand.OFF_HAND
            else -> return@perform Step.Done("Error: hold a fishing rod first (select_item minecraft:fishing_rod)")
        }
        Step.Start { BodyAction.Fish(maxSeconds * 20, hand, it) }
    }

    // ---------------------------------------------------------------- inventory and containers

    override suspend fun selectHotbar(slot: Int): String = now { p, h ->
        if (slot !in 0..8) return@now "Error: hotbar slot must be 0..8"
        listener(h).handleSetCarriedItem(ServerboundSetCarriedItemPacket(slot))
        if (p.inventory.heldItemSlot != slot) "Error: changing the hotbar slot was denied" else "Holding ${itemText(p.inventory.itemInMainHand)} (hotbar $slot)"
    }

    override suspend fun selectItem(item: String): String = now { p, h ->
        val material = Material.matchMaterial(item) ?: return@now "Error: unknown item $item"
        select(p, h, material) ?: "Holding ${itemText(p.inventory.itemInMainHand)} (hotbar ${p.inventory.heldItemSlot})"
    }

    override suspend fun swapHands(): String = now { p, h ->
        playerAction(h, ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN)
        "Main hand: ${itemText(p.inventory.itemInMainHand)}, offhand: ${itemText(p.inventory.itemInOffHand)}"
    }

    override suspend fun dropItem(wholeStack: Boolean): String = now { p, h ->
        val held = p.inventory.itemInMainHand.clone()
        if (held.type.isAir) return@now "Error: your main hand is empty"
        val action = if (wholeStack) ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS else ServerboundPlayerActionPacket.Action.DROP_ITEM
        playerAction(h, action, BlockPos.ZERO, Direction.DOWN)
        "Dropped from ${itemText(held)}; now holding ${itemText(p.inventory.itemInMainHand)}"
    }

    override suspend fun clickSlot(slot: Int, button: Int, mode: ClickMode): String = now { p, h ->
        val menu = h.containerMenu
        if (!menu.isValidSlotIndex(slot) || slot < -999 || (slot < 0 && slot != -999 && slot != -1)) {
            return@now "Error: slot $slot does not exist (0..${menu.slots.size - 1}, or -999 for outside)"
        }
        click(h, slot, button, ContainerInput.valueOf(mode.name))
        buildString {
            append("Clicked slot $slot (${mode.name.lowercase()}, button $button). Cursor: ${itemText(p.openInventory.cursor)}")
            if (h.containerMenu !== menu) {
                append(". The container closed")
            } else if (slot >= 0) {
                append(". Slot $slot now: ${itemText(p.openInventory.getItem(slot))}")
            }
        }
    }

    override suspend fun closeContainer(): String = now { _, h ->
        val menu = h.containerMenu
        listener(h).handleContainerClose(ServerboundContainerClosePacket(menu.containerId))
        if (menu === h.inventoryMenu) "Closed your inventory (crafting grid and cursor items returned)" else "Closed the container"
    }

    override suspend fun craft(item: String, times: Int): String = now { p, h ->
        val material = Material.matchMaterial(item) ?: return@now "Error: unknown item $item"
        val menu = h.containerMenu
        if (menu !is CraftingMenu && menu !is InventoryMenu) {
            return@now "Error: open a crafting table (interact_block) or close the current container to use your 2x2 grid"
        }
        val keys = Bukkit.getRecipesFor(ItemStack(material)).filterIsInstance<CraftingRecipe>().map { (it as Keyed).key }
        if (keys.isEmpty()) return@now "Error: no crafting recipe makes ${material.typeKey}"
        val known = keys.filter { p.hasDiscoveredRecipe(it) }
        if (known.isEmpty()) {
            return@now "Error: no recipe for ${material.typeKey} is in your recipe book; place the ingredients yourself with click_slot (see get_recipes)"
        }
        val displays = known.flatMap { key ->
            val ids = ArrayList<RecipeDisplayId>()
            h.level().server.recipeManager.listDisplaysForRecipe(CraftNamespacedKey.toResourceKey(Registries.RECIPE, key)) { ids += it.id() }
            ids
        }
        val listener = listener(h)
        fun held() = p.inventory.all(material).values.sumOf { it.amount }
        val before = held()
        var crafted = 0
        var problem: String? = null
        while (crafted < times && problem == null) {
            val placed = displays.any { id ->
                listener.handlePlaceRecipe(ServerboundPlaceRecipePacket(menu.containerId, id, false))
                menu.getSlot(0).item.let { !it.isEmpty && CraftItemStack.asBukkitCopy(it).type == material }
            }
            if (!placed) {
                problem = if (menu is InventoryMenu) "missing ingredients, or the recipe needs a crafting table" else "missing ingredients"
                break
            }
            val count = held()
            click(h, 0, 0, ContainerInput.QUICK_MOVE)
            if (held() <= count) problem = "taking the result failed (inventory full?)" else crafted++
        }
        val gained = held() - before
        val leftovers = (1 until if (menu is InventoryMenu) 5 else 10).mapNotNull { i ->
            menu.getSlot(i).item.takeIf { !it.isEmpty }?.let { itemText(CraftItemStack.asBukkitCopy(it)) }
        }
        buildString {
            append(if (crafted == 0) "Error: could not craft ${material.typeKey}: $problem" else "Crafted ${material.typeKey} $crafted time(s), gained $gained")
            if (crafted > 0 && problem != null) append("; stopped: $problem")
            if (leftovers.isNotEmpty()) append(". Still in the crafting grid: ${leftovers.joinToString(", ")}")
            if (crafted == 0) append(". Use get_recipes ${material.typeKey} to see what it needs")
        }
    }

    override suspend fun containerButton(id: Int): String = now { p, h ->
        if (h.containerMenu === h.inventoryMenu) return@now "Error: no container is open"
        listener(h).handleContainerButtonClick(ServerboundContainerButtonClickPacket(h.containerMenu.containerId, id))
        "Pressed button $id.\n" + describeContainer(p, h)
    }

    override suspend fun selectTrade(index: Int): String = now { p, h ->
        val menu = h.containerMenu as? MerchantMenu ?: return@now "Error: no trading screen is open"
        if (index !in 0 until menu.offers.size) return@now "Error: trade index must be 0..${menu.offers.size - 1}"
        listener(h).handleSelectTrade(ServerboundSelectTradePacket(index))
        "Selected trade $index; payment slots: ${itemText(p.openInventory.getItem(0))}, ${itemText(p.openInventory.getItem(1))}; " +
            "result slot 2: ${itemText(p.openInventory.getItem(2))} (take it with click_slot 2, mode quick_move)"
    }

    override suspend fun renameItem(name: String): String = now { p, h ->
        if (h.containerMenu !is AnvilMenu) return@now "Error: no anvil is open"
        listener(h).handleRenameItem(ServerboundRenameItemPacket(name))
        val cost = (p.openInventory as? AnvilView)?.repairCost ?: 0
        "Rename text set; result slot 2: ${itemText(p.openInventory.getItem(2))}, costs $cost levels (take it with click_slot 2)"
    }

    override suspend fun setBeacon(primary: String, secondary: String?): String = now { p, h ->
        if (h.containerMenu !is BeaconMenu) return@now "Error: no beacon is open"
        fun effect(name: String) = NamespacedKey.fromString(name.lowercase())?.let { CraftNamespacedKey.toMinecraft(it) }
            ?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) }
        val first = effect(primary) ?: return@now "Error: unknown effect $primary"
        val second = secondary?.let { effect(it) ?: return@now "Error: unknown effect $it" }
        listener(h).handleSetBeaconPacket(ServerboundSetBeaconPacket(Optional.of(first), Optional.ofNullable(second)))
        val view = p.openInventory as? BeaconView ?: return@now "The beacon closed"
        "Beacon effects: primary ${view.primaryEffect?.key?.key ?: "none"}, secondary ${view.secondaryEffect?.key?.key ?: "none"}" +
            "; payment slot: ${itemText(view.getItem(0))}"
    }

    override suspend fun toggleCrafterSlot(slot: Int): String = now { p, h ->
        val menu = h.containerMenu as? CrafterMenu ?: return@now "Error: no crafter is open"
        if (slot !in 0..8) return@now "Error: crafter slot must be 0..8"
        listener(h).handleContainerSlotStateChanged(ServerboundContainerSlotStateChangedPacket(slot, menu.containerId, menu.isSlotDisabled(slot)))
        "Crafter slot $slot is now ${if (menu.isSlotDisabled(slot)) "disabled" else "enabled"}"
    }

    override suspend fun selectBundleItem(slot: Int, index: Int): String = now { p, h ->
        if (!h.containerMenu.isValidSlotIndex(slot) || slot < 0) return@now "Error: slot $slot does not exist"
        listener(h).handleBundleItemSelectedPacket(ServerboundSelectBundleItemPacket(slot, index))
        "Selected item $index of the bundle in slot $slot: ${itemText(p.openInventory.getItem(slot))}"
    }

    override suspend fun creativeSetSlot(slot: Int, item: String?, count: Int): String = now { p, h ->
        if (!h.hasInfiniteMaterials()) return@now "Error: only possible in creative mode"
        val menuSlot = inventoryMenuSlot(slot) ?: return@now "Error: slot must be 0..40"
        val stack = if (item == null) {
            ItemStack.empty()
        } else {
            val material = Material.matchMaterial(item)?.takeIf { it.isItem } ?: return@now "Error: unknown item $item"
            ItemStack(material, count.coerceIn(1, material.maxStackSize))
        }
        listener(h).handleSetCreativeModeSlot(ServerboundSetCreativeModeSlotPacket(menuSlot, CraftItemStack.asNMSCopy(stack)))
        "Slot $slot [${inventorySlotName(slot)}]: ${itemText(p.inventory.getItem(slot))}"
    }

    // ---------------------------------------------------------------- text

    override suspend fun chat(message: String): String = perform { p, h ->
        val text = message.take(256)
        if (text.startsWith("/")) return@perform command(h, text.substring(1))
        p.chat(text)
        Step.Done("Said: $text")
    }

    override suspend fun runCommand(command: String): String = perform { _, h -> command(h, command.removePrefix("/")) }

    /** Runs [command] like a client's command packet, then reports what the server answered. */
    @Suppress("DEPRECATION")
    private fun command(h: ServerPlayer, command: String): Step {
        val listener = listener(h)
        val earlier = listener.drainMessages()
        listener.handleCommand(command)
        return later(2) { _, _ ->
            val output = listener.drainMessages()
            buildString {
                if (earlier.isNotEmpty()) append("Messages received before the command:\n${earlier.joinToString("\n")}\n")
                append("Ran /$command")
                append(if (output.isEmpty()) " (no output)" else ":\n" + output.joinToString("\n"))
            }
        }
    }

    override suspend fun editSign(x: Int, y: Int, z: Int, lines: List<String>): String = perform { p, h ->
        val block = p.world.getBlockAt(x, y, z)
        if (block.state !is Sign) return@perform Step.Done("Error: no sign at $x $y $z")
        val pos = BlockPos(x, y, z)
        val listener = listener(h)
        if (listener.signEditor?.pos != pos) {
            blockReachError(p, block)?.let { return@perform Step.Done(it) }
            val side = facingSide(p, pos)
            val hitPoint = Vec3.atCenterOf(pos).add(side.stepX * 0.5, side.stepY * 0.5, side.stepZ * 0.5)
            face(h, p.eyeLocation, Location(p.world, hitPoint.x, hitPoint.y, hitPoint.z))
            useItemOn(h, InteractionHand.MAIN_HAND, BlockHitResult(hitPoint, side, pos, false))
        }
        val editor = listener.signEditor?.takeIf { it.pos == pos }
            ?: return@perform Step.Done("Error: the sign did not open for editing (waxed, edited by someone else, or your held item was used on it)")
        listener.signEditor = null
        val text = (lines.take(4) + List(4) { "" }).take(4)
        listener.handleSignUpdate(ServerboundSignUpdatePacket(pos, text, if (editor.front) SignTextSlot.FRONT else SignTextSlot.BACK))
        later(2) { player, _ ->
            val sign = player.world.getBlockAt(x, y, z).state as? Sign ?: return@later "Error: the sign is gone"
            val side = sign.getSide(if (editor.front) Side.FRONT else Side.BACK)
            "Sign ${if (editor.front) "front" else "back"} at $x $y $z now reads: ${side.lines().joinToString(" | ") { plain(it) }}"
        }
    }

    override suspend fun writeBook(pages: List<String>, title: String?): String = perform { p, h ->
        val inv = p.inventory
        val slot = when (Material.WRITABLE_BOOK) {
            inv.itemInMainHand.type -> inv.heldItemSlot
            inv.itemInOffHand.type -> 40
            else -> return@perform Step.Done("Error: hold a book and quill first (select_item minecraft:writable_book)")
        }
        listener(h).handleEditBook(ServerboundEditBookPacket(slot, pages.take(100), Optional.ofNullable(title)))
        later(2) { player, _ ->
            val book = player.inventory.getItem(slot)
            "Book in slot $slot is now ${itemText(book)}\n" + (bookText(book) ?: "")
        }
    }

    // ---------------------------------------------------------------- misc

    override suspend fun spectate(entityUuid: String): String = now { p, h ->
        if (p.gameMode != GameMode.SPECTATOR) return@now "Error: only possible in spectator mode"
        val id = runCatching { UUID.fromString(entityUuid) }.getOrNull() ?: return@now "Error: invalid uuid"
        listener(h).handleTeleportToEntityPacket(ServerboundTeleportToEntityPacket(id))
        "Now at ${positionText(p)}"
    }

    override suspend fun wait(seconds: Int, until: WaitUntil?): String = perform { p, _ ->
        Step.Start { BodyAction.Wait(seconds * 20, until, p.health, it) }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun halt() {
        action?.cancelled?.set(true)
    }

    override fun location(): SpawnPoint? {
        val loc = FakePlayers.handle(uuid)?.bukkitEntity?.location ?: return null
        return SpawnPoint(loc.world.key.asString(), loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
    }

    override fun despawn() {
        failAction("Cancelled: body despawned")
        onDespawn(uuid)
        FakePlayers.remove(uuid)
    }

    // ---------------------------------------------------------------- packets

    private fun listener(h: ServerPlayer) = h.connection as AgentPacketListener

    private fun playerAction(h: ServerPlayer, action: ServerboundPlayerActionPacket.Action, pos: BlockPos, face: Direction) {
        listener(h).handlePlayerAction(ServerboundPlayerActionPacket(action, pos, face, ++sequence))
    }

    private fun useItemOn(h: ServerPlayer, hand: InteractionHand, hit: BlockHitResult) {
        listener(h).handleUseItemOn(ServerboundUseItemOnPacket(hand, hit, ++sequence))
    }

    private fun useItemPacket(h: ServerPlayer, hand: InteractionHand) {
        listener(h).handleUseItem(ServerboundUseItemPacket(hand, ++sequence, h.yRot, h.xRot))
    }

    private fun releaseUse(h: ServerPlayer) =
        playerAction(h, ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN)

    /** Clicks [slot] of the open container like the client's screen does. */
    private fun click(h: ServerPlayer, slot: Int, button: Int, input: ContainerInput) {
        val menu = h.containerMenu
        listener(h).handleContainerClick(
            ServerboundContainerClickPacket(
                menu.containerId, menu.stateId, slot.toShort(), button.toByte(), input, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY,
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Puts [material] in the main hand the way a player does: pick its hotbar slot, or hover it in the open
     * screen and press the number key of the selected hotbar slot. Returns an error or null.
     */
    private fun select(p: Player, h: ServerPlayer, material: Material): String? {
        val inv = p.inventory
        if (inv.itemInMainHand.type == material) return null
        (0..8).firstOrNull { inv.getItem(it)?.type == material }?.let { slot ->
            listener(h).handleSetCarriedItem(ServerboundSetCarriedItemPacket(slot))
            return if (inv.heldItemSlot == slot) null else "Error: changing the hotbar slot was denied"
        }
        val source = (9..35).firstOrNull { inv.getItem(it)?.type == material }
            ?: return if (inv.itemInOffHand.type == material) {
                "Error: ${material.typeKey} is in your offhand; use swap_hands or hand=off"
            } else {
                "Error: no ${material.typeKey} in inventory"
            }
        val raw = rawSlotOf(p, h, source) ?: return "Error: cannot reach inventory slot $source from the open container"
        click(h, raw, inv.heldItemSlot, ContainerInput.SWAP)
        return if (inv.itemInMainHand.type == material) null else "Error: moving ${material.typeKey} to the hotbar was denied"
    }

    /** Notes about what an interaction opened. */
    private fun afterInteraction(p: Player, h: ServerPlayer): String = buildString {
        if (h.containerMenu !== h.inventoryMenu) append(". Opened ${p.openInventory.topInventory.type.name.lowercase()} (get_container)")
        listener(h).signEditor?.let { append(". A sign editor opened at ${it.pos.x} ${it.pos.y} ${it.pos.z} (edit_sign)") }
        if (h.isSleeping) append(". You are sleeping (leave_bed)")
        p.vehicle?.let { append(". You are riding ${it.type.key.asString()}") }
    }

    private fun entity(p: Player, uuid: String): Entity? {
        val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
        return Bukkit.getEntity(id)?.takeIf { it.world == p.world && it != p }
    }

    private fun counts(p: Player): Map<Material, Int> =
        p.inventory.contents.filterNotNull().groupBy { it.type }.mapValues { (_, items) -> items.sumOf { it.amount } }

    private fun blockReachError(p: Player, block: Block): String? {
        val reach = (p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value ?: 4.5) + 0.5
        val distance = p.eyeLocation.distance(block.location.add(0.5, 0.5, 0.5))
        return if (distance > reach) reachText(distance, reach) else null
    }

    private fun entityReachError(p: Player, target: Entity): String? {
        val reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value ?: 3.0
        val eye = p.eyeLocation
        val box = target.boundingBox
        val dx = eye.x - eye.x.coerceIn(box.minX, box.maxX)
        val dy = eye.y - eye.y.coerceIn(box.minY, box.maxY)
        val dz = eye.z - eye.z.coerceIn(box.minZ, box.maxZ)
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        return if (distance > reach) reachText(distance, reach) else null
    }

    private fun reachText(distance: Double, reach: Double) =
        "Error: target is %.1f blocks away (reach %.1f); walk closer first".format(distance, reach)

    private fun usedText(used: Material, result: String, p: Player) =
        "Used ${used.typeKey}: $result; now holding ${itemText(p.inventory.itemInMainHand)}"

    private fun positionText(p: Player) = p.location.let { "%.1f %.1f %.1f".format(it.x, it.y, it.z) }

    private val Hand.nms get() = if (this == Hand.MAIN) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND

    private fun handItem(p: Player, hand: Hand): ItemStack =
        if (hand == Hand.MAIN) p.inventory.itemInMainHand else p.inventory.itemInOffHand

    private companion object {
        val PLACE_ORDER = listOf(Direction.DOWN) + Direction.entries.filter { it != Direction.DOWN }

        /** 30 s; beyond that the agent should pick a better tool. */
        const val MAX_BREAK_TICKS = 600
        const val MAX_HOLD_TICKS = 100

        /** Whether a fish is pulling the bobber; vanilla only exposes it to the client renderer. */
        val BITING: Field = FishingHook::class.java.getDeclaredField("biting").apply { isAccessible = true }

        /** The side of the block at [pos] that faces the eyes of [p]. */
        fun facingSide(p: Player, pos: BlockPos): Direction {
            val eye = p.eyeLocation
            val center = Vec3.atCenterOf(pos)
            return Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z)
        }

        fun rotate(h: ServerPlayer, yaw: Float, pitch: Float) {
            h.setYRot(yaw)
            h.setXRot(pitch)
            h.setYHeadRot(yaw)
            h.setYBodyRot(yaw)
        }

        /** Turns head and body of [h] from [eye] toward [target]. */
        fun face(h: ServerPlayer, eye: Location, target: Location) {
            val dx = target.x - eye.x
            val dy = target.y - eye.y
            val dz = target.z - eye.z
            val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val pitch = Math.toDegrees(-atan2(dy, hypot(dx, dz))).toFloat()
            rotate(h, yaw, pitch)
        }
    }
}
