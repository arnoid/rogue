package com.roguelike.rendering

import com.roguelike.rendering.vulkan.VulkanContext
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class MinimalGLTest {

    @Test
    fun canCreateVulkanContext() {
        Assumptions.assumeTrue(GLAvailability.isAvailable(), "GLFW/Vulkan not available — skipping")

        check(glfwInit()) { "Failed to init GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)

        val window = glfwCreateWindow(64, 64, "MinimalVulkanTest", MemoryUtil.NULL, MemoryUtil.NULL)
        check(window != MemoryUtil.NULL) { "Failed to create window" }

        try {
            val ctx = VulkanContext.create(window, debug = false)
            try {
                assertTrue(ctx.device != VK_NULL_HANDLE, "Should have a valid Vulkan device")
                println("Vulkan device created successfully")
            } finally {
                ctx.close()
            }
        } finally {
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }
}
