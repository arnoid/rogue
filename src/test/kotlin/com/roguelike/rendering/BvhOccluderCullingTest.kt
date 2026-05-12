package com.roguelike.rendering

import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies FR-011: BvhOccluder.cullingRadius limits box evaluation to
 * Manhattan distance min(light.range, cullingRadius) from the ray origin.
 */
class BvhOccluderCullingTest {

    // Box centered at (10, 0, 0) — would block ray (0,0,0)→(20,0,0)
    // Manhattan dist from origin (0,0) to center (10,0) = 10
    private val blockingBoxAtDist10 = BoundingBox(
        Vector3(9.5f, -0.5f, -0.5f),
        Vector3(10.5f, 0.5f, 0.5f)
    )

    @Test
    fun `default cullingRadius is 100`() {
        val occluder = BvhOccluder()
        assertEquals(100f, occluder.cullingRadius)
    }

    @Test
    fun `box within cullingRadius blocks the ray`() {
        val occluder = BvhOccluder()
        occluder.rebuild(listOf(blockingBoxAtDist10))
        occluder.cullingRadius = 15f  // dist 10 <= 15 → box tested
        assertTrue(occluder.isOccluded(0f, 0f, 0f, 20f, 0f, 0f))
    }

    @Test
    fun `box beyond cullingRadius is culled and does not block`() {
        val occluder = BvhOccluder()
        occluder.rebuild(listOf(blockingBoxAtDist10))
        occluder.cullingRadius = 5f   // dist 10 > 5 → box culled
        assertFalse(occluder.isOccluded(0f, 0f, 0f, 20f, 0f, 0f))
    }

    @Test
    fun `default cullingRadius of 100 includes box at dist 10`() {
        val occluder = BvhOccluder()
        occluder.rebuild(listOf(blockingBoxAtDist10))
        // cullingRadius=100 (default) → dist 10 is well within → box tested → blocked
        assertTrue(occluder.isOccluded(0f, 0f, 0f, 20f, 0f, 0f))
    }

    @Test
    fun `cullingRadius uses Manhattan distance (diagonal box is culled by tight radius)`() {
        // Box centered at (4, 4, 0) — Manhattan dist from origin = |4|+|4| = 8
        val diagonalBox = BoundingBox(
            Vector3(3.5f, 3.5f, -0.5f),
            Vector3(4.5f, 4.5f, 0.5f)
        )
        val occluder = BvhOccluder()
        occluder.rebuild(listOf(diagonalBox))

        // cullingRadius=7 → Manhattan dist 8 > 7 → culled
        occluder.cullingRadius = 7f
        // Ray from (0,0,0) to (6,6,0) would pass through box center (4,4,0)
        assertFalse(occluder.isOccluded(0f, 0f, 0f, 6f, 6f, 0f))

        // cullingRadius=9 → Manhattan dist 8 ≤ 9 → tested and blocks
        occluder.cullingRadius = 9f
        assertTrue(occluder.isOccluded(0f, 0f, 0f, 6f, 6f, 0f))
    }

    @Test
    fun `empty box list always returns false regardless of cullingRadius`() {
        val occluder = BvhOccluder()
        occluder.cullingRadius = 1000f
        assertFalse(occluder.isOccluded(0f, 0f, 0f, 20f, 0f, 0f))
    }

    @Test
    fun `Float_MAX_VALUE cullingRadius disables culling effectively`() {
        val occluder = BvhOccluder()
        occluder.rebuild(listOf(blockingBoxAtDist10))
        occluder.cullingRadius = Float.MAX_VALUE
        assertTrue(occluder.isOccluded(0f, 0f, 0f, 20f, 0f, 0f))
    }
}
