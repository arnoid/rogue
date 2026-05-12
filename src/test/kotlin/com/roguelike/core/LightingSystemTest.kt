package com.roguelike.core

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.CandleItem
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.ItemTags
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.TorchItem
import com.roguelike.core.model.World
import com.roguelike.core.model.isLit
import com.roguelike.core.model.isLightSource
import com.roguelike.core.model.setLit
import com.roguelike.core.model.toggleLit
import com.roguelike.core.systems.LightingSystem
import com.roguelike.utils.ItemCatalogLoader
import com.roguelike.world.WallEastTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LightingSystemTest {

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        // Load the real catalog from classpath resources.
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty()) { "items.json must be present on the classpath" }
    }

    // ── Tag toggling ────────────────────────────────────────────────────────

    @Test
    fun candleStartsUnlit_andTogglingCyclesLitState() {
        val candle = ItemFactory.create("Candle")!! as CandleItem
        assertTrue(candle.isLightSource())
        assertFalse(candle.isLit())
        assertTrue(ItemTags.LIGHT_SOURCE in candle.tags)
        assertFalse(ItemTags.LIGHT_SOURCE_LIT in candle.tags)

        // First click → lit
        val s1 = candle.toggleLit()
        assertTrue(s1)
        assertTrue(candle.isLit())
        assertTrue(ItemTags.LIGHT_SOURCE_LIT in candle.tags)

        // Second click → unlit
        val s2 = candle.toggleLit()
        assertFalse(s2)
        assertFalse(candle.isLit())
        assertFalse(ItemTags.LIGHT_SOURCE_LIT in candle.tags)
    }

    @Test
    fun keyIsNotLightSource_andTogglingIsNoOp() {
        val key = ItemFactory.create("Key")!!
        assertFalse(key.isLightSource())
        assertFalse(key.isLit())
        key.toggleLit()
        assertFalse(key.isLit())
    }

    @Test
    fun setLitOnlyAffectsLightSources() {
        val key = ItemFactory.create("Key")!!
        key.setLit(true)
        assertFalse(key.isLit())

        val torch = ItemFactory.create("Torch")!!
        torch.setLit(true)
        assertTrue(torch.isLit())
        torch.setLit(false)
        assertFalse(torch.isLit())
    }

    // ── Candle cone ─────────────────────────────────────────────────────────

    @Test
    fun candleCone_litsCellInFront_doesNotLitCellBehindOrAside() {
        val world = World(9, 9, 3)
        val candle = ItemFactory.create("Candle")!!.also { it.setLit(true) }

        // Player at center (4,4,1) facing +Y (north).
        val facing = Vec3(0f, 1f, 0f)
        val map = LightingSystem.computeAt(world, 4f, 4f, 1, facing, listOf(candle))

        // Source cell is always lit.
        assertTrue(map.isLit(4, 4, 1))

        // Cell directly in front (north) is lit.
        assertTrue(map.isLit(4, 6, 1), "cell 2 north of player should be lit")

        // Cell directly behind (south) is NOT lit (outside 90° cone centered on +Y).
        assertFalse(map.isLit(4, 2, 1), "cell behind player should not be lit by cone")

        // Cell directly east is on cone boundary at 45°; with 90° full cone
        // (half-angle 45°) cos(45°) ≈ 0.707 and dot=1.0 for pure east is 0.0,
        // so east should NOT be lit.
        assertFalse(map.isLit(6, 4, 1), "cell directly east should be outside 90° cone facing north")
    }

    @Test
    fun candleCone_respectsRangeLimit() {
        val world = World(15, 15, 3)
        val candle = ItemFactory.create("Candle")!!.also { it.setLit(true) }
        // Candle range = 8 (from catalog)
        val map = LightingSystem.computeAt(world, 7f, 7f, 1, Vec3(0f, 1f, 0f), listOf(candle))

        // Within range, on-axis
        assertTrue(map.isLit(7, 10, 1))
        val def = ItemCatalog["Candle"]!!.light!!
        assertEquals(8f, def.range)
        // 7 cells south is behind player → not lit anyway.
        assertFalse(map.isLit(7, 0, 1))
    }

    // ── Torch sphere ────────────────────────────────────────────────────────

    @Test
    fun torchSphere_litsAllDirections_withinRange() {
        val world = World(15, 15, 3)
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        // Torch range = 7
        val map = LightingSystem.computeAt(world, 7f, 7f, 1, Vec3(0f, 1f, 0f), listOf(torch))

        // Cells in all 4 cardinal directions within range are lit.
        assertTrue(map.isLit(7, 9, 1), "north")
        assertTrue(map.isLit(7, 5, 1), "south")
        assertTrue(map.isLit(9, 7, 1), "east")
        assertTrue(map.isLit(5, 7, 1), "west")
    }

    @Test
    fun torchSphere_doesNotLightBeyondRange() {
        val world = World(33, 33, 3)
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val map = LightingSystem.computeAt(world, 15f, 15f, 1, Vec3(0f, 1f, 0f), listOf(torch))

        // 9 cells away on a clear floor — outside range 7.
        assertFalse(map.isLit(15, 24, 1), "9 cells north is beyond torch range 7")
        // 8 cells away — also beyond.
        assertFalse(map.isLit(15, 23, 1))
    }

    // ── Occlusion ───────────────────────────────────────────────────────────

    @Test
    fun wallBlocksLight() {
        val world = World(9, 9, 3)
        // Build a wall between (4,4) and (5,4) — east wall of (4,4).
        val src = world.getNode(4, 4, 1)!!
        src.setTile(WallEastTile())
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }

        val map = LightingSystem.computeAt(world, 4f, 4f, 1, Vec3(1f, 0f, 0f), listOf(torch))
        assertTrue(map.isLit(4, 4, 1))
        // Wall sits between (4,4) and (5,4); cell at (5,4) should NOT be lit.
        assertFalse(map.isLit(5, 4, 1), "wall must block light to the cell directly behind it")
        // But (4,5) (north of player, no wall there) should be lit.
        assertTrue(map.isLit(4, 5, 1))
    }

    // ── No light without lit items ──────────────────────────────────────────

    @Test
    fun noLightWhenNoItemsAreLit() {
        val world = World(9, 9, 3)
        val unlit = ItemFactory.create("Torch")!! // unlit by default
        val map = LightingSystem.computeAt(world, 4f, 4f, 1, Vec3(0f, 1f, 0f), listOf(unlit))
        for (x in 0 until world.width) for (y in 0 until world.height) for (z in 0 until world.depth) {
            assertFalse(map.isLit(x, y, z), "cell ($x,$y,$z) should be dark with no lit items")
        }
    }

    @Test
    fun emptyInventoryProducesEmptyMap() {
        val world = World(9, 9, 3)
        val map = LightingSystem.computeAt(world, 4f, 4f, 1, Vec3(0f, 1f, 0f), emptyList())
        for (x in 0 until world.width) for (y in 0 until world.height) for (z in 0 until world.depth) {
            assertFalse(map.isLit(x, y, z))
        }
    }

    // ── 3D / multi-Z behavior ───────────────────────────────────────────────

    @Test
    fun torchLightsCellsOnOtherZLevels_whenNoFloorBlocks() {
        val world = World(9, 9, 6)
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }

        // No floors anywhere — light should pass freely between Z layers.
        val map = LightingSystem.computeAt(world, 4f, 4f, 3, Vec3(0f, 1f, 0f), listOf(torch))

        // Source cell.
        assertTrue(map.isLit(4, 4, 3))
        // Same column, one level down.
        assertTrue(map.isLit(4, 4, 2), "with no floor, light goes down to z-1")
        // Same column, one level up.
        assertTrue(map.isLit(4, 4, 4), "with no floor above, light goes up to z+1")
    }

    @Test
    fun floorAboveBlocksLightFromBelow() {
        val world = World(9, 9, 6)
        // Place a floor at (4,4,4) — this should block light from (4,4,3) going up.
        val upper = world.getNode(4, 4, 4)!!
        upper.setTile(com.roguelike.world.FloorTile())

        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val map = LightingSystem.computeAt(world, 4f, 4f, 3, Vec3(0f, 1f, 0f), listOf(torch))

        assertTrue(map.isLit(4, 4, 3))
        // (4,4,4) contains a blocking floor between source and itself — like a
        // wall, this blocks the ray and the cell is not lit by the torch.
        assertFalse(map.isLit(4, 4, 4), "floor between source and target blocks light")
        // The cell beyond the floor (4,4,5) should also NOT be lit.
        assertFalse(map.isLit(4, 4, 5), "floor must block light from passing further up")
    }

    @Test
    fun stairsAllowLightToPassBetweenLevels() {
        val world = World(9, 9, 6)
        // Floor blocks light at (4,4,4), but a stairs tile lets it through.
        val upper = world.getNode(4, 4, 4)!!
        upper.setTile(com.roguelike.world.FloorTile())
        upper.setTile(com.roguelike.world.StairsTile())

        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val map = LightingSystem.computeAt(world, 4f, 4f, 3, Vec3(0f, 1f, 0f), listOf(torch))

        assertTrue(map.isLit(4, 4, 3))
        assertTrue(map.isLit(4, 4, 4), "stairs let light pass to the floor surface")
        assertTrue(map.isLit(4, 4, 5), "stairs let light continue to the level above")
    }

    @Test
    fun floorBlocksLightFromAboveToBelow() {
        val world = World(9, 9, 6)
        // Floor at the source's own level (4,4,3) shouldn't matter — the light
        // starts inside that cell. But a floor at (4,4,3) blocks light going
        // DOWN to (4,4,2).
        val srcCell = world.getNode(4, 4, 3)!!
        srcCell.setTile(com.roguelike.world.FloorTile())

        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val map = LightingSystem.computeAt(world, 4f, 4f, 3, Vec3(0f, 1f, 0f), listOf(torch))

        assertTrue(map.isLit(4, 4, 3))
        // (4,4,2) is the cell directly below the floor — the floor blocks the
        // edge between (4,4,3) and (4,4,2) so (4,4,2) is dark.
        assertFalse(map.isLit(4, 4, 2), "floor at source level blocks light going down")
    }
}
