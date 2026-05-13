package com.roguelike.rendering

import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {

    fun render(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        maxZ: Int = world.depth - 1
    ) {
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..maxZ.coerceAtMost(world.depth - 1)) {
                    val node = world.getNode(x, y, z) ?: continue

                    node.tiles.forEach { tile ->
                        tileRenderer.render(tile, batch, environment, x.toFloat(), y.toFloat(), z.toFloat())
                    }

                    itemRenderer?.let { renderer ->
                        if (node.items.isNotEmpty()) {
                            node.items.forEach { item ->
                                renderer.render(item, batch, environment, x.toFloat(), y.toFloat(), z.toFloat())
                            }
                        }
                    }
                }
            }
        }

        propRenderer?.let { renderer ->
            val maxZClamped = maxZ.coerceAtMost(world.depth - 1)
            for (prop in world.props) {
                if (prop.z <= maxZClamped + 1) {
                    renderer.render(prop, batch, environment)
                }
            }
        }
    }
}
