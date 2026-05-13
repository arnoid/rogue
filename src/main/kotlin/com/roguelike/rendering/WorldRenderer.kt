package com.roguelike.rendering

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.lighting.GpuLightEnvironment
import com.roguelike.world.BaseTile
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {

    /**
     * GPU lighting path: executes the full two-pass shadow pipeline via [shadowRenderer].
     *
     * The [renderScene] callback inside [ShadowRenderer.render] is invoked twice:
     *   1. Depth pass (when a directional or point light is configured).
     *   2. Main lit pass with all active lights and shadow maps.
     */
    fun render(
        world: World,
        camera: Camera,
        shadowRenderer: ShadowRenderer,
        gpuLightEnv: GpuLightEnvironment,
        maxZ: Int = world.depth - 1
    ) {
        shadowRenderer.render(camera, gpuLightEnv) { batch, env ->
            renderGeometry(world, batch, env, maxZ)
        }
    }

    /** Editor path: renders into a caller-managed [ModelBatch] without the shadow pipeline. */
    fun render(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        maxZ: Int = world.depth - 1
    ) {
        renderGeometry(world, batch, environment, maxZ)
    }

    private fun renderGeometry(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        maxZ: Int
    ) {
        val clampedZ = maxZ.coerceAtMost(world.depth - 1)
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..clampedZ) {
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
            for (prop in world.props) {
                if (prop.z <= clampedZ + 1) {
                    renderer.render(prop, batch, environment)
                }
            }
        }
    }
}
