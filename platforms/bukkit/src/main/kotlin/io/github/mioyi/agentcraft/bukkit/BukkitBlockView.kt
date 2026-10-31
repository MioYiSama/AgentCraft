package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.core.nav.BlockView
import io.github.mioyi.agentcraft.core.nav.Cell
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World

/** Navigation view of a world. Main thread only. */
class BukkitBlockView(private val world: World) : BlockView {
    override fun cell(x: Int, y: Int, z: Int): Cell {
        if (y < world.minHeight || y >= world.maxHeight) return if (y < world.minHeight) Cell.AVOID else Cell.PASSABLE
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return Cell.AVOID
        val block = world.getBlockAt(x, y, z)
        val type = block.type
        return when {
            type in HAZARDS || Tag.FENCES.isTagged(type) || Tag.WALLS.isTagged(type) ||
                Tag.FENCE_GATES.isTagged(type) -> Cell.AVOID
            type == Material.WATER -> Cell.WATER
            block.isPassable -> Cell.PASSABLE
            else -> Cell.SOLID
        }
    }

    private companion object {
        val HAZARDS = setOf(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        )
    }
}
