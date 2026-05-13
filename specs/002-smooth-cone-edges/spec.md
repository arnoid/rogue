# Feature Specification: Smooth Cone Light Edges

**Feature Branch**: `002-smooth-cone-edges`

**Created**: 2026-05-12

**Status**: Draft

**Input**: User description: "screenshot shows that cone light (candle object light) edges are not straight lines but steps following nodes geometry. I want it to be straight cone."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Geometrically Correct Cone Boundary (Priority: P1)

A designer places a candle or sconce in a dungeon room. The cone of light it emits should
have a straight, angular edge — the kind you would expect from a physical spotlight — not a
staircase that follows the invisible tile grid. Looking at the scene from any angle, the
lit/unlit boundary is a smooth straight line radiating from the light source at the correct
angle.

**Why this priority**: This is the sole visible defect. The current behavior breaks
immersion: the stepped edge reveals the underlying grid and makes the scene look
technically broken rather than artistically lit.

**Independent Test**: Place a candle on a wall in an open room with no obstructions.
Rotate the scene so the cone is visible from above. The boundary between lit and unlit
floor tiles must be a straight diagonal line at the configured cone angle — no staircase
steps, no alignment with tile edges.

**Acceptance Scenarios**:

1. **Given** a cone light source with a 45° half-angle, **When** the scene is rendered,
   **Then** the two edges of the illuminated region are straight lines diverging from the
   source at exactly 45° on each side of the facing direction.
2. **Given** the cone is aimed diagonally (not axis-aligned), **When** the scene is
   rendered, **Then** the cone boundary edges remain straight and do not step along grid
   lines.
3. **Given** a narrow cone (< 20° half-angle), **When** the scene is rendered,
   **Then** the illuminated region is a narrow wedge with smooth straight edges and no
   grid artifacts.
4. **Given** a wide cone (> 80° half-angle), **When** the scene is rendered,
   **Then** the boundary is still a smooth pair of straight lines; the illuminated
   region does not become a rounded arc of grid-snapped steps.

---

### User Story 2 — Cone Edge at Range Boundary (Priority: P2)

At the maximum range of the cone light, where the brightness fades to zero, the curved
boundary that marks the end of the reach also remains smooth — not snapped to the tile
grid. Combined with the angular fix from User Story 1, the entire perimeter of the
illuminated region is grid-artifact-free.

**Why this priority**: Without fixing the range boundary, the interior of the cone can
look correct but the far arc still shows grid stepping. Completing this delivers a fully
clean cone shape.

**Independent Test**: Place a cone light in the center of a large open room. The lit
region should form a clean pie-slice / sector shape — straight edges from the source and
a smooth arc at the far end, with no grid stepping along either boundary.

**Acceptance Scenarios**:

1. **Given** a cone light at max range R, **When** the scene is rendered, **Then** the
   far boundary of the illuminated area at distance R is a smooth curve, not a staircase.
2. **Given** the player moves the light source, **When** the scene re-renders, **Then**
   both the angular edges and the range boundary update smoothly without popping to new
   grid positions.

---

### Edge Cases

- What happens when the cone's facing direction is exactly axis-aligned (0°, 90°, 180°,
  270°)? The boundary must still be straight — axis-aligned cones are the most likely to
  reveal grid snapping.
- What happens at the exact cone boundary angle (a surface sample at precisely the cone
  edge)? The sample should be treated as inside the cone (inclusive boundary) to avoid
  a thin dark fringe along the edge.
- What happens when a cone light source moves mid-frame (e.g., carried by the player)?
  The boundary must recalculate from the new exact position, with no one-frame lag.
- What if the cone angle is 360° (omnidirectional)? No boundary to smooth; the light
  degrades gracefully to a point light with no visible edge.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The cone angular boundary test MUST be evaluated using the continuous
  world-space position of each surface sample point, not the center position of the
  containing grid cell.
- **FR-002**: The boundary between the illuminated and unilluminated regions of a cone
  light MUST appear as a geometrically straight line in the rendered scene, with no
  staircase artifacts aligned to the tile grid.
- **FR-003**: FR-001 and FR-002 MUST apply to all light sources with a non-zero cone
  angle (candles, sconces, and any other light with a configured `coneDegrees` value).
- **FR-004**: The cone range boundary (the far arc at maximum light radius) MUST also be
  evaluated using continuous world-space coordinates so it appears as a smooth curve.
- **FR-005**: Fixing the cone boundary MUST NOT introduce visual artifacts within the
  interior of the illuminated region; surfaces fully inside the cone MUST remain
  uniformly lit as before.
- **FR-006**: The fix MUST preserve backward compatibility with existing point lights
  (360° cone) and spotlights; no existing light configuration should change appearance
  except to remove the stepping artifact.
- **FR-007**: The occlusion system MUST use the `ItemDef.blocksLight` property to
  determine whether a tile contributes an AABB to the light-occlusion structure,
  independent of whether that tile blocks character movement. Floor tiles MUST occlude
  light rays by default (their `ItemDef.blocksLight` value is `true`); designers MAY
  set `blocksLight: false` in `items.json` to create see-through surfaces such as glass
  floors or gratings without altering movement behaviour.

### Key Entities

- **ConeLight**: A directed light source with a world-space position, a facing direction
  vector, a half-angle (degrees), an intensity, and a maximum range.
- **SurfaceSamplePoint**: The exact world-space coordinate of a point on a lit surface
  that is tested against the cone boundary. Must not be snapped to grid cell centers.
- **OccluderTile**: Any tile whose `ItemDef.blocksLight` property is `true`. Includes
  walls (always), floor tiles (by default), and any item-defined surface that should
  be opaque to light regardless of movement traversability.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reviewer examining a rendered cone light at any angle cannot identify any
  staircase step along either angular edge of the illuminated region.
- **SC-002**: A cone light aimed at a diagonal (e.g., 45°) produces edges that a ruler
  placed on the screen would follow exactly, with no visible deviation at tile boundaries.
- **SC-003**: The fix applies consistently to all cone-emitting objects present in any
  saved world without additional configuration.
- **SC-004**: Frame rate is not measurably reduced by the fix — replacing a grid-cell
  lookup with a continuous angle test is O(1) per sample and should be strictly cheaper.
- **SC-005**: No regression in point-light (omnidirectional) appearance — scenes using
  only non-cone lights look identical before and after the fix.

## Assumptions

- The current implementation tests the cone boundary against the grid cell center
  (integer coordinates) rather than the actual sub-cell surface sample position; this is
  the root cause of the stepping artifact visible in the screenshot.
- One "surface sample point" corresponds to one exact 3D world-space coordinate already
  computed by the lighting pipeline; no new sampling infrastructure is needed.
- The cone facing direction is already stored as a continuous vector, not a quantised
  compass direction.
- A hard angular cutoff (no penumbra/feathering) is acceptable; adding a soft cone edge
  is out of scope for this fix.
- This feature applies only to the dynamic lighting pipeline used for real-time rendering;
  the static light map (if any) is out of scope.

## Clarifications

### Session 2026-05-12

- Q: Floor models are correctly lit by light sources but transparent to raycasting — should they block light? → A: Yes. Floor tiles MUST block light rays by default.
- Q: How should the system determine whether a floor tile blocks light rays? → A: Data-driven: use `ItemDef.blocksLight` (defaults to `true`); floor tiles are light-opaque unless explicitly overridden in `items.json`.
