package io.github.mioyi.agentcraft.core.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import io.github.mioyi.agentcraft.core.AgentBody
import io.github.mioyi.agentcraft.core.AgentLog
import io.github.mioyi.agentcraft.core.ClickMode
import io.github.mioyi.agentcraft.core.Face
import io.github.mioyi.agentcraft.core.Hand
import io.github.mioyi.agentcraft.core.LogKind
import io.github.mioyi.agentcraft.core.WaitUntil
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Logs every call and result, maps failures to `Error: ` strings and bounds the result size. */
abstract class AgentTool<A>(
    argsType: TypeToken,
    name: String,
    description: String,
    protected val log: AgentLog,
) : SimpleTool<A>(argsType, name, description) {

    protected abstract suspend fun invoke(args: A): String

    final override suspend fun execute(args: A): String {
        log.add(LogKind.TOOL_CALL, "$name $args")
        val result = try {
            invoke(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        }.take(MAX_RESULT)
        log.add(LogKind.TOOL_RESULT, result)
        return result
    }

    private companion object {
        const val MAX_RESULT = 6000
    }
}

@Serializable
class NoArgs {
    override fun toString() = "{}"
}

@Serializable
data class BlockArgs(
    @property:LLMDescription("Block X coordinate") val x: Int,
    @property:LLMDescription("Block Y coordinate (up)") val y: Int,
    @property:LLMDescription("Block Z coordinate") val z: Int,
)

@Serializable
data class SlotArgs(
    @property:LLMDescription("Inventory slot: 0-8 hotbar, 9-35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand")
    val slot: Int,
)

@Serializable
data class ScanArgs(
    @property:LLMDescription("Cube radius around your feet, 1..5 (default 3)") val radius: Int = 3,
)

@Serializable
data class FindBlocksArgs(
    @property:LLMDescription("Block type, e.g. minecraft:oak_log or oak_log") val type: String,
    @property:LLMDescription("Search radius in blocks, 1..24 (default 16)") val radius: Int = 16,
    @property:LLMDescription("Maximum number of results, 1..20 (default 10)") val limit: Int = 10,
)

@Serializable
data class EntitiesArgs(
    @property:LLMDescription("Search radius in blocks, 1..32 (default 16)") val radius: Int = 16,
)

@Serializable
data class WalkArgs(
    @property:LLMDescription("Target block X") val x: Int,
    @property:LLMDescription("Target block Y (feet level)") val y: Int,
    @property:LLMDescription("Target block Z") val z: Int,
    @property:LLMDescription("Stop when within this many blocks of the target, 0..5 (default 1)") val within: Double = 1.0,
    @property:LLMDescription("Sprint while walking (default false; needs food above 6)") val sprint: Boolean = false,
)

@Serializable
data class MoveArgs(
    @property:LLMDescription("1 = hold forward (W), -1 = hold back (S), 0 = neither (default 0)") val forward: Int = 0,
    @property:LLMDescription("1 = hold left (A), -1 = hold right (D), 0 = neither (default 0)") val strafe: Int = 0,
    @property:LLMDescription("Hold jump: jumps, swims up, climbs, rises while flying, charges a horse jump") val jump: Boolean = false,
    @property:LLMDescription("Hold sprint (needs forward)") val sprint: Boolean = false,
    @property:LLMDescription("How long to hold the keys, in ticks (20 per second), 1..200 (default 20)") val ticks: Int = 20,
)

@Serializable
data class LookArgs(
    @property:LLMDescription("Yaw in degrees: 0 = south (+z), 90 = west (-x), 180 = north (-z), -90 = east (+x)") val yaw: Double,
    @property:LLMDescription("Pitch in degrees: -90 = straight up, 0 = level, 90 = straight down") val pitch: Double,
)

@Serializable
data class PointArgs(
    @property:LLMDescription("X coordinate") val x: Double,
    @property:LLMDescription("Y coordinate (up)") val y: Double,
    @property:LLMDescription("Z coordinate") val z: Double,
)

@Serializable
data class ToggleArgs(
    @property:LLMDescription("true to turn on, false to turn off") val enabled: Boolean,
)

@Serializable
data class PlaceArgs(
    @property:LLMDescription("Block X coordinate of the empty space to fill") val x: Int,
    @property:LLMDescription("Block Y coordinate of the empty space to fill") val y: Int,
    @property:LLMDescription("Block Z coordinate of the empty space to fill") val z: Int,
    @property:LLMDescription("Item to place, e.g. minecraft:cobblestone, minecraft:torch, minecraft:water_bucket") val item: String,
    @property:LLMDescription(
        "Optional: which side of the space has the block you click on (down, up, north, south, west, east). " +
            "Matters for slabs (up = top half), torches on walls, logs' axis. Default: a solid neighbour, below first",
    )
    val against: String? = null,
    @property:LLMDescription("main (default) or off: the hand holding the item") val hand: String = "main",
)

@Serializable
data class InteractBlockArgs(
    @property:LLMDescription("Block X coordinate") val x: Int,
    @property:LLMDescription("Block Y coordinate (up)") val y: Int,
    @property:LLMDescription("Block Z coordinate") val z: Int,
    @property:LLMDescription("Optional clicked side: down, up, north, south, west, east (default: the side facing you)")
    val face: String? = null,
    @property:LLMDescription("main (default) or off: the hand used") val hand: String = "main",
)

@Serializable
data class UseItemArgs(
    @property:LLMDescription("Ticks to hold right click, 0..100; 0 = until the use finishes (eating, drinking)")
    val holdTicks: Int = 0,
    @property:LLMDescription("main (default) or off: the hand used, e.g. off for a shield") val hand: String = "main",
)

@Serializable
data class EntityArgs(
    @property:LLMDescription("UUID of the entity, from get_nearby_entities") val entityUuid: String,
)

@Serializable
data class InteractEntityArgs(
    @property:LLMDescription("UUID of the entity, from get_nearby_entities") val entityUuid: String,
    @property:LLMDescription("main (default) or off: the hand used") val hand: String = "main",
)

@Serializable
data class FishArgs(
    @property:LLMDescription("Give up after this many seconds without a bite, 5..120 (default 45)") val maxSeconds: Int = 45,
)

@Serializable
data class HotbarArgs(
    @property:LLMDescription("Hotbar slot 0..8") val slot: Int,
)

@Serializable
data class ItemArgs(
    @property:LLMDescription("Item type, e.g. minecraft:stone_pickaxe or stone_pickaxe") val item: String,
)

@Serializable
data class DropArgs(
    @property:LLMDescription("true drops the whole held stack (Ctrl+Q), false drops one item (Q)") val wholeStack: Boolean = false,
)

@Serializable
data class ClickArgs(
    @property:LLMDescription("Raw slot number from get_container; -999 = outside the window (drops the cursor item)") val slot: Int,
    @property:LLMDescription(
        "pickup: button 0 = left click (take/put/swap stack), 1 = right click (take half/put one). " +
            "quick_move: shift-click (button 0). swap: button 0-8 = number key of a hotbar slot, 40 = offhand key. " +
            "throw: button 0 = drop one, 1 = drop stack. pickup_all: double click (button 0). clone: creative middle click. " +
            "quick_craft: drag; button 0/4/8 start (slot -999), 1/5/9 add a slot, 2/6/10 end (slot -999) for left/right/middle",
    )
    val mode: String = "pickup",
    @property:LLMDescription("Mouse button or key, see mode (default 0)") val button: Int = 0,
)

@Serializable
data class CraftArgs(
    @property:LLMDescription("Item to craft, e.g. minecraft:oak_planks") val item: String,
    @property:LLMDescription("How many times to craft the recipe, 1..64 (default 1)") val times: Int = 1,
)

@Serializable
data class ButtonArgs(
    @property:LLMDescription("Button id, see get_container for the open screen") val id: Int,
)

@Serializable
data class TradeArgs(
    @property:LLMDescription("Trade index from get_container") val index: Int,
)

@Serializable
data class RenameArgs(
    @property:LLMDescription("New item name (max 50 characters; empty restores the default name)") val name: String,
)

@Serializable
data class BeaconArgs(
    @property:LLMDescription("Primary effect, e.g. minecraft:speed, haste, resistance, jump_boost, strength") val primary: String,
    @property:LLMDescription("Optional secondary effect (tier 4 pyramid only): regeneration or the primary again") val secondary: String? = null,
)

@Serializable
data class CrafterSlotArgs(
    @property:LLMDescription("Crafter grid slot 0..8") val slot: Int,
)

@Serializable
data class BundleArgs(
    @property:LLMDescription("Raw container slot holding the bundle") val slot: Int,
    @property:LLMDescription("Index of the item inside the bundle to take out next") val index: Int,
)

@Serializable
data class CreativeArgs(
    @property:LLMDescription("Inventory slot 0..40 (same numbering as get_inventory)") val slot: Int,
    @property:LLMDescription("Item to put there, e.g. minecraft:diamond_block; omit to clear the slot") val item: String? = null,
    @property:LLMDescription("Stack size (default 1)") val count: Int = 1,
)

@Serializable
data class ChatArgs(
    @property:LLMDescription("Message to say (max 256 characters)") val message: String,
)

@Serializable
data class CommandArgs(
    @property:LLMDescription("Command without or with the leading slash, e.g. msg Steve hi") val command: String,
)

@Serializable
data class SignArgs(
    @property:LLMDescription("Sign X coordinate") val x: Int,
    @property:LLMDescription("Sign Y coordinate") val y: Int,
    @property:LLMDescription("Sign Z coordinate") val z: Int,
    @property:LLMDescription("Up to 4 lines of text") val lines: List<String>,
)

@Serializable
data class BookArgs(
    @property:LLMDescription("Page texts, one string per page (max 100 pages)") val pages: List<String>,
    @property:LLMDescription("Optional title: signs the book, making it a written book that can no longer be edited")
    val title: String? = null,
)

@Serializable
data class WaitArgs(
    @property:LLMDescription("Seconds to wait, 1..60 (default 1)") val seconds: Int = 1,
    @property:LLMDescription("Optional: stop early when this happens: message (a chat/system message arrives) or hurt (you take damage)")
    val until: String? = null,
)

private fun hand(value: String): Hand = when (value.trim().lowercase()) {
    "main", "main_hand", "mainhand", "" -> Hand.MAIN
    "off", "off_hand", "offhand" -> Hand.OFF
    else -> throw IllegalArgumentException("hand must be main or off")
}

private fun face(value: String?): Face? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
    Face.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
        ?: throw IllegalArgumentException("face must be one of down, up, north, south, west, east")
}

private fun clickMode(value: String): ClickMode =
    ClickMode.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("mode must be one of ${ClickMode.entries.joinToString { it.name.lowercase() }}")

private fun waitUntil(value: String?): WaitUntil? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
    WaitUntil.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
        ?: throw IllegalArgumentException("until must be message or hurt")
}

/** A body tool whose results also mention unread chat, which a player would notice on screen. */
private class BodyTool<A>(
    argsType: TypeToken,
    name: String,
    description: String,
    log: AgentLog,
    private val body: AgentBody,
    private val run: suspend (A) -> String,
) : AgentTool<A>(argsType, name, description, log) {
    override suspend fun invoke(args: A): String {
        val result = run(args)
        val unread = body.unreadMessages()
        return if (unread > 0 && name != "read_chat") "$result\n[$unread unread chat message(s): call read_chat]" else result
    }
}

private class Registrar(val builder: ToolRegistryBuilder, val body: AgentBody, val log: AgentLog) {
    inline fun <reified A> add(name: String, description: String, noinline run: suspend (A) -> String) {
        builder.tool(BodyTool(typeToken<A>(), name, description, log, body, run))
    }
}

/** The tool set exposed to the LLM. Tool and argument names are part of the LLM contract. */
fun agentTools(body: AgentBody, log: AgentLog): ToolRegistry = ToolRegistry { Registrar(this, body, log).register() }

private fun Registrar.register() {
    // ------------------------------------------------------------ observation
    add<NoArgs>("get_status",
        "Your position, facing, health, armor, air, food, XP, game mode, held items, attack strength, movement state " +
            "(sneaking, flying, riding...), effects, open screens, spawn point, biome, light, time and weather.",
    ) { body.status() }
    add<NoArgs>("get_inventory",
        "All non-empty inventory slots (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand) and the selected hotbar slot.",
    ) { body.inventory() }
    add<SlotArgs>("inspect_item",
        "Full tooltip of an inventory item (enchantments, durability, lore, contents) and the text of books.",
    ) { body.inspectItem(it.slot) }
    add<BlockArgs>("get_block", "Block type, block data and sign text at the given coordinates.") {
        body.getBlock(it.x, it.y, it.z)
    }
    add<ScanArgs>("scan_area",
        "Non-air blocks in a cube around your feet, grouped by type with counts and up to 5 nearest coordinates each.",
    ) { body.scanArea(it.radius.coerceIn(1, 5)) }
    add<FindBlocksArgs>("find_blocks",
        "Nearest blocks of one type (e.g. minecraft:oak_log or oak_log) with coordinates and distance.",
    ) { body.findBlocks(it.type, it.radius.coerceIn(1, 24), it.limit.coerceIn(1, 20)) }
    add<EntitiesArgs>("get_nearby_entities",
        "Up to 20 nearest entities: uuid, type, name, coordinates, health, dropped item stacks, held items, riders.",
    ) { body.nearbyEntities(it.radius.coerceIn(1, 32)) }
    add<NoArgs>("get_looking_at", "The block (and face) or entity under your crosshair, within reach.") {
        body.lookingAt()
    }
    add<NoArgs>("read_chat",
        "Chat, system, title and action bar messages received since the last call (command feedback, whispers, other players).",
    ) { body.readMessages() }
    add<NoArgs>("get_container",
        "The open screen (chest, furnace, crafting table, villager trades, anvil, enchanting table...) or, if none, your " +
            "own inventory screen: every raw slot number with its item, the cursor item, and screen-specific data and buttons.",
    ) { body.container() }
    add<ItemArgs>("get_recipes",
        "Recipes that make an item: crafting grid layout, smelting, stonecutting, smithing; and whether each is in your recipe book.",
    ) { body.recipes(it.item) }

    // ------------------------------------------------------------ movement
    add<WalkArgs>("walk_to",
        "Pathfind and walk to the given feet position (at most 64 blocks horizontally per call).",
    ) { body.walkTo(it.x, it.y, it.z, it.within.coerceIn(0.0, 5.0), it.sprint) }
    add<MoveArgs>("move",
        "Hold movement keys for a number of ticks, relative to where you look: walk, strafe, sprint, jump, swim, climb " +
            "ladders, fly up, steer boats/horses/pigs/minecarts while riding. Turn first with look or look_at.",
    ) { body.move(it.forward, it.strafe, it.jump, it.sprint, it.ticks.coerceIn(1, 200)) }
    add<LookArgs>("look", "Turn your head to an absolute yaw and pitch.") {
        body.look(it.yaw.toFloat(), it.pitch.toFloat())
    }
    add<PointArgs>("look_at", "Turn your head and body toward a point.") {
        body.lookAt(it.x, it.y, it.z)
    }
    add<NoArgs>("jump",
        "Press jump once. In the air while wearing an elytra this starts gliding.",
    ) { body.jump() }
    add<ToggleArgs>("sneak",
        "Hold or release sneak. While held you move slowly, do not fall off edges, and right-clicks place blocks against " +
            "chests/doors instead of opening them.",
    ) { body.setSneaking(it.enabled) }
    add<ToggleArgs>("fly",
        "Start or stop flying when allowed (creative). Rise/descend with move jump=true or while sneaking.",
    ) { body.setFlying(it.enabled) }
    add<NoArgs>("dismount", "Get off the boat, minecart, horse or other entity you are riding.") {
        body.dismount()
    }
    add<NoArgs>("leave_bed", "Get out of the bed you are sleeping in.") { body.leaveBed() }
    add<NoArgs>("open_vehicle_inventory",
        "Open the inventory of the horse, donkey, llama, chest boat or chest minecart you are riding.",
    ) { body.openVehicleInventory() }

    // ------------------------------------------------------------ world interaction
    add<BlockArgs>("break_block",
        "Mine a block within reach with the held item, holding left click until it breaks (real time, depends on the tool).",
    ) { body.breakBlock(it.x, it.y, it.z) }
    add<PlaceArgs>("place_block",
        "Put the item in hand and right-click a neighbour of the given empty space so the block lands there. Also places " +
            "water/lava from buckets, torches, rails, signs (then edit_sign), boats and other placeable items.",
    ) { body.placeBlock(it.x, it.y, it.z, it.item, face(it.against), hand(it.hand)) }
    add<InteractBlockArgs>("interact_block",
        "Right-click a block within reach with the held item: open chests/furnaces/crafting tables, use doors, buttons, " +
            "levers, beds, anvils, enchanting tables; till with a hoe, use bone meal, flint and steel, buckets on cauldrons, etc.",
    ) { body.interactBlock(it.x, it.y, it.z, face(it.face), hand(it.hand)) }
    add<UseItemArgs>("use_item",
        "Right-click with the held item where you look: eat, drink, throw (snowballs, pearls, potions), shoot a bow " +
            "(hold ~20 ticks), block with a shield, fill a bucket from water you look_at, use fireworks while gliding.",
    ) { body.useItem(it.holdTicks.coerceIn(0, 100), hand(it.hand)) }
    add<EntityArgs>("attack",
        "Left-click (hit) an entity within reach. Damage scales with attack strength; wait until get_status shows 100%.",
    ) { body.attack(it.entityUuid) }
    add<InteractEntityArgs>("interact_entity",
        "Right-click an entity within reach: trade with villagers, mount horses/boats/minecarts, feed and breed animals, " +
            "shear sheep, milk cows, put on leads, name tags, saddles, armor stands.",
    ) { body.interactEntity(it.entityUuid, hand(it.hand)) }
    add<NoArgs>("swing_hand", "Left-click the air (swing your arm without hitting anything).") {
        body.swingHand()
    }
    add<BlockArgs>("pick_block",
        "Middle-click a block: select a matching item from your inventory (or get it in creative).",
    ) { body.pickBlock(it.x, it.y, it.z) }
    add<EntityArgs>("pick_entity",
        "Middle-click an entity: select its item form (spawn egg in creative, boat, minecart...).",
    ) { body.pickEntity(it.entityUuid) }
    add<FishArgs>("fish",
        "With a fishing rod in hand and water in front: cast, wait for a bite, reel in at the right moment and report the catch.",
    ) { body.fish(it.maxSeconds.coerceIn(5, 120)) }

    // ------------------------------------------------------------ inventory and containers
    add<HotbarArgs>("select_hotbar_slot", "Select hotbar slot 0..8 (number keys).") {
        body.selectHotbar(it.slot)
    }
    add<ItemArgs>("select_item", "Hold the item in your main hand, moving it to the hotbar if needed.") {
        body.selectItem(it.item)
    }
    add<NoArgs>("swap_hands", "Swap the items in your main hand and offhand (F key).") { body.swapHands() }
    add<DropArgs>("drop_item", "Throw the held item on the ground (Q).") { body.dropItem(it.wholeStack) }
    add<ClickArgs>("click_slot",
        "Click a slot of the open screen exactly like a player with a mouse (get_container lists the slots; with no screen " +
            "open it is your inventory, where slots 1-4 are the 2x2 crafting grid and 0 its result). Use it to move items " +
            "between containers and your inventory, put on armor, load furnaces, craft by hand and take results.",
    ) { body.clickSlot(it.slot, it.button, clickMode(it.mode)) }
    add<NoArgs>("close_container",
        "Close the open screen (Esc). Items left in crafting grids and on the cursor return to your inventory.",
    ) { body.closeContainer() }
    add<CraftArgs>("craft",
        "Craft with the recipe book: fills the grid of the open crafting table, or your 2x2 inventory grid when no screen is " +
            "open, then shift-clicks the result. Only recipes in your recipe book; otherwise craft by hand with click_slot.",
    ) { body.craft(it.item, it.times.coerceIn(1, 64)) }
    add<ButtonArgs>("container_button",
        "Press a button of the open screen: enchanting option 0-2, stonecutter or loom recipe index, lectern page turns.",
    ) { body.containerButton(it.id) }
    add<TradeArgs>("select_trade",
        "Select a villager trade; the payment moves into the trade slots and the result appears in slot 2.",
    ) { body.selectTrade(it.index) }
    add<RenameArgs>("rename_item", "Type the new name in the open anvil.") { body.renameItem(it.name.take(50)) }
    add<BeaconArgs>("set_beacon",
        "Choose the effects of the open beacon and confirm (needs the payment item in its slot).",
    ) { body.setBeacon(it.primary, it.secondary) }
    add<CrafterSlotArgs>("toggle_crafter_slot", "Enable or disable a slot of the open crafter.") {
        body.toggleCrafterSlot(it.slot)
    }
    add<BundleArgs>("select_bundle_item", "Choose which item a bundle in the open screen gives out next.") {
        body.selectBundleItem(it.slot, it.index)
    }
    add<CreativeArgs>("creative_set_slot",
        "Creative mode only: put any item into an inventory slot, or clear it.",
    ) { body.creativeSetSlot(it.slot, it.item, it.count) }

    // ------------------------------------------------------------ text
    add<ChatArgs>("chat", "Say something in public chat. Messages starting with / run as commands.") {
        body.chat(it.message.take(256))
    }
    add<CommandArgs>("run_command",
        "Run a command with your player's permissions and get its output.",
    ) { body.runCommand(it.command) }
    add<SignArgs>("edit_sign",
        "Write the side of a sign that faces you (right after placing it, or by right-clicking an unwaxed sign).",
    ) { body.editSign(it.x, it.y, it.z, it.lines.map { line -> line.take(90) }) }
    add<BookArgs>("write_book", "Write pages into the book and quill you hold; with a title it is signed.") {
        body.writeBook(it.pages, it.title?.take(32))
    }

    // ------------------------------------------------------------ misc
    add<EntityArgs>("spectate_teleport", "Spectator mode only: teleport to an entity or player.") {
        body.spectate(it.entityUuid)
    }
    add<WaitArgs>("wait", "Do nothing for a number of seconds, optionally until a message arrives or you get hurt.") {
        body.wait(it.seconds.coerceIn(1, 60), waitUntil(it.until))
    }
}
