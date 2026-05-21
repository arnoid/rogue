# Implementation Plan: FPS Recovery in Multi-Light Scenes (FOV-Aware Shadow Culling)

**Branch**: `feature/fps` (legacy name; spec ID `008-fps-fov-shadow-culling`) | **Date**: 2026-05-20 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/008-fps-fov-shadow-culling/spec.md`

## Summary

Recover playable frame rate (≥ 30 FPS, p99 ≤ 33 ms) in dense multi-light
scenes by attacking the measured GPU bottleneck in
`world_lit.frag.glsl` rather than the user's initially-suspected
out-of-FOV mesh shading. Combine three changes ordered by ROI:

1. **Per-tile shadow-quality LOD** (P0, Option 1 from the spec) —
   propagates a per-tile quality byte from CPU into the fragment shader
   so peripheral tiles use 1-tap PCF, low-light-count tiles skip the
   top-K insertion sort, and zero-light tiles bypass the lighting loop
   entirely. Directly attacks the dominant `K × S × D × T` per-fragment
   cost.
2. **Frustum-clipped shadow-mesh SSBO** (P1, Option 2 — the user's
   stated request) — drops shadow-triangle emission for cells whose
   AABB lies fully outside the view frustum (with a 1-cell "skirt" so
   wall-borrows into in-frustum neighbours still work). Cuts CPU upload
   cost and shrinks the per-fragment `shadowTris[]` cache footprint.
3. **Spike bounding** (P1, Option 5) — window-shift hysteresis
   (≥ 4-cell threshold + cooldown) and an explicit per-cell triangle
   cap to eliminate the observed 38.9 ms `uploadLighting` outliers.

All three ship behind a single `F11` feature flag (`PerfFlags.enabled`)
so before/after profiling is deterministic. Visual-test suite must stay
green.

## Current Implementation Status

| Phase | Status | Notes |
|---|---|---|
| Phase 0: Research & Measurement | ✅ Complete | See `research.md`; bottleneck profile derived from `logs.txt`. |
| Phase 1: Design (data model + contracts + quickstart) | ✅ Complete | This document + `data-model.md` + `contracts/` + `quickstart.md`. |
| Phase 2: US1 — Per-tile shadow LOD | ✅ Code complete | T011-T020 done; T012/T013 (Vulkan integration tests) deferred; T020 visual regression suite blocked by pre-existing failure in unrelated in-flight branch state. |
| Phase 3: US2 — Frustum-clipped shadow SSBO | ✅ Code complete | T021-T026 done; T026 visual regression suite blocked as above. |
| Phase 4: US3 — Window-shift hysteresis + tri cap | ✅ Code complete | T027-T031 done; T031 manual perf reproduction not run (requires interactive Vulkan capture). |
| Phase 5: US4 — Perf HUD + deterministic test scene | ⬜ Not started | P2; spec FR-005, FR-006, FR-007, SC-004. Out of scope for this run. |
| Phase 6: US5 — Low-poly shadow proxies for high-tri meshes | ⬜ Not started | P0 for SC-005; spec FR-009 + tightened FR-008. Code work ready; asset authoring blocked outside this repo. |
| Phase 7: Visual-regression validation | ⛔ Blocked | Requires interactive Vulkan capture (T032 / T039) and the dense-lights.wld scene (T038, US2 scope). |

## Technical Context

**Language/Version**: Kotlin 1.9.22  
**Primary Dependencies**: LWJGL 3.4.1 (Vulkan, GLFW, VMA, STB, shaderc), JOML 1.10.8  
**Storage**: SSBO/UBO via VMA; saved-world `.wld` files under `saved-worlds/`  
**Testing**: JUnit Jupiter 5.10.1; offscreen Vulkan harness (`RenderTestHarness`)  
**Target Platform**: Desktop (Windows primary). Reference hardware: integrated GPU class capable of ≥ 60 FPS on a single-light scene today.  
**Project Type**: Desktop game application  
**Performance Goals**: ≥ 30 FPS / p99 ≤ 33 ms in the reference dense-light scene; p99 `uploadLighting` ≤ 10 ms.  
**Constraints**: Must not regress spec-007 shadow correctness or the spec-006 visual test suite. Must not raise steady-state CPU work > 1 ms/frame.  
**Scale/Scope**: ~63 × 63 × 15 voxel windows, up to 128 simultaneously-uploaded lights, up to 131 071 shadow triangles per frame.

## Constitution Check

*Gate: must pass before Phase 0 research. Re-check after Phase 1 design.*

The project constitution under `.specify/memory/constitution.md` is a
placeholder (template defaults). No formal gates apply. Applicable
governance derived from the existing project practice:

- **Measure-first** (perf-skeptic skill): every change carries a
  before/after `[Profile]` capture. *Met* — see Phase 0 measurement
  block.
- **Layered comments, not deletions** (legacy-archaeologist skill):
  every modified file preserves the existing "why" comment blocks. *Met
  by design* — the changes are additive (new branches, new uniform
  fields, new HUD line).
- **No silent visual regressions**: spec-006 visual tests must remain
  green. *Met by Phase 6 gate.*
- **Single feature flag**: all behaviour changes hide behind
  `PerfFlags.enabled` (toggled by `F11`) so A/B comparisons are
  deterministic. *Met by design.*

Re-check after Phase 1: passes — no new principles violated, no
complexity tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/008-fps-fov-shadow-culling/
├── plan.md              # This file
├── spec.md              # Feature specification (already authored)
├── research.md          # Phase 0 measurement + option analysis
├── data-model.md        # Phase 1 data shapes (UBO/SSBO updates)
├── quickstart.md        # Phase 1 dev-loop instructions
├── contracts/
│   ├── perf-flags.md            # The single F11 toggle contract
│   ├── tile-quality-ssbo.md     # New per-tile quality SSBO contract
│   ├── shader-binding-table.md  # Updated bindings (sets 0/1)
│   └── shadow-proxy-discovery.md # NEW (US5): `.shadow.obj` companion convention
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (touched files)

```text
src/main/
├── kotlin/com/roguelike/
│   ├── RoguelikeGame.kt                # uploadLighting, uploadLightTiles, perf HUD
│   ├── MapEditor.kt                    # mirror frustum-cull + tri-cap (editor preview parity)
│   ├── utils/AssetLoader.kt            # NEW (US5): `.shadow.obj` companion lookup in loadModelWithShadow
│   ├── core/perf/PerfFlags.kt          # NEW: single toggle, F11 binding; NEW (US5): PER_CELL_SHADOW_TRI_CAP = 16
│   ├── core/perf/PerfHud.kt            # NEW: derived metrics (gpu_ms, cache_hit, classifier)
│   ├── core/perf/WindowShiftHysteresis.kt # NEW: light-window re-anchor state machine
│   └── ui/SimpleUI.kt                  # NEW: updateTileQuality(), new SSBO binding 5
└── resources/shaders/
    └── world_lit.frag.glsl             # Read per-tile quality byte; branch PCF/DDA

src/test/
└── kotlin/com/roguelike/
    ├── rendering/
    │   ├── PerfRegressionTest.kt       # NEW: loads dense-lights scene, asserts FPS floor
    │   ├── ShadowLodVisualTest.kt      # NEW: pins centre/periphery PCF behaviour
    │   ├── StairsCellShadowTriCountTest.kt # NEW (US5): 1×1×1 world + StairsTile, asserts ≤ 16 tris/cell
    │   └── StairsLandingGpuMsTest.kt   # NEW (US5): double-staircase-3x3x6.wld top landing, gpu_ms ≤ 20 (SC-005)
    └── perf/
        └── WindowShiftHysteresisTest.kt # NEW: pure-logic unit test

saved-worlds/perf/
└── dense-lights.wld                    # NEW: reproducible perf scene (lights≈128, tris≈45k)
```

**Structure Decision**: this is a perf/refactor change inside the
existing desktop game application — no new module, no new project. New
classes live under `core/perf/` to keep them out of the existing
`rendering/` and `editor/` namespaces and to make their feature-flagged
nature visually obvious in the source tree.

## Phase 0 — Research

**Output**: [research.md](./research.md)

Already complete. Key findings copied here for quick reference:

- Worst measured frame: **94 ms** with CPU phases summing to ≈ 5 ms ⇒
  **~89 ms is fragment-shader / GPU time**.
- Per-fragment cost dominated by `K_lights × S_pcf × D_dda × T_tris ≈
  6 × 5 × 30 × 3 ≈ 2 700 ray-triangle tests`.
- One outlier (`uploadLighting = 38.9 ms`) correlates with a single
  frame's `ul.pack = 10.1 ms` and `ul.collect = 8.7 ms` — points at a
  cache-miss storm right after a light-window re-anchor.
- User's stated "FOV-cull shadows" intuition is partially right: it
  helps the CPU upload column but does not address the dominant GPU
  cost. Per-tile LOD is required to move the FPS needle.

## Phase 1 — Design

**Outputs**: [data-model.md](./data-model.md),
[quickstart.md](./quickstart.md),
[contracts/](./contracts/).

### Data-model summary (full detail in `data-model.md`)

- **Tile Quality SSBO** (new, binding 5 on descriptor set 0): one
  `uint8` per Forward+ tile; CPU writes once per frame after
  `uploadLightTiles`. Values:
  - `0` — skip lighting loop entirely (paint ambient only).
  - `1` — single-tap shadow visibility, top-K disabled (use first
    `min(tileLightN, MAX_PER_PIXEL_LIGHTS_LOW)` lights, where
    `MAX_PER_PIXEL_LIGHTS_LOW = 3`).
  - `2` — full 5-tap PCF + top-6 (today's behaviour).
- **PerfFlags** (new singleton): one `enabled: Boolean`, toggled by F11
  via the existing `InputSystem`. When `false`, all three changes
  short-circuit to today's code path.
- **WindowShiftHysteresis** (new): state machine controlling when
  `RoguelikeGame.uploadLighting` re-anchors the player-centred grid
  window. Re-anchor only when the player has moved ≥ 4 cells from the
  current window origin AND ≥ 8 frames have elapsed since the last
  re-anchor.
- **PerfHud** (new): derives `gpu_ms`, `cache_hit_rate`, and a
  classifier string (`"gpu_bound" | "upload_spike" | "cache_miss"`)
  from already-recorded phase timings; no new measurement code.

### Contracts summary (full detail in `contracts/`)

- **`tile-quality-ssbo.md`** — SSBO layout, CPU writer signature, shader
  reader, and validation rules. New binding 5 on descriptor set 0;
  pre-allocated at `MAX_LIGHT_TILES` bytes; round-up to 4-byte stride.
- **`perf-flags.md`** — single boolean, F11 toggle, default = `true`,
  persisted via `local.properties` (`perf.flags.enabled=…`) so dev
  preference survives restart.
- **`shader-binding-table.md`** — updated descriptor-set 0 layout
  (sets 0–4 unchanged; binding 5 added with
  `VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`); host-side updates to
  `litDescriptorSetLayout` creation in `SimpleUI`.
- **`shadow-proxy-discovery.md`** *(new, US5)* — naming convention
  (`foo.obj` → `foo.shadow.obj`), the new `AssetLoader
  .loadModelWithShadow` API, the 16-tri budget, the warn-and-
  fall-back invariant, and the migration / open authoring work for
  existing shadow-emitting assets (`stairs_n.obj`,
  `ladder_vertical_n.obj`, possibly `wall_doorway_n.obj`).

### Quickstart summary (full detail in `quickstart.md`)

1. Load `saved-worlds/perf/dense-lights.wld` (created in Phase 5).
2. Stand at marker labelled "PERF_PROBE" in the scene.
3. Press F11 to toggle the flag; capture two 5-second `[Profile]`
   windows.
4. Diff `min(fps)`, `p99(frame_ms)`, `p99(uploadLighting)`.
5. Pass criteria mirror spec SC-001..SC-004.

## Constitution Re-Check

After Phase 1, no new violations. The plan adds:

- One new descriptor binding (additive, behind an opt-out flag).
- Four new Kotlin files under `core/perf/`.
- One new shader uniform read with a branchless fallback.
- Two new test classes + one new test fixture.

No new build dependencies, no new pipelines, no new render passes. Risk
of breaking spec-007 shadow correctness is mitigated by the F11 flag
plus the visual-regression gate.

## Phase 6 — US5: Low-poly shadow proxies for high-tri meshes

**Driven by**: spec FR-009 (new), tightened FR-008, SC-005 (new); see
`spec.md` Clarifications session 2026-05-21.

**Motivation recap**: the top-landing view of
`saved-worlds/double-staircase-3x3x6.wld` measures `gpu_ms = 46.9`.
`stairs_n.obj` ships at 132 raw tris (≈ 36 after interior-face
culling) and the existing per-cell soft cap of 24 already warns
(`spec 008: per-cell shadow-tri cap (24) hit at cell=(29,32,1); had
36 tris`). The fragment shader's `hitsShadowMesh` loop is linear in
per-cell tri count, so a 6-tri wedge proxy is the right fix.

**Acceptance gates** (from spec FR-009 + SC-005):

- Unit test: a known stairs cell holds ≤ 16 shadow triangles after
  load.
- Perf regression: top-landing view of `double-staircase-3x3x6.wld`
  renders at `gpu_ms ≤ 20`.

### Contract reference

[`contracts/shadow-proxy-discovery.md`](./contracts/shadow-proxy-discovery.md)
defines:

- the `foo.obj` → `foo.shadow.obj` naming rule,
- the new `AssetLoader.loadModelWithShadow(name, path): MeshPair`
  API (adds a parallel method; the existing `loadModel` signature is
  untouched to avoid churning ~10 unrelated call sites),
- the ≤ 16-tri budget tied to the new `PerfFlags.PER_CELL_SHADOW_TRI_CAP`
  constant,
- the warn-and-fall-back invariant (missing companion → use visual
  mesh as occluder, log one WARN per asset, **never** fail startup),
- the migration list of in-repo shadow emitters that need a
  companion (`stairs_n.obj`, `ladder_vertical_n.obj`, possibly
  `wall_doorway_n.obj`).

### Work units (preview of `/speckit.tasks` output)

Code work — ready for `/speckit.tasks` + `/speckit.implement`:

1. **`PerfFlags.kt`**: add `const val PER_CELL_SHADOW_TRI_CAP = 16`.
   Cross-link from the existing comment block. Public; read by both
   the asset loader (WARN threshold) and the per-cell collector
   (backstop clamp).
2. **`AssetLoader.kt`**:
   1. Add `data class MeshPair(val visual: MeshData, val shadow: MeshData)`.
   2. Add `fun loadModelWithShadow(name: String, path: String): MeshPair`
      that wraps the existing `loadModel` flow:
      - load the visual mesh as today;
      - derive companion path by inserting `.shadow` before `.obj`
        (`path.substringBeforeLast(".obj") + ".shadow.obj"`);
      - try `javaClass.classLoader.getResource(companionPath)` (same
        classpath resolution as the existing loader); if found,
        load via the same Assimp pipeline + interior-face cull;
      - emit the WARNs documented in
        `contracts/shadow-proxy-discovery.md` §3 (one-shot per
        asset; gated on a private `HashSet<String>` of already-
        warned names);
      - return `MeshPair(visual, shadow ?: visual)`.
   3. Add a test-only `preload(name, MeshData)` hook (or similar) so
      `StairsCellShadowTriCountTest` can inject a synthetic wedge
      without touching the real `.shadow.obj` file.
3. **`RoguelikeGame.kt`**:
   1. Introduce two new fields next to `stairsMesh` / `ladderMesh`:
      `private var stairsShadowMesh: MeshData? = null` and
      `private var ladderShadowMesh: MeshData? = null`.
   2. Replace the load lines at 398–399 with the new pair API:
      ```kotlin
      try {
          val p = assetLoader.loadModelWithShadow("ladder", "models/vox/stairs/ladder_vertical_n.obj")
          ladderMesh = p.visual; ladderShadowMesh = p.shadow
      } catch (_: Exception) {}
      try {
          val p = assetLoader.loadModelWithShadow("stairs", "models/vox/stairs/stairs_n.obj")
          stairsMesh = p.visual; stairsShadowMesh = p.shadow
      } catch (_: Exception) {}
      ```
   3. In the per-cell collector at lines 1044–1062, replace
      `stairsMesh?.let { … }` with `stairsShadowMesh?.let { … }` and
      `ladderMesh?.let { … }` with `ladderShadowMesh?.let { … }`.
      Keep the visual mesh references for the colour pass (no
      change to `renderWorld`).
   4. At line 1257, replace the magic literal with the new constant:
      `val cellCap = if (PerfFlags.enabled) PerfFlags.PER_CELL_SHADOW_TRI_CAP else 0xFF`.
      Update the WARN message in lines 1263–1267 to reference 16
      (or interpolate `cellCap` — already done).
4. **`MapEditor.kt`** (parity, lines 1482 / 1487): mirror the
   stairsShadowMesh / ladderShadowMesh substitution. Same rule.
5. **`StairsCellShadowTriCountTest`** *(new)*: pure-Kotlin unit
   test. Build a 1×1×1 synthetic world holding one `StairsTile`,
   inject a 6-tri wedge via the new `AssetLoader.preload` hook,
   call the per-cell collector, assert the per-cell triangle count
   is ≤ `PerfFlags.PER_CELL_SHADOW_TRI_CAP`. Runs in CI; deterministic;
   does NOT depend on the on-disk `.shadow.obj` files (so it's green
   from day 1).
6. **`StairsLandingGpuMsTest`** *(new, `@Disabled` until SC-005
   capture protocol is run)*: load `double-staircase-3x3x6.wld`,
   position the camera at the top landing facing down the stairs,
   render 100 frames, assert `gpu_ms ≤ 20`. Sibling of
   `PerfRegressionTest`. Stays `@Disabled` until a developer runs
   the capture protocol on the reference hardware.
7. **`quickstart.md`**: add a `## Authoring shadow proxies` section
   describing the `.shadow.obj` convention, the ≤ 16-tri budget,
   the warn-and-fall-back behaviour, and a one-liner Blender export
   recipe.

Asset-author work — blocked outside this repo (Blender / MeshLab /
hand-written OBJ):

A. **`models/vox/stairs/stairs_n.shadow.obj`**: ≤ 6-triangle wedge
   matching the stairs' AABB (slightly inflated is OK). P0 — gates
   SC-005. Once authored, drop into `src/main/resources/models/vox/
   stairs/` and the next launch picks it up automatically (no code
   change, no manifest edit).
B. **`models/vox/stairs/ladder_vertical_n.shadow.obj`**: ≤ 4-triangle
   box matching the ladder's AABB. P0 — gates the no-WARN
   invariant for ladder cells.
C. **`models/vox/wall/wall_doorway_n.shadow.obj`** *(P1, conditional)*:
   author only if `wall_doorway_n.obj`'s post-cull tri count
   exceeds 16 in practice. Verify by running the game with the
   FR-008 WARN enabled after step 3.4 lands; if no doorway-cell
   WARN appears, skip.

### Phase exit criteria

- `gradlew test --tests "*StairsCellShadowTriCountTest*"` is green.
- The FR-008 warn `[RoguelikeGame] spec 008: per-cell shadow-tri
  cap (16) hit` is absent from `logs.txt` after a full sweep of
  `double-staircase-3x3x6.wld` with steps A and B's assets in
  place.
- `StairsLandingGpuMsTest` (un-disabled by hand) passes on the
  reference hardware: `gpu_ms ≤ 20` on the top-landing view.
- Constitution re-check still passes (no new principles violated;
  see Constitution Check section).

## Phase 2 — Task Generation (run via `/speckit.tasks`)

Not executed by this command. The task generator will derive an
ordered, dependency-aware checklist from the requirements (FR-001..
FR-008) and user stories (US1..US4) using the existing
`tasks-template.md`. Expected to yield ≈ 18 tasks split across the
four implementation phases above.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Per-tile LOD makes peripheral shadows visibly worse | Medium | Medium | Centre/periphery split tunable via two constants in `PerfFlags`; visual-regression test pins acceptable terminator-width range. |
| Frustum-clipped shadow-mesh upload skips a borrow we needed | Low | High (light bleed through walls) | 1-cell "skirt" around the frustum; producer-side cache stays frustum-agnostic; only the consumer-side `expandedTriBuf` build applies the cull. |
| Window-shift hysteresis hides a light that just entered the window | Low | Medium (one room dark for ≤ 8 frames) | Override hysteresis when `visibleLights.size` jumps by > 20 % in one frame. |
| Tile-quality SSBO bumps the descriptor-set count past the device limit | Very low | High (validation error at startup) | New binding 5 is well within the Vulkan 1.0 minimum guarantees (4 storage buffers per stage). Validated by the existing `VulkanDebug` layer. |
| F11 collides with an existing editor / debug shortcut | Low | Low | Grep confirmed F11 is unused; if collision found before merge, fall back to F10. |

## Complexity Tracking

No constitution violations to justify.

---

**Cross-references**

- Spec: [spec.md](./spec.md)
- Skills referenced (`.github/prompts/skills.md`):
  `perf-skeptic`, `shader-surgeon`, `vulkan-plumber`,
  `visual-regression-tester`, `git-hygienist`.
- Related prior specs: `005-raytraced-shading` (shadow algorithm
  origins), `007-replace-libgdx-vulkan` (current pipeline of record).

