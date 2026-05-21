# Phase 1 — Data Model

This document defines the data shapes added or modified by spec 008.
Every entry maps 1:1 onto a Kotlin class, an SSBO/UBO field, or an
existing struct field; "new" entries are created by this feature.

## 1. PerfFlags *(new singleton)*

**File**: `src/main/kotlin/com/roguelike/core/perf/PerfFlags.kt`

```kotlin
object PerfFlags {
    /**
     * Master toggle for all spec-008 changes. When false, every
     * change short-circuits to the spec-007 behaviour, so the F11
     * key provides a deterministic A/B for profiling.
     *
     * Defaults to true so end-users get the perf benefits without
     * a setting; the local.properties override
     *   perf.flags.enabled=false
     * disables them at startup for capture sessions.
     */
    @Volatile var enabled: Boolean = true

    /** Number of taps used by `shadowVisibility` at quality byte 1.
     *  Quality byte 2 keeps the spec-007 5-tap behaviour. */
    const val PCF_TAPS_LOW: Int = 1

    /** Per-pixel light cap at quality byte 1. Quality byte 2 keeps
     *  MAX_PER_PIXEL_LIGHTS=6 (the shader constant). */
    const val MAX_PER_PIXEL_LIGHTS_LOW: Int = 3

    /**
     * Fraction of the screen's smaller axis treated as the
     * "centre / high-quality" region. Tiles whose centre is within
     * `centreFraction * min(sw, sh) / 2` of the screen midpoint get
     * quality byte 2; everything else gets quality byte 1.
     */
    @Volatile var centreFraction: Float = 0.40f
}
```

**Mutation rules**

- `enabled` toggled by F11 (one frame of latency acceptable).
- `centreFraction` is `@Volatile` so a debug overlay or hot-reload may
  tweak it; not currently exposed in UI.
- No persistence in code: dev preference is read from
  `local.properties` at startup (see `quickstart.md`).

## 2. TileShadowQuality *(new SSBO content)*

**Logical shape**

```
ssbo TileQuality {
    uint  data[MAX_LIGHT_TILES];  // one byte per tile, packed 4-per-uint
}
```

**Physical layout**

- One `uint8` per Forward+ tile. With `MAX_LIGHT_TILES = 128 × 128 =
  16 384` tiles the SSBO is exactly 16 KiB; round-up keeps the SSBO
  aligned to 4-byte stride.
- Index formula matches existing tile bins:
  `tIdx = ty * tilesX + tx` where `tx = gl_FragCoord.x /
  LIGHT_TILE_SIZE`, `ty = gl_FragCoord.y / LIGHT_TILE_SIZE`.
- Byte values:
  - `0` — fragment runs ambient-only path (skip lights loop entirely).
    Used for tiles with `tileLightCount == 0` *and* no on-screen
    light influence detected.
  - `1` — reduced quality: `PCF_TAPS_LOW` taps, `MAX_PER_PIXEL_LIGHTS_LOW`
    lights.
  - `2` — full quality: today's 5-tap PCF, 6 lights.

**Host writer (in `SimpleUI`)**

```kotlin
fun updateTileQuality(qualities: ByteArray, tileCount: Int)
```

- Allocates the SSBO lazily on first call (size `MAX_LIGHT_TILES` bytes,
  rounded to 16-byte alignment per Vulkan rules).
- Copies `qualities[0..tileCount)` into the mapped pointer; zeroes the
  tail in-place to avoid stale data on resize.

**Producer (in `RoguelikeGame.uploadLightTiles`)**

After the existing tile binning fills `tileLightCount`, run a second
pass:

```
for each tile t:
    if !PerfFlags.enabled:           q[t] = 2
    else if tileLightCount[t] == 0:  q[t] = 0
    else if distFromCentre(t) > R:   q[t] = 1
    else:                            q[t] = 2
```

Where `R = centreFraction * min(sw, sh) / 2 / LIGHT_TILE_SIZE`
(distance measured in tiles).

**Shader reader (in `world_lit.frag.glsl`)**

```glsl
layout(set = 0, binding = 5) readonly buffer TileQuality {
    uint packed[]; // 4 byte-quality values per uint, little-endian
} tileQuality;

uint readQuality(int tIdx) {
    uint w = tileQuality.packed[uint(tIdx) >> 2u];
    uint shift = uint(tIdx & 3) * 8u;
    return (w >> shift) & 0xFFu;
}
```

The reader branches **once** per fragment; both branches still pay for
the same control-flow path (Vulkan SIMD divergence within a 16×16 tile
is zero by construction — entire tile shares one quality byte).

## 3. WindowShiftHysteresis *(new state machine)*

**File**: `src/main/kotlin/com/roguelike/core/perf/WindowShiftHysteresis.kt`

```kotlin
class WindowShiftHysteresis(
    val cellThreshold: Int = 4,
    val frameCooldown: Int = 8
) {
    private var currentOriginX = Int.MIN_VALUE
    private var currentOriginY = Int.MIN_VALUE
    private var currentOriginZ = Int.MIN_VALUE
    private var framesSinceShift = Int.MAX_VALUE

    /**
     * Returns the origin to actually use this frame: either the
     * supplied [desired] (a shift happened) or the previously
     * frozen origin (held by hysteresis). Updates internal state.
     *
     * Override hysteresis when [forceShift] is true — used when
     * the per-frame light count jumps by > 20 % (e.g. a new room
     * just popped into the candidate set).
     */
    fun resolve(
        desired: Triple<Int, Int, Int>,
        forceShift: Boolean = false
    ): Triple<Int, Int, Int> { … }
}
```

Pure logic, fully unit-testable in
`WindowShiftHysteresisTest.kt` without any Vulkan dependency.

## 4. PerfHud *(new derived metrics)*

**File**: `src/main/kotlin/com/roguelike/core/perf/PerfHud.kt`

Derived fields (no new measurement):

| Field | Formula |
|---|---|
| `gpu_ms` | `frame_ms − (interaction + procedural + collectLights + uploadLighting + renderWorld)` |
| `cache_hit_rate` | `1 − shadowCellCacheMissCount / cellsTouchedCount` (counters added to existing cache code) |
| `classifier` | `"gpu_bound"` if `gpu_ms > 50 % of frame_ms`; `"upload_spike"` if `uploadLighting > 10 ms`; `"cache_miss"` if `cache_hit_rate < 0.5`; else `"steady"` |

Emitted on the existing HUD line under the `[Profile]` prefix:

```
[Profile] fps=… frame=…  …  gpu_ms=…  cache_hit=…%  driver=gpu_bound
```

## 5. Shader binding update *(modified)*

Set 0 now has bindings 0–5:

| Binding | Type | Owner | Purpose |
|---|---|---|---|
| 0 | UBO | `lighting` | Lights + ambient + grid params (existing) |
| 1 | SSBO | `grid` | Occupancy + per-cell shadow-tri range (existing) |
| 2 | SSBO | `shadowTris` | Packed shadow triangles (existing) |
| 3 | SSBO | `tileLightCount` | Forward+ per-tile light counts (existing) |
| 4 | SSBO | `tileLightIndices` | Forward+ per-tile light indices (existing) |
| **5** | **SSBO** | **`tileQuality`** | **Per-tile quality byte (new)** |

Host side: extend `litDescriptorSetLayout` with one extra
`VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`, write into
`litDescriptorSet` once at init and on swap-chain resize.

## 6. Frustum-cull "skirt" parameter *(modified behaviour in
`RoguelikeGame.uploadLighting`)*

No new persisted data; just a constant:

```kotlin
private companion object {
    /** Cells outside the view frustum that are still emitted so
     *  their wall meshes can be borrowed into in-frustum
     *  neighbours. 1 cell is enough because wall borrow-into only
     *  reaches the immediate neighbour. */
    const val FRUSTUM_SKIRT_CELLS: Int = 1
}
```

The producer (cache fill) stays frustum-agnostic so the
`shadowCellCache` hit rate is unaffected; the **consumer** that
expands triangles into `expandedTriBuf` checks
`camera.isBoxInFrustum(cellAabb.expand(FRUSTUM_SKIRT_CELLS))` and
skips the cell when it fails.

## 7. Saved performance scene *(new file)*

**File**: `saved-worlds/perf/dense-lights.wld`

- Reproduces the worst observed frame: ≈ 128 candidate lights, ≈
  45 000 shadow triangles, window ≈ 30 × 30 × 15 cells.
- Player spawn marked with a `PERF_PROBE` node tag.
- Loaded directly by `PerfRegressionTest` and by the manual quickstart
  capture flow.

## 8. Test fixtures *(new)*

| File | Purpose |
|---|---|
| `PerfRegressionTest.kt` | Loads `dense-lights.wld`, runs a 5-second 360°-rotation camera script, asserts `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33`. |
| `ShadowLodVisualTest.kt` | Renders a known scene with `PerfFlags.enabled = true`; samples a centre tile (expects 5-tap PCF terminator width) and a corner tile (expects 1-tap step). |
| `WindowShiftHysteresisTest.kt` | Pure-logic table-driven test of the state machine. |

## 9. What we deliberately did **not** add

- No new lighting-model fields. Lambertian + radius attenuation
  unchanged.
- No new pipeline variant. Set-0 layout grows; pipeline count stays
  at 5 (`AMBIENT`, `STENCIL_FRONT`, `STENCIL_BACK`, `LIT`,
  `LINE_DEBUG`).
- No new render pass. The lit pass keeps its single subpass.
- No motion vectors, no history buffer (Option 4 explicitly skipped).
- No half-res shadow texture (Option 3 explicitly deferred).

## 10. Shadow-proxy mesh discovery *(new, US5 / FR-009)*

Every visual mesh MAY have an opt-in low-poly companion that the
loader auto-substitutes whenever that mesh is emitted into the
per-cell shadow-triangle buffer. See
[`contracts/shadow-proxy-discovery.md`](./contracts/shadow-proxy-discovery.md)
for the full binding contract; this section captures only the data-
shape surface.

### 10.1 Asset-pair concept

```
<dir>/<name>.obj            ← visual mesh (rendered to colour buffer; FR-007 behaviour)
<dir>/<name>.shadow.obj     ← OPTIONAL occluder used by the shadow-tri collector
```

The companion file is **discovered by file-name convention** at
`AssetLoader.loadModelWithShadow` call time. No manifest, no
registration call, no opt-in flag at the consumer site.

### 10.2 Resolution order (loader contract)

For a call `loadModelWithShadow(name, path)`:

1. Load the visual mesh from `path` exactly as the existing
   `loadModel(name, path)` does (same Assimp pipeline, same
   `cullInteriorFaces` pass).
2. Derive the companion path:
   `companionPath = path.substringBeforeLast(".obj") + ".shadow.obj"`.
3. Attempt to resolve `companionPath` via the classpath / absolute-
   path rules used for the visual mesh.
4. **Hit** — load the companion through the same pipeline; return
   `MeshPair(visual, shadow)`.
5. **Miss** — return `MeshPair(visual, visual)` (same object
   reference). If `visual.indices.size / 3 > PerfFlags.PER_CELL_SHADOW_TRI_CAP`,
   emit a one-shot WARN naming the **visual** path so the asset
   author knows what to author.

The fall-back **MUST NOT fail startup**. A missing companion is a
performance issue, never a correctness issue.

### 10.3 The new named constant

Added to `PerfFlags` (see §1):

```kotlin
object PerfFlags {
    // … existing fields …

    /**
     * Per-cell shadow-triangle soft cap (FR-008 backstop) AND the
     * loader-side budget for `.shadow.obj` companions (FR-009).
     *
     * The fragment shader's `hitsShadowMesh` loop is linear in this
     * count. 16 was chosen empirically: stairs reduced from 36 → 6
     * tris drops top-landing `gpu_ms` from 46.9 → < 20 (SC-005).
     *
     * Read by:
     *   - AssetLoader.loadModelWithShadow (WARN threshold for both
     *     missing and oversized companions).
     *   - RoguelikeGame.kt:1257 (per-cell collector clamp, replacing
     *     the prior magic literal 24).
     *   - MapEditor.kt (editor-preview parity).
     */
    const val PER_CELL_SHADOW_TRI_CAP: Int = 16
}
```

Cross-reference: `FR-008` in `spec.md` is the **backstop** (per-cell
collector clamp); `FR-009` is the **root-cause** path (loader
discovers `.shadow.obj`). They share `PER_CELL_SHADOW_TRI_CAP` as
the single source of truth, so raising the cap is a one-line change.

### 10.4 New `RoguelikeGame` fields

Added next to the existing `stairsMesh` / `ladderMesh` declarations
(scope: `private var` on `RoguelikeGame`):

```kotlin
private var stairsMesh: MeshData? = null         // visual (existing)
private var stairsShadowMesh: MeshData? = null   // occluder (new, US5)
private var ladderMesh: MeshData? = null         // visual (existing)
private var ladderShadowMesh: MeshData? = null   // occluder (new, US5)
```

Populated together at init via the new `loadModelWithShadow` API.
The per-cell collector at `RoguelikeGame.kt:1044–1062` reads the
`*ShadowMesh` field; the colour-pass renderer keeps reading the
visual `*Mesh` field. The two references are equal-by-object when
the `.shadow.obj` companion is absent.

`MapEditor.kt` mirrors the same pair for editor-preview parity.

### 10.5 Fall-back invariant

When a visual mesh has > 16 tris **and** has no companion:

1. `loadModelWithShadow` emits a one-shot WARN naming the offending
   **visual** asset path (so the author knows the file to author).
2. The per-cell collector silently passes the visual mesh through.
3. The FR-008 backstop at `RoguelikeGame.kt:1257` then clamps the
   per-cell list to `PerfFlags.PER_CELL_SHADOW_TRI_CAP` tris,
   emitting its own one-shot WARN naming the offending **cell**.

Together the two WARNs tell an investigating developer both *what*
asset is to blame and *where* it manifests. Neither WARN is fatal.

### 10.6 Test fixture additions

Extends §8 (Test fixtures):

| File | Purpose |
|---|---|
| `StairsCellShadowTriCountTest.kt` | Pure-Kotlin unit test. Builds a 1×1×1 synthetic world with one `StairsTile`, injects a faked 6-tri wedge through a test-only `AssetLoader.preload` hook (so the test is green even before the on-disk `.shadow.obj` exists), runs the per-cell collector, asserts `triCount ≤ PerfFlags.PER_CELL_SHADOW_TRI_CAP`. |
| `StairsLandingGpuMsTest.kt` | Sibling of `PerfRegressionTest`. Loads `saved-worlds/double-staircase-3x3x6.wld`, positions the camera at the top landing facing down the stairs, renders 100 frames, asserts `gpu_ms ≤ 20` (SC-005). Ships `@Disabled` until the SC-005 capture protocol is run on the reference machine. |


