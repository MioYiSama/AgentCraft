package io.github.mioyi.agentcraft.bukkit

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MerchantMenu
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.SmithingTrimRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.TransmuteRecipe
import org.bukkit.inventory.view.AnvilView
import org.bukkit.inventory.view.BeaconView
import org.bukkit.inventory.view.BrewingStandView
import org.bukkit.inventory.view.CrafterView
import org.bukkit.inventory.view.EnchantmentView
import org.bukkit.inventory.view.FurnaceView
import org.bukkit.inventory.view.LecternView
import org.bukkit.inventory.view.LoomView
import org.bukkit.inventory.view.StonecutterView

/** Container slot mapping and descriptions of open containers and recipes. */

/**
 * Raw slot of player inventory index [invIndex] in the open screen of [h], or null if the screen does not show it.
 * Bukkit's [InventoryView.countSlots] also counts equipment slots no screen has, so the menu decides the range.
 */
fun rawSlotOf(p: Player, h: ServerPlayer, invIndex: Int): Int? {
    val view = p.openInventory
    return (0 until h.containerMenu.slots.size).firstOrNull { raw ->
        view.getInventory(raw) === p.inventory && view.convertSlot(raw) == invIndex
    }
}

/** Slot number of player inventory index [invIndex] in the player's own inventory menu (container 0). */
fun inventoryMenuSlot(invIndex: Int): Int? = when (invIndex) {
    in 0..8 -> 36 + invIndex
    in 9..35 -> invIndex
    in 36..39 -> 8 - (invIndex - 36)
    40 -> 45
    else -> null
}

fun inventorySlotName(index: Int): String = when (index) {
    in 0..8 -> "hotbar $index"
    36 -> "boots"
    37 -> "leggings"
    38 -> "chestplate"
    39 -> "helmet"
    40 -> "offhand"
    else -> "inventory $index"
}

fun describeContainer(p: Player, h: ServerPlayer): String {
    val view = p.openInventory
    val own = h.containerMenu === h.inventoryMenu
    val top = view.topInventory
    return buildString {
        if (own) {
            appendLine("No container open: showing your own inventory (container 0, 2x2 crafting grid).")
        } else {
            appendLine("Open container: ${top.type.name.lowercase()} \"${plain(view.title())}\" (container ${h.containerMenu.containerId})")
        }
        appendLine("Cursor (carried item): ${itemText(view.cursor)}")
        val empty = ArrayList<Int>()
        val slots = 0 until h.containerMenu.slots.size
        for (raw in slots) {
            val ofPlayer = view.getInventory(raw) === p.inventory
            val item = view.getItem(raw)
            val label = if (ofPlayer) inventorySlotName(view.convertSlot(raw)) else view.getSlotType(raw).name.lowercase()
            if (item == null || item.type.isAir) {
                if (!ofPlayer) empty += raw
                continue
            }
            appendLine("slot $raw [$label]: ${itemText(item)}")
        }
        if (empty.isNotEmpty()) appendLine("Empty container slots: ${ranges(empty)}")
        val playerSlots = slots.filter { view.getInventory(it) === p.inventory }
        if (playerSlots.isNotEmpty()) appendLine("Your inventory occupies slots ${ranges(playerSlots)}.")
        append(extras(view, h))
    }.trimEnd()
}

private fun extras(view: InventoryView, h: ServerPlayer): String = buildString {
    when (view) {
        is FurnaceView -> appendLine(
            "Furnace: ${if (view.isBurning) "burning" else "not burning"}, fuel left %.0f%%, cooking %.0f%%".format(
                view.burnTime * 100, view.cookTime * 100,
            ),
        )

        is BrewingStandView -> appendLine("Brewing: fuel ${view.fuelLevel}, brewing ticks left ${view.brewingTicks}")
        is EnchantmentView -> {
            appendLine("Enchantment options (container_button 0-2; needs lapis in slot 1 and the shown levels):")
            view.offers.forEachIndexed { i, offer ->
                appendLine(
                    if (offer == null) "  $i: none" else "  $i: ${offer.enchantment.key.key} ${offer.enchantmentLevel}? costs ${offer.cost} levels",
                )
            }
        }

        is AnvilView -> appendLine("Anvil: repair cost ${view.repairCost} levels, name \"${view.renameText ?: ""}\" (rename_item)")
        is StonecutterView -> {
            appendLine("Stonecutter recipes (container_button <index>), selected ${view.selectedRecipeIndex}:")
            view.recipes.forEachIndexed { i, r -> appendLine("  $i: ${itemText(r.result)}") }
        }

        is LoomView -> {
            appendLine("Loom patterns (container_button <index>), selected ${view.selectedPatternIndex}:")
            val patterns = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN)
            view.selectablePatterns.forEachIndexed { i, pattern -> appendLine("  $i: ${patterns.getKey(pattern)?.value()}") }
        }

        is LecternView -> {
            appendLine("Lectern: page ${view.page + 1} (container_button 1 previous, 2 next, 3 take book, 100+n go to page n+1)")
            bookText(view.topInventory.getItem(0))?.let { appendLine(it) }
        }

        is BeaconView -> appendLine(
            "Beacon: pyramid tier ${view.tier}, primary ${view.primaryEffect?.key?.key ?: "none"}, " +
                "secondary ${view.secondaryEffect?.key?.key ?: "none"} (pay into slot 0, then set_beacon)",
        )

        is CrafterView -> appendLine(
            "Crafter: disabled slots ${(0 until 9).filter { view.isSlotDisabled(it) }.ifEmpty { listOf("none") }.joinToString(",")}" +
                " (toggle_crafter_slot)",
        )

        else -> {}
    }
    (h.containerMenu as? MerchantMenu)?.let { menu ->
        appendLine("Trades (select_trade <index>, then take the result from slot 2):")
        menu.offers.forEachIndexed { i, offer ->
            val b = offer.getCostB().takeIf { !it.isEmpty }?.let { " + ${itemText(CraftItemStack.asBukkitCopy(it))}" } ?: ""
            val stock = if (offer.isOutOfStock) " (out of stock)" else " (${offer.uses}/${offer.maxUses} used)"
            appendLine("  $i: ${itemText(CraftItemStack.asBukkitCopy(offer.getCostA()))}$b -> ${itemText(CraftItemStack.asBukkitCopy(offer.getResult()))}$stock")
        }
    }
}

private fun ranges(values: List<Int>): String {
    if (values.isEmpty()) return "none"
    val out = ArrayList<String>()
    var start = values[0]
    var prev = start
    for (v in values.drop(1) + Int.MIN_VALUE) {
        if (v == prev + 1) {
            prev = v
            continue
        }
        out += if (start == prev) "$start" else "$start-$prev"
        start = v
        prev = v
    }
    return out.joinToString(",")
}

fun describeRecipes(p: Player, material: Material): String {
    val recipes = Bukkit.getRecipesFor(ItemStack(material))
    if (recipes.isEmpty()) return "No recipe makes ${material.typeKey}"
    return recipes.take(MAX_RECIPES).joinToString("\n\n") { recipe ->
        val key = (recipe as? Keyed)?.key
        val unlocked = key?.let { if (p.hasDiscoveredRecipe(it)) " (in your recipe book)" else " (not in your recipe book)" } ?: ""
        "${key ?: "recipe"} -> ${itemText(recipe.result)}$unlocked\n" + recipeBody(recipe)
    } + if (recipes.size > MAX_RECIPES) "\n\n(${recipes.size - MAX_RECIPES} more recipes)" else ""
}

private fun recipeBody(recipe: Recipe): String = when (recipe) {
    is ShapedRecipe -> {
        val legend = recipe.choiceMap.entries.filter { it.value != null }
            .joinToString("\n") { (c, choice) -> "  $c = ${choiceText(choice)}" }
        "Shaped crafting (${recipe.shape.maxOf { it.length }}x${recipe.shape.size}${if (recipe.shape.size <= 2 && recipe.shape.all { it.length <= 2 }) ", fits the 2x2 grid" else ", needs a crafting table"}):\n" +
            recipe.shape.joinToString("\n") { "  [${it.replace(' ', '.')}]" } + "\n" + legend
    }

    is ShapelessRecipe -> "Shapeless crafting${if (recipe.choiceList.size <= 4) " (fits the 2x2 grid)" else " (needs a crafting table)"}: " +
        recipe.choiceList.joinToString(" + ") { choiceText(it) }

    is TransmuteRecipe -> "Crafting: ${choiceText(recipe.input)} + ${choiceText(recipe.material)}"
    is CookingRecipe<*> -> "${recipe.javaClass.simpleName.removePrefix("Craft").removeSuffix("Recipe").lowercase()}: " +
        "${choiceText(recipe.inputChoice)}, ${recipe.cookingTime} ticks, ${recipe.experience} xp"

    is StonecuttingRecipe -> "Stonecutter: ${choiceText(recipe.inputChoice)}"
    is SmithingTransformRecipe -> "Smithing table: template ${choiceText(recipe.template)} + base ${choiceText(recipe.base)} + ${choiceText(recipe.addition)}"
    is SmithingTrimRecipe -> "Smithing table trim: template ${choiceText(recipe.template)} + base ${choiceText(recipe.base)} + ${choiceText(recipe.addition)}"
    else -> recipe.javaClass.simpleName
}

private fun choiceText(choice: RecipeChoice?): String = when (choice) {
    null -> "nothing"
    is RecipeChoice.MaterialChoice -> choice.choices.let { list ->
        list.take(4).joinToString("|") { it.typeKey } + if (list.size > 4) "|... (${list.size} options)" else ""
    }

    is RecipeChoice.ExactChoice -> choice.choices.joinToString("|") { itemText(it) }
    else -> choice.toString()
}

private const val MAX_RECIPES = 6
