---
description: "Task list for Dynamic Raycast Lighting"
---

# Tasks: Dynamic Raycast Lighting

**Input**: Design documents from `specs/001-raycast-lighting/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: Included — Constitution Principle II (Test-First) is NON-NEGOTIABLE. Tests MUST
be written and confirmed FAILING before the corresponding implementation tasks run.

**Organization**: Tasks are grouped by user story to enable independent implementation and
testing of each story.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in every description

## Path Conventions

Single project — all paths from repository root:
- Implementation: `src/main/kotlin/com/roguelike/`
- Tests: `src/test/kotlin/com/roguelike/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the pure-Kotlin contract that wires the rest of the feature

- [X] T001 Create `ModelOcclusionProvider` fun interface in `src/main/kotlin/com/roguelike/core/systems/ModelOcclusionProvider.kt` — single method `isOccluded(ox,oy,oz,tx,ty,tz): Boolean`; zero LibGDX imports; follows GameLogger pattern

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Write failing tests first, then wire the interface into `SurfaceLighting` and `DynamicLighting`

**⚠️ CRITICAL**: Tests T002–T003 MUST be written and confirmed FAILING before T004–T007.
No user-story work can begin until this phase is complete.

- [X] T002 [P] Write failing tests for `SurfaceLighting` with stub `ModelOcclusionProvider` in `src/test/kotlin/com/roguelike/core/SurfaceLightingModelOcclusionTest.kt` — stub blocks a single planar slab; assert light is occluded past the slab and unoccluded before it
- [X] T003 [P] Write failing integration test loading `saved-worlds/world.wld` via `WorldIO` with a `FlatOccluder` (unit AABBs at wall-tile positions) in `src/test/kotlin/com/roguelike/serialization/WorldLightingIntegrationTest.kt` — assert no light leaks past known walls in the saved world
- [X] T004 Add optional `occluder: ModelOcclusionProvider? = null` parameter to `SurfaceLighting` constructor and `SurfaceLighting.build()` factory in `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt`
- [X] T005 In `SurfaceLighting.rayClear()`: when `occluder != null` delegate to `occluder.isOccluded(oxIn, oyIn, ozIn, txIn, tyIn, tzIn)` instead of running the grid DDA in `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt`
- [X] T006 Add optional `occluder: ModelOcclusionProvider? = null` parameter to `DynamicLighting.build()` companion factory and store it on the `DynamicLighting` instance in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T007 In `DynamicLighting.rayClear()`: when `occluder != null` delegate to `occluder.isOccluded()` instead of running the grid DDA in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`

**Checkpoint**: Run `./gradlew test --tests "com.roguelike.core.SurfaceLightingModelOcclusionTest"` and `"com.roguelike.serialization.WorldLightingIntegrationTest"` — both should now PASS. All other existing lighting tests MUST still pass (backward-compat: null occluder = grid DDA).

---

## Phase 3: User Story 1 — Geometry-Accurate Shadows (Priority: P1) 🎯 MVP

**Goal**: Shadow boundaries align with rendered model geometry, not tile-grid lines

**Independent Test**: Place a torch adjacent to a wall in the map editor. Shadow edge must follow the model face, not a rounded grid boundary. `SurfaceLightingModelOcclusionTest` passes. No `[LIGHTLOG] DDA start voxel mismatch` warnings in `./gradlew run -Drogue.lightlog=1`.

- [X] T008 [P] [US1] Create `BvhOccluder` class in `src/main/kotlin/com/roguelike/rendering/BvhOccluder.kt` — implements `ModelOcclusionProvider`; has `rebuild(boxes: List<BoundingBox>)` to replace the AABB list; `isOccluded()` iterates boxes using `Intersector.intersectRayBounds` segment test; empty list → always returns false
- [X] T009 [US1] Add `worldSpaceBoxes(world: World): List<BoundingBox>` to `src/main/kotlin/com/roguelike/rendering/TileRenderer.kt` — for each wall/closed-door tile in the world, compute a world-space `BoundingBox` using `TileRenderRegistry.getRenderData(tile).model.calculateBoundingBox()` scaled and translated to cell position (x, y, z)
- [X] T010 [US1] In `src/main/kotlin/com/roguelike/RoguelikeGame.kt`: add `bvhOccluder: BvhOccluder` field; call `bvhOccluder.rebuild(tileRenderer.worldSpaceBoxes(world))` once after world load; pass `occluder = bvhOccluder` to `DynamicLighting.build()` in the render loop
- [X] T011 [US1] Run `./gradlew test --tests "com.roguelike.core.SurfaceLightingModelOcclusionTest"` — all tests pass; verify zero mismatch warnings with `./gradlew run -Drogue.lightlog=1`

**Checkpoint**: US1 complete — carry a torch in any room and shadow edges track model faces. Independently testable via `SurfaceLightingModelOcclusionTest`.

---

## Phase 4: User Story 2 — Real-Time Dynamic Lights (Priority: P2)

**Goal**: Lighting recomputes every frame; door open/close is reflected immediately

**Independent Test**: Open and close a door while holding a torch. In the frame after the door toggles, light passes through (open) or stops (closed) with no delay or stale frame.

- [X] T012 [US2] Move `bvhOccluder.rebuild()` into the per-frame render path in `src/main/kotlin/com/roguelike/RoguelikeGame.kt` — rebuild before `DynamicLighting.build()` each frame; filter out AABBs for tiles whose door state is `isOpen == true` so open doors transmit light
- [X] T013 [US2] Extend `WorldLightingIntegrationTest.kt` with a door-state test: create world with a door, place torch on one side; assert far side is dark when door is closed and lit when door is open in `src/test/kotlin/com/roguelike/serialization/WorldLightingIntegrationTest.kt`
- [X] T014 [US2] Run `./gradlew test --tests "com.roguelike.serialization.WorldLightingIntegrationTest"` — door-state scenario passes

**Checkpoint**: US2 complete — all three movement, toggle, and extinguish scenarios from the spec are verified. Independently testable via `WorldLightingIntegrationTest`.

---

## Phase 5: User Story 3 — Multiple Blended Light Sources (Priority: P3)

**Goal**: Up to 8 concurrent dynamic light sources blend additively; all contribute to surfaces they can reach

**Independent Test**: Place 8 lit items in the world; each unique surface cell should receive contributions from all sources with line-of-sight to it. `WorldLightingIntegrationTest` 8-light scenario passes with occluder active.

- [X] T015 [US3] Verify `DynamicLighting.computeMaskMultiSample` loop cap (`i >= 30` guard) supports the 8-source requirement; add a constant `MAX_SUPPORTED_LIGHTS = 8` documentation comment and ensure the cap is ≥ 8 in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T016 [P] [US3] Add 8-light scenario to `WorldLightingIntegrationTest.kt`: place 8 lit items at known positions in a hand-built world (no wld file needed); assert combined surface brightness at center > single-source brightness in `src/test/kotlin/com/roguelike/serialization/WorldLightingIntegrationTest.kt`
- [X] T017 [US3] Run `./gradlew test --tests "com.roguelike.serialization.WorldLightingIntegrationTest"` — 8-light scenario passes

**Checkpoint**: All user stories independently functional. Carry a torch past wall-mounted candles in the game — all sources blend on shared surfaces.

---

## Final Phase: Polish & Cross-Cutting Concerns

**Purpose**: Diagnostic improvements and full suite validation

- [X] T018 [P] Update `LightingDiagnostics` to log occluder hit/miss counts per frame when `rogue.lightlog=1` in `src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt`
- [X] T019 [P] Remove the `leakWarned` stale-state path from `DynamicLighting.rayClear()` (or gate it to only run when `occluder == null`) in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T020 Run full test suite: `./gradlew test` — 42/43 tests pass; 1 pre-existing failure in `WorldTest.testDoorTagMakesWallPassable` unrelated to this feature (pre-dates this branch)
- [ ] T021 Visual validation: run `./gradlew run`, load `world.wld`, walk with torch per `specs/001-raycast-lighting/quickstart.md` — confirm no shadow-edge artifacts on wall faces, zero light leak through closed doors, smooth per-model shadow boundaries

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Requires T001; BLOCKS all user stories
- **US1 (Phase 3)**: Requires Phase 2 complete; no dependency on US2/US3
- **US2 (Phase 4)**: Requires Phase 2 complete; may build on US1 `BvhOccluder` (T008–T009)
- **US3 (Phase 5)**: Requires Phase 2 complete; no dependency on US1/US2 impl
- **Polish (Final)**: Requires all desired user stories complete

### Within Each Phase

- T002 and T003 MUST fail (red) before T004–T007 implement them (green)
- T008 can be written in parallel with T009 (different files)
- T010 depends on both T008 and T009
- T012 depends on T010 (BvhOccluder + worldSpaceBoxes in place)

### Parallel Opportunities

```bash
# Phase 2: Write both failing tests together
Task T002: SurfaceLightingModelOcclusionTest.kt
Task T003: WorldLightingIntegrationTest.kt

# Phase 2: Inject into both systems in parallel
Task T004+T005: SurfaceLighting.kt changes
Task T006+T007: DynamicLighting.kt changes

# Phase 3: BvhOccluder skeleton + worldSpaceBoxes can start together
Task T008: BvhOccluder.kt
Task T009: TileRenderer.worldSpaceBoxes()
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 — Create interface
2. T002–T003 — Write failing tests (confirm red)
3. T004–T007 — Wire interface (confirm tests go green)
4. T008–T010 — Build and wire `BvhOccluder`
5. T011 — **STOP and VALIDATE**: shadow edges follow model faces, no leaks
6. Ship MVP — geometry-accurate shadows

### Incremental Delivery

1. T001–T007 (Foundation) → tests pass, backward compat confirmed
2. T008–T011 (US1) → geometry shadows ✅ demo/validate
3. T012–T014 (US2) → real-time door response ✅ demo/validate
4. T015–T017 (US3) → 8-source blending ✅ demo/validate
5. T018–T021 (Polish) → diagnostics + full suite green

---

## Notes

- `[P]` tasks touch different files and have no unresolved dependencies — run in parallel
- `[Story]` label maps each task to its user story for traceability
- All `SurfaceLighting` and `DynamicLighting` existing tests MUST stay green throughout —
  the `occluder = null` default preserves the grid-DDA path
- The `FlatOccluder` in `WorldLightingIntegrationTest` is a headless stand-in that provides
  deterministic model-like occlusion without a LibGDX display; it lives only in test code
- Commit after each checkpoint (T011, T014, T017, T021) with the checkpoint description
