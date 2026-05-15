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
   - No depth test (CPU sorts back-to-front)  Descriptor set: LightingUBO (binding 0) + OccupancyGrid SSBO (binding 1) + ShadowTriangles SSBO (binding 2)
   - Fragment shader: hybrid DDA ray-march (wall flags at boundaries) + Möller–Trumbore ray-triangle intersection (mesh triangles within cells)
   - Used for: editor in LIGHTS mode (CPU projection)

3. **GPU 3D Pipeline** — Depth-buffered world rendering
   - Vertex: worldPos3 + color4 + normal3 = 40 bytes
   - Depth test + back-face culling
   - Push constant: mat4 ViewProjection (64 bytes)
   - Shares lit fragment shader (same descriptor set as lit-world)
   - Used for: editor in GPU_RENDER mode

### Shadow/Lighting Approaches

**Approach A: Hybrid DDA Ray-Marching + Mesh Shadow Triangles** (active in editor)

This is the primary lighting system. It combines two occlusion techniques in a single
fragment shader pass:

1. **Planar structures (walls, floors, ceilings)** use boundary flags checked at DDA
   cell crossings. These are encoded in bits 0–5 of the occupancy grid cell.
2. **Complex structures (stairs, ladders)** use ray-triangle intersection
   (Möller–Trumbore) against their actual mesh geometry, uploaded as a separate SSBO.
   Triangle ranges are encoded in bits 7–31 of the occupancy grid cell.

#### GPU Data Layout

Three buffers are bound to descriptor set 0:

| Binding | Type | Content |
|---------|------|---------|
| 0 | UBO (`LightingUBO`) | `lightCount`, `gridW/H/D`, up to 32 lights × 2 vec4s each (pos+intensity, color+radius) |
| 1 | SSBO (`OccupancyGrid`) | `uint[]` — one uint per grid cell, bit-packed (see below) |
| 2 | SSBO (`ShadowTriangles`) | `vec4[]` — packed triangle vertices, 3 vec4s per triangle (xyz + padding) |

#### Occupancy Grid Bit Layout (per cell uint)

| Bits | Meaning |
|------|---------|
| 0 | North wall (Y+) |
| 1 | South wall (Y−) |
| 2 | East wall (X+) |
| 3 | West wall (X−) |
| 4 | Floor (Z−) |
| 5 | Ceiling (Z+) |
| 6 | Reserved |
| 7–15 | Shadow triangle count for this cell (0–511) |
| 16–31 | Shadow triangle start index in SSBO (0–65535) |

#### CPU-Side Shadow Triangle Collection (MapEditor.kt)

For each grid cell that contains a stairs or ladder structure:

- **Stairs**: The actual stair mesh triangles are transformed to world space using
  `collectShadowTriangles()` with the same transform as rendering (center, scale,
  Y↔Z swap, rotation). No synthetic occluder geometry is used — the mesh itself
  serves as the shadow caster, matching its visual appearance exactly.
- **Ladders**: Same approach — `collectShadowTriangles()` extracts the ladder mesh
  triangles with wall-offset positioning (±0.5 on the facing axis).

The `collectShadowTriangles()` function:
1. Iterates over the mesh's index buffer in groups of 3 (triangle list).
2. For each vertex: subtracts mesh center, applies scale, swaps Y↔Z (model space to
   world space), applies Y-axis rotation, then translates to node position + offset.
3. Appends 9 floats per triangle (v0.xyz, v1.xyz, v2.xyz) to a flat list.
4. Returns the triangle count for encoding into the occupancy grid.

The flat float list is uploaded to a VMA-backed SSBO at binding 2. Each triangle
occupies 3 vec4s (12 floats with w=0 padding) in the GPU buffer.

#### Fragment Shader Algorithm (world_lit.frag.glsl)

```
main():
  N = normalize(v_normal)
  surfacePos = v_worldPos + N * 0.15          // normal offset to avoid self-shadowing
  totalLight = vec3(0.15)                      // ambient minimum

  for each light:
    skip if NdotL <= 0 (back-face)
    skip if distance > radius
    skip if isOccluded(surfacePos, lightPos)
    totalLight += lightColor * attenuation * NdotL

  outColor = baseColor * clamp(totalLight, 0, 1)
```

`isOccluded(from, to)` — 3D DDA ray-march (max 64 steps):
```
  for each step:
    if current cell == target cell: return false (reached light)

    // Test mesh-based shadow triangles in current cell
    if hitsShadowMesh(from, dir, dist, cell): return true

    // Advance to next cell boundary (smallest tMax axis)
    // At each boundary crossing, check wall flags:
    //   - Current cell's exit-side flag OR next cell's entry-side flag
    //   - If either is set, the ray hits a wall → return true
    advance along smallest-tMax axis
```

`hitsShadowMesh(orig, dir, maxT, cell)`:
```
  decode (start, count) from occupancy grid bits 7-31
  for i in 0..count:
    read 3 vec4s from shadowTris SSBO at (start + i) * 3
    if rayTriangleIntersect(orig, dir, v0, v1, v2, maxT): return true
  return false
```

`rayTriangleIntersect()` — Möller–Trumbore algorithm:
- Returns true if ray hits triangle at parameter t ∈ (0.001, maxT)
- The 0.001 minimum prevents self-intersection from numerical noise

#### Self-Shadowing Prevention

Two mechanisms prevent fragments from incorrectly self-occluding:

1. **Normal offset (0.15 units)**: The shadow ray origin is pushed 0.15 units along the
   surface normal away from the geometry. This moves the ray start past the fragment's
   own surface so thin model faces don't block their own light.
2. **NdotL check**: Back-facing fragments (normal pointing away from light) are skipped
   entirely before any shadow ray is cast, so they receive no light contribution
   regardless of occlusion.

The DDA does NOT skip the origin cell — this allows structures to cast shadows on their
own surfaces (e.g., the vertical part of stairs blocking light on the horizontal steps).

#### Light Attenuation

`attenuation = intensity / (1.0 + dist² × 0.1)`

Combined with radius cutoff and Lambertian NdotL.

**Approach B: Stencil Shadow Volumes** (ShadowVolumeRenderer — legacy/alternative)
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
| `world_lit.frag.glsl` | Hybrid DDA + mesh-triangle lighting | 32 lights, occupancy SSBO (wall flags + tri ranges), shadow triangles SSBO, Möller–Trumbore intersection |
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
- Walls (`WALL_NORTH/SOUTH/EAST/WEST`), floors, and ceilings use boundary flags (bits 0–5) for DDA cell-crossing occlusion
- Stairs and ladders use mesh-based shadow triangles (bits 7–31) for ray-triangle occlusion within cells
- Both mechanisms are tested during the same DDA ray-march pass
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
