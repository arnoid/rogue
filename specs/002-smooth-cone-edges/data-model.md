# Data Model: Smooth Cone Light Edges

**Feature**: 002-smooth-cone-edges
**Date**: 2026-05-12

## Modified Entities

### `LightDef` (`core.model.ItemCatalog`)

**Added field**:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `coneFeatherDegrees` | `Float` | `3f` | Soft penumbra width at the cone boundary. 0 = hard cutoff (legacy). Positive values enable smooth falloff over this angular range outside the hard edge. Serialised in `items.json`; missing field defaults to 3f for backward compatibility. |

**Validation rules**:
- `coneFeatherDegrees` must be ≥ 0.
- Values > 45f are clamped to 45f (a penumbra wider than the half-angle itself is meaningless).
- Only affects lights where `shape == LightShape.CONE`; ignored for `SPHERE` lights.

---

## New Entities

### `ConeUtils` (`core.systems.ConeUtils`) — pure Kotlin, zero LibGDX imports

A file-scoped (internal) utility object with one function:

```kotlin
// Returns the angular attenuation factor for a direction vector relative to
// a cone axis.
//   dot          — cosine of angle between direction and cone axis
//   cosHardEdge  — cos(halfConeDeg)         = inner hard boundary
//   cosSoftEdge  — cos(halfConeDeg + feather) = outer soft boundary
// Returns:
//   1f  when fully inside the hard cone (dot >= cosHardEdge)
//   0f  when outside the soft boundary  (dot < cosSoftEdge)
//   linear interpolation in the penumbra zone
internal fun softConeFactor(dot: Float, cosHardEdge: Float, cosSoftEdge: Float): Float =
    ((dot - cosSoftEdge) / (cosHardEdge - cosSoftEdge)).coerceIn(0f, 1f)
```

**Validation rules**:
- `cosHardEdge > cosSoftEdge` (the hard boundary must be tighter than the soft one).
- If feathering is disabled (`coneFeatherDegrees == 0f`), `cosHardEdge == cosSoftEdge`
  and the formula degenerates to a step — callers must guard against division by zero by
  checking `featherDegrees > 0f` before calling, or by computing
  `cosSoftEdge = cosHardEdge - epsilon` as a safe minimum.

---

## Modified Behaviour

### `LightingSystem.applyLight()`

Before (hard cutoff):
```kotlin
if (dot < cosHalf) continue
val mul = falloff * intensity
addLight(map, tx, ty, tz, lr, lg, lb, mul)
```

After (soft falloff):
```kotlin
val factor = softConeFactor(dot, cosHardEdge, cosSoftEdge)
if (factor <= 0f) continue
val mul = falloff * intensity * factor
addLight(map, tx, ty, tz, lr, lg, lb, mul)
```

### `SurfaceLighting.sample()` and `sampleWall()`

Same pattern: replace `if (dot < cosHalf) continue` with soft factor multiplied into `mul`.

### `DynamicLighting.computeMaskMultiSample()`

Extended to accumulate a fractional cone coverage per light alongside the binary mask:

- For SPHERE lights: coverage = 1.0 (unchanged).
- For CONE lights: sum the `softConeFactor()` values of all N sample points that also
  pass `rayClear()`; divide by N → `coneScale ∈ [0f, 1f]`.
- The bitmask bit is still set if `coneScale > 0`.

### `DynamicLighting.buildEnv()`

For CONE lights, scale the PointLight intensity by `coneScales[i]`:
```kotlin
// Before: gpuIntensity(l.def)
// After:  gpuIntensity(l.def) * coneScales[i]
```

This propagates the fractional coverage to the GPU — boundary cells receive dim
PointLight; fully-inside cells receive full PointLight.

---

---

## Modified Behaviour (FR-007)

### `Tile` interface (`core.model.Tile`) — new method

```kotlin
// Default: same as movement blocking (walls block both; open doors block neither)
fun blocksLight(): Boolean = isBlocking()
```

### `FloorTile` (`world.Tiles`) — override

```kotlin
// Floor is traversable (isBlocking = false) but MUST block light rays
override fun blocksLight(): Boolean = true
```

Any tile class that should be transparent to light (e.g., a future glass floor) overrides
`blocksLight()` to return `false`. This is the per-tile-type data-driven knob.

### `TileRenderer.worldSpaceBoxes()` (light-occlusion AABB builder)

Before (movement-only filter):
```kotlin
if (tile !is BaseTile || !tile.isBlocking()) continue
// → FloorTile excluded; light passes through floors
```

After (light-blocking filter):
```kotlin
if (tile !is BaseTile || !tile.blocksLight()) continue
// → FloorTile included (blocksLight() = true)
// → open DoorXTile still excluded (blocksLight() = isBlocking() = false)
```

---

## Relationships

```
ItemCatalog
  └── LightDef.coneFeatherDegrees
          │
          ▼ used by
 ConeUtils.softConeFactor()       ◄── internal helper
          │
  ┌───────┼────────────────────┐
  ▼       ▼                    ▼
LightingSystem  SurfaceLighting  DynamicLighting
 applyLight()    sample()         computeMaskMultiSample() + buildEnv()


Tile (core.model)                              ← FR-007
  └── blocksLight(): Boolean = isBlocking()
          │
          ▼ overridden by
      FloorTile.blocksLight() = true
          │
          ▼ read by
  TileRenderer.worldSpaceBoxes()
          │
          ▼ feeds into
      BvhOccluder (AABB list)
```
