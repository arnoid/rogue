# Quickstart: Dynamic Raycast Lighting

**Feature**: 001-raycast-lighting

## Prerequisites

- Java 17+ on PATH
- `saved-worlds/world.wld` present (already committed)
- `src/main/resources/items/items.json` present (required for `ItemCatalogLoader`)

## Run the Tests

```bash
# All lighting tests (pure-Kotlin, no display needed)
./gradlew test --tests "com.roguelike.core.SurfaceLightingModelOcclusionTest"
./gradlew test --tests "com.roguelike.serialization.WorldLightingIntegrationTest"

# All tests
./gradlew test
```

Tests run headless — no LWJGL3 display is required.

## Run the Game

```bash
./gradlew run
```

macOS: the build already passes `-XstartOnFirstThread` via `build.gradle.kts`.

## Verify the Feature Visually

1. Launch the game with `./gradlew run`.
2. Open or create a world with at least one enclosed room connected to a corridor.
3. Pick up a **Torch** (press `E` near one) and light it (`L` key or the inventory UI).
4. Walk through the room:
   - Shadow boundaries should follow wall model edges, not tile grid lines.
   - No light should bleed through closed walls.
   - Opening a door should immediately extend the lit area into the next room.
5. Drop the torch on the floor (press `G`):
   - The torch should continue illuminating the area from its resting position.
   - Walking away should not extinguish it.

## Enable Light Diagnostics

```bash
./gradlew run -Drogue.lightlog=1
```

Prints per-frame `[LIGHTLOG]` lines to stdout showing light count, source positions,
and any DDA origin/cell mismatch warnings. A correct implementation produces zero
`DDA start voxel mismatch` warnings.

## Testing with a Saved World

The integration test loads `saved-worlds/world.wld` and verifies that:

- A synthetic torch placed at a known cell does NOT illuminate cells on the far side of
  any wall present in the saved world.
- The `FlatOccluder` (unit AABBs from wall tile positions) produces the same no-leak
  result as the grid-DDA path for rectilinear geometry.

To reuse the saved world in a manual test session, copy it to the resources directory:

```bash
cp saved-worlds/world.wld src/main/resources/world.wld
```

Then launch the game — it will load `world.wld` automatically on startup.

## Verify Spatial Culling Performance (FR-011)

With a large world (many tiles), confirm culling is working:

1. Enable light diagnostics: `./gradlew run -Drogue.lightlog=1`
2. Walk through a large room. The `[LIGHTLOG]` output should show
   `occluder queries this frame: hits=N misses=M` with a small total —
   not proportional to total world tile count.
3. To tune the culling radius at runtime, `BvhOccluder.cullingRadius` can be
   set to any positive float; `Float.MAX_VALUE` disables culling for debugging.

## Key Files

| File | Purpose |
|------|---------|
| `core/systems/ModelOcclusionProvider.kt` | Occlusion interface |
| `rendering/BvhOccluder.kt` | LibGDX AABB implementation |
| `core/systems/SurfaceLighting.kt` | Per-surface ray lighting (testable) |
| `core/systems/DynamicLighting.kt` | GPU environment builder (render path) |
| `rendering/WorldRenderer.kt` | Calls DynamicLighting per frame |
| `saved-worlds/world.wld` | Integration test fixture |
