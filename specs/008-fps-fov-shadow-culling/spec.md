# Feature Specification: FPS Recovery in Multi-Light Scenes (FOV-Aware Shadow Culling)

**Feature Branch**: `feature/fps` (existing) → suggest renaming to `008-fps-fov-shadow-culling`
**Created**: 2026-05-20
**Status**: Investigation (no code changes yet)
**Author role**: Skeptical senior game-engine developer
**Input**: User report — *"Performance drops to 10 FPS when I navigate to an area with multiple light sources. I want to improve FPS, especially by not calculating shadows for textures/models that are outside the player field of view."*

---

## Clarifications

### Session 2026-05-21

- Q: Scope of the >16-tri low-poly occluder rule — stairs/ladder only, or any visual mesh? → A: Any cell whose visual mesh exceeds 16 triangles (general rule, scoped by tri count; stairs/ladder is just today's worst offender).
- Q: Enforcement when no low-poly companion is registered for a >16-tri visual mesh? → A: Warn at load and fall back to the visual mesh as the occluder (log one warning per asset; do not fail startup).
- Q: How does the engine discover the low-poly companion mesh? → A: File-naming convention — `foo.obj` → `foo.shadow.obj`, auto-resolved by `assetLoader.loadModel`. No manifest, no explicit registration call.
- Q: Interaction with existing FR-008 (per-cell shadow-tri cap of 24, warn-when-hit)? → A: Keep FR-008 as a backstop and tighten the cap from 24 → 16 to match the new threshold. The new FR addresses the root cause; FR-008's warning catches regressions (e.g. multiple proxies stacked in one cell).
- Q: Acceptance test for the new FR? → A: Both — a unit test asserting ≤16 tris in the shadow buffer for a known stairs cell after load, AND a perf-regression assertion that `double-staircase-3x3x6.wld` top-landing view runs at `gpu_ms ≤ 20` on the reference machine.

### Rationale (motivation for the 2026-05-21 clarifications)

A real performance problem was observed on the top landing of `double-staircase-3x3x6.wld` looking down the stairs: `gpu_ms = 46.9`. The shipped `stairs_n.obj` has ~36 triangles; with the existing per-cell shadow-tri cap of 24 (`RoguelikeGame.kt:1257`) the engine already emits warnings such as `spec 008: per-cell shadow-tri cap (24) hit at cell=(29,32,1); had 36 tris`. The fragment shader's `hitsShadowMesh` hot path loops over the per-cell triangle count directly (linear in `T_tris_per_cell` from the cost model in §0), so a 6-triangle wedge proxy is visually indistinguishable as a shadow occluder yet cuts per-cell triangle count by ~6×. The same argument applies to `ladder_vertical_n.obj`. This is the motivation for FR-009 below and the tightened FR-008 cap.

---

## 0. Diagnosis Before Prescription *(read this first)*

> **Skeptic's principle:** the user's symptom is real, but the user's proposed fix ("don't calculate shadows outside FOV") is only partially the right one. We do not commit code until measurement tells us where the time is actually being spent.

### Measured ground truth (from `logs.txt`, current branch)

Sample of worst-case frames in the problem area, taken from the `[Profile]` HUD trace:

| FPS  | frame ms | upload | render | ul.collect | ul.pack | ul.upload | lights | shadowTris |
|------|---------:|-------:|-------:|-----------:|--------:|----------:|-------:|-----------:|
| 10.6 |   94.1   | 4.3    | 0.5    | 1.1        | 1.0     | 0.5       |  75    | 33 504     |
| 10.9 |   92.1   | 10.8   | 1.1    | 2.4        | 5.0     | 1.1       |  73    | 45 792     |
| 11.4 |   88.0   | 38.9   | 2.7    | 8.7        | 10.1    | 1.1       |  35    | 21 456     |
| 11.8 |   84.9   | 6.3    | 0.7    | 1.9        | 1.4     | 0.9       | 128    | 84 456     |
| 12.5 |   80.2   | 4.5    | 0.4    | 1.2        | 1.4     | 0.4       |  44    | 28 572     |
| 13.1 |   76.6   | 5.4    | 0.6    | 1.7        | 1.4     | 0.8       |  66    | 47 376     |

CPU work in steady-state worst frames is **~4–7 ms** (`uploadLighting + renderWorld + collectLights ≈ 5 ms`). The frame is **70–95 ms**. **~85–90 % of the bad-frame budget is GPU time we don't account for** — i.e., the fragment shader. The two outliers (38.9 ms / 10.8 ms `uploadLighting`) are *CPU spikes during procedural stamping or light-window re-anchoring* — those need their own, separate treatment (see Option 5).

### Cost model of the fragment shader (`world_lit.frag.glsl`)

For every fragment of every world quad on screen, the shader runs:

```
                       per fragment
─────────────────────────────────────────────
  cost ≈  K_lights  ×  S_pcf_taps  ×  D_dda_cells  ×  T_tris_per_cell
        =    6      ×       5       ×      ~30     ×       ~3        ≈ 2 700 Möller-Trumbore tests
```

with the new wall-mesh shadow geometry, `T_tris_per_cell` is now ~3 on average (was ~0.5 before that change), pushing the per-fragment cost up roughly 6×. With a 1080p viewport that's ~2 megapixels × ~2 700 ray-triangle tests = **5.4 billion ray-triangle tests per frame** at peak. On an integrated GPU this is exactly the 80–90 ms we see.

### Honest assessment of the user's hypothesis

> *"Don't calculate shadows for textures/models that are outside of player field of view."*

- **For shading work**: already done implicitly. The rasteriser only generates fragments for triangles that pass clip+cull, so out-of-FOV meshes pay *zero* shadow cost. Adding more "FOV culling" on top of the rasteriser is a no-op for shading.
- **For shadow *occluder* upload**: this *is* a real win. We currently upload every wall/door/stair triangle within a 30×30×15 window centred on the player regardless of view direction. A ~120° horizontal FOV sees roughly 1/3 of those — so we could shrink the SSBO upload by ~50 % on a typical frame. **But that frees CPU time (uploadLighting), not GPU time.** It is *not* the fix for the 10 FPS itself.
- **The real fragment-shader win** comes from reducing one of `K_lights`, `S_pcf_taps`, `D_dda_cells`, or `T_tris_per_cell` per pixel — usually by adaptive quality, light pre-classification, or moving the shadow test out of the fragment shader. Pure FOV culling does not change any of those.

The rest of this document treats the user's request as: *"make a busy room run at >30 FPS, and use FOV awareness wherever it actually helps."*

### Success criteria (testable)

1. **SC-001 — Frame-time floor**: A scene reproducing the worst-case log frame (≈ 128 lights in the candidate set, ~45 k shadow triangles) MUST hold ≥ 30 FPS (≤ 33 ms/frame) on the developer's reference machine.
2. **SC-002 — No visual regression**: The fix MUST NOT introduce visible light bleeding through walls, missing shadows on floors/ceilings (the bug closed in spec 007), or PCF terminator artifacts beyond ±1 voxel.
3. **SC-003 — CPU spike ceiling**: `uploadLighting` p99 across any continuous 60 s play session MUST stay below 10 ms (today the log shows a 38.9 ms outlier).
4. **SC-004 — Measurable, not vibes**: every committed change MUST be accompanied by a before/after `[Profile]` log line captured in the same scene. We do not merge "feels faster".
5. **SC-005 — Stairs-landing GPU floor**: Standing on the top landing of `double-staircase-3x3x6.wld` looking down the stairs (the scene that today reports `gpu_ms = 46.9`) MUST render at `gpu_ms ≤ 20` on the developer's reference machine after FR-009 lands. This is the perf-regression assertion that gates FR-009 acceptance (alongside the unit test).

---

## 1. Five Options Ranked by Skeptic's ROI

Each option is rated on **expected FPS lift** (in the measured 10-FPS scene), **risk**, **implementation cost in dev-days**, and **whether it matches the user's stated FOV-culling intuition**.

### Option 1 — Per-tile shadow-quality LOD (recommended P0)

**Idea.** The Forward+ tile bins (16×16 px tiles, already in `world_lit.frag.glsl`) already know how many lights affect each tile. Extend the per-tile data with a *quality byte* set CPU-side:

- Tiles in the centre 30 % of the screen → full 5-tap PCF, full DDA distance.
- Tiles in the screen periphery → 1-tap (centre only), DDA capped at 12 cells.
- Tiles where `tileLightCount ≤ 1` → skip the per-pixel top-K insertion-sort entirely.

The shader picks the branch from one extra `uint` read; no extra UBO slot needed.

**Pros**
- Directly attacks the dominant fragment-shader cost in the measured profile.
- FOV-aware in the most truthful sense: peripheral vision really does tolerate softer shadows (eye physiology backs this up).
- Smooth degradation — no popping at the boundary because PCF tap count drops, not shadow presence.
- Zero CPU cost, no extra uploads.

**Cons**
- Requires shader edits and a host-side per-tile quality computation. Probably 2 dev-days.
- Edge case: very fast camera motion can make the centre LOD region feel "smear-y" if we go too aggressive. Need a clamp.
- A naïve implementation will cause artefacts in screenshots taken for visual tests — bake the LOD into the harness or disable it in test mode.

**Skeptic's verdict.** This is the *only* option that proportionally attacks the measured 80 ms/frame fragment cost. Do this first.

---

### Option 2 — Frustum-aware shadow-mesh upload (the user's actual request)

**Idea.** Today `uploadLighting` builds the shadow-triangle SSBO for the entire ~30×30 light window. Replace that with a *view-frustum-clipped* selection: any cell whose AABB is fully outside the view frustum contributes only to neighbours that *are* in-frustum (so wall-borrow into the visible cell still works), but its own triangles are skipped. Implement as one extra `camera.isBoxInFrustum` check per non-empty cell during the existing iteration.

**Pros**
- Matches the user's mental model exactly — "don't compute shadows for things behind me".
- Cuts the SSBO size by ~50–66 % for a typical 67° FOV, which:
  - Reduces `ul.collect` and `ul.upload` (today 1.7 ms + 0.8 ms in worst frames).
  - Reduces *cache pressure* in the shader's `shadowTris.tris[]` reads — a secondary fragment-shader win, hard to predict without measurement (10–25 % likely).
- Cheap to implement (~0.5 dev-day) and easy to A/B-test.

**Cons**
- **Will not move FPS from 10 → 30 on its own.** Best-case it knocks 1–2 ms off the CPU and maybe 5–15 ms off the GPU. Useful, but not the headline fix.
- Camera-rotation cache invalidation: the per-cell shadow-triangle cache currently keyed on `(x,y,z) + content` would have to also vary with frustum membership, or be left frustum-agnostic with an *additional* per-frame filter pass. Pick the latter to avoid cache thrash.
- Cells just outside the frustum that contribute borrows into in-frustum cells must still be processed. Need to widen the frustum test by 1 cell on each side (a "skirt") or accept missing wall shadows at screen edges.

**Skeptic's verdict.** Do this *after* Option 1. Quick win, low risk, but oversold by the user's request alone.

---

### Option 3 — Move shadow computation to a compute pre-pass with downsampled shadow targets

**Idea.** Run a single dispatch that computes, for each tile (or each 2×2 px block), the shadow visibility per *probe direction*, and bilinearly sample it in the world-lit pass. This is the standard "screen-space shadows in a half-res buffer" pattern.

**Pros**
- Decouples shadow cost from screen resolution. A 960×540 shadow target = 4× fewer samples than 1080p; combined with the existing per-pixel top-K cap this can drop the GPU bill 3-4×.
- Compute can use shared memory to amortise the per-tile triangle range fetches, which today each fragment fetches independently.
- Path forward to *temporal* reprojection (Option 4).

**Cons**
- Big architectural change: another VkImage, another descriptor set, another pipeline, another pass. 5–8 dev-days minimum.
- Half-res shadows soften edges (the user just complained about blocky shadows in spec 007 — soft edges may be OK or may regress that fix).
- Compute pipelines add a second skill to maintain.

**Skeptic's verdict.** Right answer long-term, wrong answer for "make it playable next week". Park as a P2 / next-feature item.

---

### Option 4 — Temporal accumulation (TAA-style reprojection of last frame's shadows)

**Idea.** Cache last frame's per-fragment shadow result keyed by a stable surface ID (cell index + face). When the camera moves, reproject and trust the cached value if the underlying voxel/light state hasn't changed; only run the full DDA on cache misses.

**Pros**
- For a stationary camera (or slow rotation) approaches 1 shadow tap per frame regardless of scene complexity. Could easily 4×–10× FPS in standing-still scenes.
- Stacks with all other options.

**Cons**
- High implementation complexity (motion vectors, history validation, neighbour-clamp anti-ghosting). ~8–12 dev-days.
- Ghosting / smearing when a door opens or a light moves — both are common in this game.
- Failure modes are subtle and hard to test deterministically — the visual-test harness in spec 006 would need history-buffer-aware fixtures.
- For a roguelike where the camera is constantly turning (mouse-look + WASD), the cache hit rate is much lower than for, say, a strategy game.

**Skeptic's verdict.** Don't. The implementation surface is too big for the expected payoff in this title's actual play pattern.

---

### Option 5 — Bound the worst case: split `uploadLighting` across frames, hard-cap per-cell shadow triangles

**Idea.** Two tightly scoped fixes targeting the spikes, not the steady state:
1. **Split `uploadLighting`** so the shadow-triangle rebuild for each cell window can be done incrementally — re-process only the slab that changed since last frame (player moved a cell). The 38.9 ms `uploadLighting` outlier and the 10.8 ms one both correlate with `ul.pack` jumping (5 ms vs typical 1 ms) — that's a per-cell cache miss storm right after the player crosses a window boundary. Triple-buffer the window or shift it in 4-cell granularity instead of always-on player position.
2. **Hard-cap `T_tris_per_cell` at, say, 24** during the host-side collector (already capped at 255 by the bit-packing format). Reject the longest-leg triangles or merge co-planar wall slabs into a single quad. Caps the worst-case fragment cost.

**Pros**
- Solves the *spike* problem (the 38.9 ms outlier), not the steady-state problem. Important because spikes are what players actually notice as "stutter".
- Low implementation risk: both changes are localised and can be feature-flagged.
- Combined effort ~2 dev-days.

**Cons**
- Will *not* move steady-state FPS measurably (only ~1 ms back on the CPU column).
- Triangle merging can cause subtle shadow shape changes near wall corners.
- Window-shift granularity may make the boundary visible if a light enters/leaves the window between shifts.

**Skeptic's verdict.** Do alongside Option 1. Cheap insurance against the long-tail spikes.

---

### Recommended sequencing

```
P0 (1 sprint) :  Option 1  (per-tile shadow LOD)   → recover steady-state FPS to ≥ 30
P1 (next)     :  Option 2  (frustum-clipped SSBO)  → cut CPU upload + cache footprint
P1 (next)     :  Option 5  (spike bounding)        → kill the 38.9 ms outliers
P2 (later)    :  Option 3  (half-res shadow pass)  → headroom for richer lighting features
P3 (skip)     :  Option 4  (temporal reprojection) → not worth it for this game's camera
```

---

## 2. User Scenarios *(mandatory)*

### User Story 1 — Walking into a fully-lit hub room (Priority: P1)

As a player I can walk into a room with 30+ light sources and the frame rate stays above 30 FPS, so movement remains responsive.

**Independent Test.** Load `saved-worlds/world.wld` (or the procedural scene that reproduces the log's `[LightWindow] ... lights=66+`). Walk to the position approximately `(20, 46, 4)`. Record `[Profile]` for 5 seconds. Assert `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33`.

**Acceptance scenarios**

1. **Given** a saved scene reproducing the log's worst frame (`lights=128, shadowTris=84 456`), **When** the player stands still and rotates the camera 360°, **Then** the HUD reports `fps ≥ 30` continuously and no frame exceeds 33 ms.
2. **Given** the player crosses a light-window boundary (the event that today triggers the 38.9 ms `uploadLighting` spike), **When** the boundary is crossed, **Then** the next frame's `uploadLighting` value is ≤ 10 ms.
3. **Given** the per-tile shadow LOD is enabled, **When** the camera is stationary and the player looks at a textured wall in the screen centre, **Then** the visible shadow terminator quality matches today's (5-tap PCF, no perceptible regression).

---

### User Story 2 — Skeptic-friendly profiling overlay (Priority: P2)

As a developer investigating performance, I can see in the HUD which option's cost-line each frame fell into (steady GPU bound, CPU upload spike, cache miss storm), so I can validate whether a change actually helped.

**Independent Test.** Toggle a dev-only HUD line that surfaces three derived numbers:

```
gpu_ms ≈ frame_ms − sum(cpu_phases)
cache_hit_rate = 1 − new_cell_misses / cells_touched
per_pixel_cost_estimate = K × S × D × T
```

These let you tell at a glance which of Options 1–5 each frame is testing.

**Acceptance scenarios**

1. **Given** Option 1 (per-tile LOD) is enabled, **When** the camera centres on a light-dense wall, **Then** `gpu_ms` drops by ≥ 50 % vs. baseline at the same scene location.
2. **Given** Option 2 (frustum-clipped SSBO) is enabled, **When** the player rotates 180°, **Then** `shadowTris` reported in the HUD drops by ≥ 40 % within one frame.

---

## 3. Requirements *(mandatory)*

### Functional

- **FR-001**: The system MUST expose a per-tile shadow-quality byte (`uint`) computed CPU-side once per frame and read by `world_lit.frag.glsl`.
- **FR-002**: The fragment shader MUST select between full PCF (5 taps) and reduced PCF (1 tap) based on the per-tile quality byte and the tile's screen-space distance from the centre.
- **FR-003**: The shadow-triangle SSBO build (`uploadLighting`) MUST skip cells whose AABB lies entirely outside the camera view frustum, **except** when the cell's wall geometry is borrowed into an in-frustum neighbour (preserve the existing borrow correctness).
- **FR-004**: The player-window light grid MUST shift in granularity ≥ 4 cells (not every frame) so that boundary crossings do not invalidate the entire shadow cache at once. Per-cell content-hash invalidation MUST still work for door / wall changes.
- **FR-005**: A new HUD line MUST report `gpu_ms`, `cache_hit_rate`, and the dominant cost driver name (`gpu_bound`, `upload_spike`, or `cache_miss`).
- **FR-006**: All performance changes MUST be feature-flagged behind a single in-game toggle (key `F11`) so before/after profiling captures are deterministic.
- **FR-007**: A repeatable performance test scene MUST be saved under `saved-worlds/perf/dense-lights.wld` and used by an integration test that fails if `min(fps) < 30` over a 5-second camera-rotation script.
- **FR-008**: The per-cell shadow-triangle count packing (8 bits, max 255) MUST be re-validated; the host-side collector MUST enforce a per-cell soft cap of **16 triangles** (tightened from the prior 24 to match FR-009's threshold) and, if the cap is ever hit, the build process MUST log a warning naming the offending cell. FR-008 acts as a backstop that catches regressions where multiple low-poly proxies stack in a single cell; the root-cause fix for oversized single meshes lives in FR-009.
- **FR-009**: Shadow geometry MUST use a separate low-poly occluder mesh for **any** visual mesh whose triangle count exceeds **16** when that mesh is emitted into a cell's shadow-triangle buffer (the rule originated from the stairs/ladder case but is not limited to it). Discovery is by file-naming convention: for a visual mesh loaded from `path/foo.obj`, the loader MUST look for `path/foo.shadow.obj` and, when present, substitute it as the occluder during shadow-triangle collection. When the companion file is absent and the visual mesh exceeds 16 triangles, the loader MUST log a single warning per asset and fall back to using the visual mesh as the occluder (startup MUST NOT fail). The proxy is at the asset author's discretion (no fixed silhouette-containment budget); FR-008's per-cell cap remains the safety net. Acceptance is verified by **both** (a) a unit test that loads a known stairs cell (`stairs_n` + its `.shadow.obj` companion) and asserts ≤ 16 triangles in the shadow buffer for that cell, and (b) a perf-regression assertion on `double-staircase-3x3x6.wld` from the top-landing viewpoint that `gpu_ms ≤ 20` on the reference hardware. Full loader-level binding contract: [`contracts/shadow-proxy-discovery.md`](./contracts/shadow-proxy-discovery.md). Data-model entry: [`data-model.md`](./data-model.md) §10. Plan phase: `plan.md` Phase 6 — US5.

### Non-functional

- **NFR-001**: Steady-state FPS in the reference dense-light scene MUST be ≥ 30; p99 frame time MUST be ≤ 33 ms.
- **NFR-002**: p99 `uploadLighting` MUST be ≤ 10 ms across any continuous 60-second sample.
- **NFR-003**: No spec-007 visual regression: the existing visual-test suite under `src/test/kotlin/com/roguelike/rendering/` MUST still pass.
- **NFR-004**: The new code MUST add no more than 1 ms of CPU work per frame on the steady-state baseline.

### Out of scope (explicitly)

- Temporal reprojection (Option 4) — too costly for this title's camera pattern.
- A full compute-based shadow pass (Option 3) — tracked as a future spec.
- Changing the per-pixel lighting model (Lambertian + attenuation curve).
- Multi-bounce / global illumination.

---

## 4. Key Entities *(updated for this spec)*

- **TileShadowQuality**: new per-tile byte (`uint8`) in a side SSBO. `0` = skip shading entirely, `1` = single-tap PCF, `2` = full 5-tap PCF.
- **FrustumCullState**: cached planes of the current view frustum + a per-cell `inFrustum` bitset reused by `uploadLighting` to skip shadow-triangle emission.
- **WindowShiftHysteresis**: state machine governing when the player-centred grid window is re-anchored (≥ 4-cell threshold + 8-frame cooldown).
- **PerfHUD**: extended HUD model exposing `gpu_ms`, `cache_hit_rate`, and the dominant-cost classification.

---

## 5. Risks & Open Questions

1. **Risk** — Option 1's centre/periphery split assumes a single dominant focal region. If the player has a mini-map or aim cursor not at the centre, we may downgrade exactly the tile they're staring at. **Mitigation**: make the high-quality region a configurable "attention region" rather than hard-coded centre.
2. **Risk** — Frustum-clipped SSBO upload may cause the `shadowCellCache` hit rate to plummet on every camera turn. **Mitigation**: do the frustum filter on the *consumer* side (`expandedTriBuf` rebuild) rather than on the producer (`shadowTriBuf` / cache).
3. **Open** — Is the 38.9 ms `uploadLighting` outlier dominated by `ul.pack` (CPU-side allocation/sort) or by the SSBO write? `ul.upload` was only 1.1 ms in that frame, so it's CPU-side. Confirm with a sampling profiler before assuming Option 5's split-frame idea is the right fix.
4. **Open** — The shader's `MAX_PER_PIXEL_LIGHTS = 6` is empirical. Should it scale with quality byte too? Probably yes — peripheral tiles could use 3 instead.
5. **Open** — Should we measure on integrated GPU vs discrete? The 10 FPS report is likely on integrated. State the target hardware in the success criteria.

---

## 6. Pointers for the implementer (do not skip)

- The hot file is `src/main/resources/shaders/world_lit.frag.glsl` — read it end-to-end before touching anything else. The top-K insertion sort at lines 332–376 is itself non-trivial cost.
- The host-side counterpart lives in `RoguelikeGame.uploadLighting` (lines 684–1170) plus `SimpleUI.updateLighting` (the SSBO uploader).
- The Forward+ tile bin code (`uploadLightTiles`, `tileLightCount`, `tileLightIndices`) already exists — extending it with a quality byte is the natural insertion point.
- The shadow-cell cache (`shadowCellCache`, `shadowKeyFor`) is the part most likely to break under any frustum-aware change; keep it producer-side and untouched.
- `Camera.isBoxInFrustum` and `isSphereInFrustum` are already implemented and used elsewhere — no new math required.

---

*End of specification.*

