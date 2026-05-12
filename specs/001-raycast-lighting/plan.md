# Implementation Plan: Dynamic Raycast Lighting

**Branch**: `001-raycast-lighting` | **Date**: 2026-05-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-raycast-lighting/spec.md`

## Summary

Replace the world-node-based line-of-sight queries in the existing lighting pipeline with
continuous 3D DDA ray tests against actual model-space AABBs. The DDA algorithm itself
is retained; what changes is the occlusion oracle — instead of checking
`world.getNode()` for wall slots and floor slots, rays test against a spatial BVH of
world-space bounding boxes built from the rendered `ModelInstance` objects. This
eliminates grid-quantized shadow edges, fixes the documented light-leak corner cases, and
delivers shadow boundaries that align with visible model geometry.

A spatial culling stage (FR-011) caps `BvhOccluder` to only test boxes within Manhattan
distance `min(light.range, cullingRadius)` of each light origin, keeping per-ray cost
constant regardless of total world tile count.

## Technical Context

**Language/Version**: Kotlin 1.9.22 (JVM)

**Primary Dependencies**: LibGDX 1.12.1 (`gdx`, `gdx-backend-lwjgl3`, `gdx-platform:natives-desktop`)

**Storage**: `.wld` world files (JSON via `com.badlogic.gdx.utils.Json`) for integration tests

**Testing**: JUnit Jupiter 5.10.1; pure-Kotlin unit tests (no LibGDX display) + integration
tests loading `saved-worlds/world.wld` via `WorldIO`

**Target Platform**: Desktop (LWJGL3, macOS `-XstartOnFirstThread` already configured)

**Project Type**: Desktop roguelike game (LibGDX)

**Performance Goals**: ≥ 30 fps with ≤ 8 concurrent dynamic light sources on the
development machine, regardless of total map tile count; spatial culling (Manhattan radius
= `min(light.range, 100)` nodes, configurable) keeps per-ray box tests O(K) where K is
tiles within the culling radius, not O(N_world)

**Constraints**: Zero light leaks through closed walls/doors; shadow edges must align with
visible model geometry, not tile-grid boundaries; zero LibGDX imports in `core`;
`cullingRadius` configurable without code changes (FR-011)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Core-Rendering Separation | ✅ PASS | `ModelOcclusionProvider` interface lives in `core.systems` (pure Kotlin). `BvhOccluder` lives in `rendering` and implements it via LibGDX `BoundingBox`. Core systems receive the interface by injection. |
| II. Test-First | ✅ PASS | `SurfaceLightingModelOcclusionTest` and `WorldLightingIntegrationTest` written before implementation. DDA + AABB path tested against `world.wld` fixtures without a display. |
| III. SOLID & Single Responsibility | ✅ PASS | `ModelOcclusionProvider` has exactly one method. `BvhOccluder` is solely responsible for AABB occlusion and spatial culling. `DynamicLighting` and `SurfaceLighting` are modified only to accept the injected provider. |
| IV. Data-Driven | ✅ PASS | No changes to `game_rules.md` required; occlusion is a rendering concern, not a game-rule change. |
| V. Simplicity & YAGNI | ✅ PASS | AABB-based occlusion is the minimum viable approach for model-aligned shadows. Per-triangle tests deferred until a concrete need is demonstrated. |

**Post-design re-check**: FR-011 (spatial culling) adds `cullingRadius` property to
`BvhOccluder` (rendering layer only); the `ModelOcclusionProvider` interface is unchanged.
No new violations.

## Project Structure

### Documentation (this feature)

```text
specs/001-raycast-lighting/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── model-occlusion-provider.md
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code Changes

```text
src/main/kotlin/com/roguelike/
├── core/systems/
│   ├── ModelOcclusionProvider.kt      # NEW — injectable interface (pure Kotlin)
│   ├── SurfaceLighting.kt             # MODIFY — accept optional ModelOcclusionProvider
│   └── DynamicLighting.kt             # MODIFY — accept optional ModelOcclusionProvider
└── rendering/
    ├── BvhOccluder.kt                 # NEW — LibGDX AABB-based implementation + Manhattan culling (FR-011)
    └── WorldRenderer.kt               # MODIFY — pass BvhOccluder to DynamicLighting.build()

src/test/kotlin/com/roguelike/
├── core/
│   └── SurfaceLightingModelOcclusionTest.kt   # NEW — tests with stub occluder
└── serialization/
    └── WorldLightingIntegrationTest.kt         # NEW — load world.wld, verify no leaks
```

## Complexity Tracking

> No Constitution violations — table intentionally empty.
