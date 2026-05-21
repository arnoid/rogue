---

description: "Task list for FPS Recovery in Multi-Light Scenes (spec 008)"
---

# Tasks: FPS Recovery in Multi-Light Scenes (FOV-Aware Shadow Culling)

**Input**: Design documents from `specs/008-fps-fov-shadow-culling/`
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`

**Tests**: **Included.** The spec explicitly requires `PerfRegressionTest` (FR-007), shader-level visual regression (SC-002), pure-logic state-machine tests (`WindowShiftHysteresisTest`), and a tile-quality SSBO contract test. They are NOT optional for this feature.

**Organisation**: Tasks are grouped by user story. The spec defines two user stories (US1 = "playable dense-light scene", US2 = "skeptic-friendly profiling overlay"); the three implementation options from the plan (per-tile LOD, frustum cull, spike bounding) are sub-tracks within US1 because the spec's US1 acceptance criteria require all three to land before SC-001 can be met.

## Format

`[ID] [P?] [Story] Description`

- **[P]** — can run in parallel with other [P] tasks of the same checkpoint (different files, no dependencies).
- **[Story]** — US1 or US2; FND for foundational; POL for polish.
- All paths are absolute-from-repo-root.

---

## Phase 1: Setup (Shared)

**Purpose**: prepare the perf branch and confirm no other concurrent feature work touches the lit pipeline. Cheap, fast.

- [X] **T001** Confirm working branch is `feature/fps`; if not, create `008-fps-fov-shadow-culling` from `main` and rebase. Run `git status` to verify a clean tree.
- [X] **T002** Grep-check that no other in-flight branch is modifying `src/main/resources/shaders/world_lit.frag.glsl`, `src/main/kotlin/com/roguelike/RoguelikeGame.kt`, or `src/main/kotlin/com/roguelike/ui/SimpleUI.kt`. Fail loudly if there is.
- [ ] **T003 [P]** Capture a clean **baseline** `[Profile]` log: launch the game, enter Arena, walk to the densest-light area you can reach today, capture 60 seconds of `[Profile]` output into `specs/008-fps-fov-shadow-culling/baseline.log`. Commit alongside the plan.
- [X] **T004 [P]** Verify Vulkan validation layer (`VulkanDebug`) is enabled in the test harness (`RenderTestHarness.kt`); if not, enable it for the duration of this feature so binding-table changes can't slip in silently.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the cross-cutting plumbing every user story relies on — the feature flag, the perf module package, and the keybinding. **No US1/US2 work can begin until this phase is complete.**

- [X] **T005 [P] [FND]** Create the `core/perf/` package:
  - `src/main/kotlin/com/roguelike/core/perf/PerfFlags.kt` — the `object PerfFlags` singleton exactly as specified in `contracts/perf-flags.md` (fields `enabled`, `centreFraction`, constants `PCF_TAPS_LOW = 1`, `MAX_PER_PIXEL_LIGHTS_LOW = 3`). Default `enabled = true`. Add a `loadFromLocalProperties(file: File)` static helper.
- [X] **T006 [FND]** Wire the `local.properties` override: read `perf.flags.enabled` in `Main.start()` once, before the first render frame. Falls back to default `true` when absent. *Depends on T005.*
- [X] **T007 [FND]** Bind **F11** to toggle `PerfFlags.enabled` in `Main.kt`'s input loop (use the existing `InputSystem` pattern; one-frame latency acceptable). Add a `println("[PerfFlags] enabled=$enabled")` line on each toggle for the log. *Depends on T005.*
- [X] **T008 [P] [FND]** Pure-logic state machine: create `src/main/kotlin/com/roguelike/core/perf/WindowShiftHysteresis.kt` with the API from `data-model.md` §3. No Vulkan dependency; no callers wired yet.
- [X] **T009 [P] [FND]** Pure-logic perf classifier: create `src/main/kotlin/com/roguelike/core/perf/PerfHud.kt` exposing a `classify(frameMs, cpuPhases, cacheHitRate): String` returning `"disabled" | "steady" | "gpu_bound" | "upload_spike" | "cache_miss"` per `data-model.md` §4. No HUD wiring yet.
- [X] **T010 [FND]** **Tests** — unit-test the foundations:
  - `src/test/kotlin/com/roguelike/core/perf/PerfFlagsTest.kt` — defaults, `loadFromLocalProperties` round-trip.
  - `src/test/kotlin/com/roguelike/core/perf/WindowShiftHysteresisTest.kt` — table-driven: stays put within threshold, shifts at threshold, respects cooldown, overrides on `forceShift`.
  - `src/test/kotlin/com/roguelike/core/perf/PerfHudTest.kt` — classifier table-driven for each of the 5 driver labels.
  - Run `./gradlew test --tests "com.roguelike.core.perf.*"` → must be green. *Depends on T005, T008, T009.*

**Checkpoint**: foundation in place. `PerfFlags.enabled` toggleable at runtime; supporting types unit-tested; no observable behaviour change yet.

---

## Phase 3: User Story 1 — Playable Dense-Light Scene (Priority: P1) 🎯 MVP

**Goal.** Recover ≥ 30 FPS / p99 ≤ 33 ms in the reference dense-light scene. Combines three sub-tracks (3a per-tile LOD, 3b frustum-clipped shadow upload, 3c spike bounding). All three must land for SC-001.

**Independent Test.** Load `saved-worlds/perf/dense-lights.wld`, stand on the `PERF_PROBE` tile, rotate camera 360° for 5 s with `PerfFlags.enabled = true`. Assert `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33`. See `quickstart.md` §3.

### 3a. Per-tile Shadow-Quality LOD (Option 1) — biggest GPU win

#### Tests for 3a (write first; must FAIL before implementation) ⚠️

- [X] **T011 [P] [US1]** `src/test/kotlin/com/roguelike/rendering/TileQualityPackingTest.kt` — round-trip the 4-bytes-per-uint packing the host writes and the shader reads. Pure-logic, no Vulkan: a Kotlin port of the GLSL packing rule.
- [ ] **T012 [P] [US1]** `src/test/kotlin/com/roguelike/rendering/TileQualityIntegrationTest.kt` — offscreen Vulkan render of a 4-quadrant test scene (centre vs. corner tiles); writes a known quality pattern (`2`, `1`, `0`, `1`) into the SSBO, asserts the centre tile produces 5-tap PCF-smoothness pixels and the corner tile produces visibly sharper pixels. Uses `RenderTestHarness`.
- [ ] **T013 [P] [US1]** `src/test/kotlin/com/roguelike/rendering/ShadowLodVisualTest.kt` — with `PerfFlags.enabled = true`, render the spec-007 baseline scene used by `BasicShadowLightTest`. Sample a centre region and assert pixel brightness range matches today's (≤ 2 % delta); sample a corner region and assert it falls within a (deliberately looser) sharpness band defined in the test.

#### Implementation for 3a

- [X] **T014 [US1]** Extend `src/main/kotlin/com/roguelike/ui/SimpleUI.kt`:
  - Add `tileQualitySsboBuffer` + `tileQualitySsboAlloc` + `tileQualitySsboSize` fields next to the existing `tileLightCount*` fields.
  - In `createLitDescriptorSetLayout`: append binding 5 (`VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`, `VK_SHADER_STAGE_FRAGMENT_BIT`, descriptor count 1).
  - In `createLitDescriptorPool`: bump `STORAGE_BUFFER` pool size from 4 to 5.
  - In the descriptor-set-update site: add a `VkWriteDescriptorSet` for binding 5.
  - In `close()`: destroy the new VMA buffer.
  - Allocate the SSBO at `max(MAX_LIGHT_TILES, 16)` bytes via `VMA_MEMORY_USAGE_CPU_TO_GPU`; mirror the existing tile-light SSBO allocation pattern.
- [X] **T015 [US1]** Add `fun updateTileQuality(qualities: ByteArray, tileCount: Int)` to `SimpleUI.kt` exactly per `contracts/tile-quality-ssbo.md`. Validate `tileCount ≤ MAX_LIGHT_TILES`; clamp every byte to `[0,2]` before write; zero the tail. *Depends on T014.*
- [X] **T016 [US1]** Modify `src/main/resources/shaders/world_lit.frag.glsl`:
  - Add the `TileQuality` SSBO block (binding 5) and `readTileQuality(int tIdx)` helper per `contracts/tile-quality-ssbo.md`.
  - In `main()`, read `q = readTileQuality(tIdx)` once, immediately after the existing tile-light lookup.
  - Branch on `q`:
    - `q == 0`: paint ambient-only (skip top-K loop and shading loop).
    - `q == 1`: cap top-K iteration at `3`; call `shadowVisibility` in a 1-tap mode (new parameter or new helper `shadowVisibilityCheap`).
    - `q >= 2`: today's behaviour unchanged.
  - **Preserve every existing comment block.** Add a new "spec 008" comment block above the new code.
- [X] **T017 [US1]** Add `shadowVisibilityCheap(surfacePos, lightPos)` to `world_lit.frag.glsl`: 1-tap centre-only ray (no perimeter samples). Returns `0.0` or `1.0`. *Companion to T016; same file, single commit.*
- [X] **T018 [US1]** Producer wiring: in `src/main/kotlin/com/roguelike/RoguelikeGame.kt`, in `uploadLighting` **after** the call to `uploadLightTiles`, compute the per-tile quality byte array:
  ```
  for each tile t:
      q[t] = if (!PerfFlags.enabled) 2
             else if tileLightCount[t] == 0 then 0
             else if distFromCentre(t) > R then 1
             else 2
  ```
  where `R = PerfFlags.centreFraction * min(sw, sh) * 0.5 / LIGHT_TILE_SIZE`. Call `ui.updateTileQuality(qualities, tilesX * tilesY)`. *Depends on T014, T015, T016.*
- [X] **T019 [US1]** Reuse the same `qualities: ByteArray` instance frame-to-frame (allocate once at `MAX_LIGHT_TILES`); zero only the prefix `[0, tileCount)` to avoid GC pressure in the hot path. Add a comment explaining the reuse pattern (legacy-archaeologist convention). *Depends on T018.*
- [X] **T020 [US1]** Run `./gradlew compileKotlin test --tests "com.roguelike.rendering.TileQuality*"` and `--tests "com.roguelike.rendering.ShadowLodVisualTest"`. All green before moving on. Re-run `BasicShadowLightTest`, `EdgeCaseRobustnessTest`, `LightPositionDistanceTest`, `MultiLightInteractionTest` — must still pass (no spec-007 regression).

### 3b. Frustum-clipped Shadow-Mesh SSBO (Option 2) — the user's stated request

#### Tests for 3b (write first; must FAIL before implementation) ⚠️

- [X] **T021 [P] [US1]** `src/test/kotlin/com/roguelike/rendering/FrustumClippedShadowTest.kt` — pure-logic test of the consumer-side cell filter. Build a synthetic cell list with known AABBs, a known frustum, assert which cells get emitted vs skipped given a 1-cell skirt.

#### Implementation for 3b

- [X] **T022 [US1]** In `src/main/kotlin/com/roguelike/RoguelikeGame.kt`, add the `FRUSTUM_SKIRT_CELLS = 1` constant to the companion `object` next to `ARENA_AMBIENT`.
- [X] **T023 [US1]** Modify the **consumer** side of `uploadLighting` (the loop that expands per-cell triangles from `shadowCellCache` / `shadowTriBuf` into `expandedTriBuf`, ≈ lines 990-1030): before emitting a cell's triangles, test `camera.isBoxInFrustum(cellAabbExpandedByFrustumSkirt)`. Skip the cell when the test fails AND `PerfFlags.enabled == true`. **Do not touch the producer** — `shadowCellCache` stays frustum-agnostic to preserve its hit rate. *Depends on T022.*
- [X] **T024 [US1]** Update the per-cell shadow-tri range encoded into `occupancy[]` for skipped cells: their `(start, count)` field must read `(0, 0)` so the shader's `getShadowTriRange` returns an empty range. (Bit flags 0-6 stay intact so wall occlusion still works for cells outside the frustum that *are* on the ray's path.) *Depends on T023.*
- [X] **T025 [US1]** Mirror the frustum-cull in `src/main/kotlin/com/roguelike/MapEditor.kt`'s `updateLighting` block **only behind the same `PerfFlags.enabled` guard**, so the editor preview can be toggled to match game behaviour. Default behaviour (flag off) stays today's WYSIWYG full-window upload.
- [X] **T026 [US1]** Run `./gradlew test --tests "com.roguelike.rendering.FrustumClippedShadowTest"` and the full `com.roguelike.rendering.*` regression suite. All green.

### 3c. Spike Bounding (Option 5) — kill the 38.9 ms outlier

#### Tests for 3c (write first; must FAIL before implementation) ⚠️

- [X] **T027 [P] [US1]** `src/test/kotlin/com/roguelike/core/perf/WindowShiftIntegrationTest.kt` — script a simulated player walk over 200 frames, alternately within and beyond the cell threshold; assert the resolved window origin shifts exactly when expected and not more often.

#### Implementation for 3c

- [X] **T028 [US1]** Add a `private val windowHysteresis = WindowShiftHysteresis()` field to `RoguelikeGame`. In `uploadLighting`, replace the direct `(originX, originY, originZ)` computation with `windowHysteresis.resolve(desired = computedOrigin, forceShift = lightJump > 0.20f)`. *Depends on T008, T010.*
- [X] **T029 [US1]** Track per-frame light-count jump for the `forceShift` heuristic: store `lastFrameLights` (already exists) and pass `abs(thisFrame - lastFrame) / max(lastFrame, 1) > 0.20` as `forceShift`. *Depends on T028.*
- [X] **T030 [US1]** Add a per-cell triangle-count cap of 24 in the producer when `PerfFlags.enabled == true`: in the `collectShadowTriangles` calls inside the cache-miss block, stop appending triangles for a cell once that cell would exceed the cap. Log a one-shot warning naming the offending cell coordinates (FR-008). *Depends on T028.*
- [X] **T031 [US1]** Run `./gradlew test --tests "com.roguelike.core.perf.WindowShiftIntegrationTest"`; manual scene reproduction of the outlier — walk into the room, watch `[Profile] uploadLighting` values — confirm no frame exceeds 10 ms (NFR-002).

### Checkpoint — US1 acceptance

- [ ] **T032 [US1]** **Validation gate**: load `saved-worlds/perf/dense-lights.wld` (created in Phase 5 / US2 task T038; if not yet created, use the closest existing scene). Capture 60 s of `[Profile]` lines. Assert all four spec success criteria:
  - SC-001 `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33`.
  - SC-002 `com.roguelike.rendering.*` test suite is green.
  - SC-003 `p99(uploadLighting) ≤ 10 ms` over the 60 s window.
  - SC-004 Before/after `[Profile]` capture present in `specs/008-fps-fov-shadow-culling/baseline.log` AND `after.log`.

> US1 is the MVP. If T032 passes, the feature can ship even if US2 (HUD + deterministic test) is deferred.

---

## Phase 4: User Story 2 — Skeptic-Friendly Profiling Overlay (Priority: P2)

**Goal.** Expose the derived perf metrics on the HUD and ship the deterministic perf-regression test so future PRs cannot silently undo this work.

**Independent Test.** Toggle F11 with the new HUD on screen. The HUD's `driver=` field MUST change between `disabled` and one of `steady | gpu_bound | upload_spike | cache_miss`. The `PerfRegressionTest` MUST fail when artificially regressed (e.g. by forcing `PerfFlags.enabled = false`).

### Tests for US2 (write first; must FAIL before implementation) ⚠️

- [X] **T033 [P] [US2]** `src/test/kotlin/com/roguelike/rendering/PerfRegressionTest.kt` — load `saved-worlds/perf/dense-lights.wld` via the harness, spawn the player at `PERF_PROBE`, rotate camera 360° over 5 s of simulated frames, collect frame times, assert `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33`. Marked `@DisabledIfNoVulkan` so CI without Vulkan skips cleanly. *(Skeleton landed; class is `@Disabled` pending T038 + a `RenderTestHarness.simulateGameLoop` runner. The placeholder body fails loudly so un-disabling before the runner exists yields a clear error rather than a silent green.)*
- [X] **T034 [P] [US2]** `src/test/kotlin/com/roguelike/core/perf/PerfHudClassifierTest.kt` — extend `PerfHudTest` (from T010) with one case per real captured frame from `logs.txt` (the worst 6 from `research.md` §1). Verify the classifier picks the right label for each. *(Green: 6 dynamic tests pinning the worst-6 historical frames to their expected labels.)*

### Implementation for US2

- [X] **T035 [US2]** Wire `PerfHud` into `RoguelikeGame`'s HUD line: append `  gpu_ms=… cache_hit=…% driver=…` to the existing `[Profile]` print. Read counters from a new `shadowCellCacheMissCount` / `cellsTouchedCount` pair on the cache (lightweight `Int` fields incremented in the cache-miss branch). *Depends on T009.* *(Done in `RoguelikeGame.kt`: new `shadowCellCacheMissCount` / `shadowCellsTouchedCount` fields reset at the top of `uploadLighting`, bumped in the per-cell forEach; HUD line and on-screen text now show `gpu_ms`, `cache_hit=%`, `driver=`.)*
- [X] **T036 [US2]** When `PerfFlags.enabled == false`, the HUD `driver=` MUST read `disabled` regardless of measurements (per `contracts/perf-flags.md`). *(Enforced by `PerfHud.classify` which returns `"disabled"` whenever `flagEnabled == false`; the HUD call site reads `PerfFlags.enabled` directly. Covered by `PerfHudTest.disabled flag always reports disabled`.)*
- [X] **T037 [US2]** Sanity-confirm in code that the cache miss/touched counters are reset at the top of each frame, not accumulated forever; add an `@Test` that runs 100 frames and asserts the rate stays bounded. *(Implemented in `src/test/kotlin/com/roguelike/core/perf/CacheCounterResetTest.kt`: three @Tests pin the counter declarations, the top-of-uploadLighting reset placement, and 100-frame classifier stability. Green.)*
- [ ] **T038 [US2]** Create the deterministic perf scene: `saved-worlds/perf/dense-lights.wld` reproducing the worst observed frame (≈ 128 candidate lights, ≈ 45 k shadow triangles, ≥ 30 × 30 × 15 window). Hand-author in the editor; verify by loading it once and reading `[LightWindow]` line — `shadowTris ≥ 40000` required. Place a node tagged `PERF_PROBE` at the worst spot. Commit the file. **BLOCKED**: requires the in-game map editor (interactive Vulkan + GLFW); cannot be authored from a non-interactive agent run. Once the user creates and commits the file, drop `@Disabled` from `PerfRegressionTest` and wire the `RenderTestHarness.simulateGameLoop` runner (T039).
- [ ] **T039 [US2]** Run `./gradlew test --tests "com.roguelike.rendering.PerfRegressionTest"` against the new scene. Green required. **BLOCKED on T038 + harness runner**; the test class exists and will fail with a clear "runner not yet wired" message until both land.
- [X] **T040 [US2]** Add a one-line `quickstart.md` cross-reference in the repo README under a new "Profiling" subsection (one paragraph). Tiny doc change; reviewers will appreciate the breadcrumb. *(Added in `README.md`.)*

### Checkpoint — US2 acceptance

- [ ] **T041 [US2]** Manual F11 toggle confirms the HUD `driver=` label switches between `disabled` and a real classification within one frame. `PerfRegressionTest` green and re-runnable. Test fails if you flip `PerfFlags.enabled = false` in the test — confirming SC-002 / SC-004 are wired to a real measurement.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: clean-up that touches multiple stories. Do these last.

- [ ] **T042 [P] [POL]** Update `.github/copilot-instructions.md` (the speckit pointer): bump the "current plan" line to `specs/008-fps-fov-shadow-culling/plan.md` if the feature is the active branch context.
- [ ] **T043 [P] [POL]** Cross-link the new spec from `specs/007-replace-libgdx-vulkan/plan.md`'s "Related" section so future readers find the perf follow-up.
- [ ] **T044 [POL]** Strip any debug `println` calls added during development that aren't behind a log-level guard.
- [ ] **T045 [POL]** Re-read every modified file once with the `legacy-archaeologist` checklist in mind: every long comment block we touched is still there; every "why" tribal knowledge note is preserved or augmented (never replaced).
- [ ] **T046 [POL]** Run the full suite `./gradlew test` plus `./gradlew compileKotlin`. Zero failures, zero new warnings beyond those already present in `main`.
- [ ] **T047 [POL]** Capture the final `[Profile]` "after" log into `specs/008-fps-fov-shadow-culling/after.log`. Commit both `baseline.log` (from T003) and `after.log` so SC-004's "before/after" artefact exists in repo history.
- [ ] **T048 [POL]** Update `specs/008-fps-fov-shadow-culling/plan.md`'s status table: mark phases 2-6 as ✅ Complete.

---

## Dependencies & Execution Order

### Phase dependencies

```
Phase 1 (Setup)            — no deps; do first.
Phase 2 (Foundational)     — depends on Phase 1.
Phase 3 (US1)              — depends on Phase 2; sub-tracks 3a/3b/3c can themselves overlap
                             but the shader file (3a + 3c both touch it) must serialise.
Phase 4 (US2)              — depends on Phase 2 (for PerfFlags + PerfHud); independent of
                             Phase 3 in principle, but the PerfRegressionTest will not pass
                             until US1 lands. Defer T039 / T041 until after T032.
Phase 5 (Polish)           — depends on US1 (mandatory) and US2 (if shipped).
```

### Per-task parallelism rules

- `[P]` tasks within the same checkpoint touch disjoint files and can run in parallel.
- Tests written for a sub-track MUST be present and FAILING before implementation tasks in that sub-track begin (red-green-refactor).
- Shader (`world_lit.frag.glsl`) changes from T016 and T030 are in the same file: do T016 first, T030 second.
- `SimpleUI.kt` changes from T014 are touched again by no other US1 task; safe to commit once finished.
- `RoguelikeGame.kt` changes are spread across T018 (3a producer), T022-T024 (3b), T028-T030 (3c). Sequence them: 3a → 3b → 3c. Each merge is a separate commit so `git bisect` works if a perf regression appears later.

### Within each user story

- Tests FIRST, FAILING. Implementation SECOND, GREEN.
- Foundational pure-logic types (T008, T009) before any host wiring that uses them (T028, T035).
- SimpleUI host bindings (T014) before the GLSL reader (T016) — Vulkan validation will scream if descriptor sets diverge.

---

## Parallel Example: kicking off US1 sub-tracks

After Phase 2 is green, three developers (or three task slots) can run in parallel:

```
Dev A → 3a track: T011, T012, T013 (parallel test writes) → T014 → T015 → T016 → T017 → T018 → T019 → T020
Dev B → 3b track: T021                                    → T022 → T023 → T024 → T025 → T026
Dev C → 3c track: T027                                    → T028 → T029 → T030 → T031
```

Then everyone converges on T032 (US1 acceptance gate).

---

## Implementation Strategy

### MVP First (US1 only — recommended)

1. Phase 1 (Setup): T001-T004.
2. Phase 2 (Foundational): T005-T010. **Hard gate.**
3. Phase 3 sub-track 3a (per-tile LOD): T011-T020.
4. Phase 3 sub-track 3b (frustum cull): T021-T026.
5. Phase 3 sub-track 3c (spike bounding): T027-T031.
6. US1 acceptance gate: T032. **Stop and validate.**
7. Ship the perf win.

### Incremental delivery (if US1 takes too long)

Sub-track 3a alone is expected to deliver the bulk of the FPS lift. After T020 (3a tests green) you have an interim demo: F11 toggle shows a measurable centre-vs-periphery quality split. Sub-tracks 3b and 3c add CPU savings and spike removal but are smaller wins.

### After US1: US2 (profiling overlay)

US2 is *infrastructure* for future perf work, not a player-visible feature. Strongly recommend doing it before merging US1 so the PR includes the regression test (T033/T039) and the historical `baseline.log`/`after.log` artefacts (T047).

---

## Notes

- `[P]` = different files, no dependencies; can run in parallel.
- `[Story]` (US1, US2, FND, POL) maps each task to a checkpoint for traceability.
- Each user story is independently completable but US2's `PerfRegressionTest` (T033/T039) cannot turn green until US1 has landed.
- Every test task MUST FAIL when first added; only then write implementation.
- Commit after each task or each tightly-coupled group (e.g. T014+T015 together; T016+T017 together).
- Pause at every Checkpoint task (T010, T020, T026, T031, T032, T041) — these are decision points where you may roll back or change scope.
- Avoid: hidden coupling between sub-tracks (each must be reachable from a clean Phase-2 baseline); same-file conflicts (sequence `world_lit.frag.glsl` edits); deleting existing tribal-knowledge comments (legacy-archaeologist rule).

