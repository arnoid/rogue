# Tasks: Replace libGDX with LWJGL 3 + Vulkan

**Input**: Design documents from `/specs/007-replace-libgdx-vulkan/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Tests are included where the spec requires visual test suite migration (US5). No TDD approach was explicitly requested for other stories.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Single project**: `src/main/kotlin/com/roguelike/`, `src/test/kotlin/com/roguelike/`
- Shaders: `src/main/resources/shaders/`

---

## Phase 1: Setup (Build System Migration)

**Purpose**: Remove libGDX, trim LWJGL to needed modules, add JOML + imgui-java

- [x] T001 Rewrite `build.gradle.kts` — remove all libGDX deps (gdx, gdx-backend-lwjgl3, gdx-platform, vis-ui, ktx-scene2d), trim LWJGL to 8 modules (core, glfw, vulkan, vma, stb, shaderc, openal, assimp), add JOML 1.10.8 and imgui-java 1.87.6 with multi-platform natives per plan.md dependency block
- [x] T002 Add `compileShaders` Gradle task in `build.gradle.kts` that compiles `src/main/resources/shaders/*.vert.glsl` and `*.frag.glsl` to `.spv` via shaderc, outputting to `build/resources/main/shaders/`, failing build on errors
- [x] T003 Update `tasks.test` block in `build.gradle.kts` — remove OpenGL jvmArgs, add Vulkan-related configuration, keep forkEvery=1 and maxParallelForks=1
- [x] T004 Update `application` and `JavaExec` blocks in `build.gradle.kts` — remove `-XstartOnFirstThread` (libGDX-specific), keep workingDir and system property forwarding

**Checkpoint**: `./gradlew dependencies` resolves with zero libGDX artifacts, LWJGL/JOML/imgui-java present

---

## Phase 2: Foundational (Vulkan Infrastructure + Math Migration)

**Purpose**: Core Vulkan infrastructure and JOML math that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 [P] Implement `VulkanContext` in `src/main/kotlin/com/roguelike/rendering/vulkan/VulkanContext.kt` — VkInstance (with validation layers in debug), physical device selection (discrete GPU preferred), logical device, graphics+present queues, VMA allocator, surface handle; implement AutoCloseable per contract
- [x] T006 [P] Implement `VulkanDebug` in `src/main/kotlin/com/roguelike/rendering/vulkan/VulkanDebug.kt` — debug messenger setup/teardown, stderr logging callback, conditional on debug flag
- [x] T007 [P] Implement `ShaderCompiler` in `src/main/kotlin/com/roguelike/rendering/vulkan/ShaderCompiler.kt` — runtime SPIR-V compilation via lwjgl-shaderc for dev iteration, load pre-compiled .spv files for release, create VkShaderModule
- [x] T008 Implement `SwapChain` in `src/main/kotlin/com/roguelike/rendering/vulkan/SwapChain.kt` — swap chain creation (B8G8R8A8_SRGB, MAILBOX/FIFO), image views, depth+stencil attachment (D24_S8 or D32_S8), framebuffers, render pass with color+depth+stencil, recreate on resize; implement AutoCloseable per contract (depends on T005)
- [x] T009 Implement `RenderPipeline` in `src/main/kotlin/com/roguelike/rendering/vulkan/RenderPipeline.kt` — pipeline creation for PassType enum (AMBIENT, STENCIL_FRONT, STENCIL_BACK, LIT, LINE_DEBUG) with correct stencil/blend/cull/topology state per data-model.md variants; descriptor set layout (SceneUBO binding 0, LightUBO binding 1, MaterialUBO binding 2), push constant range (64 bytes vertex), pipeline layout (depends on T007, T008)
- [x] T010 [P] Migrate math types throughout codebase — replace all `com.badlogic.gdx.math.Vector3` → `org.joml.Vector3f`, `Matrix4` → `Matrix4f`, `Color` → `Vector4f` in: `src/main/kotlin/com/roguelike/rendering/PointLightData.kt`, `WorldRenderer.kt`, `TileRenderer.kt`, `ItemRenderer.kt`, `PropRenderer.kt`, `OrientationGizmo.kt`, `InventoryUI.kt`, and any other files with libGDX math imports
- [x] T011 [P] Migrate file I/O — replace all `com.badlogic.gdx.files.FileHandle` and `Gdx.files.*` usages with `java.nio.file.Path` / classloader `getResourceAsStream` throughout `src/main/kotlin/com/roguelike/`

**Checkpoint**: Foundation ready — VulkanContext can be created, swap chain presents blank frames, all math types are JOML, zero libGDX imports remain in non-rendering files

---

## Phase 3: User Story 1 — Application Launches with Vulkan Window (Priority: P1) 🎯 MVP

**Goal**: GLFW window opens with Vulkan rendering surface, clean shutdown destroys all resources

**Independent Test**: Launch app → GLFW window appears → Vulkan instance+device created → swap chain presents frames → close window → clean exit with no errors

### Implementation for User Story 1

- [x] T012 [US1] Rewrite `src/main/kotlin/com/roguelike/Main.kt` — replace `Lwjgl3Application` with GLFW window creation (`glfwCreateWindow`), Vulkan surface via `GLFWVulkan.glfwCreateWindowSurface()`, initialize VulkanContext + SwapChain, implement main game loop (glfwPollEvents → acquire image → record empty command buffer → submit → present), clean shutdown destroying all Vulkan resources
- [x] T013 [US1] Rewrite `src/main/kotlin/com/roguelike/RoguelikeLauncher.kt` — remove `ApplicationAdapter` base class, implement state machine enum (INIT → MENU → GAME | EDITOR → SHUTDOWN), replace `create()`/`render()`/`dispose()` with init/loop/cleanup methods called from Main.kt game loop
- [x] T014 [US1] Implement swap chain recreation on window resize in `src/main/kotlin/com/roguelike/Main.kt` — handle `VK_ERROR_OUT_OF_DATE_KHR` and `VK_SUBOPTIMAL_KHR` from acquire/present, call `SwapChain.recreate()`, handle minimized window (extent=0) by pausing render loop
- [x] T015 [US1] Implement Vulkan error handling in `src/main/kotlin/com/roguelike/Main.kt` — detect missing Vulkan support at startup, display clear error message and exit within 5 seconds; detect missing required extensions (`VK_KHR_swapchain`), report and exit gracefully

**Checkpoint**: `./gradlew run` opens a GLFW window with Vulkan, presents cleared frames, resizes correctly, closes cleanly

---

## Phase 4: User Story 2 — 3D Scene Renders Correctly via Vulkan (Priority: P1)

**Goal**: Full shadow volume stencil rendering pipeline produces visually identical output to previous OpenGL pipeline

**Independent Test**: Load a scene with models, textures, point lights → rendered output matches OpenGL reference screenshots

### Implementation for User Story 2

- [x] T016 [P] [US2] Implement `VulkanMesh` in `src/main/kotlin/com/roguelike/rendering/vulkan/VulkanMesh.kt` — VMA vertex/index buffer creation, bind/draw commands, dynamic update for shadow volumes, support POSITION, POSITION_NORMAL, POSITION_NORMAL_UV vertex formats per data-model.md; implement AutoCloseable per contract
- [x] T017 [P] [US2] Implement `VulkanTexture` in `src/main/kotlin/com/roguelike/rendering/vulkan/VulkanTexture.kt` — STB image loading → staging buffer → vkCmdCopyBufferToImage → layout transition, VkImageView + VkSampler creation, solid color fallback texture; implement AutoCloseable per contract
- [x] T018 [P] [US2] Rewrite shaders to Vulkan GLSL `#version 450` in `src/main/resources/shaders/`:
  - `ambient_pass.vert.glsl` — layout qualifiers, SceneUBO (set=0, binding=0), push constants (modelMatrix)
  - `ambient_pass.frag.glsl` — layout qualifiers, MaterialUBO (set=0, binding=2), outColor location 0
  - `lit_pass.vert.glsl` — layout qualifiers, SceneUBO, push constants, output v_normal + v_worldPos
  - `lit_pass.frag.glsl` — layout qualifiers, LightUBO (set=0, binding=1), MaterialUBO, additive output
  - `shadow_volume.vert.glsl` — position-only input, SceneUBO, push constants
  - `shadow_volume.frag.glsl` — empty (color write disabled via pipeline)
- [x] T019 [US2] Rewrite `src/main/kotlin/com/roguelike/rendering/Camera.kt` — JOML-based with Vector3f position/direction/up, Matrix4f view/projection/viewProjection, `perspectiveVulkan()` for Y-flip, project/unproject methods per contract
- [x] T020 [US2] Implement `AssetLoader` in `src/main/kotlin/com/roguelike/rendering/AssetLoader.kt` — STB texture loading → VulkanTexture, Assimp model loading (.obj) → VulkanMesh, replace all libGDX Pixmap/Texture/Model loading (depends on T016, T017)
- [x] T021 [US2] Rewrite `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` — Vulkan command buffer recording with 4 pipeline variants: ambient pass (draw scene with ambient pipeline), per-light stencil pass (clear stencil → bind stencil-front pipeline draw shadow volumes → bind stencil-back pipeline draw shadow volumes), per-light lit pass (bind lit pipeline with stencil test EQUAL 0, additive blend, draw scene); use descriptor sets for SceneUBO/LightUBO/MaterialUBO, push constants for model matrix (depends on T009, T016, T017, T018, T019)
- [x] T022 [US2] Delete `src/main/kotlin/com/roguelike/rendering/ShadowVolumeShaderProvider.kt` — replaced by RenderPipeline
- [x] T023 [US2] Rewrite `src/main/kotlin/com/roguelike/rendering/PointLightData.kt` — replace libGDX Vector3 with JOML Vector3f for light position (may already be done in T010, verify and finalize)
- [x] T024 [US2] Update `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt` — replace ModelBatch/ModelInstance/Environment rendering with VulkanMesh binding and Vulkan draw call recording via ShadowVolumeRenderer, use JOML transforms (depends on T021)
- [x] T025 [US2] Update `src/main/kotlin/com/roguelike/rendering/TileRenderer.kt` — replace libGDX model rendering with VulkanMesh-based rendering, JOML transforms (depends on T016, T020)
- [x] T026 [P] [US2] Update `src/main/kotlin/com/roguelike/rendering/ItemRenderer.kt` — replace libGDX model rendering with VulkanMesh-based rendering (depends on T016, T020)
- [x] T027 [P] [US2] Update `src/main/kotlin/com/roguelike/rendering/PropRenderer.kt` — replace libGDX model rendering with VulkanMesh-based rendering (depends on T016, T020)
- [x] T028 [US2] Update `src/main/kotlin/com/roguelike/rendering/OrientationGizmo.kt` — replace libGDX rendering with VulkanMesh line debug pipeline (depends on T009, T016)

**Checkpoint**: `./gradlew run` renders the game world with models, textures, shadow volumes, and lighting matching prior OpenGL output

---

## Phase 5: User Story 3 — Player Input Works Without libGDX (Priority: P1)

**Goal**: Keyboard and mouse input handled via GLFW callbacks, game is playable

**Independent Test**: Launch game → press movement keys → character moves; click mouse → UI/game responds

### Implementation for User Story 3

- [x] T029 [US3] Implement `InputSystem` in `src/main/kotlin/com/roguelike/input/InputSystem.kt` — GLFW callback installation (key, mouse button, cursor position, scroll), per-frame key/button state arrays, polling API (isKeyPressed, isKeyJustPressed, isMouseButtonPressed, getMouseX/Y, getScrollDelta), endFrame() to clear just-pressed state; implement contract interface
- [ ] T030 [US3] Integrate InputSystem into game loop in `src/main/kotlin/com/roguelike/Main.kt` — install callbacks after window creation, call endFrame() at end of each frame, pass InputSystem to RoguelikeLauncher
- [ ] T031 [US3] Update all `Gdx.input.*` call sites throughout `src/main/kotlin/com/roguelike/` — replace `Gdx.input.isKeyPressed()` → `InputSystem.isKeyPressed()`, `Gdx.input.getX/Y()` → `InputSystem.getMouseX/Y()`, update `systems/`, `editor/`, `MapEditor.kt`, `MainMenuScreen.kt`, and any other input consumers

**Checkpoint**: Game is fully playable via keyboard and mouse with zero Gdx.input references

---

## Phase 6: User Story 4 — UI System Works Without vis-ui (Priority: P2)

**Goal**: Dear ImGui replaces vis-ui/scene2d for world editor and inventory UI

**Independent Test**: Open world editor → all panels, buttons, text fields render and respond to interaction

### Implementation for User Story 4

- [ ] T032 [US4] Implement `ImGuiSystem` in `src/main/kotlin/com/roguelike/ui/ImGuiSystem.kt` — initialize imgui-java with GLFW window + Vulkan context, begin/end frame, record ImGui draw commands, input multiplexing (ImGui gets first pass via wantsKeyboard/wantsMouse); implement UISystem contract
- [ ] T033 [US4] Integrate ImGuiSystem into game loop in `src/main/kotlin/com/roguelike/Main.kt` — call beginFrame() before game update, endFrame() after scene rendering to record ImGui commands into command buffer
- [ ] T034 [US4] Rewrite `src/main/kotlin/com/roguelike/editor/` (WorldEditor) — replace all vis-ui widgets (VisWindow, VisTable, VisTextButton, VisTextField, VisList, VisSlider, VisSplitPane) with Dear ImGui equivalents (ImGui.begin/end, ImGui.button, ImGui.inputText, ImGui.listBox, ImGui.sliderFloat, docking)
- [ ] T035 [US4] Rewrite `src/main/kotlin/com/roguelike/MapEditor.kt` — replace vis-ui/scene2d UI with Dear ImGui for map editing tools and controls
- [ ] T036 [US4] Rewrite `src/main/kotlin/com/roguelike/rendering/InventoryUI.kt` — replace vis-ui item lists/panels with Dear ImGui windows and list widgets
- [ ] T037 [US4] Rewrite `src/main/kotlin/com/roguelike/MainMenuScreen.kt` — replace libGDX Screen/scene2d with Dear ImGui menu rendered in MENU state of RoguelikeLauncher

**Checkpoint**: World editor and inventory fully functional with Dear ImGui, zero vis-ui/scene2d imports

---

## Phase 7: User Story 5 — Visual Test Suite Runs with Vulkan (Priority: P2)

**Goal**: Visual test harness captures Vulkan-rendered frames for comparison against reference images

**Independent Test**: `./gradlew test` — all visual tests execute, capture output, produce pass/fail against references

### Implementation for User Story 5

- [x] T038 [P] [US5] Implement `VulkanAvailability` in `src/test/kotlin/com/roguelike/rendering/VulkanAvailability.kt` — replace `GLAvailability.kt`, check for Vulkan support, skip tests if unavailable
- [x] T039 [US5] Rewrite `src/test/kotlin/com/roguelike/rendering/RenderTestHarness.kt` — headless Vulkan device (no surface/swap chain), offscreen color+depth+stencil framebuffer, render scene, `vkCmdCopyImageToBuffer` readback to host-visible buffer, return pixel data for comparison
- [ ] T040 [US5] Rewrite `src/test/kotlin/com/roguelike/rendering/SceneBuilder.kt` — use VulkanMesh + JOML transforms instead of libGDX Model/ModelInstance
- [ ] T041 [US5] Rewrite `src/test/kotlin/com/roguelike/rendering/MinimalGLTest.kt` → `MinimalVulkanTest.kt` — minimal Vulkan rendering test using new harness
- [ ] T042 [US5] Update `src/test/kotlin/com/roguelike/rendering/BasicShadowLightTest.kt` — use new RenderTestHarness
- [ ] T043 [P] [US5] Update remaining visual tests to use new harness: `EdgeCaseRobustnessTest.kt`, `LightPositionDistanceTest.kt`, `MultiLightInteractionTest.kt`, `RegressionArtifactTest.kt`, `ShadowVolumeGeometryTest.kt` in `src/test/kotlin/com/roguelike/rendering/`
- [ ] T044 [US5] Delete `src/test/kotlin/com/roguelike/rendering/GLAvailability.kt` and `GLTestBase.kt` — replaced by Vulkan equivalents

**Checkpoint**: `./gradlew test` passes with all visual tests using Vulkan offscreen rendering

---

## Phase 8: User Story 6 — File and Asset Loading Without libGDX (Priority: P3)

**Goal**: All asset loading uses STB/Assimp/standard Java I/O, zero libGDX FileHandle usage

**Independent Test**: Load a PNG texture and config file → texture usable in Vulkan pipeline, config data parsed correctly

### Implementation for User Story 6

- [ ] T045 [US6] Verify and finalize `src/main/kotlin/com/roguelike/rendering/AssetLoader.kt` — ensure all texture formats load correctly via STB, all model formats load via Assimp, error handling for corrupt/missing files (placeholder texture + warning log)
- [ ] T046 [US6] Audit and update `src/main/kotlin/com/roguelike/serialization/` — replace any remaining `FileHandle` or `Gdx.files` references with `java.nio.file.Path` and standard Kotlin I/O
- [ ] T047 [US6] Audit and update `src/main/kotlin/com/roguelike/world/World.kt` — remove any `FileHandle` usage for world save/load, use `java.nio.file.Path`

**Checkpoint**: Zero `com.badlogic.gdx` imports in entire codebase, all assets load correctly

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final cleanup, verification, and cross-cutting improvements

- [x] T048 [P] Remove all remaining `com.badlogic.gdx.*` imports — grep entire `src/` tree, fix any stragglers
- [ ] T049 [P] Verify `./gradlew compileShaders` produces valid .spv files for all 6 shaders and fails on intentional error
- [ ] T050 Verify clean resource cleanup — run with Vulkan validation layers, confirm zero validation errors on startup, rendering, resize, and shutdown
- [ ] T051 Performance validation — verify 60 FPS on target hardware, profile frame time with RenderDoc
- [ ] T052 [P] Update `specs/007-replace-libgdx-vulkan/quickstart.md` if any build/run commands changed
- [ ] T053 Run `./gradlew build` end-to-end — confirm zero compile errors, all tests pass, no libGDX on classpath

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational — window + game loop
- **US2 (Phase 4)**: Depends on US1 (needs working window + game loop to render into)
- **US3 (Phase 5)**: Depends on US1 (needs GLFW window for callbacks). Can run in parallel with US2
- **US4 (Phase 6)**: Depends on US1 + US3 (needs window + input for ImGui). Can partially overlap with US2
- **US5 (Phase 7)**: Depends on US2 (needs working renderer for visual tests)
- **US6 (Phase 8)**: Depends on US2 (AssetLoader created in T020). Can start after T020 is done
- **Polish (Phase 9)**: Depends on all user stories being complete

### User Story Dependencies

- **US1 (P1)**: After Foundational — no story dependencies
- **US2 (P1)**: After US1 — needs working window/loop to render into
- **US3 (P1)**: After US1 — needs GLFW window; independent of US2
- **US4 (P2)**: After US1 + US3 — needs window + input
- **US5 (P2)**: After US2 — needs complete rendering pipeline
- **US6 (P3)**: After US2 (T020) — finalizes asset loading

### Within Each User Story

- Infrastructure/models before services
- Services before renderers
- Shaders before pipelines that use them
- Core implementation before integration

### Parallel Opportunities

- T005, T006, T007 (VulkanContext, VulkanDebug, ShaderCompiler) can run in parallel
- T010, T011 (math migration, file I/O migration) can run in parallel with Vulkan infrastructure
- T016, T017, T018 (VulkanMesh, VulkanTexture, shaders) can run in parallel
- T026, T027 (ItemRenderer, PropRenderer) can run in parallel
- T038, T043 (VulkanAvailability, batch test updates) can run in parallel
- US3 and US2 can be worked on simultaneously after US1

---

## Parallel Example: Phase 2 (Foundational)

```
# These can all run in parallel:
T005: Implement VulkanContext in rendering/vulkan/VulkanContext.kt
T006: Implement VulkanDebug in rendering/vulkan/VulkanDebug.kt
T007: Implement ShaderCompiler in rendering/vulkan/ShaderCompiler.kt
T010: Migrate math types (Vector3→Vector3f, Matrix4→Matrix4f) throughout codebase
T011: Migrate file I/O (FileHandle→Path) throughout codebase

# Then sequentially:
T008: SwapChain (needs T005)
T009: RenderPipeline (needs T007, T008)
```

## Parallel Example: Phase 4 (US2 — 3D Rendering)

```
# These can all run in parallel:
T016: VulkanMesh in rendering/vulkan/VulkanMesh.kt
T017: VulkanTexture in rendering/vulkan/VulkanTexture.kt
T018: Rewrite all 6 shaders to Vulkan GLSL #version 450

# Then:
T019: Camera rewrite (independent)
T020: AssetLoader (needs T016, T017)
T021: ShadowVolumeRenderer rewrite (needs T009, T016-T019)
T024-T028: Individual renderer updates (need T021)
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: Setup (build system migration)
2. Complete Phase 2: Foundational (Vulkan infra + math)
3. Complete Phase 3: US1 (window + game loop)
4. **STOP and VALIDATE**: App launches, shows Vulkan window, resizes, closes cleanly
5. No libGDX on classpath

### Incremental Delivery

1. Setup + Foundational → Build compiles, Vulkan basics work
2. US1 → Window + game loop → Validate launch/close
3. US2 → 3D rendering → Validate visual parity with OpenGL
4. US3 → Input → Game is playable
5. US4 → UI → Editor and menus work
6. US5 → Visual tests → Quality assurance restored
7. US6 → Asset cleanup → Zero libGDX anywhere
8. Polish → Validation layers clean, 60 FPS confirmed

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Vulkan validation layers should be enabled throughout development to catch resource leaks





