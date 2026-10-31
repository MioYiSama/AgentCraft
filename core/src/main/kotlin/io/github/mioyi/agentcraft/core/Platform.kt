package io.github.mioyi.agentcraft.core

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.UUID

/** A position in a world; [world] is the namespaced world key, e.g. `minecraft:overworld`. */
@Serializable
data class SpawnPoint(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

/** [spawn] == null keeps the position loaded from the player's saved data. */
data class BodySpec(val uuid: UUID, val name: String, val spawn: SpawnPoint?)

/** Platform-neutral services the agent core needs from the server implementation. */
interface Platform {
    val dataDir: Path

    /** Console info. */
    fun log(message: String)

    /** Console warning. */
    fun warn(message: String)

    /** Online operators + console. */
    fun notifyOperators(message: String)

    /** Schedules [task] on the server thread; never runs inline. */
    fun runOnMain(task: () -> Unit)

    suspend fun isNameAvailable(name: String): Boolean

    /** Spawns a fake player; throws on failure. */
    suspend fun spawnBody(spec: BodySpec): AgentBody

    /** Deletes the saved player data of [uuid]. Main thread only. */
    fun deleteBodyData(uuid: UUID)
}

enum class Hand { MAIN, OFF }

enum class Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

/** Container click kinds, as sent by the client (vanilla `ContainerInput`). */
enum class ClickMode { PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL }

/** Events that end a wait early. */
enum class WaitUntil { MESSAGE, HURT }

/**
 * The in-world player an agent controls. Actions go through the same server handlers as a real client's
 * packets, so they obey the same rules (reach, cooldowns, protection plugins) and fire the same events.
 *
 * Suspend methods perform their own world access on the server thread and never throw (except
 * [kotlinx.coroutines.CancellationException]); failures are returned as strings starting with `Error: `.
 * Inventory slots are player inventory indices: 0-8 hotbar, 9-35 main, 36-39 boots/leggings/chestplate/
 * helmet, 40 offhand. Container slots are the raw slot numbers of the open container.
 */
interface AgentBody {
    val uuid: UUID
    val name: String

    // observation
    suspend fun status(): String
    suspend fun inventory(): String
    suspend fun inspectItem(slot: Int): String
    suspend fun getBlock(x: Int, y: Int, z: Int): String
    suspend fun scanArea(radius: Int): String
    suspend fun findBlocks(type: String, radius: Int, limit: Int): String
    suspend fun nearbyEntities(radius: Int): String
    suspend fun lookingAt(): String
    suspend fun readMessages(): String
    suspend fun container(): String
    suspend fun recipes(item: String): String

    /** Thread-safe: number of received chat/system messages not yet returned by [readMessages]. */
    fun unreadMessages(): Int

    // movement
    suspend fun walkTo(x: Int, y: Int, z: Int, within: Double, sprint: Boolean): String

    /** Holds movement keys for [ticks]: [forward] and [strafe] are -1, 0 or 1 (strafe 1 = left). */
    suspend fun move(forward: Int, strafe: Int, jump: Boolean, sprint: Boolean, ticks: Int): String
    suspend fun look(yaw: Float, pitch: Float): String
    suspend fun lookAt(x: Double, y: Double, z: Double): String
    suspend fun jump(): String
    suspend fun setSneaking(sneaking: Boolean): String
    suspend fun setFlying(flying: Boolean): String
    suspend fun dismount(): String
    suspend fun leaveBed(): String
    suspend fun openVehicleInventory(): String

    // world interaction
    suspend fun breakBlock(x: Int, y: Int, z: Int): String

    /** [against]: the side of the target space whose neighbour is clicked; null picks a solid one. */
    suspend fun placeBlock(x: Int, y: Int, z: Int, item: String, against: Face?, hand: Hand): String

    /** [face]: the clicked side of the block; null picks the side facing the eyes. */
    suspend fun interactBlock(x: Int, y: Int, z: Int, face: Face?, hand: Hand): String
    suspend fun useItem(holdTicks: Int, hand: Hand): String
    suspend fun attack(entityUuid: String): String
    suspend fun interactEntity(entityUuid: String, hand: Hand): String
    suspend fun swingHand(): String
    suspend fun pickBlock(x: Int, y: Int, z: Int): String
    suspend fun pickEntity(entityUuid: String): String
    suspend fun fish(maxSeconds: Int): String

    // inventory and containers
    suspend fun selectHotbar(slot: Int): String
    suspend fun selectItem(item: String): String
    suspend fun swapHands(): String
    suspend fun dropItem(wholeStack: Boolean): String
    suspend fun clickSlot(slot: Int, button: Int, mode: ClickMode): String
    suspend fun closeContainer(): String
    suspend fun craft(item: String, times: Int): String
    suspend fun containerButton(id: Int): String
    suspend fun selectTrade(index: Int): String
    suspend fun renameItem(name: String): String
    suspend fun setBeacon(primary: String, secondary: String?): String
    suspend fun toggleCrafterSlot(slot: Int): String
    suspend fun selectBundleItem(slot: Int, index: Int): String

    /** Creative mode only; [item] null clears the slot. */
    suspend fun creativeSetSlot(slot: Int, item: String?, count: Int): String

    // text
    suspend fun chat(message: String): String
    suspend fun runCommand(command: String): String

    /** Edits the side of the sign facing the player, opening its editor first if needed. */
    suspend fun editSign(x: Int, y: Int, z: Int, lines: List<String>): String
    suspend fun writeBook(pages: List<String>, title: String?): String

    // misc
    suspend fun spectate(entityUuid: String): String
    suspend fun wait(seconds: Int, until: WaitUntil?): String

    /** Thread-safe: cancels the current action; inputs are zeroed on the next tick. */
    fun halt()

    /** Main thread only; null if the body is offline. */
    fun location(): SpawnPoint?

    /** Main thread only; removes the player (saving its player data). */
    fun despawn()
}
