# Quickstart: Procedural Map Generator and GPU Lighting Engine

**Feature**: `003-procedural-map-gpu-lighting`
**Date**: 2026-05-13

This guide covers how to verify each user story independently during development.

---

## Prerequisites

```bash
./gradlew test          # Must be green before any implementation begins
./gradlew run           # Launches the game (desktop; macOS adds -XstartOnFirstThread automatically)
```

---

## US1 — Smooth Per-Pixel Surface Lighting

**Goal**: A single floor tile shows multiple brightness levels in one frame.

### Headless test (automated)

```bash
./gradlew test --tests "com.roguelike.rendering.GpuLightEnvironmentTest"
```

Verify that `GpuLightEnvironment` correctly collects point light data from a mock world.

### Visual acceptance test

1. Run `./gradlew run`
2. Open a saved world or generate a new one.
3. Place a single torch in the center of a large open room with no walls.
4. Rotate the camera.
5. **Pass**: Every floor tile shows a radial brightness gradient — tiles near the torch are bright, tiles at the radius edge are near-dark. No two adjacent non-equidistant tiles share the same brightness. No sudden brightness jumps at tile boundaries.
6. **Fail**: Any tile is uniformly lit or unlit across its full surface.

---

## US2 — Directional Light with Geometrically Accurate Shadows

**Goal**: Shadow boundaries from a directional light are straight geometric lines.

### Visual acceptance test

1. Run `./gradlew run`
2. Create a room with a single vertical wall and a directional light at 45°.
3. **Pass**: The shadow of the wall on the floor is a straight diagonal line — verifiable with a ruler against the screen. No staircase steps along any shadow edge.
4. **Fail**: The shadow boundary follows the tile grid in a staircase pattern.

### Regression check

```bash
./gradlew test
```

All existing tests must remain green after the shadow renderer is introduced.

---

## US3 — Point Light Occlusion by Walls

**Goal**: Point light does not bleed through solid walls.

### Visual acceptance test (zero bleeding)

1. Run `./gradlew run`
2. Place a torch in Room A. Move to Room B separated by a solid wall.
3. **Pass**: Room B surface brightness is zero — no glow visible from Room A's torch.
4. **Fail**: Any surface in Room B is lit by Room A's torch.

### Visual acceptance test (pillar shadow)

1. Place a cylindrical pillar between a torch and a far wall.
2. **Pass**: A distinct shadow silhouette of the pillar appears on the far wall at the geometrically correct position.
3. **Fail**: No shadow silhouette visible, or shadow is at wrong position.

---

## US4 — Socket-Based Procedural Dungeon Generation

**Goal**: Generator produces a collision-free, socket-compatible dungeon layout.

### Headless tests (automated)

```bash
./gradlew test --tests "com.roguelike.generation.*"
```

Key scenarios covered by unit tests:
- Collision check rejects a placement when any required cell is occupied.
- Two templates with compatible sockets transition to CONNECTED state.
- A socket with no valid candidates is set to SEALED.
- A 9×9 face exposes 9 sockets, one per Base Unit.

### Integration test (headless)

```bash
./gradlew test --tests "com.roguelike.generation.MapGeneratorIntegrationTest"
```

- Two templates: a 3×3 corridor and a 9×9 room.
- Run generator to completion.
- Assert: every CONNECTED socket pair has matching tags and opposing directions.
- Assert: no grid cell is occupied by more than one submap.
- Assert: every unmatched socket is SEALED.
- Assert: completion time < 5 seconds (SC-004).

---

## US5 — Step-Through Generation Debugger

**Goal**: Debug mode pauses before each placement; developer can accept or reject.

### Visual acceptance test

1. Run `./gradlew run` (or launch with debug flag that enables `MapGenerator(debugMode = true)`).
2. Start procedural generation.
3. **Pass — pause**: Generation pauses before each valid candidate placement; a dialog appears.
4. **Pass — confirm**: Click "I do agree!" — the room is placed, sockets marked CONNECTED, generation advances.
5. **Pass — reject**: Click "I do not agree!" — no placement committed, next candidate evaluated.
6. **Pass — auto-seal**: When no candidates remain for a socket, it is automatically sealed without showing a dialog.
7. **Fail**: Generation does not pause, dialog does not appear, or clicking "I do not agree!" commits a placement.

---

## Regression Guard

After each phase completes, run the full suite:

```bash
./gradlew test
```

All tests must pass. SC-008 requires zero regression in movement, collision, or non-lighting behavior.
