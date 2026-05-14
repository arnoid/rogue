# Implementation Plan: Visual Rendering Test Suite for Shadow Volume Pipeline

**Branch**: `006-rendering-visual-tests` | **Date**: 2025-05-13 | **Spec**: `specs/006-rendering-visual-tests/spec.md`
**Input**: Feature specification from `specs/006-rendering-visual-tests/spec.md`

## Summary

Build an automated visual test suite for the shadow volume rendering pipeline. Tests programmatically construct 3D scenes with lights, occluders, and receivers, render them through the full multi-pass stencil pipeline (ambient → per-light stencil → per-light lit), save output PNGs to `build/test-output/rendering/`, and assert correctness via pixel-sampling with configurable tolerance. The suite covers 24 scenarios across 6 categories: basic shadow/light, geometry accuracy, light position/distance, multi-light, edge cases, and regression tests.

## Technical Context

**Language/Version**: Kotlin 1.9.22  
**Primary Dependencies**: libGDX 1.12.1 (gdx, gdx-backend-lwjgl3, gdx-platform natives-desktop), vis-ui 1.5.3, ktx-scene2d 1.12.1-rc1  
**Storage**: N/A (PNG file output to `build/test-output/rendering/`)  
**Testing**: JUnit Jupiter 5.10.1  
**Target Platform**: Desktop (LWJGL3)  
**Project Type**: Desktop game application  
**Performance Goals**: Full test suite completes in < 120 seconds  
**Constraints**: Headless/offscreen rendering via LWJGL3 hidden window + FBO; stencil buffer required  
**Scale/Scope**: 24 test scenarios, ~6 test classes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution is unpopulated (template placeholders only). No gates to enforce. **PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/006-rendering-visual-tests/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/
├── main/kotlin/com/roguelike/rendering/
│   ├── ShadowVolumeRenderer.kt       # Multi-pass stencil pipeline orchestrator
│   ├── ShadowVolumeBuilder.kt         # CPU-side shadow volume geometry builder
│   ├── ShadowVolumeShaderProvider.kt  # Shader program manager
│   ├── ShadowVolumeMesh.kt            # Shadow volume mesh data
│   ├── OccluderExtractor.kt           # Wall triangle extraction from world
│   ├── PointLightData.kt              # Point light runtime data
│   ├── SilhouetteEdge.kt              # Silhouette edge data
│   └── SilhouetteCache.kt             # Silhouette edge caching
├── main/resources/shaders/            # GLSL shader files
└── test/kotlin/com/roguelike/
    └── rendering/
        ├── ShadowVolumeBuilderTest.kt          # Existing geometry unit tests
        ├── RenderTestHarness.kt                 # NEW: Headless rendering harness
        ├── PixelSampler.kt                      # NEW: Pixel sampling assertion utilities
        ├── SceneBuilder.kt                      # NEW: Programmatic scene construction
        ├── BasicShadowLightTest.kt              # NEW: User Story 1 (5 scenarios)
        ├── ShadowVolumeGeometryTest.kt          # NEW: User Story 2 (4 scenarios)
        ├── LightPositionDistanceTest.kt         # NEW: User Story 3 (4 scenarios)
        ├── MultiLightInteractionTest.kt         # NEW: User Story 4 (3 scenarios)
        ├── EdgeCaseRobustnessTest.kt            # NEW: User Story 5 (4 scenarios)
        └── RegressionArtifactTest.kt            # NEW: User Story 6 (4 scenarios)

build/test-output/rendering/                     # PNG output directory (auto-created)
```

**Structure Decision**: Single project, tests added under existing `src/test/kotlin/com/roguelike/rendering/` directory. New test infrastructure files (harness, pixel sampler, scene builder) alongside test classes.

## Complexity Tracking

No constitution violations to justify.
