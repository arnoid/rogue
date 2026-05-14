# Research: Visual Rendering Test Suite

## R1: Headless Rendering with libGDX + LWJGL3

**Decision**: Use LWJGL3 backend with a hidden window and FBO (FrameBuffer) for offscreen rendering.

**Rationale**: libGDX doesn't support truly headless GL rendering. LWJGL3 requires a window for OpenGL context creation. The standard approach is to create a hidden window (`Lwjgl3ApplicationConfiguration.setInitialVisible(false)`) and render to an FBO. The FBO contents can then be read back via `ScreenUtils.getFrameBufferPixmap()` and saved as PNG.

**Alternatives considered**:
- Mesa/software GL: Too complex to set up, platform-dependent
- Mock GL: Would not test actual rendering pipeline
- Lwjgl3 headless mode: Not available; LWJGL3 requires a window for GL context

**Key implementation details**:
- Use `Lwjgl3Application` with hidden window, started once per test class via `@BeforeAll`
- FBO must request stencil buffer (8-bit) for shadow volume stencil operations
- `Lwjgl3ApplicationConfiguration`: `setBackBufferConfig(8, 8, 8, 8, 16, 8, 0)` for RGBA8 + depth16 + stencil8
- Use `Gdx.app.postRunnable {}` to execute rendering on the GL thread
- Clean up GL resources in `@AfterAll`

## R2: FBO with Stencil Buffer Support

**Decision**: Create FBO using `FrameBuffer.FrameBufferBuilder` with explicit stencil attachment.

**Rationale**: The default `FrameBuffer` constructor doesn't include a stencil buffer. The shadow volume pipeline requires stencil for depth-fail marking. `FrameBufferBuilder` allows specifying `addDepthRenderBuffer(GL30.GL_DEPTH24_STENCIL8)` for combined depth-stencil.

**Alternatives considered**:
- Render to default framebuffer: Works but can't control size independently of window
- Separate depth/stencil buffers: Less portable, combined depth-stencil is standard

## R3: Pixel Sampling Strategy

**Decision**: Sample rectangular regions and compute average brightness/color, compare against expected ranges with configurable tolerance.

**Rationale**: Individual pixel comparisons are too brittle across GPU/driver combinations. Sampling a region (e.g., 10x10 pixels) and averaging provides robustness while still detecting regressions. Tolerance thresholds (e.g., ±15/255) handle minor variations.

**Alternatives considered**:
- Reference image comparison (perceptual diff): Too brittle across GPUs, requires golden images
- Exact pixel matching: Fails across different hardware
- Histogram comparison: More complex, harder to author assertions

**Sampling approach**:
- Define `PixelRegion(x, y, width, height)` for areas of interest
- Compute average R, G, B, brightness for a region
- Assert `brightness > threshold` for lit regions, `brightness < threshold` for shadowed
- Assert relative comparisons: `litRegion.brightness > shadowRegion.brightness * factor`

## R4: Scene Construction with ModelBuilder

**Decision**: Use libGDX `ModelBuilder` to create primitive geometry (boxes, spheres, rects) programmatically.

**Rationale**: `ModelBuilder.createBox()`, `ModelBuilder.createSphere()`, and `ModelBuilder.createRect()` provide adequate primitives. Combined with `ModelInstance` positioning via `transform`, this covers all test scene requirements without external assets.

**Alternatives considered**:
- Loading .obj/.g3db files: Adds external dependencies, harder to maintain
- Raw mesh construction: More code, ModelBuilder already does this

## R5: Test Lifecycle and GL Thread Synchronization

**Decision**: Use a shared `Lwjgl3Application` instance per test class with `CountDownLatch` synchronization for GL thread execution.

**Rationale**: Creating/destroying the GL context per test is slow (~500ms each). Sharing across a test class keeps the suite under 120s. JUnit test methods run on the test thread, but GL calls must happen on the GL thread. `Gdx.app.postRunnable` + `CountDownLatch` bridges this gap.

**Alternatives considered**:
- One app per test: Too slow (24 × 500ms = 12s just for startup)
- Global singleton for all test classes: Risks shared state; per-class is safer
- Running tests on GL thread directly: JUnit doesn't support this easily

## R6: Occluder Triangle Extraction for Test Scenes

**Decision**: Construct `ShadowVolumeBuilder.Triangle` lists directly in tests rather than using `OccluderExtractor`.

**Rationale**: `OccluderExtractor` requires a full `World` object. Test scenes use simple primitives, so it's simpler to construct triangle lists directly (similar to `ShadowVolumeBuilderTest`). This keeps tests focused on the rendering pipeline, not world construction.

**Alternatives considered**:
- Building a World and using OccluderExtractor: Too much setup for rendering tests
- Extracting triangles from ModelInstance meshes: Possible but adds complexity

