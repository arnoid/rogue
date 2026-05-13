# Research: Smooth Cone Light Edges

**Feature**: 002-smooth-cone-edges
**Date**: 2026-05-12

## Root Cause Analysis

Three independent code paths all evaluate cone inclusion at cell granularity, producing
the visible staircase pattern aligned with the tile grid.

### 1. `LightingSystem.applyLight()` (coarse `LightMap3D`)

```kotlin
for (tx in minX..maxX) {
    for (ty in minY..maxY) {
        val dx = (tx - sx).toFloat()   // ← integer cell offset
        val dy = (ty - sy).toFloat()   // ← integer cell offset
        ...
        val dot = nx * fx + ny * fy + nz * fz
        if (dot < cosHalf) continue    // ← hard binary cutoff
    }
}
```

- Direction vector is computed from **integer cell indices** — identical to cell-center
  positions in the cell-centered coordinate convention.
- Hard cutoff `dot < cosHalf` means every cell is either fully lit or fully dark.
- A diagonal cone boundary (e.g., 45°) produces a staircase that alternates lit/dark
  cells following the grid exactly.

### 2. `DynamicLighting.computeMaskMultiSample()`

```kotlin
if (isCone) {
    val vx = px - l.pos.x   // ← continuous, BUT...
    ...
    if (dot < coneCos) continue   // ← binary exclusion per sample
}
// Result: bit = 1 if ANY sample passes cone + LOS
```

- Uses 5 continuous sample points per cell (4 corners + center).
- Returns a **binary bit** per light: visible (1) if ANY sample is inside cone AND
  has clear LOS.
- At the cone boundary, cells whose nearest corner is inside the cone get full
  PointLight intensity; cells with NO corners inside the cone are dark.
- The binary cell decision still produces a staircase — softer than LightingSystem
  (~0.5-cell resolution) but still grid-aligned.

### 3. `SurfaceLighting.floor()` / `SurfaceLighting.cell()`

```kotlin
fun floor(x: Int, y: Int, z: Int, ...): FloatArray =
    sample(x.toFloat(), y.toFloat(), z - 0.5f + 0.02f, ...)
    //     ^^^ single sample at integer cell center
```

- Single sample point at the integer cell center.
- Hard cone cutoff: `if (dot < cosHalf) continue`.
- Most severe stepping — identical to the coarse `LightingSystem` pattern.

---

## Decision 1: Fix Strategy — Smooth Angular Falloff

**Decision**: Replace hard cone cutoffs with a smooth angular falloff over a configurable
penumbra zone. Cells inside the cone receive full intensity; cells in the penumbra zone
receive linearly interpolated intensity; cells outside the penumbra receive no light.

**Formula**: For a cone with half-angle θ and penumbra angle φ:
```
cosHardEdge = cos(θ)
cosSoftEdge  = cos(θ + φ)    // slightly wider outer boundary

factor = clamp((dot - cosSoftEdge) / (cosHardEdge - cosSoftEdge), 0, 1)
```
A `factor` of 1.0 means fully inside; 0 means fully outside.

**Default penumbra**: 3° (enough to eliminate visible stepping at typical dungeon scales
without creating a visually distracting soft edge).

**Rationale**:
- Requires no architectural changes to `LightingSystem` or `SurfaceLighting`.
- Works in the cell-based coordinate frame — no sub-cell sampling infrastructure needed.
- Eliminates the staircase by making the intensity of boundary cells proportional to how
  far inside the cone they are.
- Matches the existing range falloff pattern (which also uses a linear gradient).

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Per-pixel cone test (custom GLSL shader) | Requires writing a custom LibGDX shader; significant effort; out of scope for this fix |
| Sub-cell rasterisation (compute fraction of cell area inside cone) | Requires wedge-vs-rectangle intersection math; overkill for the visual improvement needed |
| More sample points per cell | Reduces but does not eliminate the staircase; 16 samples would still produce ~1/16-cell stepping |
| Hard cutoff with anti-alias post-pass | No standard anti-aliasing pass in this engine; would require architectural additions |

---

## Decision 2: `DynamicLighting` — Fractional Cone Coverage

**Decision**: Change `computeMaskMultiSample()` so that CONE lights accumulate a fractional
coverage score (0.0–1.0) rather than returning a binary bit. This score is stored per-light
and applied as a multiplier to the PointLight intensity in `buildEnv()`.

**Implementation**:
- In `computeMaskMultiSample()`, for CONE lights: count how many of the N sample points
  pass the soft cone falloff test (using the same `softConeFactor()` helper) and accumulate
  a weighted sum. Normalise to [0,1].
- Store this as a `FloatArray` (`coneScales`) alongside the existing `Int` mask.
- In `buildEnv()`, scale each CONE PointLight's intensity by `coneScales[i]`.

**Rationale**:
- The CPU visibility mask determines which cells "see" the light. For the boundary cells
  to receive proportional light, the GPU PointLight intensity must be scaled accordingly.
- The `DynamicLighting` architecture (binary mask + PointLight GPU) cannot do per-pixel
  cone attenuation without a custom shader. Scaling the PointLight intensity at cell
  granularity is the most practical option.
- 5 sample points give 6 discrete levels (0/5, 1/5, 2/5, 3/5, 4/5, 5/5); combined with
  the smooth factor per sample, this produces a fine-grained gradient.

---

## Decision 3: Shared `softConeFactor()` Helper

**Decision**: Extract the soft falloff formula into a package-private helper function in
`core.systems`:

```kotlin
// Returns 0f if completely outside soft boundary,
// 1f if fully inside hard boundary, or a linear interpolation between.
internal fun softConeFactor(dot: Float, cosHardEdge: Float, cosSoftEdge: Float): Float =
    ((dot - cosSoftEdge) / (cosHardEdge - cosSoftEdge)).coerceIn(0f, 1f)
```

All three systems call the same helper, ensuring consistent visual behavior.

**Rationale**: Avoids copy-pasting the clamped linear interpolation formula across three
files. A single shared function is easier to tune (change the default penumbra) and test.

---

## Decision 4: `LightDef.coneFeatherDegrees` — Configurable Per-Light Penumbra

**Decision**: Add an optional `coneFeatherDegrees: Float = 3f` field to `LightDef` (or
its equivalent data class). When `coneFeatherDegrees = 0`, the cone falls back to a hard
cutoff (legacy behavior). A positive value enables the soft edge.

**Rationale**: Different light sources may want different edge softness (a magical beam
might be sharp; torch fire might be diffuse). Using a per-light field follows the
existing `LightDef` data-driven pattern (Principle IV).

**Alternatives considered**:
- Global constant: simpler but can't be tuned per-light type — rejected in favour of
  data-driven design.
- Hard-coded 3°: acceptable default but not configurable — rejected.

---

---

## Decision 5: Floor Tile Light Occlusion (FR-007)

**Decision**: Add `blocksLight(): Boolean` to the `Tile` interface (default `= isBlocking()`)
and override it in `FloorTile` to return `true`. Change `TileRenderer.worldSpaceBoxes()` to
filter by `tile.blocksLight()` instead of `tile.isBlocking()`.

**Root cause**: `worldSpaceBoxes()` uses `tile.isBlocking()` to decide which tiles contribute
AABBs to `BvhOccluder`. `FloorTile.isBlocking()` returns `false` (floor is traversable), so
floor AABBs were never added — light rays passed through floors.

**Rationale**:
- Adds a single method to the `Tile` interface (`core.model`) with a safe default — no
  behavior change for walls, doors (open/closed still work correctly).
- Follows the existing `ItemDef.blocksLight` philosophy: each type declares its light-blocking
  behavior independently of its movement-blocking behavior.
- Any future tile type that should be transparent (glass floor, grating) simply overrides
  `blocksLight()` to return `false` without touching movement logic.

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| `ItemCatalog["FloorTile"]?.blocksLight` lookup | Tile type strings aren't guaranteed to be registered in `ItemCatalog` (catalog is for interactive items); adds implicit dependency |
| Hard-code floor tiles in `worldSpaceBoxes()` | Brittle enumeration; won't handle future tile types; violates Principle IV (data-driven) |
| New `blocksLight` property on `WorldNode` | Node-level granularity is wrong; light-blocking is a tile concern, not a node concern |

---

## Unchanged Scope

- `LightingSystem.losClear3D()` — LOS algorithm is unchanged.
- `BvhOccluder` — AABB occlusion structure unchanged; only the AABB list input changes.
- `DynamicLighting.rayClear()` — LOS ray algorithm unchanged.
- World node grid layout — no changes to tile/wall geometry.
- Player torch or sphere-shaped lights — sphere shape has no cone boundary; unaffected.
