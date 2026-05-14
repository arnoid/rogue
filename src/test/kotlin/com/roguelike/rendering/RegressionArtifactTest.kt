package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * User Story 6: Regression Tests for Known Artifacts (P3)
 * 4 tests reproducing known shadow volume failure modes.
 */
class RegressionArtifactTest : GLTestBase() {

    /**
     * T034: No shadow acne — receiver coplanar with shadow boundary.
     * Assert: no alternating dark/light pixel banding.
     */
    @Test
    fun noShadowAcne() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Wall with receiver plane very close to shadow boundary
            .addWall(Vector3f(0f, 1f, 0f), 6f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.1f, Vector3f(0f, 0f, 1f))
            .addBox(Vector3f(0f, -0.99f, -3f), Vector3f(4f, 0.01f, 4f), Vector4f(1f, 1f, 1f, 1f))
            .addLight(Vector3f(0f, 3f, 5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "regression_no_shadow_acne")
        val sampler = PixelSampler(pixelData)
        // Sample a region that might show acne — check brightness variance is low
        val stats = sampler.sampleRegion(256, 350, 50, 10)
        val variance = stats.maxBrightness - stats.minBrightness
        // With acne, variance would be very high (alternating lit/shadow pixels)
        // Allow reasonable variance but flag extreme banding
        assertTrue(
            variance < 120f,
            "Possible shadow acne: brightness variance $variance in boundary region (max-min: ${stats.maxBrightness}-${stats.minBrightness})"
        )

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T035: No light bleed through thin wall.
     */
    @Test
    fun noLightBleedThroughWall() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(6f, 4f, 0f), Vector3f(0f, 1f, 0f))
            // Thin wall
            .addWall(Vector3f(0f, 1.5f, 0f), 8f, 5f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.05f, Vector3f(1f, 0f, 0f))
            // Receiver on far side
            .addBox(Vector3f(-3f, 0.5f, 0f), Vector3f(2f, 2f, 2f), Vector4f(1f, 1f, 1f, 1f))
            // Bright light on near side
            .addLight(Vector3f(3f, 2f, 0f), Vector4f(1f, 1f, 1f, 1f), 12f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "regression_no_light_bleed")
        PixelSampler(pixelData).assertShadowed(100, 256, 40, 40, maxBrightness = 40f)
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T036: No cap geometry artifacts visible to camera.
     */
    @Test
    fun noCapsVisibleAsArtifacts() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 3f, 8f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Occluder positioned so caps might be visible
            .addOccluderBox(Vector3f(0f, 0.5f, 0f), Vector3f(2f, 2f, 0.5f), Vector4f(1f, 0f, 0f, 1f))
            .addLight(Vector3f(0f, 4f, -5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "regression_no_cap_artifacts")
        val litArea = PixelSampler(pixelData).sampleRegion(256, 350, 50, 20)
        val variance = litArea.maxBrightness - litArea.minBrightness
        assertTrue(variance < 100f,
            "Possible cap artifacts: brightness variance $variance in supposedly uniform area")
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T037: Stencil buffer no overflow with 10+ occluders.
     */
    @Test
    fun stencilBufferNoOverflow() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 15f, 15f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 30f, Vector4f(0.75f, 0.75f, 0.75f, 1f))

        // Add 12 occluder boxes scattered around
        for (i in 0 until 12) {
            val angle = (i * 30f) * (Math.PI / 180f)
            val x = (Math.cos(angle) * 4f).toFloat()
            val z = (Math.sin(angle) * 4f).toFloat()
            builder.addOccluderBox(Vector3f(x, 0.5f, z), Vector3f(0.8f, 1.5f, 0.8f), Vector4f(0.5f, 0.5f, 0.5f, 1f))
        }

        builder.addLight(Vector3f(0f, 5f, 0f), Vector4f(1f, 1f, 1f, 1f), 10f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "regression_stencil_no_overflow")
        PixelSampler(pixelData).assertLit(256, 200, 20, 20, minBrightness = 15f)
        pixelData.dispose(); harness.disposeScene(scene)
    }
}

