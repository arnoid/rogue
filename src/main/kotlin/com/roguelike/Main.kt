package com.roguelike

import com.roguelike.input.InputSystem
import com.roguelike.rendering.Camera
import com.roguelike.rendering.vulkan.ShaderCache
import com.roguelike.rendering.vulkan.ShaderCompileSplash
import com.roguelike.rendering.vulkan.SwapChain
import com.roguelike.rendering.vulkan.VulkanContext
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*

fun main() {
    // Initialize GLFW
    check(glfwInit()) { "Failed to initialize GLFW" }
    check(GLFWVulkan.glfwVulkanSupported()) {
        "Vulkan is not supported on this system. Please install Vulkan-capable GPU drivers."
    }

    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API) // No OpenGL context
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

    val window = glfwCreateWindow(1024, 768, "Roguelike 3D", MemoryUtil.NULL, MemoryUtil.NULL)
    check(window != MemoryUtil.NULL) { "Failed to create GLFW window" }

    val debug = System.getProperty("rogue.debug") != null

    // Create Vulkan context
    val vulkanContext = try {
        VulkanContext.create(window, debug)
    } catch (e: Exception) {
        System.err.println("ERROR: ${e.message}")
        System.err.println("Vulkan initialization failed. Exiting in 5 seconds...")
        Thread.sleep(5000)
        glfwDestroyWindow(window)
        glfwTerminate()
        return
    }

    // Create swap chain
    val swapChain = SwapChain(vulkanContext)
    MemoryStack.stackPush().use { stack ->
        val pWidth = stack.mallocInt(1)
        val pHeight = stack.mallocInt(1)
        glfwGetFramebufferSize(window, pWidth, pHeight)
        swapChain.create(pWidth.get(0), pHeight.get(0))
    }

    // Create input system
    val inputSystem = InputSystem()
    inputSystem.install(window)

    // Create camera
    val camera = Camera()
    camera.resize(swapChain.width, swapChain.height)

    // Create launcher (state machine) — `init()` constructs SimpleUI which
    // loads all GLSL shaders. Defer that call until the on-disk shader cache
    // is fully populated, otherwise the runtime-compile fallback would stall
    // the main thread inside the SimpleUI constructor with no UI feedback.
    val launcher = RoguelikeLauncher(vulkanContext, swapChain, inputSystem, camera)

    // Create synchronization objects
    val imageAvailableSemaphore: Long
    val renderFinishedSemaphore: Long
    val inFlightFence: Long
    val commandPool: Long
    val commandBuffer: VkCommandBuffer

    MemoryStack.stackPush().use { stack ->
        val semaphoreCI = VkSemaphoreCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
        val fenceCI = VkFenceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            .flags(VK_FENCE_CREATE_SIGNALED_BIT)

        val pSemaphore = stack.mallocLong(1)
        val pFence = stack.mallocLong(1)

        vkCreateSemaphore(vulkanContext.vkDevice, semaphoreCI, null, pSemaphore)
        imageAvailableSemaphore = pSemaphore.get(0)
        vkCreateSemaphore(vulkanContext.vkDevice, semaphoreCI, null, pSemaphore)
        renderFinishedSemaphore = pSemaphore.get(0)
        vkCreateFence(vulkanContext.vkDevice, fenceCI, null, pFence)
        inFlightFence = pFence.get(0)

        // Create command pool and buffer
        val poolCI = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(vulkanContext.graphicsQueueFamily)
        val pPool = stack.mallocLong(1)
        vkCreateCommandPool(vulkanContext.vkDevice, poolCI, null, pPool)
        commandPool = pPool.get(0)

        val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        val pCmdBuf = stack.mallocPointer(1)
        vkAllocateCommandBuffers(vulkanContext.vkDevice, allocInfo, pCmdBuf)
        commandBuffer = VkCommandBuffer(pCmdBuf.get(0), vulkanContext.vkDevice)
    }

    // Window resize callback
    glfwSetFramebufferSizeCallback(window) { _, width, height ->
        if (width > 0 && height > 0) {
            swapChain.recreate(width, height)
            camera.resize(width, height)
        }
    }

    // ── Shader cache bootstrap ───────────────────────────────────────────
    // Check the on-disk SPIR-V cache against the current GLSL sources, the
    // targeted Vulkan API version and the shaderc optimisation level. Any
    // shader whose source has changed (or that has never been compiled on
    // this machine) goes through a background compile while a splash screen
    // blocks user input. Once the cache is fully populated, SimpleUI's
    // constructor just memory-maps the cached `.spv` and the renderer is
    // ready to go.
    val stale = ShaderCache.staleShaders()
    if (stale.isNotEmpty()) {
        println("[Main] shader cache has ${stale.size} stale shader(s); compiling…")
        ShaderCompileSplash.run(
            window = window,
            context = vulkanContext,
            swapChain = swapChain,
            commandBuffer = commandBuffer,
            imageAvailableSemaphore = imageAvailableSemaphore,
            renderFinishedSemaphore = renderFinishedSemaphore,
            inFlightFence = inFlightFence,
            stale = stale
        )
        println("[Main] shader compilation complete")
    } else {
        println("[Main] shader cache is up to date — skipping compile splash")
    }

    // Now that every SPIR-V module is on disk, building the renderer is fast.
    launcher.init()

    // Main game loop
    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()

        // Check if launcher requested shutdown (e.g., quit button)
        if (launcher.state == AppState.SHUTDOWN) {
            glfwSetWindowShouldClose(window, true)
            continue
        }

        // Skip rendering when minimized
        if (swapChain.width == 0 || swapChain.height == 0) {
            Thread.sleep(10)
            continue
        }

        // Each frame gets its own stack frame
        MemoryStack.stackPush().use { stack ->
            // Wait for previous frame
            vkWaitForFences(vulkanContext.vkDevice, inFlightFence, true, Long.MAX_VALUE)

            // Acquire next image
            val imageIndex = swapChain.acquireNextImage(imageAvailableSemaphore)
            if (imageIndex == null) {
                val pW = IntArray(1)
                val pH = IntArray(1)
                glfwGetFramebufferSize(window, pW, pH)
                if (pW[0] > 0 && pH[0] > 0) {
                    swapChain.recreate(pW[0], pH[0])
                    camera.resize(pW[0], pH[0])
                }
                return@use
            }

            vkResetFences(vulkanContext.vkDevice, inFlightFence)

            // Record command buffer
            vkResetCommandBuffer(commandBuffer, 0)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            vkBeginCommandBuffer(commandBuffer, beginInfo)

            // Begin render pass
            val clearValues = VkClearValue.calloc(2, stack)
            // Arena uses a pure-black background (no ambient sky fill) so
            // unlit space outside light radii reads as true black. Menus
            // and the editor keep the dim blue clear for legibility.
            if (launcher.state == AppState.GAME) {
                clearValues.get(0).color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
            } else {
                clearValues.get(0).color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.1f).float32(3, 1f)
            }
            clearValues.get(1).depthStencil().depth(1f).stencil(0)

            val renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(swapChain.renderPass)
                .framebuffer(swapChain.getFramebuffer(imageIndex))
                .renderArea { it.offset { o -> o.x(0).y(0) }.extent { e -> e.width(swapChain.width).height(swapChain.height) } }
                .pClearValues(clearValues)

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)

            // Set dynamic viewport and scissor
            val viewport = VkViewport.calloc(1, stack)
            viewport.get(0).x(0f).y(0f).width(swapChain.width.toFloat()).height(swapChain.height.toFloat()).minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(commandBuffer, 0, viewport)

            val scissor = VkRect2D.calloc(1, stack)
            scissor.get(0).offset { it.x(0).y(0) }.extent { it.width(swapChain.width).height(swapChain.height) }
            vkCmdSetScissor(commandBuffer, 0, scissor)

            // TODO: Record scene rendering commands via launcher
            launcher.render(commandBuffer)

            vkCmdEndRenderPass(commandBuffer)
            vkEndCommandBuffer(commandBuffer)

            // Submit
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(imageAvailableSemaphore))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(commandBuffer))
                .pSignalSemaphores(stack.longs(renderFinishedSemaphore))

            check(vkQueueSubmit(vulkanContext.graphicsQueue, submitInfo, inFlightFence) == VK_SUCCESS)

            // Present
            val presentInfo = VkPresentInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(stack.longs(renderFinishedSemaphore))
                .swapchainCount(1)
                .pSwapchains(stack.longs(swapChain.handle))
                .pImageIndices(stack.ints(imageIndex))

            val presentResult = vkQueuePresentKHR(vulkanContext.presentQueue, presentInfo)
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
                val pW = IntArray(1)
                val pH = IntArray(1)
                glfwGetFramebufferSize(window, pW, pH)
                if (pW[0] > 0 && pH[0] > 0) {
                    swapChain.recreate(pW[0], pH[0])
                    camera.resize(pW[0], pH[0])
                }
            }
        }

        inputSystem.endFrame()
    }

    // Cleanup
    vulkanContext.waitIdle()
    launcher.cleanup()

    vkDestroySemaphore(vulkanContext.vkDevice, imageAvailableSemaphore, null)
    vkDestroySemaphore(vulkanContext.vkDevice, renderFinishedSemaphore, null)
    vkDestroyFence(vulkanContext.vkDevice, inFlightFence, null)
    vkDestroyCommandPool(vulkanContext.vkDevice, commandPool, null)

    swapChain.close()
    vulkanContext.close()

    Callbacks.glfwFreeCallbacks(window)
    glfwDestroyWindow(window)
    glfwTerminate()
}
