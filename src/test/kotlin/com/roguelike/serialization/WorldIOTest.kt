package com.roguelike.serialization

import com.roguelike.world.Tile
import com.roguelike.world.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class WorldIOTest {

    class MockTile(override val type: String) : Tile

    @Test
    fun testSaveAndLoad() {
        val tempFile = File.createTempFile("world", ".wld")
        try {
            val world = World(3, 1, 3)
            val node = world.getNode(1, 0, 1)!!
            node.tiles.add(MockTile("TestTile"))
            world.addTag(node, "TestTag")

            WorldIO.saveWorld(tempFile.absolutePath, world)

            val loadedWorld = WorldIO.loadWorld(
                tempFile.absolutePath,
                { w, h, d -> World(w, h, d) },
                { type -> if (type == "TestTile") MockTile(type) else null }
            )

            assertNotNull(loadedWorld)
            assertEquals(3, loadedWorld!!.width)
            val loadedNode = loadedWorld.getNode(1, 0, 1)!!
            assertEquals(1, loadedNode.tiles.size)
            assertEquals("TestTile", loadedNode.tiles[0].type)
            assertTrue(loadedNode.tags.contains("TestTag"))
        } finally {
            tempFile.delete()
        }
    }
}
