package io.github.mioyi.agentcraft.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathFinderTest {
    /** Ground: y < 64 is SOLID, y >= 64 is PASSABLE, unless overridden. */
    private class World : BlockView {
        val cells = HashMap<BlockPos3, Cell>()
        override fun cell(x: Int, y: Int, z: Int) =
            cells[BlockPos3(x, y, z)] ?: if (y < 64) Cell.SOLID else Cell.PASSABLE

        fun set(x: Int, y: Int, z: Int, c: Cell) {
            cells[BlockPos3(x, y, z)] = c
        }
    }

    /** Walls the corridor z = 0 in on both sides for x in [xs], heights 64..67. */
    private fun World.corridor(xs: IntRange) {
        for (x in xs) for (y in 64..67) {
            set(x, y, -1, Cell.SOLID)
            set(x, y, 1, Cell.SOLID)
        }
    }

    @Test
    fun `flat line`() {
        val path = PathFinder(World()).find(BlockPos3(0, 64, 0), BlockPos3(5, 64, 0), 0.0)
        assertNotNull(path)
        assertEquals(5, path.size)
        assertEquals(BlockPos3(5, 64, 0), path.last())
    }

    @Test
    fun `steps up a one high wall`() {
        val w = World()
        w.corridor(-2..8)
        w.set(3, 64, 0, Cell.SOLID)
        val path = PathFinder(w).find(BlockPos3(0, 64, 0), BlockPos3(6, 64, 0), 0.0)
        assertNotNull(path)
        assertTrue(BlockPos3(3, 65, 0) in path)
        assertEquals(BlockPos3(6, 64, 0), path.last())
    }

    @Test
    fun `three high wall in a closed corridor blocks`() {
        val w = World()
        w.corridor(-2..8)
        w.set(-2, 64, 0, Cell.SOLID); w.set(-2, 65, 0, Cell.SOLID); w.set(-2, 66, 0, Cell.SOLID)
        w.set(8, 64, 0, Cell.SOLID); w.set(8, 65, 0, Cell.SOLID); w.set(8, 66, 0, Cell.SOLID)
        for (y in 64..66) w.set(3, y, 0, Cell.SOLID)
        assertNull(PathFinder(w).find(BlockPos3(0, 64, 0), BlockPos3(6, 64, 0), 0.0))
    }

    @Test
    fun `routes around lava`() {
        val w = World()
        w.set(2, 64, 0, Cell.AVOID)
        val path = PathFinder(w).find(BlockPos3(0, 64, 0), BlockPos3(4, 64, 0), 0.0)
        assertNotNull(path)
        assertTrue(BlockPos3(2, 64, 0) !in path)
        assertEquals(BlockPos3(4, 64, 0), path.last())
    }

    @Test
    fun `drops up to three blocks`() {
        val w = World()
        w.corridor(-1..6)
        w.set(-1, 64, 0, Cell.SOLID); w.set(-1, 65, 0, Cell.SOLID)
        // platform at y=67 (floor y=66) for x <= 1, ground at y=64 beyond: a drop of 3
        for (x in -1..1) for (y in 64..66) w.set(x, y, 0, Cell.SOLID)
        val path = PathFinder(w).find(BlockPos3(0, 67, 0), BlockPos3(4, 64, 0), 0.0)
        assertNotNull(path)
        assertEquals(listOf(BlockPos3(1, 67, 0), BlockPos3(2, 64, 0)), path.take(2))
    }

    @Test
    fun `drop of four is refused`() {
        val w = World()
        w.corridor(-1..6)
        for (x in -1..1) for (y in 64..67) w.set(x, y, 0, Cell.SOLID)
        for (y in 64..69) w.set(-2, y, 0, Cell.SOLID)
        assertNull(PathFinder(w).find(BlockPos3(0, 68, 0), BlockPos3(4, 64, 0), 0.0))
    }

    @Test
    fun `within reaches a neighbour of a solid goal`() {
        val w = World()
        w.set(3, 64, 0, Cell.SOLID)
        val path = PathFinder(w).find(BlockPos3(0, 64, 0), BlockPos3(3, 64, 0), 1.0)
        assertNotNull(path)
        assertEquals(BlockPos3(2, 64, 0), path.last())
    }
}
