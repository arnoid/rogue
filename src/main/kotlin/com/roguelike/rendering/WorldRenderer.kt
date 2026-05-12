package com.roguelike.rendering

import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.roguelike.core.model.TileSlot
import com.roguelike.core.systems.DynamicLighting
import com.roguelike.world.BaseTile
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {

    /**
     * @param dynamicLighting optional per-frame GPU-light driver. Each rendered
     *                        surface gets an [Environment] that contains only
     *                        the lights with grid LOS to that surface (sampled
     *                        at multiple points so partially-occluded surfaces
     *                        are still lit). The GPU then handles per-pixel
     *                        attenuation and cone falloff.
     */
    fun render(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        maxZ: Int = world.depth - 1,
        dynamicLighting: DynamicLighting? = null
    ) {
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..maxZ.coerceAtMost(world.depth - 1)) {
                    val node = world.getNode(x, y, z) ?: continue

                    node.tiles.forEach { tile ->
                        val slot = (tile as? BaseTile)?.slot ?: TileSlot.FLOOR
                        val env = if (dynamicLighting != null) envForTile(dynamicLighting, x, y, z, slot)
                                  else environment
                        tileRenderer.render(tile, batch, env, x.toFloat(), y.toFloat(), z.toFloat())
                    }

                    itemRenderer?.let { renderer ->
                        if (node.items.isNotEmpty()) {
                            val env = dynamicLighting?.environmentForCell(x, y, z) ?: environment
                            node.items.forEach { item ->
                                renderer.render(item, batch, env, x.toFloat(), y.toFloat(), z.toFloat())
                            }
                        }
                    }
                }
            }
        }

        // Render props (freely-placed decorations) — per-cell environment.
        propRenderer?.let { renderer ->
            val maxZClamped = maxZ.coerceAtMost(world.depth - 1)
            for (prop in world.props) {
                if (prop.z <= maxZClamped + 1) {
                    val cx = Math.round(prop.x)
                    val cy = Math.round(prop.y)
                    val cz = Math.round(prop.z)
                    val env = dynamicLighting?.environmentForCell(cx, cy, cz) ?: environment
                    renderer.render(prop, batch, env)
                }
            }
        }
    }

    private fun envForTile(dl: DynamicLighting, x: Int, y: Int, z: Int, slot: TileSlot): Environment =
        when (slot) {
            TileSlot.FLOOR -> dl.environmentForFloor(x, y, z)
            TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH,
            TileSlot.WALL_EAST,  TileSlot.WALL_WEST -> dl.environmentForWall(x, y, z, slot)
            else -> dl.environmentForCell(x, y, z)
        }
}
