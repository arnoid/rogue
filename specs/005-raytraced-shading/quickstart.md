# Quickstart: Stencil Shadow Volume Lighting

**Feature**: 005-raytraced-shading

## Prerequisites

- JDK 17
- Kotlin 1.9.22 (managed by Gradle)
- Desktop GPU with OpenGL 3.2 core profile support

## Build & Test

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run the game
./gradlew run
```

## Key Files (New)

| File | Purpose |
|------|---------|
| `src/main/kotlin/com/roguelike/rendering/ShadowVolumeBuilder.kt` | CPU-side silhouette detection, edge extrusion, cap generation |
| `src/main/kotlin/com/roguelike/rendering/ShadowVolumeRenderer.kt` | Multi-pass render orchestrator (ambient → stencil → lit) |
| `src/main/kotlin/com/roguelike/rendering/ShadowVolumeShaderProvider.kt` | Loads and provides shadow volume, lit-pass, and ambient shaders |
| `src/main/resources/shaders/shadow_volume.vert.glsl` | Position-only vertex transform for shadow volumes |
| `src/main/resources/shaders/shadow_volume.frag.glsl` | No-op fragment shader (stencil-only pass) |
| `src/main/resources/shaders/lit_pass.vert.glsl` | Vertex shader outputting world pos, normal, UV |
| `src/main/resources/shaders/lit_pass.frag.glsl` | Per-light diffuse + inverse-square attenuation |
| `src/main/resources/shaders/ambient_pass.vert.glsl` | Vertex shader for ambient pass |
| `src/main/resources/shaders/ambient_pass.frag.glsl` | Ambient-only lighting |

## Key Files (Modified)

| File | Change |
|------|--------|
| `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt` | Replace `dynamicLighting` param with `shadowVolumeRenderer` |
| `src/main/kotlin/com/roguelike/RoguelikeGame.kt` | Wire `ShadowVolumeRenderer` into render loop |

## Key Files (Deleted)

| File | Reason |
|------|--------|
| `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt` | Replaced by shadow volume pipeline |
| `src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt` | Replaced |
| `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt` | Replaced |
| `src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt` | Coupled to deleted systems |

## Architecture Overview

```
LightDef (core/model)          ← Pure data, LibGDX-free
       │
       ▼
PointLightData (rendering)     ← Runtime light with world position
       │
       ▼
ShadowVolumeBuilder            ← Silhouette → extrusion → capped mesh
       │
       ▼
ShadowVolumeRenderer           ← Orchestrates render passes:
       │                          1. Ambient pass (full scene)
       │                          2. Per-light: stencil mark → lit pass
       ▼
WorldRenderer                  ← Submits scene geometry per pass
```

## Render Pass Sequence

1. **Ambient pass**: Render all geometry → color + depth buffer (ambient only)
2. **Per light** (up to 4):
   - Clear stencil to 0
   - Render shadow volume (front faces → incr on depth-fail)
   - Render shadow volume (back faces → decr on depth-fail)
   - Render scene with stencil==0 test, additive blending, per-light diffuse+attenuation

## Testing Strategy

- **Unit tests**: `ShadowVolumeBuilderTest` — test silhouette detection, extrusion geometry, cap generation on simple meshes (cube, L-shape). Pure math, no GPU required.
- **Visual verification**: Manual in-game testing for shadow correctness, light bleed, self-shadowing.
- **Performance**: FPS counter with 4 active lights in a dungeon room.

