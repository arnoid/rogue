package com.roguelike.rendering

import com.roguelike.core.model.Item
import com.roguelike.rendering.vulkan.VulkanMesh
import com.roguelike.utils.AssetLoader
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * Renders items using Vulkan mesh-based rendering.
 * Collects VulkanMesh entries with model matrices for the shadow volume pipeline.
 */
class ItemRenderer(val assetLoader: AssetLoader) {
    fun render(item: Item, x: Float, y: Float, z: Float, tint: Vector4f? = null) {
        // Rendering is now handled via collectMesh + ShadowVolumeRenderer
    }

    /**
     * Collect a mesh entry for this item into the provided list.
     */
    fun collectMesh(
        item: Item,
        x: Float, y: Float, z: Float,
        entries: MutableList<SceneMeshEntry>
    ) {
        // Load or retrieve the mesh for this item's model
        val meshData = assetLoader.getModel(item.type) ?: return

        // TODO: Convert MeshData to VulkanMesh via RenderingAssetLoader
        // For now, items without a VulkanMesh are skipped
    }
}
