package com.roguelike.core

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.Player
import com.roguelike.core.model.World
import com.roguelike.core.model.setLit
import com.roguelike.core.systems.ModelOcclusionProvider
import com.roguelike.core.systems.SurfaceLighting
import com.roguelike.utils.ItemCatalogLoader
import com.roguelike.world.FloorTile
import com.roguelike.world.WallEastTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SurfaceLightingModelOcclusionTest {

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())
    }

    private fun bright(arr: FloatArray): Float = maxOf(arr[0], maxOf(arr[1], arr[2]))

    private fun playerWithTorch(world: World, x: Int, y: Int, z: Int): Player {
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        return Player().also {
            it.position.set(x.toFloat(), y.toFloat(), z.toFloat())
            it.inventory.add(torch)
        }
    }

    // Stub occluder: blocks rays that cross the plane x = 5.0
    // (the wall between cells x=4 and x=5 sits at x=4.5 in game coords,
    //  so a stub that blocks any ray whose x-range includes 4.5 simulates it)
    private val xWallOccluder = ModelOcclusionProvider { ox, oy, oz, tx, ty, tz ->
        val minX = minOf(ox, tx); val maxX = maxOf(ox, tx)
        minX < 4.5f && maxX > 4.5f
    }

    @Test
    fun lightPassesThroughOpenSpaceWithOccluder() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        world.getNode(3, 4, 1)!!.setTile(FloorTile())

        val player = playerWithTorch(world, 4, 4, 1)
        // occluder only blocks crossing x=4.5; ray from (4,4,1) to (3,4,1) does not cross
        val sl = SurfaceLighting.build(world, player, occluder = xWallOccluder)
        val buf = FloatArray(3)
        sl.floor(3, 4, 1, buf)
        assertTrue(bright(buf) > 0.05f, "floor at x=3 should be lit when occluder does not block it, was ${bright(buf)}")
    }

    @Test
    fun lightIsBlockedByStubOccluder() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        world.getNode(6, 4, 1)!!.setTile(FloorTile())

        val player = playerWithTorch(world, 4, 4, 1)
        // Ray from (4,4,1) to (6,4,~0.5) crosses x=4.5 → occluder blocks it
        val sl = SurfaceLighting.build(world, player, occluder = xWallOccluder)
        val buf = FloatArray(3)
        sl.floor(6, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "floor on far side of stub occluder wall should be dark")
    }

    @Test
    fun nullOccluderFallsBackToGridDda() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(WallEastTile())
        world.getNode(5, 4, 1)!!.setTile(FloorTile())

        val player = playerWithTorch(world, 4, 4, 1)
        // null occluder uses the existing grid DDA — wall should still block
        val sl = SurfaceLighting.build(world, player, occluder = null)
        val buf = FloatArray(3)
        sl.floor(5, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "grid DDA must still block light through a wall when occluder is null")
    }

    @Test
    fun occluderOverridesGridDdaWhenProvided() {
        // No grid walls — but the stub occluder blocks the ray crossing x=4.5.
        // This verifies the occluder path is taken instead of the DDA.
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        world.getNode(6, 4, 1)!!.setTile(FloorTile())

        val player = playerWithTorch(world, 4, 4, 1)
        val sl = SurfaceLighting.build(world, player, occluder = xWallOccluder)
        val buf = FloatArray(3)
        sl.floor(6, 4, 1, buf)
        // DDA would say lit (no wall in grid), but occluder blocks it
        assertEquals(0f, bright(buf), 1e-3f, "occluder (not DDA) should determine visibility when non-null")
    }
}
