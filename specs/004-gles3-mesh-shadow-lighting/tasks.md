# Tasks: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Input**: Design documents from `specs/004-gles3-mesh-shadow-lighting/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Organization**: Tasks grouped by user story. Phase 1 (cleanup) and Phase 2 (foundational shader pipeline) must complete before any user story phase begins.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to

---

## Phase 1: Setup — Delete Old CPU Lighting System

**Purpose**: Remove all CPU-based lighting code cleanly. After this phase the project will not compile until Phase 2 foundational work is complete. Delete first, fix callers in Phase 3.

- [X] T001 Delete src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt
- [X] T002 [P] Delete src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt
- [X] T003 [P] Delete src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt
- [X] T004 [P] Delete src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt
- [X] T005 [P] Delete src/main/kotlin/com/roguelike/rendering/BvhOccluder.kt
- [X] T006 [P] Delete src/main/resources/shaders/point_shadow.frag.glsl
- [X] T007 [P] Delete src/test/kotlin/com/roguelike/core/DynamicLightingTest.kt
- [X] T008 [P] Delete src/test/kotlin/com/roguelike/core/LightingSystemTest.kt
- [X] T009 [P] Delete src/test/kotlin/com/roguelike/core/LightingSystemConeTest.kt
- [X] T010 [P] Delete src/test/kotlin/com/roguelike/core/SurfaceLightingTest.kt
- [X] T011 [P] Delete src/test/kotlin/com/roguelike/core/SurfaceLightingConeSmoothTest.kt
- [X] T012 [P] Delete src/test/kotlin/com/roguelike/core/SurfaceLightingModelOcclusionTest.kt
- [X] T013 Update src/test/kotlin/com/roguelike/core/DropPickupTest.kt — remove the two `LightingSystem.compute(world, player)` calls at lines 90 and 107; replace with a simpler non-lighting assertion (e.g. just assert item count) so the test still validates the drop/pickup mechanic without needing LightingSystem

**Checkpoint**: All 12 deleted files gone. DropPickupTest compiles without LightingSystem import.

---

## Phase 2: Foundational — GPU Shader Pipeline

**Purpose**: Write the GLSL 1.50 shader pair and the Kotlin shader wrapper classes that all user stories depend on. All user story phases are blocked until this is complete.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T014 Write src/main/resources/shaders/gles3_lighting.vert.glsl — GLSL 1.50 vertex shader. Declare `in vec3 a_position`, `in vec3 a_normal`, `in vec2 a_texCoord0`. Uniforms: `u_projViewTrans` (mat4), `u_worldTrans` (mat4), `u_normalMatrix` (mat3), `u_shadowMapProjViewTrans` (mat4). Outputs: `out vec2 v_texCoord`, `out vec3 v_worldPos`, `out vec3 v_worldNormal`, `out vec4 v_shadowCoord`. Compute `gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0)`. Compute `v_worldPos = vec3(u_worldTrans * vec4(a_position, 1.0))`. Compute `v_worldNormal = normalize(u_normalMatrix * a_normal)`. Compute `v_shadowCoord = u_shadowMapProjViewTrans * vec4(v_worldPos, 1.0)`.

- [X] T015 Write src/main/resources/shaders/gles3_lighting.frag.glsl — GLSL 1.50 fragment shader. Declare `out vec4 fragColor`. Inputs: `in vec2 v_texCoord`, `in vec3 v_worldPos`, `in vec3 v_worldNormal`, `in vec4 v_shadowCoord`. Uniforms: `u_diffuseTexture` (sampler2D), `u_ambientColor` (vec3), `u_hasDirLight` (int), `u_dirLightDir` (vec3), `u_dirLightColor` (vec3), `u_shadowMap` (sampler2D), `u_pointLightCount` (int), `u_pointLightPos[8]` (vec3), `u_pointLightColor[8]` (vec3), `u_pointLightIntensity[8]` (float), `u_hasPointShadow[8]` (int), `u_pointShadowCube[8]` (samplerCube), `u_pointShadowFarPlane[8]` (float). Compute Blinn-Phong diffuse for directional light (dot product of normal and light direction, clamped 0..1). Compute directional shadow factor via PCF 3×3 on `u_shadowMap` (sample shadow map in NDC space from `v_shadowCoord`, compare depth with bias 0.005). For each point light (0..<u_pointLightCount): compute distance attenuation `1.0 / (dist * dist)`, diffuse term, and optionally sample `u_pointShadowCube[i]` for shadow. Sum all contributions + ambient. Output: `fragColor = texture(u_diffuseTexture, v_texCoord) * vec4(totalLight, 1.0)`.

- [X] T016 Write src/test/kotlin/com/roguelike/rendering/Gles3LightingShaderUniformTest.kt — headless test that reads `gles3_lighting.vert.glsl` and `gles3_lighting.frag.glsl` as raw strings via `File(...)` and asserts that each expected uniform name appears in the respective source: `u_projViewTrans`, `u_worldTrans`, `u_normalMatrix`, `u_shadowMapProjViewTrans` in vert; `u_ambientColor`, `u_pointLightCount`, `u_pointLightPos`, `u_pointLightColor`, `u_pointLightIntensity`, `u_pointShadowCube`, `u_pointShadowFarPlane`, `u_hasPointShadow`, `u_hasDirLight`, `u_dirLightDir`, `u_dirLightColor`, `u_shadowMap`, `u_diffuseTexture` in frag. Run `./gradlew test` and confirm RED (test fails because shader files do not exist yet if run before T014/T015, or confirm it passes after T014/T015 are written). This test is permanently valuable as a compile-time contract for the shader uniform interface.

- [X] T017 Write src/main/kotlin/com/roguelike/rendering/Gles3LightingShader.kt — implement `com.badlogic.gdx.graphics.g3d.Shader`. Constructor loads `gles3_lighting.vert.glsl` and `gles3_lighting.frag.glsl` via `Gdx.files.internal("shaders/...")` and compiles them into a `ShaderProgram`. Fields: store uniform locations for all uniforms listed in T015. `init(renderable)`: no-op. `begin(camera, context)`: call `program.bind()`, set `u_projViewTrans` from `camera.combined`. `render(renderable)`: set `u_worldTrans` from `renderable.worldTransform`, compute `u_normalMatrix` as the upper-left 3×3 of the inverse-transpose of `u_worldTrans`, bind diffuse texture from `renderable.material` TextureAttribute to unit 0, set `u_diffuseTexture = 0`, call `renderable.meshPart.mesh.render(program, renderable.meshPart.primitiveType, renderable.meshPart.offset, renderable.meshPart.size)`. `end()`: no-op. `canRender(renderable)`: return true for all renderables. `compareTo(other)`: return 0. Add `setLightEnvironment(gpuLightEnv: GpuLightEnvironment)` method that sets all ambient, directional, and point-light uniforms. Add `setShadowMap(texture: Texture?)` method that binds the texture to unit 1 and sets `u_shadowMap = 1`. Add `setPointShadowCubemap(index: Int, fbo: FrameBufferCubemap?, farPlane: Float)` method that binds the cubemap color buffer texture to unit `2+index` and sets `u_pointShadowCube[index]`, `u_pointShadowFarPlane[index]`, `u_hasPointShadow[index]`. `dispose()`: dispose `program`.

- [X] T018 Write src/main/kotlin/com/roguelike/rendering/Gles3ShaderProvider.kt — implement `com.badlogic.gdx.graphics.g3d.utils.ShaderProvider`. Lazily create and cache a single `Gles3LightingShader` instance on first call to `getShader(renderable)`. Return the cached instance for all subsequent calls. `dispose()`: dispose the cached shader if non-null.

- [X] T019 Replace src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt entirely. New implementation: `mainBatch = ModelBatch(Gles3ShaderProvider())`, `shadowBatch = ModelBatch(DepthShaderProvider())`. Keep fields: `shadowLight: DirectionalShadowLight?`, `cubemapPool: Array<FrameBufferCubemap?>(8)`, `cubemapFarPlane: FloatArray(8)`, `cubemapCamera: PerspectiveCamera(90f, 512f, 512f)`, `sceneCentre: Vector3`. Keep methods: `initDirectionalLight(data)`, `setSceneCentre(x,y,z)`, `renderCubemapDepthPass(index, lightData, renderScene)` (already correct). Rewrite `render(camera, gpuLightEnv, renderScene)`: (1) for each index in 0..<gpuLightEnv.pointLights.size, call `renderCubemapDepthPass(i, gpuLightEnv.pointLights[i], renderScene)`; (2) if shadowLight != null, run directional depth pass; (3) get `Gles3LightingShader` from `(mainBatch.shaderProvider as Gles3ShaderProvider).getShader(null)` (or cache it) and call `setLightEnvironment(gpuLightEnv)`, `setShadowMap(shadowLight?.frameBuffer?.colorBufferTexture)`, `setPointShadowCubemap(i, cubemapPool[i], cubemapFarPlane[i])` for each slot; (4) `mainBatch.begin(camera)`; call `renderScene(mainBatch, Environment())`; `mainBatch.end()`. Keep `fromActor()` companion. Remove `buildEnvironment()` companion. `dispose()`: dispose `mainBatch`, `shadowBatch`, `shadowLight`, all cubemapPool entries.

- [X] T020 Update src/test/kotlin/com/roguelike/rendering/ShadowRendererConstructionTest.kt — remove the three `buildEnvironment()` tests (method deleted). Add one smoke test: instantiate `ShadowRenderer()` and immediately call `dispose()` — should not throw. (Cannot test rendering without a GL context.)

**Checkpoint**: `./gradlew compileKotlin` and `./gradlew test` both pass. Shader source files present. Old lighting classes gone.

---

## Phase 3: User Story 1 — Mesh-Accurate Shadow Casting (Priority: P1) 🎯 MVP

**Goal**: Player sees mesh-precise shadows with zero light bleed through walls. Old DynamicLighting call in RoguelikeGame replaced by ShadowRenderer.

**Independent Test**: `./gradlew run` → equip a torch → stand next to a wall → shadow on floor follows wall geometry exactly.

### Implementation for User Story 1

- [X] T021 [US1] Simplify src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt — delete the legacy `render(world, batch, environment, maxZ, dynamicLighting)` overload and the `envForTile()` private helper. Only the GPU overload `render(world, camera, shadowRenderer, gpuLightEnv, maxZ)` remains. Remove the `DynamicLighting` import.

- [X] T022 [US1] Update src/main/kotlin/com/roguelike/RoguelikeGame.kt — (a) Remove `import com.roguelike.rendering.BvhOccluder`, `import com.roguelike.core.systems.LightingSystem`, and the `private val bvhOccluder = BvhOccluder()` field. (b) Add `private val shadowRenderer = ShadowRenderer()`. (c) Remove the `modelBatch` field that was used for the old path (if it exists separately from shadowRenderer's batch). (d) In `create()` or `show()`, call `shadowRenderer.initDirectionalLight(DirectionalLightData(-0.5f, -1f, -0.3f, 0.8f, 0.8f, 0.8f, 1.0f))` to configure a default sun. (e) In the render loop, replace the three lines `bvhOccluder.rebuild(...)` + `DynamicLighting.build(...)` + `worldRenderer.render(world, modelBatch, environment, ...)` with: `shadowRenderer.setSceneCentre(player.position.x, player.position.y, player.position.z)`, then `val gpuEnv = ShadowRenderer.fromActor(player)`, then `worldRenderer.render(world, camera, shadowRenderer, gpuEnv, maxZ = playerZ)`. (f) Replace `modelBatch.render(playerInstance, playerEnv)` with `modelBatch.render(playerInstance, Environment())` or include it inside the renderScene lambda. (g) In `dispose()`, call `shadowRenderer.dispose()`.

- [ ] T023 [US1] Visual acceptance: run `./gradlew run`, equip torch item, stand adjacent to a wall, observe shadow on floor tile. Confirm shadow boundary matches wall mesh edge. Confirm no light leaks to the room's far side. Mark PASS or note any shader compilation errors to fix.

**Checkpoint**: Shadow rendering pipeline is live. Old DynamicLighting code path is gone from the running game.

---

## Phase 4: User Story 2 — Per-Pixel Partial Mesh Lighting (Priority: P2)

**Goal**: Individual floor tiles show a brightness gradient — bright near the torch, dim far from it — not binary on/off.

**Independent Test**: Single torch + large floor tile → visible intensity gradient across the tile surface.

### Implementation for User Story 2

- [ ] T024 [US2] Verify src/main/kotlin/com/roguelike/rendering/Gles3LightingShader.kt `setLightEnvironment()` correctly sets `u_pointLightPos[i]`, `u_pointLightColor[i]`, `u_pointLightIntensity[i]` from the PointLightData list. If actor-carried lights don't have correct world-space positions, update `ShadowRenderer.fromActor()` to use `actor.position.x/y/z` as the light's world position.

- [ ] T025 [US2] Visual acceptance: run `./gradlew run`, equip torch, stand at one corner of a large room. Observe floor tiles along the wall extending away from you. Confirm at least three distinct brightness levels are visible (bright near → medium → dim far). Mark PASS or note regression.

**Checkpoint**: Per-pixel gradient visible. No tile is uniformly "full bright" or "full dark" when partially lit.

---

## Phase 5: User Story 3 — Multiple Dynamic Point Lights (Priority: P3)

**Goal**: Two or more torch sources simultaneously illuminate surfaces with additive blending, each casting independent shadows.

**Independent Test**: Drop one torch, hold another → both illuminate corridor → wall blocks each independently.

### Implementation for User Story 3

- [ ] T026 [US3] Verify src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt `render()` calls `renderCubemapDepthPass(i, gpuLightEnv.pointLights[i], renderScene)` for each active point light before the main pass. If not, add those calls now.

- [ ] T027 [US3] Update src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt `render()` — after cubemap depth passes and before `mainBatch.begin()`, call `setPointShadowCubemap(i, cubemapPool[i], cubemapFarPlane[i])` on the cached `Gles3LightingShader` instance for each of the 8 slots (use null for empty slots).

- [ ] T028 [US3] Verify src/main/resources/shaders/gles3_lighting.frag.glsl point light shadow loop correctly samples `u_pointShadowCube[i]` for each light where `u_hasPointShadow[i] == 1`. Compute `fragToLight = v_worldPos - u_pointLightPos[i]`, sample `texture(u_pointShadowCube[i], fragToLight).r * u_pointShadowFarPlane[i]`, compare with `length(fragToLight)` minus bias 0.05. If not correctly implemented, update the fragment shader now.

- [ ] T029 [US3] Visual acceptance: run `./gradlew run`, drop one torch item on the floor, keep one equipped. Move player so both torches are visible. Confirm: (a) floor between them receives light from both; (b) wall casting a shadow from left torch does not block right torch's illumination of the right side. Mark PASS.

**Checkpoint**: Multi-light additive blending confirmed. Each light's shadow is independently occluded.

---

## Phase 6: User Story 4 — Directional Ambient Light (Priority: P4)

**Goal**: Fully shadowed surfaces are dim but not pitch black.

**Independent Test**: Drop all torches → room is dark but grey/dim, not absolute black.

### Implementation for User Story 4

- [ ] T030 [US4] Verify src/main/kotlin/com/roguelike/rendering/Gles3LightingShader.kt `setLightEnvironment(gpuLightEnv)` sets `u_ambientColor` from `gpuLightEnv.ambientR/G/B`. Confirm `ShadowRenderer.fromActor()` passes non-zero ambient values (default 0.2f per GpuLightEnvironment.build). If ambient is zero, set it to 0.15f..0.2f so shadowed areas are visibly dim.

- [ ] T031 [US4] Visual acceptance: run `./gradlew run`, drop all torch items. Confirm walls and floor are faintly lit (greyed out) rather than pitch black. Set ambient to 0.0f as a control test to confirm scene goes black, then restore to 0.2f. Mark PASS.

**Checkpoint**: Ambient contribution visible. Scene never fully black.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Verification, regression checks, and cleanup.

- [X] T032 Run `grep -r "DynamicLighting\|SurfaceLighting\|LightingSystem\|BvhOccluder" src/main --include="*.kt"` and confirm zero matches. If any remain, remove them.

- [X] T033 Run `./gradlew test` and confirm BUILD SUCCESSFUL with all tests passing.

- [ ] T034 [P] Run `./gradlew run` and observe in-game FPS with 8 torch items equipped (use F3 or log if available). Confirm ≥ 30 FPS. If below threshold, reduce cubemap resolution from 512 to 256 in `ShadowRenderer.CUBEMAP_SIZE` and re-test.

- [ ] T035 [P] Run quickstart.md regression checklist: shader files present, old shader deleted, no old lighting imports. Record results.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Cleanup)**: No dependencies — can start immediately. All deletions are parallel [P].
- **Phase 2 (Foundational)**: Depends on Phase 1. Write shaders → write test → write shader class → write provider → replace ShadowRenderer. Tasks T014→T015→T016→T017→T018→T019→T020 (sequential within phase; T014 and T015 can run in parallel since they're different files).
- **Phase 3 (US1)**: Depends on Phase 2 complete. Must run before US2, US3, US4.
- **Phase 4 (US2)**: Depends on Phase 3 (US1 pipeline live).
- **Phase 5 (US3)**: Depends on Phase 3 (US1 pipeline live). Can run in parallel with Phase 4 on different files.
- **Phase 6 (US4)**: Depends on Phase 3. Can run after US1.
- **Phase 7 (Polish)**: Depends on all story phases complete.

### User Story Dependencies

- **US1 (P1)**: Depends only on Phase 2. Core pipeline — all other stories build on this.
- **US2 (P2)**: Depends on US1 (shader must be running to observe per-pixel gradient).
- **US3 (P3)**: Depends on US1. Cubemap shadow path adds onto the US1 pipeline.
- **US4 (P4)**: Depends on US1. Ambient is a single uniform already in the shader.

### Within Each Phase

- T014 (vertex shader) and T015 (fragment shader) can run in parallel
- T016 (uniform test) must run after T014+T015 to validate the source
- T017 (Gles3LightingShader) must run after T016 (test confirms shader source contract)
- T018 (Gles3ShaderProvider) can run in parallel with T017 (no shared state)
- T019 (ShadowRenderer replacement) depends on T017+T018

### Parallel Opportunities

```bash
# Phase 1 — all deletions run in parallel:
T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T012 simultaneously

# Phase 2 — shaders can run in parallel:
T014 (vert shader) + T015 (frag shader)  → then T016 → T017 + T018 → T019 → T020

# Phase 4 + Phase 5 can run in parallel after Phase 3:
T024 + T026 simultaneously (different files)
T025 (visual US2) + T029 (visual US3) as separate test sessions
```

---

## Parallel Example: Phase 2

```bash
# Write both shaders simultaneously (different files):
Task T014: gles3_lighting.vert.glsl
Task T015: gles3_lighting.frag.glsl

# Then write shader wrapper and provider in parallel:
Task T017: Gles3LightingShader.kt
Task T018: Gles3ShaderProvider.kt
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: Delete old lighting files
2. Complete Phase 2: Shader pipeline (T014–T020)
3. Complete Phase 3: Wire into RoguelikeGame (T021–T022)
4. **STOP and VALIDATE**: `./gradlew run` → torch shadow follows wall geometry
5. Demo: directional shadow map working, zero light bleed

### Incremental Delivery

1. Phase 1 + Phase 2 → Foundation ready, old system gone
2. Phase 3 (US1) → Shadow mesh accuracy ✅ demo-able
3. Phase 4 (US2) → Per-pixel gradient ✅ additive value
4. Phase 5 (US3) → Multi-light shadows ✅ additive value
5. Phase 6 (US4) → Ambient ✅ polish
6. Phase 7 → Verified and performant

---

## Notes

- **GLSL version**: All shader source must use `#version 150` (not `#version 300 es`) — we are on OpenGL 3.2 core profile on desktop. The `ShaderProgram.prependVertexCode` / `prependFragmentCode` set in `Main.kt` already adds `#version 150` — do NOT add a second `#version` directive in the shader files; use `#version 150` as the very first line.
- **Texture units**: unit 0 = diffuse, unit 1 = directional shadow map, units 2–9 = point light cubemaps. Do not conflict.
- **WorldRenderer lambda**: The `renderScene` callback passed to `shadowRenderer.render()` uses the `batch` parameter — do NOT call `modelBatch.begin/end` inside the lambda; the batch is already begun by `ShadowRenderer`.
- **Player rendering**: After WorldRenderer.render(), render the player `ModelInstance` inside the `renderScene` lambda if possible, or call `mainBatch.render(playerInstance, Environment())` separately after `shadowRenderer.render()` — the main batch is disposed after `end()` so the player must be rendered inside the lambda.
- **Compile-broken period**: Between Phase 1 completion and Phase 2 completion the project will not compile. This is expected — proceed sequentially through Phase 2 until `./gradlew compileKotlin` passes.
