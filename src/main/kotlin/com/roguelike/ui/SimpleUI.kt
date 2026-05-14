package com.roguelike.ui

import com.roguelike.input.InputSystem
import com.roguelike.rendering.vulkan.ShaderCompiler
import com.roguelike.rendering.vulkan.VulkanContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer

/**
 * Simple immediate-mode UI renderer for Vulkan with bitmap font text support.
 * Renders colored quads and textured glyph quads via a single pipeline.
 * Font atlas generated at init time using STB TrueType from a built-in monospace font.
 */
class SimpleUI(
    private val context: VulkanContext,
    private val renderPass: Long
) : AutoCloseable {

    private var pipeline: Long = VK_NULL_HANDLE
    private var pipelineLayout: Long = VK_NULL_HANDLE
    private var vertShaderModule: Long = VK_NULL_HANDLE
    private var fragShaderModule: Long = VK_NULL_HANDLE
    private var vertexBuffer: Long = VK_NULL_HANDLE
    private var vertexAllocation: Long = VK_NULL_HANDLE

    // --- Lit-world pipeline (per-pixel lighting) ---
    private var litPipeline: Long = VK_NULL_HANDLE
    private var litPipelineLayout: Long = VK_NULL_HANDLE
    private var litVertShaderModule: Long = VK_NULL_HANDLE
    private var litFragShaderModule: Long = VK_NULL_HANDLE
    private var litVertexBuffer: Long = VK_NULL_HANDLE
    private var litVertexAllocation: Long = VK_NULL_HANDLE
    private var litDescriptorPool: Long = VK_NULL_HANDLE
    private var litDescriptorSetLayout: Long = VK_NULL_HANDLE
    private var litDescriptorSet: Long = VK_NULL_HANDLE
    private var lightingUboBuffer: Long = VK_NULL_HANDLE
    private var lightingUboAlloc: Long = VK_NULL_HANDLE
    private var occupancySsboBuffer: Long = VK_NULL_HANDLE
    private var occupancySsboAlloc: Long = VK_NULL_HANDLE
    private var occupancySsboSize: Long = 0

    // --- GPU-rasterized 3D pipeline (depth-buffered, VP transform on GPU) ---
    private var gpuPipeline: Long = VK_NULL_HANDLE
    private var gpuPipelineLayout: Long = VK_NULL_HANDLE
    private var gpuVertShaderModule: Long = VK_NULL_HANDLE
    private var gpuFragShaderModule: Long = VK_NULL_HANDLE
    private var gpuVertexBuffer: Long = VK_NULL_HANDLE
    private var gpuVertexAllocation: Long = VK_NULL_HANDLE

    // GPU quad vertex: worldPos3 + color4 + normal3 = 10 floats
    private val gpuFloatsPerVertex = 10
    private val maxGpuQuads = 16384
    private val vpMatrix = FloatArray(16) // cached VP matrix for push constants

    // Vertex: pos2 + color4 + uv2 = 8 floats per vertex, 6 verts per quad
    private val maxQuads = 16384
    private val floatsPerVertex = 8
    private val verticesPerQuad = 6
    private val vertexData = FloatArray(maxQuads * verticesPerQuad * floatsPerVertex)
    private var quadCount = 0

    private val gpuVertexData = FloatArray(maxGpuQuads * verticesPerQuad * gpuFloatsPerVertex)
    private var gpuQuadCount = 0

    // Lit quad vertex: pos2 + color4 + worldPos3 + normal3 = 12 floats
    private val litFloatsPerVertex = 12
    private val maxLitQuads = 16384
    private val litVertexData = FloatArray(maxLitQuads * verticesPerQuad * litFloatsPerVertex)
    private var litQuadCount = 0

    // Font atlas Vulkan resources
    private var fontImage: Long = VK_NULL_HANDLE
    private var fontImageAlloc: Long = VK_NULL_HANDLE
    private var fontImageView: Long = VK_NULL_HANDLE
    private var fontSampler: Long = VK_NULL_HANDLE
    private var descriptorPool: Long = VK_NULL_HANDLE
    private var descriptorSetLayout: Long = VK_NULL_HANDLE
    private var descriptorSet: Long = VK_NULL_HANDLE

    // Font metrics from STB bake
    private val ATLAS_W = 512
    private val ATLAS_H = 512
    private val FONT_HEIGHT = 20f
    private val FIRST_CHAR = 32
    private val NUM_CHARS = 96

    /** Simple glyph metrics for our pixel font. */
    data class GlyphInfo(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val xoff: Float, val yoff: Float, val xadvance: Float)
    private var glyphs: Array<GlyphInfo> = emptyArray()

    var screenWidth: Float = 1024f
    var screenHeight: Float = 768f

    data class ButtonRect(val x: Float, val y: Float, val w: Float, val h: Float, val id: String)
    private val buttons = mutableListOf<ButtonRect>()

    init {
        createFontAtlas()
        createDescriptors()
        createPipeline()
        createVertexBuffer()
        createLitPipeline()
        createGpuPipeline()
    }

    // ---- Font Atlas using STB TrueType with a minimal embedded bitmap font ----

    private fun createFontAtlas() {
        val bitmapBuf = MemoryUtil.memCalloc(ATLAS_W * ATLAS_H)
        try {
            glyphs = renderPixelFont(bitmapBuf)
            uploadFontTexture(bitmapBuf)
        } finally {
            MemoryUtil.memFree(bitmapBuf)
        }
    }

    private fun renderPixelFont(bitmap: ByteBuffer): Array<GlyphInfo> {
        val cellW = 8
        val cellH = 14
        val glyphW = 5
        val glyphH = 7
        val cols = ATLAS_W / cellW

        return Array(NUM_CHARS) { i ->
            val ch = (FIRST_CHAR + i).toChar()
            val col = i % cols
            val row = i / cols
            val ox = col * cellW
            val oy = row * cellH

            val pattern = getGlyphPattern(ch)
            for (py in 0 until glyphH) {
                for (px in 0 until glyphW) {
                    if (pattern[py] and (1 shl (glyphW - 1 - px)) != 0) {
                        val bx = ox + px + 1
                        val by = oy + py + 3
                        if (bx < ATLAS_W && by < ATLAS_H) {
                            bitmap.put(by * ATLAS_W + bx, 0xFF.toByte())
                        }
                    }
                }
            }

            GlyphInfo(ox, oy, ox + cellW, oy + cellH, 0f, -10f, cellW.toFloat())
        }
    }

    /** 5x7 pixel font patterns for printable ASCII. Each int is a row bitmask (5 bits wide). */
    private fun getGlyphPattern(ch: Char): IntArray {
        return when (ch) {
            ' ' -> intArrayOf(0, 0, 0, 0, 0, 0, 0)
            '!' -> intArrayOf(0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04)
            '"' -> intArrayOf(0x0A, 0x0A, 0, 0, 0, 0, 0)
            '#' -> intArrayOf(0x0A, 0x1F, 0x0A, 0x0A, 0x1F, 0x0A, 0)
            '$' -> intArrayOf(0x04, 0x0F, 0x14, 0x0E, 0x05, 0x1E, 0x04)
            '%' -> intArrayOf(0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03)
            '&' -> intArrayOf(0x08, 0x14, 0x14, 0x08, 0x15, 0x12, 0x0D)
            '\'' -> intArrayOf(0x04, 0x04, 0, 0, 0, 0, 0)
            '(' -> intArrayOf(0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02)
            ')' -> intArrayOf(0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08)
            '*' -> intArrayOf(0, 0x04, 0x15, 0x0E, 0x15, 0x04, 0)
            '+' -> intArrayOf(0, 0x04, 0x04, 0x1F, 0x04, 0x04, 0)
            ',' -> intArrayOf(0, 0, 0, 0, 0, 0x04, 0x08)
            '-' -> intArrayOf(0, 0, 0, 0x1F, 0, 0, 0)
            '.' -> intArrayOf(0, 0, 0, 0, 0, 0, 0x04)
            '/' -> intArrayOf(0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10)
            '0' -> intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E)
            '1' -> intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E)
            '2' -> intArrayOf(0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F)
            '3' -> intArrayOf(0x0E, 0x11, 0x01, 0x06, 0x01, 0x11, 0x0E)
            '4' -> intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02)
            '5' -> intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E)
            '6' -> intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E)
            '7' -> intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08)
            '8' -> intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E)
            '9' -> intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C)
            ':' -> intArrayOf(0, 0, 0x04, 0, 0x04, 0, 0)
            'A', 'a' -> intArrayOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11)
            'B', 'b' -> intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E)
            'C', 'c' -> intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E)
            'D', 'd' -> intArrayOf(0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E)
            'E', 'e' -> intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F)
            'F', 'f' -> intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10)
            'G', 'g' -> intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F)
            'H', 'h' -> intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11)
            'I', 'i' -> intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E)
            'J', 'j' -> intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C)
            'K', 'k' -> intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11)
            'L', 'l' -> intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F)
            'M', 'm' -> intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11)
            'N', 'n' -> intArrayOf(0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11)
            'O', 'o' -> intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E)
            'P', 'p' -> intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10)
            'Q', 'q' -> intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D)
            'R', 'r' -> intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11)
            'S', 's' -> intArrayOf(0x0E, 0x11, 0x10, 0x0E, 0x01, 0x11, 0x0E)
            'T', 't' -> intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04)
            'U', 'u' -> intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E)
            'V', 'v' -> intArrayOf(0x11, 0x11, 0x11, 0x11, 0x0A, 0x0A, 0x04)
            'W', 'w' -> intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11)
            'X', 'x' -> intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11)
            'Y', 'y' -> intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04)
            'Z', 'z' -> intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F)
            '[' -> intArrayOf(0x0E, 0x08, 0x08, 0x08, 0x08, 0x08, 0x0E)
            ']' -> intArrayOf(0x0E, 0x02, 0x02, 0x02, 0x02, 0x02, 0x0E)
            '_' -> intArrayOf(0, 0, 0, 0, 0, 0, 0x1F)
            '<' -> intArrayOf(0x02, 0x04, 0x08, 0x10, 0x08, 0x04, 0x02)
            '>' -> intArrayOf(0x08, 0x04, 0x02, 0x01, 0x02, 0x04, 0x08)
            '=' -> intArrayOf(0, 0, 0x1F, 0, 0x1F, 0, 0)
            else -> intArrayOf(0x1F, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1F) // box for unknown
        }
    }

    private fun uploadFontTexture(bitmap: ByteBuffer) {
        MemoryStack.stackPush().use { stack ->
            // Create font image
            val imageCI = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8_UNORM)
                .extent { it.width(ATLAS_W).height(ATLAS_H).depth(1) }
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)

            val allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_GPU_ONLY)

            val pImage = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateImage(context.allocator, imageCI, allocCI, pImage, pAlloc, null) == VK_SUCCESS)
            fontImage = pImage.get(0)
            fontImageAlloc = pAlloc.get(0)

            // Create staging buffer
            val bufSize = (ATLAS_W * ATLAS_H).toLong()
            val stageBufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bufSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val stageAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_ONLY)
            val pStageBuf = stack.mallocLong(1)
            val pStageAlloc = stack.mallocPointer(1)
            vmaCreateBuffer(context.allocator, stageBufCI, stageAllocCI, pStageBuf, pStageAlloc, null)
            val stageBuf = pStageBuf.get(0)
            val stageAlloc = pStageAlloc.get(0)

            // Copy bitmap data to staging
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, stageAlloc, ppData)
            val mapped = ppData.getByteBuffer(0, ATLAS_W * ATLAS_H)
            bitmap.position(0)
            mapped.put(bitmap)
            mapped.flip()
            vmaUnmapMemory(context.allocator, stageAlloc)

            // One-shot command buffer to upload
            val poolCI = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(context.graphicsQueueFamily)
            val pPool = stack.mallocLong(1)
            vkCreateCommandPool(context.vkDevice, poolCI, null, pPool)
            val cmdPool = pPool.get(0)

            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmdBuf = stack.mallocPointer(1)
            vkAllocateCommandBuffers(context.vkDevice, allocInfo, pCmdBuf)
            val cmd = VkCommandBuffer(pCmdBuf.get(0), context.vkDevice)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            vkBeginCommandBuffer(cmd, beginInfo)

            // Transition to TRANSFER_DST
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(fontImage)
                .subresourceRange { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1) }
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier)

            // Copy buffer to image
            val region = VkBufferImageCopy.calloc(1, stack)
            region.get(0)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                .imageOffset { it.x(0).y(0).z(0) }
                .imageExtent { it.width(ATLAS_W).height(ATLAS_H).depth(1) }
            vkCmdCopyBufferToImage(cmd, stageBuf, fontImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)

            // Transition to SHADER_READ_ONLY
            barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier)

            vkEndCommandBuffer(cmd)

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd))
            vkQueueSubmit(context.graphicsQueue, submitInfo, VK_NULL_HANDLE)
            vkQueueWaitIdle(context.graphicsQueue)

            vkDestroyCommandPool(context.vkDevice, cmdPool, null)
            vmaDestroyBuffer(context.allocator, stageBuf, stageAlloc)

            // Create image view
            val viewCI = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(fontImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8_UNORM)
                .subresourceRange { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1) }
            val pView = stack.mallocLong(1)
            vkCreateImageView(context.vkDevice, viewCI, null, pView)
            fontImageView = pView.get(0)

            // Create sampler
            val samplerCI = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            val pSampler = stack.mallocLong(1)
            vkCreateSampler(context.vkDevice, samplerCI, null, pSampler)
            fontSampler = pSampler.get(0)
        }
    }

    private fun createDescriptors() {
        MemoryStack.stackPush().use { stack ->
            // Descriptor set layout
            val binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
            binding.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            val layoutCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(binding)
            val pLayout = stack.mallocLong(1)
            vkCreateDescriptorSetLayout(context.vkDevice, layoutCI, null, pLayout)
            descriptorSetLayout = pLayout.get(0)

            // Descriptor pool
            val poolSize = VkDescriptorPoolSize.calloc(1, stack)
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
            val poolCI = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(poolSize)
            val pPool = stack.mallocLong(1)
            vkCreateDescriptorPool(context.vkDevice, poolCI, null, pPool)
            descriptorPool = pPool.get(0)

            // Allocate descriptor set
            val dsAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout))
            val pSet = stack.mallocLong(1)
            vkAllocateDescriptorSets(context.vkDevice, dsAllocInfo, pSet)
            descriptorSet = pSet.get(0)

            // Write descriptor
            val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
            imageInfo.get(0)
                .sampler(fontSampler)
                .imageView(fontImageView)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val write = VkWriteDescriptorSet.calloc(1, stack)
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(imageInfo)
            vkUpdateDescriptorSets(context.vkDevice, write, null)
        }
    }

    private fun createPipeline() {
        MemoryStack.stackPush().use { stack ->
            vertShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/ui.vert.glsl")
            fragShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/ui.frag.glsl")

            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertShaderModule).pName(stack.UTF8("main"))
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragShaderModule).pName(stack.UTF8("main"))

            // Vertex input: vec2 pos + vec4 color + vec2 uv = 8 floats = 32 bytes
            val bindingDesc = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDesc.get(0).binding(0).stride(floatsPerVertex * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

            val attrDescs = VkVertexInputAttributeDescription.calloc(3, stack)
            attrDescs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)        // pos
            attrDescs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8)   // color
            attrDescs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(24)        // uv

            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(bindingDesc)
                .pVertexAttributeDescriptions(attrDescs)

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false)
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            val depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).stencilTestEnable(false)

            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            colorBlendAttachment.get(0)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD)
            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).logicOpEnable(false).pAttachments(colorBlendAttachment)
            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

            // Pipeline layout with descriptor set for font texture
            val pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
            val pLayout = stack.mallocLong(1)
            check(vkCreatePipelineLayout(context.vkDevice, pipelineLayoutCI, null, pLayout) == VK_SUCCESS)
            pipelineLayout = pLayout.get(0)

            val pipelineCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineCI.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling)
                .pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState)
                .layout(pipelineLayout).renderPass(renderPass).subpass(0)

            val pPipeline = stack.mallocLong(1)
            check(vkCreateGraphicsPipelines(context.vkDevice, VK_NULL_HANDLE, pipelineCI, null, pPipeline) == VK_SUCCESS)
            pipeline = pPipeline.get(0)
        }
    }

    private fun createVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = (maxQuads * verticesPerQuad * floatsPerVertex * 4).toLong()
            val bufferCI = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(bufferSize).usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val allocCI = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
            val pBuffer = stack.mallocLong(1)
            val pAllocation = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufferCI, allocCI, pBuffer, pAllocation, null) == VK_SUCCESS)
            vertexBuffer = pBuffer.get(0)
            vertexAllocation = pAllocation.get(0)
        }
    }

    // ---- Lit-world Pipeline ----

    private fun createLitPipeline() {
        createLitDescriptors()
        createLitUboBuffers()
        createLitVertexBuffer()

        MemoryStack.stackPush().use { stack ->
            litVertShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/world_lit.vert.glsl")
            litFragShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/world_lit.frag.glsl")

            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(litVertShaderModule).pName(stack.UTF8("main"))
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(litFragShaderModule).pName(stack.UTF8("main"))

            // Vertex input: vec2 pos + vec4 color + vec3 worldPos + vec3 normal = 12 floats = 48 bytes
            val bindingDesc = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDesc.get(0).binding(0).stride(litFloatsPerVertex * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

            val attrDescs = VkVertexInputAttributeDescription.calloc(4, stack)
            attrDescs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)           // pos (2 floats)
            attrDescs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8)     // color (4 floats)
            attrDescs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32_SFLOAT).offset(24)       // worldPos (3 floats)
            attrDescs.get(3).binding(0).location(3).format(VK_FORMAT_R32G32B32_SFLOAT).offset(36)       // normal (3 floats)

            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(bindingDesc)
                .pVertexAttributeDescriptions(attrDescs)

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false)
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            val depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).stencilTestEnable(false)

            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            colorBlendAttachment.get(0)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD)
            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).logicOpEnable(false).pAttachments(colorBlendAttachment)
            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

            val pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(litDescriptorSetLayout))
            val pLayout = stack.mallocLong(1)
            check(vkCreatePipelineLayout(context.vkDevice, pipelineLayoutCI, null, pLayout) == VK_SUCCESS)
            litPipelineLayout = pLayout.get(0)

            val pipelineCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineCI.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling)
                .pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState)
                .layout(litPipelineLayout).renderPass(renderPass).subpass(0)

            val pPipeline = stack.mallocLong(1)
            check(vkCreateGraphicsPipelines(context.vkDevice, VK_NULL_HANDLE, pipelineCI, null, pPipeline) == VK_SUCCESS)
            litPipeline = pPipeline.get(0)
        }
    }

    private fun createLitDescriptors() {
        MemoryStack.stackPush().use { stack ->
            // Layout: binding 0 = UBO (lighting data), binding 1 = SSBO (occupancy grid)
            val bindings = VkDescriptorSetLayoutBinding.calloc(2, stack)
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

            val layoutCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings)
            val pLayout = stack.mallocLong(1)
            vkCreateDescriptorSetLayout(context.vkDevice, layoutCI, null, pLayout)
            litDescriptorSetLayout = pLayout.get(0)

            // Pool
            val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
            val poolCI = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(poolSizes)
            val pPool = stack.mallocLong(1)
            vkCreateDescriptorPool(context.vkDevice, poolCI, null, pPool)
            litDescriptorPool = pPool.get(0)

            // Allocate set
            val dsAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(litDescriptorPool)
                .pSetLayouts(stack.longs(litDescriptorSetLayout))
            val pSet = stack.mallocLong(1)
            vkAllocateDescriptorSets(context.vkDevice, dsAllocInfo, pSet)
            litDescriptorSet = pSet.get(0)
        }
    }

    private fun createLitUboBuffers() {
        MemoryStack.stackPush().use { stack ->
            // LightingUBO: 4 ints (16 bytes) + 32 * vec4 (512 bytes) + 32 * vec4 (512 bytes) = 1040 bytes
            val uboSize = 16 + 32 * 16 + 32 * 16
            val bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(uboSize.toLong())
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val allocCI = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
            val pBuf = stack.mallocLong(1)
            val pAlloc = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufCI, allocCI, pBuf, pAlloc, null) == VK_SUCCESS)
            lightingUboBuffer = pBuf.get(0)
            lightingUboAlloc = pAlloc.get(0)

            // Occupancy SSBO: initial size = 4 bytes (empty grid)
            occupancySsboSize = 4
            val ssboCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(occupancySsboSize)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            check(vmaCreateBuffer(context.allocator, ssboCI, allocCI, pBuf, pAlloc, null) == VK_SUCCESS)
            occupancySsboBuffer = pBuf.get(0)
            occupancySsboAlloc = pAlloc.get(0)

            updateLitDescriptorSet(uboSize.toLong())
        }
    }

    private fun updateLitDescriptorSet(uboSize: Long) {
        MemoryStack.stackPush().use { stack ->
            val writes = VkWriteDescriptorSet.calloc(2, stack)

            val uboBI = VkDescriptorBufferInfo.calloc(1, stack)
            uboBI.get(0).buffer(lightingUboBuffer).offset(0).range(uboSize)
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(litDescriptorSet)
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(uboBI)

            val ssboBI = VkDescriptorBufferInfo.calloc(1, stack)
            ssboBI.get(0).buffer(occupancySsboBuffer).offset(0).range(occupancySsboSize)
            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(litDescriptorSet)
                .dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(ssboBI)

            vkUpdateDescriptorSets(context.vkDevice, writes, null)
        }
    }

    private fun createLitVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = (maxLitQuads * verticesPerQuad * litFloatsPerVertex * 4).toLong()
            val bufferCI = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(bufferSize).usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val allocCI = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
            val pBuffer = stack.mallocLong(1)
            val pAllocation = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufferCI, allocCI, pBuffer, pAllocation, null) == VK_SUCCESS)
            litVertexBuffer = pBuffer.get(0)
            litVertexAllocation = pAllocation.get(0)
        }
    }

    // ---- Lit Quad Public API ----

    /**
     * Data class for light sources to upload to the GPU.
     */
    data class LightData(
        val x: Float, val y: Float, val z: Float,
        val intensity: Float,
        val r: Float, val g: Float, val b: Float,
        val radius: Float
    )

    /**
     * Update the lighting UBO and occupancy grid SSBO for per-pixel lighting.
     * Call this once per frame before drawing lit quads.
     *
     * @param lights List of active lights
     * @param occupancyGrid Flat array of occupancy values (0 or 1) indexed as [z * w * h + y * w + x]
     * @param gridW Grid width
     * @param gridH Grid height
     * @param gridD Grid depth
     */
    fun updateLighting(
        lights: List<LightData>,
        occupancyGrid: IntArray,
        gridW: Int, gridH: Int, gridD: Int
    ) {
        // Update lighting UBO
        MemoryStack.stackPush().use { stack ->
            val uboSize = 16 + 32 * 16 + 32 * 16
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, lightingUboAlloc, ppData)
            val buf = ppData.getByteBuffer(0, uboSize)

            val count = minOf(lights.size, 32)
            buf.putInt(count)
            buf.putInt(gridW)
            buf.putInt(gridH)
            buf.putInt(gridD)

            // lightPosIntensity[32] — vec4 each
            val floatBuf = buf.asFloatBuffer()
            for (i in 0 until 32) {
                if (i < count) {
                    val l = lights[i]
                    floatBuf.put(l.x); floatBuf.put(l.y); floatBuf.put(l.z); floatBuf.put(l.intensity)
                } else {
                    floatBuf.put(0f); floatBuf.put(0f); floatBuf.put(0f); floatBuf.put(0f)
                }
            }
            // lightColorRadius[32] — vec4 each
            for (i in 0 until 32) {
                if (i < count) {
                    val l = lights[i]
                    floatBuf.put(l.r); floatBuf.put(l.g); floatBuf.put(l.b); floatBuf.put(l.radius)
                } else {
                    floatBuf.put(0f); floatBuf.put(0f); floatBuf.put(0f); floatBuf.put(0f)
                }
            }

            vmaUnmapMemory(context.allocator, lightingUboAlloc)
        }

        // Update occupancy SSBO — recreate if size changed
        val requiredSize = (gridW * gridH * gridD * 4).toLong().coerceAtLeast(4L)
        if (requiredSize != occupancySsboSize) {
            // Destroy old SSBO
            if (occupancySsboBuffer != VK_NULL_HANDLE) {
                vmaDestroyBuffer(context.allocator, occupancySsboBuffer, occupancySsboAlloc)
            }
            occupancySsboSize = requiredSize
            MemoryStack.stackPush().use { stack ->
                val ssboCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(occupancySsboSize)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                val allocCI = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                val pBuf = stack.mallocLong(1)
                val pAlloc = stack.mallocPointer(1)
                check(vmaCreateBuffer(context.allocator, ssboCI, allocCI, pBuf, pAlloc, null) == VK_SUCCESS)
                occupancySsboBuffer = pBuf.get(0)
                occupancySsboAlloc = pAlloc.get(0)
            }
            // Re-bind descriptor
            val uboSize = (16 + 32 * 16 + 32 * 16).toLong()
            updateLitDescriptorSet(uboSize)
        }

        // Upload occupancy data
        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, occupancySsboAlloc, ppData)
            val buf = ppData.getByteBuffer(0, occupancySsboSize.toInt()).asIntBuffer()
            val count = minOf(occupancyGrid.size, gridW * gridH * gridD)
            buf.put(occupancyGrid, 0, count)
            vmaUnmapMemory(context.allocator, occupancySsboAlloc)
        }
    }

    /**
     * Draw a lit quad with per-pixel lighting. Pass world-space corner positions and face normal.
     * The shader will compute per-pixel lighting using interpolated world positions.
     *
     * Screen-space positions (sx0..sy3) are in pixels.
     * World-space positions (wx0..wz3) are the 3D positions of each corner.
     * Normal (nx,ny,nz) is the face normal.
     * Base color (r,g,b) is the base diffuse color (with face shade pre-applied).
     */
    fun drawLitQuad(
        sx0: Float, sy0: Float, sx1: Float, sy1: Float,
        sx2: Float, sy2: Float, sx3: Float, sy3: Float,
        wx0: Float, wy0: Float, wz0: Float,
        wx1: Float, wy1: Float, wz1: Float,
        wx2: Float, wy2: Float, wz2: Float,
        wx3: Float, wy3: Float, wz3: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        if (litQuadCount >= maxLitQuads) return
        val n0x = pixelToNdcX(sx0); val n0y = pixelToNdcY(sy0)
        val n1x = pixelToNdcX(sx1); val n1y = pixelToNdcY(sy1)
        val n2x = pixelToNdcX(sx2); val n2y = pixelToNdcY(sy2)
        val n3x = pixelToNdcX(sx3); val n3y = pixelToNdcY(sy3)
        val offset = litQuadCount * verticesPerQuad * litFloatsPerVertex

        // Check diagonal split direction (same logic as drawQuad)
        val d02x = n2x - n0x; val d02y = n2y - n0y
        val cross1 = d02x * (n1y - n0y) - d02y * (n1x - n0x)
        val cross3 = d02x * (n3y - n0y) - d02y * (n3x - n0x)

        if (cross1 * cross3 < 0f) {
            // Split along diagonal 0–2: triangles (0,1,2) and (0,2,3)
            putLitVertex(offset + 0,  n0x, n0y, r, g, b, a, wx0, wy0, wz0, nx, ny, nz)
            putLitVertex(offset + 12, n1x, n1y, r, g, b, a, wx1, wy1, wz1, nx, ny, nz)
            putLitVertex(offset + 24, n2x, n2y, r, g, b, a, wx2, wy2, wz2, nx, ny, nz)
            putLitVertex(offset + 36, n0x, n0y, r, g, b, a, wx0, wy0, wz0, nx, ny, nz)
            putLitVertex(offset + 48, n2x, n2y, r, g, b, a, wx2, wy2, wz2, nx, ny, nz)
            putLitVertex(offset + 60, n3x, n3y, r, g, b, a, wx3, wy3, wz3, nx, ny, nz)
        } else {
            // Split along diagonal 1–3: triangles (0,1,3) and (1,2,3)
            putLitVertex(offset + 0,  n0x, n0y, r, g, b, a, wx0, wy0, wz0, nx, ny, nz)
            putLitVertex(offset + 12, n1x, n1y, r, g, b, a, wx1, wy1, wz1, nx, ny, nz)
            putLitVertex(offset + 24, n3x, n3y, r, g, b, a, wx3, wy3, wz3, nx, ny, nz)
            putLitVertex(offset + 36, n1x, n1y, r, g, b, a, wx1, wy1, wz1, nx, ny, nz)
            putLitVertex(offset + 48, n2x, n2y, r, g, b, a, wx2, wy2, wz2, nx, ny, nz)
            putLitVertex(offset + 60, n3x, n3y, r, g, b, a, wx3, wy3, wz3, nx, ny, nz)
        }
        litQuadCount++
    }

    private fun putLitVertex(offset: Int, x: Float, y: Float, r: Float, g: Float, b: Float, a: Float,
                             wx: Float, wy: Float, wz: Float, nx: Float, ny: Float, nz: Float) {
        litVertexData[offset] = x
        litVertexData[offset + 1] = y
        litVertexData[offset + 2] = r
        litVertexData[offset + 3] = g
        litVertexData[offset + 4] = b
        litVertexData[offset + 5] = a
        litVertexData[offset + 6] = wx
        litVertexData[offset + 7] = wy
        litVertexData[offset + 8] = wz
        litVertexData[offset + 9] = nx
        litVertexData[offset + 10] = ny
        litVertexData[offset + 11] = nz
    }

    // ---- GPU-rasterized 3D Pipeline (depth-buffered) ----

    private fun createGpuPipeline() {
        createGpuVertexBuffer()

        MemoryStack.stackPush().use { stack ->
            gpuVertShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/world_gpu.vert.glsl")
            gpuFragShaderModule = ShaderCompiler.loadShaderModule(context.vkDevice, "shaders/world_lit.frag.glsl")

            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(gpuVertShaderModule).pName(stack.UTF8("main"))
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(gpuFragShaderModule).pName(stack.UTF8("main"))

            // Vertex input: vec3 worldPos + vec4 color + vec3 normal = 10 floats = 40 bytes
            val bindingDesc = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDesc.get(0).binding(0).stride(gpuFloatsPerVertex * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

            val attrDescs = VkVertexInputAttributeDescription.calloc(3, stack)
            attrDescs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)        // worldPos (3 floats)
            attrDescs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(12)    // color (4 floats)
            attrDescs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32_SFLOAT).offset(28)       // normal (3 floats)

            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(bindingDesc)
                .pVertexAttributeDescriptions(attrDescs)

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false)
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
            // Back-face culling on GPU
            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f).cullMode(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            // Depth test ENABLED for GPU rendering
            val depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS).stencilTestEnable(false)

            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            colorBlendAttachment.get(0)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(false)
            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).logicOpEnable(false).pAttachments(colorBlendAttachment)
            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

            // Push constant for VP matrix (64 bytes = mat4)
            val pushConstantRange = VkPushConstantRange.calloc(1, stack)
            pushConstantRange.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64)

            val pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(litDescriptorSetLayout))
                .pPushConstantRanges(pushConstantRange)
            val pLayout = stack.mallocLong(1)
            check(vkCreatePipelineLayout(context.vkDevice, pipelineLayoutCI, null, pLayout) == VK_SUCCESS)
            gpuPipelineLayout = pLayout.get(0)

            val pipelineCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineCI.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling)
                .pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState)
                .layout(gpuPipelineLayout).renderPass(renderPass).subpass(0)

            val pPipeline = stack.mallocLong(1)
            check(vkCreateGraphicsPipelines(context.vkDevice, VK_NULL_HANDLE, pipelineCI, null, pPipeline) == VK_SUCCESS)
            gpuPipeline = pPipeline.get(0)
        }
    }

    private fun createGpuVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = (maxGpuQuads * verticesPerQuad * gpuFloatsPerVertex * 4).toLong()
            val bufferCI = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(bufferSize).usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val allocCI = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
            val pBuffer = stack.mallocLong(1)
            val pAllocation = stack.mallocPointer(1)
            check(vmaCreateBuffer(context.allocator, bufferCI, allocCI, pBuffer, pAllocation, null) == VK_SUCCESS)
            gpuVertexBuffer = pBuffer.get(0)
            gpuVertexAllocation = pAllocation.get(0)
        }
    }

    /**
     * Set the view-projection matrix for GPU rendering.
     * Call once per frame before drawGpuQuad calls.
     * Takes a column-major float array (16 floats) from JOML Matrix4f.get(FloatArray).
     */
    fun setViewProjection(matrix: FloatArray) {
        System.arraycopy(matrix, 0, vpMatrix, 0, 16)
    }

    /**
     * Draw a quad using full GPU 3D rasterization (depth-buffered, VP transform on GPU).
     * No CPU projection needed — just pass world-space corners and the GPU does the rest.
     *
     * Vertices should be wound counter-clockwise when viewed from the front.
     * Back-face culling is handled by the GPU pipeline.
     */
    fun drawGpuQuad(
        wx0: Float, wy0: Float, wz0: Float,
        wx1: Float, wy1: Float, wz1: Float,
        wx2: Float, wy2: Float, wz2: Float,
        wx3: Float, wy3: Float, wz3: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        if (gpuQuadCount >= maxGpuQuads) return
        val offset = gpuQuadCount * verticesPerQuad * gpuFloatsPerVertex

        // Two triangles: (0,1,2) and (0,2,3) — CCW winding
        putGpuVertex(offset + 0,  wx0, wy0, wz0, r, g, b, a, nx, ny, nz)
        putGpuVertex(offset + 10, wx1, wy1, wz1, r, g, b, a, nx, ny, nz)
        putGpuVertex(offset + 20, wx2, wy2, wz2, r, g, b, a, nx, ny, nz)
        putGpuVertex(offset + 30, wx0, wy0, wz0, r, g, b, a, nx, ny, nz)
        putGpuVertex(offset + 40, wx2, wy2, wz2, r, g, b, a, nx, ny, nz)
        putGpuVertex(offset + 50, wx3, wy3, wz3, r, g, b, a, nx, ny, nz)

        gpuQuadCount++
    }

    private fun putGpuVertex(offset: Int, wx: Float, wy: Float, wz: Float,
                             r: Float, g: Float, b: Float, a: Float,
                             nx: Float, ny: Float, nz: Float) {
        gpuVertexData[offset] = wx
        gpuVertexData[offset + 1] = wy
        gpuVertexData[offset + 2] = wz
        gpuVertexData[offset + 3] = r
        gpuVertexData[offset + 4] = g
        gpuVertexData[offset + 5] = b
        gpuVertexData[offset + 6] = a
        gpuVertexData[offset + 7] = nx
        gpuVertexData[offset + 8] = ny
        gpuVertexData[offset + 9] = nz
    }

    // ---- Public API ----

    fun beginFrame() {
        quadCount = 0
        litQuadCount = 0
        gpuQuadCount = 0
        buttons.clear()
    }

    private fun pixelToNdcX(px: Float): Float = (px / screenWidth) * 2f - 1f
    private fun pixelToNdcY(py: Float): Float = (py / screenHeight) * 2f - 1f

    /** Draw a solid colored rectangle (no texture). */
    fun drawRect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        if (quadCount >= maxQuads) return
        val x0 = pixelToNdcX(x); val y0 = pixelToNdcY(y)
        val x1 = pixelToNdcX(x + w); val y1 = pixelToNdcY(y + h)
        val offset = quadCount * verticesPerQuad * floatsPerVertex
        // UV = (-1,-1) signals solid color in shader
        putVertex(offset + 0,  x0, y0, r, g, b, a, -1f, -1f)
        putVertex(offset + 8,  x1, y0, r, g, b, a, -1f, -1f)
        putVertex(offset + 16, x0, y1, r, g, b, a, -1f, -1f)
        putVertex(offset + 24, x1, y0, r, g, b, a, -1f, -1f)
        putVertex(offset + 32, x1, y1, r, g, b, a, -1f, -1f)
        putVertex(offset + 40, x0, y1, r, g, b, a, -1f, -1f)
        quadCount++
    }

    /**
     * Draw an arbitrary solid-color quadrilateral given 4 screen-space pixel corners.
     * Corners should be in order (e.g. CW or CCW winding around the face).
     * Automatically handles both winding orders by checking the cross product
     * to avoid bowtie triangulation.
     * Two triangles: (0,1,2) and (0,2,3) — or (0,1,3) and (1,2,3) if needed.
     */
    fun drawQuad(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        if (quadCount >= maxQuads) return
        val nx0 = pixelToNdcX(x0); val ny0 = pixelToNdcY(y0)
        val nx1 = pixelToNdcX(x1); val ny1 = pixelToNdcY(y1)
        val nx2 = pixelToNdcX(x2); val ny2 = pixelToNdcY(y2)
        val nx3 = pixelToNdcX(x3); val ny3 = pixelToNdcY(y3)
        val offset = quadCount * verticesPerQuad * floatsPerVertex

        // Check if diagonal (0→2) is a valid split by testing that vertices 1 and 3
        // lie on opposite sides of the line 0→2.  If not, use the other diagonal.
        val d02x = nx2 - nx0; val d02y = ny2 - ny0
        val cross1 = d02x * (ny1 - ny0) - d02y * (nx1 - nx0)
        val cross3 = d02x * (ny3 - ny0) - d02y * (nx3 - nx0)

        if (cross1 * cross3 < 0f) {
            // Standard split along diagonal 0–2: triangles (0,1,2) and (0,2,3)
            putVertex(offset + 0,  nx0, ny0, r, g, b, a, -1f, -1f)
            putVertex(offset + 8,  nx1, ny1, r, g, b, a, -1f, -1f)
            putVertex(offset + 16, nx2, ny2, r, g, b, a, -1f, -1f)
            putVertex(offset + 24, nx0, ny0, r, g, b, a, -1f, -1f)
            putVertex(offset + 32, nx2, ny2, r, g, b, a, -1f, -1f)
            putVertex(offset + 40, nx3, ny3, r, g, b, a, -1f, -1f)
        } else {
            // Split along diagonal 1–3: triangles (0,1,3) and (1,2,3)
            putVertex(offset + 0,  nx0, ny0, r, g, b, a, -1f, -1f)
            putVertex(offset + 8,  nx1, ny1, r, g, b, a, -1f, -1f)
            putVertex(offset + 16, nx3, ny3, r, g, b, a, -1f, -1f)
            putVertex(offset + 24, nx1, ny1, r, g, b, a, -1f, -1f)
            putVertex(offset + 32, nx2, ny2, r, g, b, a, -1f, -1f)
            putVertex(offset + 40, nx3, ny3, r, g, b, a, -1f, -1f)
        }
        quadCount++
    }

    /** Draw a text string at pixel position. */
    fun drawText(text: String, x: Float, y: Float, r: Float = 1f, green: Float = 1f, b: Float = 1f, a: Float = 1f, scale: Float = 1f) {
        if (glyphs.isEmpty()) return
        var cursorX = x
        val cellW = 8f * scale
        val cellH = 14f * scale

        for (ch in text) {
            val idx = ch.code - FIRST_CHAR
            if (idx < 0 || idx >= NUM_CHARS) { cursorX += cellW; continue }
            if (quadCount >= maxQuads) return

            val gl = glyphs[idx]
            val u0 = gl.x0.toFloat() / ATLAS_W
            val v0 = gl.y0.toFloat() / ATLAS_H
            val u1 = gl.x1.toFloat() / ATLAS_W
            val v1 = gl.y1.toFloat() / ATLAS_H

            val gx = cursorX + gl.xoff * scale
            val gy = y + (gl.yoff + 14f) * scale

            val px0 = pixelToNdcX(gx); val py0 = pixelToNdcY(gy)
            val px1 = pixelToNdcX(gx + cellW); val py1 = pixelToNdcY(gy + cellH)

            val offset = quadCount * verticesPerQuad * floatsPerVertex
            putVertex(offset + 0,  px0, py0, r, green, b, a, u0, v0)
            putVertex(offset + 8,  px1, py0, r, green, b, a, u1, v0)
            putVertex(offset + 16, px0, py1, r, green, b, a, u0, v1)
            putVertex(offset + 24, px1, py0, r, green, b, a, u1, v0)
            putVertex(offset + 32, px1, py1, r, green, b, a, u1, v1)
            putVertex(offset + 40, px0, py1, r, green, b, a, u0, v1)
            quadCount++

            cursorX += gl.xadvance * scale
        }
    }

    /** Measure text width in pixels. */
    fun textWidth(text: String, scale: Float = 1f): Float {
        if (glyphs.isEmpty()) return 0f
        var w = 0f
        for (ch in text) {
            val idx = ch.code - FIRST_CHAR
            if (idx < 0 || idx >= NUM_CHARS) { w += 8f * scale; continue }
            w += glyphs[idx].xadvance * scale
        }
        return w
    }

    /** Draw a button with text label. Returns true if clicked. */
    fun button(label: String, x: Float, y: Float, w: Float, h: Float, input: InputSystem): Boolean {
        val mx = input.getMouseX()
        val my = input.getMouseY()
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + h
        val clicked = hovered && input.isMouseButtonJustPressed(0)

        if (hovered) {
            drawRect(x, y, w, h, 0.35f, 0.45f, 0.65f, 0.95f)
        } else {
            drawRect(x, y, w, h, 0.22f, 0.27f, 0.40f, 0.9f)
        }
        // Border
        val bw = 2f
        drawRect(x, y, w, bw, 0.5f, 0.6f, 0.8f)
        drawRect(x, y + h - bw, w, bw, 0.5f, 0.6f, 0.8f)
        drawRect(x, y, bw, h, 0.5f, 0.6f, 0.8f)
        drawRect(x + w - bw, y, bw, h, 0.5f, 0.6f, 0.8f)

        // Centered text
        val textScale = 1.5f
        val tw = textWidth(label, textScale)
        val tx = x + (w - tw) / 2f
        val ty = y + (h - 14f * textScale) / 2f
        drawText(label, tx, ty, 0.95f, 0.95f, 1f, 1f, textScale)

        buttons.add(ButtonRect(x, y, w, h, label))
        return clicked
    }

    private fun putVertex(offset: Int, x: Float, y: Float, r: Float, g: Float, b: Float, a: Float, u: Float, v: Float) {
        vertexData[offset] = x
        vertexData[offset + 1] = y
        vertexData[offset + 2] = r
        vertexData[offset + 3] = g
        vertexData[offset + 4] = b
        vertexData[offset + 5] = a
        vertexData[offset + 6] = u
        vertexData[offset + 7] = v
    }

    fun render(commandBuffer: VkCommandBuffer) {
        // Render GPU-rasterized quads first (depth-buffered, no sorting needed)
        if (gpuQuadCount > 0) {
            MemoryStack.stackPush().use { stack ->
                val ppData = stack.mallocPointer(1)
                vmaMapMemory(context.allocator, gpuVertexAllocation, ppData)
                val mapped = ppData.getByteBuffer(0, gpuVertexData.size * 4)
                mapped.asFloatBuffer().put(gpuVertexData, 0, gpuQuadCount * verticesPerQuad * gpuFloatsPerVertex)
                vmaUnmapMemory(context.allocator, gpuVertexAllocation)

                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, gpuPipeline)
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, gpuPipelineLayout, 0, stack.longs(litDescriptorSet), null)
                // Push VP matrix
                val vpBuf = stack.mallocFloat(16)
                vpBuf.put(vpMatrix).flip()
                vkCmdPushConstants(commandBuffer, gpuPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, vpBuf)
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(gpuVertexBuffer), stack.longs(0L))
                vkCmdDraw(commandBuffer, gpuQuadCount * verticesPerQuad, 1, 0, 0)
            }
        }

        // Render lit quads (CPU-projected, drawn before UI)
        if (litQuadCount > 0) {
            MemoryStack.stackPush().use { stack ->
                val ppData = stack.mallocPointer(1)
                vmaMapMemory(context.allocator, litVertexAllocation, ppData)
                val mapped = ppData.getByteBuffer(0, litVertexData.size * 4)
                mapped.asFloatBuffer().put(litVertexData, 0, litQuadCount * verticesPerQuad * litFloatsPerVertex)
                vmaUnmapMemory(context.allocator, litVertexAllocation)

                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, litPipeline)
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, litPipelineLayout, 0, stack.longs(litDescriptorSet), null)
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(litVertexBuffer), stack.longs(0L))
                vkCmdDraw(commandBuffer, litQuadCount * verticesPerQuad, 1, 0, 0)
            }
        }

        // Render UI quads
        if (quadCount == 0) return
        MemoryStack.stackPush().use { stack ->
            val ppData = stack.mallocPointer(1)
            vmaMapMemory(context.allocator, vertexAllocation, ppData)
            val mapped = ppData.getByteBuffer(0, vertexData.size * 4)
            mapped.asFloatBuffer().put(vertexData, 0, quadCount * verticesPerQuad * floatsPerVertex)
            vmaUnmapMemory(context.allocator, vertexAllocation)

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(descriptorSet), null)
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0L))
            vkCmdDraw(commandBuffer, quadCount * verticesPerQuad, 1, 0, 0)
        }
    }

    override fun close() {
        // ...existing cleanup...
        if (vertexBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, vertexBuffer, vertexAllocation)
        if (fontImage != VK_NULL_HANDLE) vmaDestroyImage(context.allocator, fontImage, fontImageAlloc)
        if (fontImageView != VK_NULL_HANDLE) vkDestroyImageView(context.vkDevice, fontImageView, null)
        if (fontSampler != VK_NULL_HANDLE) vkDestroySampler(context.vkDevice, fontSampler, null)
        if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(context.vkDevice, descriptorPool, null)
        if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(context.vkDevice, descriptorSetLayout, null)
        if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(context.vkDevice, pipeline, null)
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(context.vkDevice, pipelineLayout, null)
        ShaderCompiler.destroyShaderModule(context.vkDevice, vertShaderModule)
        ShaderCompiler.destroyShaderModule(context.vkDevice, fragShaderModule)
        // Lit pipeline cleanup
        if (litVertexBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, litVertexBuffer, litVertexAllocation)
        if (lightingUboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, lightingUboBuffer, lightingUboAlloc)
        if (occupancySsboBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, occupancySsboBuffer, occupancySsboAlloc)
        if (litDescriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(context.vkDevice, litDescriptorPool, null)
        if (litDescriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(context.vkDevice, litDescriptorSetLayout, null)
        if (litPipeline != VK_NULL_HANDLE) vkDestroyPipeline(context.vkDevice, litPipeline, null)
        if (litPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(context.vkDevice, litPipelineLayout, null)
        ShaderCompiler.destroyShaderModule(context.vkDevice, litVertShaderModule)
        ShaderCompiler.destroyShaderModule(context.vkDevice, litFragShaderModule)
        // GPU pipeline cleanup
        if (gpuVertexBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(context.allocator, gpuVertexBuffer, gpuVertexAllocation)
        if (gpuPipeline != VK_NULL_HANDLE) vkDestroyPipeline(context.vkDevice, gpuPipeline, null)
        if (gpuPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(context.vkDevice, gpuPipelineLayout, null)
        ShaderCompiler.destroyShaderModule(context.vkDevice, gpuVertShaderModule)
        ShaderCompiler.destroyShaderModule(context.vkDevice, gpuFragShaderModule)
    }
}
