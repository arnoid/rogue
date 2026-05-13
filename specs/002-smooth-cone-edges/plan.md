# Implementation Plan: Smooth Cone Light Edges

**Branch**: `002-smooth-cone-edges` | **Date**: 2026-05-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-smooth-cone-edges/spec.md`

## Summary

Three independent code paths all apply a **hard binary cone cutoff** (inside/outside) at
**cell granularity**, producing the staircase pattern aligned with the tile grid. The fix
replaces the hard cutoff with a **smooth angular falloff over a configurable penumbra zone**
(default 3°) using a shared `softConeFactor()` helper. Cells inside the hard cone receive
full intensity; cells in the penumbra receive a linearly-interpolated fraction; cells
outside receive none.

`DynamicLighting` additionally needs its binary per-cell light mask extended to carry a
per-light intensity scale so the GPU PointLight intensity can reflect fractional cone
coverage at the boundary.

A companion fix (FR-007) corrects floor tile transparency in the `BvhOccluder`: floor tiles
are movement-passable (`isBlocking() = false`) but must block light rays. A new
`blocksLight()` method is added to the `Tile` interface (defaulting to `isBlocking()`) with
`FloorTile` overriding it to `true`. `TileRenderer.worldSpaceBoxes()` switches to this
filter.

## Technical Context

**Language/Version**: Kotlin 1.9.22 (JVM)

**Primary Dependencies**: LibGDX 1.12.1; JUnit Jupiter 5.10.1

**Storage**: `LightDef` (data class in `ItemCatalog.kt`) — adding one optional field

**Testing**: JUnit Jupiter; pure-Kotlin unit tests (no LibGDX display); existing
`SurfaceLightingModelOcclusionTest` and `LightingSystemTest` as regression guards

**Target Platform**: Desktop (LWJGL3, macOS `-XstartOnFirstThread`)

**Performance Goals**: Smooth cone falloff replaces a `continue` with a multiply — O(1)
per sample; net cost is zero or negative (fewer wasted rayClear calls on boundary cells)

**Constraints**: Zero changes to `LightDef` serialisation format that break saved worlds;
`coneFeatherDegrees` must be backward-compatible (old JSON with no field → default 3f).
`blocksLight()` default on `Tile` = `isBlocking()` — zero behavioral change for all existing
tile types except `FloorTile` (the intended fix).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Core-Rendering Separation | ✅ PASS | `softConeFactor()` goes in `core.systems` (pure Kotlin). `LightDef.coneFeatherDegrees` is a data field in `core.model`. `Tile.blocksLight()` is added to `core.model.Tile` (pure Kotlin). `TileRenderer.worldSpaceBoxes()` is in `rendering` — it calls `tile.blocksLight()` via the core interface; no LibGDX bleeds into core. |
| II. Test-First | ✅ PASS | Tests written before implementation: `ConeUtilsTest` (softConeFactor), `LightingSystemConeTest`, `SurfaceLightingConeSmoothTest` (no grid steps), `FloorOcclusionTest` (floor AABBs included in occluder). |
| III. SOLID & Single Responsibility | ✅ PASS | `softConeFactor()` is a pure, single-purpose function. `blocksLight()` on `Tile` is a focused, testable predicate. `DynamicLighting.computeMaskMultiSample()` gains only the float-scale output. |
| IV. Data-Driven | ✅ PASS | `coneFeatherDegrees` in `LightDef` (configured via `items.json`). `blocksLight()` per tile class — each tile type declares its own light-blocking behavior, following the same per-type data pattern as `ItemDef.blocksLight` for items. |
| V. Simplicity & YAGNI | ✅ PASS | One 10-line helper, one field, one interface method with a default. `DynamicLighting` intensity-scale array is the minimal GPU fix. No new packages, patterns, or catalog entries needed. |

**Post-design re-check**: All new code is either pure Kotlin in `core` or rendering-layer
changes that read `core` interfaces. Zero LibGDX imports in `core`. No violations.

## Project Structure

### Documentation (this feature)

```text
specs/002-smooth-cone-edges/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code Changes

```text
src/main/kotlin/com/roguelike/
├── core/
│   ├── model/
│   │   ├── ItemCatalog.kt           # MODIFY — add coneFeatherDegrees to LightDef
│   │   └── Tile.kt                  # MODIFY — add blocksLight() default method (FR-007)
│   └── systems/
│       ├── ConeUtils.kt             # NEW — softConeFactor() pure helper (pure Kotlin)
│       ├── LightingSystem.kt        # MODIFY — use softConeFactor() in applyLight()
│       ├── SurfaceLighting.kt       # MODIFY — use softConeFactor() in sample() and sampleWall()
│       └── DynamicLighting.kt       # MODIFY — fractional cone coverage in computeMaskMultiSample() + buildEnv()
├── world/
│   └── Tiles.kt                     # MODIFY — FloorTile.blocksLight() = true (FR-007)
└── rendering/
    └── TileRenderer.kt              # MODIFY — worldSpaceBoxes() use blocksLight() not isBlocking() (FR-007)

src/test/kotlin/com/roguelike/
├── core/
│   ├── ConeUtilsTest.kt                    # NEW — unit tests for softConeFactor()
│   ├── LightingSystemConeTest.kt            # NEW — no grid-staircase in LightingSystem cone output
│   └── SurfaceLightingConeSmoothTest.kt     # NEW — no hard step in SurfaceLighting cone boundary
└── world/
    └── FloorOcclusionTest.kt               # NEW — FloorTile.blocksLight()=true; WallTile unchanged (FR-007)
```

## Complexity Tracking

> No Constitution violations — table intentionally empty.
