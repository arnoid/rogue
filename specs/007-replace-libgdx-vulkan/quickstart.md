# Quickstart: Replace libGDX with Vulkan

## Prerequisites

- JDK 17+ (LWJGL 3.4.1 requires Java 8+ but Kotlin 1.9.22 targets JVM 17)
- Vulkan 1.0+ capable GPU driver
- Gradle 8.x (project wrapper)

## Build & Run

```bash
# Build (includes SPIR-V shader compilation)
./gradlew build

# Run the game
./gradlew run

# Run visual tests
./gradlew test
```

## Key Development Tasks

### 1. Shader Iteration
Shaders are in `src/main/resources/shaders/` as Vulkan-compatible GLSL (`#version 450`).

SPIR-V compilation happens via a Gradle task using shaderc:
```bash
./gradlew compileShaders  # Compiles .vert/.frag → .spv
```

For rapid iteration, the application can also compile at runtime via `lwjgl-shaderc` (debug mode).

### 2. Adding a New Vulkan Pipeline
1. Write vertex + fragment shaders in `src/main/resources/shaders/`
2. Create a `RenderPipeline` with the desired state (blend, stencil, cull, topology)
3. Register in the relevant renderer

### 3. Adding a New Model
1. Place `.obj` file in `src/main/resources/models/`
2. Load via `AssetLoader.loadModel()` → returns `VulkanMesh`
3. Render by binding vertex/index buffers in command recording

### 4. Debugging Vulkan Issues
- Validation layers are enabled in debug builds (set via VM arg or env var)
- `VulkanDebug.kt` installs a debug messenger that logs to stderr
- Use RenderDoc for GPU frame capture (Vulkan natively supported)

## Architecture Overview

```
Main.kt (GLFW window + game loop)
  └── RoguelikeLauncher (state machine: MENU | GAME | EDITOR)
        ├── VulkanContext (instance, device, queues, VMA)
        ├── SwapChain (images, framebuffers, depth+stencil)
        ├── RenderPipeline[] (ambient, stencil×2, lit, debug, imgui)
        ├── InputSystem (GLFW callbacks → polling state)
        └── ImGui (UI rendering)

Frame loop:
  1. glfwPollEvents()
  2. Acquire swap chain image
  3. Record command buffers:
     a. Begin render pass
     b. Ambient pass (bind ambient pipeline, draw scene)
     c. Per-light: clear stencil → stencil pass (draw shadow volumes) → lit pass (draw scene)
     d. ImGui render pass
     e. End render pass
  4. Submit to graphics queue
  5. Present
```

## File Conversion Checklist

When migrating a file from libGDX:
- [ ] Remove all `com.badlogic.gdx.*` imports
- [ ] Replace `Vector3` → `org.joml.Vector3f`
- [ ] Replace `Matrix4` → `org.joml.Matrix4f`
- [ ] Replace `Color` → `org.joml.Vector4f` or custom Color
- [ ] Replace `Gdx.input.*` → GLFW input wrapper
- [ ] Replace `Gdx.files.*` → `java.nio.file.Path` / classpath resources
- [ ] Replace `ModelBatch.render()` → Vulkan draw call recording
- [ ] Replace `ShaderProgram` → SPIR-V `VkShaderModule`
- [ ] Replace `Disposable.dispose()` → Vulkan resource cleanup


