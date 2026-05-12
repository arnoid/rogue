package com.roguelike.rendering

import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.systems.ModelOcclusionProvider
import kotlin.math.abs

/**
 * Implements [ModelOcclusionProvider] using a flat list of world-space AABBs.
 * Rebuilt via [rebuild] once per world load (static geometry) or once per
 * frame (if doors open/close or props move).
 *
 * Uses a parametric slab test for segment-vs-AABB intersection so only the
 * actual segment (t ∈ [0,1]) is tested — not an infinite ray.
 * Endpoint transparency: if origin or target is inside a box, the endpoints
 * are NOT treated as occluders (the slab test only fires for interior crossings).
 */
class BvhOccluder : ModelOcclusionProvider {

    private var boxes: List<BoundingBox> = emptyList()

    /**
     * Manhattan-distance culling radius (default 100 world nodes).
     * Boxes where |ox−cx|+|oy−cy| > cullingRadius are skipped entirely.
     * Effective per-light bound is min(light.range, cullingRadius) because
     * rays never reach beyond light.range. Set to Float.MAX_VALUE to disable.
     */
    var cullingRadius: Float = 100f

    fun rebuild(newBoxes: List<BoundingBox>) {
        boxes = newBoxes
    }

    override fun isOccluded(
        ox: Float, oy: Float, oz: Float,
        tx: Float, ty: Float, tz: Float
    ): Boolean {
        val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
        val r = cullingRadius
        for (box in boxes) {
            val cx = (box.min.x + box.max.x) * 0.5f
            val cy = (box.min.y + box.max.y) * 0.5f
            if (abs(ox - cx) + abs(oy - cy) > r) continue
            if (segmentHitsBox(ox, oy, oz, dx, dy, dz, box)) return true
        }
        return false
    }

    private fun segmentHitsBox(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        box: BoundingBox
    ): Boolean {
        // Parametric slab test: find t ∈ [0,1] where the segment is inside all 6 slabs.
        // tMin starts at 0 (segment start) and tMax at 1 (segment end).
        // For each axis, narrow the overlap interval.
        var tMin = 0f; var tMax = 1f

        // X axis
        if (abs(dx) < 1e-7f) {
            if (ox <= box.min.x || ox >= box.max.x) return false
        } else {
            val t1 = (box.min.x - ox) / dx; val t2 = (box.max.x - ox) / dx
            tMin = maxOf(tMin, minOf(t1, t2)); tMax = minOf(tMax, maxOf(t1, t2))
            if (tMin >= tMax) return false
        }

        // Y axis
        if (abs(dy) < 1e-7f) {
            if (oy <= box.min.y || oy >= box.max.y) return false
        } else {
            val t1 = (box.min.y - oy) / dy; val t2 = (box.max.y - oy) / dy
            tMin = maxOf(tMin, minOf(t1, t2)); tMax = minOf(tMax, maxOf(t1, t2))
            if (tMin >= tMax) return false
        }

        // Z axis
        if (abs(dz) < 1e-7f) {
            if (oz <= box.min.z || oz >= box.max.z) return false
        } else {
            val t1 = (box.min.z - oz) / dz; val t2 = (box.max.z - oz) / dz
            tMin = maxOf(tMin, minOf(t1, t2)); tMax = minOf(tMax, maxOf(t1, t2))
            if (tMin >= tMax) return false
        }

        return true
    }
}
