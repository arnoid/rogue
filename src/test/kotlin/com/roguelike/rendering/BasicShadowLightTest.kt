package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Test

/** Common color constants for tests (replacing libGDX Color.*) */
private val WHITE = Vector4f(1f, 1f, 1f, 1f)
private val GRAY = Vector4f(0.5f, 0.5f, 0.5f, 1f)
private val LIGHT_GRAY = Vector4f(0.75f, 0.75f, 0.75f, 1f)
private val RED = Vector4f(1f, 0f, 0f, 1f)
private val Y = Vector3f(0f, 1f, 0f)
private val X = Vector3f(1f, 0f, 0f)
private val Z = Vector3f(0f, 0f, 1f)

/**
 * User Story 1: Basic Shadow/Light Verification Tests (P1 MVP)
 */
class BasicShadowLightTest : GLTestBase() {

    @Test
    fun spherePartialShadowOnCube() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 12f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Y, 20f, GRAY)
            .addBox(Vector3f(0f, 0f, -4f), Vector3f(3f, 3f, 3f), LIGHT_GRAY)
            .addOccluderBox(Vector3f(0f, 0.5f, 0f), Vector3f(1f, 1f, 1f), RED)
            .addLight(Vector3f(0f, 3f, 6f), WHITE, 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "basic_sphere_partial_shadow_on_cube")
        val sampler = PixelSampler(pixelData)
        sampler.assertBrighterThan(350, 256, 30, 30, 256, 256, 30, 30, 1.3f)
        pixelData.dispose()
        harness.disposeScene(scene)
    }

    @Test
    fun wallBlocksAllLight() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(8f, 5f, 0f), Vector3f(0f, 0f, 0f))
            .addBox(Vector3f(-3f, 0f, 0f), Vector3f(2f, 2f, 2f), LIGHT_GRAY)
            .addWall(Vector3f(0f, 0f, 0f), 6f, 6f, GRAY, 0.2f, X)
            .addLight(Vector3f(4f, 2f, 0f), WHITE, 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "basic_wall_full_occlusion")
        val sampler = PixelSampler(pixelData)
        sampler.assertShadowed(100, 230, 40, 40, maxBrightness = 35f)
        pixelData.dispose()
        harness.disposeScene(scene)
    }

    @Test
    fun wallLitCubeShadowed() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 6f, 12f), Vector3f(0f, 0f, -2f))
            .addBox(Vector3f(0f, 0f, -5f), Vector3f(2f, 2f, 2f), LIGHT_GRAY)
            .addWall(Vector3f(0f, 1f, -2f), 8f, 4f, WHITE, 0.2f, Z)
            .addLight(Vector3f(0f, 2f, 3f), WHITE, 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "basic_wall_front_lit_back_shadow")
        val sampler = PixelSampler(pixelData)
        sampler.assertLit(256, 200, 40, 40, minBrightness = 30f)
        pixelData.dispose()
        harness.disposeScene(scene)
    }

    @Test
    fun noOccludersFullyLit() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Y, 15f, LIGHT_GRAY)
            .addBox(Vector3f(-3f, 0f, 0f), Vector3f(2f, 2f, 2f), LIGHT_GRAY)
            .addBox(Vector3f(3f, 0f, 0f), Vector3f(2f, 2f, 2f), LIGHT_GRAY)
            .addLight(Vector3f(0f, 5f, 0f), WHITE, 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "basic_no_occluder_full_illumination")
        val sampler = PixelSampler(pixelData)
        val left = sampler.sampleRegion(150, 256, 30, 30)
        val right = sampler.sampleRegion(360, 256, 30, 30)
        val diff = kotlin.math.abs(left.avgBrightness - right.avgBrightness)
        org.junit.jupiter.api.Assertions.assertTrue(diff < 30f,
            "Expected similar brightness left(${left.avgBrightness}) vs right(${right.avgBrightness}), diff=$diff")
        pixelData.dispose()
        harness.disposeScene(scene)
    }

    @Test
    fun zeroIntensityLightAmbientOnly() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 5f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Y, 10f, LIGHT_GRAY)
            .addBox(Vector3f(0f, 0.5f, 0f), Vector3f(2f, 2f, 2f), WHITE)
            .addLight(Vector3f(3f, 3f, 3f), WHITE, 0f, 20f)

        val (pixelData, scene) = harness.renderAndSave(builder, "basic_zero_intensity_ambient_only")
        val sampler = PixelSampler(pixelData)
        sampler.assertShadowed(256, 256, 40, 40, maxBrightness = 40f)
        sampler.assertShadowed(100, 300, 40, 40, maxBrightness = 40f)
        pixelData.dispose()
        harness.disposeScene(scene)
    }
}
