package com.roguelike.rendering

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {

    // Reusable scratch objects to avoid per-frame allocation
    private val tmpBB = BoundingBox()
    private val tmpMin = Vector3()
    private val tmpMax = Vector3()

    /**
     * Render world geometry.
     *
     * @param camera  If non-null, only nodes visible in the camera frustum are rendered.
     * @param minZ    Minimum Z level to render (inclusive). Defaults to 0.
     * @param maxZ    Maximum Z level to render (inclusive).
     */
    fun render(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        camera: Camera? = null,
        minZ: Int = 0,
        maxZ: Int = world.depth - 1
    ) {
        val zLo = minZ.coerceIn(0, world.depth - 1)
        val zHi = maxZ.coerceAtMost(world.depth - 1)

        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                // Frustum cull: check if this (x,y) column is visible.
                // We test a bounding box spanning [zLo..zHi] at this column.
                if (camera != null) {
                    tmpMin.set(x - 0.5f, y - 0.5f, zLo - 0.5f)
                    tmpMax.set(x + 0.5f, y + 0.5f, zHi + 0.5f)
                    tmpBB.set(tmpMin, tmpMax)
                    if (!camera.frustum.boundsInFrustum(tmpBB)) continue
                }

                for (z in zLo..zHi) {
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
                val pz = prop.z
                if (pz < zLo - 1 || pz > zHi + 1) continue
                if (camera != null) {
                    tmpMin.set(prop.x - 1f, prop.y - 1f, pz - 1f)
                    tmpMax.set(prop.x + 1f, prop.y + 1f, pz + 1f)
                    tmpBB.set(tmpMin, tmpMax)
                    if (!camera.frustum.boundsInFrustum(tmpBB)) continue
                }
                renderer.render(prop, batch, environment)
            }
        }
    }
}
