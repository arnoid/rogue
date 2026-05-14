package com.roguelike.rendering.vulkan

import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer

/**
 * GPU image for texture sampling, loaded via STB.
 * Supports loading from file and creating solid color fallback textures.
 */
class VulkanTexture private constructor(
    val image: Long,
    val allocation: Long,
    val imageView: Long,
    val sampler: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    private val allocator: Long,
    private val device: VkDevice
) : AutoCloseable {

    override fun close() {
        vkDestroySampler(device, sampler, null)
        vkDestroyImageView(device, imageView, null)
        vmaDestroyImage(allocator, image, allocation)
    }

    companion object {
        /**
         * Load texture from file path using STB.
         */
        fun loadFromFile(context: VulkanContext, path: String): VulkanTexture {
            MemoryStack.stackPush().use { stack ->
                val pWidth = stack.mallocInt(1)
                val pHeight = stack.mallocInt(1)
                val pChannels = stack.mallocInt(1)

                // Try classpath first, then absolute path
                val pixels: ByteBuffer
                val stream = VulkanTexture::class.java.classLoader.getResourceAsStream(path)
                if (stream != null) {
                    val bytes = stream.readBytes()
                    val buf = MemoryUtil.memAlloc(bytes.size)
                    buf.put(bytes).flip()
                    val loaded = STBImage.stbi_load_from_memory(buf, pWidth, pHeight, pChannels, 4)
                    MemoryUtil.memFree(buf)
                    pixels = loaded ?: throw RuntimeException("Failed to load texture: $path")
                } else {
                    val loaded = STBImage.stbi_load(path, pWidth, pHeight, pChannels, 4)
                    pixels = loaded ?: throw RuntimeException("Failed to load texture: $path")
                }

                val w = pWidth.get(0)
                val h = pHeight.get(0)
                val tex = createFromPixels(context, pixels, w, h)
                STBImage.stbi_image_free(pixels)
                return tex
            }
        }

        /**
         * Create 1x1 solid color texture (for untextured geometry).
         */
        fun createSolidColor(context: VulkanContext, r: Float, g: Float, b: Float, a: Float): VulkanTexture {
            val pixels = MemoryUtil.memAlloc(4)
            pixels.put((r * 255).toInt().coerceIn(0, 255).toByte())
            pixels.put((g * 255).toInt().coerceIn(0, 255).toByte())
            pixels.put((b * 255).toInt().coerceIn(0, 255).toByte())
            pixels.put((a * 255).toInt().coerceIn(0, 255).toByte())
            pixels.flip()
            val tex = createFromPixels(context, pixels, 1, 1)
            MemoryUtil.memFree(pixels)
            return tex
        }

        private fun createFromPixels(context: VulkanContext, pixels: ByteBuffer, width: Int, height: Int): VulkanTexture {
            val imageSize = width * height * 4L
            val format = VK_FORMAT_R8G8B8A8_SRGB

            MemoryStack.stackPush().use { stack ->
                // Create staging buffer
                val stagingBufferCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(imageSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

                val stagingAllocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_CPU_ONLY)

                val pStagingBuf = stack.mallocLong(1)
                val pStagingAlloc = stack.mallocPointer(1)
                vmaCreateBuffer(context.allocator, stagingBufferCI, stagingAllocCI, pStagingBuf, pStagingAlloc, null)
                val stagingBuffer = pStagingBuf.get(0)
                val stagingAlloc = pStagingAlloc.get(0)

                // Copy pixels to staging buffer
                val ppData = stack.mallocPointer(1)
                vmaMapMemory(context.allocator, stagingAlloc, ppData)
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), ppData.get(0), imageSize)
                vmaUnmapMemory(context.allocator, stagingAlloc)

                // Create image
                val imageCI = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent { it.width(width).height(height).depth(1) }
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)

                val imgAllocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_ONLY)

                val pImage = stack.mallocLong(1)
                val pAlloc = stack.mallocPointer(1)
                check(vmaCreateImage(context.allocator, imageCI, imgAllocCI, pImage, pAlloc, null) == VK_SUCCESS)
                val image = pImage.get(0)
                val allocation = pAlloc.get(0)

                // Transition + copy (using a one-shot command buffer)
                val cmdBuf = beginSingleTimeCommands(context, stack)

                // Transition to TRANSFER_DST
                transitionImageLayout(cmdBuf, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack)

                // Copy buffer to image
                val region = VkBufferImageCopy.calloc(1, stack)
                region.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .imageOffset { it.x(0).y(0).z(0) }
                    .imageExtent { it.width(width).height(height).depth(1) }
                vkCmdCopyBufferToImage(cmdBuf, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)

                // Transition to SHADER_READ_ONLY
                transitionImageLayout(cmdBuf, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack)

                endSingleTimeCommands(context, cmdBuf, stack)

                // Clean up staging
                vmaDestroyBuffer(context.allocator, stagingBuffer, stagingAlloc)

                // Create image view
                val viewCI = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange { it
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)
                    }
                val pView = stack.mallocLong(1)
                check(vkCreateImageView(context.vkDevice, viewCI, null, pView) == VK_SUCCESS)
                val imageView = pView.get(0)

                // Create sampler
                val samplerCI = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1f)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                val pSampler = stack.mallocLong(1)
                check(vkCreateSampler(context.vkDevice, samplerCI, null, pSampler) == VK_SUCCESS)

                return VulkanTexture(image, allocation, imageView, pSampler.get(0), width, height, format, context.allocator, context.vkDevice)
            }
        }

        private fun beginSingleTimeCommands(context: VulkanContext, stack: MemoryStack): VkCommandBuffer {
            val poolCI = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(context.graphicsQueueFamily)
            val pPool = stack.mallocLong(1)
            vkCreateCommandPool(context.vkDevice, poolCI, null, pPool)

            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(pPool.get(0))
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmdBuf = stack.mallocPointer(1)
            vkAllocateCommandBuffers(context.vkDevice, allocInfo, pCmdBuf)
            val cmdBuf = VkCommandBuffer(pCmdBuf.get(0), context.vkDevice)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            vkBeginCommandBuffer(cmdBuf, beginInfo)

            return cmdBuf
        }

        private fun endSingleTimeCommands(context: VulkanContext, cmdBuf: VkCommandBuffer, stack: MemoryStack) {
            vkEndCommandBuffer(cmdBuf)

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmdBuf))
            vkQueueSubmit(context.graphicsQueue, submitInfo, VK_NULL_HANDLE)
            vkQueueWaitIdle(context.graphicsQueue)
        }

        private fun transitionImageLayout(
            cmdBuf: VkCommandBuffer, image: Long,
            oldLayout: Int, newLayout: Int, stack: MemoryStack
        ) {
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange { it
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                }

            val srcStage: Int
            val dstStage: Int
            when {
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                    barrier.get(0).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                }
                oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                }
                else -> throw RuntimeException("Unsupported layout transition")
            }

            vkCmdPipelineBarrier(cmdBuf, srcStage, dstStage, 0, null, null, barrier)
        }
    }
}

