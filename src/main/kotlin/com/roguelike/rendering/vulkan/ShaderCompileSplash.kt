package com.roguelike.rendering.vulkan

import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkClearAttachment
import org.lwjgl.vulkan.VkClearRect
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkPresentInfoKHR
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderPassBeginInfo
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VkViewport
import org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
import org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [ShaderCache.compileAll] on a background thread and shows a minimal
 * blocking splash screen until it finishes.
 *
 * The splash deliberately avoids any path that needs the project's GLSL
 * shaders to be compiled — those are precisely what's being built. Instead
 * it relies on `vkCmdClearAttachments` to paint a coloured progress bar
 * inside the existing render pass and uses the GLFW window title to display
 * the textual "Compiling shaders…" status. That gives the user a visible,
 * blocking notification with zero shader dependencies.
 */
object ShaderCompileSplash {

    /**
     * Synchronously precompile [stale] shaders, presenting splash frames the
     * whole time. Returns once every shader has been written into the cache.
     */
    fun run(
        window: Long,
        context: VulkanContext,
        swapChain: SwapChain,
        commandBuffer: VkCommandBuffer,
        imageAvailableSemaphore: Long,
        renderFinishedSemaphore: Long,
        inFlightFence: Long,
        stale: List<String>
    ) {
        if (stale.isEmpty()) return

        val total = stale.size
        val completed = AtomicInteger(0)
        val currentName = AtomicReference("starting…")
        val error = AtomicReference<Throwable?>(null)
        val originalTitle = GLFW.glfwGetWindowTitle(window) ?: "Roguelike 3D"

        val worker = Thread({
            try {
                ShaderCache.compileAll(stale) { done, _, name ->
                    completed.set(done)
                    currentName.set(name)
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "shader-compile").apply {
            isDaemon = true
            start()
        }

        while (worker.isAlive) {
            GLFW.glfwPollEvents()
            if (GLFW.glfwWindowShouldClose(window)) break

            // Skip presenting when the window is minimised — keep title fresh
            // so the user still sees compile progress in the taskbar.
            updateTitle(window, originalTitle, completed.get(), total, currentName.get())
            if (swapChain.width == 0 || swapChain.height == 0) {
                Thread.sleep(50)
                continue
            }

            try {
                presentFrame(
                    context,
                    swapChain,
                    commandBuffer,
                    imageAvailableSemaphore,
                    renderFinishedSemaphore,
                    inFlightFence,
                    completed.get(),
                    total
                )
            } catch (_: Throwable) {
                // Best-effort: a transient swapchain failure shouldn't block
                // shader compilation. Try again next iteration.
            }

            // ~30 fps is plenty for a static splash.
            Thread.sleep(33)
        }

        worker.join()

        // Restore the original window title before handing control back.
        GLFW.glfwSetWindowTitle(window, originalTitle)

        val fail = error.get()
        if (fail != null) throw RuntimeException("Shader compilation failed", fail)
    }

    private fun updateTitle(window: Long, original: String, done: Int, total: Int, current: String) {
        val pct = if (total > 0) (done * 100) / total else 100
        GLFW.glfwSetWindowTitle(
            window,
            "$original — Compiling shaders $done/$total ($pct%) — $current"
        )
    }

    private fun presentFrame(
        context: VulkanContext,
        swapChain: SwapChain,
        commandBuffer: VkCommandBuffer,
        imageAvailableSemaphore: Long,
        renderFinishedSemaphore: Long,
        inFlightFence: Long,
        done: Int,
        total: Int
    ) {
        vkWaitForFences(context.vkDevice, inFlightFence, true, Long.MAX_VALUE)
        val imageIndex = swapChain.acquireNextImage(imageAvailableSemaphore) ?: return
        vkResetFences(context.vkDevice, inFlightFence)

        MemoryStack.stackPush().use { stack ->
            vkResetCommandBuffer(commandBuffer, 0)
            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            vkBeginCommandBuffer(commandBuffer, beginInfo)

            // Clear to a dark slate so the splash reads as "loading", not as
            // the main menu's near-black.
            val clearValues = VkClearValue.calloc(2, stack)
            clearValues.get(0).color()
                .float32(0, 0.08f).float32(1, 0.10f).float32(2, 0.14f).float32(3, 1f)
            clearValues.get(1).depthStencil().depth(1f).stencil(0)

            val renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(swapChain.renderPass)
                .framebuffer(swapChain.getFramebuffer(imageIndex))
                .renderArea { it.offset { o -> o.x(0).y(0) }.extent { e -> e.width(swapChain.width).height(swapChain.height) } }
                .pClearValues(clearValues)

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)

            val viewport = VkViewport.calloc(1, stack)
            viewport.get(0)
                .x(0f).y(0f)
                .width(swapChain.width.toFloat()).height(swapChain.height.toFloat())
                .minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(commandBuffer, 0, viewport)

            val scissor = VkRect2D.calloc(1, stack)
            scissor.get(0).offset { it.x(0).y(0) }.extent { it.width(swapChain.width).height(swapChain.height) }
            vkCmdSetScissor(commandBuffer, 0, scissor)

            drawProgressBar(commandBuffer, swapChain, done, total, stack)

            vkCmdEndRenderPass(commandBuffer)
            vkEndCommandBuffer(commandBuffer)

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(imageAvailableSemaphore))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(commandBuffer))
                .pSignalSemaphores(stack.longs(renderFinishedSemaphore))

            check(vkQueueSubmit(context.graphicsQueue, submitInfo, inFlightFence) == VK_SUCCESS)

            val presentInfo = VkPresentInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(stack.longs(renderFinishedSemaphore))
                .swapchainCount(1)
                .pSwapchains(stack.longs(swapChain.handle))
                .pImageIndices(stack.ints(imageIndex))

            vkQueuePresentKHR(context.presentQueue, presentInfo)
        }
    }

    /**
     * Draw a horizontal progress bar centred on screen by issuing
     * [vkCmdClearAttachments] calls inside the render pass. No shaders or
     * pipelines required — perfect for a shader-bootstrap splash.
     */
    private fun drawProgressBar(
        commandBuffer: VkCommandBuffer,
        swapChain: SwapChain,
        done: Int,
        total: Int,
        stack: MemoryStack
    ) {
        val w = swapChain.width
        val h = swapChain.height
        val barW = (w * 0.55f).toInt().coerceAtLeast(120)
        val barH = 18
        val barX = (w - barW) / 2
        val barY = h / 2 - barH / 2

        // Outer track (dark).
        clearRect(commandBuffer, stack, barX - 2, barY - 2, barW + 4, barH + 4, 0.15f, 0.17f, 0.22f)
        // Track interior (slightly darker than background).
        clearRect(commandBuffer, stack, barX, barY, barW, barH, 0.05f, 0.06f, 0.09f)
        // Filled portion.
        val frac = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 1f
        val filled = (barW * frac).toInt()
        if (filled > 0) {
            clearRect(commandBuffer, stack, barX, barY, filled, barH, 0.30f, 0.55f, 0.90f)
        }
    }

    /** Clear a single rectangular region of the colour attachment to [r,g,b]. */
    private fun clearRect(
        commandBuffer: VkCommandBuffer,
        stack: MemoryStack,
        x: Int, y: Int, w: Int, h: Int,
        r: Float, g: Float, b: Float
    ) {
        val attachment = VkClearAttachment.calloc(1, stack)
        attachment.get(0)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .colorAttachment(0)
            .clearValue { cv -> cv.color().float32(0, r).float32(1, g).float32(2, b).float32(3, 1f) }

        val rect = VkClearRect.calloc(1, stack)
        rect.get(0)
            .baseArrayLayer(0)
            .layerCount(1)
            .rect { r2 ->
                r2.offset { o -> o.x(x).y(y) }
                r2.extent { e -> e.width(w).height(h) }
            }

        vkCmdClearAttachments(commandBuffer, attachment, rect)
    }
}



