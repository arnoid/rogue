# Feature Specification: Stencil Shadow Volume Lighting

**Feature Branch**: `005-raytraced-shading`

**Created**: 2026-05-13

**Status**: Draft

**Input**: User description: "Implement LibGDX OpenGL 3.2 wireframe shadow volumes using stencil buffer depth-fail (Carmack's Reverse) for real-time shadow casting from point lights."

## Overview

Replace the existing CPU-based `DynamicLighting` system with a GPU-driven stencil shadow volume pipeline. Shadow volumes are constructed by extruding silhouette edges of occluder meshes away from each light source to infinity, then using the stencil buffer (depth-fail method) to determine which fragments are in shadow. This produces pixel-perfect hard shadows that respect mesh geometry, with correct self-shadowing and zero light bleed through walls.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Mesh-Accurate Shadow Casting (Priority: P1) 🎯 MVP

As a player, I see shadows that follow the exact silhouette of 3D objects in the world — walls cast hard-edged shadows, pillars cast rectangular shadows, and no light bleeds through geometry.

**Why this priority**: This is the core requirement. Without per-mesh shadow casting via shadow volumes the entire feature has no value. All other stories depend on this.

**Independent Test**: Place a single point light next to a wall segment in a generated map. Verify the shadow on the floor precisely follows the wall geometry with no bleed-through.

**Acceptance Scenarios**:

1. **Given** a point light adjacent to a wall mesh, **When** the scene is rendered, **Then** the shadow on the floor matches the wall's geometric silhouette exactly with no light bleeding through the wall.
2. **Given** a light inside a closed room, **When** the scene is rendered, **Then** no light escapes through wall or ceiling geometry to illuminate adjacent rooms.
3. **Given** a pillar mesh between a light and the floor, **When** the scene is rendered, **Then** the pillar casts a shadow whose edges precisely match its silhouette from the light's perspective.

---

### User Story 2 — Self-Shadowing (Priority: P2)

As a player, I see objects correctly casting shadows upon themselves — a wall's own geometry produces shadow on itself where faces turn away from the light source.

**Why this priority**: Without self-shadowing, lit objects appear flat and unrealistic. Requires Story 1's stencil pipeline.

**Independent Test**: Place a single point light near a corner where two walls meet at 90°. Verify the wall facing away from the light is dark while the wall facing the light is bright, with the transition at the geometric edge.

**Acceptance Scenarios**:

1. **Given** an L-shaped wall and a nearby point light, **When** rendered, **Then** the face perpendicular to the light direction is dark and the face facing the light is bright.
2. **Given** a complex mesh (e.g. furniture), **When** lit from one side, **Then** the far side of the mesh is visibly darker due to self-shadowing.

---

### User Story 3 — Multiple Dynamic Point Lights (Priority: P3)

As a player, I see multiple torches or light sources simultaneously illuminating surfaces with additive blending, each casting its own independent shadow volume.

**Why this priority**: Game levels have many light sources. Without multi-light support a single torch per scene would be the limit.

**Independent Test**: Place two torches at different positions in the same corridor. Verify both cast visible light and each casts its own independent shadow, with the area between them receiving light from both sources.

**Acceptance Scenarios**:

1. **Given** two point lights in a corridor, **When** rendered, **Then** each light casts its own shadow volume independently. A wall blocking one light does not affect the other.
2. **Given** overlapping light radii, **When** rendered, **Then** surfaces in the overlap region receive additive illumination from both lights.

---

### User Story 4 — Ambient Baseline (Priority: P4)

As a player, fully shadowed surfaces are dim but not pitch black — a minimum ambient term ensures visibility.

**Why this priority**: Pure black is unplayable. Requires Story 1 pipeline.

**Independent Test**: Drop all torches → room is dark but grey/dim, not absolute black.

**Acceptance Scenarios**:

1. **Given** no active light sources, **When** rendered, **Then** all surfaces are lit by a low ambient value (≈ 0.05–0.1) so geometry is faintly visible.

---

### User Story 5 — Performance (Priority: P5)

As a player, the game maintains ≥ 30 FPS with up to 4 simultaneous point lights casting shadow volumes.

**Why this priority**: Shadow volumes are fill-rate intensive. Performance must be validated.

**Independent Test**: Equip 4 torches, observe FPS counter. Confirm ≥ 30 FPS.

**Acceptance Scenarios**:

1. **Given** 4 active point lights on a standard desktop GPU, **When** rendering a typical dungeon room, **Then** FPS ≥ 30.
2. **Given** a light source far from the camera, **When** its shadow volume extends off-screen, **Then** scissor or distance culling prevents wasted fill.

---

## Technical Requirements

### TR-1: Shadow Volume Construction (CPU-side, Kotlin)

Construct shadow volume geometry per light per frame:

1. **Face Classification**: For each triangle in each occluder mesh, compute `dot(faceNormal, lightDirection)`. Faces with positive dot product face toward the light (front-facing); others are back-facing.
2. **Silhouette Detection**: Find all edges shared by exactly one front-facing and one back-facing triangle. These edges form the silhouette with respect to the light.
3. **Extrusion**: For each silhouette edge (v0, v1), create a quad by extending both vertices away from the light to a large distance (or to infinity using `w=0` homogeneous coordinates). The four vertices of the quad are: v0, v1, v1_extruded, v0_extruded.
4. **Capping**: Add a front cap (the original front-facing triangles) and a back cap (the extruded back-facing triangles projected to infinity) to form a closed volume. Both caps are required for the depth-fail method.

**Data**: Shadow volume meshes are rendered as triangle lists with no textures or materials — geometry only.

### TR-2: Stencil Buffer Rendering (Depth-Fail / Carmack's Reverse)

The depth-fail method handles the camera being inside a shadow volume correctly.

**Per light, the render passes are:**

1. **Ambient pass** (once, before any light): Render the entire scene with ambient-only lighting into the colour and depth buffers. This establishes the depth buffer for subsequent stencil tests.

2. **For each active light:**
   a. Clear the stencil buffer to 0.
   b. Disable colour and depth writes. Enable depth test (read-only).
   c. **Front-face pass**: Cull back faces. Set stencil op to **increment on depth fail**. Render the shadow volume.
   d. **Back-face pass**: Cull front faces. Set stencil op to **decrement on depth fail**. Render the shadow volume.
   e. Re-enable colour writes. Set stencil test to pass only where stencil == 0 (not in shadow).
   f. Render the scene with this light's diffuse + attenuation contribution, using **additive blending** (`GL_ONE, GL_ONE`) so multiple lights accumulate.

### TR-3: Light Attenuation

Within the lit pass for each light:

- **Inverse-square falloff**: `intensity = lightIntensity / (dist² + 1.0)` (the +1.0 prevents division by zero at dist=0).
- **Radius cutoff**: Fragments where `distance(fragPos, lightPos) > lightRadius` receive zero contribution from this light (skip in shader or clamp attenuation to zero).
- **Diffuse term**: Standard N·L Lambertian: `max(dot(normal, lightDir), 0.0)`.

### TR-4: Shader Requirements

**Vertex shader** (`shadow_volume.vert.glsl`):
- Inputs: `a_position` (vec3). No normals, no UVs — shadow volume geometry is position-only.
- Uniforms: `u_projViewTrans` (mat4), `u_worldTrans` (mat4).
- Output: `gl_Position`.
- Note: Version directive prepended by `Main.kt` via `ShaderProgram.prependVertexCode`.

**Fragment shader for shadow volume pass** (`shadow_volume.frag.glsl`):
- Outputs nothing to colour buffer (colour writes disabled).
- Minimal no-op shader; only the depth/stencil test matters.

**Fragment shader for lit pass** (`lit_pass.frag.glsl`):
- Inputs: `v_worldPos` (vec3), `v_worldNormal` (vec3), `v_texCoord` (vec2).
- Uniforms: `u_LightPos` (vec3), `u_LightColor` (vec3), `u_LightIntensity` (float), `u_LightRadius` (float), `u_diffuseTexture` (sampler2D), `u_diffuseColor` (vec4).
- Computes: diffuse N·L, inverse-square attenuation, radius cutoff.
- Output: `fragColor` = diffuse * attenuation * NdotL * lightColor.

**Fragment shader for ambient pass** (`ambient_pass.frag.glsl`):
- Inputs: `v_texCoord` (vec2).
- Uniforms: `u_ambientColor` (vec3), `u_diffuseTexture` (sampler2D), `u_diffuseColor` (vec4).
- Output: `fragColor` = texture * diffuseColor * ambient.

### TR-5: Performance Optimisations

1. **Scissor test**: For each light, compute a screen-space bounding rectangle from the light's radius and position. Set `glScissor` to limit rasterisation of the shadow volume to the affected screen region.
2. **Distance culling**: If `distance(cameraPos, lightPos) - lightRadius > maxCullDist`, skip this light entirely (shadow volume wouldn't affect visible scene).
3. **Silhouette caching**: If an occluder mesh hasn't moved and the light hasn't moved since the last frame, reuse the previously computed silhouette edges and extruded geometry.
4. **Closed volume guarantee**: All shadow volumes must be watertight (closed manifold) for depth-fail correctness. The extrusion + front cap + back cap ensures this.

### TR-6: Integration with Existing Code

| Component | Action |
|-----------|--------|
| `DynamicLighting.kt` | **DELETE** — replaced entirely by shadow volume pipeline |
| `DynamicLightingTest.kt` | **DELETE** |
| `LightingSystem.kt` | **DELETE** if present |
| `SurfaceLighting.kt` | **DELETE** if present |
| `WorldRenderer.kt` | **UPDATE** — remove `dynamicLighting` parameter; add `shadowVolumeRenderer` parameter |
| `RoguelikeGame.kt` | **UPDATE** — replace `DynamicLighting.build()` + old render path with `ShadowVolumeRenderer.render()` |
| `core/model/lighting/` | **KEEP** if present — pure data classes remain LibGDX-free |

### TR-7: New Files

| File | Package | Description |
|------|---------|-------------|
| `ShadowVolumeBuilder.kt` | `rendering` | CPU-side silhouette detection + extrusion + capping |
| `ShadowVolumeRenderer.kt` | `rendering` | Orchestrates ambient pass + per-light stencil + lit passes |
| `ShadowVolumeShaderProvider.kt` | `rendering` | Provides shadow volume and lit-pass shaders |
| `shadow_volume.vert.glsl` | `resources/shaders` | Shadow volume vertex shader |
| `shadow_volume.frag.glsl` | `resources/shaders` | Shadow volume fragment shader (no-op) |
| `lit_pass.vert.glsl` | `resources/shaders` | Lit pass vertex shader |
| `lit_pass.frag.glsl` | `resources/shaders` | Per-light diffuse + attenuation fragment shader |
| `ambient_pass.vert.glsl` | `resources/shaders` | Ambient pass vertex shader |
| `ambient_pass.frag.glsl` | `resources/shaders` | Ambient-only fragment shader |

### TR-8: Constraints

- **OpenGL 3.2 core profile** on desktop (LWJGL3). GLSL version is prepended by `Main.kt` — shader source files must NOT contain `#version` directives.
- **`core` package must remain LibGDX-free**. All rendering code (shader wrappers, shadow volume builder, renderer) lives in `com.roguelike.rendering`.
- **Stencil buffer**: Must request ≥ 8-bit stencil in the LWJGL3 window configuration. Current default is 8 bits.
- **Depth buffer precision**: Use `GL_LEQUAL` depth test; 24-bit depth buffer (LWJGL3 default).

---

## Definitions

| Term | Definition |
|------|-----------|
| Shadow Volume | A 3D mesh representing the region of space that is in shadow from a specific light source. Constructed by extruding silhouette edges of occluder geometry. |
| Silhouette Edge | An edge shared by one triangle facing toward the light and one facing away. The boundary of the shadow's shape. |
| Depth-Fail (Carmack's Reverse) | Stencil shadow volume method that increments/decrements stencil when depth test fails (i.e., counts shadow surfaces behind the fragment). Handles the camera-inside-shadow case correctly. |
| Front Cap | The set of front-facing triangles of the occluder, used to close the shadow volume at the near end. |
| Back Cap | The set of back-facing triangles extruded to infinity, used to close the shadow volume at the far end. |
| Fill Rate | The GPU's capacity to rasterise and shade fragments per second. Shadow volumes consume fill rate proportional to their screen-space coverage. |

---

## Unknowns / Risks

1. **Fill-rate pressure**: Shadow volumes covering large screen areas are expensive. Scissor optimisation (TR-5) mitigates but doesn't eliminate. If FPS drops below 30 with 4 lights, reduce shadow volume resolution or limit to 2 lights.
2. **Silhouette extraction cost**: CPU cost of per-frame silhouette detection on complex meshes. The dungeon's tile-based meshes are low-poly (~12 tris per wall segment) so this should be manageable. Cache silhouettes for static geometry.
3. **Infinity extrusion**: Using `w=0` homogeneous coordinates requires a projection matrix with `far = ∞`. LibGDX `PerspectiveCamera` doesn't natively support this — may need a custom projection matrix or a large finite extrusion distance.
4. **Stencil buffer overflow**: With many overlapping shadow volumes, stencil values could exceed 255 (8-bit). Unlikely with ≤ 4 lights in a dungeon setting.
5. **Non-manifold meshes**: If any occluder mesh is not a closed manifold, the shadow volume will have holes. All tile/wall/prop meshes must be checked for watertightness.

