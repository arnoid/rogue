package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * User Story 3: Light Position and Distance Behavior Tests (P2)
 * 4 tests verifying light intensity and shadow behavior with position and distance.
 */
class LightPositionDistanceTest : GLTestBase() {

    /**
     * T020: Light very close to surface — bright center, dimmer edges.
     */
    @Test
    fun closeLightBrightSpotSteepFalloff() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 10f, 0.1f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Light very close to the floor
            .addLight(Vector3f(0f, 0f, 0f), Vector4f(1f, 1f, 1f, 1f), 10f, 15f)

        val (pixelData, scene) = harness.renderAndSave(builder, "light_close_bright_spot")
        val sampler = PixelSampler(pixelData)
        val center = sampler.sampleRegion(241, 300, 30, 30)
        val edge = sampler.sampleRegion(50, 300, 30, 30)
        // Center should be brighter than edge due to attenuation
        assertTrue(center.avgBrightness > edge.avgBrightness,
            "Center (${center.avgBrightness}) should be brighter than edge (${edge.avgBrightness})")
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T021: Light far from surface — uniform low brightness.
     */
    @Test
    fun farLightEvenDimIllumination() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Light very far away
            .addLight(Vector3f(0f, 40f, 0f), Vector4f(1f, 1f, 1f, 1f), 5f, 60f)

        val (pixelData, scene) = harness.renderAndSave(builder, "light_far_even_dim")
        val sampler = PixelSampler(pixelData)
        val left = sampler.sampleRegion(150, 350, 30, 30)
        val right = sampler.sampleRegion(360, 350, 30, 30)
        // Both sides should have similar (low) brightness
        val diff = kotlin.math.abs(left.avgBrightness - right.avgBrightness)
        assertTrue(diff < 25f, "Expected uniform dim illumination, diff=$diff")
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T022: Light inside a closed room — all interior walls illuminated.
     */
    @Test
    fun lightInsideClosedRoom() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 2f, 4.5f), Vector3f(0f, 1f, 0f))
            // Floor
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 10f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Ceiling
            .addPlane(Vector3f(0f, 4f, 0f), Vector3f(0f, 1f, 0f), 10f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Walls
            .addWall(Vector3f(-5f, 1.5f, 0f), 10f, 5f, Vector4f(0.75f, 0.75f, 0.75f, 1f), 0.2f, Vector3f(1f, 0f, 0f))
            .addWall(Vector3f(5f, 1.5f, 0f), 10f, 5f, Vector4f(0.75f, 0.75f, 0.75f, 1f), 0.2f, Vector3f(1f, 0f, 0f))
            .addWall(Vector3f(0f, 1.5f, -5f), 10f, 5f, Vector4f(0.75f, 0.75f, 0.75f, 1f), 0.2f, Vector3f(0f, 0f, 1f))
            .addWall(Vector3f(0f, 1.5f, 5f), 10f, 5f, Vector4f(0.75f, 0.75f, 0.75f, 1f), 0.2f, Vector3f(0f, 0f, 1f))
            // Light inside room
            .addLight(Vector3f(0f, 2f, 0f), Vector4f(1f, 1f, 1f, 1f), 8f, 20f)

        val (pixelData, scene) = harness.renderAndSave(builder, "light_inside_closed_room")
        PixelSampler(pixelData).assertLit(256, 256, 40, 40, minBrightness = 20f)
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T023: Light at wall surface — no artifacts, correct illumination sides.
     */
    @Test
    fun lightAtWallSurface() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(8f, 4f, 0f), Vector3f(0f, 1f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Wall
            .addWall(Vector3f(0f, 1.5f, 0f), 6f, 4f, Vector4f(0.75f, 0.75f, 0.75f, 1f), 0.2f, Vector3f(1f, 0f, 0f))
            // Light right at wall surface
            .addLight(Vector3f(0.15f, 2f, 0f), Vector4f(1f, 1f, 1f, 1f), 8f, 20f)

        val (pixelData, scene) = harness.renderAndSave(builder, "light_at_wall_surface")
        PixelSampler(pixelData).assertLit(300, 256, 30, 30, minBrightness = 20f)
        pixelData.dispose(); harness.disposeScene(scene)
    }
}

