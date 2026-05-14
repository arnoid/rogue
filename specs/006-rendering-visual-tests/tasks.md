# Tasks: Visual Rendering Test Suite for Shadow Volume Pipeline

**Input**: Design documents from `/specs/006-rendering-visual-tests/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Test source**: `src/test/kotlin/com/roguelike/rendering/`
- **PNG output**: `build/test-output/rendering/`

---

## Phase 1: Setup

**Purpose**: Project initialization and output directory structure

- [X] T001 Create test output directory structure at `build/test-output/rendering/` and add to `.gitignore` if not already present
- [X] T002 Verify Gradle test dependencies include JUnit Jupiter 5.10.1, libGDX 1.12.1 test backend (gdx-backend-lwjgl3), and stencil buffer support in `build.gradle.kts`

---

## Phase 2: Foundational (Test Infrastructure)

**Purpose**: Core test infrastructure that ALL user stories depend on. MUST complete before any test class.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T003 Implement `RegionStats` data class in `src/test/kotlin/com/roguelike/rendering/PixelSampler.kt` with fields: avgR, avgG, avgB, avgBrightness, minBrightness, maxBrightness
- [X] T004 Implement `PixelSampler` class in `src/test/kotlin/com/roguelike/rendering/PixelSampler.kt` with methods: sampleRegion(x,y,w,h), assertLit(), assertShadowed(), assertBrighterThan(), assertColor() using configurable tolerance (default 15/255)
- [X] T005 Implement `TestScene` data class in `src/test/kotlin/com/roguelike/rendering/SceneBuilder.kt` holding modelInstances, lights, occluderTriangles, camera
- [X] T006 Implement `SceneBuilder` DSL in `src/test/kotlin/com/roguelike/rendering/SceneBuilder.kt` with methods: addBox(), addSphere(), addPlane(), addWall() (model + occluder triangles), addLight(), camera(), build() → TestScene
- [X] T007 Implement `RenderTestHarness` in `src/test/kotlin/com/roguelike/rendering/RenderTestHarness.kt` with: initialize() creating hidden Lwjgl3Application + FBO (512×512, RGBA8+depth16+stencil8 via FrameBufferBuilder), renderScene(TestScene) → Pixmap executing full shadow volume pipeline on GL thread via postRunnable+CountDownLatch, saveImage(pixmap, testName) writing PNG to build/test-output/rendering/, dispose() cleanup

**Checkpoint**: Infrastructure ready — test classes can now be implemented in parallel

---

## Phase 3: User Story 1 — Basic Shadow/Light Verification Tests (Priority: P1) 🎯 MVP

**Goal**: 5 foundational tests verifying basic shadow casting and illumination produce correct PNGs with pixel-sampling assertions

**Independent Test**: Run `BasicShadowLightTest` — all 5 tests pass, PNGs in `build/test-output/rendering/` show correct shadow/light behavior

- [X] T008 [US1] Create test class `src/test/kotlin/com/roguelike/rendering/BasicShadowLightTest.kt` with @BeforeAll initializing RenderTestHarness and @AfterAll disposing it
- [X] T009 [US1] Implement test `spherePartialShadowOnCube` — scene: sphere offset on Y between light and cube; assert partial shadow on cube via pixel sampling (darker behind sphere, lit outside)
- [X] T010 [US1] Implement test `wallBlocksAllLight` — scene: wall between light and cube, light behind wall; assert cube receives only ambient light
- [X] T011 [US1] Implement test `wallLitCubeShadowed` — scene: light in front of wall, cube behind wall; assert wall illuminated, cube ambient-only
- [X] T012 [US1] Implement test `noOccludersFullyLit` — scene: light + receivers, no occluders; assert uniform brightness across all receivers
- [X] T013 [US1] Implement test `zeroIntensityLightAmbientOnly` — scene: light at zero intensity; assert all surfaces uniformly dim (ambient only)

**Checkpoint**: User Story 1 complete — 5 basic shadow/light tests passing independently

---

## Phase 4: User Story 2 — Shadow Volume Geometry Accuracy Tests (Priority: P1)

**Goal**: 4 tests verifying shadow geometry produces correct shadow shapes for various occluder configurations

**Independent Test**: Run `ShadowVolumeGeometryTest` — all 4 tests pass with correct shadow boundaries in PNGs

- [X] T014 [P] [US2] Create test class `src/test/kotlin/com/roguelike/rendering/ShadowVolumeGeometryTest.kt` with @BeforeAll/@AfterAll harness lifecycle
- [X] T015 [US2] Implement test `flatWallSharpShadowBoundary` — scene: single flat wall between light and floor; assert sharp lit-to-shadowed transition via pixel sampling across boundary
- [X] T016 [US2] Implement test `corridorWallsShadows` — scene: multiple walls forming open corridor with light at one end; assert shadows along corridor match wall positions
- [X] T017 [US2] Implement test `lShapedWallShadowWrap` — scene: L-shaped wall + light; assert shadow wraps around corner
- [X] T018 [US2] Implement test `cubeOccluderMultiAngle` — scene: cube occluder with light at front/side/above angles; assert shadow projection matches each light position

**Checkpoint**: User Stories 1 & 2 complete — 9 geometry and basic tests passing

---

## Phase 5: User Story 3 — Light Position and Distance Behavior Tests (Priority: P2)

**Goal**: 4 tests verifying light intensity and shadow behavior change correctly with position and distance

**Independent Test**: Run `LightPositionDistanceTest` — all 4 tests pass, pixel brightness values match expected attenuation

- [X] T019 [P] [US3] Create test class `src/test/kotlin/com/roguelike/rendering/LightPositionDistanceTest.kt` with @BeforeAll/@AfterAll harness lifecycle
- [X] T020 [US3] Implement test `closeLightBrightSpotSteepFalloff` — scene: light very close to surface; assert bright center, significantly dimmer edges
- [X] T021 [US3] Implement test `farLightEvenDimIllumination` — scene: light far from surface; assert uniform low brightness
- [X] T022 [US3] Implement test `lightInsideClosedRoom` — scene: light inside 6-wall room; assert all interior walls illuminated
- [X] T023 [US3] Implement test `lightAtWallSurface` — scene: light at wall surface; assert no artifacts, lit side illuminated, opposite side dark

**Checkpoint**: User Stories 1–3 complete — 13 tests passing

---

## Phase 6: User Story 4 — Multi-Light Interaction Tests (Priority: P2)

**Goal**: 3 tests verifying correct multi-light additive blending and shadow overlap

**Independent Test**: Run `MultiLightInteractionTest` — all 3 tests pass, overlap regions show correct additive/color mixing

- [X] T024 [P] [US4] Create test class `src/test/kotlin/com/roguelike/rendering/MultiLightInteractionTest.kt` with @BeforeAll/@AfterAll harness lifecycle
- [X] T025 [US4] Implement test `twoLightsOppositeSidesOccluder` — scene: two lights on opposite sides of occluder; assert each side lit, overlap region intermediate brightness
- [X] T026 [US4] Implement test `coloredLightsBlending` — scene: red + blue lights; assert overlap region shows purple/magenta via color channel assertions
- [X] T027 [US4] Implement test `overlappingRadiiAdditiveBrightness` — scene: two lights with overlapping radii, no occluders; assert overlap brighter than single-light regions

**Checkpoint**: User Stories 1–4 complete — 16 tests passing

---

## Phase 7: User Story 5 — Edge Case and Robustness Tests (Priority: P2)

**Goal**: 4 tests verifying graceful handling of degenerate and boundary conditions

**Independent Test**: Run `EdgeCaseRobustnessTest` — all 4 tests pass without crashes or visual corruption

- [X] T028 [P] [US5] Create test class `src/test/kotlin/com/roguelike/rendering/EdgeCaseRobustnessTest.kt` with @BeforeAll/@AfterAll harness lifecycle
- [X] T029 [US5] Implement test `cameraInsideShadowVolume` — scene: camera inside shadow volume; assert no stencil corruption, objects outside volume properly lit
- [X] T030 [US5] Implement test `objectAtShadowBoundary` — scene: object exactly at shadow boundary; assert partial illumination, no z-fighting
- [X] T031 [US5] Implement test `thinOccluderCastsShadow` — scene: near-zero thickness occluder; assert shadow still cast correctly
- [X] T032 [US5] Implement test `occluderBehindLightNoForwardShadow` — scene: occluder behind light; assert full illumination on receiving surface

**Checkpoint**: User Stories 1–5 complete — 20 tests passing

---

## Phase 8: User Story 6 — Regression Tests for Known Artifacts (Priority: P3)

**Goal**: 4 tests reproducing known shadow volume failure modes to prevent regressions

**Independent Test**: Run `RegressionArtifactTest` — all 4 tests pass, confirming no shadow acne, light bleeding, cap artifacts, or stencil overflow

- [X] T033 [P] [US6] Create test class `src/test/kotlin/com/roguelike/rendering/RegressionArtifactTest.kt` with @BeforeAll/@AfterAll harness lifecycle
- [X] T034 [US6] Implement test `noShadowAcne` — scene: receiver coplanar with shadow boundary; assert no alternating dark/light pixel banding, smooth brightness transition
- [X] T035 [US6] Implement test `noLightBleedThroughWall` — scene: thin wall, light on one side, receiver on other; assert receiver shows only ambient light
- [X] T036 [US6] Implement test `noCapsVisibleAsArtifacts` — scene: shadow volume caps visible to camera; assert no cap geometry artifacts in output
- [X] T037 [US6] Implement test `stencilBufferNoOverflow` — scene: 10+ occluders near single light; assert no stencil overflow, correct shadow/light boundaries

**Checkpoint**: All 24 test scenarios implemented and passing

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup across all stories

- [X] T038 Run full test suite and verify all 24 tests pass in under 120 seconds
- [X] T039 [P] Verify all 24 PNG outputs are generated in `build/test-output/rendering/` with descriptive filenames matching test names
- [X] T040 [P] Review pixel sampling tolerance thresholds across all tests — ensure zero false positives on correct rendering
- [X] T041 Validate test independence — run each test class in isolation (no shared state, no execution order dependency)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **User Stories (Phases 3–8)**: All depend on Phase 2 completion
  - US1 and US2 (both P1): Can proceed in parallel after Phase 2
  - US3, US4, US5 (all P2): Can proceed in parallel after Phase 2
  - US6 (P3): Can proceed after Phase 2, no dependency on other stories
- **Polish (Phase 9)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no cross-story dependencies
- **US2 (P1)**: After Phase 2 — no cross-story dependencies
- **US3 (P2)**: After Phase 2 — no cross-story dependencies
- **US4 (P2)**: After Phase 2 — no cross-story dependencies
- **US5 (P2)**: After Phase 2 — no cross-story dependencies
- **US6 (P3)**: After Phase 2 — no cross-story dependencies

### Within Each User Story

- Create test class skeleton first
- Implement individual test methods sequentially within class
- Each test method is self-contained (builds own scene, renders, asserts)

### Parallel Opportunities

- T003, T004, T005, T006 can run in parallel (different files: PixelSampler.kt, SceneBuilder.kt)
- T007 depends on T003–T006 (harness uses all infrastructure)
- All test class skeletons (T008, T014, T019, T024, T028, T033) can run in parallel
- All Phase 9 tasks marked [P] can run in parallel

---

## Parallel Example: User Stories 1 & 2

```bash
# After Phase 2 completes, launch in parallel:
# Developer A: User Story 1
Task T008: Create BasicShadowLightTest.kt
Task T009–T013: Implement 5 basic tests

# Developer B: User Story 2
Task T014: Create ShadowVolumeGeometryTest.kt
Task T015–T018: Implement 4 geometry tests
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (infrastructure — BLOCKS all tests)
3. Complete Phase 3: User Story 1 (5 basic shadow/light tests)
4. **STOP and VALIDATE**: Run BasicShadowLightTest, verify 5 PNGs, check pixel assertions
5. This alone delivers immediate regression detection value

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. US1 (5 tests) → Basic shadow/light validation (MVP!)
3. US2 (4 tests) → Geometry accuracy validation
4. US3 (4 tests) → Light position/distance validation
5. US4 (3 tests) → Multi-light pipeline validation
6. US5 (4 tests) → Edge case robustness
7. US6 (4 tests) → Known artifact regression prevention
8. Each story adds scenarios without breaking previous tests

---

## Notes

- All tests use the same infrastructure (RenderTestHarness, PixelSampler, SceneBuilder)
- Occluder triangles are constructed directly in tests per R6 decision (no OccluderExtractor/World)
- FBO uses FrameBufferBuilder with GL_DEPTH24_STENCIL8 per R2 decision
- GL thread synchronization via postRunnable + CountDownLatch per R5 decision
- Hidden window via `Lwjgl3ApplicationConfiguration.setInitialVisible(false)` per R1 decision
- Pixel sampling uses region averaging with ±15/255 tolerance per R3 decision

