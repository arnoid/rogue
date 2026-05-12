# Data Model: Dynamic Raycast Lighting

**Feature**: 001-raycast-lighting
**Date**: 2026-05-12

## New Entities

### `ModelOcclusionProvider` (interface)

**Package**: `com.roguelike.core.systems`
**Layer**: `core` — pure Kotlin, zero LibGDX imports

| Field/Method | Type | Description |
|---|---|---|
| `isOccluded(ox, oy, oz, tx, ty, tz)` | `(Float×6) → Boolean` | True if the segment from origin to target is blocked by any registered geometry. All coordinates use game cell-centered convention. |

**Notes**:
- Declared as `fun interface` so simple lambdas can stub it in tests.
- The source cell (light origin) and target cell (surface sample) are NOT pre-excluded here;
  callers must ensure they offset ray endpoints to avoid self-intersection.

---

### `BvhOccluder`

**Package**: `com.roguelike.rendering`
**Layer**: `rendering` — LibGDX-dependent, implements `ModelOcclusionProvider`

| Field/Method | Type | Description |
|---|---|---|
| `rebuild(boxes)` | `List<BoundingBox> → Unit` | Replaces the internal AABB list. Call once per frame if geometry changes (door opens, prop moves), or once on world load if geometry is static. |
| `isOccluded(ox, oy, oz, tx, ty, tz)` | `(Float×6) → Boolean` | Iterates registered `BoundingBox` objects, skipping any box whose 2D center is further than `cullingRadius` (Manhattan distance) from `(ox, oy)`. Returns true if any remaining box intersects the segment via slab test. |
| `cullingRadius` | `Float` (default `100f`) | Configurable Manhattan-distance cap. Boxes with `|ox−cx| + |oy−cy| > cullingRadius` are skipped. Effective bound per light is `min(light.range, cullingRadius)` due to ray length limiting. |
| `boxes` (private) | `List<BoundingBox>` | World-space AABBs derived from rendered `ModelInstance` objects. |

**State transitions**:
```
empty → rebuild(boxes) → ready
ready → rebuild(boxes) → ready   (idempotent refresh)
```

**Validation rules**:
- `boxes` may be empty (open world with no geometry) — `isOccluded` returns `false`.
- `BoundingBox` objects must be in world-space (post-transform), not model-space.
- `cullingRadius` must be > 0; setting it to `Float.MAX_VALUE` disables culling effectively.

---

## Modified Entities

### `SurfaceLighting`

**Modified field**: optional `occluder: ModelOcclusionProvider?` parameter on constructor
and on the `build(world, actor, occluder)` factory.

| Behavior | `occluder == null` | `occluder != null` |
|----------|-------------------|-------------------|
| `rayClear()` | existing grid DDA | delegates to `occluder.isOccluded()` |
| Backward compatibility | fully preserved | new behavior |

---

### `DynamicLighting`

**Modified field**: optional `occluder: ModelOcclusionProvider?` parameter on
`DynamicLighting.build(world, actor, ambient, occluder)` factory.

| Behavior | `occluder == null` | `occluder != null` |
|----------|-------------------|-------------------|
| `rayClear()` | existing grid DDA | delegates to `occluder.isOccluded()` |
| Backward compatibility | fully preserved | new behavior |

---

### `WorldRenderer`

**Modified method**: `render(world, batch, environment, maxZ, dynamicLighting)` is unchanged
in signature. The caller (game screen) is responsible for building `DynamicLighting` with
a `BvhOccluder`. `WorldRenderer` itself does not construct the occluder — it is injected
via `DynamicLighting`.

---

## Relationships

```
WorldRenderer
  └─ uses ─────────────────────────────► DynamicLighting
                                              │
                              (optional inject)
                                              ▼
                                 ModelOcclusionProvider ◄── BvhOccluder
                                    (core interface)       (rendering impl)
                                              │
                              also injected into:
                                              ▼
                                    SurfaceLighting
                                    (core, testable)
```

## Test Entities

### `StubOccluder` (test helper, inline lambda)

Used in `SurfaceLightingModelOcclusionTest`. Created as a `ModelOcclusionProvider` lambda
that checks a single hard-coded AABB range:

```kotlin
val occluder = ModelOcclusionProvider { ox, oy, oz, tx, ty, tz ->
    // returns true (occluded) if ray crosses the box [4.5..5.5 × 0..9 × 0..2]
    ...
}
```

### `FlatOccluder` (test helper class)

Used in `WorldLightingIntegrationTest`. Builds a list of unit AABBs (1×1×1) for every
wall tile found in the loaded world, providing a deterministic model-geometry approximation
without needing a LibGDX display context.
