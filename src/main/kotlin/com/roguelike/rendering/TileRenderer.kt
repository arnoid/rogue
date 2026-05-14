package com.roguelike.rendering

import com.roguelike.core.model.Tile
import com.roguelike.rendering.vulkan.VulkanMesh
import com.roguelike.world.*
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * Renders tiles using Vulkan mesh-based rendering.
 * Collects VulkanMesh entries with model matrices for the shadow volume pipeline.
 */
class TileRenderer(private val registry: TileRenderRegistry) {

    fun render(
        tile: Tile,
        x: Float, y: Float, z: Float,
        ignoreYRotation: Boolean = false,
        tint: Vector4f? = null
    ) {
        if (tile !is BaseTile) return
        val renderData = registry[tile] ?: return
        // Rendering is now handled via collectMesh + ShadowVolumeRenderer
    }

    /**
     * Collect a mesh entry for this tile into the provided list.
     * The model matrix encodes position and rotation.
     */
    fun collectMesh(
        tile: Tile,
        x: Float, y: Float, z: Float,
        entries: MutableList<SceneMeshEntry>
    ) {
        if (tile !is BaseTile) return
        val renderData = registry[tile] ?: return

        // The model in TileRenderData is expected to be a VulkanMesh once
        // the asset loading pipeline is integrated
        val mesh = renderData.model as? VulkanMesh ?: return

        val modelMatrix = Matrix4f().translation(
            x + tile.xOffset,
            y + tile.yOffset,
            z + tile.zOffset
        )

        // Apply rotations
        modelMatrix.rotateX(Math.toRadians(tile.rotationX.toDouble()).toFloat())
        modelMatrix.rotateY(Math.toRadians(tile.rotationY.toDouble()).toFloat())
        modelMatrix.rotateZ(Math.toRadians(tile.rotationZ.toDouble()).toFloat())

        // Apply scale
        val sx = renderData.scaleX ?: renderData.scale
        val sy = renderData.scaleY ?: renderData.scale
        val sz = renderData.scaleZ ?: renderData.scale
        modelMatrix.scale(sx, sy, sz)

        entries.add(SceneMeshEntry(mesh, modelMatrix))
    }
}

