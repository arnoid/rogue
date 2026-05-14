# Data Model: Visual Rendering Test Suite

## Test Infrastructure Entities

### RenderTestHarness

Manages the headless libGDX application lifecycle and provides rendering utilities.

| Field | Type | Description |
|-------|------|-------------|
| app | Lwjgl3Application | Hidden-window libGDX application instance |
| fbo | FrameBuffer | Offscreen framebuffer with stencil attachment |
| camera | PerspectiveCamera | Configurable test camera |
| modelBatch | ModelBatch | Scene rendering batch |
| shaderProvider | ShadowVolumeShaderProvider | Shadow volume shader manager |
| renderer | ShadowVolumeRenderer | Multi-pass stencil pipeline |
| outputDir | File | `build/test-output/rendering/` |
| width | Int | FBO width (default: 512) |
| height | Int | FBO height (default: 512) |

**Operations**:
- `initialize()`: Create app, FBO, camera, batches
- `renderScene(lights, occluderTris, renderFn) → Pixmap`: Execute full pipeline, return framebuffer contents
- `saveImage(pixmap, testName)`: Write PNG to output directory
- `dispose()`: Clean up all GL resources

### PixelSampler

Utility for sampling and asserting pixel values from rendered images.

| Field | Type | Description |
|-------|------|-------------|
| pixmap | Pixmap | Source rendered image |
| tolerance | Int | Default brightness tolerance (0-255, default: 15) |

**Operations**:
- `sampleRegion(x, y, w, h) → RegionStats`: Average R, G, B, brightness for a region
- `assertLit(x, y, w, h, minBrightness)`: Assert region brightness above threshold
- `assertShadowed(x, y, w, h, maxBrightness)`: Assert region brightness below threshold
- `assertBrighterThan(litRegion, shadowRegion, factor)`: Relative brightness comparison
- `assertColor(x, y, w, h, expectedR, expectedG, expectedB, tolerance)`: Color assertion

### RegionStats

Result of sampling a pixel region.

| Field | Type | Description |
|-------|------|-------------|
| avgR | Float | Average red channel (0-255) |
| avgG | Float | Average green channel (0-255) |
| avgB | Float | Average blue channel (0-255) |
| avgBrightness | Float | Average brightness: (R + G + B) / 3 |
| minBrightness | Float | Minimum pixel brightness in region |
| maxBrightness | Float | Maximum pixel brightness in region |

### SceneBuilder

DSL-style builder for constructing test scenes programmatically.

| Field | Type | Description |
|-------|------|-------------|
| models | List\<ModelInstance\> | Scene geometry instances |
| lights | List\<PointLightData\> | Scene point lights |
| occluderTriangles | List\<List\<Triangle\>\> | Occluder geometry for shadow volumes |
| cameraPosition | Vector3 | Camera world position |
| cameraLookAt | Vector3 | Camera look-at target |

**Operations**:
- `addBox(position, size, color) → SceneBuilder`: Add a box to the scene
- `addSphere(position, radius, color) → SceneBuilder`: Add a sphere
- `addPlane(position, normal, size, color) → SceneBuilder`: Add a flat plane
- `addWall(position, direction, size, color) → SceneBuilder`: Add a wall (both model + occluder triangles)
- `addLight(position, color, intensity, radius) → SceneBuilder`: Add a point light
- `camera(position, lookAt) → SceneBuilder`: Configure camera
- `build() → TestScene`: Finalize and return all scene data

### TestScene

Immutable scene data ready for rendering.

| Field | Type | Description |
|-------|------|-------------|
| modelInstances | List\<ModelInstance\> | All renderable geometry |
| lights | List\<PointLightData\> | All active lights |
| occluderTriangles | List\<List\<Triangle\>\> | Occluder triangle lists |
| camera | PerspectiveCamera | Configured camera |

## Relationships

```
RenderTestHarness ──uses──→ ShadowVolumeRenderer
RenderTestHarness ──uses──→ ShadowVolumeShaderProvider
RenderTestHarness ──produces──→ Pixmap
SceneBuilder ──produces──→ TestScene
TestScene ──consumed by──→ RenderTestHarness.renderScene()
PixelSampler ──operates on──→ Pixmap
PixelSampler ──produces──→ RegionStats
```

## State Transitions

### RenderTestHarness Lifecycle

```
UNINITIALIZED → initialize() → READY → renderScene() → READY → dispose() → DISPOSED
```

### Test Execution Flow

```
@BeforeAll: harness.initialize()
@Test: scene = SceneBuilder.build()
     → pixmap = harness.renderScene(scene)
     → harness.saveImage(pixmap, testName)
     → PixelSampler(pixmap).assertLit/assertShadowed(...)
     → pixmap.dispose()
@AfterAll: harness.dispose()
```

