# Tasks: Stencil Shadow Volume Lighting

**Input**: Design documents from `/specs/005-raytraced-shading/`
**Prerequisites**: spec.md (user stories), research.md (decisions), data-model.md (entities), quickstart.md (test scenarios)

**Tests**: Unit tests for `ShadowVolumeBuilder` are included per quickstart.md testing strategy. Visual/performance tests are manual.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Source**: `src/main/kotlin/com/roguelike/`
- **Shaders**: `src/main/resources/shaders/`
- **Tests**: `src/test/kotlin/com/roguelike/`
- **Rendering package**: `com.roguelike.rendering`
- **Core model package**: `com.roguelike.core.model` (LibGDX-free, keep existing)

---

## Phase 1: Setup (Delete Old Lighting System)

**Purpose**: Remove the old CPU-based lighting pipeline and prepare for the GPU shadow volume replacement

- [X] T001 Delete old lighting implementation `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T002 [P] Delete old lighting support file `src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt`
- [X] T003 [P] Delete old lighting support file `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt`
- [X] T004 [P] Delete old lighting diagnostics `src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt`
- [X] T005 [P] Delete old lighting tests `src/test/kotlin/com/roguelike/core/DynamicLightingTest.kt`
- [X] T006 [P] Delete old lighting tests `src/test/kotlin/com/roguelike/core/LightingSystemTest.kt`
- [X] T007 [P] Delete old lighting tests `src/test/kotlin/com/roguelike/core/SurfaceLightingTest.kt`
- [X] T008 Fix compilation errors in `src/main/kotlin/com/roguelike/RoguelikeGame.kt` — remove all references to `DynamicLighting`, `LightingSystem`, `SurfaceLighting`, `LightingDiagnostics`
- [X] T009 Fix compilation errors in `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt` — remove `dynamicLighting` parameter and all references to deleted classes
- [X] T010 Verify build passes with `./gradlew test` after deletions

---

## Phase 2: Foundational (Shader Files + Data Types + Shader Provider)

**Purpose**: Create all shader files, data types, and the shader provider that every user story depends on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T011 [P] Create shadow volume vertex shader `src/main/resources/shaders/shadow_volume.vert.glsl` — inputs: `a_position` (vec3); uniforms: `u_projViewTrans` (mat4), `u_worldTrans` (mat4); output: `gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0)`. No `#version` directive (prepended by Main.kt)
- [X] T012 [P] Create shadow volume fragment shader `src/main/resources/shaders/shadow_volume.frag.glsl` — no-op shader: declare `out vec4 fragColor;` and set `fragColor = vec4(0.0)`. Colour writes will be disabled at GL level. No `#version` directive
- [X] T013 [P] Create ambient pass vertex shader `src/main/resources/shaders/ambient_pass.vert.glsl` — inputs: `a_position` (vec3), `a_normal` (vec3), `a_texCoord0` (vec2); uniforms: `u_projViewTrans` (mat4), `u_worldTrans` (mat4); outputs: `v_texCoord` (vec2); transforms position and passes through texcoord. No `#version` directive
- [X] T014 [P] Create ambient pass fragment shader `src/main/resources/shaders/ambient_pass.frag.glsl` — uniforms: `u_ambientColor` (vec3), `u_diffuseTexture` (sampler2D), `u_diffuseColor` (vec4); output: `fragColor = texture(u_diffuseTexture, v_texCoord) * u_diffuseColor * vec4(u_ambientColor, 1.0)`. No `#version` directive
- [X] T015 [P] Create lit pass vertex shader `src/main/resources/shaders/lit_pass.vert.glsl` — inputs: `a_position` (vec3), `a_normal` (vec3), `a_texCoord0` (vec2); uniforms: `u_projViewTrans` (mat4), `u_worldTrans` (mat4); outputs: `v_worldPos` (vec3), `v_worldNormal` (vec3), `v_texCoord` (vec2). No `#version` directive
- [X] T016 [P] Create lit pass fragment shader `src/main/resources/shaders/lit_pass.frag.glsl` — inputs: `v_worldPos`, `v_worldNormal`, `v_texCoord`; uniforms: `u_LightPos` (vec3), `u_LightColor` (vec3), `u_LightIntensity` (float), `u_LightRadius` (float), `u_diffuseTexture` (sampler2D), `u_diffuseColor` (vec4); compute Lambertian `NdotL = max(dot(normalize(v_worldNormal), lightDir), 0.0)`, inverse-square attenuation `intensity / (dist² + 1.0)`, radius cutoff, output `fragColor = texColor * diffuseColor * attenuation * NdotL * lightColor`. No `#version` directive
- [X] T017 [P] Create `PointLightData` data class in `src/main/kotlin/com/roguelike/rendering/PointLightData.kt` — fields: `position: Vector3`, `color: Color`, `intensity: Float`, `radius: Float`. Constructed from `LightDef` + world position
- [X] T018 [P] Create `SilhouetteEdge` data class in `src/main/kotlin/com/roguelike/rendering/SilhouetteEdge.kt` — fields: `v0: Vector3`, `v1: Vector3`. Validate `v0 ≠ v1`
- [X] T019 [P] Create `ShadowVolumeMesh` data class in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeMesh.kt` — fields: `vertices: FloatArray`, `indices: ShortArray`, `vertexCount: Int`, `indexCount: Int`
- [X] T020 [P] Create `SilhouetteCache` class in `src/main/kotlin/com/roguelike/rendering/SilhouetteCache.kt` — fields: `meshId: Int`, `lightId: Int`, `lightPos: Vector3`, `edges: List<SilhouetteEdge>`, `shadowVolume: ShadowVolumeMesh`, `valid: Boolean`. Invalidation when light/mesh moves
- [X] T021 Create `ShadowVolumeShaderProvider` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeShaderProvider.kt` — loads and compiles shadow_volume, ambient_pass, and lit_pass shader pairs from resources/shaders/. Provides methods: `getShadowVolumeShader()`, `getAmbientShader()`, `getLitPassShader()`. Uses `ShaderProgram` with `Gdx.files.internal()`
- [X] T022 Verify build passes with `./gradlew test` after foundational phase

**Checkpoint**: Foundation ready — all shaders compiled, data types available, shader provider functional

---

## Phase 3: User Story 1 — Mesh-Accurate Shadow Casting (Priority: P1) 🎯 MVP

**Goal**: Shadows follow exact silhouette of 3D objects — walls cast hard-edged shadows, no light bleed through geometry

**Independent Test**: Place a single point light next to a wall segment in a generated map. Verify the shadow on the floor precisely follows the wall geometry with no bleed-through.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T023 [P] [US1] Create `ShadowVolumeBuilderTest` in `src/test/kotlin/com/roguelike/rendering/ShadowVolumeBuilderTest.kt` — test silhouette detection on a unit cube mesh with a known light position: verify correct silhouette edges are identified (edges between front-facing and back-facing triangles)
- [X] T024 [P] [US1] Add extrusion test to `ShadowVolumeBuilderTest` — given silhouette edges and a light position, verify extruded quads are generated with vertices extended 1000 units away from light (per R1 finite extrusion decision)
- [X] T025 [P] [US1] Add cap generation test to `ShadowVolumeBuilderTest` — verify front cap contains original front-facing triangles and back cap contains extruded back-facing triangles, forming a closed volume

### Implementation for User Story 1

- [X] T026 [US1] Implement `ShadowVolumeBuilder` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeBuilder.kt` — methods: `classifyFaces(mesh, lightPos)` returns front/back facing lists; `findSilhouetteEdges(mesh, lightPos)` returns `List<SilhouetteEdge>`; `extrudeSilhouette(edges, lightPos, extrudeDistance=1000f)` returns extruded quads; `buildShadowVolume(mesh, lightPos)` returns `ShadowVolumeMesh` with front cap + extruded sides + back cap. Use adjacency map for edge-to-face lookup
- [X] T027 [US1] Implement `ShadowVolumeRenderer` core in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` — constructor takes `ShadowVolumeShaderProvider`; implement `render(camera, lights: List<PointLightData>, occluders, sceneRenderCallback)` with: (1) ambient pass — bind ambient shader, render full scene via callback, write color+depth; (2) per-light stencil pass — clear stencil, disable color/depth write, enable depth read, front-face cull-back incr-on-depth-fail, back-face cull-front decr-on-depth-fail using `Gdx.gl.glStencilFunc/glStencilOp`; (3) lit pass — stencil==0, additive blend `GL_ONE,GL_ONE`, bind lit shader with light uniforms, render scene. Uses LibGDX `Mesh` with `VertexAttribute.Position()` for shadow volume geometry, uploaded per-frame via `setVertices()`
- [X] T028 [US1] Update `WorldRenderer.kt` in `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt` — add `shadowVolumeRenderer: ShadowVolumeRenderer` parameter; expose a `renderSceneGeometry(shader)` method that iterates all tiles/props/items and renders them with the given shader; remove old lighting code path
- [X] T029 [US1] Update `RoguelikeGame.kt` in `src/main/kotlin/com/roguelike/RoguelikeGame.kt` — instantiate `ShadowVolumeShaderProvider`, `ShadowVolumeBuilder`, `ShadowVolumeRenderer` in `create()`; in render loop: collect active `PointLightData` from game world torch entities, collect occluder meshes, call `shadowVolumeRenderer.render(camera, lights, occluders, worldRenderer::renderSceneGeometry)`
- [X] T030 [US1] Verify `./gradlew test` passes and visual test: single point light next to wall casts geometrically accurate shadow

**Checkpoint**: User Story 1 complete — single light casts pixel-perfect hard shadows with no light bleed. MVP delivered.

---

## Phase 4: User Story 2 — Self-Shadowing (Priority: P2)

**Goal**: Objects cast shadows upon themselves — faces turned away from light are dark

**Independent Test**: Place a single point light near a 90° wall corner. Verify the wall facing away from the light is dark while the facing wall is bright, with transition at the geometric edge.

**Dependencies**: Requires US1 pipeline (Phase 3)

### Implementation for User Story 2

- [X] T031 [US2] Ensure `ShadowVolumeBuilder.classifyFaces()` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeBuilder.kt` correctly classifies all faces including those of the same mesh — verify the front cap includes self-facing triangles so the stencil test naturally shadows back-facing surfaces of the occluder itself
- [X] T032 [US2] Add self-shadowing unit test to `src/test/kotlin/com/roguelike/rendering/ShadowVolumeBuilderTest.kt` — test an L-shaped mesh where one face is front-lit and the perpendicular face should be in shadow: verify the shadow volume geometry encloses the back-facing region
- [X] T033 [US2] Visual verification: L-shaped wall corner with one point light — confirm bright face vs dark face with sharp transition at the geometric edge

**Checkpoint**: Self-shadowing works correctly on all mesh geometry

---

## Phase 5: User Story 3 — Multiple Dynamic Point Lights (Priority: P3)

**Goal**: Multiple torches simultaneously illuminate surfaces with additive blending, each casting independent shadows

**Independent Test**: Place two torches at different positions in a corridor. Verify both cast visible light and independent shadows, with overlapping regions receiving additive illumination.

**Dependencies**: Requires US1 pipeline (Phase 3)

### Implementation for User Story 3

- [X] T034 [US3] Verify `ShadowVolumeRenderer.render()` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` iterates over `List<PointLightData>` correctly — each light gets its own stencil clear + stencil mark + lit pass cycle. Additive blend (`GL_ONE, GL_ONE`) accumulates per-light contributions
- [X] T035 [US3] Update `RoguelikeGame.kt` light collection in `src/main/kotlin/com/roguelike/RoguelikeGame.kt` — gather all active torch/light-bearing entities into `List<PointLightData>` (up to 4 lights per TR-5)
- [X] T036 [US3] Visual verification: two+ point lights in corridor — independent shadows, additive overlap brightness

**Checkpoint**: Multiple lights render correctly with independent shadow volumes and additive blending

---

## Phase 6: User Story 4 — Ambient Baseline (Priority: P4)

**Goal**: Fully shadowed surfaces are dim but not pitch black — minimum ambient term ensures visibility

**Independent Test**: Remove all light sources — room is dark but faintly visible (grey/dim), not absolute black.

**Dependencies**: Requires US1 pipeline (Phase 3)

### Implementation for User Story 4

- [X] T037 [US4] Configure ambient color uniform in `ShadowVolumeRenderer.render()` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` — set `u_ambientColor` to a configurable value defaulting to `vec3(0.08, 0.08, 0.10)` (slight blue tint for dungeon atmosphere). Pass as constructor parameter or game config
- [X] T038 [US4] Visual verification: no active lights — all surfaces faintly visible with ambient, not black; with lights active — ambient baseline visible in fully shadowed areas

**Checkpoint**: Ambient baseline prevents pitch-black areas while preserving shadow contrast

---

## Phase 7: User Story 5 — Performance (Priority: P5)

**Goal**: ≥ 30 FPS with up to 4 simultaneous point lights casting shadow volumes

**Independent Test**: Equip 4 torches, observe FPS counter. Confirm ≥ 30 FPS.

**Dependencies**: Requires US1 + US3 (Phases 3, 5)

### Implementation for User Story 5

- [X] T039 [P] [US5] Implement scissor test optimization in `ShadowVolumeRenderer.render()` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` — for each light, compute screen-space bounding rectangle from light position + radius, call `Gdx.gl.glScissor()` to limit rasterization to affected region; disable scissor after lit pass
- [X] T040 [P] [US5] Implement distance culling in `ShadowVolumeRenderer.render()` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` — skip light entirely if `distance(cameraPos, lightPos) - lightRadius > maxCullDist` (configurable, default 50 units)
- [X] T041 [US5] Implement silhouette caching in `ShadowVolumeBuilder` in `src/main/kotlin/com/roguelike/rendering/ShadowVolumeBuilder.kt` — use `HashMap<Pair<Int, Int>, SilhouetteCache>` keyed by `(meshId, lightId)`. On `buildShadowVolume()`, check cache: if `valid` and `lightPos` unchanged, return cached `ShadowVolumeMesh`. Invalidate on light/mesh movement per R4 decision
- [X] T042 [US5] Performance verification: 4 active point lights in dungeon room — confirm ≥ 30 FPS on standard desktop GPU

**Checkpoint**: Performance target met with 4 simultaneous lights

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Cleanup, documentation, and final validation

- [X] T043 [P] Dispose shader programs and mesh resources in `ShadowVolumeShaderProvider.dispose()` and `ShadowVolumeRenderer.dispose()` — call on game shutdown in `RoguelikeGame.kt`
- [X] T044 [P] Verify stencil buffer is 8-bit in LWJGL3 window config — check `Lwjgl3ApplicationConfiguration` in `src/main/kotlin/com/roguelike/Main.kt` or launcher, confirm stencil bits ≥ 8
- [X] T045 [P] Verify `core/model/` package remains LibGDX-free — `LightDef`, `LightShape`, `LightDirection` have no `com.badlogic.gdx` imports
- [X] T046 Run full test suite `./gradlew test` — all tests pass, no references to deleted lighting classes remain
- [X] T047 Run quickstart.md validation — build, test, run game per quickstart.md instructions

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — delete old files and fix compilation
- **Foundational (Phase 2)**: Depends on Phase 1 — creates shaders, data types, shader provider
- **US1 (Phase 3)**: Depends on Phase 2 — core shadow volume pipeline
- **US2 (Phase 4)**: Depends on Phase 3 — self-shadowing is a property of the US1 pipeline
- **US3 (Phase 5)**: Depends on Phase 3 — multi-light iteration
- **US4 (Phase 6)**: Depends on Phase 3 — ambient pass configuration
- **US5 (Phase 7)**: Depends on Phase 3 + Phase 5 — performance optimization
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **US1 (P1)**: After Foundational — independent, MVP
- **US2 (P2)**: After US1 — tests self-shadowing property of US1's pipeline
- **US3 (P3)**: After US1 — extends single-light to multi-light loop
- **US4 (P4)**: After US1 — configures ambient uniform already used in ambient pass
- **US5 (P5)**: After US1 + US3 — optimizations require multi-light to measure

### Parallel Opportunities

- T001–T007: All old file deletions can run in parallel
- T011–T020: All shader files and data types can be created in parallel
- T023–T025: All ShadowVolumeBuilder tests can be written in parallel
- T039–T040: Scissor and distance culling are independent optimizations
- T043–T045: All polish tasks can run in parallel
- US3, US4 can be started in parallel after US1 completes (US5 needs US3)

---

## Parallel Example: Foundational Phase

```
# All shader files in parallel (T011–T016):
Task: "Create shadow_volume.vert.glsl"
Task: "Create shadow_volume.frag.glsl"
Task: "Create ambient_pass.vert.glsl"
Task: "Create ambient_pass.frag.glsl"
Task: "Create lit_pass.vert.glsl"
Task: "Create lit_pass.frag.glsl"

# All data types in parallel (T017–T020):
Task: "Create PointLightData.kt"
Task: "Create SilhouetteEdge.kt"
Task: "Create ShadowVolumeMesh.kt"
Task: "Create SilhouetteCache.kt"
```

## Parallel Example: User Story 1 Tests

```
# All builder tests in parallel (T023–T025):
Task: "Silhouette detection test"
Task: "Extrusion test"
Task: "Cap generation test"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Delete old lighting system
2. Complete Phase 2: Create shaders, data types, shader provider
3. Complete Phase 3: User Story 1 — single light with mesh-accurate shadows
4. **STOP and VALIDATE**: Single point light casts geometrically accurate shadow, no light bleed
5. MVP is playable with one torch

### Incremental Delivery

1. Setup + Foundational → Old code removed, new foundation ready
2. Add US1 → Single light shadow volumes → **MVP!**
3. Add US2 → Self-shadowing verified → Better visual quality
4. Add US3 → Multiple torches work → Full lighting gameplay
5. Add US4 → Ambient baseline → Playable in dark areas
6. Add US5 → Performance optimized → Smooth with 4 lights
7. Each story adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- All shader files must NOT contain `#version` directives (prepended by Main.kt)
- All rendering code in `com.roguelike.rendering` — `core` package stays LibGDX-free
- Extrusion distance: 1000 units (per R1 research decision)
- Stencil method: depth-fail / Carmack's Reverse (per R6 decision)
- Shadow volume mesh: position-only `LibGDX Mesh`, per-frame upload (per R3 decision)
- Commit after each task or logical group

