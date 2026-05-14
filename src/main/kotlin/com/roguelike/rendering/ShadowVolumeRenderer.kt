package com.roguelike.rendering

import com.roguelike.rendering.vulkan.*
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

/**
 * Vulkan shadow volume renderer. Records command buffer draw calls using
 * the 4 pipeline variants: ambient, stencil-front, stencil-back, lit.
 *
 * Rendering flow per frame:
 * 1. Ambient pass: bind ambient pipeline → push model matrix → draw scene meshes
 * 2. Per-light:
 *    a. Stencil pass: bind stencil-front → draw shadow volumes → bind stencil-back → draw shadow volumes
 *    b. Lit pass: bind lit pipeline (stencil test EQUAL 0, additive blend) → update light UBO → draw scene meshes
 */
class ShadowVolumeRenderer(
    private val context: VulkanContext,
    private val descriptorSetLayout: Long
) : AutoCloseable {

    private val builder = ShadowVolumeBuilder()

    // UBO buffers
    private var sceneUboBuffer: Long = VK_NULL_HANDLE
    private var sceneUboAlloc: Long = VK_NULL_HANDLE
    private var lightUboBuffer: Long = VK_NULL_HANDLE
    private var lightUboAlloc: Long = VK_NULL_HANDLE
    private var materialUboBuffer: Long = VK_NULL_HANDLE
    private var materialUboAlloc: Long = VK_NULL_HANDLE

    // Descriptor pool and set
    private var descriptorPool: Long = VK_NULL_HANDLE
    private var descriptorSet: Long = VK_NULL_HANDLE

    // Occluder SSBO for per-pixel shadow ray-marching
    private var occluderSsboBuffer: Long = VK_NULL_HANDLE
    private var occluderSsboAlloc: Long = VK_NULL_HANDLE
    private var occluderSsboSize: Long = 0
    private val MAX_OCCLUDER_TRIANGLES = 8192 // Max triangles in SSBO

    // Shadow volume mesh (reusable, dynamically updated)
    private var shadowMesh: VulkanMesh? = null

    // Pipelines (set externally before rendering)
    var ambientPipeline: RenderPipeline? = null
    var stencilFrontPipeline: RenderPipeline? = null
    var stencilBackPipeline: RenderPipeline? = null
    var litPipeline: RenderPipeline? = null

    // Identity matrix for push constants
    private val identityMatrix = Matrix4f()
    private val matrixBuffer = FloatArray(16)

    init {
        createUboBuffers()
        createDescriptorPool()
        allocateDescriptorSet()
        updateDescriptorSet()
    }

    private fun createUboBuffers() {
        // SceneUBO: mat4 (64) + vec3 (12) + pad (4) = 80 bytes
        sceneUboBuffer = createUboBuffer(80)
        sceneUboAlloc = lastAlloc

        // LightUBO: vec3 (12) + float (4) + vec4 (16) + float (4) + pad (12) = 48 bytes
        lightUboBuffer = createUboBuffer(48)
        lightUboAlloc = lastAlloc

        // MaterialUBO: vec4 (16) + vec4 (16) + vec4 (16) = 48 bytes
        materialUboBuffer = createUboBuffer(48)
        materialUboAlloc = lastAlloc

        // OccluderSSBO: 16 bytes header (triangleCount + 3 padding uints) + 3 * vec4(16) per triangle
        occluderSsboSize = (16 + MAX_OCCLUDER_TRIANGLES * 3 * 16).toLong()
        occluderSsboBuffer = createSsboBuffer(occluderSsboSize.toInt())
        occluderSsboAlloc = lastAlloc
    }

    private var lastAlloc: Long = 0

    private fun createUboBuffer(size: Int): Long {
        MemoryStack.stackPush().use { stack ->
            val bufferCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size.toLong())
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)

            val pBuffer = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufferCI, allocCI, pBuffer, pAlloc, null) == VK_SUCCESS)
            lastAlloc = pAlloc.get(0)
            return pBuffer.get(0)
        }
    }

    private fun createSsboBuffer(size: Int): Long {
        MemoryStack.stackPush().use { stack ->
            val bufferCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size.toLong())
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)

            val pBuffer = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufferCI, allocCI, pBuffer, pAlloc, null) == VK_SUCCESS)
            lastAlloc = pAlloc.get(0)
            return pBuffer.get(0)
        }
    }

    private fun createDescriptorPool() {
        MemoryStack.stackPush().use { stack ->
            val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
            poolSizes.get(0)
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(3)
            poolSizes.get(1)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)

            val poolCI = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes)
                .maxSets(1)

            val pPool = stack.mallocLong(1)
            check(vkCreateDescriptorPool(context.vkDevice, poolCI, null, pPool) == VK_SUCCESS)
            descriptorPool = pPool.get(0)
        }
    }

    private fun allocateDescriptorSet() {
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout))

            val pSet = stack.mallocLong(1)
            check(vkAllocateDescriptorSets(context.vkDevice, allocInfo, pSet) == VK_SUCCESS)
            descriptorSet = pSet.get(0)
        }
    }

    private fun updateDescriptorSet() {
        MemoryStack.stackPush().use { stack ->
            val writes = VkWriteDescriptorSet.calloc(4, stack)

            val sceneBI = VkDescriptorBufferInfo.calloc(1, stack)
            sceneBI.get(0).buffer(sceneUboBuffer).offset(0).range(80)
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(sceneBI)

            val lightBI = VkDescriptorBufferInfo.calloc(1, stack)
            lightBI.get(0).buffer(lightUboBuffer).offset(0).range(48)
            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(lightBI)

            val materialBI = VkDescriptorBufferInfo.calloc(1, stack)
            materialBI.get(0).buffer(materialUboBuffer).offset(0).range(48)
            writes.get(2)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(materialBI)

            val occluderBI = VkDescriptorBufferInfo.calloc(1, stack)
            occluderBI.get(0).buffer(occluderSsboBuffer).offset(0).range(occluderSsboSize)
            writes.get(3)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(occluderBI)

            vkUpdateDescriptorSets(context.vkDevice, writes, null)
        }
    }

    private fun updateSceneUbo(camera: Camera) {
        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, sceneUboAlloc, ppData)
            val buf = ppData.getByteBuffer(0, 80).asFloatBuffer()

            camera.viewProjection.get(matrixBuffer)
            buf.put(matrixBuffer)
            buf.put(camera.position.x)
            buf.put(camera.position.y)
            buf.put(camera.position.z)
            buf.put(0f)

            vmaUnmapMemory(context.allocator, sceneUboAlloc)
        }
    }

    private fun updateLightUbo(light: PointLightData) {
        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, lightUboAlloc, ppData)
            val buf = ppData.getByteBuffer(0, 48).asFloatBuffer()

            buf.put(light.position.x)
            buf.put(light.position.y)
            buf.put(light.position.z)
            buf.put(light.intensity)
            buf.put(light.color.x)
            buf.put(light.color.y)
            buf.put(light.color.z)
            buf.put(light.color.w)
            buf.put(light.radius)
            buf.put(0f)
            buf.put(0f)
            buf.put(0f)

            vmaUnmapMemory(context.allocator, lightUboAlloc)
        }
    }

    fun updateMaterialUbo(
        diffuse: FloatArray = floatArrayOf(0.8f, 0.8f, 0.8f, 1f),
        emissive: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        ambient: FloatArray = floatArrayOf(0.15f, 0.15f, 0.15f, 1f)
    ) {
        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, materialUboAlloc, ppData)
            val buf = ppData.getByteBuffer(0, 48).asFloatBuffer()
            buf.put(diffuse)
            buf.put(emissive)
            buf.put(ambient)
            vmaUnmapMemory(context.allocator, materialUboAlloc)
        }
    }

    private fun pushModelMatrix(commandBuffer: VkCommandBuffer, pipeline: RenderPipeline, modelMatrix: Matrix4f) {
        MemoryStack.stackPush().use { stack ->
            val buf = stack.mallocFloat(16)
            modelMatrix.get(buf)
            vkCmdPushConstants(commandBuffer, pipeline.layout, VK_SHADER_STAGE_VERTEX_BIT, 0, buf)
        }
    }

    /**
     * Upload occluder triangles to the SSBO for per-pixel shadow ray-marching.
     * Layout: [triangleCount(uint), pad, pad, pad, v0(vec4), v1(vec4), v2(vec4), ...]
     */
    private fun updateOccluderSsbo(occluders: List<List<ShadowVolumeBuilder.Triangle>>) {
        val allTriangles = occluders.flatten()
        val count = minOf(allTriangles.size, MAX_OCCLUDER_TRIANGLES)

        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, occluderSsboAlloc, ppData)
            val byteSize = (16 + count * 3 * 16) // header + triangles
            val buf = ppData.getByteBuffer(0, byteSize)

            // Header: triangleCount as uint32 + 3 padding uint32s
            buf.putInt(count)
            buf.putInt(0)
            buf.putInt(0)
            buf.putInt(0)

            // Each triangle: 3 x vec4 (xyz + w=0 padding)
            val floatBuf = buf.asFloatBuffer()
            for (i in 0 until count) {
                val tri = allTriangles[i]
                // v0
                floatBuf.put(tri.v0.x); floatBuf.put(tri.v0.y); floatBuf.put(tri.v0.z); floatBuf.put(0f)
                // v1
                floatBuf.put(tri.v1.x); floatBuf.put(tri.v1.y); floatBuf.put(tri.v1.z); floatBuf.put(0f)
                // v2
                floatBuf.put(tri.v2.x); floatBuf.put(tri.v2.y); floatBuf.put(tri.v2.z); floatBuf.put(0f)
            }

            vmaUnmapMemory(context.allocator, occluderSsboAlloc)
        }
    }

    /**
     * Record the full shadow volume rendering pipeline into the command buffer.
     *
     * @param commandBuffer Active command buffer (render pass already begun)
     * @param camera Current camera
     * @param lights List of active point lights
     * @param sceneMeshes Scene geometry meshes with their model matrices
     * @param occluders Triangle lists for shadow volume construction
     */
    fun render(
        commandBuffer: VkCommandBuffer,
        camera: Camera,
        lights: List<PointLightData>,
        sceneMeshes: List<SceneMeshEntry>,
        occluders: List<List<ShadowVolumeBuilder.Triangle>>
    ) {
        val ambient = ambientPipeline ?: return
        val stencilFront = stencilFrontPipeline ?: return
        val stencilBack = stencilBackPipeline ?: return
        val lit = litPipeline ?: return

        updateSceneUbo(camera)
        updateMaterialUbo()
        updateOccluderSsbo(occluders)

        // Bind descriptor set
        MemoryStack.stackPush().use { stack ->
            vkCmdBindDescriptorSets(
                commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                ambient.layout, 0,
                stack.longs(descriptorSet), null
            )
        }

        // === AMBIENT PASS ===
        ambient.bind(commandBuffer)
        for (entry in sceneMeshes) {
            pushModelMatrix(commandBuffer, ambient, entry.modelMatrix)
            entry.mesh.bind(commandBuffer)
            entry.mesh.draw(commandBuffer)
        }

        // === PER-LIGHT PASSES ===
        for (light in lights) {
            updateLightUbo(light)

            // Build shadow volumes
            val shadowVolumeMeshes = occluders.mapNotNull { triangles ->
                val svMesh = builder.buildShadowVolume(triangles, light.position)
                if (svMesh.indexCount == 0) null else svMesh
            }

            // --- Stencil front-face pass ---
            stencilFront.bind(commandBuffer)
            MemoryStack.stackPush().use { stack ->
                vkCmdBindDescriptorSets(
                    commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    stencilFront.layout, 0,
                    stack.longs(descriptorSet), null
                )
            }
            for (svMesh in shadowVolumeMeshes) {
                val mesh = getOrCreateShadowMesh(svMesh)
                pushModelMatrix(commandBuffer, stencilFront, identityMatrix)
                mesh.bind(commandBuffer)
                mesh.draw(commandBuffer)
            }

            // --- Stencil back-face pass ---
            stencilBack.bind(commandBuffer)
            MemoryStack.stackPush().use { stack ->
                vkCmdBindDescriptorSets(
                    commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    stencilBack.layout, 0,
                    stack.longs(descriptorSet), null
                )
            }
            for (svMesh in shadowVolumeMeshes) {
                val mesh = getOrCreateShadowMesh(svMesh)
                pushModelMatrix(commandBuffer, stencilBack, identityMatrix)
                mesh.bind(commandBuffer)
                mesh.draw(commandBuffer)
            }

            // --- Lit pass ---
            lit.bind(commandBuffer)
            MemoryStack.stackPush().use { stack ->
                vkCmdBindDescriptorSets(
                    commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    lit.layout, 0,
                    stack.longs(descriptorSet), null
                )
            }
            for (entry in sceneMeshes) {
                pushModelMatrix(commandBuffer, lit, entry.modelMatrix)
                entry.mesh.bind(commandBuffer)
                entry.mesh.draw(commandBuffer)
            }
        }
    }

    private fun getOrCreateShadowMesh(svMesh: ShadowVolumeMesh): VulkanMesh {
        val mesh = shadowMesh ?: VulkanMesh(
            context.allocator, context.vkDevice, VertexFormat.POSITION,
            maxVertices = 65535, maxIndices = 65535
        ).also { shadowMesh = it }

        mesh.updateVertices(svMesh.vertices, svMesh.vertices.size)
        mesh.updateIndices(svMesh.indices, svMesh.indices.size)
        return mesh
    }

    override fun close() {
        shadowMesh?.close()
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(context.vkDevice, descriptorPool, null)
        }
        if (sceneUboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, sceneUboBuffer, sceneUboAlloc)
        if (lightUboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, lightUboBuffer, lightUboAlloc)
        if (materialUboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, materialUboBuffer, materialUboAlloc)
        if (occluderSsboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, occluderSsboBuffer, occluderSsboAlloc)
    }
}

/**
 * A mesh with its model transform, for scene rendering.
 */
data class SceneMeshEntry(
    val mesh: VulkanMesh,
    val modelMatrix: Matrix4f
)
