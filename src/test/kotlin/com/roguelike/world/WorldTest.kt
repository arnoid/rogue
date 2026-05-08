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
        val world = World(5, 1, 5)
        val node = world.getNode(2, 0, 2)!!
        
        // Empty node should be walkable
        assertTrue(world.isWalkable(2f, 0f, 2f))
        
        node.setTile(MockTile("Wall", true, TileSlot.WALL))
        assertFalse(world.isWalkable(2f, 0f, 2f))
        
        node.clear()
        node.setTile(MockTile("Floor", false, TileSlot.FLOOR))
        assertTrue(world.isWalkable(2f, 0f, 2f))
    }

    @Test
    fun testTags() {
        val world = World(5, 1, 5)
        val node = world.getNode(1, 0, 1)!!
        
        world.addTag(node, "test_tag")
        assertTrue(node.tags.contains("test_tag"))
        assertEquals(1, world.getNodesWithTag("test_tag").size)
        
        world.removeTag(node, "test_tag")
        assertFalse(node.tags.contains("test_tag"))
        assertEquals(0, world.getNodesWithTag("test_tag").size)
    }
}
