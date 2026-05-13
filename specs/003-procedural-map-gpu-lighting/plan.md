# Implementation Plan: Procedural Map Generator and GPU Lighting Engine

**Branch**: `003-procedural-map-gpu-lighting` | **Date**: 2026-05-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-procedural-map-gpu-lighting/spec.md`

## Summary

Replace the CPU per-cell raycasting lighting with a GPU shadow mapping pipeline
(`DirectionalShadowLight` + `FboCubemap` per-point-light omnidirectional shadows), and
validate and test the already-implemented socket-based procedural map generator.

System A (map generator) is 95% implemented in `com.roguelike.generation`; the primary
gap is **zero unit tests**, which violates Constitution Principle II. System B (GPU lighting)
requires a new `rendering.ShadowRenderer` and `core.model.lighting.GpuLightEnvironment`
that replaces the per-surface `DynamicLighting.environmentForXxx()` call pattern.

## Technical Context

**Language/Version**: Kotlin 1.9.22 / JVM 17

**Primary Dependencies**:
- LibGDX 1.12.1 (gdx-backend-lwjgl3, gdx-platform:natives-desktop)
- kotlinx-coroutines-core 1.7.3 (already used by MapGenerator)

**Storage**: N/A (world state in memory; items.json on disk)

**Testing**: JUnit 5 + Kotlin test via `./gradlew test` (headless, no display required for core logic)

**Target Platform**: Desktop + GLES 3.0+; no GLES 2.0 fallback (confirmed in spec clarification)

**Project Type**: Desktop game (LibGDX)

**Performance Goals**: ≥ 30 FPS with 8 simultaneous point lights (SC-007); map generation ≤ 5 s for 20-room target (SC-004)

**Constraints**: Core package must have zero LibGDX imports (Principle I). TDD mandatory for all core logic (Principle II). Shadow maps require GLES 3.0+ depth texture support (guaranteed on confirmed targets).

**Scale/Scope**: Up to 8 active point lights per frame; up to ~200 Base Unit cells per generated map.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Core-Rendering Separation | ⚠️ PRE-EXISTING VIOLATION | `DynamicLighting` in `core.systems` imports LibGDX. Not introduced by this feature. `GpuLightEnvironment` and all new data classes are LibGDX-free. See Complexity Tracking. |
| II. Test-First | ⚠️ VIOLATION TO FIX | `com.roguelike.generation` has zero unit tests. This feature MUST add them. Red-Green-Refactor required. |
| III. SOLID / Single Responsibility | ✅ COMPLIANT | `ShadowRenderer` owns GPU resources; `GpuLightEnvironment` owns data; `MapGenerator` owns generation loop. No inline responsibility expansion. |
| IV. Data-Driven Game Mechanics | ✅ COMPLIANT | Light sources defined in `items.json` / `LightDef`; no rendering properties in core data classes. `GpuLightEnvironment` built from catalog values at runtime. |
| V. Simplicity / YAGNI | ✅ COMPLIANT | No new abstractions beyond what the spec requires. `DynamicLighting` is NOT refactored in this feature; its replacement is the natural outcome of the GPU pipeline. |

**Re-check after Phase 1**: Confirm `GpuLightEnvironment`, `DirectionalLightData`, and `PointLightData` have no LibGDX imports before implementation proceeds.

## Project Structure

### Documentation (this feature)

```text
specs/003-procedural-map-gpu-lighting/
├── plan.md              # This file
├── research.md          # Phase 0 output ✅
├── data-model.md        # Phase 1 output ✅
├── quickstart.md        # Phase 1 output ✅
├── checklists/
│   └── requirements.md  # Quality checklist ✅
└── tasks.md             # Phase 2 output (/speckit-tasks — not yet created)
```

### Source Code (new files for this feature)

```text
src/main/kotlin/com/roguelike/
├── core/
│   └── model/
│       └── lighting/
│           ├── DirectionalLightData.kt     [NEW — System B, no LibGDX]
│           ├── PointLightData.kt           [NEW — System B, no LibGDX]
│           └── GpuLightEnvironment.kt      [NEW — System B, no LibGDX]
└── rendering/
    └── ShadowRenderer.kt                   [NEW — System B, LibGDX allowed]

src/test/kotlin/com/roguelike/
├── generation/
│   ├── Vector3IntTest.kt                   [NEW — System A tests]
│   ├── SocketTest.kt                       [NEW — System A tests]
│   ├── SubmapTemplateTest.kt               [NEW — System A tests]
│   ├── MapGeneratorTest.kt                 [NEW — System A tests]
│   └── MapGeneratorIntegrationTest.kt      [NEW — System A tests]
└── core/
    └── model/
        └── lighting/
            └── GpuLightEnvironmentTest.kt  [NEW — System B data model test]
```

### Existing files modified

```text
src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt  [MODIFY — wire ShadowRenderer]
src/main/kotlin/com/roguelike/rendering/TileRenderer.kt   [MODIFY — if depth pass needs per-tile call]
```

**Structure Decision**: Single-project Kotlin/Gradle layout. New production code follows the existing `core/model/` and `rendering/` package conventions. New test code mirrors production package paths under `src/test/kotlin/`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| `DynamicLighting` in `core.systems` imports LibGDX (Principle I) | Pre-existing; GPU pipeline will eventually replace it | Migrating `DynamicLighting` to `rendering` in this feature would be a large refactor unrelated to delivering the GPU shadow pipeline; deferred to a dedicated cleanup feature |
| `FboCubemap` depth passes (6 per point light) | Required for omnidirectional point-light shadow occlusion (FR-004) | Dual-paraboloid shadow maps require complex reconstruction math; rejected for initial implementation |

## Phase 0 — Research ✅

All findings documented in `research.md`. Key decisions:

1. System A is already implemented; focus is tests and constitution compliance.
2. System B requires new `ShadowRenderer` + `GpuLightEnvironment` (no changes to `DynamicLighting`).
3. `FboCubemap` is available in LibGDX 1.12.1 on confirmed GLES 3.0+ target.
4. `items.json` catalog entries require no changes (FR-006).

## Phase 1 — Design ✅

Artifacts:
- `data-model.md` — entity definitions for both systems
- `quickstart.md` — per-story visual and automated test procedures
- No `contracts/` directory needed (this is a game with no external API surface)

## Phase 2 — Implementation Overview

*Detailed task breakdown generated by `/speckit-tasks`.*

### System A: Validate and Test Generation Package

Priority: P4/P5 per spec, but Constitution Principle II makes tests a prerequisite for any merge.

1. **Baseline green** — run `./gradlew test` and confirm existing tests pass.
2. **Write generation unit tests (RED)** — tests for `Vector3Int`, `Socket`, `SubmapTemplate`, `PlacedSubmap`, `MapGenerator` collision logic, socket matching, sealing.
3. **Confirm tests GREEN** — no new implementation code needed for most tests (the classes already work; tests are retrospective TDD).
4. **Write integration test** — `MapGeneratorIntegrationTest` using a minimal two-template library; assert collision-free + socket-compatible output.

### System B: GPU Lighting Pipeline

Priority: P1 (per-pixel lighting) → P2 (directional shadows) → P3 (point-light occlusion)

**Phase B-1: Data model**
1. Write `GpuLightEnvironmentTest` (RED)
2. Implement `DirectionalLightData`, `PointLightData`, `GpuLightEnvironment` in `core.model.lighting` (GREEN)

**Phase B-2: Directional shadow light (US1 + US2)**
1. Implement `ShadowRenderer` with `DirectionalShadowLight` + depth pass + main pass
2. Wire `WorldRenderer` to use `ShadowRenderer` when available
3. Confirm SC-001 (no staircase shadows), SC-002 (partial tile illumination) visually

**Phase B-3: Point-light occlusion (US3)**
1. Add `FboCubemap` pool to `ShadowRenderer` (one per active point light, max 8)
2. Add custom GLSL fragment shader for cube-map shadow sampling
3. Confirm SC-003 (no light bleed through walls) visually

**Phase B-4: Deprecate CPU path**
1. Ensure `ShadowRenderer` fully replaces `DynamicLighting.environmentForXxx()` in `WorldRenderer`
2. Verify SC-008 — all existing tests pass

### Execution Order

System A tests can run in parallel with System B implementation (different files, no shared dependencies).

```
Phase 1 (setup) → baseline green
  ├─ System A tests (T00x series)     — independent, headless
  └─ System B data model (T01x series) — independent, headless
       └─ System B rendering (T02x–T04x) — requires display for visual sign-off
```
