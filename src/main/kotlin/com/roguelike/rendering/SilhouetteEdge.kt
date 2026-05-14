package com.roguelike.rendering

import org.joml.Vector3f

/**
 * One edge of a mesh's silhouette relative to a light source.
 * An edge shared by one front-facing and one back-facing triangle.
 */
data class SilhouetteEdge(
    val v0: Vector3f,
    val v1: Vector3f
) {
    init {
        require(v0.distance(v1) > 1e-6f) { "Degenerate silhouette edge: v0 == v1" }
    }
}
