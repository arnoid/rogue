# Quickstart: Visual Rendering Test Suite

## Prerequisites

- JDK 17+ with Kotlin 1.9.22
- GPU with OpenGL 3.0+ support (for stencil buffer)
- Display available (LWJGL3 requires window creation even if hidden)

## Running Tests

```bash
# Run all rendering visual tests
./gradlew test --tests "com.roguelike.rendering.*Test"

# Run a specific test class
./gradlew test --tests "com.roguelike.rendering.BasicShadowLightTest"

# Run a specific test
./gradlew test --tests "com.roguelike.rendering.BasicShadowLightTest.sphere shadow on cube"
```

## Viewing Output

Test PNGs are saved to `build/test-output/rendering/`. Each file is named after the test:

```
build/test-output/rendering/
├── basic_sphere_partial_shadow_on_cube.png
├── basic_wall_full_occlusion.png
├── basic_wall_front_lit_back_shadow.png
├── basic_no_occluder_full_illumination.png
├── basic_zero_intensity_ambient_only.png
├── geometry_flat_wall_sharp_boundary.png
├── geometry_corridor_shadows.png
├── geometry_l_shaped_wall_wrap.png
├── geometry_cube_multi_angle.png
├── ...
```

## Writing a New Test

```kotlin
class MyRenderingTest {
    companion object {
        private lateinit var harness: RenderTestHarness

        @JvmStatic @BeforeAll
        fun setup() { harness = RenderTestHarness(); harness.initialize() }

        @JvmStatic @AfterAll
        fun teardown() { harness.dispose() }
    }

    @Test
    fun `my shadow scenario`() {
        val scene = SceneBuilder()
            .camera(Vector3(0f, 5f, 10f), Vector3.Zero)
            .addPlane(Vector3.Zero, Vector3.Y, 10f, Color.GRAY)        // floor
            .addBox(Vector3(0f, 0.5f, 0f), Vector3(1f, 1f, 1f), Color.RED) // occluder
            .addLight(Vector3(3f, 3f, 3f), Color.WHITE, 5f, 20f)
            .build()

        val pixmap = harness.renderScene(scene)
        harness.saveImage(pixmap, "my_shadow_scenario")

        val sampler = PixelSampler(pixmap)
        sampler.assertLit(300, 256, 20, 20, minBrightness = 40f)      // lit area
        sampler.assertShadowed(200, 256, 20, 20, maxBrightness = 25f)  // shadow area
        pixmap.dispose()
    }
}
```

## Key Design Decisions

- **No reference images**: Tests use pixel-sampling assertions with tolerance, not golden image comparison
- **No external assets**: All geometry created via `ModelBuilder` primitives
- **Occluder triangles constructed directly**: Tests build `ShadowVolumeBuilder.Triangle` lists, not full `World` objects
- **Shared harness per class**: One GL context per test class for performance
- **512×512 FBO**: Balances detail vs. rendering speed

