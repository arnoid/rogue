# Research: Replace libGDX with LWJGL 3 + Vulkan

## R1: Vulkan Initialization with LWJGL 3

**Decision**: Use LWJGL's `org.lwjgl.vulkan` bindings with VMA (Vulkan Memory Allocator) for all GPU memory management.

**Rationale**: LWJGL provides direct, thin bindings to the Vulkan C API. VMA handles the complex memory allocation strategy (device-local, host-visible, staging buffers) that would otherwise require hundreds of lines of boilerplate.

**Alternatives considered**:
- **Manual Vulkan memory management**: Rejected — error-prone, requires tracking memory types/heaps manually.
- **Higher-level frameworks (e.g., BGFX via LWJGL)**: Rejected — abstracts away Vulkan specifics needed for stencil shadow volumes.

**Key patterns**:
1. `VkInstance` creation with validation layers (debug) or without (release)
2. Physical device selection: enumerate, score by discrete GPU preference, check queue families
3. Logical device creation with graphics + present queue
4. GLFW surface integration: `GLFWVulkan.glfwCreateWindowSurface()`
5. Swap chain: query surface capabilities, select format (B8G8R8A8_SRGB preferred), present mode (MAILBOX or FIFO)
6. All Vulkan object creation uses `MemoryStack` for temporary allocations

## R2: Stencil Shadow Volumes in Vulkan

**Decision**: Implement the existing 3-pass pipeline (ambient → stencil → lit) using Vulkan render passes with stencil attachments.

**Rationale**: The current OpenGL pipeline uses `glStencilOp`/`glStencilFunc` with depth-fail (Carmack's Reverse). Vulkan provides identical stencil operations via `VkPipelineDepthStencilStateCreateInfo`. The algorithm translates 1:1.

**Alternatives considered**:
- **Shadow mapping**: Rejected — spec requires visual parity with existing stencil shadow volumes.
- **Ray-traced shadows**: Rejected — requires VK_KHR_ray_tracing, not universally available on Vulkan 1.0.

**Key implementation details**:
- Render pass with 3 subpasses (ambient, stencil, lit) or 3 separate render passes
- Depth+stencil attachment format: `VK_FORMAT_D24_UNORM_S8_UINT` or `VK_FORMAT_D32_SFLOAT_S8_UINT`
- Stencil pass uses two pipeline variants: front-face cull (INCR_WRAP on depth-fail) and back-face cull (DECR_WRAP on depth-fail)
- Lit pass uses stencil test (EQUAL, reference=0) with additive blending
- Dynamic scissor for light culling (as in current implementation)

## R3: SPIR-V Shader Compilation

**Decision**: Use shaderc at build time via a Gradle task. Shaders are authored as Vulkan-compatible GLSL (#version 450) and compiled to `.spv` files placed in resources.

**Rationale**: Build-time compilation catches shader errors early. Runtime compilation via `lwjgl-shaderc` is kept as a fallback for development iteration.

**Alternatives considered**:
- **Runtime-only compilation**: Rejected — fails build feedback loop, adds startup latency.
- **External glslc tool**: Rejected — requires separate toolchain install; shaderc via LWJGL is self-contained.

**Shader migration notes**:
- Add `#version 450` to all shaders
- Replace `attribute`/`varying` with `layout(location=N) in/out`
- Replace `uniform` blocks with `layout(set=0, binding=N) uniform UBO { ... }`
- Push constants for per-draw transform matrices (64 bytes = 1 Matrix4f)
- 6 shaders to convert: ambient_pass (vert+frag), lit_pass (vert+frag), shadow_volume (vert+frag)

## R4: Dear ImGui Integration with Vulkan

**Decision**: Use `imgui-java` (io.github.spair) which provides ImGui bindings with a GLFW+Vulkan backend.

**Rationale**: imgui-java handles the ImGui Vulkan renderer setup (descriptor pool, render pass, pipeline) out of the box. It supports all widgets needed: menus, buttons, text inputs, sliders, tables, tree nodes, split panes (via docking branch).

**Alternatives considered**:
- **Nuklear via LWJGL**: Rejected — less widget variety, C-style API harder to use from Kotlin.
- **Custom immediate-mode UI**: Rejected — enormous effort to replicate vis-ui feature set.

**Integration points**:
- Initialize ImGui with GLFW window handle and Vulkan device
- ImGui renders into its own render pass or as a subpass after scene rendering
- ImGui handles its own input via GLFW callbacks (install before game callbacks)
- World editor menus/palettes/split panes map directly to ImGui windows/menus

## R5: Model Loading Without libGDX

**Decision**: Use LWJGL's Assimp bindings for .obj files. For .g3db files, write a minimal parser or convert assets to .obj/.glTF at migration time.

**Rationale**: Assimp supports .obj natively and is already available via LWJGL (`lwjgl-assimp`). The .g3db format is libGDX-proprietary; converting to standard formats avoids maintaining a custom parser.

**Alternatives considered**:
- **Custom OBJ parser**: Viable for simple meshes but Assimp handles edge cases (multi-material, normals, texture coords).
- **Keep G3dModelLoader**: Rejected — depends on libGDX.

**Note**: If Assimp is used, add `lwjgl-assimp` to dependencies. Current plan uses the already-listed modules; add if needed during implementation.

## R6: GLFW Input System

**Decision**: Replace `Gdx.input` polling with GLFW callback-based input. Maintain a polling-compatible wrapper via per-frame key/button state arrays.

**Rationale**: The current `InputHandler` polls `Gdx.input.isKeyPressed()` every frame. GLFW uses callbacks but per-frame state can be tracked in arrays, preserving the polling API surface.

**Details**:
- `glfwSetKeyCallback` → update `keyState[key]` and `keyJustPressed[key]`
- `glfwSetMouseButtonCallback` → update button state
- `glfwSetCursorPosCallback` → track mouse position
- `glfwSetScrollCallback` → track scroll delta
- Clear `justPressed` arrays at end of frame
- `InputMultiplexer` pattern: ImGui gets first pass, then game input

## R7: Vulkan Offscreen Rendering for Tests

**Decision**: Create a headless Vulkan device (no surface/swap chain) with a color+depth+stencil framebuffer rendering to a `VkImage`. After rendering, copy image to host-visible buffer and read pixels.

**Rationale**: Visual tests currently use OpenGL FBOs. The equivalent in Vulkan is an offscreen render target with `vkCmdCopyImageToBuffer` for readback.

**Alternatives considered**:
- **Hidden GLFW window + swap chain**: Possible but adds window system dependency to CI.
- **Software rasterizer**: Rejected — not representative of GPU output.


