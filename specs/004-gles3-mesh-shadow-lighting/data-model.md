# Data Model: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Feature**: `004-gles3-mesh-shadow-lighting` | **Date**: 2026-05-13

---

## Retained Entities (unchanged from previous sprint)

### DirectionalLightData
**Package**: `com.roguelike.core.model.lighting`
**Status**: KEEP — no changes needed

| Field | Type | Description |
|-------|------|-------------|
| directionX | Float | X component of normalized light direction |
| directionY | Float | Y component of normalized light direction |
| directionZ | Float | Z component of normalized light direction |
| r | Float | Red channel (0..1) |
| g | Float | Green channel (0..1) |
| b | Float | Blue channel (0..1) |
| intensity | Float | Brightness multiplier |

---

### PointLightData
**Package**: `com.roguelike.core.model.lighting`
**Status**: KEEP — no changes needed

| Field | Type | Description |
|-------|------|-------------|
| x | Float | World-space X position |
| y | Float | World-space Y position |
| z | Float | World-space Z position |
| r | Float | Red channel (0..1) |
| g | Float | Green channel (0..1) |
| b | Float | Blue channel (0..1) |
| intensity | Float | Combined brightness and falloff radius |

---

### GpuLightEnvironment
**Package**: `com.roguelike.core.model.lighting`
**Status**: KEEP — no changes needed

| Field | Type | Constraint |
|-------|------|------------|
| directionalLight | DirectionalLightData? | nullable; absent = no directional light |
| pointLights | List\<PointLightData\> | max 8 elements (enforced by constructor) |
| ambientR | Float | 0..1 |
| ambientG | Float | 0..1 |
| ambientB | Float | 0..1 |

**Factory**: `GpuLightEnvironment.build()` — truncates `pointLights` to 8 silently.

---

## New / Replaced Entities

### Gles3LightingShader
**Package**: `com.roguelike.rendering`
**File**: `src/main/kotlin/com/roguelike/rendering/Gles3LightingShader.kt`
**Status**: NEW — replaces the LibGDX `DefaultShader` for the main render pass

Implements `com.badlogic.gdx.graphics.g3d.Shader`. Compiles `gles3_lighting.vert.glsl` + `gles3_lighting.frag.glsl` at construction time. Holds uniform locations for all lighting uniforms listed in research Finding 7.

| Responsibility | Detail |
|----------------|--------|
| Geometry uniforms | `u_projViewTrans`, `u_worldTrans`, `u_normalMatrix`, `u_diffuseTexture` |
| Ambient | `u_ambientColor` — set from `GpuLightEnvironment.ambient*` |
| Directional light | `u_dirLightDir`, `u_dirLightColor`, `u_hasDirLight`, `u_shadowMapProjViewTrans`, `u_shadowMap` |
| Point lights | `u_pointLightPos[8]`, `u_pointLightColor[8]`, `u_pointLightIntensity[8]`, `u_pointLightCount` |
| Point shadows | `u_pointShadowCube[8]`, `u_pointShadowFarPlane[8]`, `u_hasPointShadow[8]` |
| Texture units | unit 0 = diffuse, units 1 = shadow map, units 2–9 = point shadow cubemaps |

**Lifecycle**: Created once per `Gles3ShaderProvider` instance. `dispose()` must be called on screen dispose.

---

### Gles3ShaderProvider
**Package**: `com.roguelike.rendering`
**File**: `src/main/kotlin/com/roguelike/rendering/Gles3ShaderProvider.kt`
**Status**: NEW

Implements `com.badlogic.gdx.graphics.g3d.utils.ShaderProvider`. Returns the same `Gles3LightingShader` instance for all renderables (single-shader approach — all tiles share the same material + shader). Caches the single instance; `dispose()` cleans up.

---

### ShadowRenderer (replaced internals)
**Package**: `com.roguelike.rendering`
**File**: `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
**Status**: REPLACE — new implementation, same public API surface

| Field | Type | Description |
|-------|------|-------------|
| mainBatch | ModelBatch | Uses `Gles3ShaderProvider` |
| shadowBatch | ModelBatch | Uses `DepthShaderProvider` (directional depth pass) |
| shadowLight | DirectionalShadowLight? | Orthographic depth map, 2048×2048 |
| cubemapPool | Array\<FrameBufferCubemap?\>(8) | One per point light, 512×512, lazy-init |
| cubemapFarPlane | FloatArray(8) | Per-light far plane stored for shader uniform |
| cubemapCamera | PerspectiveCamera(90°) | Shared camera for cubemap face renders |
| sceneCentre | Vector3 | Directional shadow frustum centre |

**Methods (same signatures as previous)**:
- `initDirectionalLight(data: DirectionalLightData)` — create/replace `DirectionalShadowLight`
- `setSceneCentre(x, y, z)` — update frustum centre
- `render(camera, gpuLightEnv, renderScene)` — full pipeline (depth passes + main pass)
- `dispose()` — release all GPU resources

**Render pipeline detail** (`render()` method):
1. For each `PointLightData` in `gpuLightEnv.pointLights`: call `renderCubemapDepthPass(i, light, renderScene)`
2. If `shadowLight != null`: directional depth pass (same as current)
3. Main pass: bind cubemap textures to units 2–9, set all uniforms on `Gles3LightingShader`, call `renderScene(mainBatch, env)`

**Companion methods**:
- `fromActor(actor, ambient*)` — KEEP, builds `GpuLightEnvironment` from lit inventory
- `buildEnvironment(gpuLightEnv)` — REMOVE (only used internally now; `Gles3LightingShader` sets uniforms directly, no LibGDX `Environment` object needed for the custom shader path)

---

### WorldRenderer (simplified)
**Package**: `com.roguelike.rendering`
**File**: `src/main/kotlin/com/roguelike/rendering/WorldRenderer.kt`
**Status**: SIMPLIFY — remove legacy `DynamicLighting` overload

Only one `render()` method remains:
```
render(world, camera, shadowRenderer, gpuLightEnv, maxZ)
```
The legacy `render(world, batch, environment, maxZ, dynamicLighting)` overload and `envForTile()` helper are deleted.

---

## Deleted Entities

| Class | File | Reason |
|-------|------|--------|
| `DynamicLighting` | `core/systems/DynamicLighting.kt` | CPU per-cell lighting — replaced by GPU |
| `SurfaceLighting` | `core/systems/SurfaceLighting.kt` | CPU per-surface variant — replaced by GPU |
| `LightingSystem` | `core/systems/LightingSystem.kt` | CPU raycasting map — replaced by GPU |
| `LightingDiagnostics` | `core/systems/LightingDiagnostics.kt` | Diagnostics for deleted system |
| `BvhOccluder` | `rendering/BvhOccluder.kt` | CPU BVH for spatial occlusion — not needed with GPU shadows |
| `LightMap` | (inner class in LightingSystem.kt) | Deleted with LightingSystem |
| `ModelOcclusionProvider` | (interface in DynamicLighting.kt) | Deleted with DynamicLighting |

---

## Shader Assets

### gles3_lighting.vert.glsl
**Path**: `src/main/resources/shaders/gles3_lighting.vert.glsl`

Inputs: `a_position`, `a_normal`, `a_texCoord0`
Uniforms: `u_projViewTrans`, `u_worldTrans`, `u_normalMatrix`, `u_shadowMapProjViewTrans`
Outputs: `v_texCoord`, `v_worldPos`, `v_worldNormal`, `v_shadowCoord`

### gles3_lighting.frag.glsl
**Path**: `src/main/resources/shaders/gles3_lighting.frag.glsl`

Inputs: `v_texCoord`, `v_worldPos`, `v_worldNormal`, `v_shadowCoord`
Uniforms: all lighting uniforms (see research Finding 7)
Output: `fragColor`

Lighting model: Blinn-Phong diffuse + ambient. No specular (tile surfaces are diffuse-only). Shadow factor: directional via PCF 3×3 on `sampler2D`, point lights via single-sample cubemap depth compare.

### point_shadow.frag.glsl
**Path**: `src/main/resources/shaders/point_shadow.frag.glsl`
**Status**: DELETE — replaced by gles3_lighting.frag.glsl
