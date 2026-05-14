package com.roguelike.rendering

import com.roguelike.rendering.vulkan.VulkanContext
import com.roguelike.rendering.vulkan.VulkanMesh
import com.roguelike.rendering.vulkan.VulkanTexture
import com.roguelike.rendering.vulkan.VertexFormat
import com.roguelike.utils.AssetLoader as BaseAssetLoader
import com.roguelike.utils.MeshData

/**
 * Rendering-level asset loader that creates VulkanMesh and VulkanTexture
 * resources from raw model/texture data.
 */
class RenderingAssetLoader(
    private val context: VulkanContext
) : AutoCloseable {

    private val baseLoader = BaseAssetLoader()
    private val meshCache = mutableMapOf<String, VulkanMesh>()
    private val textureCache = mutableMapOf<String, VulkanTexture>()

    /** Default white 1x1 texture for untextured geometry. */
    val defaultTexture: VulkanTexture by lazy {
        VulkanTexture.createSolidColor(context, 1f, 1f, 1f, 1f)
    }

    /**
     * Load a model file and create a GPU-resident VulkanMesh.
     */
    fun loadMesh(name: String, path: String): VulkanMesh {
        meshCache[name]?.let { return it }
        val meshData = baseLoader.loadModel(name, path)
        val mesh = VulkanMesh.create(
            context.allocator,
            context.vkDevice,
            VertexFormat.POSITION_NORMAL,
            meshData.vertices,
            meshData.indices
        )
        meshCache[name] = mesh
        return mesh
    }

    /**
     * Create a VulkanMesh from raw vertex/index data.
     */
    fun createMesh(
        name: String,
        format: VertexFormat,
        vertices: FloatArray,
        indices: ShortArray
    ): VulkanMesh {
        meshCache[name]?.let { return it }
        val mesh = VulkanMesh.create(context.allocator, context.vkDevice, format, vertices, indices)
        meshCache[name] = mesh
        return mesh
    }

    /**
     * Load a texture from file and create a GPU-resident VulkanTexture.
     */
    fun loadTexture(name: String, path: String): VulkanTexture {
        textureCache[name]?.let { return it }
        val texture = try {
            VulkanTexture.loadFromFile(context, path)
        } catch (e: Exception) {
            System.err.println("WARNING: Failed to load texture '$path': ${e.message}. Using fallback.")
            VulkanTexture.createSolidColor(context, 1f, 0f, 1f, 1f) // magenta fallback
        }
        textureCache[name] = texture
        return texture
    }

    fun getMesh(name: String): VulkanMesh? = meshCache[name]
    fun getTexture(name: String): VulkanTexture? = textureCache[name]
    fun getMeshData(name: String): MeshData? = baseLoader.getModel(name)

    override fun close() {
        meshCache.values.forEach { it.close() }
        meshCache.clear()
        textureCache.values.forEach { it.close() }
        textureCache.clear()
        baseLoader.dispose()
    }
}


