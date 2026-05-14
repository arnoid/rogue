now# Feature Specification: Replace libGDX with LWJGL 3 + Vulkan

**Feature Branch**: `007-replace-libgdx-vulkan`  
**Created**: 2026-05-13  
**Updated**: 2026-05-14  
**Status**: In Progress (Phases 1–5 complete, Phase 6 partially complete)  
**Input**: User description: "Remove libGDX entirely from the project and replace it with raw LWJGL 3.4.1 and a Vulkan rendering implementation."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Application Launches with Vulkan Window (Priority: P1) ✅ COMPLETE

As a player, I can launch the roguelike game and see it open in a window rendered via Vulkan, so that the game runs without any libGDX dependency.

**Why this priority**: Without a working window and basic Vulkan rendering surface, no other feature can function. This is the foundational replacement for Lwjgl3Application and the entry point for all rendering.

**Independent Test**: Launch the application and verify a GLFW window opens, a Vulkan instance and device are created, a swap chain presents frames, and the window can be closed cleanly.

**Acceptance Scenarios**:

1. **Given** the application is built with LWJGL 3.4.1 and no libGDX on the classpath, **When** the user launches the game, **Then** a GLFW window opens at the configured resolution with a Vulkan rendering surface.
2. **Given** the Vulkan window is open, **When** the user closes the window, **Then** all Vulkan resources are destroyed and the process exits cleanly without errors.
3. **Given** the target system has no Vulkan-capable GPU driver, **When** the user launches the game, **Then** a clear error message is displayed explaining that Vulkan support is required.

---

### User Story 2 - 3D Scene Renders Correctly via Vulkan (Priority: P1) ✅ COMPLETE

As a player, I can see the roguelike game world rendered in 3D with the same visual fidelity as the previous OpenGL pipeline—models, textures, lighting, and shadows all appear correctly.

**Why this priority**: The core gameplay experience depends on correct 3D rendering. The lighting and shadow pipeline must all work through Vulkan.

**Independent Test**: Load a game scene containing models, textures, and shadow-casting lights; verify correct 3D rendering with lighting and shadows.

**Implementation Note**: The rendering system evolved from the original stencil-based shadow volume plan to include **two parallel lighting/shadow approaches**:
1. **Stencil-based shadow volumes** (`ShadowVolumeRenderer`) — multi-pass (ambient → stencil × 2 → lit) using UBOs and pipelines
2. **Per-pixel DDA ray-marching** (`world_lit.frag.glsl`) — single-pass fragment shader that ray-marches through a 3D voxel occupancy grid SSBO for per-pixel shadow determination

The map editor actively uses approach #2 (per-pixel DDA). Both coexist in the codebase.

**Acceptance Scenarios**:

1. **Given** a game scene with 3D geometry, **When** the scene is rendered, **Then** all geometry appears with correct positioning, depth ordering, and face shading.
2. **Given** a scene with point lights and occluding geometry, **When** the lighting shader executes, **Then** per-pixel shadows appear correctly — lit areas are bright, occluded areas receive only ambient light.
3. **Given** GLSL shaders (`#version 450`), **When** the Vulkan pipeline is active, **Then** shaders compile to SPIR-V and produce correct visual output.

---

### User Story 3 - Player Input Works Without libGDX (Priority: P1) ✅ COMPLETE

As a player, I can control my character using keyboard and mouse, with input handled directly through GLFW via LWJGL instead of libGDX's input system.

**Why this priority**: The game is unplayable without input handling. This is tightly coupled with the windowing replacement.

**Independent Test**: Launch the game, press movement keys and click the mouse; verify character responds and UI elements react to clicks.

**Acceptance Scenarios**:

1. **Given** the game is running, **When** the player presses keyboard keys, **Then** input events are received and processed identically to the previous libGDX input system.
2. **Given** the game is running, **When** the player moves and clicks the mouse, **Then** mouse position and button events are received and processed correctly.

---

### User Story 4 - UI System Works Without vis-ui (Priority: P2) ✅ COMPLETE (SimpleUI approach)

As a player using the world editor or inventory system, I can interact with UI elements (buttons, panels, text fields, lists) that are rendered through a Vulkan-compatible UI solution replacing vis-ui and ktx-scene2d.

**Why this priority**: The world editor and items system rely on UI widgets. Without a UI replacement, these tools are unusable, but the core game loop can still function with minimal HUD.

**Implementation Note**: Instead of Dear ImGui (as originally planned in the spec), the UI was implemented as **SimpleUI** — a custom immediate-mode UI renderer providing:
- Colored quad rendering (rectangles, arbitrary quadrilaterals)
- Bitmap font text rendering (5×7 pixel font baked into a Vulkan texture atlas)
- Button interaction with hover/click detection
- Three Vulkan pipelines: UI overlay, lit-world (CPU-projected + per-pixel lighting), GPU 3D (depth-buffered with VP push constants)

This is a pragmatic replacement that covers all current editor needs without the complexity of integrating Dear ImGui.

**Independent Test**: Open the world editor; verify all UI panels, buttons, and tool palettes render correctly and respond to interaction.

**Acceptance Scenarios**:

1. **Given** the world editor is opened, **When** UI panels are displayed, **Then** all buttons, tool palettes, tabs, and text labels render correctly and are interactive.
2. **Given** the map editor is showing the tools palette, **When** the user clicks tool buttons, **Then** the selected tool changes and visual feedback is shown.
3. **Given** the main menu is displayed, **When** the user clicks menu buttons, **Then** navigation to Arena, Editor, or Quit works correctly.

---

### User Story 5 - Visual Test Suite Runs with Vulkan (Priority: P2) 🔄 PARTIAL

As a developer, I can run the existing visual test suite against the Vulkan rendering pipeline, with tests capturing rendered output for comparison against reference images.

**Why this priority**: The test harness ensures rendering correctness across changes. It must work with Vulkan to maintain quality assurance, but is not user-facing.

**Independent Test**: Run the visual test suite; verify tests execute, capture framebuffer output via Vulkan (replacing OpenGL FBOs), and produce pass/fail results against reference images.

**Acceptance Scenarios**:

1. **Given** the visual test suite exists, **When** tests are executed, **Then** each test renders a scene via Vulkan, captures the output to an image, and compares it against a reference image.
2. **Given** the OpenGL FBO-based capture mechanism, **When** replaced with Vulkan offscreen rendering, **Then** captured images have equivalent resolution and color accuracy.

---

### User Story 6 - File and Asset Loading Without libGDX (Priority: P3) ✅ COMPLETE

As a developer, assets (textures, models, configuration files) load correctly using standard Kotlin/Java I/O and STB image loading via LWJGL, replacing libGDX's FileHandle, Pixmap, and Texture classes.

**Why this priority**: Asset loading is foundational but can be addressed incrementally — individual asset types can be migrated one at a time.

**Independent Test**: Load a texture file and a game configuration file; verify the texture is usable in the Vulkan pipeline and configuration data is parsed correctly.

**Acceptance Scenarios**:

1. **Given** a PNG texture file, **When** loaded via STB through LWJGL, **Then** the image data is available for Vulkan texture creation with correct dimensions and pixel format.
2. **Given** game configuration files previously loaded via FileHandle, **When** loaded via Kotlin/Java standard I/O, **Then** all configuration data is correctly parsed and applied.

---

### User Story 7 - Light Source Editing in Map Editor (Priority: P2) ✅ COMPLETE

As a level designer, I can place, select, move, and configure light sources in the map editor, so that I can design the lighting for game levels.

**Why this priority**: Dynamic lighting is a core visual feature. The editor must support full light manipulation for level design workflow.

**Independent Test**: Open the editor in LIGHTS or GPU_RENDER mode → place a light → click to select it → drag to move → adjust radius/intensity via palette controls.

**Acceptance Scenarios**:

1. **Given** the editor is in the Lights palette tab with the Light tool selected, **When** the user clicks in the viewport, **Then** a new light source is placed at the clicked world position.
2. **Given** existing light sources in the scene, **When** the user clicks near a light source (within 20px screen distance), **Then** that light is selected and highlighted.
3. **Given** a selected light source, **When** the user clicks and drags in the viewport, **Then** the light moves along the XY plane at its current Z height following the mouse.
4. **Given** a selected light source, **When** the user adjusts radius/intensity controls in the palette, **Then** the light's radius and intensity update in real-time and the visual preview reflects the change.
5. **Given** the editor is in GPU_RENDER mode, **When** lights are present, **Then** per-pixel lighting with DDA ray-marched shadows is visible in real-time.

---

### Edge Cases

- What happens when the Vulkan driver does not support required extensions (e.g., VK_KHR_swapchain)? The application must report missing extensions clearly and exit gracefully.
- How does the system handle window resize during rendering? The Vulkan swap chain must be recreated and rendering must resume without artifacts.
- What happens if SPIR-V shader compilation fails at build time? The build must fail with clear error messages indicating which shader failed and why.
- How does the system behave on systems with multiple GPUs? The application should select a discrete GPU when available, with fallback to integrated.
- What happens when texture loading fails (corrupt file, unsupported format)? A placeholder texture should be used and a warning logged.
- What happens when a surface pixel is at the boundary of an occupied voxel? The DDA ray-march must skip the starting voxel to avoid self-occlusion artifacts.
- What happens when floor tiles are in the occupancy grid? Only wall tiles should be marked as occluders — floor tiles are horizontal surfaces that should not block same-level light rays.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The build system MUST remove all libGDX dependencies (gdx, gdx-backend-lwjgl3, gdx-platform, vis-ui, ktx-scene2d) and add LWJGL 3.4.1 with modules: core, GLFW, Vulkan, VMA, STB, shaderc, OpenAL, Assimp, plus JOML 1.10.8.
- **FR-002**: The application MUST create and manage a GLFW window directly via LWJGL, replacing Lwjgl3Application lifecycle (create, render loop, resize, dispose).
- **FR-003**: The application MUST initialize a Vulkan instance, select a physical device, create a logical device, and manage a swap chain for frame presentation.
- **FR-004**: The rendering system MUST provide multiple Vulkan pipelines for different rendering modes: UI overlay, lit-world (per-pixel lighting), GPU 3D (depth-buffered), stencil shadow volumes, and line debug.
- **FR-005**: All GLSL shaders (`#version 450`) MUST be compilable to SPIR-V format compatible with Vulkan, via shaderc at build time and/or runtime.
- **FR-006**: The lighting system MUST support per-pixel shadow determination via 3D DDA voxel ray-marching in the fragment shader, using a 3D occupancy grid SSBO and a lighting UBO supporting up to 32 point lights.
- **FR-006b**: A stencil-based shadow volume pipeline MUST also be available as an alternative lighting/shadow approach, using 4 pipeline variants (ambient, stencil-front, stencil-back, lit).
- **FR-007**: 3D math operations (vectors, matrices, cameras, projections) MUST be provided by JOML, replacing libGDX's Vector3, Matrix4, Camera, and related classes.
- **FR-008**: Texture and image loading MUST use STB via LWJGL bindings, replacing libGDX's Pixmap and Texture classes.
- **FR-009**: Input handling (keyboard, mouse, scroll) MUST be implemented via GLFW callbacks through LWJGL, with a polling-compatible wrapper (isKeyPressed, isKeyJustPressed, getMouseX/Y, getScrollDelta).
- **FR-010**: File I/O MUST use standard Kotlin/Java APIs (java.nio.file, kotlin.io), replacing all usage of libGDX's FileHandle.
- **FR-011**: The UI system MUST be implemented as a custom immediate-mode renderer (SimpleUI) providing colored quads, bitmap font text, button interaction, and multiple rendering pipeline modes (2D UI, lit-world, GPU 3D).
- **FR-012**: The visual test suite MUST be updated to perform offscreen Vulkan rendering for frame capture, replacing OpenGL FBO-based capture.
- **FR-013**: The application MUST handle Vulkan swap chain recreation on window resize events.
- **FR-014**: The application MUST perform proper Vulkan resource cleanup (destroy pipelines, buffers, images, device, instance) on shutdown via AutoCloseable.
- **FR-015**: The build system MUST include a shader compilation step that converts GLSL to SPIR-V and fails the build on compilation errors.
- **FR-016**: The map editor MUST support light source selection by clicking (screen-space proximity detection), drag-to-move along the XY plane, and radius/intensity editing via palette controls.
- **FR-017**: The occupancy grid for shadow ray-marching MUST only mark wall tiles as occluders — floor tiles must NOT be marked as occluders to avoid incorrect self-shadowing.
- **FR-018**: The DDA ray-march shadow algorithm MUST skip the starting voxel to prevent self-occlusion artifacts on surfaces.

### Key Entities

- **VulkanContext**: The Vulkan instance, physical device, logical device, queue handles, and VMA allocator needed for all Vulkan operations.
- **SwapChain**: The presentation surface and associated image views, framebuffers, depth+stencil attachment, and render pass.
- **RenderPipeline**: The Vulkan graphics pipeline configuration including shaders, vertex input, rasterization, stencil, and blend state. Supports 5 variants: AMBIENT, STENCIL_FRONT, STENCIL_BACK, LIT, LINE_DEBUG.
- **VulkanMesh**: Vertex and index buffer data uploaded to GPU memory via VMA, supporting POSITION, POSITION_NORMAL, POSITION_NORMAL_UV vertex formats with dynamic update capability.
- **VulkanTexture**: Image, image view, and sampler created from STB-loaded pixel data.
- **Camera**: JOML-based camera with view and projection matrices (Vulkan Y-flip), project/unproject methods.
- **SimpleUI**: Immediate-mode UI renderer with three Vulkan pipeline modes (UI, lit-world, GPU 3D), font atlas, and button interaction.
- **InputSystem**: GLFW callback-based input with polling-compatible API.
- **ShadowVolumeRenderer**: Multi-pass stencil shadow renderer with per-light stencil marking and additive lit pass.
- **DebugRenderer**: Wireframe shape rendering (cubes, spheres) for editor overlays via SimpleUI.
- **LightSource**: Point light entity with position, intensity, radius, and color (stored in World).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The application launches and renders the game world with zero libGDX classes on the classpath.
- **SC-002**: Visual test suite passes with rendered output matching reference images within a defined pixel-difference threshold (e.g., 99% similarity).
- **SC-003**: The game maintains a stable frame rate of at least 60 FPS on hardware that previously ran the OpenGL version at 60 FPS.
- **SC-004**: All existing player interactions (movement, inventory, editor) function identically to the pre-migration behavior.
- **SC-005**: Window resize, minimize, restore, and close operations complete without crashes or visual artifacts.
- **SC-006**: The build completes successfully with shader compilation integrated, and fails clearly when a shader has errors.
- **SC-007**: The application starts and displays a meaningful error within 5 seconds on systems without Vulkan support.
- **SC-008**: In GPU rendering mode with lights present, surfaces facing lights are visibly brighter than surfaces in shadow.
- **SC-009**: Light sources in the editor can be placed, selected, moved, and have their radius/intensity modified.

## Assumptions

- Target systems have Vulkan 1.0+ capable GPU drivers installed. Systems without Vulkan support are out of scope for rendering fallback (OpenGL fallback is not included).
- JOML is used for 3D math (vectors, matrices, quaternions, cameras) as it is the standard Java math library for LWJGL projects.
- The UI system is implemented as a custom SimpleUI renderer rather than Dear ImGui. While ImGui stubs exist in placeholder files, the custom approach covers all current needs.
- Audio configuration currently handled by libGDX will be replaced with LWJGL's OpenAL bindings or deferred to a separate feature if audio is minimal.
- The existing GLSL shader logic has been extended with per-pixel DDA shadow ray-marching rather than being a direct translation of the original OpenGL stencil approach.
- The migration is a complete replacement — no hybrid libGDX+Vulkan state is supported. The codebase fully transitions in this feature.
- Existing game logic (map generation, line-of-sight, items system) does not depend on libGDX internals and requires only rendering/UI interface changes.
