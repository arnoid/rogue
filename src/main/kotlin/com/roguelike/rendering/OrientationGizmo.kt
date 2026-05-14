package com.roguelike.rendering

import com.roguelike.rendering.vulkan.RenderPipeline
import com.roguelike.rendering.vulkan.VulkanMesh
import com.roguelike.rendering.vulkan.VertexFormat
import com.roguelike.rendering.vulkan.VulkanContext
import org.joml.Matrix4f
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * Orientation gizmo widget rendered using Vulkan LINE_DEBUG pipeline.
 * Draws X/Y/Z axes as colored lines in a corner of the viewport.
 */
class OrientationGizmo(
    private val mainCamera: Camera,
    private val onReset: () -> Unit
) {
    private var lineMesh: VulkanMesh? = null

    /**
     * Record gizmo draw commands into the command buffer.
     * Uses the LINE_DEBUG pipeline for line rendering.
     */
    fun render() {
        // TODO: Integrate with LINE_DEBUG pipeline when available
        // This requires a separate pipeline bind and line vertex data
    }

    /**
     * Record gizmo draw commands with explicit command buffer and pipeline.
     */
    fun render(commandBuffer: VkCommandBuffer, lineDebugPipeline: RenderPipeline?) {
        val pipeline = lineDebugPipeline ?: return
        val mesh = lineMesh ?: return

        pipeline.bind(commandBuffer)
        mesh.bind(commandBuffer)
        mesh.draw(commandBuffer)
    }

    fun dispose() {
        lineMesh?.close()
        lineMesh = null
    }
}
