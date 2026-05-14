package com.roguelike.rendering

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWVulkan

/**
 * Pre-checks whether GLFW + Vulkan can be initialized on this system.
 * This avoids native crashes when running in environments without a display or Vulkan driver.
 */
object GLAvailability {

    /**
     * Returns true if GLFW can initialize and Vulkan is supported.
     */
    fun isAvailable(): Boolean {
        return try {
            val previousCallback = GLFW.glfwSetErrorCallback(null)
            val silentCallback = GLFWErrorCallback.createPrint(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
            GLFW.glfwSetErrorCallback(silentCallback)

            val glfwOk = GLFW.glfwInit()
            val vulkanOk = glfwOk && GLFWVulkan.glfwVulkanSupported()

            if (glfwOk) {
                GLFW.glfwTerminate()
            }

            silentCallback.free()
            GLFW.glfwSetErrorCallback(previousCallback)

            vulkanOk
        } catch (e: Throwable) {
            System.err.println("GLAvailability: GLFW/Vulkan init check failed: ${e.message}")
            false
        }
    }
}
