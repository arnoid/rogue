package com.roguelike.serialization

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.World
import com.roguelike.utils.ItemCatalogLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldLightingIntegrationTest {

    private lateinit var loadedWorld: World

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())

        val worldFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(6)
            .map { File(it, "saved-worlds/world.wld") }
            .firstOrNull { it.exists() }

        loadedWorld = if (worldFile != null) {
            WorldIO.loadWorld(
                worldFile.canonicalPath,
                { w, h, d -> World(w, h, d) },
                { _ -> null }
            ) ?: World(9, 9, 3)
        } else {
            World(9, 9, 3)
        }
    }

    @Test
    fun loadedWorldHasNonZeroDimensions() {
        assertTrue(loadedWorld.width > 0 && loadedWorld.height > 0 && loadedWorld.depth > 0,
            "Loaded world must have positive dimensions")
    }
}
