package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.core.nav.BlockPos3
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Sittable
import org.bukkit.entity.Tameable
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.Damageable
import kotlin.math.floor

/** Text rendering shared by the observation tools. */

val Material.typeKey: String get() = key.asString()

fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

fun itemText(item: ItemStack?): String {
    if (item == null || item.type.isAir) return "nothing"
    val meta = item.itemMeta
    val damage = (meta as? Damageable)?.takeIf { it.hasDamage() }?.let {
        " (damage ${it.damage}/${item.type.maxDurability})"
    } ?: ""
    val name = meta?.takeIf { it.hasCustomName() }?.customName()?.let { " named \"${plain(it)}\"" } ?: ""
    val enchants = item.enchantments.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ", " [", "]") { (e, level) ->
        "${e.key.key} $level"
    } ?: ""
    return "${item.type.typeKey} x${item.amount}$damage$name$enchants"
}

/** Pages of a written or writable book, or null if [item] is not a book. */
fun bookText(item: ItemStack?, maxPages: Int = 20): String? {
    val meta = item?.itemMeta as? BookMeta ?: return null
    val header = buildString {
        if (meta.hasTitle()) append("Title: ${plain(meta.title() ?: Component.empty())}\n")
        if (meta.hasAuthor()) append("Author: ${plain(meta.author() ?: Component.empty())}\n")
    }
    if (meta.pageCount == 0) return header + "The book has no pages"
    return header + meta.pages().take(maxPages).withIndex().joinToString("\n") { (i, page) ->
        "Page ${i + 1}: ${plain(page)}"
    } + if (meta.pageCount > maxPages) "\n(${meta.pageCount - maxPages} more pages)" else ""
}

fun feet(loc: Location) = BlockPos3(loc.blockX, floor(loc.y + 0.01).toInt(), loc.blockZ)

fun posText(p: BlockPos3) = "${p.x} ${p.y} ${p.z}"

fun blockText(loc: Location) = "${loc.blockX} ${loc.blockY} ${loc.blockZ}"

fun blockText(block: Block) = "${block.x} ${block.y} ${block.z}"

fun entityLine(e: Entity, from: Location): String {
    val l = e.location
    val extra = buildString {
        if (e is LivingEntity) append(" health=%.1f".format(e.health))
        when (e) {
            is Item -> append(" item=${itemText(e.itemStack)}")
            is Player -> append(" player gamemode=${e.gameMode.name.lowercase()}")
            is FishHook -> append(" state=${e.state.name.lowercase()}")
            else -> {}
        }
        if (e is Ageable && !e.isAdult) append(" baby")
        if (e is Tameable && e.isTamed) append(" tamed${e.ownerUniqueId?.let { " owner=$it" } ?: ""}")
        if (e is Sittable && e.isSitting) append(" sitting")
        if (e is Villager) append(" profession=${e.profession.key.key} level=${e.villagerLevel}")
        if (e is LivingEntity) {
            val held = e.equipment?.itemInMainHand
            if (held != null && !held.type.isAir) append(" holding=${held.type.typeKey}")
        }
        e.vehicle?.let { append(" riding=${it.type.key.asString()}") }
        if (e.passengers.isNotEmpty()) append(" passengers=${e.passengers.joinToString(",") { it.name }}")
    }
    return "${e.uniqueId} ${e.type.key.asString()} \"${e.name}\" (${blockText(l)}) dist=${"%.1f".format(l.distance(from))}$extra"
}
