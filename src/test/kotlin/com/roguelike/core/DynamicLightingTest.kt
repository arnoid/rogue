package com.roguelike.core

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.Player
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.setLit
import com.roguelike.core.systems.DynamicLighting
import com.roguelike.utils.ItemCatalogLoader
import com.roguelike.world.FloorTile
import com.roguelike.world.WallEastTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicLightingTest {

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())
    }

    /**
     * Reproduces the user's diagram. Light at (0,0). A vertical wall stack runs
     * along the east side of column x=0 between y=0..3 (so the wall is east
     * edge of (0,y)). At y=3 the wall ends — so cell (1,3) shares an open
     * boundary with (0,3) at its south side via the corner around (1,2).
     *
     * The cell (1,3) center is occluded from (0,0) — a ray from (0.5,0.5) to
     * (1.5,3.5) crosses the wall on (0,1)'s east edge. But the corner of (1,3)
     * adjacent to (0,3) is reachable diagonally past the wall's end. So the
     * floor surface should still be marked lit (multi-sample LOS finds at
     * least one reachable corner).
     */
    @Test
    fun floorWithReachableCornerIsLit_evenIfCenterIsOccluded() {
        val world = World(9, 9, 3)
        // Place floors so the assertion targets exist.
        for (x in 0..3) for (y in 0..3) world.getNode(x, y, 1)!!.setTile(FloorTile())
        // East wall on cells (0,0), (0,1), (0,2) — wall ends; (0,3) has no east wall.
        for (y in 0..2) world.getNode(0, y, 1)!!.setTile(WallEastTile())

        // Lit torch carried by player at cell (0,0).
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(0f, 0f, 1f)
            it.inventory.add(torch)
        }
        val dl = DynamicLighting.build(world, player)

        // The light's own cell.
        assertEnvHasLight(dl.environmentForFloor(0, 0, 1), expected = true)
        // Floors east of the wall: centers occluded, but cells (1,0)..(1,2) are
        // entirely behind the wall — no sample point is reachable, so unlit.
        assertEnvHasLight(dl.environmentForFloor(1, 0, 1), expected = false)
        assertEnvHasLight(dl.environmentForFloor(1, 1, 1), expected = false)
        assertEnvHasLight(dl.environmentForFloor(1, 2, 1), expected = false)
        // Cell (1,3): center occluded by the wall stretch, but the south-west
        // corner is reachable around the wall's end. Multi-sample LOS should
        // mark this floor as lit.
        assertEnvHasLight(dl.environmentForFloor(1, 3, 1), expected = true)
    }

    @Test
    fun wallFaceFacingLightIsLit_oppositeFaceIsAlsoConsideredLit_butShadowedByItself() {
        val world = World(9, 9, 3)
        for (x in 0..2) for (y in 0..2) world.getNode(x, y, 1)!!.setTile(FloorTile())
        // Wall on east of (1,1).
        world.getNode(1, 1, 1)!!.setTile(WallEastTile())
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        val player = Player().also {
            it.position.set(0f, 1f, 1f) // west of the wall
            it.inventory.add(torch)
        }
        val dl = DynamicLighting.build(world, player)

        // The wall face on (1,1) (east-edge of cell 1,1) — owning cell is (1,1)
        // which the light reaches freely; surface is therefore marked lit.
        assertEnvHasLight(dl.environmentForWall(1, 1, 1, TileSlot.WALL_EAST), expected = true)
        // The floor of (2,1) lies beyond the wall — its samples are all behind
        // the wall edge → unlit.
        assertEnvHasLight(dl.environmentForFloor(2, 1, 1), expected = false)
    }

    private fun assertEnvHasLight(env: com.badlogic.gdx.graphics.g3d.Environment, expected: Boolean) {
        // Environment.directionalLights / pointLights / spotLights are private
        // arrays exposed via has(...)? Easiest: check that a PointLight or
        // SpotLight was added.
        val hasPoint = env.has(com.badlogic.gdx.graphics.g3d.attributes.PointLightsAttribute.Type)
        val hasSpot  = env.has(com.badlogic.gdx.graphics.g3d.attributes.SpotLightsAttribute.Type)
        val any = hasPoint || hasSpot
        if (expected) assertTrue(any, "expected at least one light in environment")
        else assertFalse(any, "expected no lights in environment")
    }
}



