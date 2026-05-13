# Tasks: Smooth Cone Light Edges

**Input**: Design documents from `specs/002-smooth-cone-edges/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Tests**: Included — constitution mandates TDD (Test-First is NON-NEGOTIABLE). Write tests RED before implementation GREEN.

**Organization**: Tasks grouped by user story. FR-007 (floor tile occlusion) and shared cone infrastructure are in Phase 2 (Foundational) since they are blocking prerequisites for both US1 and US2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- All file paths are project-relative

---

## Phase 1: Setup

**Purpose**: Establish green baseline before any changes

- [X] T001 Run baseline test suite to confirm green state: `./gradlew test`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: FR-007 floor tile occlusion fix + shared `softConeFactor()` helper + `LightDef` field — required by both US1 and US2.

**⚠️ CRITICAL**: All user story work depends on T008 completing.

### FR-007 — Floor Tile Light Occlusion

- [X] T002 Write failing FloorOcclusionTest: FloorTile.blocksLight() returns true; WallNorthTile.blocksLight() returns true; open DoorNorthTile.blocksLight() returns false in `src/test/kotlin/com/roguelike/world/FloorOcclusionTest.kt` (RED — blocksLight() does not exist yet)
- [X] T003 Add `fun blocksLight(): Boolean = isBlocking()` default method to Tile interface in `src/main/kotlin/com/roguelike/core/model/Tile.kt`
- [X] T004 Override `blocksLight(): Boolean = true` in FloorTile in `src/main/kotlin/com/roguelike/world/Tiles.kt`
- [X] T005 Change `worldSpaceBoxes()` filter from `!tile.isBlocking()` to `!tile.blocksLight()` in `src/main/kotlin/com/roguelike/rendering/TileRenderer.kt` — verifies T002 goes GREEN

### Shared Cone Infrastructure

- [X] T006 [P] Write failing ConeUtilsTest: softConeFactor(1f, 0.707f, 0.643f) == 1f; softConeFactor(0.643f, 0.707f, 0.643f) == 0f; softConeFactor(0.675f, 0.707f, 0.643f) ≈ 0.5f in `src/test/kotlin/com/roguelike/core/ConeUtilsTest.kt` (RED — ConeUtils does not exist yet)
- [X] T007 [P] Add `val coneFeatherDegrees: Float = 3f` field to LightDef data class in `src/main/kotlin/com/roguelike/core/model/ItemCatalog.kt`
- [X] T008 Create `ConeUtils.kt` with `internal fun softConeFactor(dot: Float, cosHardEdge: Float, cosSoftEdge: Float): Float = ((dot - cosSoftEdge) / (cosHardEdge - cosSoftEdge)).coerceIn(0f, 1f)` in `src/main/kotlin/com/roguelike/core/systems/ConeUtils.kt` — makes T006 GREEN

**Checkpoint**: Foundation ready. FloorTile blocks light, softConeFactor() helper exists, LightDef has coneFeatherDegrees.

---

## Phase 3: User Story 1 — Geometrically Correct Cone Boundary (Priority: P1) 🎯 MVP

**Goal**: Replace hard binary cone cutoff in all three lighting code paths with soft angular falloff using `softConeFactor()`. Eliminates the grid-staircase pattern at cone light edges.

**Independent Test**: `./gradlew test --tests "com.roguelike.core.LightingSystemConeTest"` passes; `./gradlew test --tests "com.roguelike.core.SurfaceLightingConeSmoothTest"` passes.

### Tests for User Story 1

> **Write these tests FIRST, confirm they FAIL before implementation**

- [X] T009 [P] [US1] Write LightingSystemConeTest: assert that intensity of the cell just outside the hard cone edge is > 0 (penumbra) and < intensity of cell well inside cone in `src/test/kotlin/com/roguelike/core/LightingSystemConeTest.kt` (RED)
- [X] T010 [P] [US1] Write SurfaceLightingConeSmoothTest: assert that two adjacent cells straddling the cone boundary receive different non-zero intensities (no hard step) in `src/test/kotlin/com/roguelike/core/SurfaceLightingConeSmoothTest.kt` (RED)

### Implementation for User Story 1

- [X] T011 [US1] Replace hard cone cutoff `if (dot < cosHalf) continue` with soft falloff using `softConeFactor()` in `LightingSystem.applyLight()` in `src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt` — makes T009 GREEN
- [X] T012 [US1] Replace hard cone cutoff `if (dot < cosHalf) continue` with `softConeFactor()` multiplied into `mul` in `SurfaceLighting.sample()` and `SurfaceLighting.sampleWall()` in `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt` — makes T010 GREEN
- [X] T013 [US1] Add `coneScales: FloatArray` output to `DynamicLighting.computeMaskMultiSample()`: for CONE lights, accumulate `softConeFactor()` across the 5 sample points and normalise to [0,1]; for SPHERE lights, set 1.0 in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T014 [US1] Update `DynamicLighting.buildEnv()` to scale each CONE PointLight intensity by `coneScales[i]` in `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt`
- [X] T015 [US1] Add `"coneFeatherDegrees": 3.0` to candle and wall_candle light definitions in `src/main/resources/items/items.json`

**Checkpoint**: Run `./gradlew test --tests "com.roguelike.core.LightingSystemConeTest"` and `./gradlew test --tests "com.roguelike.core.SurfaceLightingConeSmoothTest"` — both must pass before proceeding.

---

## Phase 4: User Story 2 — Cone Edge at Range Boundary (Priority: P2)

**Goal**: Confirm the far arc at maximum cone range is smooth. The existing linear range falloff (`1 - dist/range`) already fades to zero at the range boundary; US2 primarily verifies that no grid-staircase remains on the arc after the US1 angular fix.

**Independent Test**: `./gradlew run` — open a saved world, place a cone light in a large open room, observe the far arc; it must be a smooth curve with no visible grid steps.

- [ ] T016 [US2] Visual verification: run `./gradlew run` with a cone light source and confirm the far arc at max range is smooth per quickstart.md acceptance scenario — no staircase on the arc
- [ ] T017 [US2] If T016 reveals remaining range-boundary staircase: add a range feather zone (`rangeFeatherFraction: Float = 0.05f` soft outer band) to `SurfaceLighting.sample()` using the same linear interpolation pattern as `softConeFactor()` in `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt`
- [ ] T018 [US2] If T017 was implemented: apply matching range feather in `LightingSystem.applyLight()` for consistency in `src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt`

**Checkpoint**: Far arc boundary is visually smooth (no staircase). US1 and US2 acceptance criteria both satisfied.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Debug logging, full regression, and visual validation.

- [X] T019 Add `[LIGHTLOG]` output for `coneFeatherDegrees` alongside existing cone parameters (half-angle, feather width) in `src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt` or the diagnostics logging path used by `-Drogue.lightlog=1`
- [X] T020 Run full test suite regression guard: `./gradlew test` — all tests must pass including existing `SurfaceLightingModelOcclusionTest` and `LightingSystemTest`
- [ ] T021 Visual validation per quickstart.md: open a saved world with a wall-mounted candle; confirm straight diagonal cone edges, no staircase, no floor light leakage; confirm point lights (torch, lantern) look identical to before

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies
- **Phase 2 (Foundational)**: Depends on Phase 1 — **BLOCKS all user stories**
  - T002–T005 (FR-007) can proceed immediately after T001
  - T006–T008 (ConeUtils) can run in parallel with T002–T005
  - T008 MUST complete before Phase 3 begins
- **Phase 3 (US1)**: Depends on Phase 2 completion (T008 must be GREEN)
- **Phase 4 (US2)**: Depends on Phase 3 completion
- **Phase 5 (Polish)**: Depends on Phase 4 completion

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational (T008)
- **US2 (P2)**: Depends on US1 completion — range boundary verification requires angular fix first

### Within Phase 3 (US1)

- T009 and T010 (tests) MUST fail before T011/T012 begin
- T011 and T012 are independent (different files) — can run in parallel
- T013 and T014 are sequential (both in DynamicLighting.kt)
- T015 (items.json) can run at any point after T007

### Parallel Opportunities

- **Phase 2**: T006, T007 can run in parallel (different files)
- **Phase 2**: T002–T005 (FR-007) and T006–T008 (ConeUtils) can proceed in parallel
- **Phase 3**: T009 and T010 (tests) can run in parallel; T011 and T012 (implementation) can run in parallel

---

## Parallel Example: Phase 3 (US1)

```bash
# Launch test writing in parallel (both RED):
Task: "Write LightingSystemConeTest.kt" (T009)
Task: "Write SurfaceLightingConeSmoothTest.kt" (T010)

# Then launch implementation in parallel (make both GREEN):
Task: "Update LightingSystem.applyLight() with softConeFactor()" (T011)
Task: "Update SurfaceLighting.sample()/sampleWall() with softConeFactor()" (T012)
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: baseline green
2. Complete Phase 2: Foundational (FR-007 + ConeUtils)
3. Complete Phase 3: US1 (angular cone fix in all three code paths)
4. **STOP and VALIDATE**: straight cone edges visible, tests pass
5. Deploy/demo if ready

### Incremental Delivery

1. Phase 1 + Phase 2 → foundation ready (floor occlusion fixed, helper exists)
2. Phase 3 (US1) → straight cone edges, tests green → Demo (MVP!)
3. Phase 4 (US2) → smooth range arc → Demo
4. Phase 5 (Polish) → logging, regression, visual sign-off

---

## Notes

- TDD is mandatory per the project constitution (Principle II). Every test MUST fail before its implementation task begins.
- `softConeFactor()` is `internal` in `core.systems` — tests in the same package can call it directly.
- `DynamicLighting` changes (T013, T014) must be tested visually since the GPU PointLight intensity is hard to unit-test without a display.
- `blocksLight()` default on `Tile` = `isBlocking()` — existing walls, doors, stairs behavior unchanged.
- items.json change (T015) is backward-compatible: existing JSON without `coneFeatherDegrees` defaults to `3f` per LightDef field default.
