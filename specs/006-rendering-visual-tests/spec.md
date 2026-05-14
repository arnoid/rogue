# Feature Specification: Visual Rendering Test Suite for Shadow Volume Pipeline

**Feature Branch**: `006-rendering-visual-tests`  
**Created**: 2025-05-13  
**Status**: Draft  
**Input**: User description: "Rendering has visual artifacts rendering shadows incorrectly. I want to have a set of tests that can be used to check if shadow casting and light casting is correct, without launching the game and let AI agent to understand if the changes that were introduced by the agent are correct or not."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Basic Shadow/Light Verification Tests (Priority: P1)

As a developer or AI agent making changes to the shadow volume pipeline, I need a set of foundational tests that render simple scenes (light + occluder + receiver) and produce PNG output files, so I can verify that basic shadow casting and illumination work correctly without launching the game.

**Why this priority**: These are the core tests that validate the fundamental shadow volume behavior. If basic shadow casting doesn't work, nothing else matters. This is also the minimum viable test suite that delivers immediate value for regression detection.

**Independent Test**: Can be fully tested by running JUnit tests that render scenes to PNG files and sample specific pixel regions. Delivers immediate value by catching the most common shadow rendering regressions.

**Acceptance Scenarios**:

1. **Given** a scene with a sphere offset on the Y axis partially between a light and a cube, **When** the scene is rendered through the shadow volume pipeline, **Then** the output PNG shows a partial shadow on the cube surface, and pixel sampling confirms darker regions behind the sphere and lit regions outside the shadow.
2. **Given** a scene with a wall between a light source and a cube, **When** the light is positioned behind the wall (opposite side from camera), **Then** the output PNG shows no illumination reaching the cube, and pixel sampling confirms the cube receives only ambient light.
3. **Given** a scene with a light in front of a wall and a cube behind the wall, **When** rendered, **Then** the wall is illuminated but the cube behind it is in shadow (ambient only).
4. **Given** a scene with a light and receiving surfaces but no occluders, **When** rendered, **Then** all surfaces are fully illuminated and pixel sampling confirms uniform brightness across all receivers.
5. **Given** a scene with a light at zero intensity, **When** rendered, **Then** only ambient illumination is visible and all surfaces appear uniformly dim.

---

### User Story 2 - Shadow Volume Geometry Accuracy Tests (Priority: P1)

As a developer modifying the ShadowVolumeBuilder or OccluderExtractor, I need tests that verify shadow volume geometry produces correct shadow shapes for various occluder configurations, so I can confirm that shadow boundaries are accurate.

**Why this priority**: Shadow volume geometry is the core of the rendering pipeline. Incorrect geometry leads directly to the visual artifacts the project is experiencing. Equal priority with basic tests since they target the root cause.

**Independent Test**: Can be tested by rendering scenes with specific occluder shapes and verifying shadow boundary positions via pixel sampling.

**Acceptance Scenarios**:

1. **Given** a single flat wall occluder between a light and a floor plane, **When** rendered, **Then** a clean, sharp shadow boundary appears on the floor, and pixel sampling across the boundary shows a distinct transition from lit to shadowed.
2. **Given** multiple walls forming an open corridor with a light at one end, **When** rendered, **Then** shadows are cast correctly along the corridor walls and floor, with illumination decreasing and shadow coverage matching wall positions.
3. **Given** an L-shaped wall occluder and a light source, **When** rendered, **Then** the shadow wraps around the corner of the L-shape, and pixel sampling confirms shadow coverage matches the expected geometry.
4. **Given** a cube occluder with a light source at various angles (front, side, above), **When** rendered for each angle, **Then** the shadow behind the cube matches the expected projection for each light position.

---

### User Story 3 - Light Position and Distance Behavior Tests (Priority: P2)

As a developer adjusting light attenuation or positioning logic, I need tests that verify how light intensity and shadow behavior change with light position and distance, so I can ensure physically plausible illumination.

**Why this priority**: Light position and distance affect the visual quality but are secondary to whether shadows appear at all. These tests validate the subtleties of illumination once basic shadow casting is confirmed working.

**Independent Test**: Can be tested by rendering scenes with varying light distances and positions, then comparing pixel brightness values.

**Acceptance Scenarios**:

1. **Given** a light placed very close to a flat surface, **When** rendered, **Then** the output shows a bright central spot with steep brightness falloff, and pixel sampling confirms significantly higher brightness at the nearest point compared to edges.
2. **Given** a light placed far from a flat surface, **When** rendered, **Then** the output shows even, dim illumination across the surface, and pixel sampling confirms relatively uniform low brightness.
3. **Given** a light placed inside a closed room (six walls), **When** rendered, **Then** all interior walls are illuminated, and pixel sampling confirms light reaches every wall surface.
4. **Given** a light positioned exactly at a wall's surface, **When** rendered, **Then** the scene renders without visual artifacts, and the wall on the light's side receives illumination while the opposite side does not.

---

### User Story 4 - Multi-Light Interaction Tests (Priority: P2)

As a developer working on the multi-pass rendering pipeline, I need tests that verify correct behavior when multiple lights interact, including additive blending and shadow overlap, so I can ensure the stencil-based multi-pass approach produces correct combined illumination.

**Why this priority**: Multi-light scenarios are common in-game but build upon single-light correctness. These tests validate the additive blending pass of the pipeline.

**Independent Test**: Can be tested by rendering multi-light scenes and sampling pixels in overlap regions to verify additive brightness and color mixing.

**Acceptance Scenarios**:

1. **Given** two lights on opposite sides of an occluder, **When** rendered, **Then** each light illuminates its respective side, shadows from one light are partially cancelled by the other light in overlap regions, and pixel sampling confirms intermediate brightness in dual-lit areas.
2. **Given** multiple lights with different colors (e.g., red and blue), **When** rendered, **Then** areas lit by both lights show blended colors (e.g., purple/magenta), and pixel sampling confirms the expected color channel mixing.
3. **Given** two lights with overlapping radii and no occluders, **When** rendered, **Then** the overlap region is brighter than single-light regions, and pixel sampling confirms additive brightness values.

---

### User Story 5 - Edge Case and Robustness Tests (Priority: P2)

As a developer making pipeline changes, I need tests covering degenerate and boundary conditions, so I can ensure the renderer handles edge cases gracefully without crashes or visual corruption.

**Why this priority**: Edge cases cause subtle bugs that are hard to catch manually. These tests prevent regressions in corner cases that often surface during gameplay.

**Independent Test**: Can be tested by rendering edge-case scenes and verifying no crashes occur and output images are free of corruption artifacts.

**Acceptance Scenarios**:

1. **Given** a camera positioned inside a shadow volume, **When** rendered, **Then** the scene renders correctly without stencil corruption, and pixel sampling confirms that objects outside the shadow volume appear properly lit.
2. **Given** an object positioned exactly at a shadow boundary, **When** rendered, **Then** the object shows partial illumination consistent with its position, and no flickering or z-fighting is visible in the output.
3. **Given** a very thin occluder (near-zero thickness), **When** rendered, **Then** a shadow is still cast correctly behind it, and pixel sampling confirms shadow presence.
4. **Given** an occluder placed behind the light source (farther from the scene), **When** rendered, **Then** no shadow is cast forward into the scene, and pixel sampling confirms full illumination on the receiving surface.

---

### User Story 6 - Regression Tests for Known Artifacts (Priority: P3)

As a developer fixing specific known rendering artifacts, I need tests that reproduce common shadow volume pipeline failure modes, so I can verify fixes and prevent regressions of previously observed bugs.

**Why this priority**: These tests target specific known issues. They are important for regression prevention but are less critical for initial test suite viability since they test failure modes rather than correct behavior.

**Independent Test**: Can be tested by constructing scenes that historically trigger specific artifacts and verifying the output is artifact-free.

**Acceptance Scenarios**:

1. **Given** a scene designed to trigger shadow acne (receiver surface coplanar or nearly coplanar with shadow boundary), **When** rendered, **Then** the output is free of alternating dark/light pixel banding at the shadow edge, and pixel sampling confirms smooth brightness transition.
2. **Given** a thin wall with a light on one side and a receiver on the other, **When** rendered, **Then** no light bleeds through the wall, and pixel sampling confirms the receiver side shows only ambient illumination.
3. **Given** a scene with shadow volume caps potentially visible to the camera, **When** rendered, **Then** no cap geometry is visible as artifacts in the output, and the image shows only expected scene geometry and shadows.
4. **Given** a scene with many overlapping shadow volumes (10+ occluders near a single light), **When** rendered, **Then** the stencil buffer does not overflow, rendering completes without corruption, and pixel sampling confirms correct shadow/light boundaries.

---

### Edge Cases

- What happens when an occluder has zero triangles (degenerate mesh)? The test should render without crashing, producing a fully-lit scene.
- What happens when a light has negative intensity values? The test should handle gracefully without visual corruption.
- What happens when the camera is placed at the exact same position as the light? Rendering should complete without errors.
- What happens when an occluder completely encloses the light? The scene outside the occluder should receive no illumination from that light.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The test suite MUST provide a reusable test harness that initializes a headless rendering environment capable of executing the shadow volume pipeline without a visible window.
- **FR-002**: Each test MUST programmatically construct its 3D scene using primitive shapes (cubes, spheres, planes) without relying on external asset files.
- **FR-003**: Each test MUST position a camera, one or more lights, and occluder/receiver geometry to create a specific shadow/light scenario.
- **FR-004**: Each test MUST render the scene through the full shadow volume pipeline (ambient pass → per-light stencil pass → per-light lit pass).
- **FR-005**: Each test MUST save the rendered framebuffer as a PNG file to a designated output directory (`build/test-output/rendering/`).
- **FR-006**: Each test MUST include pixel-sampling assertions that check brightness and/or color values at specific regions of the rendered image to detect regressions automatically.
- **FR-007**: The test suite MUST be executable via the standard test runner (JUnit Jupiter) through the project's build system.
- **FR-008**: Each test output PNG filename MUST include the test name and scenario identifier for traceability (e.g., `basic_sphere_shadow_on_cube.png`).
- **FR-009**: Pixel-sampling assertions MUST use configurable tolerance thresholds to account for minor rendering variations across environments.
- **FR-010**: The test harness MUST clean up rendering resources (framebuffers, textures, models) after each test to prevent resource leaks.
- **FR-011**: The test suite MUST cover all 24 specified test scenarios across 6 categories: basic light/shadow, shadow volume geometry, light position/distance, multi-light, edge cases, and regression tests.
- **FR-012**: The test output directory MUST be automatically created if it does not exist.
- **FR-013**: Each test MUST be independent and not rely on execution order or state from other tests.

### Key Entities

- **Test Scene**: A programmatically constructed 3D environment consisting of camera, lights, occluders, and receiver surfaces, configured to test a specific shadow/light scenario.
- **Pixel Sample Region**: A defined rectangular area of the rendered image used for brightness/color assertions, specified by coordinates and expected value ranges.
- **Test Output**: A PNG image file produced by each test, serving as both human-readable visual verification and input for automated pixel-sampling assertions.
- **Test Harness**: The shared infrastructure that initializes the headless rendering environment, manages the shadow volume pipeline lifecycle, and provides utilities for scene construction and pixel sampling.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 24 test scenarios execute successfully as automated tests without requiring a visible game window.
- **SC-002**: Each test produces a valid PNG output file that can be visually inspected by a human or AI agent.
- **SC-003**: Pixel-sampling assertions detect intentionally introduced shadow rendering regressions (e.g., removing the stencil pass) with 100% reliability.
- **SC-004**: The full test suite completes execution in under 120 seconds on a standard development machine.
- **SC-005**: An AI agent can determine whether shadow rendering changes are correct or incorrect by examining test results (pass/fail) and output PNG files, without launching the game.
- **SC-006**: Zero false positives from pixel-sampling assertions when the renderer is functioning correctly (tolerance thresholds are tuned appropriately).
- **SC-007**: The test suite catches the known visual artifacts (shadow acne, light bleeding, cap artifacts, stencil overflow) when they are present.

## Assumptions

- The existing headless/offscreen rendering capability (Lwjgl3 backend with offscreen FBO or hidden window) is functional and can execute the full shadow volume pipeline including stencil operations.
- ModelBuilder can create adequate primitive geometry (cubes, spheres, planes) for test scenes without requiring external 3D asset files.
- The shadow volume pipeline (ShadowVolumeRenderer, ShadowVolumeBuilder, ShadowVolumeShaderProvider, OccluderExtractor) is architecturally stable and does not require major refactoring to be testable in isolation.
- Pixel color values are deterministic enough across test runs on the same machine to allow threshold-based assertions (no random sampling or non-deterministic rendering paths).
- The existing JUnit Jupiter 5.10.1 setup supports the lifecycle requirements for initializing and tearing down the rendering environment per test or per test class.
- GPU/driver differences across development machines may cause minor pixel-level variations; tolerance thresholds will accommodate this.
- The `build/test-output/rendering/` directory is not cleaned between test runs unless explicitly requested, allowing historical comparison of outputs.

