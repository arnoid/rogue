package com.roguelike.rendering.vulkan

import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.util.vma.Vma
import org.lwjgl.util.vma.VmaAllocatorCreateInfo
import org.lwjgl.util.vma.VmaVulkanFunctions

/**
 * Core Vulkan state: instance, device, queues, VMA allocator, surface.
 * Created once at startup, destroyed on shutdown.
 */
class VulkanContext private constructor(
    val vkInstance: VkInstance,
    val vkPhysicalDevice: VkPhysicalDevice,
    val vkDevice: VkDevice,
    val graphicsQueue: VkQueue,
    val presentQueue: VkQueue,
    val graphicsQueueFamily: Int,
    val presentQueueFamily: Int,
    val allocator: Long,
    val surface: Long,
    val debugMessenger: Long?,
    private val window: Long
) : AutoCloseable {

    val instance: Long get() = vkInstance.address()
    val physicalDevice: Long get() = vkPhysicalDevice.address()
    val device: Long get() = vkDevice.address()

    fun waitIdle() {
        vkDeviceWaitIdle(vkDevice)
    }

    override fun close() {
        waitIdle()
        Vma.vmaDestroyAllocator(allocator)
        vkDestroyDevice(vkDevice, null)
        if (surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(vkInstance, surface, null)
        }
        if (debugMessenger != null && debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugMessenger, null)
        }
        vkDestroyInstance(vkInstance, null)
    }

    companion object {
        private val VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation"

        fun create(window: Long, debug: Boolean = false): VulkanContext {
            // --- Instance Creation ---
            val vkInstance: VkInstance
            val debugMessenger: Long?
            val surface: Long

            MemoryStack.stackPush().use { stack ->
                val appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Roguelike 3D"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("Custom Vulkan Engine"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_0)

                val glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions()
                    ?: throw RuntimeException("Vulkan not supported by GLFW")

                val extensions = if (debug) {
                    val buf = stack.mallocPointer(glfwExtensions.capacity() + 1)
                    for (i in 0 until glfwExtensions.capacity()) {
                        buf.put(glfwExtensions.get(i))
                    }
                    buf.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
                    buf.flip()
                } else {
                    glfwExtensions
                }

                val hasValidation = debug && hasValidationLayer()
                val layers = if (hasValidation) {
                    val buf = stack.mallocPointer(1)
                    buf.put(stack.UTF8(VALIDATION_LAYER))
                    buf.flip()
                } else null

                val instanceCI = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensions)
                if (layers != null) {
                    instanceCI.ppEnabledLayerNames(layers)
                }

                val pInstance = stack.mallocPointer(1)
                check(vkCreateInstance(instanceCI, null, pInstance) == VK_SUCCESS) {
                    "Failed to create Vulkan instance"
                }
                vkInstance = VkInstance(pInstance.get(0), instanceCI)

                debugMessenger = if (hasValidation) {
                    VulkanDebug.setupDebugMessenger(vkInstance, stack)
                } else null

                val pSurface = stack.mallocLong(1)
                check(GLFWVulkan.glfwCreateWindowSurface(vkInstance, window, null, pSurface) == VK_SUCCESS) {
                    "Failed to create window surface"
                }
                surface = pSurface.get(0)
            }

            // --- Physical Device Selection ---
            // Use separate stack frames for device enumeration to avoid stack overflow
            // from large VkExtensionProperties arrays
            data class DeviceCandidate(
                val device: VkPhysicalDevice,
                val graphicsFamily: Int,
                val presentFamily: Int,
                val score: Int
            )

            val candidates = mutableListOf<DeviceCandidate>()

            val deviceCount: Int
            val physicalDevices: List<VkPhysicalDevice>
            MemoryStack.stackPush().use { stack ->
                val pDeviceCount = stack.mallocInt(1)
                vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, null)
                deviceCount = pDeviceCount.get(0)
                check(deviceCount > 0) { "No Vulkan-capable GPUs found" }

                val pDevices = stack.mallocPointer(deviceCount)
                vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, pDevices)
                physicalDevices = (0 until deviceCount).map { VkPhysicalDevice(pDevices.get(it), vkInstance) }
            }

            for (physDevice in physicalDevices) {
                // Each device gets its own stack frame to avoid overflow
                MemoryStack.stackPush().use { stack ->
                    val pQueueCount = stack.mallocInt(1)
                    vkGetPhysicalDeviceQueueFamilyProperties(physDevice, pQueueCount, null)
                    val queueProps = VkQueueFamilyProperties.calloc(pQueueCount.get(0), stack)
                    vkGetPhysicalDeviceQueueFamilyProperties(physDevice, pQueueCount, queueProps)

                    var graphicsFamily = -1
                    var presentFamily = -1
                    val pSupported = stack.mallocInt(1)
                    for (q in 0 until pQueueCount.get(0)) {
                        if (queueProps.get(q).queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
                            graphicsFamily = q
                        }
                        vkGetPhysicalDeviceSurfaceSupportKHR(physDevice, q, surface, pSupported)
                        if (pSupported.get(0) == VK_TRUE) {
                            presentFamily = q
                        }
                        if (graphicsFamily >= 0 && presentFamily >= 0) break
                    }

                    if (graphicsFamily < 0 || presentFamily < 0) return@use

                    // Check swapchain extension — use heap allocation for large array
                    val pExtCount = stack.mallocInt(1)
                    vkEnumerateDeviceExtensionProperties(physDevice, null as? CharSequence, pExtCount, null)
                    val extCount = pExtCount.get(0)
                    val extProps = VkExtensionProperties.calloc(extCount) // HEAP, not stack
                    try {
                        vkEnumerateDeviceExtensionProperties(physDevice, null as? CharSequence, pExtCount, extProps)
                        val hasSwapchain = (0 until extCount).any {
                            extProps.get(it).extensionNameString() == VK_KHR_SWAPCHAIN_EXTENSION_NAME
                        }
                        if (!hasSwapchain) return@use
                    } finally {
                        extProps.free()
                    }

                    val props = VkPhysicalDeviceProperties.calloc(stack)
                    vkGetPhysicalDeviceProperties(physDevice, props)
                    val score = if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) 1000 else 100

                    candidates.add(DeviceCandidate(physDevice, graphicsFamily, presentFamily, score))
                }
            }

            check(candidates.isNotEmpty()) { "No suitable Vulkan GPU found" }
            val best = candidates.maxBy { it.score }

            // --- Logical Device + Queues + VMA ---
            val vkDevice: VkDevice
            val graphicsQueue: VkQueue
            val presentQueue: VkQueue
            val allocatorHandle: Long

            MemoryStack.stackPush().use { stack ->
                val uniqueFamilies = setOf(best.graphicsFamily, best.presentFamily)
                val queueCIs = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size, stack)
                val pPriority = stack.floats(1.0f)
                uniqueFamilies.forEachIndexed { idx, family ->
                    queueCIs.get(idx)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(family)
                        .pQueuePriorities(pPriority)
                }

                val deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)

                val ppSwapchain = stack.mallocPointer(1)
                ppSwapchain.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                ppSwapchain.flip()

                val deviceCI = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCIs)
                    .pEnabledFeatures(deviceFeatures)
                    .ppEnabledExtensionNames(ppSwapchain)

                val pDevice = stack.mallocPointer(1)
                check(vkCreateDevice(best.device, deviceCI, null, pDevice) == VK_SUCCESS) {
                    "Failed to create logical device"
                }
                vkDevice = VkDevice(pDevice.get(0), best.device, deviceCI)

                val pQueue = stack.mallocPointer(1)
                vkGetDeviceQueue(vkDevice, best.graphicsFamily, 0, pQueue)
                graphicsQueue = VkQueue(pQueue.get(0), vkDevice)
                vkGetDeviceQueue(vkDevice, best.presentFamily, 0, pQueue)
                presentQueue = VkQueue(pQueue.get(0), vkDevice)

                val vmaVulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(vkInstance, vkDevice)

                val allocatorCI = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(best.device)
                    .device(vkDevice)
                    .instance(vkInstance)
                    .pVulkanFunctions(vmaVulkanFunctions)

                val pAllocator = stack.mallocPointer(1)
                check(Vma.vmaCreateAllocator(allocatorCI, pAllocator) == VK_SUCCESS) {
                    "Failed to create VMA allocator"
                }
                allocatorHandle = pAllocator.get(0)
            }

            return VulkanContext(
                vkInstance = vkInstance,
                vkPhysicalDevice = best.device,
                vkDevice = vkDevice,
                graphicsQueue = graphicsQueue,
                presentQueue = presentQueue,
                graphicsQueueFamily = best.graphicsFamily,
                presentQueueFamily = best.presentFamily,
                allocator = allocatorHandle,
                surface = surface,
                debugMessenger = debugMessenger,
                window = window
            )
        }

        private fun hasValidationLayer(): Boolean {
            MemoryStack.stackPush().use { stack ->
                val pCount = stack.mallocInt(1)
                vkEnumerateInstanceLayerProperties(pCount, null)
                val count = pCount.get(0)
                if (count == 0) return false
                val layers = VkLayerProperties.calloc(count) // heap
                try {
                    vkEnumerateInstanceLayerProperties(pCount, layers)
                    return (0 until count).any {
                        layers.get(it).layerNameString() == VALIDATION_LAYER
                    }
                } finally {
                    layers.free()
                }
            }
        }
    }
}




