# Feature Specification: Procedural Map Generator and GPU Lighting Engine

**Feature Branch**: `003-procedural-map-gpu-lighting`

**Created**: 2026-05-13

**Status**: Draft

**Input**: User description: "Socket-Based 3D Procedural Map Generator paired with GPU-based Per-Pixel Lighting and Shadow Mapping Engine. System A: socket-based room connection with Base Unit 3x3x3 grid, multi-socket rule, async generation loop with step-through debugger. System B: replace CPU raycasting with GPU per-pixel lighting, DirectionalShadowLight, PointLight with FboCubemap omnidirectional shadows."

## Clarifications

### Session 2026-05-13

- Q: What minimum OpenGL/ES version must the game support for omnidirectional point-light shadows? → A: Desktop + GLES 3.0+ only; no GLES 2.0 fallback required.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Smooth Per-Pixel Surface Lighting (Priority: P1)

A designer walks through a dungeon room lit by a torch in the corner. Instead of every
floor tile and wall face being uniformly "on" or "off" relative to the torch, each surface
receives light proportional to how directly it faces the light source. A floor tile that
is close and perpendicular to the torch is bright; the same tile angled away or near the
edge of the light radius fades smoothly to dark. A single large floor slab can have one
corner brightly lit and the opposite corner in shadow — all in the same render.

**Why this priority**: The current binary (fully lit / fully dark) model is the most
visible quality defect in the shipped game. It makes the dungeon feel flat and unreal.
Fixing per-pixel attenuation directly improves player immersion and is the foundation
for all subsequent shadow features.

**Independent Test**: Place a single point light source in the center of a flat open
room with no walls. Rotating the camera, every floor tile must show a smooth brightness
gradient — tiles close to the source are bright, tiles at the edge are near-dark, and no
two adjacent tiles have the same brightness level unless equidistant.

**Acceptance Scenarios**:

1. **Given** a point light source in an open room, **When** the scene is rendered,
   **Then** the illuminated region shows a smooth radial gradient with no sudden
   brightness jumps at tile boundaries.
2. **Given** a wall face perpendicular to a point light, **When** the scene is rendered,
   **Then** the face is brightest at the closest point and dims toward its edges.
3. **Given** a floor tile at the edge of the light's effective radius, **When** the
   scene is rendered, **Then** the tile surface smoothly transitions from dim to
   unlit rather than switching on/off as a whole.
4. **Given** the player moves a carried light source one step, **When** the scene
   re-renders, **Then** all surface brightness values update continuously with no
   tiles popping from lit to unlit.

---

### User Story 2 — Directional Light with Geometrically Accurate Shadows (Priority: P2)

A designer configures a global directional light (representing sunlight coming through
a ceiling grate or a torch fixed to a far wall). The light casts shadows from every wall
and pillar onto the floor. The shadow edge where a wall blocks the global light is a
perfectly straight geometric line — no staircase steps along the tile grid.

**Why this priority**: The directional shadow is the simpler of the two shadow techniques
and serves as the foundation for validating the two-pass render architecture before the
more complex omnidirectional system is built.

**Independent Test**: Place a directional light aimed at a 45° angle in a room with a
single vertical wall. The shadow cast by the wall onto the floor must be a straight
diagonal line that can be verified with a ruler against the screen.

**Acceptance Scenarios**:

1. **Given** a directional light at any angle, **When** the scene is rendered,
   **Then** the shadow boundaries on floor surfaces are straight lines with no
   grid-aligned staircase artifacts.
2. **Given** a wall casting a shadow on the floor, **When** the player moves around
   the scene, **Then** the shadow boundary remains sharp and geometrically stable from
   all camera angles.
3. **Given** two walls forming an L-corner, **When** the scene is rendered,
   **Then** the combined shadow region is the correct geometric union of both
   individual wall shadows with no overlap artifacts.

---

### User Story 3 — Point Light Occlusion by Walls (Priority: P3)

A designer places a torch in one room and a wall separates it from an adjacent corridor.
The corridor is correctly dark — the torch light does not bleed through the solid wall.
Furthermore, from inside the torchlit room, surfaces directly behind a pillar are visibly
in shadow while surfaces on the near side of the pillar are bright.

**Why this priority**: The current CPU approach bleeds point light through walls, making
the entire game space feel incorrectly lit. This is the most technically complex piece of
the lighting system (requiring cube-map shadow rendering) and is deferred to P3 so the
simpler P1 and P2 can validate the render pipeline first.

**Independent Test**: Place a point light on one side of a solid wall, stand on the
opposite side, and confirm that surface brightness on the far side is zero (no light
bleeding). Additionally, place a pillar between the light and a far wall and confirm a
visible shadow from the pillar on the far wall.

**Acceptance Scenarios**:

1. **Given** a point light in Room A and a solid wall, **When** a surface in Room B
   (behind the wall) is rendered, **Then** that surface receives zero contribution
   from Room A's point light.
2. **Given** a torch and a cylindrical pillar between it and a wall, **When** the
   scene is rendered, **Then** the wall shows a distinct shadow silhouette of the pillar
   at the geometrically correct position.
3. **Given** a point light and an open doorway, **When** the scene is rendered,
   **Then** light passes through the doorway opening and illuminates the corridor
   beyond, but not through the solid door frame itself.

---

### User Story 4 — Socket-Based Procedural Dungeon Generation (Priority: P4)

A designer creates a set of room templates of varying sizes. Running the generator
produces a connected dungeon: rooms are placed adjacent to one another, connecting
only where their exposed sockets have matching types. A 3×3 corridor template connects
to a matching 3×3 socket on the face of a larger 9×9 room. Connections are never
placed where a room would overlap another already-placed room. Any socket that could
not be matched gets sealed, spawning a wall or door in its place.

**Why this priority**: This is a new gameplay subsystem with no existing code to replace.
It delivers core variety to the dungeon experience. Prioritized after lighting because
the current map loading still works; lighting is more visually broken.

**Independent Test**: Create a minimal template library with two templates (a 3×3
corridor and a 9×9 room). Run the generator to completion. Inspect the output: every
room placement must be collision-free, every connection must have matching socket tags,
and every unmatched socket must have a SEALED state.

**Acceptance Scenarios**:

1. **Given** two templates with compatible socket tags, **When** the generator places
   them adjacent to each other, **Then** their connecting sockets transition to CONNECTED
   state and no grid cells are double-occupied.
2. **Given** a 9×9 room face with nine 3×3 sockets, **When** a 3×3 corridor connects to
   one of those sockets, **Then** only that single socket becomes CONNECTED; the
   remaining eight are still available.
3. **Given** a room template that would overlap an already-placed room, **When** the
   generator evaluates the placement, **Then** the placement is rejected and the system
   tries the next candidate template.
4. **Given** an OPEN socket for which no compatible template fits without overlap,
   **When** the generator finishes evaluating candidates, **Then** the socket is set to
   SEALED.
5. **Given** a template with a dimensional footprint of 3×3×2 Base Units, **When**
   the generator checks collisions, **Then** all 18 individual Base Unit cells
   (3×3×2) are verified to be unoccupied before placement is committed.

---

### User Story 5 — Step-Through Generation Debugger (Priority: P5)

A developer running the generator in debug mode can inspect each candidate room
placement before it is committed to the map. They see which socket is being evaluated
and which template is the proposed match. They click "I do agree!" to accept the
placement (committing it and advancing generation) or "I do not agree!" to reject it
and advance to the next candidate. If no candidates remain for a socket, it is sealed
automatically.

**Why this priority**: This is a developer-facing debug tool, not a player-facing feature.
It enables manual tuning of template libraries and diagnosing generation failures. It
has no impact on the shipped player experience.

**Independent Test**: Launch the game in debug mode. Run the generator over a minimal
template library. Verify that generation pauses before each placement, that clicking
"I do agree!" commits the room and advances, and that clicking "I do not agree!" skips
to the next candidate without modifying the map.

**Acceptance Scenarios**:

1. **Given** the generator is running in debug mode and a valid candidate is found,
   **When** the candidate is evaluated, **Then** generation pauses and a confirmation
   dialog is shown before any placement is committed.
2. **Given** the confirmation dialog is visible, **When** the developer clicks
   "I do agree!", **Then** the room is placed, its sockets are marked CONNECTED, and
   generation resumes.
3. **Given** the confirmation dialog is visible, **When** the developer clicks
   "I do not agree!", **Then** the candidate is skipped, no placement is committed,
   and the next candidate (if any) is evaluated.
4. **Given** the debugger is active, **When** no candidates remain for a socket,
   **Then** the socket is automatically sealed without showing a confirmation dialog.

---

### Edge Cases

- What happens when the template library is empty? The generator must fail gracefully
  with a clear error state, not hang or crash.
- What happens when the starting socket at (0,0,0) has no compatible templates? The
  generator completes with a single sealed starting node.
- What happens with a directional light whose direction is exactly vertical (straight
  down)? Shadows on the floor must still be computed correctly (no division-by-zero
  or degenerate shadow projection).
- What happens when a point light is placed inside a wall (overlapping solid geometry)?
  The light's shadow contribution must be computed from the actual placement position
  without crashing the renderer.
- What happens when the generation loop produces a dead-end dungeon (every open socket
  gets sealed before reaching a target room count)? The result is accepted as valid;
  the generator does not retry indefinitely.
- What happens if the GPU does not support the required depth texture extension for
  omnidirectional shadows? The game targets Desktop + GLES 3.0+ only; cube-map depth
  textures are guaranteed available on all supported platforms. No GLES 2.0 fallback
  is required.

## Requirements *(mandatory)*

### Functional Requirements

**System B — GPU Lighting**

- **FR-001**: Every rendered surface MUST receive per-pixel light intensity calculated
  from its actual world-space position and surface normal, not from its containing
  grid cell's center point.
- **FR-002**: A single rendered tile face (floor or wall) MUST be capable of showing
  multiple brightness levels across its surface within a single rendered frame.
- **FR-003**: Directional shadow boundaries on all surfaces MUST be geometrically
  straight lines with no tile-grid-aligned staircase artifacts.
- **FR-004**: Point lights MUST be occluded by solid wall geometry; no light
  contribution from a point light source MUST pass through a solid surface to the
  other side.
- **FR-005**: The GPU lighting pipeline MUST fully replace the current CPU center-node
  raycasting lighting; the two systems MUST NOT coexist in production rendering.
- **FR-006**: All existing point lights (torch, candle, sconce) configured in the item
  catalog MUST automatically use the new GPU pipeline without requiring changes to
  their `items.json` definitions.
- **FR-007**: The lighting architecture MUST respect the core-rendering separation
  boundary: all light source data models MUST remain in the `core` package with no
  rendering imports; the GPU pipeline implementation MUST live in the `rendering`
  package.

**System A — Procedural Map Generator**

- **FR-008**: The generator MUST operate on a uniform voxel grid where the fundamental
  unit is a Base Unit of 3×3×3 game cells; all socket positions and room footprints
  MUST be expressed in Base Unit coordinates.
- **FR-009**: Room templates with a face larger than one Base Unit (e.g., 9×9 = 3×3
  Base Units wide) MUST expose one socket per Base Unit on that face; a 9×9 face MUST
  have nine distinct 3×3 sockets, not one aggregate socket.
- **FR-010**: Sockets MUST only connect to sockets with an exactly matching tag string
  AND an opposing direction (a NORTH socket connects only to a SOUTH socket with the
  same tag).
- **FR-011**: Before committing a room placement, the generator MUST verify that every
  Base Unit cell required by the new room's footprint is unoccupied in the grid map.
- **FR-012**: When no compatible template can be placed at an OPEN socket without
  collision, the socket MUST be set to SEALED.
- **FR-013**: The generation loop MUST run asynchronously and MUST NOT block the
  game's main rendering thread.
- **FR-014**: In debug mode, the generator MUST pause before committing each valid
  placement and expose a callback allowing external code to approve or reject the
  placement.
- **FR-015**: The debug confirmation UI MUST have exactly two actions: a confirmation
  action labeled "I do agree!" styled with a soft pink appearance, and a rejection
  action labeled "I do not agree!" styled with a neutral gray appearance.
- **FR-016**: The procedural map generator data models (socket, template, grid map)
  MUST live in the `core` package with no LibGDX imports; they MUST be unit-testable
  without a running display.

### Key Entities

- **BaseUnit**: The fundamental grid subdivision, 3×3×3 game cells. All spatial
  measurements in the generation system use Base Unit coordinates.
- **Socket**: A connection point on a room template surface. Has a position (in Base
  Unit coordinates), a facing direction, a tag string, and a lifecycle state
  (OPEN → CONNECTED or OPEN → SEALED).
- **SubmapTemplate**: A reusable room blueprint. Defined by its Base Unit footprint
  dimensions and a list of sockets on its faces.
- **PlacedSubmap**: An instance of a SubmapTemplate committed to the grid at a specific
  absolute Base Unit position. Owns the resolved socket states for its instance.
- **GridMap**: A spatial index from Base Unit coordinates to the placed submap occupying
  that cell. Used for collision checks before placement.
- **LightEnvironment**: The per-frame collection of active light sources (point lights,
  directional lights) and their shadow maps, passed to the GPU shader for all surface
  rendering in that frame.
- **ShadowMap**: A depth texture rendered from the perspective of a directional or point
  light. Used in the main render pass to determine which surface fragments are in shadow.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reviewer cannot identify any staircase step along any shadow boundary
  from any directional light source when inspecting the rendered scene at any camera angle.
- **SC-002**: A reviewer can visibly see that a single floor or wall tile face is
  partially illuminated and partially shadowed within the same frame, not uniformly
  lit or unlit.
- **SC-003**: A point light placed in Room A produces zero surface brightness on any
  surface in a Room B that is separated from Room A by a solid wall, verified by
  screenshot comparison.
- **SC-004**: The procedural generator produces a connected, collision-free dungeon
  layout using a provided template library in under 5 seconds for a 20-room target size.
- **SC-005**: Every generated map satisfies the socket compatibility invariant: every
  CONNECTED socket pair has matching tags and opposing directions; no grid cell is
  occupied by more than one placed submap.
- **SC-006**: The step-through debugger correctly pauses before every valid placement,
  accepts 100% of "I do agree!" clicks as commits, and skips 100% of "I do not agree!"
  clicks without modifying the map.
- **SC-007**: Frame rate during GPU lighting rendering does not fall below 30 FPS on
  the reference development machine when the scene contains up to 8 simultaneous point
  lights.
- **SC-008**: All existing game tests pass after the GPU lighting pipeline replaces the
  CPU raycasting system; no regression in movement, collision, or non-lighting
  behavior.

## Assumptions

- The game targets Desktop + GLES 3.0+ only. Cube-map depth texture support is
  guaranteed on all supported platforms. No GLES 2.0 fallback is required or in scope.
- The step-through debugger (US5) is a development-only tool; it will not be present
  in production/release builds and therefore does not require localization, accessibility
  review, or UX polish beyond functional correctness.
- The GPU lighting pipeline fully replaces (does not coexist with) the current CPU
  `DynamicLighting`, `SurfaceLighting`, and `LightingSystem` classes. The existing
  CPU classes may be removed or archived once the GPU pipeline is validated.
- Room templates for the procedural generator are pre-authored by designers and exist
  as data files; the generator does not create new template shapes at runtime.
- The procedural generator operates on a fresh world instance (no streaming or
  incremental generation of an already-playing session is required).
- The two systems (A and B) are architecturally independent and can be implemented,
  tested, and merged in separate phases. System A (map generator) can ship before
  System B (GPU lighting) without breaking the other.
- Both systems must comply with the project's Core-Rendering Separation principle:
  game-logic data models in `core` (no LibGDX), rendering pipeline in `rendering`.
