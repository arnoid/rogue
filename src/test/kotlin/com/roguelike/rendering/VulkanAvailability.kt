package com.roguelike.rendering

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWVulkan

/**
 * Checks whether Vulkan is available on this system.
 * Replaces GLAvailability for Vulkan-based rendering tests.
 */
object VulkanAvailability {

    @Volatile
    private var checked = false

    @Volatile
    private var available = false

    /**
     * Returns true if GLFW can initialize and Vulkan is supported.
     * Result is cached after first check.
     */
    fun isAvailable(): Boolean {
        if (checked) return available
        synchronized(this) {
            if (checked) return available
            available = try {
                val silentCallback = GLFWErrorCallback.createPrint(
                    java.io.PrintStream(java.io.OutputStream.nullOutputStream())
                )
                GLFW.glfwSetErrorCallback(silentCallback)

                val glfwOk = GLFW.glfwInit()
                val vulkanOk = glfwOk && GLFWVulkan.glfwVulkanSupported()

                if (glfwOk) {
                    GLFW.glfwTerminate()
                }

                silentCallback.free()
                GLFW.glfwSetErrorCallback(null)

                vulkanOk
            } catch (e: Throwable) {
                System.err.println("VulkanAvailability: check failed: ${e.message}")
                false
            }
            checked = true
            return available
        }
    }
}

