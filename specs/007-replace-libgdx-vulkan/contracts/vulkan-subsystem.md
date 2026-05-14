# Vulkan Subsystem Contracts

## VulkanContext

```kotlin
interface VulkanContext : AutoCloseable {
    val instance: Long          // VkInstance handle
    val physicalDevice: Long    // VkPhysicalDevice handle  
    val device: Long            // VkDevice handle
    val graphicsQueue: Long     // VkQueue handle
    val presentQueue: Long      // VkQueue handle
    val graphicsQueueFamily: Int
    val presentQueueFamily: Int
    val allocator: Long         // VMA allocator
    val surface: Long           // Window surface

    /** Wait for device idle before cleanup. */
    fun waitIdle()
}
```

## SwapChain

```kotlin
interface SwapChain : AutoCloseable {
    val handle: Long
    val imageCount: Int
    val format: Int             // VkFormat
    val extent: Pair<Int, Int>  // width, height
    val renderPass: Long
    
    /** Acquire next image index. Returns null if swap chain needs recreation. */
    fun acquireNextImage(semaphore: Long, timeout: Long = Long.MAX_VALUE): Int?
    
    /** Recreate swap chain (e.g., on window resize). */
    fun recreate(width: Int, height: Int)
    
    /** Get framebuffer for image index. */
    fun getFramebuffer(imageIndex: Int): Long
}
```

## RenderPipeline

```kotlin
interface RenderPipeline : AutoCloseable {
    val handle: Long
    val layout: Long
    
    /** Bind this pipeline to the command buffer. */
    fun bind(commandBuffer: Long)
}

enum class PassType {
    AMBIENT, STENCIL_FRONT, STENCIL_BACK, LIT, LINE_DEBUG
}
```

## VulkanMesh

```kotlin
interface VulkanMesh : AutoCloseable {
    val vertexBuffer: Long
    val indexBuffer: Long
    val indexCount: Int
    
    /** Bind vertex and index buffers to command buffer. */
    fun bind(commandBuffer: Long)
    
    /** Issue indexed draw call. */
    fun draw(commandBuffer: Long, instanceCount: Int = 1)
    
    /** Update vertex data (for dynamic meshes like shadow volumes). */
    fun updateVertices(data: FloatArray, count: Int)
    fun updateIndices(data: ShortArray, count: Int)
}
```

## VulkanTexture

```kotlin
interface VulkanTexture : AutoCloseable {
    val imageView: Long
    val sampler: Long
    val width: Int
    val height: Int
    
    companion object {
        /** Load texture from file path using STB. */
        fun loadFromFile(context: VulkanContext, path: String): VulkanTexture
        
        /** Create 1x1 solid color texture (for untextured geometry). */
        fun createSolidColor(context: VulkanContext, r: Float, g: Float, b: Float, a: Float): VulkanTexture
    }
}
```

## Camera

```kotlin
interface Camera {
    val position: org.joml.Vector3f
    val direction: org.joml.Vector3f
    val up: org.joml.Vector3f
    val viewMatrix: org.joml.Matrix4f
    val projectionMatrix: org.joml.Matrix4f
    val viewProjection: org.joml.Matrix4f
    
    fun update()
    fun resize(width: Int, height: Int)
    
    /** Project world position to screen coordinates. */
    fun project(worldPos: org.joml.Vector3f, viewportWidth: Float, viewportHeight: Float): org.joml.Vector3f
    
    /** Unproject screen coordinates to world ray. */
    fun unproject(screenPos: org.joml.Vector3f, viewportWidth: Float, viewportHeight: Float): org.joml.Vector3f
}
```

## InputSystem

```kotlin
interface InputSystem {
    fun isKeyPressed(key: Int): Boolean
    fun isKeyJustPressed(key: Int): Boolean
    fun isMouseButtonPressed(button: Int): Boolean
    fun isMouseButtonJustPressed(button: Int): Boolean
    fun getMouseX(): Float
    fun getMouseY(): Float
    fun getScrollDelta(): Float
    
    /** Call at end of frame to clear just-pressed state. */
    fun endFrame()
    
    /** Install GLFW callbacks on window. */
    fun install(window: Long)
}
```

## UISystem (ImGui wrapper)

```kotlin
interface UISystem : AutoCloseable {
    /** Initialize ImGui with GLFW window and Vulkan context. */
    fun init(window: Long, context: VulkanContext, renderPass: Long)
    
    /** Begin new ImGui frame. */
    fun beginFrame()
    
    /** End frame and record ImGui draw commands into command buffer. */
    fun endFrame(commandBuffer: Long)
    
    /** Whether ImGui wants to capture keyboard/mouse input. */
    fun wantsKeyboard(): Boolean
    fun wantsMouse(): Boolean
}
```

## Renderer Contract

```kotlin
interface SceneRenderer {
    /**
     * Record draw commands for the full scene into the command buffer.
     * Called once per pass (ambient, lit per light).
     */
    fun recordScene(
        commandBuffer: Long,
        pipeline: RenderPipeline,
        camera: Camera,
        materialUBO: Long,   // descriptor set for material
        lightUBO: Long?       // descriptor set for light data (null for ambient)
    )
}
```

