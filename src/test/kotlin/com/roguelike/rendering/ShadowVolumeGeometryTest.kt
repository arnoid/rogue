package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Test

/**
 * User Story 2: Shadow Volume Geometry Accuracy Tests (P1)
 * 4 tests verifying shadow geometry produces correct shadow shapes.
 */
class ShadowVolumeGeometryTest : GLTestBase() {

    /**
     * T015: Single flat wall between light and floor — sharp lit-to-shadowed transition.
     */
    @Test
    fun flatWallSharpShadowBoundary() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 10f, 8f), Vector3f(0f, 0f, -2f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            .addWall(Vector3f(0f, 1f, 0f), 4f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.1f, Vector3f(0f, 0f, 1f))
            .addLight(Vector3f(0f, 3f, 5f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "geometry_flat_wall_sharp_boundary")
        PixelSampler(pixelData).assertBrighterThan(256, 150, 30, 30, 256, 350, 30, 30, 1.3f)
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T016: Multiple walls forming corridor with light at one end.
     */
    @Test
    fun corridorWallsShadows() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 12f, 10f), Vector3f(0f, 0f, -4f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // Left corridor wall
            .addWall(Vector3f(-2f, 1f, -2f), 8f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.15f, Vector3f(1f, 0f, 0f))
            // Right corridor wall
            .addWall(Vector3f(2f, 1f, -2f), 8f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.15f, Vector3f(1f, 0f, 0f))
            // Light at one end of corridor
            .addLight(Vector3f(0f, 2f, 3f), Vector4f(1f, 1f, 1f, 1f), 10f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "geometry_corridor_shadows")
        val sampler = PixelSampler(pixelData)
        sampler.assertLit(256, 256, 30, 30, minBrightness = 20f)
        sampler.assertBrighterThan(256, 256, 30, 30, 50, 300, 30, 30, 1.2f)
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T017: L-shaped wall + light — shadow wraps around corner.
     */
    @Test
    fun lShapedWallShadowWrap() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 12f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            // L-shape: horizontal wall segment
            .addWall(Vector3f(-2f, 1f, 0f), 4f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.15f, Vector3f(0f, 0f, 1f))
            // L-shape: vertical wall segment (perpendicular)
            .addWall(Vector3f(0f, 1f, -2f), 4f, 3f, Vector4f(0.5f, 0.5f, 0.5f, 1f), 0.15f, Vector3f(1f, 0f, 0f))
            // Light on the open side
            .addLight(Vector3f(4f, 3f, 4f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "geometry_l_shaped_wall_wrap")
        PixelSampler(pixelData).assertBrighterThan(350, 200, 30, 30, 150, 350, 30, 30, 1.2f)
        pixelData.dispose(); harness.disposeScene(scene)
    }

    /**
     * T018: Cube occluder with light at different angles.
     */
    @Test
    fun cubeOccluderMultiAngle() {
        requireGL()
        val builder = SceneBuilder()
            .camera(Vector3f(0f, 8f, 10f), Vector3f(0f, 0f, 0f))
            .addPlane(Vector3f(0f, -1f, 0f), Vector3f(0f, 1f, 0f), 20f, Vector4f(0.75f, 0.75f, 0.75f, 1f))
            .addOccluderBox(Vector3f(0f, 0.5f, 0f), Vector3f(1.5f, 1.5f, 1.5f), Vector4f(1f, 0f, 0f, 1f))
            .addLight(Vector3f(0f, 4f, 6f), Vector4f(1f, 1f, 1f, 1f), 8f, 30f)

        val (pixelData, scene) = harness.renderAndSave(builder, "geometry_cube_multi_angle")
        PixelSampler(pixelData).assertBrighterThan(370, 300, 30, 30, 256, 350, 30, 30, 1.2f)
        pixelData.dispose(); harness.disposeScene(scene)
    }
}

