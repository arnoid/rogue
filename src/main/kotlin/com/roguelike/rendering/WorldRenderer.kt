package com.roguelike.rendering

import com.roguelike.world.World
import org.joml.Matrix4f

/**
 * Renders world geometry using Vulkan.
 * Collects scene meshes and occluder triangles, then delegates to ShadowVolumeRenderer.
 */
class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {
    /** Collected scene meshes for the current frame. */
    val sceneMeshes = mutableListOf<SceneMeshEntry>()

    /** Collected occluder triangle lists for shadow volume generation. */
    val occluderTriangles = mutableListOf<List<ShadowVolumeBuilder.Triangle>>()

    /**
     * Collect renderable meshes from the world. Call before recording commands.
     */
    fun collectRenderables(world: World, camera: Camera? = null, minZ: Int = 0, maxZ: Int = world.depth - 1) {
        sceneMeshes.clear()
        occluderTriangles.clear()

        // Iterate visible tiles and collect meshes
        for (z in minZ..maxZ) {
            for (y in 0 until world.height) {
                for (x in 0 until world.width) {
                    val node = world.getNode(x, y, z) ?: continue

                    // Collect all tiles on this node
                    for (tile in node.tiles) {
                        tileRenderer.collectMesh(tile, x.toFloat(), y.toFloat(), z.toFloat(), sceneMeshes)
                    }
                }
            }
        }
    }

    fun render(world: World, camera: Camera? = null, minZ: Int = 0, maxZ: Int = world.depth - 1) {
        collectRenderables(world, camera, minZ, maxZ)
    }
}
