# Implementation Plan: Replace libGDX with LWJGL 3 + Vulkan

**Branch**: `007-replace-libgdx-vulkan` | **Date**: 2026-05-14 (updated) | **Spec**: `specs/007-replace-libgdx-vulkan/spec.md`
**Input**: Feature specification from `specs/007-replace-libgdx-vulkan/spec.md`

## Summary

Remove all libGDX dependencies and replace with raw LWJGL 3.4.1 + Vulkan. The rendering pipeline includes two shadow/lighting approaches: (1) stencil-based shadow volumes via ShadowVolumeRenderer, and (2) per-pixel DDA voxel ray-marching in the fragment shader. The editor UI uses a custom SimpleUI immediate-mode renderer (not Dear ImGui as originally planned). Input handling uses GLFW callbacks. Assets load via STB/standard Java I/O.

## Current Implementation Status

| Phase | Status | Notes |
|---|---|---|
| Phase 1: Setup (Build System) | ✅ Complete | All libGDX deps removed, LWJGL/JOML added |
| Phase 2: Foundational (Vulkan Infra) | ✅ Complete | VulkanContext, SwapChain, RenderPipeline, ShaderCompiler, math migration |
| Phase 3: US1 (Window + Game Loop) | ✅ Complete | GLFW window, Vulkan game loop, swap chain resize |
| Phase 4: US2 (3D Rendering) | ✅ Complete | Both shadow approaches working, shaders, camera |
| Phase 5: US3 (Input) | ✅ Complete | InputSystem with GLFW callbacks |
| Phase 6: US4 (UI) | ✅ Complete | SimpleUI replaces vis-ui (ImGui NOT used) |
| Phase 7: US5 (Visual Tests) | 🔄 Partial | VulkanAvailability + RenderTestHarness done, individual tests need migration |
| Phase 8: US6 (Assets) | ✅ Complete | STB loading, standard Java I/O |
| Phase 9: Polish | ⬜ Not started | Validation layers, perf, cleanup |
| US7: Light Editing | ✅ Complete | Select, drag, radius/intensity controls |

## Technical Context

**Language/Version**: Kotlin 1.9.22  
**Primary Dependencies**: LWJGL 3.4.1 (core, glfw, vulkan, vma, stb, shaderc, openal, assimp), JOML 1.10.8  
**UI System**: Custom SimpleUI (NOT Dear ImGui — ImGui stubs exist but are unused)  
**Testing**: JUnit Jupiter 5.10.1  
**Target Platform**: Desktop (Windows primary, Linux/macOS secondary)  
**Project Type**: Desktop game application  
**Performance Goals**: 60 FPS, visual parity with current OpenGL pipeline  
**Constraints**: Vulkan 1.0+ required, stencil buffer required for shadow volumes  

## Project Structure

### Documentation (this feature)

```text
specs/007-replace-libgdx-vulkan/
├── plan.md              # This file
├── spec.md              # Feature specification (updated 2026-05-14)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── vulkan-subsystem.md
│   └── shader-contracts.md
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
src/
├── main/kotlin/com/roguelike/
│   ├── Main.kt                           # GLFW window + Vulkan game loop
│   ├── RoguelikeLauncher.kt              # State machine (MENU|GAME|EDITOR)
│   ├── RoguelikeGame.kt                  # Arena gameplay screen (2D grid preview)
│   ├── MainMenuScreen.kt                 # Main menu (SimpleUI buttons)
│   ├── MapEditor.kt                      # World editor with orbital camera, tools, light editing
│   ├── core/
│   │   ├── math/Vec3.kt                  # Custom Vec3 (no libGDX deps)
│   │   └── model/*.kt                    # WorldNode, Tile, Item, Actor, LightSource, etc.
│   ├── rendering/
│   │   ├── vulkan/
│   │   │   ├── VulkanContext.kt          # VkInstance, VkDevice, VMA allocator
│   │   │   ├── SwapChain.kt             # Swap chain + framebuffers + depth/stencil
│   │   │   ├── RenderPipeline.kt        # VkPipeline per pass type (5 variants)
│   │   │   ├── VulkanMesh.kt            # GPU vertex/index buffers via VMA
│   │   │   ├── VulkanTexture.kt         # STB loading → VkImage
│   │   │   ├── VulkanDebug.kt           # Validation layer debug messenger
│   │   │   └── ShaderCompiler.kt        # Shaderc SPIR-V compilation
│   │   ├── Camera.kt                    # JOML-based, Vulkan clip space (Y-flip)
│   │   ├── ShadowVolumeRenderer.kt      # Multi-pass stencil shadow volumes
│   │   ├── ShadowVolumeBuilder.kt       # Shadow volume geometry computation
│   │   ├── ShadowVolumeMesh.kt          # Shadow volume mesh data
│   │   ├── SilhouetteCache.kt           # Silhouette edge caching
│   │   ├── SilhouetteEdge.kt            # Edge data structure
│   │   ├── OccluderExtractor.kt         # Occluder geometry extraction
│   │   ├── PointLightData.kt            # JOML Vector3f-based light data
│   │   ├── DebugRenderer.kt             # Wireframe shapes via SimpleUI
│   │   ├── WorldRenderer.kt             # World geometry collection
│   │   ├── TileRenderer.kt              # Tile mesh collection
│   │   ├── TileRenderRegistry.kt        # Tile type → renderer mapping
│   │   ├── ItemRenderer.kt              # Item rendering
│   │   ├── PropRenderer.kt              # Prop rendering
│   │   └── AssetLoader.kt              # STB textures + Assimp models
│   ├── input/
│   │   └── InputSystem.kt              # GLFW callbacks → polling wrapper
│   ├── ui/
│   │   ├── SimpleUI.kt                 # Custom immediate-mode UI renderer (3 pipelines)
│   │   ├── MenuBar.kt                  # Dropdown menu bar widget
│   │   ├── FileDialog.kt              # File browser dialog
│   │   └── RecentFiles.kt             # Recent files tracking
│   ├── editor/
│   │   ├── EditorToolMode.kt           # Editor tool mode enum
│   │   ├── EditorInputHandler.kt       # Editor-specific input
│   │   ├── EditorPalettePanel.kt       # TODO: ImGui placeholder (unused)
│   │   └── EditorStatusBar.kt          # TODO: ImGui placeholder (unused)
│   └── world/
│       ├── World.kt                    # World data model
│       └── WorldEditor.kt             # World editor logic
├── main/resources/
│   ├── shaders/
│   │   ├── ui.vert.glsl               # SimpleUI 2D overlay vertex shader
│   │   ├── ui.frag.glsl               # SimpleUI 2D overlay fragment shader (font atlas)
│   │   ├── world_gpu.vert.glsl        # GPU 3D vertex shader (VP push constant)
│   │   ├── world_lit.frag.glsl        # Per-pixel DDA ray-march lighting fragment shader
│   │   ├── world_lit.vert.glsl        # CPU-projected lit-world vertex shader
│   │   ├── ambient_pass.vert.glsl     # Stencil shadow ambient pass vertex
│   │   ├── ambient_pass.frag.glsl     # Stencil shadow ambient pass fragment
│   │   ├── lit_pass.vert.glsl         # Stencil shadow lit pass vertex
│   │   ├── lit_pass.frag.glsl         # Stencil shadow lit pass fragment
│   │   ├── shadow_volume.vert.glsl    # Shadow volume geometry vertex
│   │   └── shadow_volume.frag.glsl    # Shadow volume geometry fragment (no output)
│   ├── models/                         # Asset files
│   └── items/                          # JSON configs
└── test/kotlin/com/roguelike/rendering/
    ├── RenderTestHarness.kt            # Vulkan offscreen rendering harness
    ├── VulkanAvailability.kt           # Vulkan support detection for tests
    ├── PixelSampler.kt                 # Pure pixel comparison logic
    ├── SceneBuilder.kt                 # Test scene construction
    ├── GLTestBase.kt                   # Legacy GL test base (to be removed)
    ├── GLAvailability.kt               # Legacy GL availability (to be removed)
    ├── MinimalGLTest.kt                # Legacy (to be rewritten)
    ├── BasicShadowLightTest.kt         # Shadow/light test
    ├── ShadowVolumeBuilderTest.kt      # Pure unit test
    └── *.kt (other test classes)       # Various visual tests
```

## Rendering Architecture

### Three-Pipeline Approach in SimpleUI

The SimpleUI class contains three separate Vulkan graphics pipelines:

1. **UI Pipeline** — 2D screen-space overlay
   - Vertex: pos2 + color4 + uv2 = 32 bytes
   - No depth test, alpha blending
   - Font atlas texture via descriptor set
   - Used for: text, buttons, rectangles, HUD

2. **Lit-World Pipeline** — CPU-projected with per-pixel lighting
   - Vertex: pos2 + color4 + worldPos3 + normal3 = 48 bytes
   - No depth test (CPU sorts back-to-front)
   - Descriptor set: LightingUBO (binding 0) + OccupancyGrid SSBO (binding 1)
   - Fragment shader: DDA ray-march through voxel grid
   - Used for: editor in LIGHTS mode (CPU projection)

3. **GPU 3D Pipeline** — Depth-buffered world rendering
   - Vertex: worldPos3 + color4 + normal3 = 40 bytes
   - Depth test + back-face culling
   - Push constant: mat4 ViewProjection (64 bytes)
   - Shares lit fragment shader (same descriptor set as lit-world)
   - Used for: editor in GPU_RENDER mode

### Shadow/Lighting Approaches

**Approach A: Per-Pixel DDA Ray-Marching** (active in editor)
- Fragment shader (`world_lit.frag.glsl`) receives interpolated world position + normal
- UBO stores up to 32 lights (position, intensity, color, radius)
- SSBO stores 3D occupancy grid (uint per cell, only walls are occluders)
- Per pixel, per light: DDA ray-march from surface to light, checking voxel occupancy
- Self-occlusion prevention: skip starting voxel in ray-march
- Ambient minimum: 0.15 base illumination
- Attenuation: intensity / (1 + dist² × 0.1)

**Approach B: Stencil Shadow Volumes** (ShadowVolumeRenderer)
- 4-pass per-light rendering: ambient → stencil-front → stencil-back → lit
- UBOs: SceneUBO, LightUBO, MaterialUBO + OccluderSSBO
- Stencil: depth-fail method (Carmack's Reverse)
- Additive blending for lit pass (ONE, ONE)
- ShadowVolumeBuilder generates shadow geometry from occluder triangles

### Shader Files Summary

| Shader | Purpose | Key Features |
|---|---|---|
| `ui.vert/frag.glsl` | SimpleUI 2D overlay | Font atlas sampling, solid color fallback |
| `world_gpu.vert.glsl` | GPU 3D vertex transform | VP matrix push constant, passes worldPos/normal |
| `world_lit.vert.glsl` | CPU-projected lit world | NDC screen positions, passes worldPos/normal |
| `world_lit.frag.glsl` | Per-pixel DDA lighting | 32 lights, 3D occupancy SSBO, shadow ray-march |
| `ambient_pass.vert/frag.glsl` | Stencil shadow ambient | SceneUBO + MaterialUBO, ambient * diffuse |
| `lit_pass.vert/frag.glsl` | Stencil shadow lit | LightUBO + MaterialUBO, diffuse lighting |
| `shadow_volume.vert/frag.glsl` | Shadow volume geometry | Position-only, no color output |

## Map Editor Architecture

### Editor Modes
- **NORMAL**: CPU painter's algorithm, no lighting
- **GRID_TOGGLE**: Wireframe grid visibility toggle
- **LIGHTS**: CPU-projected rendering with per-pixel lighting enabled
- **GPU_RENDER**: GPU depth-buffered rendering with per-pixel lighting enabled

### Light Editing Features
- **Placement**: Click with LIGHT tool to place at cursor position (z + 0.8 above floor)
- **Selection**: Click within 20px screen distance of existing light to select
- **Dragging**: Hold mouse and drag to move selected light along XY plane at its Z height
- **Properties**: Radius and intensity adjustable via +/- buttons in Lights palette tab
- **Visual feedback**: Selected light shown with higher alpha, radius sphere wireframe when preview enabled
- **State**: `selectedLightIndex`, `draggingLight`, `defaultLightRadius`, `defaultLightIntensity`

### Occupancy Grid Rules
- Only wall tiles (`WALL_NORTH`, `WALL_SOUTH`, `WALL_EAST`, `WALL_WEST`) are marked as occupied
- Floor tiles are NOT occluders (they are horizontal surfaces that don't block same-level light rays)
- Grid indexed as `[z * gridW * gridH + y * gridW + x]`

## Dependencies (build.gradle.kts)

The build.gradle.kts includes:
1. **LWJGL 3.4.1**: core, glfw, vulkan, vma, stb, shaderc, openal, assimp (with multi-platform natives)
2. **JOML 1.10.8**: 3D math
3. **Kotlin coroutines 1.7.3**
4. **JUnit Jupiter 5.10.1**: testing
5. **NO libGDX, NO vis-ui, NO ktx-scene2d**
6. **NO Dear ImGui** (imgui-java dependency may be in build.gradle.kts but is unused at runtime)

## libGDX API → LWJGL/Vulkan Replacement Map

| libGDX API | Replacement | Notes |
|---|---|---|
| `Lwjgl3Application` | GLFW window + custom game loop | `glfwCreateWindow`, `glfwPollEvents`, manual render loop |
| `ApplicationAdapter` | State machine enum | `create()`→init, `render()`→loop body, `dispose()`→cleanup |
| `Gdx.gl.*` | Vulkan API via LWJGL | `VK10.*`, `VK13.*` |
| `ModelBatch` | Vulkan command buffer recording | `vkCmdBindPipeline`, `vkCmdDraw` |
| `ShaderProgram` | `VkShaderModule` + SPIR-V | Runtime compilation via shaderc |
| `PerspectiveCamera` | JOML `Matrix4f` camera | `perspectiveVulkan()` for Y-flip |
| `Vector3` / `Matrix4` | JOML `Vector3f` / `Matrix4f` | Drop-in structural replacement |
| `Color` | JOML `Vector4f` or raw floats | |
| `Pixmap` / `Texture` | STB `stbi_load` → `VkImage` | Via staging buffer + layout transition |
| `FileHandle` | `java.nio.file.Path` / classloader resources | |
| `Gdx.input.*` | GLFW callbacks + state arrays | `glfwSetKeyCallback`, etc. |
| `vis-ui` / `scene2d` | SimpleUI (custom immediate-mode renderer) | Colored quads, bitmap font, buttons |
| `ScreenUtils.getFrameBufferPixmap` | `vkCmdCopyImageToBuffer` + readback | For visual tests |
| `Mesh` | VulkanMesh (VMA vertex/index buffers) | |
| `glStencilOp` / `glStencilFunc` | `VkPipelineDepthStencilStateCreateInfo` | 1:1 mapping |

## Complexity Tracking

No constitution violations to justify.
