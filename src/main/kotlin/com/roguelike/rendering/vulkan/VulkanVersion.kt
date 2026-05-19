package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VK13

/**
 * Resolves the highest Vulkan instance API version that the current loader
 * supports and exposes the matching shaderc target enums. Capped at
 * Vulkan 1.3 — every newer version is still backwards-compatible at the
 * SPIR-V level, but our shaders only use features available in 1.3.
 *
 * Why target the latest available version?
 * - Lets shaderc emit newer SPIR-V opcodes (e.g. SPIR-V 1.6 under VK 1.3),
 *   giving the driver's backend more high-level information to optimise.
 * - Unlocks driver features that depend on `apiVersion` ≥ 1.x (e.g. shader
 *   subgroup operations, scalar block layout) without having to enable
 *   individual extensions.
 */
object VulkanVersion {

    /**
     * Encoded Vulkan API version (`VK_MAKE_VERSION` form) to pass to
     * `VkApplicationInfo.apiVersion(...)`.
     */
    val instanceApiVersion: Int

    /**
     * Major/minor pair derived from [instanceApiVersion], useful for logs.
     */
    val majorMinor: Pair<Int, Int>

    /** shaderc target-env enum that pairs with [instanceApiVersion]. */
    val shadercTargetEnv: Int

    /**
     * Stable string tag (e.g. `"vk13"`) used as part of the on-disk shader
     * cache path. Bumping the target version invalidates older caches.
     */
    val cacheTag: String

    init {
        val resolved = MemoryStack.stackPush().use { stack ->
            val pVer = stack.mallocInt(1)
            // vkEnumerateInstanceVersion was added in Vulkan 1.1. On a 1.0
            // loader the symbol is absent — fall back to 1.0.
            val ok = try {
                VK11.vkEnumerateInstanceVersion(pVer)
            } catch (_: Throwable) {
                // Older LWJGL/loader path: treat as 1.0.
                pVer.put(0, VK10.VK_API_VERSION_1_0)
                VK10.VK_SUCCESS
            }
            if (ok == VK10.VK_SUCCESS) pVer.get(0) else VK10.VK_API_VERSION_1_0
        }

        val major = VK10.VK_VERSION_MAJOR(resolved)
        val minor = VK10.VK_VERSION_MINOR(resolved)
        majorMinor = major to minor

        // Choose the highest target we know about that fits the loader.
        instanceApiVersion = when {
            major > 1 -> VK13.VK_API_VERSION_1_3
            major == 1 && minor >= 3 -> VK13.VK_API_VERSION_1_3
            major == 1 && minor == 2 -> VK12.VK_API_VERSION_1_2
            major == 1 && minor == 1 -> VK11.VK_API_VERSION_1_1
            else -> VK10.VK_API_VERSION_1_0
        }
        shadercTargetEnv = when (instanceApiVersion) {
            VK13.VK_API_VERSION_1_3 -> Shaderc.shaderc_env_version_vulkan_1_3
            VK12.VK_API_VERSION_1_2 -> Shaderc.shaderc_env_version_vulkan_1_2
            VK11.VK_API_VERSION_1_1 -> Shaderc.shaderc_env_version_vulkan_1_1
            else -> Shaderc.shaderc_env_version_vulkan_1_0
        }
        cacheTag = when (instanceApiVersion) {
            VK13.VK_API_VERSION_1_3 -> "vk13"
            VK12.VK_API_VERSION_1_2 -> "vk12"
            VK11.VK_API_VERSION_1_1 -> "vk11"
            else -> "vk10"
        }
        println("[VulkanVersion] loader supports $major.$minor — targeting ${majorMinor.first}.${majorMinor.second.coerceAtMost(3)} ($cacheTag)")
    }
}

