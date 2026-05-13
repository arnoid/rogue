# Data Model: Stencil Shadow Volume Lighting

**Feature**: 005-raytraced-shading | **Date**: 2026-05-13

## Existing Entities (KEEP)

### LightDef
**Package**: `com.roguelike.core.model`  
**Purpose**: Pure data class describing a light source's properties.

| Field | Type | Description |
|-------|------|-------------|
| color | RGB (3 floats) | Light color |
| intensity | Float | Light brightness multiplier |
| radius | Float | Maximum range of the light |
| shape | LightShape | Enum: POINT, CONE, etc. |
| direction | LightDirection | Enum: directional hint |

**Notes**: LibGDX-free. Consumed by `ShadowVolumeRenderer` to configure per-light uniforms.

---

## New Entities

### SilhouetteEdge
**Package**: `com.roguelike.rendering`  
**Purpose**: Represents one edge of a mesh's silhouette relative to a light source.

| Field | Type | Description |
|-------|------|-------------|
| v0 | Vector3 | First vertex of the edge |
| v1 | Vector3 | Second vertex of the edge |

**Validation**: v0 ≠ v1 (degenerate edge).

### ShadowVolumeMesh
**Package**: `com.roguelike.rendering`  
**Purpose**: The extruded shadow volume geometry for one occluder mesh from one light.

| Field | Type | Description |
|-------|------|-------------|
| vertices | FloatArray | Position-only vertex data (x,y,z per vertex) |
| indices | ShortArray | Triangle list indices |
| vertexCount | Int | Number of vertices |
| indexCount | Int | Number of indices |

**Relationships**: Built from `SilhouetteEdge[]` + front/back caps by `ShadowVolumeBuilder`.

### SilhouetteCache
**Package**: `com.roguelike.rendering`  
**Purpose**: Caches computed silhouette and extruded geometry for a static mesh/light pair.

| Field | Type | Description |
|-------|------|-------------|
| meshId | Int | Identity hash of the source mesh |
| lightId | Int | Identity of the light source |
| lightPos | Vector3 | Light position when cache was built |
| edges | List&lt;SilhouetteEdge&gt; | Cached silhouette edges |
| shadowVolume | ShadowVolumeMesh | Cached extruded geometry |
| valid | Boolean | False if light or mesh moved |

**Invalidation**: Set `valid = false` when `lightPos` changes or mesh is modified.

### PointLightData
**Package**: `com.roguelike.rendering`  
**Purpose**: Runtime representation of an active point light for the shadow volume pipeline.

| Field | Type | Description |
|-------|------|-------------|
| position | Vector3 | World-space position |
| color | Color (vec3) | RGB light color |
| intensity | Float | Brightness multiplier |
| radius | Float | Maximum range (attenuation cutoff) |

**Relationships**: Created from `LightDef` + world-space position of the light-bearing entity.

---

## State Transitions

### Shadow Volume Pipeline State Machine (per frame)

```
IDLE
  │
  ▼
AMBIENT_PASS          Render full scene with ambient-only shader
  │                   Write to color + depth buffer
  ▼
┌─FOR_EACH_LIGHT──────────────────────────┐
│  STENCIL_CLEAR      glClear(STENCIL)    │
│    │                                     │
│    ▼                                     │
│  STENCIL_FRONT      Cull back, incr on  │
│    │                depth-fail           │
│    ▼                                     │
│  STENCIL_BACK       Cull front, decr on │
│    │                depth-fail           │
│    ▼                                     │
│  LIT_PASS           Stencil==0 only,    │
│                     additive blend      │
└──────────────────────────────────────────┘
  │
  ▼
IDLE
```

---

## Shader Uniform Contracts

### shadow_volume.vert.glsl
| Uniform | Type | Source |
|---------|------|--------|
| u_projViewTrans | mat4 | camera.combined |
| u_worldTrans | mat4 | model transform |

### lit_pass.frag.glsl
| Uniform | Type | Source |
|---------|------|--------|
| u_LightPos | vec3 | PointLightData.position |
| u_LightColor | vec3 | PointLightData.color |
| u_LightIntensity | float | PointLightData.intensity |
| u_LightRadius | float | PointLightData.radius |
| u_diffuseTexture | sampler2D | Material texture |
| u_diffuseColor | vec4 | Material base color |

### ambient_pass.frag.glsl
| Uniform | Type | Source |
|---------|------|--------|
| u_ambientColor | vec3 | Global ambient (0.05–0.1) |
| u_diffuseTexture | sampler2D | Material texture |
| u_diffuseColor | vec4 | Material base color |

