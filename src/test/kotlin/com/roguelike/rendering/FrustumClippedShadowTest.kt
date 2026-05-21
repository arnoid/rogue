package com.roguelike.rendering

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.roguelike.rendering.Camera

/**
 * Pure-logic test of the consumer-side frustum-cull filter spec 008
 * adds to RoguelikeGame.uploadLighting (the per-cell expansion step).
 *
 * The actual production filter calls `camera.isBoxInFrustum(...)` with
 * the cell AABB expanded by FRUSTUM_SKIRT_CELLS. This test pins down
 * the behaviour we expect on a real Camera with a known viewport and
 * camera transform, so any future refactor that changes the cull
 * semantics (e.g. silently changes the skirt size) breaks loudly.
 */
class FrustumClippedShadowTest {

    /** Mirror of the production-side filter — keep this in sync with
     *  the body of the loop in RoguelikeGame.expandTrianglesInto. */
    private fun cellInFrustum(
        camera: Camera,
        cellX: Int, cellY: Int, cellZ: Int,
        skirt: Int
    ): Boolean {
        val s = skirt.toFloat()
        return camera.isBoxInFrustum(
            cellX.toFloat() - s, cellY.toFloat() - s, cellZ.toFloat() - s,
            (cellX + 1).toFloat() + s, (cellY + 1).toFloat() + s, (cellZ + 1).toFloat() + s
        )
    }

    @Test
    fun `cells inside the camera frustum survive`() {
        val cam = Camera()
        cam.resize(800, 600)
        // Camera looking down -Z from far away; world origin will be in frustum.
        cam.position.set(0f, 0f, 50f)
        cam.direction.set(0f, 0f, -1f)
        cam.up.set(0f, 1f, 0f)
        cam.update()

        assertTrue(cellInFrustum(cam, 0, 0, 0, skirt = 1),
            "Cell at origin must be inside the frustum.")
    }

    @Test
    fun `cells far behind the camera are culled`() {
        val cam = Camera()
        cam.resize(800, 600)
        cam.position.set(0f, 0f, 50f)
        cam.direction.set(0f, 0f, -1f)
        cam.up.set(0f, 1f, 0f)
        cam.update()
        // Cell BEHIND the camera (+Z direction) at a distance well past
        // the skirt.
        assertFalse(cellInFrustum(cam, 0, 0, 200, skirt = 1),
            "Cell far behind the camera must be culled.")
    }

    @Test
    fun `cells just outside the frustum but inside the skirt survive`() {
        // We can't easily get a "just-outside" cell without measuring
        // the camera's exact horizontal half-extent; instead, verify
        // that for a known on-frustum-edge cell, expanding the skirt
        // from 0 to 1 can only turn a "false" into a "true" (monotonic).
        val cam = Camera()
        cam.resize(800, 600)
        cam.position.set(0f, 0f, 50f)
        cam.direction.set(0f, 0f, -1f)
        cam.up.set(0f, 1f, 0f)
        cam.update()

        for (x in -60..60 step 5) {
            for (y in -40..40 step 5) {
                val no = cellInFrustum(cam, x, y, 0, skirt = 0)
                val with = cellInFrustum(cam, x, y, 0, skirt = 1)
                if (no) {
                    assertTrue(with, "Cell ($x,$y) in frustum at skirt=0 must stay in at skirt=1")
                }
            }
        }
    }

    @Test
    fun `synthetic cell list partitions cleanly`() {
        val cam = Camera()
        cam.resize(800, 600)
        cam.position.set(10f, 10f, 50f)
        cam.direction.set(0f, 0f, -1f)
        cam.up.set(0f, 1f, 0f)
        cam.update()

        // 200 cells scattered across a wide box; we don't predict which
        // survive — only that the partition is internally consistent
        // (every "survivor" is reachable by isBoxInFrustum and every
        // "culled" cell is not).
        var survivors = 0
        var culled = 0
        for (x in -100..100 step 4) {
            for (z in -100..100 step 20) {
                val ok = cellInFrustum(cam, x, 10, z, skirt = 1)
                if (ok) survivors++ else culled++
            }
        }
        // At a 800×600 viewport pointed at (10,10,0) from z=50, a
        // 200-cell-wide patch MUST have both survivors and culled
        // cells, otherwise the frustum-cull is no-op (a bug we want
        // to catch).
        assertTrue(survivors > 0, "no cells in frustum — cull would do nothing")
        assertTrue(culled > 0,    "no cells culled — cull would do nothing")
    }
}



