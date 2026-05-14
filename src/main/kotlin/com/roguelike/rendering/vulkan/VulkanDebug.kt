package com.roguelike.rendering.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.VK10.*

/**
 * Vulkan debug messenger — installs a validation layer callback that logs to stderr.
 * Only active when debug mode is enabled.
 */
object VulkanDebug {

    private val SEVERITY_VERBOSE = VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT
    private val SEVERITY_INFO = VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT
    private val SEVERITY_WARNING = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
    private val SEVERITY_ERROR = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT

    /**
     * Set up the debug messenger on the given VkInstance.
     * Returns the messenger handle, or VK_NULL_HANDLE if setup fails.
     */
    fun setupDebugMessenger(instance: VkInstance, stack: MemoryStack): Long {
        val createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
            .messageSeverity(
                SEVERITY_WARNING or SEVERITY_ERROR
            )
            .messageType(
                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
            )
            .pfnUserCallback { severity, _, pCallbackData, _ ->
                val data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
                val prefix = when {
                    severity and SEVERITY_ERROR != 0 -> "ERROR"
                    severity and SEVERITY_WARNING != 0 -> "WARNING"
                    severity and SEVERITY_INFO != 0 -> "INFO"
                    else -> "VERBOSE"
                }
                System.err.println("[Vulkan $prefix] ${data.pMessageString()}")
                VK_FALSE
            }

        val pMessenger = stack.mallocLong(1)
        val result = vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pMessenger)
        return if (result == VK_SUCCESS) pMessenger.get(0) else VK_NULL_HANDLE
    }
}

