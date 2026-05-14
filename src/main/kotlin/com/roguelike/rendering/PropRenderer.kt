package com.roguelike.rendering

import com.roguelike.core.model.Prop
import com.roguelike.rendering.vulkan.VulkanMesh
import com.roguelike.utils.AssetLoader
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * Renders props using Vulkan mesh-based rendering.
 * Collects VulkanMesh entries with model matrices for the shadow volume pipeline.
 */
class PropRenderer(private val assetLoader: AssetLoader) {
    fun render(prop: Prop, selected: Boolean = false, tint: Vector4f? = null) {
        // Rendering is now handled via collectMesh + ShadowVolumeRenderer
    }

    /**
     * Collect a mesh entry for this prop into the provided list.
     */
    fun collectMesh(
        prop: Prop,
        entries: MutableList<SceneMeshEntry>
    ) {
        // Load or retrieve the mesh for this prop's model
        val meshData = assetLoader.getModel(prop.modelPath) ?: return

        // TODO: Convert MeshData to VulkanMesh via RenderingAssetLoader
        // For now, props without a VulkanMesh are skipped
    }

    fun removeFromCache(propId: String) {}
}

