package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

/**
 * Vertex format types for different geometry needs.
 */
enum class VertexFormat(val stride: Int, val floatsPerVertex: Int) {
    POSITION(12, 3),                // xyz
    POSITION_NORMAL(24, 6),          // xyz + nxnynz
    POSITION_NORMAL_UV(32, 8)        // xyz + nxnynz + uv
}

/**
 * GPU-resident geometry data managed via VMA.
 * Supports dynamic vertex/index updates for shadow volumes.
 */
class VulkanMesh(
    private val allocator: Long,
    private val device: VkDevice,
    val vertexFormat: VertexFormat,
    maxVertices: Int,
    maxIndices: Int
) : AutoCloseable {

    var vertexBuffer: Long = VK_NULL_HANDLE; private set
    var vertexAllocation: Long = VK_NULL_HANDLE; private set
    var indexBuffer: Long = VK_NULL_HANDLE; private set
    var indexAllocation: Long = VK_NULL_HANDLE; private set
    var vertexCount: Int = 0; private set
    var indexCount: Int = 0; private set

    private var maxVertexBytes: Int = maxVertices * vertexFormat.stride
    private var maxIndexBytes: Int = maxIndices * 2 // Short indices

    init {
        createVertexBuffer(maxVertexBytes)
        createIndexBuffer(maxIndexBytes)
    }

    private fun createVertexBuffer(size: Int) {
        MemoryStack.stackPush().use { stack ->
            val bufferCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size.toLong())
                .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)

            val pBuffer = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(allocator, bufferCI, allocCI, pBuffer, pAlloc, null) == VK_SUCCESS)
            vertexBuffer = pBuffer.get(0)
            vertexAllocation = pAlloc.get(0)
        }
    }

    private fun createIndexBuffer(size: Int) {
        MemoryStack.stackPush().use { stack ->
            val bufferCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size.toLong())
                .usage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)

            val pBuffer = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(allocator, bufferCI, allocCI, pBuffer, pAlloc, null) == VK_SUCCESS)
            indexBuffer = pBuffer.get(0)
            indexAllocation = pAlloc.get(0)
        }
    }

    /**
     * Upload vertex data. count = number of floats in data to use.
     */
    fun updateVertices(data: FloatArray, count: Int) {
        vertexCount = count / vertexFormat.floatsPerVertex
        val byteSize = count * 4
        if (byteSize > maxVertexBytes) {
            vmaDestroyBuffer(allocator, vertexBuffer, vertexAllocation)
            maxVertexBytes = (byteSize * 1.5f).toInt()
            createVertexBuffer(maxVertexBytes)
        }

        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(allocator, vertexAllocation, ppData)
            val mapped = ppData.getByteBuffer(0, byteSize)
            mapped.asFloatBuffer().put(data, 0, count)
            vmaUnmapMemory(allocator, vertexAllocation)
        }
    }

    /**
     * Upload index data. count = number of shorts in data to use.
     */
    fun updateIndices(data: ShortArray, count: Int) {
        indexCount = count
        val byteSize = count * 2
        if (byteSize > maxIndexBytes) {
            vmaDestroyBuffer(allocator, indexBuffer, indexAllocation)
            maxIndexBytes = (byteSize * 1.5f).toInt()
            createIndexBuffer(maxIndexBytes)
        }

        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(allocator, indexAllocation, ppData)
            val mapped = ppData.getByteBuffer(0, byteSize)
            mapped.asShortBuffer().put(data, 0, count)
            vmaUnmapMemory(allocator, indexAllocation)
        }
    }

    /**
     * Bind vertex and index buffers to command buffer.
     */
    fun bind(commandBuffer: VkCommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            val offsets = stack.mallocLong(1)
            offsets.put(0, 0)
            val buffers = stack.mallocLong(1)
            buffers.put(0, vertexBuffer)
            vkCmdBindVertexBuffers(commandBuffer, 0, buffers, offsets)
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT16)
        }
    }

    /**
     * Issue indexed draw call.
     */
    fun draw(commandBuffer: VkCommandBuffer, instanceCount: Int = 1) {
        vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, 0, 0, 0)
    }

    override fun close() {
        if (vertexBuffer != VK_NULL_HANDLE) {
            vmaDestroyBuffer(allocator, vertexBuffer, vertexAllocation)
        }
        if (indexBuffer != VK_NULL_HANDLE) {
            vmaDestroyBuffer(allocator, indexBuffer, indexAllocation)
        }
    }

    companion object {
        /**
         * Create a mesh from vertex and index data.
         */
        fun create(
            allocator: Long,
            device: VkDevice,
            format: VertexFormat,
            vertices: FloatArray,
            indices: ShortArray
        ): VulkanMesh {
            val mesh = VulkanMesh(allocator, device, format, vertices.size / format.floatsPerVertex, indices.size)
            mesh.updateVertices(vertices, vertices.size)
            mesh.updateIndices(indices, indices.size)
            return mesh
        }
    }
}

