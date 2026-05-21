package com.roguelike.rendering

import com.roguelike.rendering.vulkan.*
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Headless rendering harness for visual tests.
 * Creates a hidden GLFW window + VulkanContext + SwapChain,
 * renders a single frame, reads back pixels via staging buffer + vkCmdCopyImageToBuffer.
 */
class RenderTestHarness(
    val width: Int = 512,
    val height: Int = 512
) {
    private val outputDir = File("build/test-output/rendering")

    @Volatile
    private var ready = false

    private var window: Long = MemoryUtil.NULL
    private var vulkanContext: VulkanContext? = null
    private var swapChain: SwapChain? = null
    private var descriptorSetLayout: Long = VK_NULL_HANDLE
    private var renderer: ShadowVolumeRenderer? = null
    private var commandPool: Long = VK_NULL_HANDLE

    // Pipelines
    private var ambientPipeline: RenderPipeline? = null
    private var stencilFrontPipeline: RenderPipeline? = null
    private var stencilBackPipeline: RenderPipeline? = null
    private var litPipeline: RenderPipeline? = null

    /**
     * Initialize the Vulkan rendering context for offscreen rendering.
     * @return true if initialization succeeded, false if Vulkan is not available.
     */
    fun initialize(): Boolean {
        if (!GLAvailability.isAvailable()) {
            System.err.println("RenderTestHarness: GLFW/Vulkan not available — skipping visual tests")
            return false
        }

        try {
            // Initialize GLFW
            if (!glfwInit()) {
                System.err.println("RenderTestHarness: GLFW init failed")
                return false
            }

            if (!GLFWVulkan.glfwVulkanSupported()) {
                System.err.println("RenderTestHarness: Vulkan not supported")
                glfwTerminate()
                return false
            }

            // Create hidden window
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            window = glfwCreateWindow(width, height, "Test Harness", MemoryUtil.NULL, MemoryUtil.NULL)
            if (window == MemoryUtil.NULL) {
                System.err.println("RenderTestHarness: Failed to create window")
                glfwTerminate()
                return false
            }

            // Create Vulkan context.
            //
            // spec 008 (FPS recovery): force the validation layer on for the
            // duration of this feature so any descriptor-set / binding-table
            // drift introduced by the new tile-quality SSBO is caught loudly
            // at test time. The legacy default (`debug = false`) silenced
            // every `VUID-Vk*` message, which is exactly the failure mode
            // the new binding 5 makes catastrophic. See
            // `specs/008-fps-fov-shadow-culling/contracts/shader-binding-table.md`.
            vulkanContext = VulkanContext.create(window, debug = true)
            val ctx = vulkanContext!!

            // Create swap chain
            val sc = SwapChain(ctx)
            sc.create(width, height)
            swapChain = sc

            // Create command pool
            MemoryStack.stackPush().use { stack ->
                val poolCI = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.graphicsQueueFamily)
                val pPool = stack.mallocLong(1)
                check(vkCreateCommandPool(ctx.vkDevice, poolCI, null, pPool) == VK_SUCCESS)
                commandPool = pPool.get(0)
            }

            // Create descriptor set layout and pipelines
            descriptorSetLayout = RenderPipeline.createDescriptorSetLayout(ctx.vkDevice)

            val extent = sc.extent
            ambientPipeline = RenderPipeline.create(
                ctx.vkDevice, sc.renderPass, descriptorSetLayout,
                PassType.AMBIENT,
                "shaders/ambient_pass.vert.glsl", "shaders/ambient_pass.frag.glsl",
                extent
            )
            stencilFrontPipeline = RenderPipeline.create(
                ctx.vkDevice, sc.renderPass, descriptorSetLayout,
                PassType.STENCIL_FRONT,
                "shaders/shadow_volume.vert.glsl", "shaders/shadow_volume.frag.glsl",
                extent
            )
            stencilBackPipeline = RenderPipeline.create(
                ctx.vkDevice, sc.renderPass, descriptorSetLayout,
                PassType.STENCIL_BACK,
                "shaders/shadow_volume.vert.glsl", "shaders/shadow_volume.frag.glsl",
                extent
            )
            litPipeline = RenderPipeline.create(
                ctx.vkDevice, sc.renderPass, descriptorSetLayout,
                PassType.LIT,
                "shaders/lit_pass.vert.glsl", "shaders/lit_pass.frag.glsl",
                extent
            )

            // Create renderer
            val svr = ShadowVolumeRenderer(ctx, descriptorSetLayout)
            svr.ambientPipeline = ambientPipeline
            svr.stencilFrontPipeline = stencilFrontPipeline
            svr.stencilBackPipeline = stencilBackPipeline
            svr.litPipeline = litPipeline
            renderer = svr

            ready = true
            return true
        } catch (e: Exception) {
            System.err.println("RenderTestHarness: Initialization failed: ${e.message}")
            e.printStackTrace()
            dispose()
            return false
        }
    }

    /**
     * Build, render, save PNG, and return pixel data + scene.
     */
    fun renderAndSave(builder: SceneBuilder, testName: String): Pair<PixelData, TestScene> {
        check(ready) { "RenderTestHarness not initialized" }
        val scene = builder.build()
        val pixelData = renderSceneInternal(scene)
        outputDir.mkdirs()
        saveImage(pixelData, testName)
        return Pair(pixelData, scene)
    }

    fun renderScene(builder: SceneBuilder): PixelData {
        check(ready) { "RenderTestHarness not initialized" }
        val scene = builder.build()
        return renderSceneInternal(scene)
    }

    private fun renderSceneInternal(scene: TestScene): PixelData {
        val ctx = vulkanContext!!
        val sc = swapChain!!
        val svr = renderer!!

        // Create scene meshes from occluder triangles
        val sceneMeshEntries = mutableListOf<SceneMeshEntry>()
        for (triangleList in scene.occluderTriangles) {
            // Convert triangles to position+normal vertex data
            val vertices = mutableListOf<Float>()
            val indices = mutableListOf<Short>()
            var idx: Short = 0
            for (tri in triangleList) {
                val normal = tri.normal()
                // v0
                vertices.addAll(listOf(tri.v0.x, tri.v0.y, tri.v0.z, normal.x, normal.y, normal.z))
                // v1
                vertices.addAll(listOf(tri.v1.x, tri.v1.y, tri.v1.z, normal.x, normal.y, normal.z))
                // v2
                vertices.addAll(listOf(tri.v2.x, tri.v2.y, tri.v2.z, normal.x, normal.y, normal.z))
                indices.add(idx); indices.add((idx + 1).toShort()); indices.add((idx + 2).toShort())
                idx = (idx + 3).toShort()
            }

            if (vertices.isNotEmpty()) {
                val mesh = VulkanMesh.create(
                    ctx.allocator, ctx.vkDevice,
                    VertexFormat.POSITION_NORMAL,
                    vertices.toFloatArray(),
                    indices.toShortArray()
                )
                sceneMeshEntries.add(SceneMeshEntry(mesh, Matrix4f()))
            }
        }

        // Acquire image, render, read back
        MemoryStack.stackPush().use { stack ->
            // Create semaphore for acquire
            val semCI = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            val pSem = stack.mallocLong(1)
            vkCreateSemaphore(ctx.vkDevice, semCI, null, pSem)
            val imageAvailSem = pSem.get(0)
            vkCreateSemaphore(ctx.vkDevice, semCI, null, pSem)
            val renderDoneSem = pSem.get(0)

            val fenceCI = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pFence = stack.mallocLong(1)
            vkCreateFence(ctx.vkDevice, fenceCI, null, pFence)
            val fence = pFence.get(0)

            // Acquire image
            val imageIndex = sc.acquireNextImage(imageAvailSem)
                ?: throw RuntimeException("Failed to acquire swap chain image for test")

            // Allocate command buffer
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmdBuf = stack.mallocPointer(1)
            vkAllocateCommandBuffers(ctx.vkDevice, allocInfo, pCmdBuf)
            val cmdBuf = VkCommandBuffer(pCmdBuf.get(0), ctx.vkDevice)

            // Begin command buffer
            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            vkBeginCommandBuffer(cmdBuf, beginInfo)

            // Begin render pass
            val clearValues = VkClearValue.calloc(2, stack)
            clearValues.get(0).color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
            clearValues.get(1).depthStencil().depth(1f).stencil(0)

            val rpInfo = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(sc.renderPass)
                .framebuffer(sc.getFramebuffer(imageIndex))
                .renderArea { it.offset { o -> o.x(0).y(0) }.extent { e -> e.width(width).height(height) } }
                .pClearValues(clearValues)

            vkCmdBeginRenderPass(cmdBuf, rpInfo, VK_SUBPASS_CONTENTS_INLINE)

            // Set viewport and scissor
            val viewport = VkViewport.calloc(1, stack)
            viewport.get(0).x(0f).y(0f).width(width.toFloat()).height(height.toFloat()).minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(cmdBuf, 0, viewport)

            val scissor = VkRect2D.calloc(1, stack)
            scissor.get(0).offset { it.x(0).y(0) }.extent { it.width(width).height(height) }
            vkCmdSetScissor(cmdBuf, 0, scissor)

            // Render the scene
            svr.render(cmdBuf, scene.camera, scene.lights, sceneMeshEntries, scene.occluderTriangles)

            vkCmdEndRenderPass(cmdBuf)
            vkEndCommandBuffer(cmdBuf)

            // Submit
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(imageAvailSem))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(cmdBuf))
                .pSignalSemaphores(stack.longs(renderDoneSem))

            vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence)
            vkWaitForFences(ctx.vkDevice, fence, true, Long.MAX_VALUE)

            // Now read back pixels from the swap chain image
            val pixelData = readBackPixels(ctx, sc, imageIndex)

            // Cleanup sync objects
            vkDestroyFence(ctx.vkDevice, fence, null)
            vkDestroySemaphore(ctx.vkDevice, imageAvailSem, null)
            vkDestroySemaphore(ctx.vkDevice, renderDoneSem, null)

            // Cleanup scene meshes
            for (entry in sceneMeshEntries) {
                entry.mesh.close()
            }

            return pixelData
        }
    }

    /**
     * Read back pixels from a swap chain image using vkCmdCopyImageToBuffer.
     */
    private fun readBackPixels(ctx: VulkanContext, sc: SwapChain, imageIndex: Int): PixelData {
        val imageSize = (width * height * 4).toLong()

        MemoryStack.stackPush().use { stack ->
            // Create staging buffer for readback
            val bufferCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(imageSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_GPU_TO_CPU)

            val pBuf = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(ctx.allocator, bufferCI, allocCI, pBuf, pAlloc, null) == VK_SUCCESS)
            val stagingBuffer = pBuf.get(0)
            val stagingAlloc = pAlloc.get(0)

            // Record copy command
            val cmdAllocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmd = stack.mallocPointer(1)
            vkAllocateCommandBuffers(ctx.vkDevice, cmdAllocInfo, pCmd)
            val copyCmdBuf = VkCommandBuffer(pCmd.get(0), ctx.vkDevice)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            vkBeginCommandBuffer(copyCmdBuf, beginInfo)

            // Transition swap chain image to TRANSFER_SRC
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(getSwapChainImage(sc, imageIndex))
                .subresourceRange { it
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1)
                }
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)

            vkCmdPipelineBarrier(copyCmdBuf,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier)

            // Copy image to buffer
            val region = VkBufferImageCopy.calloc(1, stack)
            region.get(0)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                .imageOffset { it.x(0).y(0).z(0) }
                .imageExtent { it.width(width).height(height).depth(1) }

            vkCmdCopyImageToBuffer(copyCmdBuf, getSwapChainImage(sc, imageIndex),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuffer, region)

            vkEndCommandBuffer(copyCmdBuf)

            // Submit and wait
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(copyCmdBuf))
            vkQueueSubmit(ctx.graphicsQueue, submitInfo, VK_NULL_HANDLE)
            vkQueueWaitIdle(ctx.graphicsQueue)

            // Map staging buffer and read pixels
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(ctx.allocator, stagingAlloc, ppData)
            val mappedBuf = ppData.getByteBuffer(0, imageSize.toInt())

            // Copy to PixelData (BGRA -> RGBA conversion since format is B8G8R8A8)
            val pixelBuf = ByteBuffer.allocate(width * height * 4)
            for (i in 0 until width * height) {
                val b = mappedBuf.get(i * 4).toInt() and 0xFF
                val g = mappedBuf.get(i * 4 + 1).toInt() and 0xFF
                val r = mappedBuf.get(i * 4 + 2).toInt() and 0xFF
                val a = mappedBuf.get(i * 4 + 3).toInt() and 0xFF
                pixelBuf.put((r and 0xFF).toByte())
                pixelBuf.put((g and 0xFF).toByte())
                pixelBuf.put((b and 0xFF).toByte())
                pixelBuf.put((a and 0xFF).toByte())
            }
            pixelBuf.flip()

            vmaUnmapMemory(ctx.allocator, stagingAlloc)
            vmaDestroyBuffer(ctx.allocator, stagingBuffer, stagingAlloc)

            return PixelData(width, height, pixelBuf)
        }
    }

    /**
     * Get the VkImage handle for a swap chain image.
     * SwapChain doesn't expose images directly, so we use reflection or re-acquire.
     */
    private fun getSwapChainImage(sc: SwapChain, imageIndex: Int): Long {
        // Access private 'images' field via reflection
        val field = SwapChain::class.java.getDeclaredField("images")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val images = field.get(sc) as List<Long>
        return images[imageIndex]
    }

    fun disposeScene(scene: TestScene) {
        scene.dispose()
    }

    fun saveImage(pixelData: PixelData, testName: String) {
        val file = File(outputDir, "$testName.png")
        outputDir.mkdirs()
        val img = BufferedImage(pixelData.width, pixelData.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixelData.height) {
            for (x in 0 until pixelData.width) {
                val pixel = pixelData.getPixel(x, y)
                val r = (pixel ushr 24) and 0xFF
                val g = (pixel ushr 16) and 0xFF
                val b = (pixel ushr 8) and 0xFF
                val a = pixel and 0xFF
                img.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(img, "PNG", file)
    }

    fun dispose() {
        ready = false
        val ctx = vulkanContext ?: return
        ctx.waitIdle()

        renderer?.close()
        ambientPipeline?.close()
        stencilFrontPipeline?.close()
        stencilBackPipeline?.close()
        litPipeline?.close()

        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(ctx.vkDevice, descriptorSetLayout, null)
        }
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(ctx.vkDevice, commandPool, null)
        }

        swapChain?.close()
        vulkanContext?.close()

        if (window != MemoryUtil.NULL) {
            glfwDestroyWindow(window)
        }
        glfwTerminate()

        vulkanContext = null
        swapChain = null
        renderer = null
    }
}
