package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * User Story 4: Multi-Light Interaction Tests (P2)
 * 3 tests verifying correct multi-light additive blending and shadow overlap.
 */
class MultiLightInteractionTest : GLTestBase() {

    /**
     * T025: Two lights on opposite sides of occluder.
     * Each side lit, overlap region intermediate brightness.
     */
    @Test
    fun twoLightsOppositeSidesOccluder() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 10f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            .addOccluderBox(Vector3f(0f, 0.5f, 0f), Vector3f(1f, 2f, 1f), Vector4f(0.5f, 0.5f, 0.5f, 1f))
            // Light on left
            .addLight(Vector3f(-6f, 3f, 0f), Vector4f(1f, 1f, 1f, 1f), 6f, 25f)
            // Light on right
            .addLight(Vector3f(6f, 3f, 0f), Vector4f(1f, 1f, 1f, 1f), 6f, 25f)

        val (pixelData, scene) = harness.renderAndSave(builder, "multi_two_lights_opposite")
        val sampler = PixelSampler(pixelData)
        // Left and right sides of the floor should be lit
        val left = sampler.sampleRegion(100, 300, 30, 30)
        val right = sampler.sampleRegion(400, 300, 30, 30)
        // Both should have some illumination
        assertTrue(left.avgBrightness > 15f, "Left side should be lit: ${left.avgBrightness}")
        assertTrue(right.avgBrightness > 15f, "Right side should be lit: ${right.avgBrightness}")

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T026: Red + blue lights — overlap shows purple/magenta.
     */
    @Test
    fun coloredLightsBlending() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 8f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(1f, 1f, 1f, 1f))
            // Red light from left
            .addLight(Vector3f(-3f, 3f, 0f), Vector4f(1f, 0f, 0f, 1f), 8f, 25f)
            // Blue light from right
            .addLight(Vector3f(3f, 3f, 0f), Vector4f(0f, 0f, 1f, 1f), 8f, 25f)

        val (pixelData, scene) = harness.renderAndSave(builder, "multi_colored_lights_blending")
        val sampler = PixelSampler(pixelData)
        // Center overlap region should have both red and blue channels
        val center = sampler.sampleRegion(240, 300, 30, 30)
        assertTrue(center.avgR > 10f && center.avgB > 10f,
            "Center should have both R(${center.avgR}) and B(${center.avgB}) > 10")

        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T027: Two lights with overlapping radii, no occluders — additive brightness.
     */
    @Test
    fun overlappingRadiiAdditiveBrightness() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 8f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Two lights near each other
            .addLight(Vector3f(-2f, 3f, 0f), Vector4f(1f, 1f, 1f, 1f), 5f, 20f)
            .addLight(Vector3f(2f, 3f, 0f), Vector4f(1f, 1f, 1f, 1f), 5f, 20f)

        val (pixelData, scene) = harness.renderAndSave(builder, "multi_overlapping_additive")
        val sampler = PixelSampler(pixelData)
        val overlap = sampler.sampleRegion(245, 300, 20, 20)
        val singleLight = sampler.sampleRegion(100, 300, 20, 20)
        // Overlap region should be brighter than area lit by single light
        assertTrue(overlap.avgBrightness > singleLight.avgBrightness,
            "Overlap (${overlap.avgBrightness}) should be brighter than single-light area (${singleLight.avgBrightness})")

        pixelData.dispose(); harness.disposeScene(scene)
    }
}

