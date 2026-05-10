package com.roguelike.rendering

import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null
) {

    fun render(world: World, batch: ModelBatch, environment: Environment, maxZ: Int = world.depth - 1) {
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..maxZ.coerceAtMost(world.depth - 1)) {
                    val node = world.getNode(x, y, z) ?: continue

                    node.tiles.forEach {
                        tileRenderer.render(it, batch, environment, x.toFloat(), y.toFloat(), z.toFloat())
                    }

                    itemRenderer?.let { renderer ->
                        node.items.forEach { item ->
                            renderer.render(item, batch, environment, x.toFloat(), y.toFloat(), z.toFloat())
                        }
                    }
                }
            }
        }
    }
}
