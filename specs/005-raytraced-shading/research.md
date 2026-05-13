# Research: Stencil Shadow Volume Lighting

**Feature**: 005-raytraced-shading | **Date**: 2026-05-13

## R1: Infinity Extrusion with LibGDX PerspectiveCamera

**Decision**: Use a large finite extrusion distance (W = 1000 units) instead of true `w=0` infinity projection.

**Rationale**: LibGDX's `PerspectiveCamera` does not support an infinite far plane. Patching the projection matrix to use `far = ∞` requires overriding `PerspectiveCamera.update()` and applying the Infinite Reverse-Z transform, which introduces risk with LibGDX's built-in frustum culling and `ModelBatch` sorting. A large finite W (1000 units, ~100× the dungeon's maximum dimension) produces visually identical results for the dungeon's scale without modifying camera internals.

**Alternatives considered**:
- **Custom infinite projection matrix**: Correct but breaks `camera.frustum.boundsInFrustum()` used by existing tile culling. Would require replacing all frustum checks.
- **`w=0` homogeneous coordinates**: The mathematically correct approach, but LibGDX's `Mesh` class uses `float[]` vertex data with no special handling for `w=0`. Would need a custom shader to handle the divide-by-zero case. Overly complex for the dungeon's scale.

## R2: LibGDX Stencil Buffer Access

**Decision**: Use `Gdx.gl.glStencilFunc()` / `glStencilOp()` / `glStencilMask()` directly via LibGDX's OpenGL bindings. LibGDX does not wrap stencil operations in any higher-level API.

**Rationale**: LibGDX exposes the full OpenGL ES / desktop GL API through `Gdx.gl` (which maps to `GL20` / `GL30`). All stencil operations (`glEnable(GL_STENCIL_TEST)`, `glStencilFunc`, `glStencilOp`, `glClear(GL_STENCIL_BUFFER_BIT)`) are available. The LWJGL3 backend requests an 8-bit stencil buffer by default via `Lwjgl3ApplicationConfiguration`.

**Alternatives considered**:
- **LibGDX `Environment` / `Attribute` system**: Does not support stencil operations. Only handles lighting attributes.

## R3: Shadow Volume Mesh Construction in LibGDX

**Decision**: Build shadow volume geometry using `LibGDX Mesh` with `VertexAttribute.Position()` only, uploaded per-frame via `Mesh.setVertices()`.

**Rationale**: Shadow volumes are position-only geometry (no normals, UVs, colors). LibGDX's `Mesh` class supports dynamic vertex updates efficiently. Per-frame upload is acceptable given the low vertex count (~48 verts per wall segment × 4 lights = ~200 shadow volume quads worst case).

**Alternatives considered**:
- **VBO with `GL_STREAM_DRAW`**: Would require raw OpenGL calls to bypass LibGDX's `Mesh`. More performant for high vertex counts but unnecessary at this scale.
- **Reuse `ModelBatch`**: Shadow volumes don't need materials/textures. Using `ModelBatch` adds overhead from material sorting. Direct `mesh.render()` with a bound shader is simpler.

## R4: Silhouette Caching Strategy

**Decision**: Cache silhouette edges per (mesh, light-position) pair. Invalidate when the light moves or the mesh is modified. Use a `HashMap<Pair<Int, Int>, SilhouetteCache>` keyed by (meshId, lightId).

**Rationale**: Dungeon geometry is static between level generation events. Lights attached to carried items move with the player but at most 4 lights update per frame. Caching eliminates redundant CPU work for static occluders lit by stationary lights (e.g., wall torches).

**Alternatives considered**:
- **No caching**: Simpler but wastes CPU on static geometry. With ~200 wall segments × 4 lights, silhouette detection could cost 2ms+ per frame.
- **Spatial hash for light proximity**: Over-engineered for ≤ 4 lights.

## R5: Render Pass Integration with Existing WorldRenderer

**Decision**: Replace the `dynamicLighting` parameter in `WorldRenderer.render()` with a `shadowVolumeRenderer: ShadowVolumeRenderer?` parameter. The `ShadowVolumeRenderer` takes ownership of the full multi-pass pipeline (ambient → stencil → lit) and calls back into `WorldRenderer` for scene geometry rendering.

**Rationale**: The existing `WorldRenderer` iterates tiles and calls `tileRenderer.render()` per tile with a per-tile `Environment`. The shadow volume pipeline requires full-scene passes (ambient pass renders ALL geometry, then each light pass renders ALL geometry again with stencil test). This inversion means `ShadowVolumeRenderer` drives the render loop and delegates geometry submission to `WorldRenderer`.

**Alternatives considered**:
- **Keep per-tile rendering**: Incompatible with stencil shadow volumes which require full-scene passes.
- **Separate renderer entirely**: Would duplicate the tile/prop/item iteration logic.

## R6: Depth-Fail vs Depth-Pass

**Decision**: Depth-fail (Carmack's Reverse) as specified.

**Rationale**: Depth-pass is simpler but fails when the camera is inside a shadow volume (common in a first-person/close-camera roguelike). Depth-fail handles this case correctly at the cost of requiring front and back caps on the shadow volume (already part of the spec).

**Alternatives considered**:
- **Depth-pass (Z-pass)**: Fails for camera-in-shadow case. Would require detection and fallback, adding complexity.

## R7: `DynamicLighting.kt` in `core/systems/` — LibGDX Dependency

**Decision**: Delete `DynamicLighting.kt`, `LightingSystem.kt`, `SurfaceLighting.kt`, and `LightingDiagnostics.kt` from `core/systems/`. These files import LibGDX classes (`com.badlogic.gdx.graphics.*`) violating the core-LibGDX-free constraint.

**Rationale**: The spec explicitly calls for deletion. The `core/model/` data classes (`LightDef`, `LightShape`, `LightDirection`, etc.) are LibGDX-free and will be retained as the data layer consumed by the new `rendering/` classes.

**Alternatives considered**:
- **Move to `rendering/` instead of delete**: The classes are tightly coupled to the old per-tile `Environment` approach and are not salvageable for the shadow volume pipeline.

