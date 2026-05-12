# Feature Specification: Dynamic Raycast Lighting

**Feature Branch**: `001-raycast-lighting`

**Created**: 2026-05-12

**Status**: Draft

**Input**: User description: "Implement proper dynamic lightning with raytracing, based on actual models and instead of world nodes."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Geometry-Accurate Shadows (Priority: P1)

A player carrying a torch walks through a dungeon. Light and shadow boundaries follow the
actual shapes of walls, pillars, and furniture — not the invisible grid of world nodes.
A curved wall casts a curved shadow; a narrow doorway casts a thin beam of light. The
scene looks physically plausible.

**Why this priority**: This is the core promise of the feature. Without shadows aligned to
model geometry, all subsequent lighting work is meaningless.

**Independent Test**: Place a torch next to an angled wall model. The shadow edge follows
the model surface exactly; no light leaks through the geometry, and no shadow appears
beyond an open space.

**Acceptance Scenarios**:

1. **Given** a torch is placed adjacent to a wall model, **When** the scene is rendered,
   **Then** the shadow boundary aligns with the visible edge of the wall mesh, not with
   the tile grid.
2. **Given** an open doorway between two rooms, **When** a light source is in one room,
   **Then** a beam of light passes through the doorway opening and illuminates the floor
   beyond; the door frame casts a shadow on both sides.
3. **Given** a pillar (solid model) in an open room, **When** a light source is placed to
   one side, **Then** the pillar casts a shadow on the opposite side proportional to
   the light angle.

---

### User Story 2 - Real-Time Dynamic Lights (Priority: P2)

Lights update instantaneously as game state changes: the player moves, a door opens or
closes, a torch is picked up or dropped, a magic effect activates. There is no visible
"pop" or lag when illumination changes.

**Why this priority**: A lighting system that only updates statically is not truly dynamic.
Real-time response is the second-most critical requirement.

**Independent Test**: Carry a torch and walk continuously through a corridor with doors.
Shadows cast by every model update each frame with no stutter or freeze.

**Acceptance Scenarios**:

1. **Given** the player is holding a light source and moving, **When** they move each
   frame, **Then** all shadows and lit areas update smoothly in the same frame with no
   visible delay.
2. **Given** a door is closed (blocking light), **When** the player opens the door,
   **Then** in the next rendered frame light passes through the doorway and previously
   shadowed geometry becomes illuminated.
3. **Given** a light source is extinguished (picked up, destroyed, or toggled off),
   **When** it deactivates, **Then** the area immediately returns to ambient illumination
   with no lingering incorrect shadow.

---

### User Story 3 - Multiple Blended Light Sources (Priority: P3)

A room contains several independent light sources (torches on walls, a lantern carried by
the player, a glowing item on the floor). All sources contribute additively to the final
illumination; a surface lit by two sources is brighter than one lit by a single source.
Shadows from one source can be partially filled by another.

**Why this priority**: Dungeon environments commonly have multiple lights. Single-source
restriction would break realistic layouts.

**Independent Test**: Place two torches at opposite ends of a corridor. The centre of the
corridor is brighter than either end; a box in the middle casts two shadows pointing in
opposite directions.

**Acceptance Scenarios**:

1. **Given** two light sources are active in the same room, **When** the scene renders,
   **Then** surfaces visible to both sources receive combined illumination greater than
   either source alone.
2. **Given** an object casts a shadow from one light source, **When** a second source
   illuminates the shadowed side, **Then** the shadow is partially or fully filled by the
   second source.
3. **Given** up to 8 active light sources are present in the current view, **When** the
   frame is rendered, **Then** all sources contribute correctly without any source being
   silently dropped.

---

### Edge Cases

- What happens when a light source is embedded inside a model (clipping through geometry)?
  The light should still illuminate outward; occlusion testing starts from just outside the
  source position.
- How does the system handle a completely dark area with no light source? A configurable
  ambient illumination value provides a minimum visibility floor so the scene is never
  fully black.
- What happens when a light source is at the edge of the world boundary? Rays that exit
  world bounds return no hit and the edge is treated as unoccluded open space.
- How does moving between Z levels (stairs) affect lighting? Lights are 3D; a light at
  Z=0 only illuminates geometry it has a clear ray path to — stair geometry may partially
  block it from upper levels.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The lighting system MUST cast rays from each active light source against
  rendered model geometry to determine occlusion, not against world node boundaries.
- **FR-002**: Each light source MUST have a position, a color (RGB), an intensity, and a
  maximum range beyond which it contributes no illumination.
- **FR-003**: Light intensity MUST decrease smoothly with distance from the source using
  a physically-based attenuation curve.
- **FR-004**: Rendered geometry (walls, doors, pillars, furniture models) MUST occlude
  light rays, producing accurate per-model shadows.
- **FR-005**: Closed door models MUST block light; open door models MUST allow light to
  pass through, consistent with the door state tracked by the game world.
- **FR-006**: Multiple active light sources MUST blend additively on illuminated surfaces.
- **FR-007**: Lighting MUST recompute every rendered frame so that movement of the player,
  actors, or toggling of doors/lights is immediately reflected in the next frame.
- **FR-008**: A global ambient illumination level MUST be configurable to prevent fully
  black areas when no light source is present.
- **FR-009**: The number of rays cast per light source MUST be configurable to allow
  quality/performance tradeoffs without code changes.
- **FR-010**: The lighting system MUST integrate with the existing map editor so designers
  see accurate lighting while placing tiles and models.
- **FR-011**: For each light source, node evaluation MUST be spatially culled to a
  configurable culling radius (default: 100 nodes) using Manhattan distance
  (`|dx|+|dy| ≤ cullingRadius`). The effective culling bound MUST be
  `min(light.range, cullingRadius)` so that short-range lights never evaluate nodes
  beyond their visible reach. The culling radius MUST be configurable without code
  changes, consistent with FR-009.

### Key Entities

- **LightSource**: A point light with position (3D), color (RGB), intensity (float),
  range (float), and active state. Attached to world actors, tiles, or items.
- **ShadowRay**: A ray from a light source to a sample point, used to determine if the
  point is occluded.
- **LightingResult**: Per-surface illumination value (color + intensity) accumulated from
  all visible light sources after occlusion testing.
- **AmbientLight**: A scene-wide minimum illumination applied to all surfaces regardless
  of occlusion.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Shadow boundaries align with visible model edges — a reviewer cannot
  identify a discrepancy between a shadow edge and the model edge that cast it.
- **SC-002**: Light does not visibly leak through closed walls or closed door models
  in any scenario testable in the map editor.
- **SC-003**: Lighting updates without perceptible lag: no frame shows stale illumination
  when the player moves, a door toggles, or a light source changes state.
- **SC-004**: With up to 8 concurrent dynamic light sources and spatial culling active
  (Manhattan radius = `min(light.range, 100)` nodes per source), the game maintains a
  playable frame rate (≥ 30 fps) on the target development machine regardless of total
  map tile count.
- **SC-005**: A scene with a single torch and no ambient light is not completely dark —
  the ambient floor value ensures minimum visibility.
- **SC-006**: Increasing the ray-count quality setting produces visibly smoother shadow
  penumbras; decreasing it degrades quality gracefully without crashing.

## Assumptions

- Light sources are **point lights** (omnidirectional); directional or spot lights are out
  of scope for this feature.
- The raycast occlusion test uses the same 3D model geometry already loaded by the
  rendering pipeline — no separate collision meshes are required.
- Transparent or semi-transparent model surfaces are treated as fully opaque for occlusion
  purposes in this initial implementation.
- The existing door state (open/closed) tracked in the world model is the authoritative
  source for whether door geometry blocks light.
- Mobile or WebGL targets are out of scope; the feature targets desktop (LWJGL3) only.
- A maximum of 8 simultaneous dynamic light sources in the current view is the initial
  performance target; more sources may be added in a later optimization pass.
- One "node" equals one world unit (1×1 tile cell) for the purposes of the spatial
  culling distance calculation in FR-011.

## Clarifications

### Session 2026-05-12

- Q: What distance metric should the 100-node spatial culling radius use? → A: Manhattan distance (`|dx|+|dy| ≤ radius`); diamond-shaped region, no sqrt required.
- Q: Should the culling radius interact with the light's existing `range` property? → A: Use `min(light.range, cullingRadius)` — short-range lights get tighter bounds automatically.
- Q: Should the 100-node culling default be configurable or hardcoded? → A: Configurable default of 100, consistent with FR-009 pattern; allows tuning without code changes.
