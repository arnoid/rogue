package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

/**
 * Pass types for the shadow volume rendering pipeline.
 */
enum class PassType {
    AMBIENT,
    STENCIL_FRONT,
    STENCIL_BACK,
    LIT,
    LINE_DEBUG
}

/**
 * A configured Vulkan graphics pipeline for a specific rendering pass.
 */
class RenderPipeline private constructor(
    val handle: Long,
    val layout: Long,
    val passType: PassType,
    private val device: VkDevice,
    val vertShaderModule: Long,
    val fragShaderModule: Long
) : AutoCloseable {

    fun bind(commandBuffer: VkCommandBuffer) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle)
    }

    override fun close() {
        vkDestroyPipeline(device, handle, null)
        vkDestroyPipelineLayout(device, layout, null)
        ShaderCompiler.destroyShaderModule(device, vertShaderModule)
        ShaderCompiler.destroyShaderModule(device, fragShaderModule)
    }

    companion object {
        /**
         * Create a pipeline layout shared by all pass types.
         * Descriptor set layout: set 0 with bindings for SceneUBO(0), LightUBO(1), MaterialUBO(2).
         * Push constant range: 64 bytes (mat4) at vertex stage.
         */
        fun createDescriptorSetLayout(device: VkDevice): Long {
            MemoryStack.stackPush().use { stack ->
                val bindings = VkDescriptorSetLayoutBinding.calloc(4, stack)

                // Binding 0: SceneUBO
                bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)

                // Binding 1: LightUBO
                bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

                // Binding 2: MaterialUBO
                bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

                // Binding 3: OccluderSSBO (per-pixel shadow ray-marching)
                bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

                val layoutCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings)

                val pLayout = stack.mallocLong(1)
                check(vkCreateDescriptorSetLayout(device, layoutCI, null, pLayout) == VK_SUCCESS)
                return pLayout.get(0)
            }
        }

        /**
         * Create a render pipeline for the specified pass type.
         */
        fun create(
            device: VkDevice,
            renderPass: Long,
            descriptorSetLayout: Long,
            passType: PassType,
            vertShaderPath: String,
            fragShaderPath: String,
            extent: Pair<Int, Int>
        ): RenderPipeline {
            val vertModule = ShaderCompiler.loadShaderModule(device, vertShaderPath)
            val fragModule = ShaderCompiler.loadShaderModule(device, fragShaderPath)

            MemoryStack.stackPush().use { stack ->
                // Shader stages
                val entryPoint = stack.UTF8("main")
                val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
                stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModule)
                    .pName(entryPoint)
                stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModule)
                    .pName(entryPoint)

                // Vertex input
                val vertexBinding = VkVertexInputBindingDescription.calloc(1, stack)
                val vertexAttribs: VkVertexInputAttributeDescription.Buffer

                when (passType) {
                    PassType.STENCIL_FRONT, PassType.STENCIL_BACK -> {
                        // Position-only (12 bytes stride)
                        vertexBinding.get(0).binding(0).stride(12).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
                        vertexAttribs = VkVertexInputAttributeDescription.calloc(1, stack)
                        vertexAttribs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
                    }
                    else -> {
                        // Position + Normal (24 bytes stride)
                        vertexBinding.get(0).binding(0).stride(24).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
                        vertexAttribs = VkVertexInputAttributeDescription.calloc(2, stack)
                        vertexAttribs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
                        vertexAttribs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12)
                    }
                }

                val vertexInputCI = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(vertexBinding)
                    .pVertexAttributeDescriptions(vertexAttribs)

                // Input assembly
                val topology = if (passType == PassType.LINE_DEBUG) VK_PRIMITIVE_TOPOLOGY_LINE_LIST else VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(topology)
                    .primitiveRestartEnable(false)

                // Viewport & scissor (dynamic)
                val viewport = VkViewport.calloc(1, stack)
                    .get(0).x(0f).y(0f).width(extent.first.toFloat()).height(extent.second.toFloat()).minDepth(0f).maxDepth(1f)
                val viewports = VkViewport.calloc(1, stack)
                viewports.put(0, viewport)

                val scissor = VkRect2D.calloc(1, stack)
                scissor.get(0).offset { it.x(0).y(0) }.extent { it.width(extent.first).height(extent.second) }

                val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .pViewports(viewports)
                    .pScissors(scissor)

                // Rasterization
                val cullMode = when (passType) {
                    PassType.STENCIL_FRONT -> VK_CULL_MODE_BACK_BIT // Cull back for front-face pass
                    PassType.STENCIL_BACK -> VK_CULL_MODE_FRONT_BIT // Cull front for back-face pass
                    PassType.LINE_DEBUG -> VK_CULL_MODE_NONE
                    else -> VK_CULL_MODE_BACK_BIT
                }
                val rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1f)
                    .cullMode(cullMode)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)

                // Multisample
                val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false)

                // Depth+stencil
                val depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)

                when (passType) {
                    PassType.AMBIENT -> {
                        depthStencil
                            .depthTestEnable(true)
                            .depthWriteEnable(true)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                            .stencilTestEnable(false)
                    }
                    PassType.STENCIL_FRONT -> {
                        depthStencil
                            .depthTestEnable(true)
                            .depthWriteEnable(false)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                            .stencilTestEnable(true)
                        depthStencil.front()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_INCREMENT_AND_WRAP)
                            .compareOp(VK_COMPARE_OP_ALWAYS)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                        depthStencil.back()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_INCREMENT_AND_WRAP)
                            .compareOp(VK_COMPARE_OP_ALWAYS)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                    }
                    PassType.STENCIL_BACK -> {
                        depthStencil
                            .depthTestEnable(true)
                            .depthWriteEnable(false)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                            .stencilTestEnable(true)
                        depthStencil.front()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_DECREMENT_AND_WRAP)
                            .compareOp(VK_COMPARE_OP_ALWAYS)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                        depthStencil.back()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_DECREMENT_AND_WRAP)
                            .compareOp(VK_COMPARE_OP_ALWAYS)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                    }
                    PassType.LIT -> {
                        depthStencil
                            .depthTestEnable(true)
                            .depthWriteEnable(false)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                            .stencilTestEnable(true)
                        depthStencil.front()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_KEEP)
                            .compareOp(VK_COMPARE_OP_EQUAL)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                        depthStencil.back()
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .depthFailOp(VK_STENCIL_OP_KEEP)
                            .compareOp(VK_COMPARE_OP_EQUAL)
                            .compareMask(0xFF)
                            .writeMask(0xFF)
                            .reference(0)
                    }
                    PassType.LINE_DEBUG -> {
                        depthStencil
                            .depthTestEnable(false)
                            .depthWriteEnable(false)
                            .stencilTestEnable(false)
                    }
                }

                // Color blend
                val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                when (passType) {
                    PassType.STENCIL_FRONT, PassType.STENCIL_BACK -> {
                        // No color writes for stencil passes
                        colorBlendAttachment.get(0)
                            .colorWriteMask(0)
                            .blendEnable(false)
                    }
                    PassType.LIT -> {
                        // Additive blending (ONE, ONE)
                        colorBlendAttachment.get(0)
                            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                            .blendEnable(true)
                            .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                            .dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                            .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                            .alphaBlendOp(VK_BLEND_OP_ADD)
                    }
                    else -> {
                        colorBlendAttachment.get(0)
                            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                            .blendEnable(false)
                    }
                }

                val colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment)

                // Dynamic state
                val dynamicStates = stack.mallocInt(2)
                dynamicStates.put(VK_DYNAMIC_STATE_VIEWPORT).put(VK_DYNAMIC_STATE_SCISSOR).flip()
                val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates)

                // Pipeline layout (push constants + descriptor set)
                val pushConstantRange = VkPushConstantRange.calloc(1, stack)
                pushConstantRange.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(64) // mat4

                val pSetLayouts = stack.mallocLong(1)
                pSetLayouts.put(0, descriptorSetLayout)

                val layoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayouts)
                    .pPushConstantRanges(pushConstantRange)

                val pLayout = stack.mallocLong(1)
                check(vkCreatePipelineLayout(device, layoutCI, null, pLayout) == VK_SUCCESS)
                val pipelineLayout = pLayout.get(0)

                // Create pipeline
                val pipelineCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                pipelineCI.get(0)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInputCI)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0)

                val pPipeline = stack.mallocLong(1)
                check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineCI, null, pPipeline) == VK_SUCCESS) {
                    "Failed to create graphics pipeline for $passType"
                }

                return RenderPipeline(
                    handle = pPipeline.get(0),
                    layout = pipelineLayout,
                    passType = passType,
                    device = device,
                    vertShaderModule = vertModule,
                    fragShaderModule = fragModule
                )
            }
        }
    }
}

