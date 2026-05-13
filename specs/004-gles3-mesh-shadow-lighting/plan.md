# Implementation Plan: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Branch**: `004-gles3-mesh-shadow-lighting` | **Date**: 2026-05-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-gles3-mesh-shadow-lighting/spec.md`

## Summary

Replace the existing CPU-based raycasting lighting system (DynamicLighting, SurfaceLighting, LightingSystem, BvhOccluder) entirely with a GPU shadow-mapping pipeline that respects mesh geometry. The new system uses a custom GLSL 1.50 shader pair (`Gles3LightingShader`) compiled under LibGDX's `ModelBatch` via a `Gles3ShaderProvider`. Directional shadow mapping reuses LibGDX's `DirectionalShadowLight`; point-light omnidirectional shadows use the existing `FrameBufferCubemap` pool. All CPU lighting code (~1,700 lines + 6 test files) is deleted with no feature-flag wrapping.

## Technical Context

**Language/Version**: Kotlin 1.9.22 / JVM 17

**Primary Dependencies**: LibGDX 1.12.1, kotlinx-coroutines-core 1.7.3

**Storage**: N/A

**Testing**: JUnit Jupiter 5.10.1 via `./gradlew test`

**Target Platform**: Desktop macOS/Linux (OpenGL 3.2 core profile = GLSL 1.50)

**Project Type**: 3D roguelike desktop game (LibGDX)

**Performance Goals**: ≥ 30 FPS with 8 simultaneous point lights

**Constraints**: GLSL 1.50 only (no `attribute`/`varying`/`texture2D`); zero LibGDX imports in `core` package; shader must load from `src/main/resources/shaders/`

**Scale/Scope**: Affects ~2,000 lines of existing code; net reduction expected after deletion

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Core-Rendering Separation | ✅ PASS | `Gles3LightingShader`, `Gles3ShaderProvider`, `ShadowRenderer` live in `rendering` only. `core/model/lighting/` data classes retain zero LibGDX imports. |
| II. Test-First (TDD) | ✅ PASS with caveat | `Gles3LightingShaderUniformTest` written before implementation. GL-context tests use shader source string assertions as proxy. See Complexity Tracking. |
| III. SOLID & Single Responsibility | ✅ PASS | `Gles3LightingShader` = uniform binding. `Gles3ShaderProvider` = shader creation. `ShadowRenderer` = pass orchestration. Each has one reason to change. |
| IV. Data-Driven | ✅ PASS | `GpuLightEnvironment` is the canonical data class; shader reads from it. |
| V. Simplicity & YAGNI | ✅ PASS | Net deletion of ~1,700 lines. No new abstractions beyond what the spec requires. |

## Project Structure

### Documentation (this feature)

```text
specs/004-gles3-mesh-shadow-lighting/
├── plan.md              ← this file
├── research.md          ← Phase 0 output
├── data-model.md        ← Phase 1 output
├── quickstart.md        ← Phase 1 output
├── checklists/
│   └── requirements.md
└── tasks.md             ← Phase 2 output (/speckit-tasks)
```

### Source Code Changes

```text
src/main/kotlin/com/roguelike/
├── core/
│   ├── model/lighting/
│   │   ├── DirectionalLightData.kt          KEEP (unchanged)
│   │   ├── PointLightData.kt                KEEP (unchanged)
│   │   └── GpuLightEnvironment.kt           KEEP (unchanged)
│   └── systems/
│       ├── DynamicLighting.kt               DELETE
│       ├── LightingSystem.kt                DELETE
│       ├── LightingDiagnostics.kt           DELETE
│       └── SurfaceLighting.kt               DELETE
├── rendering/
│   ├── BvhOccluder.kt                       DELETE
│   ├── Gles3LightingShader.kt               NEW
│   ├── Gles3ShaderProvider.kt               NEW
│   ├── ShadowRenderer.kt                    REPLACE
│   └── WorldRenderer.kt                     SIMPLIFY
└── RoguelikeGame.kt                         UPDATE

src/main/resources/shaders/
├── gles3_lighting.vert.glsl                 NEW
├── gles3_lighting.frag.glsl                 NEW
└── point_shadow.frag.glsl                   DELETE

src/test/kotlin/com/roguelike/
├── core/
│   ├── DynamicLightingTest.kt               DELETE
│   ├── LightingSystemTest.kt                DELETE
│   ├── LightingSystemConeTest.kt            DELETE
│   ├── SurfaceLightingTest.kt               DELETE
│   ├── SurfaceLightingConeSmoothTest.kt     DELETE
│   ├── SurfaceLightingModelOcclusionTest.kt DELETE
│   └── DropPickupTest.kt                    UPDATE (remove LightingSystem.compute calls)
└── rendering/
    ├── ShadowRendererConstructionTest.kt    UPDATE
    └── Gles3LightingShaderUniformTest.kt    NEW
```

**Structure Decision**: Single Kotlin/JVM project. `core` stays LibGDX-free. New rendering classes go in `rendering`. Shaders load via `Gdx.files.internal("shaders/...")` (working dir = `src/main/resources`).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Custom `Shader` impl (not DefaultShader) | `DefaultShader` dynamically generates GLSL source incompatible with `#version 150` mid-source injection. Binding 8 cubemap samplers requires explicit `glActiveTexture` calls not available via `DefaultShader` API. | Subclassing `DefaultShader` requires duplicating ~300 lines of private source-generation code — more complexity than a clean `BaseShader` implementation. |
| TDD gap for GL-context shader compilation | `ShaderProgram` requires a running GL context — cannot fail-first in headless JUnit. | Full headless GL mocking not in current deps; adding `gdx-testing` would violate Principle V. Accepted tradeoff: shader source string assertions + manual visual acceptance. |

## Phase 0: Research

**Status**: COMPLETE — see [research.md](research.md)

Key decisions:
1. GLSL 1.50 (`#version 150`) on desktop (OpenGL 3.2 core), not `#version 300 es`
2. `BaseShader`-based `Gles3LightingShader`
3. `DirectionalShadowLight` kept for directional depth pass
4. `FrameBufferCubemap` kept for point light depth; wired to fragment shader uniforms
5. Full deletion of CPU lighting — no wrapping or feature flags

## Phase 1: Design

**Status**: COMPLETE

- [data-model.md](data-model.md) — entity specs for new, retained, and deleted classes
- [quickstart.md](quickstart.md) — visual and headless acceptance scenarios per user story

**Post-design Constitution re-check**: All five principles pass. Custom `Shader` complexity is documented in the Complexity Tracking table above.
