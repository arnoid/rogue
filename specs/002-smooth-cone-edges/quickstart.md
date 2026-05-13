# Quickstart: Smooth Cone Light Edges

**Feature**: 002-smooth-cone-edges

## Prerequisites

- Java 17+ on PATH
- `saved-worlds/world.wld` present (integration test fixture)
- `src/main/resources/items/items.json` present

## Run the Tests

```bash
# New: soft cone falloff unit test
./gradlew test --tests "com.roguelike.core.ConeUtilsTest"

# New: LightingSystem cone boundary gradient test
./gradlew test --tests "com.roguelike.core.LightingSystemConeTest"

# New: SurfaceLighting cone smooth boundary test
./gradlew test --tests "com.roguelike.core.SurfaceLightingConeSmoothTest"

# New: floor tile occlusion test (FR-007)
./gradlew test --tests "com.roguelike.core.FloorOcclusionTest"

# Full suite (regression guard)
./gradlew test
```

## Verify Visually

1. `./gradlew run` — open any saved world with a wall-mounted candle.
2. Look at the cone light's boundary edges.
3. Expected: **straight diagonal lines** from the candle outward.
4. Regression check: point lights (torch, lantern) look identical to before.

## Debug the Cone Falloff

Add the system property to see per-light cone parameters logged:

```bash
./gradlew run -Drogue.lightlog=1
```

The `[LIGHTLOG]` output includes the cone half-angle and feather width for each
cone light. Verify `coneFeatherDegrees` defaults to 3.0 for existing candle items.

## Tune the Penumbra Width

In `src/main/resources/items/items.json`, find the candle light definition:

```json
{
  "light": {
    "shape": "CONE",
    "coneDegrees": 90,
    "coneFeatherDegrees": 3.0,
    ...
  }
}
```

- `coneFeatherDegrees: 0` → hard cutoff (old behavior, shows steps)
- `coneFeatherDegrees: 3` → subtle smooth edge (default)
- `coneFeatherDegrees: 10` → wide visible penumbra/diffuse edge

## Key Files

| File | Purpose |
|------|---------|
| `core/model/ItemCatalog.kt` | `LightDef.coneFeatherDegrees` data field |
| `core/systems/ConeUtils.kt` | `softConeFactor()` pure helper |
| `core/systems/LightingSystem.kt` | Cone falloff in `applyLight()` |
| `core/systems/SurfaceLighting.kt` | Cone falloff in `sample()` / `sampleWall()` |
| `core/systems/DynamicLighting.kt` | Fractional cone coverage in mask + `buildEnv()` |
| `rendering/TileRenderer.kt` | `worldSpaceBoxes()` — use `blocksLight()` instead of `isBlocking()` |
| `items/items.json` | `coneFeatherDegrees` per light source type; `blocksLight` per tile type |
