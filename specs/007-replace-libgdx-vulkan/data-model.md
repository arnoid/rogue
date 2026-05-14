# Data Model: Replace libGDX with LWJGL 3 + Vulkan

**Updated**: 2026-05-14

## Vulkan Infrastructure Entities

### VulkanContext
Core Vulkan state holding all device-level resources.

| Field | Type | Description |
|---|---|---|
| instance | VkInstance | Vulkan instance handle |
| physicalDevice | VkPhysicalDevice | Selected GPU |
| vkDevice | VkDevice | Logical device |
| graphicsQueue | VkQueue | Graphics command queue |
| presentQueue | VkQueue | Presentation queue |
| graphicsQueueFamily | Int | Queue family index for graphics |
| presentQueueFamily | Int | Queue family index for present |
| allocator | Long | VMA allocator handle |
| debugMessenger | Long? | Debug callback handle (debug builds) |
| surface | Long | Window surface handle |

**Lifecycle**: Created once at startup → destroyed on shutdown.  
**Validation**: Instance creation must verify required extensions (`VK_KHR_surface`, platform surface ext). Device must support `VK_KHR_swapchain`. If Vulkan unavailable, report error and exit.

### SwapChain
Presentation infrastructure.

| Field | Type | Description |
|---|---|---|
| handle | Long | VkSwapchainKHR handle |
| images | List<Long> | Swap chain VkImage handles |
| imageViews | List<Long> | VkImageView per image |
| format | Int | Surface format (VK_FORMAT_*) |
| width | Int | Framebuffer width |
| height | Int | Framebuffer height |
| depthImage | Long | Depth+stencil VkImage |
| depthImageView | Long | Depth+stencil view |
| depthFormat | Int | D24_S8 or D32_S8 |
| framebuffers | List<Long> | One per swap chain image |
| renderPass | Long | Compatible render pass |

**Lifecycle**: Created at startup and on window resize → destroyed before recreation and on shutdown.  
**State transitions**: On `VK_ERROR_OUT_OF_DATE_KHR` or `VK_SUBOPTIMAL_KHR` → recreate.

### RenderPipeline
A configured Vulkan graphics pipeline for a specific rendering pass.

| Field | Type | Description |
|---|---|---|
| handle | Long | VkPipeline handle |
| layout | Long | VkPipelineLayout |
| vertShaderModule | Long | Vertex shader SPIR-V module |
| fragShaderModule | Long | Fragment shader SPIR-V module |
| passType | PassType | AMBIENT, STENCIL_FRONT, STENCIL_BACK, LIT, LINE_DEBUG |

**Variants** (used by ShadowVolumeRenderer):
1. **Ambient**: depth write, no stencil, no blend
2. **Stencil Front-face**: depth test, no color write, stencil INCR_WRAP on depth-fail, cull back
3. **Stencil Back-face**: depth test, no color write, stencil DECR_WRAP on depth-fail, cull front
4. **Lit**: depth test LEQUAL, stencil test EQUAL(0), additive blend (ONE, ONE), cull back
5. **Line Debug**: line topology, no depth test, blend

### SimpleUI Pipelines
Three additional pipelines managed by SimpleUI (separate from RenderPipeline):

1. **UI Pipeline**: 2D overlay, alpha blend, no depth test, font atlas descriptor set
2. **Lit-World Pipeline**: CPU-projected lit quads, per-pixel DDA lighting, no depth test
3. **GPU 3D Pipeline**: Depth-buffered, back-face culled, VP push constant, per-pixel DDA lighting

### VulkanMesh
GPU-resident geometry data.

| Field | Type | Description |
|---|---|---|
| vertexBuffer | Long | VMA buffer handle |
| vertexAllocation | Long | VMA allocation handle |
| indexBuffer | Long | VMA buffer handle |
| indexAllocation | Long | VMA allocation handle |
| vertexCount | Int | Number of vertices |
| indexCount | Int | Number of indices |
| vertexFormat | VertexFormat | POSITION, POSITION_NORMAL, POSITION_NORMAL_UV |

**Vertex formats**:
- `POSITION` (3 floats, 12 bytes) — shadow volumes
- `POSITION_NORMAL` (6 floats, 24 bytes) — colored world geometry
- `POSITION_NORMAL_UV` (8 floats, 32 bytes) — textured geometry

### VulkanTexture
GPU image for texture sampling.

| Field | Type | Description |
|---|---|---|
| image | Long | VkImage handle |
| allocation | Long | VMA allocation |
| imageView | Long | VkImageView |
| sampler | Long | VkSampler |
| width | Int | Pixel width |
| height | Int | Pixel height |
| format | Int | VK_FORMAT_R8G8B8A8_SRGB |

**Loading**: STB → staging buffer → `vkCmdCopyBufferToImage` → transition layout.

### Camera (JOML-based)
Replaces libGDX PerspectiveCamera.

| Field | Type | Description |
|---|---|---|
| position | Vector3f | Camera position |
| direction | Vector3f | Look direction |
| up | Vector3f | Up vector |
| fov | Float | Field of view (degrees) |
| aspectRatio | Float | Width / height |
| near | Float | Near clip plane |
| far | Float | Far clip plane |
| viewMatrix | Matrix4f | Computed view matrix |
| projectionMatrix | Matrix4f | Computed projection matrix |
| viewProjection | Matrix4f | Combined VP matrix |

**Note**: Vulkan clip space has Y flipped vs OpenGL. Projection matrix must account for this (negate Y scale or use `Matrix4f.perspectiveVulkan()`).

### SimpleUI
Custom immediate-mode UI renderer.

| Field | Type | Description |
|---|---|---|
| pipeline | Long | UI overlay pipeline |
| litPipeline | Long | Lit-world pipeline |
| gpuPipeline | Long | GPU 3D pipeline |
| vertexData | FloatArray | UI quad vertex buffer (pos2+color4+uv2) |
| litVertexData | FloatArray | Lit quad vertex buffer (pos2+color4+worldPos3+normal3) |
| gpuVertexData | FloatArray | GPU quad vertex buffer (worldPos3+color4+normal3) |
| vpMatrix | FloatArray | Cached VP matrix for GPU push constants |
| lightingUboBuffer | Long | Lighting UBO (up to 32 lights) |
| occupancySsboBuffer | Long | 3D occupancy grid SSBO |
| fontImage/View/Sampler | Long | Font atlas Vulkan resources |
| screenWidth/Height | Float | Current screen dimensions |

**Vertex formats** (per pipeline):
- UI: pos2 + color4 + uv2 = 8 floats (32 bytes)
- Lit-world: pos2 + color4 + worldPos3 + normal3 = 12 floats (48 bytes)
- GPU 3D: worldPos3 + color4 + normal3 = 10 floats (40 bytes)

**Max quads per pipeline**: 16,384

### LightSource
Point light entity stored in World.

| Field | Type | Description |
|---|---|---|
| id | String | UUID identifier |
| x, y, z | Float | World position |
| intensity | Float | Brightness multiplier (default: 5.0) |
| radius | Float | Light reach distance (default: 5.0) |
| colorHex | String | RGB hex color (e.g., "ffcc88") |

## Rendering Data Flow

### Per-Pixel DDA Lighting (SimpleUI pipelines)

```
// Set 0, Binding 0: Lighting UBO (updated once per frame)
struct LightingUBO {
    int lightCount;                    // offset 0
    int gridW;                         // offset 4
    int gridH;                         // offset 8
    int gridD;                         // offset 12
    vec4 lightPosIntensity[32];        // offset 16: xyz = position, w = intensity
    vec4 lightColorRadius[32];         // offset 528: rgb = color, a = radius
};
// Total: 1040 bytes

// Push Constants (GPU 3D pipeline, vertex stage):
struct PushConstants {
    mat4 viewProjection;               // 64 bytes
};

// Set 0, Binding 1: Occupancy Grid SSBO
buffer OccupancyGrid {
    uint cells[];                      // gridW * gridH * gridD entries
};
// Only wall tiles are marked as occupied (value != 0)
```

### Stencil Shadow Volume Rendering (ShadowVolumeRenderer)

```
// Set 0, Binding 0: Scene UBO (updated once per frame)
struct SceneUBO {
    mat4 viewProjection;    // offset 0
    vec3 cameraPosition;    // offset 64
    float _pad0;            // offset 76
};
// Total: 80 bytes

// Push Constants: Per-draw data (64 bytes max)
struct PushConstants {
    mat4 modelMatrix;       // 64 bytes
};

// Set 0, Binding 1: Light UBO (updated per lit pass)
struct LightUBO {
    vec3 lightPosition;     // offset 0
    float intensity;        // offset 12
    vec4 lightColor;        // offset 16
    float radius;           // offset 32
};
// Total: 48 bytes

// Set 0, Binding 2: Material UBO
struct MaterialUBO {
    vec4 diffuseColor;      // offset 0
    vec4 emissiveColor;     // offset 16
    vec4 ambientColor;      // offset 32
};
// Total: 48 bytes

// Set 0, Binding 3: Occluder SSBO (shadow volume approach)
buffer OccluderSSBO {
    uint triangleCount;     // offset 0
    uint _pad[3];           // offset 4
    vec4 vertices[];        // 3 vec4s per triangle (v0, v1, v2)
};
// Max: 8192 triangles
```

## Game Logic Entities (Unchanged)

The following entities from `core/model/` are **not modified** — they have no libGDX dependencies:
- `WorldNode`, `Tile`, `TileSlot`, `Item`, `Actor`, `Prop`, `LightSource`
- `World` (world package)
- `Vec3` (core/math)
- `GameLogger`, `ItemCatalog`, `ItemDef`
- `MovementSystem`, `InteractionSystem`

## State Machine

### Application States
```
INIT → MENU → GAME | EDITOR → MENU → SHUTDOWN
```

Replaces libGDX `Game.setScreen()` pattern with enum-based state transitions.

### Editor Modes
```
NORMAL (CPU painter, no lighting)
GRID_TOGGLE (wireframe visibility)
LIGHTS (CPU-projected, per-pixel lighting)
GPU_RENDER (GPU depth-buffered, per-pixel lighting)
```

### Vulkan Frame States
```
ACQUIRE_IMAGE → RECORD_COMMANDS → SUBMIT → PRESENT → (next frame)
                                                       ↓
                                             RECREATE_SWAP_CHAIN (on resize)
```

### Rendering Order (per frame, within render pass)
```
1. GPU 3D quads (depth-buffered, no sorting needed)
2. Lit-world quads (CPU-projected, alpha blend)
3. UI quads (2D overlay, alpha blend, on top of everything)
```
