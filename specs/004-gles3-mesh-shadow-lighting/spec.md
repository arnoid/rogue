# Feature Specification: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Feature Branch**: `004-gles3-mesh-shadow-lighting`

**Created**: 2026-05-13

**Status**: Draft

**Input**: User description: "Discard existing lightning model and math. Implement OpenGL ES 3.0 from scratch. I want a lighting system that respects model mesh when casting shadows."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Mesh-Accurate Shadow Casting (Priority: P1)

As a player, I see shadows that follow the exact silhouette of 3D objects in the world — pillars cast rectangular shadows, walls cast hard-edged shadows, and no light bleeds through geometry.

**Why this priority**: This is the core requirement. Without per-mesh shadow casting the entire feature has no value. All other stories depend on this being correct first.

**Independent Test**: Place a single point light next to a wall segment in a generated map. Verify the shadow on the floor precisely follows the wall geometry with no bleed-through. Can be validated with a static screenshot comparison.

**Acceptance Scenarios**:

1. **Given** a point light adjacent to a wall mesh, **When** the scene is rendered, **Then** the shadow on the floor matches the wall's geometric silhouette exactly with no light bleeding through the wall.
2. **Given** a directional (sun) light shining at an angle, **When** the scene is rendered, **Then** every opaque mesh casts a clean shadow whose edge follows the mesh boundary.
3. **Given** a light inside a closed room, **When** the scene is rendered, **Then** no light escapes through wall or ceiling geometry to illuminate adjacent rooms.

---

### User Story 2 - Per-Pixel Partial Mesh Lighting (Priority: P2)

As a player, I see individual floor tiles and wall faces that are partially lit — one side of a tile near a torch is bright, the opposite side is dim — driven by the geometry of each triangle, not a per-object constant.

**Why this priority**: This eliminates the "all-or-nothing" binary lighting problem. Requires Story 1's render pipeline to be in place first.

**Independent Test**: Place a single torch at one corner of a large floor tile. Verify the tile surface shows a smooth light-to-shadow gradient across its surface from bright (near torch) to dim (far corner).

**Acceptance Scenarios**:

1. **Given** a large floor tile and a nearby point light, **When** rendered, **Then** the tile surface shows varying brightness across its face — brighter near the light, darker away from it.
2. **Given** a wall mesh and an angled directional light, **When** rendered, **Then** faces perpendicular to the light are bright and faces parallel to the light are dim, with no hard object-level on/off switching.

---

### User Story 3 - Multiple Dynamic Point Lights (Priority: P3)

As a player, I see multiple torches or light sources simultaneously illuminating the same surfaces with additive blending, each casting its own shadow independently.

**Why this priority**: Game levels will have many light sources. Without multi-light support a single torch per scene would be the limit.

**Independent Test**: Place two torches at different positions in the same corridor. Verify both cast visible light and each casts its own independent shadow, with the area between them receiving light from both sources.

**Acceptance Scenarios**:

1. **Given** two point lights in the same room, **When** rendered, **Then** a surface between them shows combined illumination from both lights.
2. **Given** two point lights on opposite sides of a wall, **When** rendered, **Then** each light's shadow is occluded by the wall independently — the wall blocks each respective light on its far side.
3. **Given** eight active point lights in a scene, **When** rendered, **Then** all eight contribute to lighting with no lights silently dropped.

---

### User Story 4 - Directional Ambient Light (Priority: P4)

As a player, I see a global ambient contribution ensuring fully shadowed areas remain faintly visible rather than pitch black, simulating bounced or ambient light.

**Why this priority**: Purely black shadowed areas look wrong and make gameplay difficult. This is visual polish layered on top of the core pipeline.

**Independent Test**: Render a scene with all point lights removed. Areas fully in shadow should still show faint, uniform illumination — not total darkness.

**Acceptance Scenarios**:

1. **Given** a surface fully occluded from all point lights, **When** rendered, **Then** the surface is dim but not completely black — the ambient level is visible.
2. **Given** a surface directly lit by a point light, **When** rendered, **Then** the bright direct illumination dominates and the ambient contribution is not visually noticeable.

---

### Edge Cases

- What happens when a light source is positioned inside a wall mesh? (Light illuminates only accessible geometry on the same side; no special handling required.)
- How does the system handle zero active lights? (Scene renders using ambient contribution only — no crash or undefined behaviour.)
- What happens when more than the maximum supported point lights are present? (Excess lights are culled by distance; the closest lights to the camera take priority silently.)
- How are transparent or semi-transparent meshes handled? (Out of scope — all meshes treated as fully opaque shadow casters for this feature.)
- What happens when a shadow map resolution is insufficient to resolve fine geometry edges? (Shadow acne is prevented via a configurable depth bias; no hard failure, slight softening of fine edges is acceptable.)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST compute per-pixel lighting on every rendered mesh surface using the GPU.
- **FR-002**: System MUST cast shadows that follow the exact silhouette of each 3D mesh (mesh-accurate shadow mapping).
- **FR-003**: System MUST support at least 8 simultaneous point light sources contributing to a scene.
- **FR-004**: System MUST support one directional light (global/sunlight) with shadow mapping.
- **FR-005**: System MUST ensure no light penetrates solid opaque geometry (zero light bleed through walls, floors, or ceilings).
- **FR-006**: System MUST blend multiple light contributions additively per fragment so surfaces are illuminated by all visible lights.
- **FR-007**: System MUST provide a configurable ambient light level so fully shadowed surfaces are not pitch black.
- **FR-008**: System MUST discard and replace the previous CPU-based raycasting lighting model entirely — no old code paths may remain active in the rendered output.
- **FR-009**: System MUST integrate with the existing generated map's mesh objects without requiring changes to asset files or tile definitions.
- **FR-010**: System MUST use only OpenGL ES 3.0 compliant shader code — no deprecated built-ins, no legacy GLSL syntax.

### Key Entities

- **DirectionalLight**: Global light with a direction vector, colour, and intensity; casts shadows via an orthographic shadow map.
- **PointLight**: Positional light with world-space location, colour, intensity, and falloff radius; casts omnidirectional shadows.
- **ShadowMap**: Depth texture rendered from a light's point of view used to determine which surface fragments are in shadow.
- **LightEnvironment**: The aggregate of all active lights for a given frame, passed uniformly to the shader.
- **LitFragment**: A screen pixel whose final colour is the sum of direct illumination from each light (attenuated by shadow factor) plus ambient.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Shadow edges follow mesh geometry — a shadow boundary deviates by no more than 1 pixel from the corresponding mesh silhouette edge at 1024×768 resolution.
- **SC-002**: Zero light bleeds through any wall, floor, or ceiling mesh under any camera angle — verified by placing a light on one side and confirming the opposite side receives no direct illumination.
- **SC-003**: A scene with 8 active point lights renders at 30 FPS or above on mid-range desktop hardware.
- **SC-004**: Per-pixel lighting is observable — a single large floor tile with a nearby torch shows a measurable brightness gradient from the lit edge to the far dim corner.
- **SC-005**: The previous CPU lighting system produces zero visual output after this change — no rendered frame shows any contribution from the old model.
- **SC-006**: The lighting pipeline compiles and runs without GLSL errors on an OpenGL ES 3.0 context.

## Assumptions

- The game engine context is already configured for an OpenGL ES 3.0 compatible surface; no windowing or context changes are required.
- All meshes in the generated map are fully opaque — transparency and alpha blending are out of scope for shadow casters.
- The maximum number of simultaneous point lights is 8; lights beyond this limit are culled by distance without user-visible error.
- The existing mesh-based rendering pipeline is retained; only the lighting shaders and light data classes are replaced.
- Asset files (tile models, textures) remain unchanged — no mesh re-export or format conversion is needed.
- The old CPU raycasting lighting system is deleted entirely, not wrapped or feature-flagged — a clean removal.
- Shadow map resolution defaults to 1024×1024 for the directional light and 512×512 per face for point-light omnidirectional cubemaps.
- Performance target is 30 FPS minimum on mid-range desktop hardware; mobile is out of scope.
