package com.roguelike.world

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorldTest {

    class MockTile(override val type: String, private val blocking: Boolean) : Tile {
        override fun isBlocking(): Boolean = blocking
    }

    @Test
    fun testIsWalkable() {
        val world = World(5, 1, 5)
        val node = world.getNode(2, 0, 2)!!
        
        // Empty node should be walkable if we follow the logic (node.tiles.none { it.isBlocking() })
        // Wait, current logic in World.isWalkable:
        // return node.tiles.none { it.isBlocking() }
        assertTrue(world.isWalkable(2f, 0f, 2f))
        
        node.tiles.add(MockTile("Wall", true))
        assertFalse(world.isWalkable(2f, 0f, 2f))
        
        node.tiles.clear()
        node.tiles.add(MockTile("Floor", false))
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
