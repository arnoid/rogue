# Research: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Feature**: `004-gles3-mesh-shadow-lighting` | **Date**: 2026-05-13

---

## Finding 1: Desktop GL Context vs GLES 3.0

**Decision**: Target GLSL 1.50 (`#version 150`) on desktop, which is the functional equivalent of GLSL ES 3.00 (`#version 300 es`) for our purposes.

**Rationale**: `Main.kt` uses `GLEmulation.GL30, 3, 2` which creates an OpenGL 3.2 core profile context on desktop macOS/Linux. OpenGL 3.2 core uses GLSL 1.50 — not GLSL ES 3.00. They share identical syntax for `in`/`out`, `texture()`, and `layout` qualifiers. The spec says "GLES 3.0" but on desktop this maps to GLSL 1.50. Shaders must use `#version 150` (not `#version 300 es`) to compile under the actual context.

**Alternatives considered**: Downgrading to `GLEmulation.ANGLE` or `GL20` to avoid core profile — rejected because the existing `DirectionalShadowLight` requires OpenGL 3.x.

---

## Finding 2: LibGDX Shader Extension Pattern

**Decision**: Use a custom `ShaderProvider` returning instances of a `Gles3LightingShader` that extends LibGDX `DefaultShader`.

**Rationale**: Writing a `Shader` implementation from scratch requires re-implementing every LibGDX built-in uniform (model transform, camera projection, texture sampler, bone weights, blending). `DefaultShader` already handles all of this. The preferred extension pattern is:
- Subclass `DefaultShader` (or compose with `ShaderProgram`)
- Override `getDefaultVertexShader()` / `getDefaultFragmentShader()` to supply custom GLSL source
- `DefaultShader` injects the prefix (position, normal, UV, MVP) automatically when it generates the shader

However, `DefaultShader.getDefaultVertexShader()` is static and not overridable. The correct LibGDX extension point is:
1. Create a `ShaderProvider` that, for each `Renderable`, builds a `DefaultShader` with custom prefix/suffix code injected via the `ShaderProgram.prependVertexCode` / `prependFragmentCode` globals — OR —
2. Build a `ShaderProgram` directly from our GLSL files and implement the `Shader` interface (`begin`, `render`, `end`) manually.

**Selected approach**: Write a `Gles3LightingShader` implementing `com.badlogic.gdx.graphics.g3d.Shader` directly. This is more work but gives full control of uniforms and avoids fighting `DefaultShader`'s dynamic source generation. We only need to handle the uniforms our tiles actually use: `u_projTrans`, `u_viewTrans`, `u_worldTrans`, `u_texture`, normals — no skeletal animation, no blend shapes.

**Alternatives considered**:
- Extend `DefaultShader` with custom prefix — rejected because `DefaultShader` generates shader source at construction and does not support injection after the `#version` line in GLSL 1.50 core.
- Use LibGDX's `BaseShader` — viable alternative; `BaseShader` is lighter than `DefaultShader` and provides the `Uniform` / `Setter` infrastructure. Selected over raw `Shader` interface for its built-in uniform registration helpers.

**Revised decision**: Extend `BaseShader` (not `DefaultShader`). `BaseShader` provides `register()`/`set()` helpers for uniforms without the opinionated source generation of `DefaultShader`.

---

## Finding 3: Directional Shadow Map

**Decision**: Keep LibGDX `DirectionalShadowLight` + `DepthShaderProvider` for the depth pass. The existing `ShadowRenderer.initDirectionalLight()` + depth-pass logic is correct and does not need replacement.

**Rationale**: `DirectionalShadowLight` renders into a 2048×2048 `FrameBuffer` using `DepthShaderProvider`, which writes depth from the orthographic light camera. The shadow map texture handle is accessible via `shadowLight.frameBuffer.colorBufferTexture`. Our custom `Gles3LightingShader` will bind this texture as `uniform sampler2D u_shadowMap` and perform PCF (percentage-closer filtering) shadow lookup in the fragment shader.

**Alternatives considered**: Custom FBO for directional shadow — rejected; `DirectionalShadowLight` already handles this correctly and is battle-tested.

---

## Finding 4: Point Light Omnidirectional Shadows

**Decision**: Use `FrameBufferCubemap` (already in `ShadowRenderer`) for depth-only cubemap rendering, then bind the result as `uniform samplerCube u_pointShadowCube[8]` in the fragment shader.

**Rationale**: The existing `renderCubemapDepthPass()` method correctly iterates the 6 cube faces via `fbo.nextSide()`. The missing piece from the previous sprint is wiring the cubemap texture handle into the main-pass fragment shader. The `Gles3LightingShader` will keep an array of 8 `FrameBufferCubemap` handles (managed by `ShadowRenderer`) and bind their color-buffer textures (which store depth in the red channel via RGBA8888) as cubemap samplers.

**Constraint**: `FrameBufferCubemap.getColorBufferTexture()` returns a `Texture` that wraps the GL cubemap object. Binding it to a `samplerCube` uniform requires calling `Gdx.gl.glActiveTexture()` + `texture.bind(unit)` manually for each of the 8 slots.

---

## Finding 5: Scope of Old System Deletion

**Decision**: Delete the following files entirely. No feature flags, no deprecation period.

**Files to delete**:
- `src/main/kotlin/com/roguelike/core/systems/DynamicLighting.kt` (657 lines, CPU per-cell lighting)
- `src/main/kotlin/com/roguelike/core/systems/SurfaceLighting.kt` (530 lines, per-surface CPU variant)
- `src/main/kotlin/com/roguelike/core/systems/LightingSystem.kt` (468 lines, CPU raycasting map)
- `src/main/kotlin/com/roguelike/core/systems/LightingDiagnostics.kt` (diagnostics for old system)
- `src/main/kotlin/com/roguelike/rendering/BvhOccluder.kt` (87 lines, BVH for CPU occlusion)
- `src/main/resources/shaders/point_shadow.frag.glsl` (replaced by new shader pair)

**Test files to delete** (test the deleted systems — no longer meaningful):
- `src/test/kotlin/com/roguelike/core/LightingSystemTest.kt`
- `src/test/kotlin/com/roguelike/core/LightingSystemConeTest.kt`
- `src/test/kotlin/com/roguelike/core/SurfaceLightingTest.kt`
- `src/test/kotlin/com/roguelike/core/SurfaceLightingConeSmoothTest.kt`
- `src/test/kotlin/com/roguelike/core/SurfaceLightingModelOcclusionTest.kt`
- `src/test/kotlin/com/roguelike/core/DynamicLightingTest.kt`

**Files to keep** (remain correct):
- `core/model/lighting/DirectionalLightData.kt` — LibGDX-free data class, tested, correct
- `core/model/lighting/PointLightData.kt` — same
- `core/model/lighting/GpuLightEnvironment.kt` — same, with build() factory and 8-light cap

**Callers to update after deletion**:
- `RoguelikeGame.kt`: remove `bvhOccluder`, remove `DynamicLighting.build()` call, switch `worldRenderer.render()` to the `ShadowRenderer` overload
- `WorldRenderer.kt`: remove the legacy `DynamicLighting?` parameter overload and `envForTile()` helper
- `DropPickupTest.kt`: remove two `LightingSystem.compute()` calls (replace with a simpler assertion that doesn't need lighting)

---

## Finding 6: Constitution Compliance

**Principle I (Core-Rendering Separation)**: COMPLIANT. The three `core/model/lighting/` data classes have zero LibGDX imports. `Gles3LightingShader`, `Gles3ShaderProvider`, and the updated `ShadowRenderer` live in `rendering` only.

**Principle II (TDD)**: The `Gles3LightingShader` can be tested for uniform registration and `GpuLightEnvironment` → uniform value mapping without a display, by constructing the shader with a mock `ShaderProgram`. The cubemap shadow path is display-dependent and will be tested via visual acceptance only. All data-model tests already pass.

**Principle V (YAGNI)**: Removing ~1,700 lines of CPU lighting code is a net simplification. No new abstractions are added beyond what FR-001–FR-010 require.

---

## Finding 7: Shader Uniform Layout

The `Gles3LightingShader` fragment shader will accept:

```glsl
// Standard LibGDX geometry
uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;
uniform mat3 u_normalMatrix;
uniform sampler2D u_diffuseTexture;

// Ambient
uniform vec3 u_ambientColor;

// Directional light + shadow map
uniform vec3  u_dirLightDir;
uniform vec3  u_dirLightColor;
uniform mat4  u_shadowMapProjViewTrans;
uniform sampler2D u_shadowMap;
uniform int   u_hasDirLight;

// Point lights (up to 8)
uniform int   u_pointLightCount;
uniform vec3  u_pointLightPos[8];
uniform vec3  u_pointLightColor[8];
uniform float u_pointLightIntensity[8];

// Point light cubemap shadows (up to 8)
uniform samplerCube u_pointShadowCube[8];
uniform float       u_pointShadowFarPlane[8];
uniform int         u_hasPointShadow[8];
```

Vertex outputs (passed to fragment): `v_texCoord`, `v_worldPos`, `v_worldNormal`, `v_shadowCoord` (light-space position for directional shadow lookup).
