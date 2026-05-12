# Contract: ModelOcclusionProvider

**Package**: `com.roguelike.core.systems`
**Layer**: `core` — pure Kotlin

## Interface Definition

```kotlin
fun interface ModelOcclusionProvider {
    /**
     * Returns true if the straight-line segment from (ox, oy, oz) to (tx, ty, tz)
     * is blocked by scene geometry registered with this provider.
     *
     * Coordinate convention: game cell-centered — cell (x,y,z) is centered at
     * world (x, y, z); walls between cell N and N+1 sit at world N+0.5.
     *
     * Implementors MUST NOT treat the segment endpoints themselves as occluders.
     * A ray from a light source to its own surface must not be self-blocked.
     */
    fun isOccluded(
        ox: Float, oy: Float, oz: Float,   // ray origin (light world-space position)
        tx: Float, ty: Float, tz: Float    // ray target (surface sample point)
    ): Boolean
}
```

## Contract Rules

1. **Deterministic**: the same input arguments MUST always return the same result
   for a given occluder state (before and after any `rebuild()` call).
2. **No side effects**: `isOccluded` MUST NOT modify world state, allocate persistent
   objects, or produce observable side effects.
3. **Endpoint transparency**: the segment endpoints (origin and target) MUST NOT
   themselves cause occlusion, even if they lie inside a bounding box.
4. **Empty occluder**: when no geometry is registered, `isOccluded` MUST return `false`.
5. **Thread safety**: not required — all lighting computation runs on the render thread.

## Companion: `BvhOccluder` (rendering layer)

```kotlin
// com.roguelike.rendering.BvhOccluder
class BvhOccluder : ModelOcclusionProvider {

    /**
     * Replace the registered bounding-box list. Call once per world load, or
     * once per frame if dynamic geometry changes (door open/close, prop move).
     * Boxes MUST be in world-space (post-transform), cell-centered convention.
     */
    fun rebuild(boxes: List<BoundingBox>)

    /**
     * Configurable Manhattan-distance culling radius (default 100f, unit = world nodes).
     * Boxes whose 2D center is further than cullingRadius from the ray origin are skipped.
     * Effective per-light bound is min(light.range, cullingRadius).
     * Set to Float.MAX_VALUE to disable culling.
     */
    var cullingRadius: Float = 100f

    override fun isOccluded(
        ox: Float, oy: Float, oz: Float,
        tx: Float, ty: Float, tz: Float
    ): Boolean
}
```

## Usage Example (rendering layer)

```kotlin
// In the game screen, once per frame:
val occluder = BvhOccluder()
occluder.rebuild(tileRenderer.worldSpaceBounds())   // List<BoundingBox>

val lighting = DynamicLighting.build(world, player, ambient = Color(0.02f, 0.02f, 0.02f, 1f),
                                     occluder = occluder)
worldRenderer.render(world, batch, baseEnv, maxZ = ceil(player.z).toInt(),
                     dynamicLighting = lighting)
```

## Usage Example (test stub)

```kotlin
// Stub that blocks rays crossing x in 4.5..5.5:
val wallOccluder = ModelOcclusionProvider { ox, oy, oz, tx, ty, tz ->
    val minX = minOf(ox, tx); val maxX = maxOf(ox, tx)
    minX < 5.5f && maxX > 4.5f   // true = ray crosses the wall plane
}
val sl = SurfaceLighting.build(world, player, occluder = wallOccluder)
```

## Contract Rules (additions for FR-011)

6. **Culling transparency**: boxes skipped by spatial culling MUST be treated as
   non-occluding, not as errors. Culling is a performance optimization, not a
   correctness guarantee for distant geometry.
7. **Culling configurability**: `cullingRadius` MUST be readable and writable at
   any time without requiring a `rebuild()` call.

## Version History

| Version | Change |
|---------|--------|
| 1.0 | Initial interface definition |
| 1.1 | `BvhOccluder.cullingRadius` added (FR-011 — spatial culling for large maps) |
