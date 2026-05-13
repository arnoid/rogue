# Tasks: Procedural Map Generator and GPU Lighting Engine

**Input**: Design documents from `specs/003-procedural-map-gpu-lighting/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Tests**: Included — Constitution Principle II mandates TDD (Test-First is NON-NEGOTIABLE).
- System B (GPU): unit tests for data classes; GPU pipeline validated visually.
- System A (generation): the code is already implemented; unit tests are written first and expected to immediately go GREEN against the existing implementation. Any RED reveals a bug to fix.

**Organization**: Tasks grouped by user story in spec priority order (P1→P5). System A tests (US4/US5) run in parallel with System B implementation (US1–US3) — different files, no shared dependencies.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- All file paths are project-relative

---

## Phase 1: Setup

**Purpose**: Establish green baseline before any changes

- [ ] T001 Run baseline test suite to confirm green state: `./gradlew test`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `GpuLightEnvironment` data classes (LibGDX-free, headless-testable) — required by all System B user stories.

**⚠️ CRITICAL**: All System B user story work (US1–US3) depends on T005 completing.

### GpuLightEnvironment Data Classes

- [ ] T002 [P] Write failing GpuLightEnvironmentTest: assert `DirectionalLightData` holds normalised direction floats; assert `PointLightData` holds position + color + intensity; assert `GpuLightEnvironment` exposes `directionalLight`, `pointLights` (list), and ambient rgb fields in `src/test/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironmentTest.kt` (RED — classes do not exist yet)
- [ ] T003 [P] Create `DirectionalLightData` data class (directionX, directionY, directionZ, r, g, b, intensity — all Float, no LibGDX imports) in `src/main/kotlin/com/roguelike/core/model/lighting/DirectionalLightData.kt`
- [ ] T004 [P] Create `PointLightData` data class (x, y, z, r, g, b, intensity — all Float, no LibGDX imports) in `src/main/kotlin/com/roguelike/core/model/lighting/PointLightData.kt`
- [ ] T005 Create `GpuLightEnvironment` data class (directionalLight: DirectionalLightData?, pointLights: List\<PointLightData\>, ambientR/G/B: Float, no LibGDX imports) in `src/main/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironment.kt`
- [ ] T006 Confirm T002 (GpuLightEnvironmentTest) goes GREEN: `./gradlew test --tests "com.roguelike.core.model.lighting.GpuLightEnvironmentTest"`

**Checkpoint**: Foundation ready. All GpuLightEnvironment data classes exist and are headless-testable. System B user story work can begin.

---

## Phase 3: User Story 1 — Smooth Per-Pixel Surface Lighting (Priority: P1) 🎯 MVP

**Goal**: Replace per-surface `DynamicLighting.environmentForXxx()` with a single-environment GPU approach so each tile face shows smooth per-pixel brightness gradients driven by real point-light attenuation.

**Independent Test**: `./gradlew run` → place a single torch in a large open room → every floor tile shows a smooth radial gradient; no two adjacent non-equidistant tiles share the same brightness level.

### Tests for User Story 1

> **Write tests FIRST. For GPU pipeline items, headless tests cover data correctness; visual sign-off covers render correctness.**

- [ ] T007 [P] [US1] Write failing ShadowRendererConstructionTest: assert `ShadowRenderer` can be constructed with a null `DirectionalShadowLight` (point-lights-only mode) and that `dispose()` does not throw; use a LibGDX headless backend in `src/test/kotlin/com/roguelike/rendering/ShadowRendererConstructionTest.kt` (RED — ShadowRenderer does not exist yet)

### Implementation for User Story 1

- [ ] T008 [P] [US1] Create `ShadowRenderer` skeleton in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`: fields `modelBatch: ModelBatch`, `environment: Environment`, `gpuLightEnv: GpuLightEnvironment?`; stub `render()` and `dispose()` methods
- [ ] T009 [US1] Implement `ShadowRenderer.buildEnvironment(gpuLightEnv: GpuLightEnvironment): Environment` — converts `PointLightData` list to LibGDX `PointLight` instances and adds ambient `ColorAttribute`; no shadow map wiring yet in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T010 [US1] Implement `GpuLightEnvironment.fromActorInventory(actor: Actor): GpuLightEnvironment` factory function (collect lit items → read `LightDef` → build `PointLightData` list; directionalLight = null for now) in `src/main/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironment.kt`
- [ ] T011 [US1] Implement `ShadowRenderer.mainPass(instances: List<ModelInstance>, camera: Camera)` — calls `buildEnvironment()`, then renders all instances with that single environment via `modelBatch` in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T012 [US1] Wire `ShadowRenderer` into `WorldRenderer.render()`: add optional `shadowRenderer: ShadowRenderer?` parameter; when non-null, collect all `ModelInstance` objects for the visible world nodes and pass them to `shadowRenderer.mainPass()` instead of calling per-surface `dynamicLighting.environmentForXxx()` in `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt`
- [ ] T013 [US1] Confirm T007 (ShadowRendererConstructionTest) goes GREEN: `./gradlew test --tests "com.roguelike.rendering.ShadowRendererConstructionTest"`
- [ ] T014 [US1] Visual acceptance: run `./gradlew run`, place a torch in an open room, confirm smooth per-pixel radial brightness gradient on floor tiles — no uniform-lit tiles, no brightness jumps at tile boundaries (SC-002)

**Checkpoint**: `./gradlew test` passes. A single floor or wall tile face is visibly partially illuminated and partially shadowed within the same frame (SC-002). Run `./gradlew test` before proceeding to US2.

---

## Phase 4: User Story 2 — Directional Light with Geometrically Accurate Shadows (Priority: P2)

**Goal**: Add a `DirectionalShadowLight` depth pass so directional light casts geometrically straight shadow boundaries with no tile-grid staircase artifacts.

**Independent Test**: `./gradlew run` → place a directional light at 45° in a room with one vertical wall → the shadow on the floor is a straight diagonal line verifiable with a ruler; no staircase.

### Tests for User Story 2

- [ ] T015 [P] [US2] Write failing DirectionalLightDataTest: assert that `DirectionalLightData(0f, -1f, 0f, 1f, 1f, 1f, 0.8f)` has the correct field values and that a direction of (0,0,0) is a valid (if degenerate) data class state (no forced normalisation crash) in `src/test/kotlin/com/roguelike/core/model/lighting/DirectionalLightDataTest.kt` (RED — DirectionalLightData may not yet exist at test-write time in this phase; confirm it is GREEN after T003)

### Implementation for User Story 2

- [ ] T016 [US2] Add `shadowLight: DirectionalShadowLight?` field to `ShadowRenderer`; implement `ShadowRenderer.initDirectionalLight(dirData: DirectionalLightData)` that constructs a `DirectionalShadowLight(2048, 2048, 60f, 60f, 1f, 300f)` and sets direction/color from `dirData` in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T017 [US2] Implement `ShadowRenderer.depthPass(instances: List<ModelInstance>)` using a `ModelBatch(DepthShaderProvider())`: call `shadowLight.begin(center, direction)`, render all instances, call `shadowLight.end()` in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T018 [US2] Update `ShadowRenderer.buildEnvironment()` to add `shadowLight` via `environment.add(shadowLight)` and set `environment.shadowMap = shadowLight` when a directional light is configured in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T019 [US2] Update `ShadowRenderer.mainPass()` to call `depthPass()` first when `shadowLight != null` in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T020 [US2] Update `GpuLightEnvironment.fromActorInventory()` to also populate `directionalLight` from any `DIRECTIONAL`-shape `LightDef` items in the actor's inventory in `src/main/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironment.kt`
- [ ] T021 [US2] Confirm T015 (DirectionalLightDataTest) goes GREEN: `./gradlew test --tests "com.roguelike.core.model.lighting.DirectionalLightDataTest"`
- [ ] T022 [US2] Visual acceptance: run `./gradlew run`, configure a directional light at 45°, confirm the shadow of a wall is a straight diagonal line with no staircase artifacts (SC-001)

**Checkpoint**: SC-001 satisfied. Shadow boundary is a geometrically straight line. Run `./gradlew test` before proceeding to US3.

---

## Phase 5: User Story 3 — Point Light Occlusion by Walls (Priority: P3)

**Goal**: Add FboCubemap-based omnidirectional shadow passes so point lights are correctly occluded by solid walls (no light bleed).

**Independent Test**: `./gradlew run` → place a torch in Room A, stand in Room B separated by a solid wall → Room B surface brightness is zero (screenshot comparison, SC-003).

### Tests for User Story 3

- [ ] T023 [P] [US3] Write failing PointLightOcclusionDataTest: assert `GpuLightEnvironment` enforces `pointLights.size <= 8` (coerce or throw) and that a `GpuLightEnvironment` with 9 point lights either truncates to 8 or fails with a clear message in `src/test/kotlin/com/roguelike/core/model/lighting/PointLightOcclusionDataTest.kt` (RED — enforcement not yet implemented)
- [ ] T024 [P] [US3] Add max-8 point-light enforcement to `GpuLightEnvironment`: in the data class or factory, coerce `pointLights` to `pointLights.take(8)` with a logged warning when count exceeds 8 in `src/main/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironment.kt`
- [ ] T025 [US3] Confirm T023 (PointLightOcclusionDataTest) goes GREEN: `./gradlew test --tests "com.roguelike.core.model.lighting.PointLightOcclusionDataTest"`

### Implementation for User Story 3

- [ ] T026 [US3] Create custom GLSL fragment shader for omnidirectional point-light shadow sampling in `src/main/resources/shaders/point_shadow.frag.glsl`: implement `calculatePointShadow(samplerCube shadowMap, vec3 fragToLight, float farPlane)` using cube-map depth sampling with bias 0.05 to prevent shadow acne
- [ ] T027 [US3] Add `FboCubemap` pool to `ShadowRenderer` (field `cubemapPool: Array<FrameBufferCubemap?>` of size 8; lazy-initialized on first render) in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T028 [US3] Implement `ShadowRenderer.cubemapDepthPass(lightIndex: Int, lightData: PointLightData, instances: List<ModelInstance>)`: for each of the 6 cube faces, bind the FBO face, set up a perspective camera at the light position pointing in the face direction, frustum-cull instances, render depth in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T029 [US3] Wire `cubemapDepthPass()` into `ShadowRenderer.mainPass()`: call once per point light before the main render pass in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T030 [US3] Wire custom shader into `ShadowRenderer`: pass `u_pointLightShadowMap[i]` and `u_lightFarPlane` uniforms from the cubemap pool to the shader via a custom `ShaderProvider` in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T031 [US3] Update `ShadowRenderer.dispose()` to free all `FboCubemap` instances in `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
- [ ] T032 [US3] Visual acceptance — zero bleed: run `./gradlew run`, place torch in Room A, confirm Room B receives zero brightness (SC-003)
- [ ] T033 [US3] Visual acceptance — pillar shadow: confirm a visible shadow silhouette of a pillar on a far wall at the geometrically correct position

**Checkpoint**: SC-003 satisfied. No light bleeding through walls. Run `./gradlew test` before proceeding to US4.

---

## Phase 6: User Story 4 — Socket-Based Procedural Dungeon Generation (Priority: P4)

**Goal**: Validate the existing `com.roguelike.generation` package with a complete unit and integration test suite (Constitution Principle II — TDD is non-negotiable).

**Note**: The `generation` package code is already implemented. Tests are written first; they are expected to go immediately GREEN against the existing code. Any RED reveals a latent bug that must be fixed.

**Independent Test**: `./gradlew test --tests "com.roguelike.generation.*"` passes; generation of a 20-room dungeon completes in < 5 s (SC-004).

### Tests for User Story 4 (TDD — Constitution Principle II)

> **Write ALL tests before running. Confirm failures against incomplete implementations if any; confirm GREEN against existing code.**

- [ ] T034 [P] [US4] Write Vector3IntTest: assert `+`, `-`, `*`, `negate()` operations; assert `ZERO`, `NORTH`, `SOUTH`, `EAST`, `WEST`, `UP`, `DOWN` companion constants; assert `allRotations()` returns 4 distinct entries for an asymmetric vector in `src/test/kotlin/com/roguelike/generation/Vector3IntTest.kt`
- [ ] T035 [P] [US4] Write SocketTest: assert new socket starts `OPEN`; assert `state` is mutable; assert connection rule `a.tag == b.tag && a.direction == b.direction.negate()` for a NORTH/SOUTH pair; assert mismatched tags do not satisfy connection rule in `src/test/kotlin/com/roguelike/generation/SocketTest.kt`
- [ ] T036 [P] [US4] Write SubmapTemplateTest: assert a 9×9 face (3×3 Base Units) template exposes exactly 9 sockets on that face (Multi-Socket Rule, FR-009); assert `allRotations()` returns ≤ 4 variants; assert footprint dimensions match expected Base Unit coordinates in `src/test/kotlin/com/roguelike/generation/SubmapTemplateTest.kt`
- [ ] T037 [P] [US4] Write PlacedSubmapTest: assert `occupiedCells()` returns exactly `footprint.x * footprint.y * footprint.z` cells for a non-degenerate template; assert all cell coordinates fall within `[origin, origin + footprint)` in `src/test/kotlin/com/roguelike/generation/PlacedSubmapTest.kt`
- [ ] T038 [US4] Write MapGeneratorTest: (a) collision check — assert placing a second template at the same origin is rejected; (b) socket matching — assert two templates with compatible tags and opposing directions produce a CONNECTED socket pair; (c) sealing — assert an OPEN socket with no compatible candidates transitions to SEALED after `generate()` completes in `src/test/kotlin/com/roguelike/generation/MapGeneratorTest.kt`
- [ ] T039 [US4] Run T034–T038 and confirm all GREEN against existing code: `./gradlew test --tests "com.roguelike.generation.Vector3IntTest" --tests "com.roguelike.generation.SocketTest" --tests "com.roguelike.generation.SubmapTemplateTest" --tests "com.roguelike.generation.PlacedSubmapTest" --tests "com.roguelike.generation.MapGeneratorTest"`. Fix any failing tests by correcting the production code (not the tests).
- [ ] T040 [US4] Write MapGeneratorIntegrationTest: use a minimal two-template library (one 3×3 corridor, one 9×9 room with matching socket tags); run `generate(targetRoomCount = 20)`; assert collision-free (no cell double-occupied); assert socket compatibility invariant (every CONNECTED pair has matching tags + opposing directions); assert every unmatched socket is SEALED; assert completion time < 5 s in `src/test/kotlin/com/roguelike/generation/MapGeneratorIntegrationTest.kt`
- [ ] T041 [US4] Run T040 and confirm GREEN: `./gradlew test --tests "com.roguelike.generation.MapGeneratorIntegrationTest"`. Fix any failing assertions in production code.

**Checkpoint**: All `com.roguelike.generation.*` tests pass. SC-004 (< 5 s for 20 rooms) and SC-005 (socket compatibility invariant) verified. Run `./gradlew test` before proceeding.

---

## Phase 7: User Story 5 — Step-Through Generation Debugger (Priority: P5)

**Goal**: Validate the existing `GenerationDebugUI` debug confirmation loop.

**Independent Test**: `./gradlew run` in debug mode → generation pauses before each placement; "I do agree!" commits, "I do not agree!" skips.

### Tests for User Story 5 (TDD — Constitution Principle II)

- [ ] T042 [US5] Write GenerationDebugUITest: assert the confirm button label is exactly `"I do agree!"`; assert the reject button label is exactly `"I do not agree!"`; assert the confirm button color matches soft pink (R≈1.0, G≈0.6, B≈0.7); assert the reject button color matches neutral gray (R≈0.5, G≈0.5, B≈0.5) — instantiate `GenerationDebugUI` with a LibGDX headless backend in `src/test/kotlin/com/roguelike/generation/GenerationDebugUITest.kt`
- [ ] T043 [US5] Run T042 and confirm GREEN: `./gradlew test --tests "com.roguelike.generation.GenerationDebugUITest"`. Fix any label or color mismatches in `GenerationDebugUI`.
- [ ] T044 [US5] Visual acceptance: run `./gradlew run` with `MapGenerator(debugMode = true)`; confirm generation pauses before each valid placement; confirm "I do agree!" commits the room and advances generation; confirm "I do not agree!" skips to next candidate without modifying the map; confirm auto-seal when no candidates remain (SC-006)

**Checkpoint**: SC-006 satisfied. Step-through debugger correctly pauses, commits, and skips.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Full regression guard, deprecation of CPU path, performance validation, and visual sign-offs.

- [ ] T045 Run full regression test suite: `./gradlew test` — all tests must pass, including all existing `SurfaceLightingModelOcclusionTest`, `LightingSystemTest`, `BvhOccluderCullingTest`, and all newly added generation + lighting tests (SC-008)
- [ ] T046 Deprecate per-surface `DynamicLighting` calls in `WorldRenderer`: when `shadowRenderer` is non-null, the `dynamicLighting` parameter must be ignored; add `@Deprecated` annotation to the `dynamicLighting` parameter or guard with a check in `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt`
- [ ] T047 Performance check: run `./gradlew run` with 8 simultaneous point lights in a scene; monitor FPS overlay and confirm ≥ 30 FPS on reference development machine (SC-007)
- [ ] T048 Visual sign-off US1: open a saved world, confirm smooth per-pixel radial gradient on floor tiles with no tile-boundary brightness jumps (SC-002)
- [ ] T049 Visual sign-off US2: confirm straight diagonal shadow edges from directional light at any angle (SC-001)
- [ ] T050 Visual sign-off US3: confirm zero brightness on Room B surfaces when torch is in Room A behind a wall (SC-003, screenshot comparison)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies
- **Phase 2 (Foundational)**: Depends on Phase 1 — **BLOCKS US1–US3**
  - T002–T006 can all begin after T001
  - T003 and T004 can run in parallel (different files)
  - T005 depends on T003 and T004
- **Phase 3 (US1)**: Depends on Phase 2 (T005 must be GREEN)
- **Phase 4 (US2)**: Depends on Phase 3 (ShadowRenderer skeleton must exist)
- **Phase 5 (US3)**: Depends on Phase 4 (depth pass infrastructure must work)
- **Phases 6–7 (US4–US5)**: Depend only on Phase 1; can run in parallel with Phases 3–5
- **Phase 8 (Polish)**: Depends on all user story phases completing

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational (T005 GREEN)
- **US2 (P2)**: Depends on US1 (ShadowRenderer must exist)
- **US3 (P3)**: Depends on US2 (depth pass infrastructure needed)
- **US4 (P4)**: Depends only on Phase 1 — **INDEPENDENT of System B**
- **US5 (P5)**: Depends only on Phase 1 — **INDEPENDENT of System B**

### Within Phase 3 (US1)

- T007 (test) can be written before T008
- T008 and T010 are independent (different files) — parallel
- T009 depends on T008 (ShadowRenderer must exist)
- T011 depends on T009
- T012 depends on T011
- T013 confirms T007 GREEN
- T014 requires a running display

### Parallel Opportunities

- **Phase 2**: T002, T003, T004 can run in parallel
- **Phase 3**: T007 and T008/T010 can run in parallel (test vs. implementation)
- **Phases 3–5 (System B) vs. Phases 6–7 (System A tests)**: Fully independent — run in parallel if capacity allows
- **Phase 6 (US4)**: T034, T035, T036, T037 can all run in parallel (different test files)

---

## Parallel Example: System B (US1) + System A Tests (US4)

```bash
# Stream 1: System B — GPU Lighting (US1)
Task: "Write ShadowRendererConstructionTest.kt" (T007)
Task: "Create ShadowRenderer skeleton" (T008)
  → "Implement buildEnvironment()" (T009)
  → "Implement mainPass()" (T011)
  → "Wire WorldRenderer" (T012)

# Stream 2: System A — Generation tests (US4) — runs in parallel with Stream 1
Task: "Write Vector3IntTest.kt" (T034)
Task: "Write SocketTest.kt" (T035)
Task: "Write SubmapTemplateTest.kt" (T036)
Task: "Write PlacedSubmapTest.kt" (T037)
  → "Write MapGeneratorTest.kt" (T038)
  → "Confirm all GREEN" (T039)
  → "Write MapGeneratorIntegrationTest.kt" (T040)
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: baseline green
2. Complete Phase 2: GpuLightEnvironment data classes
3. Complete Phase 3: US1 — single-environment GPU point-light rendering (no shadows yet)
4. **STOP and VALIDATE**: Smooth per-pixel lighting visible, SC-002 confirmed
5. Demo if ready

### Incremental Delivery

1. Phase 1 + Phase 2 → foundation ready
2. Phase 3 (US1) → smooth tile gradients → **Demo (MVP!)**
3. Phase 4 (US2) → straight directional shadow edges → **Demo**
4. Phase 5 (US3) → point-light occlusion, no wall bleed → **Demo**
5. Phases 6–7 (US4/US5) → constitution compliance → test suite green
6. Phase 8 (Polish) → full regression, performance, visual sign-off → **Ship**

---

## Notes

- TDD is mandatory per Constitution Principle II. For System B GPU tasks, headless data-class tests cover correctness; visual acceptance covers render correctness.
- For System A (US4/US5), tests are written against existing code and expected to go GREEN immediately. Any RED = latent bug in production code; fix the code, not the test.
- `GpuLightEnvironment` and all `*LightData` classes MUST have zero LibGDX imports (Constitution Principle I). Verify with `grep -r "com.badlogic" src/main/kotlin/com/roguelike/core/`.
- `ShadowRenderer` is in `rendering` — LibGDX imports are expected and correct there.
- Pre-existing `DynamicLighting` Principle I violation is documented in plan.md Complexity Tracking; do not migrate it in this feature.
- FR-006: `items.json` requires no changes — `GpuLightEnvironment.fromActorInventory()` reads existing `LightDef` fields.
- FboCubemap depth passes require GLES 3.0+ — guaranteed on confirmed Desktop + GLES 3.0+ target.
