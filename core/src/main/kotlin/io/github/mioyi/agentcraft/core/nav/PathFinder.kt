package io.github.mioyi.agentcraft.core.nav

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.sqrt

enum class Cell {
    /** Blocks movement; can be stood on. */
    SOLID,

    /** Air, plants. */
    PASSABLE,

    /** Enterable and swimmable. */
    WATER,

    /** Never entered nor stood on: lava, fire, cactus, fences, walls, unloaded chunks. */
    AVOID,
}

fun interface BlockView {
    fun cell(x: Int, y: Int, z: Int): Cell
}

data class BlockPos3(val x: Int, val y: Int, val z: Int) {
    fun offset(dx: Int, dy: Int, dz: Int) = BlockPos3(x + dx, y + dy, z + dz)

    fun distanceTo(o: BlockPos3): Double {
        val dx = (x - o.x).toDouble()
        val dy = (y - o.y).toDouble()
        val dz = (z - o.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun horizontalDistanceTo(o: BlockPos3): Double {
        val dx = (x - o.x).toDouble()
        val dz = (z - o.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }
}

/** A* over walkable feet positions: cardinal moves, 1-block step ups and drops of up to 3 blocks. */
class PathFinder(private val view: BlockView, private val maxNodes: Int = 4000) {

    /**
     * Returns the waypoints after [start] ending at the reached node, an empty list when [start] already
     * satisfies the goal, or null when no path exists within the search limits.
     */
    fun find(start: BlockPos3, goal: BlockPos3, within: Double): List<BlockPos3>? {
        val radius = max(within, 0.0)
        fun isGoal(p: BlockPos3) = p.distanceTo(goal) <= radius
        fun h(p: BlockPos3) = max(p.distanceTo(goal) - radius, 0.0)

        if (isGoal(start)) return emptyList()
        val maxRange = start.horizontalDistanceTo(goal) + 16

        val g = HashMap<BlockPos3, Double>()
        val parent = HashMap<BlockPos3, BlockPos3>()
        val closed = HashSet<BlockPos3>()
        val open = PriorityQueue<Node>(compareBy { it.f })
        g[start] = 0.0
        open.add(Node(start, h(start)))
        var expanded = 0

        while (open.isNotEmpty()) {
            val current = open.poll().pos
            if (!closed.add(current)) continue
            if (isGoal(current)) return reconstruct(parent, start, current)
            if (++expanded > maxNodes) return null
            if (current.horizontalDistanceTo(start) > maxRange) continue
            val base = g.getValue(current)
            neighbors(current) { next, cost ->
                if (next in closed) return@neighbors
                val tentative = base + cost
                if (tentative < (g[next] ?: Double.POSITIVE_INFINITY)) {
                    g[next] = tentative
                    parent[next] = current
                    open.add(Node(next, tentative + h(next)))
                }
            }
        }
        return null
    }

    private fun reconstruct(parent: Map<BlockPos3, BlockPos3>, start: BlockPos3, end: BlockPos3): List<BlockPos3> {
        val path = ArrayList<BlockPos3>()
        var p = end
        while (p != start) {
            path.add(p)
            p = parent.getValue(p)
        }
        path.reverse()
        return path
    }

    private fun cell(p: BlockPos3) = view.cell(p.x, p.y, p.z)

    private fun Cell.enterable() = this == Cell.PASSABLE || this == Cell.WATER

    private fun standable(p: BlockPos3): Boolean {
        val feet = cell(p)
        if (!feet.enterable() || !cell(p.offset(0, 1, 0)).enterable()) return false
        return feet == Cell.WATER || cell(p.offset(0, -1, 0)) == Cell.SOLID
    }

    private inline fun neighbors(p: BlockPos3, emit: (BlockPos3, Double) -> Unit) {
        for ((dx, dz) in DIRECTIONS) {
            val side = p.offset(dx, 0, dz)
            if (standable(side)) {
                emit(side, 1.0)
                continue
            }
            val up = side.offset(0, 1, 0)
            val headroom = cell(p.offset(0, 2, 0))
            if (headroom != Cell.SOLID && headroom != Cell.AVOID && standable(up)) {
                emit(up, 2.0)
                continue
            }
            if (!cell(side.offset(0, 1, 0)).enterable()) continue
            for (k in 1..3) {
                if (!cell(side.offset(0, -(k - 1), 0)).enterable()) break
                val down = side.offset(0, -k, 0)
                if (standable(down)) {
                    emit(down, 1 + 0.5 * k)
                    break
                }
            }
        }
    }

    private class Node(val pos: BlockPos3, val f: Double)

    private companion object {
        val DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
