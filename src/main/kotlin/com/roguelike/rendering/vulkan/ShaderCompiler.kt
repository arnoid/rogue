package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkShaderModuleCreateInfo

/**
 * Compiles GLSL shaders to SPIR-V at runtime (dev mode) or loads pre-compiled .spv files (release mode).
 * Creates VkShaderModule handles from SPIR-V bytecode.
 */
object ShaderCompiler {

    /**
     * Load a shader module from the disk cache, a pre-compiled `.spv` on the
     * classpath, or by runtime-compiling the GLSL source. The runtime compile
     * path also writes the result back into [ShaderCache] so subsequent
     * launches stay fast.
     */
    fun loadShaderModule(device: VkDevice, resourcePath: String): Long {
        // 1. Disk cache (filled by [ShaderCache.compileAll] during startup
        //    splash, or by the runtime-compile fallback below).
        ShaderCache.readCachedSpirv(resourcePath)?.let { return createShaderModule(device, it) }

        // 2. Pre-baked .spv next to the GLSL on the classpath (release builds).
        val spvPath = resourcePath.removeSuffix(".glsl") + ".spv"
        val spvBytes = javaClass.classLoader.getResourceAsStream(spvPath)?.readBytes()
        if (spvBytes != null) {
            return createShaderModule(device, spvBytes)
        }

        // 3. Fall back to runtime compilation. This branch is mostly hit
        //    when [ShaderCache] is bypassed (tests, headless tools, etc).
        val glslSource = javaClass.classLoader.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()
            ?: throw RuntimeException("Shader not found: $resourcePath")

        val shaderKind = when {
            resourcePath.contains(".vert") -> Shaderc.shaderc_vertex_shader
            resourcePath.contains(".frag") -> Shaderc.shaderc_fragment_shader
            else -> throw RuntimeException("Unknown shader type: $resourcePath")
        }

        val spirv = compileGlslToSpirv(glslSource, shaderKind, resourcePath)
        return createShaderModule(device, spirv)
    }

    /**
     * Compile GLSL source to SPIR-V bytecode using shaderc.
     *
     * Targets the highest Vulkan API version supported by the current loader
     * (see [VulkanVersion]) and requests `shaderc_optimization_level_performance`
     * so the driver's backend receives already-DCE'd / inlined / constant-folded
     * SPIR-V instead of debug-grade output.
     */
    fun compileGlslToSpirv(source: String, shaderKind: Int, fileName: String): ByteArray {
        val compiler = Shaderc.shaderc_compiler_initialize()
        check(compiler != MemoryUtil.NULL) { "Failed to initialize shaderc compiler" }

        try {
            val options = Shaderc.shaderc_compile_options_initialize()
            Shaderc.shaderc_compile_options_set_target_env(
                options,
                Shaderc.shaderc_target_env_vulkan,
                VulkanVersion.shadercTargetEnv
            )
            Shaderc.shaderc_compile_options_set_optimization_level(
                options,
                Shaderc.shaderc_optimization_level_performance
            )

            val result = Shaderc.shaderc_compile_into_spv(
                compiler, source, shaderKind, fileName, "main", options
            )

            Shaderc.shaderc_compile_options_release(options)

            val status = Shaderc.shaderc_result_get_compilation_status(result)
            if (status != Shaderc.shaderc_compilation_status_success) {
                val errorMsg = Shaderc.shaderc_result_get_error_message(result) ?: "Unknown error"
                Shaderc.shaderc_result_release(result)
                throw RuntimeException("Shader compilation failed for $fileName:\n$errorMsg")
            }

            val spirvBuffer = Shaderc.shaderc_result_get_bytes(result)
                ?: throw RuntimeException("Empty SPIR-V output for $fileName")
            val bytes = ByteArray(spirvBuffer.remaining())
            spirvBuffer.get(bytes)

            Shaderc.shaderc_result_release(result)
            return bytes
        } finally {
            Shaderc.shaderc_compiler_release(compiler)
        }
    }

    /**
     * Create a VkShaderModule from SPIR-V bytecode.
     */
    fun createShaderModule(device: VkDevice, spirvBytes: ByteArray): Long {
        MemoryStack.stackPush().use { stack ->
            val buffer = MemoryUtil.memAlloc(spirvBytes.size)
            buffer.put(spirvBytes).flip()

            val createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(buffer)

            val pModule = stack.mallocLong(1)
            val result = vkCreateShaderModule(device, createInfo, null, pModule)
            MemoryUtil.memFree(buffer)

            check(result == VK_SUCCESS) { "Failed to create shader module" }
            return pModule.get(0)
        }
    }

    /**
     * Destroy a VkShaderModule.
     */
    fun destroyShaderModule(device: VkDevice, module: Long) {
        if (module != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, module, null)
        }
    }
}


