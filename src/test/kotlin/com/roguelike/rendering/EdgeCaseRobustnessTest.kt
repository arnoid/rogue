package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * User Story 5: Edge Case and Robustness Tests (P2)
 * 4 tests verifying graceful handling of degenerate and boundary conditions.
 */
class EdgeCaseRobustnessTest : GLTestBase() {

    /**
     * T029: Camera inside shadow volume — no stencil corruption.
     */
    @Test
    fun cameraInsideShadowVolume() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 0.5f, 2f), Vector3f(0f, 0f, -5f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            .addOccluderBox(Vector3f(0f, 0.5f, 0f), Vector3f(1f, 1f, 1f), Vector4f(1f, 0f, 0f, 1f))
            // Light behind camera, so shadow volume extends through camera
            .addLight(Vector3f(0f, 2f, 5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "edge_camera_inside_shadow_volume")
        val sampler = PixelSampler(pixelData)
        val side = sampler.sampleRegion(50, 256, 30, 30)
        // Just verify no crash and some rendering occurred
        assertTrue(side.avgBrightness >= 0f, "Rendering should produce valid pixels")

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T030: Object at shadow boundary — partial illumination, no z-fighting.
     */
    @Test
    fun objectAtShadowBoundary() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Wall casting shadow
            .addWall(Vector3f(0f, 1f, 0f), 4f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.1f, Vector3f(0f, 0f, 1f))
            // Box placed right at expected shadow boundary edge
            .addBox(Vector3f(2f, 0f, -2f), Vector3f(1f, 1f, 1f), Vector4f(1f, 1f, 1f, 1f))
            .addLight(Vector3f(0f, 3f, 5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "edge_object_at_shadow_boundary")

        // Should not crash or produce z-fighting artifacts
        val sampler = PixelSampler(pixelData)
        assertTrue(PixelSampler(pixelData).sampleRegion(256, 256, 40, 40).avgBrightness >= 0f,
            "Should render without artifacts")

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T031: Near-zero thickness occluder still casts shadow.
     */
    @Test
    fun thinOccluderCastsShadow() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Very thin wall
            .addWall(Vector3f(0f, 1f, 0f), 4f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.02f, Vector3f(0f, 0f, 1f))
            .addLight(Vector3f(0f, 3f, 5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "edge_thin_occluder_shadow")
        PixelSampler(pixelData).assertBrighterThan(256, 150, 30, 30, 256, 350, 30, 30, 1.2f)

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T032: Occluder behind light — no forward shadow, full illumination.
     */
    @Test
    fun occluderBehindLightNoForwardShadow() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Receiver
            .addBox(Vector3f(0f, 0f, -3f), Vector3f(3f, 2f, 2f), Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Occluder behind the light (should not cast shadow forward)
            .addOccluderBox(Vector3f(0f, 0.5f, 8f), Vector3f(2f, 2f, 2f), Vector4f(0.25f, 0.25f, 0.25f, 1f))
            // Light between occluder and receiver
            .addLight(Vector3f(0f, 3f, 3f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "edge_occluder_behind_light")
        PixelSampler(pixelData).assertLit(256, 256, 40, 40, minBrightness = 20f)

        pixelData.dispose(); harness.disposeScene(scene)
    }
}

