package com.roguelike.core

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.Player
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.setLit
import com.roguelike.core.systems.SurfaceLighting
import com.roguelike.utils.ItemCatalogLoader
import com.roguelike.world.FloorTile
import com.roguelike.world.WallEastTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SurfaceLightingTest {

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())
    }

    private fun build(world: World, player: Player): SurfaceLighting =
        SurfaceLighting.build(world, player)

    private fun bright(arr: FloatArray): Float = maxOf(arr[0], maxOf(arr[1], arr[2]))

    @Test
    fun floorSurfaceIsLitByTorchAtSameCell() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(4f, 4f, 1f)
            it.inventory.add(torch)
        }
        val sl = build(world, player)
        val buf = FloatArray(3)
        sl.floor(4, 4, 1, buf)
        assertTrue(bright(buf) > 0.1f, "floor under the player should be brightly lit")
    }

    @Test
    fun floorBeyondWallIsNotLit() {
        val world = World(9, 9, 3)
        // Wall on east edge of player cell.
        world.getNode(4, 4, 1)!!.setTile(WallEastTile())
        world.getNode(5, 4, 1)!!.setTile(FloorTile())
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(4f, 4f, 1f)
            it.inventory.add(torch)
        }
        val sl = build(world, player)
        val buf = FloatArray(3)
        sl.floor(5, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "floor on the far side of a wall should be dark")
    }

    @Test
    fun wallFacingLightIsLit_floorBeyondWallIsNot() {
        val world = World(9, 9, 3)
        // Wall on east edge of cell (5,4) — sits between (5,4) and (6,4).
        val targetCell = world.getNode(5, 4, 1)!!
        targetCell.setTile(WallEastTile())
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(4f, 4f, 1f)
            it.inventory.add(torch)
        }
        val sl = build(world, player)
        val buf = FloatArray(3)

        // The wall's face is on cell (5,4); the player can see it directly so
        // it should be lit.
        sl.wall(5, 4, 1, TileSlot.WALL_EAST, buf)
        assertTrue(bright(buf) > 0.05f, "wall face that the player can see should be lit")

        // The wall blocks visibility to floor beyond it on cell (6,4).
        sl.floor(6, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "floor on the far side of the wall should be dark")
    }

    @Test
    fun candleConeOnlyLitsForwardFloor() {
        val world = World(15, 15, 3)
        for (x in 0 until 15) for (y in 0 until 15) world.getNode(x, y, 1)!!.setTile(FloorTile())

        val candle = ItemFactory.create("Candle")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(7f, 7f, 1f)
            it.facingDirection.set(0f, 1f, 0f) // north
            it.inventory.add(candle)
        }
        val sl = build(world, player)
        val buf = FloatArray(3)

        // In front (north)
        sl.floor(7, 10, 1, buf)
        assertTrue(bright(buf) > 0.1f, "floor north of player should be inside candle cone")

        // Behind (south)
        sl.floor(7, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "floor south of player should be outside cone")

        // Directly east (on 90° cone boundary)
        sl.floor(10, 7, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "floor due east is outside 90° forward cone")
    }

    @Test
    fun droppedLitItemLightsSurfacesFromItsCellWithStoredFacing() {
        val world = World(15, 15, 3)
        for (x in 0 until 15) for (y in 0 until 15) world.getNode(x, y, 1)!!.setTile(FloorTile())

        val candle = ItemFactory.create("Candle")!!.also {
            it.setLit(true)
            it.facingX = 1f
            it.facingY = 0f
        }
        world.getNode(7, 7, 1)!!.items.add(candle)

        // Player elsewhere with empty inventory.
        val player = Player().also { it.position.set(7f, 7f, 1f) }
        val sl = build(world, player)
        val buf = FloatArray(3)

        // East of the candle — inside the cone.
        sl.floor(10, 7, 1, buf)
        assertTrue(bright(buf) > 0.05f, "dropped candle should light cell east of itself")

        // North of the candle — outside the eastward cone.
        sl.floor(7, 10, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "dropped candle should not light cell north of itself")
    }

    @Test
    fun unlitInventoryProducesNoLight() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        val torch = ItemFactory.create("Torch")!! // unlit
        val player = Player().also {
            it.position.set(4f, 4f, 1f)
            it.inventory.add(torch)
        }
        val sl = build(world, player)
        val buf = FloatArray(3)
        sl.floor(4, 4, 1, buf)
        assertEquals(0f, bright(buf), 1e-3f, "no lit items → no light anywhere")
    }
}


