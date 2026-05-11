package com.roguelike.world

import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldTest {

    class MockTile(override val type: String, private val blocking: Boolean, override val slot: TileSlot = TileSlot.FLOOR) : Tile {
        override fun isBlocking(): Boolean = blocking
    }

    @Test
    fun testIsWalkable() {
        val world = World(6, 3, 6)
        val node = world.getNode(2, 0, 2)!!

        // Empty node (no floor) should NOT be walkable
        assertFalse(world.isWalkable(2f, 0f, 2f))

        // Node with floor should be walkable
        node.setTile(MockTile("Floor", false, TileSlot.FLOOR))
        assertTrue(world.isWalkable(2f, 0f, 2f))
    }

    @Test
    fun testTags() {
        val world = World(6, 3, 6)
        val node = world.getNode(1, 0, 1)!!

        world.addTag(node, "test_tag")
        assertTrue(node.tags.contains("test_tag"))
        assertEquals(1, world.getNodesWithTag("test_tag").size)

        world.removeTag(node, "test_tag")
        assertFalse(node.tags.contains("test_tag"))
        assertEquals(0, world.getNodesWithTag("test_tag").size)
    }

    @Test
    fun testDimensionsMustBeDivisibleBy3() {
        assertThrows(IllegalArgumentException::class.java) { World(5, 3, 3) }
        assertThrows(IllegalArgumentException::class.java) { World(3, 4, 3) }
        assertThrows(IllegalArgumentException::class.java) { World(3, 3, 7) }
        assertDoesNotThrow { World(3, 6, 9) }
    }

    @Test
    fun testWallBlocking() {
        val world = World(3, 3, 3)
        val node = world.getNode(1, 1, 0)!!

        node.setTile(MockTile("WallNorth", true, TileSlot.WALL_NORTH))
        assertTrue(node.isWallBlocking(TileSlot.WALL_NORTH))
        assertFalse(node.isWallBlocking(TileSlot.WALL_SOUTH))
    }

    @Test
    fun testDoorTagMakesWallPassable() {
        val world = World(3, 3, 3)
        val node = world.getNode(1, 1, 0)!!

        node.setTile(MockTile("WallNorth", true, TileSlot.WALL_NORTH))
        assertTrue(node.isWallBlocking(TileSlot.WALL_NORTH))

        node.tagAsDoor(TileSlot.WALL_NORTH)
        assertFalse(node.isWallBlocking(TileSlot.WALL_NORTH))
    }
}
