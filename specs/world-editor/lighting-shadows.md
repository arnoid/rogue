# World Editor — Per-Pixel Lighting & Shadow Specification

> **Source files**: `world_lit.vert.glsl`, `world_lit.frag.glsl`, `SimpleUI.kt` (lit pipeline), `MapEditor.kt` (renderGrid), `DebugRenderer.kt` (drawLitLine/drawLitWireframeCube)

## Overview

The world is rendered as axis-aligned unit cubes (1×1×1) on an integer grid. Each cube has 6 faces. When the **Lights editor mode** is active, all cube faces and wireframe edges receive **per-pixel lighting with GPU-accelerated shadow ray-marching** through a voxel occupancy grid.

The system uses a **two-pipeline** immediate-mode renderer:
1. **UI pipeline** — flat-color quads and text (no lighting)
2. **Lit pipeline** — quads with world-space position + normal, lit per-pixel by a fragment shader

## Architecture

### Rendering Flow (per frame)

1. **Build occupancy grid** (CPU): Iterate all world cells. Mark cell `(x,y,z)` as occupied (1) if the node contains any tile (floor or wall). Store as flat `IntArray[gridW * gridH * gridD]` indexed as `z * W * H + y * W + x`.
2. **Collect light sources** (CPU): Convert each `LightSource` to `LightData(x, y, z, intensity, r, g, b, radius)`.
3. **Upload to GPU**: Write occupancy grid to an SSBO and light data to a UBO via `updateLighting()`.
4. **Draw faces**: For each visible cube face, call `drawLitQuad()` which emits a quad with per-vertex world position and face normal into the lit pipeline's vertex buffer.
5. **Draw wireframe edges**: For each cube, call `drawLitWireframeCube()` which draws 12 lit line segments on top of faces.
6. **Flush**: The lit pipeline renders all accumulated lit quads in a single draw call, then the UI pipeline renders flat UI on top.

### GPU Data Layout

#### Lighting UBO (binding 0, set 0)

Total size: **1040 bytes** (16 + 512 + 512).

```
Offset  Type        Field
0       int         lightCount          (0–32)
4       int         gridW               (world width in cells)
8       int         gridH               (world height in cells)
12      int         gridD               (world depth in cells)
16      vec4[32]    lightPosIntensity   (xyz = world position, w = intensity)
528     vec4[32]    lightColorRadius    (rgb = color 0–1, a = radius in world units)
```

#### Occupancy Grid SSBO (binding 1, set 0)

Flat array of `uint` values. Size: `gridW × gridH × gridD × 4` bytes.  
Index formula: `z * gridW * gridH + y * gridW + x`.  
Value: `0` = empty, non-zero = occupied (blocks light).

### Vertex Format (Lit Pipeline)

12 floats per vertex, 6 vertices per quad (two triangles).

| Offset (floats) | Format | Attribute | Description |
|---|---|---|---|
| 0–1 | vec2 | `a_position` | Screen-space NDC position (−1 to +1) |
| 2–5 | vec4 | `a_color` | Base diffuse color (RGBA, pre-multiplied with face shade) |
| 6–8 | vec3 | `a_worldPos` | World-space 3D position of this vertex |
| 9–11 | vec3 | `a_normal` | Unit face normal in world space |

Stride: 48 bytes per vertex.

## Vertex Shader (`world_lit.vert.glsl`)

Pass-through. Receives NDC screen position directly (CPU-projected). Forwards color, world position, and normal to fragment shader as interpolated varyings.

```glsl
gl_Position = vec4(a_position, 0.0, 1.0);
```

The CPU projects 3D world corners to screen pixels, then converts to NDC. The world-space positions are passed separately for the fragment shader to use for lighting calculations.

## Fragment Shader (`world_lit.frag.glsl`)

### Inputs (interpolated per pixel)

| Varying | Type | Description |
|---|---|---|
| `v_color` | vec4 | Base color with environment face shade baked in |
| `v_worldPos` | vec3 | Interpolated world-space position of this pixel |
| `v_normal` | vec3 | Interpolated face normal |

### Lighting Model

1. **No-light path**: If `lightCount == 0`, output `v_color` directly (environment lighting is pre-baked into the vertex color).

2. **Self-shadow offset**: Before shadow testing, push the surface position outward along the normal:
   ```
   surfacePos = v_worldPos + N * 0.05
   ```
   This prevents self-shadowing artifacts at cell boundaries where `floor()` would map the pixel back into its own occupied cell.

3. **Per-light accumulation**: Start with ambient `vec3(0.15)`. For each light:
   - **Range check**: Skip if `distance(surfacePos, lightPos) > radius`.
   - **Lambertian diffuse**: `NdotL = max(dot(N, L), 0)`. Skip if ≤ 0 (back-facing).
   - **Shadow test**: Ray-march from `surfacePos` to `lightPos` through the occupancy grid using **3D DDA** (see below). If any occupied cell is hit, the light is fully blocked.
   - **Attenuation**: `intensity / (1 + dist² × 0.1)`.
   - **Contribution**: `lightColor × attenuation × NdotL`.

4. **Final color**: `baseColor × clamp(totalLight, 0, 1)`.

### Shadow Algorithm: 3D DDA (Amanatides & Woo)

The shadow ray-march uses a proper voxel traversal algorithm, not fixed-step sampling. This visits **every** grid cell the ray passes through with zero skipping.

```
Input: from (shadow ray origin), to (light position)
Output: true if any occupied cell on the ray

1. Compute ray direction dir = normalize(to - from)
2. Start voxel (ix,iy,iz) = floor(from)
3. End voxel (ex,ey,ez) = floor(to)
4. Step signs: sx = sign(dir.x), sy = sign(dir.y), sz = sign(dir.z)
5. Compute tMax per axis = distance along ray to first voxel boundary
6. Compute tDelta per axis = distance along ray to cross one full voxel
7. Loop (max 64 iterations):
   a. If cell (ix,iy,iz) is occupied → return true
   b. If (ix,iy,iz) == (ex,ey,ez) → return false (reached light)
   c. Advance along the axis with smallest tMax:
      - if tMaxX < tMaxY && tMaxX < tMaxZ: ix += sx, tMaxX += tDeltaX
      - else if tMaxY < tMaxZ: iy += sy, tMaxY += tDeltaY
      - else: iz += sz, tMaxZ += tDeltaZ
```

The `isOccupied(ix,iy,iz)` function bounds-checks against grid dimensions and returns `false` for out-of-bounds cells (rays that leave the grid don't hit anything).

## CPU-Side Rendering (`MapEditor.renderGrid`)

### Cube Face Definition

Six faces per unit cube, each defined by 4 corner offsets from the cube origin `(x, y, z)`:

| Face | Corners (offsets from origin) | Normal | Environment Shade |
|---|---|---|---|
| Top (Z+) | (0,0,1) (1,0,1) (1,1,1) (0,1,1) | (0,0,+1) | 1.00 |
| Bottom (Z−) | (0,1,0) (1,1,0) (1,0,0) (0,0,0) | (0,0,−1) | 0.35 |
| North (Y+) | (0,1,0) (0,1,1) (1,1,1) (1,1,0) | (0,+1,0) | 0.70 |
| South (Y−) | (1,0,0) (1,0,1) (0,0,1) (0,0,0) | (0,−1,0) | 0.55 |
| East (X+) | (1,0,0) (1,1,0) (1,1,1) (1,0,1) | (+1,0,0) | 0.60 |
| West (X−) | (0,1,0) (0,0,0) (0,0,1) (0,1,1) | (−1,0,0) | 0.50 |

### Back-Face Culling

For each face, compute `dot(faceNormal, viewDir)` where `viewDir = cameraPos − cubeCenter`. Cull (skip) the face if `dot < 0`.

### Tile Colors

| Content | Base RGB |
|---|---|
| Floor | (0.25, 0.30, 0.40) |
| Wall | (0.55, 0.42, 0.30) |

### Layer Dimming

| Layer | Dimming Factor |
|---|---|
| Active (currentZ) | 1.0 |
| Lower layers | 0.45 |

### Per-Face Color Passed to Shader

The base color passed to `drawLitQuad` is: `baseRGB × layerDim × faceShade`.

This means the environment shade (directional ambient approximation) is **baked into the vertex color**. The GPU shader then multiplies this by the computed `totalLight` value.

### Painter's Algorithm

Tiles are sorted by **squared distance to camera** (farthest first). This ensures correct visual ordering without a depth buffer.

### Wireframe Edges

After drawing solid faces, wireframe edges are drawn on top using `drawLitWireframeCube()`:
- 12 edges per cube, each as a thin lit quad (line thickness 1.5 px)
- Edge color: dark outline `(0.15, 0.18, 0.22) × layerDim`, alpha 0.7
- Edge normal: computed per-edge as direction from edge midpoint to camera (so edges always receive light facing the viewer)

## `drawLitQuad` API

```
drawLitQuad(
    sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3,   // 4 screen-space pixel corners
    wx0, wy0, wz0, wx1, wy1, wz1,              // 4 world-space 3D corners
    wx2, wy2, wz2, wx3, wy3, wz3,
    nx, ny, nz,                                  // face normal (shared for all 4 vertices)
    r, g, b, a                                   // base color (pre-shaded)
)
```

Internally:
1. Convert screen pixels to NDC: `ndcX = (px / screenWidth) * 2 - 1`
2. Choose triangulation diagonal by cross-product test (same as `drawQuad`)
3. Emit 6 vertices (2 triangles) into the lit vertex buffer

## `updateLighting` API

```
updateLighting(
    lights: List<LightData>,        // up to 32 point lights
    occupancyGrid: IntArray,        // flat occupancy array
    gridW: Int, gridH: Int, gridD: Int
)
```

Internally:
1. Map lighting UBO memory, write header (4 ints) + 32 `vec4` light positions + 32 `vec4` light colors
2. If occupancy grid size changed, recreate the SSBO buffer and re-bind the descriptor set
3. Map SSBO memory, copy the occupancy grid data

## Light Source Data Model

```kotlin
data class LightSource(
    val id: String,
    var x: Float,          // world X (float, e.g. 7.5 = center of cell 7)
    var y: Float,          // world Y
    var z: Float,          // world Z (e.g. 0.8 = slightly above floor at z=0)
    var intensity: Float,  // brightness multiplier (default 5.0)
    var radius: Float,     // max range in world units (default 5.0)
    var colorHex: String   // RGB hex e.g. "ffcc88"
)
```

## Vulkan Pipeline Configuration

The lit pipeline uses:
- **Vertex input**: 4 attributes (vec2 + vec4 + vec3 + vec3), stride 48 bytes
- **Topology**: Triangle list
- **Culling**: None (back-face culling is done CPU-side)
- **Depth test**: Disabled (painter's algorithm handles ordering)
- **Blend**: Standard alpha blend (srcAlpha, 1-srcAlpha)
- **Dynamic state**: Viewport + scissor
- **Descriptor set**: binding 0 = UBO (lighting), binding 1 = SSBO (occupancy grid)

## Key Implementation Details

1. **Lit quads render before UI quads** in the command buffer. This ensures wireframe edges and UI elements draw on top.
2. **The occupancy grid only records the current world state** — it includes ALL layers (0 to depth-1), not just visible layers. This way shadows from upper floors correctly block light on lower floors.
3. **The lit pipeline is a separate Vulkan pipeline** with its own shaders, vertex buffer, descriptor set layout, UBO, and SSBO. It shares the same render pass as the UI pipeline.
4. **`beginFrame()` resets both quad counters** (UI and lit) to zero.
5. **Maximum 16384 lit quads per frame** (same as UI quads). Each quad = 6 vertices × 12 floats = 72 floats.

