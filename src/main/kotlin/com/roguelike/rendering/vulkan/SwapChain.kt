package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*

/**
 * Vulkan swap chain with image views, depth+stencil attachment, framebuffers, and render pass.
 * Handles recreation on window resize.
 */
class SwapChain(
    private val context: VulkanContext
) : AutoCloseable {

    var handle: Long = VK_NULL_HANDLE; private set
    var imageCount: Int = 0; private set
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB; private set
    var width: Int = 0; private set
    var height: Int = 0; private set
    var renderPass: Long = VK_NULL_HANDLE; private set
    var depthFormat: Int = VK_FORMAT_D24_UNORM_S8_UINT; private set

    private var images: List<Long> = emptyList()
    private var imageViews: List<Long> = emptyList()
    private var depthImage: Long = VK_NULL_HANDLE
    private var depthImageView: Long = VK_NULL_HANDLE
    private var depthAllocation: Long = VK_NULL_HANDLE
    private var framebuffers: List<Long> = emptyList()

    val extent: Pair<Int, Int> get() = width to height

    fun create(desiredWidth: Int, desiredHeight: Int) {
        MemoryStack.stackPush().use { stack ->
            // Query surface capabilities
            val caps = VkSurfaceCapabilitiesKHR.calloc(stack)
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.vkPhysicalDevice, context.surface, caps)

            // Choose extent
            width = if (caps.currentExtent().width() != -1) {
                caps.currentExtent().width()
            } else {
                desiredWidth.coerceIn(caps.minImageExtent().width(), caps.maxImageExtent().width())
            }
            height = if (caps.currentExtent().height() != -1) {
                caps.currentExtent().height()
            } else {
                desiredHeight.coerceIn(caps.minImageExtent().height(), caps.maxImageExtent().height())
            }

            // Choose format
            val pFormatCount = stack.mallocInt(1)
            vkGetPhysicalDeviceSurfaceFormatsKHR(context.vkPhysicalDevice, context.surface, pFormatCount, null)
            val formats = VkSurfaceFormatKHR.calloc(pFormatCount.get(0), stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(context.vkPhysicalDevice, context.surface, pFormatCount, formats)

            format = VK_FORMAT_B8G8R8A8_SRGB
            var colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
            for (i in 0 until pFormatCount.get(0)) {
                val sf = formats.get(i)
                if (sf.format() == VK_FORMAT_B8G8R8A8_SRGB && sf.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    format = sf.format()
                    colorSpace = sf.colorSpace()
                    break
                }
            }

            // Choose present mode (MAILBOX preferred, FIFO fallback)
            val pModeCount = stack.mallocInt(1)
            vkGetPhysicalDeviceSurfacePresentModesKHR(context.vkPhysicalDevice, context.surface, pModeCount, null)
            val modes = stack.mallocInt(pModeCount.get(0))
            vkGetPhysicalDeviceSurfacePresentModesKHR(context.vkPhysicalDevice, context.surface, pModeCount, modes)
            var presentMode = VK_PRESENT_MODE_FIFO_KHR
            for (i in 0 until pModeCount.get(0)) {
                if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                    presentMode = VK_PRESENT_MODE_MAILBOX_KHR
                    break
                }
            }

            // Image count
            var imgCount = caps.minImageCount() + 1
            if (caps.maxImageCount() > 0) imgCount = imgCount.coerceAtMost(caps.maxImageCount())

            // Create swap chain
            val swapCI = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(context.surface)
                .minImageCount(imgCount)
                .imageFormat(format)
                .imageColorSpace(colorSpace)
                .imageExtent { it.width(width).height(height) }
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(VK_NULL_HANDLE)

            if (context.graphicsQueueFamily != context.presentQueueFamily) {
                swapCI.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                swapCI.pQueueFamilyIndices(stack.ints(context.graphicsQueueFamily, context.presentQueueFamily))
            } else {
                swapCI.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            val pSwapchain = stack.mallocLong(1)
            check(vkCreateSwapchainKHR(context.vkDevice, swapCI, null, pSwapchain) == VK_SUCCESS) {
                "Failed to create swap chain"
            }
            handle = pSwapchain.get(0)

            // Get images
            val pImgCount = stack.mallocInt(1)
            vkGetSwapchainImagesKHR(context.vkDevice, handle, pImgCount, null)
            imageCount = pImgCount.get(0)
            val pImages = stack.mallocLong(imageCount)
            vkGetSwapchainImagesKHR(context.vkDevice, handle, pImgCount, pImages)
            images = (0 until imageCount).map { pImages.get(it) }

            // Create image views
            imageViews = images.map { image ->
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
                pView.get(0)
            }

            // Choose depth+stencil format
            depthFormat = findDepthStencilFormat(stack)

            // Create depth+stencil image
            createDepthStencilResources(stack)

            // Create render pass
            createRenderPass(stack)

            // Create framebuffers
            createFramebuffers(stack)
        }
    }

    fun recreate(newWidth: Int, newHeight: Int) {
        context.waitIdle()
        cleanup()
        create(newWidth, newHeight)
    }

    fun acquireNextImage(semaphore: Long, timeout: Long = Long.MAX_VALUE): Int? {
        MemoryStack.stackPush().use { stack ->
            val pIndex = stack.mallocInt(1)
            val result = vkAcquireNextImageKHR(context.vkDevice, handle, timeout, semaphore, VK_NULL_HANDLE, pIndex)
            return when (result) {
                VK_SUCCESS, VK_SUBOPTIMAL_KHR -> pIndex.get(0)
                VK_ERROR_OUT_OF_DATE_KHR -> null
                else -> throw RuntimeException("Failed to acquire swap chain image: $result")
            }
        }
    }

    fun getFramebuffer(imageIndex: Int): Long = framebuffers[imageIndex]

    private fun findDepthStencilFormat(stack: MemoryStack): Int {
        val candidates = intArrayOf(VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT)
        val props = VkFormatProperties.calloc(stack)
        for (fmt in candidates) {
            vkGetPhysicalDeviceFormatProperties(context.vkPhysicalDevice, fmt, props)
            if (props.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0) {
                return fmt
            }
        }
        throw RuntimeException("No suitable depth+stencil format found")
    }

    private fun createDepthStencilResources(stack: MemoryStack) {
        val imageCI = VkImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D)
            .format(depthFormat)
            .extent { it.width(width).height(height).depth(1) }
            .mipLevels(1)
            .arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)

        val pImage = stack.mallocLong(1)
        check(vkCreateImage(context.vkDevice, imageCI, null, pImage) == VK_SUCCESS)
        depthImage = pImage.get(0)

        // Allocate memory
        val memReqs = VkMemoryRequirements.calloc(stack)
        vkGetImageMemoryRequirements(context.vkDevice, depthImage, memReqs)
        val memTypeIndex = findMemoryType(stack, memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)

        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(memTypeIndex)

        val pMemory = stack.mallocLong(1)
        check(vkAllocateMemory(context.vkDevice, allocInfo, null, pMemory) == VK_SUCCESS)
        depthAllocation = pMemory.get(0)
        vkBindImageMemory(context.vkDevice, depthImage, depthAllocation, 0)

        // Create image view
        val viewCI = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(depthImage)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(depthFormat)
            .subresourceRange { it
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT or VK_IMAGE_ASPECT_STENCIL_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
            }
        val pView = stack.mallocLong(1)
        check(vkCreateImageView(context.vkDevice, viewCI, null, pView) == VK_SUCCESS)
        depthImageView = pView.get(0)
    }

    private fun findMemoryType(stack: MemoryStack, typeFilter: Int, properties: Int): Int {
        val memProps = VkPhysicalDeviceMemoryProperties.calloc(stack)
        vkGetPhysicalDeviceMemoryProperties(context.vkPhysicalDevice, memProps)
        for (i in 0 until memProps.memoryTypeCount()) {
            if (typeFilter and (1 shl i) != 0 &&
                memProps.memoryTypes(i).propertyFlags() and properties == properties) {
                return i
            }
        }
        throw RuntimeException("Failed to find suitable memory type")
    }

    private fun createRenderPass(stack: MemoryStack) {
        val attachments = VkAttachmentDescription.calloc(2, stack)

        // Color attachment
        attachments.get(0)
            .format(format)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)

        // Depth+stencil attachment
        attachments.get(1)
            .format(depthFormat)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)

        val colorRef = VkAttachmentReference.calloc(1, stack)
            .get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val colorRefs = VkAttachmentReference.calloc(1, stack)
        colorRefs.put(0, colorRef)

        val depthRef = VkAttachmentReference.calloc(stack)
            .attachment(1)
            .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)

        val subpass = VkSubpassDescription.calloc(1, stack)
            .get(0)
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1)
            .pColorAttachments(colorRefs)
            .pDepthStencilAttachment(depthRef)

        val subpasses = VkSubpassDescription.calloc(1, stack)
        subpasses.put(0, subpass)

        val dependency = VkSubpassDependency.calloc(1, stack)
            .get(0)
            .srcSubpass(VK_SUBPASS_EXTERNAL)
            .dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .srcAccessMask(0)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)

        val dependencies = VkSubpassDependency.calloc(1, stack)
        dependencies.put(0, dependency)

        val rpCI = VkRenderPassCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(attachments)
            .pSubpasses(subpasses)
            .pDependencies(dependencies)

        val pRenderPass = stack.mallocLong(1)
        check(vkCreateRenderPass(context.vkDevice, rpCI, null, pRenderPass) == VK_SUCCESS)
        renderPass = pRenderPass.get(0)
    }

    private fun createFramebuffers(stack: MemoryStack) {
        framebuffers = imageViews.map { colorView ->
            val attachments = stack.mallocLong(2)
            attachments.put(0, colorView)
            attachments.put(1, depthImageView)

            val fbCI = VkFramebufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                .renderPass(renderPass)
                .pAttachments(attachments)
                .width(width)
                .height(height)
                .layers(1)

            val pFB = stack.mallocLong(1)
            check(vkCreateFramebuffer(context.vkDevice, fbCI, null, pFB) == VK_SUCCESS)
            pFB.get(0)
        }
    }

    private fun cleanup() {
        framebuffers.forEach { vkDestroyFramebuffer(context.vkDevice, it, null) }
        framebuffers = emptyList()

        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(context.vkDevice, renderPass, null)
            renderPass = VK_NULL_HANDLE
        }

        if (depthImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(context.vkDevice, depthImageView, null)
            depthImageView = VK_NULL_HANDLE
        }
        if (depthImage != VK_NULL_HANDLE) {
            vkDestroyImage(context.vkDevice, depthImage, null)
            depthImage = VK_NULL_HANDLE
        }
        if (depthAllocation != VK_NULL_HANDLE) {
            vkFreeMemory(context.vkDevice, depthAllocation, null)
            depthAllocation = VK_NULL_HANDLE
        }

        imageViews.forEach { vkDestroyImageView(context.vkDevice, it, null) }
        imageViews = emptyList()

        if (handle != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(context.vkDevice, handle, null)
            handle = VK_NULL_HANDLE
        }
    }

    override fun close() {
        cleanup()
    }
}


