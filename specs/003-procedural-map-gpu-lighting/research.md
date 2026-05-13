# Research: Procedural Map Generator and GPU Lighting Engine

**Feature**: `003-procedural-map-gpu-lighting`
**Date**: 2026-05-13

---

## Finding 1 — System A (Map Generator) Status

**Decision**: System A is already substantially implemented in `com.roguelike.generation`.

**What exists**:
- `Vector3Int` — 3D integer vector with rotation helpers
- `Socket` / `SocketState` — connection point with OPEN/CONNECTED/SEALED states
- `SubmapTemplate` — room blueprint with footprint + sockets, including `allRotations()`
- `PlacedSubmap` — committed instance at an absolute grid position
- `MapGenerator` — coroutine-based generator with `debugChannel` / `decisionChannel` and collision check
- `GenerationDebugUI` — LibGDX Stage UI with pink "I do agree!" and gray "I do not agree!" buttons
- `ProceduralMapManager` — entry point wiring coroutine scope to game loop
- `WorldStamper` — stamps a `PlacedSubmap` into a `World` instance

**What is missing**:
1. Unit tests — zero test files exist for the `generation` package (Constitution Principle II violation)
2. `Vector3Int`, `Socket`, `SocketState`, `SubmapTemplate`, `PlacedSubmap` contain no LibGDX imports and could live in `core.generation` per Principle I, but current location in `generation` is acceptable because `generation` is not explicitly the `core` package — this is a pre-existing boundary decision
3. `MapGenerator` imports only `kotlinx.coroutines` (no LibGDX) — compliant
4. `GenerationDebugUI` imports LibGDX and belongs in `rendering` or as a standalone top-level package — current placement is acceptable (it is not in `core`)

**Rationale**: Implement tests for the existing classes without moving them; no package migration needed unless a future plan explicitly targets that cleanup.

**Alternatives considered**: Rewrite from scratch to place in `core` — rejected, would be pure churn with zero user value.

---

## Finding 2 — System B (GPU Lighting) Architecture

**Decision**: Two-pass shadow mapping via LibGDX `DirectionalShadowLight` + `DepthShaderProvider`; omnidirectional point-light shadows via `FboCubemap` + custom GLSL.

**What exists**: None. `DirectionalShadowLight`, `DepthShaderProvider`, and `FboCubemap` are zero-referenced in the current codebase.

**Current approach**: `DynamicLighting` in `core.systems` computes per-surface `Environment` objects (CPU LOS + mask). `WorldRenderer` consumes these per-surface environments on every frame. There is no shadow map pass.

**Why the current approach cannot be extended**:
- Shadow maps are per-light, not per-surface; the current per-surface environment architecture is architecturally orthogonal to shadow mapping.
- `DynamicLighting` is in `core.systems` but imports `com.badlogic.gdx.*` — a pre-existing Principle I violation.
- Adding shadow map logic to `DynamicLighting` would deepen the Principle I violation.

**Decision**: Introduce `rendering.ShadowRenderer` that owns:
1. A `DirectionalShadowLight` instance and depth pass.
2. A `GpuLightEnvironment` that replaces per-surface `DynamicLighting.environmentForXxx()` calls.
3. FboCubemap management for up to N point lights (N=8 per SC-007).

**Rationale**: Isolates all GPU resources in `rendering`, honours Principle I, and is independently testable through visual acceptance criteria (unit tests for GPU state are impractical without a display).

**Alternatives considered**:
- Extend `WorldRenderer` inline — rejected; violates Principle III (single responsibility).
- Move `DynamicLighting` to `rendering` — deferred; complex migration, not in scope for this feature.

---

## Finding 3 — DynamicLighting Principle I Pre-Existing Violation

**Decision**: Document violation; do not fix in this feature.

**Context**: `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt` imports `com.badlogic.gdx.graphics.Color`, `com.badlogic.gdx.graphics.g3d.Environment`, `com.badlogic.gdx.graphics.g3d.environment.PointLight`, and `com.badlogic.gdx.math.Vector3`. This is in `com.roguelike.core.systems` — a clear Principle I violation.

**Why not fixed here**: Moving `DynamicLighting` to `rendering` is a significant refactor with broad callsite changes. The GPU lighting plan calls for `ShadowRenderer` + `GpuLightEnvironment` to eventually replace `DynamicLighting` entirely (FR-005). The replacement will naturally resolve the violation without a separate migration.

**Tracked in**: Complexity Tracking table in `plan.md`.

---

## Finding 4 — FboCubemap / Omnidirectional Shadow Approach

**Decision**: Use `FboCubemap` (LibGDX 1.12.1 — available) for per-point-light cube-map depth passes. Render the scene six times per dynamic point light into a depth-format FBO cube face, then sample in a custom GLSL fragment shader.

**LibGDX version**: 1.12.1 — `FboCubemap` is available; `FrameBufferCubemap` extends `GLFrameBuffer<Cubemap>` and supports depth attachment on GLES 3.0+ (which is our confirmed target).

**Performance budget**: 8 point lights × 6 faces = 48 depth passes per frame. On reference hardware at 30 FPS this is acceptable only with frustum culling per face. The `ShadowRenderer` MUST skip cube faces whose frustum contains no geometry.

**Rationale**: No third-party library needed; FboCubemap is built into LibGDX. Custom GLSL depth sampling is required because `DefaultShader` does not wire `samplerCube` uniforms.

**Alternatives considered**:
- Dual-paraboloid shadow maps — fewer passes (2 per light), but complex reconstruction; rejected for initial implementation.
- Cascaded shadow maps for point lights — not a standard technique; rejected.

---

## Finding 5 — GpuLightEnvironment Design

**Decision**: Replace the per-surface `DynamicLighting.environmentForXxx()` call pattern with a single `GpuLightEnvironment` object per frame, held by `ShadowRenderer` and passed unchanged to all surfaces.

**Data model**:
```
GpuLightEnvironment (core.model, no LibGDX)
  - directionalLight: DirectionalLightData (direction, color, intensity)
  - pointLights: List<PointLightData> (position, color, intensity, range)

ShadowRenderer (rendering, LibGDX)
  - Converts GpuLightEnvironment to LibGDX Environment + shadow maps
  - Manages FboCubemap pool
  - Owns depth pass and main pass
```

**Core-Rendering separation**: `GpuLightEnvironment` and its sub-types carry no LibGDX imports. `ShadowRenderer` converts them to LibGDX types at render time.

---

## Finding 6 — items.json Light Source Compatibility (FR-006)

**Decision**: No changes to `items.json` required for the GPU pipeline.

**Context**: The existing `LightDef` fields (`range`, `intensity`, `color`, `shape`, `coneDegrees`) map directly to LibGDX `PointLight` and `DirectionalLight` parameters. `GpuLightEnvironment` builds from `LightDef` values. Existing catalog entries are automatically compatible.

---

## Finding 7 — Test Strategy for GPU Systems

**Decision**: Unit tests cover pure-logic data classes (`GpuLightEnvironment`, data conversion); GPU pipeline validated through visual acceptance criteria (SC-001 through SC-003, SC-007).

**Rationale**: LibGDX GPU resources cannot be instantiated without an OpenGL context. Constitution Principle II mandates TDD for core logic; the rendering pipeline is explicitly excluded from headless testing.
