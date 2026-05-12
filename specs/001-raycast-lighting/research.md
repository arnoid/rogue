# Research: Dynamic Raycast Lighting

**Feature**: 001-raycast-lighting
**Date**: 2026-05-12

## Existing System Analysis

### Three Lighting Systems in the Codebase

| Class | Layer | Occlusion | Output |
|-------|-------|-----------|--------|
| `LightingSystem` | `core.systems` (pure Kotlin) | Integer-step 3D LOS through world nodes | `LightMap3D` — per-cell uniform tint |
| `SurfaceLighting` | `core.systems` (pure Kotlin) | Continuous DDA through world nodes | Per-surface RGB float — floor/wall/cell |
| `DynamicLighting` | `core.systems` (LibGDX) | Continuous DDA + multi-sample points | LibGDX `Environment` per surface |

`DynamicLighting` is what `WorldRenderer` actually uses. `SurfaceLighting` is the pure-core
mirror used for testing and future headless rendering.

### Documented Light Leak Issue

Both `DynamicLighting.rayClear` and `SurfaceLighting.rayClear` contain a `leakWarned` flag
and a diagnostic block that fires when the light source's declared grid cell disagrees with
its DDA start voxel. The fix applied (+0.5 shift to align DDA voxel boundaries with game
wall positions) is correct for integer-aligned walls. The residual leaks occur when model
geometry doesn't align perfectly with grid edges — the current occlusion tests walls at
grid-quantized positions, but models can have non-aligned faces.

### Root Cause of Visual Artifacts

1. **Grid-quantized wall positions**: `isWallBetween` / `wallBlockedBetween` check wall
   *slots* on grid-aligned integer cells. A model placed at a tile boundary that's been
   nudged slightly produces a gap between the DDA wall-test plane and the model face.
2. **Multi-sample heuristic in `DynamicLighting`**: 5–10 sample points per surface are
   tested and the surface is marked lit if ANY sample is reachable. A surface near a wall
   corner may have one sample point sneak through, making it appear lit even though most
   of it is occluded.
3. **Stairs treated as opaque for horizontal light**: `isCellOpaque` blocks light through
   stair cells entirely, but stair models are sloped — the upper portion of a stair cell
   doesn't actually block horizontal light paths near the top.

---

## Decision 1: Occlusion Approach

**Decision**: 3D DDA ray march against world-space **axis-aligned bounding boxes (AABBs)**
derived from rendered `ModelInstance` objects.

**Rationale**:
- AABB tests are cheap (LibGDX `Intersector.intersectRayBounds` or manual slab test)
- Captures model-aligned occlusion without per-triangle cost
- Eliminates grid-quantization error: if a model is placed at (4.0, 4.5, 0.0) its AABB
  correctly reflects that position, not a rounded cell index
- For dungeon tiles (walls, doors, pillars) box-shaped AABBs match the actual geometry well

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Per-triangle ray-mesh intersection | 10-100× more expensive; no visible quality gain for box-like tile geometry |
| LibGDX shadow mapping (render-pass shadows) | Requires custom shaders; libGDX default shader has no shadow map support; out of scope |
| Depth buffer occlusion query | GPU readback stalls; incompatible with multi-pass CPU lighting approach |
| Keep world-node LOS | Doesn't fix grid-quantization artifacts; the whole point of this feature |

---

## Decision 2: Architecture Bridge

**Decision**: Define `ModelOcclusionProvider` as a `fun interface` in `core.systems` (pure
Kotlin). Implement it as `BvhOccluder` in `rendering` (LibGDX). Inject into `SurfaceLighting`
and `DynamicLighting` as an optional constructor / factory parameter (null = fall back to
existing grid DDA for backward compatibility and tests).

**Rationale**: Follows the `GameLogger` pattern already established in the codebase.
Keeps `core` free of LibGDX. Existing unit tests (which construct `SurfaceLighting`
directly without models) continue to work unchanged by passing `null`.

---

## Decision 3: AABB Construction Strategy

**Decision**: `BvhOccluder.rebuild(instances: List<Pair<ModelInstance, BoundingBox>>)`.
The caller (game screen or `WorldRenderer`) passes pre-computed world-space `BoundingBox`
objects. `BvhOccluder` stores them in a flat list sorted by their X-axis extent for a
lightweight sweep-and-prune broad phase.

**Rationale**: LibGDX `ModelInstance.calculateBoundingBox()` is relatively cheap but
allocates. Pre-computing outside the occlusion hot path keeps per-ray cost minimal.
A full BVH tree is overkill for dungeon scenes with < 2000 tile instances in view.

---

## Decision 4: Fallback Behavior

**Decision**: When `ModelOcclusionProvider` is `null`, `SurfaceLighting` and
`DynamicLighting` continue to use the existing grid DDA. This preserves backward
compatibility for tests and the editor (which may not have model instances available).

---

## Decision 5: Testing Strategy

**Decision**: Two test classes:

1. **`SurfaceLightingModelOcclusionTest`** — pure Kotlin, no display:
   - Uses a hand-crafted `ModelOcclusionProvider` stub (`fun interface` lambda) that mimics
     a single box occluder
   - Tests: light passes through open space; light blocked by box; door-state toggle

2. **`WorldLightingIntegrationTest`** — loads `saved-worlds/world.wld` via `WorldIO` and
   a `FlatOccluder` that builds AABBs from grid cell wall flags (a deterministic stand-in
   for the real LibGDX `BvhOccluder` usable without a GPU context):
   - Tests: no light leaks across known walls in the saved world
   - Tests: lit items dropped on world nodes illuminate adjacent surfaces

**Why `*.wld` files**: The saved world files contain a fully-laid-out dungeon with known
wall/door/stair geometry. Testing against them catches regressions in the DDA logic when
actual world geometry is present, not just a hand-crafted minimal world.

---

## Decision 6: Artifact Elimination Plan

| Artifact | Root Cause | Fix |
|----------|-----------|-----|
| Shadow edges on tile grid boundaries | Wall occlusion at integer grid coordinates | AABB occlusion uses model world-space bounds |
| Light leaking through wall corners | Multi-sample point slipping past corner | AABB test blocks the entire box face, no point-slipping |
| Stair-cell horizontal light blockage | `isCellOpaque` treats entire stair cell as opaque | AABB for stair model covers only the actual slope geometry |
| `leakWarned` light origin mismatch | DDA start voxel ≠ declared source cell | Model occluder uses continuous world coords, no voxel quantization |

---

## Decision 7: Spatial Culling for Large Maps (FR-011)

**Decision**: Add `var cullingRadius: Float = 100f` to `BvhOccluder`. In `isOccluded()`,
before the slab intersection test for each box, compute the Manhattan distance from light
origin `(ox, oy)` to the box's XY center `((min.x+max.x)/2, (min.y+max.y)/2)` and skip
boxes where `|ox−cx| + |oy−cy| > cullingRadius`. The `cullingRadius` property is
configurable (default 100 nodes, where 1 node = 1 world unit). The effective per-light
bound is naturally `min(light.range, cullingRadius)` because the ray-march in `rayClear()`
never generates sample points beyond `light.range`, so boxes beyond `light.range` but within
`cullingRadius` are tested for intersection but will never match a segment that doesn't
reach them.

**Rationale**:
- Without culling, each `isOccluded()` call is O(N) in the total number of blocking tiles
  in the world. A 200×200 dungeon can have tens of thousands of tiles; with 8 lights and
  hundreds of surface samples, this becomes the dominant bottleneck.
- With Manhattan culling, the hot path tests only tiles within the diamond of radius 100
  around each light origin. For a typical torch (range ≈ 8 tiles), the effective bound is
  min(8, 100) = 8, limiting the test to at most ~200 nearby tiles regardless of world size.
- Manhattan distance (`|dx|+|dy|`) is the cheapest 2D metric: two abs-calls, one add,
  one compare — no sqrt, no branches beyond the comparison.
- Culling is confined to `BvhOccluder`; the `ModelOcclusionProvider` interface is unchanged.
- `cullingRadius` as a mutable property (not a constructor parameter) lets the caller tune
  it at runtime without rebuilding the occluder.

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Filter in `worldSpaceBoxes()` per light | Requires O(lights) rebuild calls per frame; current design rebuilds once per frame for all lights together |
| Filter at `rebuild()` time | `rebuild()` is called before lights are evaluated; can't pass per-light positions |
| Euclidean (circular) radius | Requires `sqrt()` per box — marginally more accurate but significantly slower in the inner loop |
| BVH tree (octree/k-d tree) | Better asymptotic performance but overkill for ≤2000 tiles; flat list + Manhattan culling is sufficient |
| No culling, keep world-filtered list | O(N) per ray; causes visible FPS drop on large maps, the exact reported symptom |

---

## Unchanged Scope

- `LightingSystem` (coarse `LightMap3D`) is NOT modified — it serves a different purpose
  (visibility fog / minimap) and is not on the render hot path.
- The LibGDX `PointLight` / `Environment` GPU rendering approach is NOT changed.
- The `DynamicLighting` multi-sample surface API (`environmentForFloor`, `environmentForWall`,
  `environmentForCell`) is NOT changed — only the occlusion oracle it uses internally.
