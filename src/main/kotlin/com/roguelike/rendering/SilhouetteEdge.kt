package com.roguelike.rendering

import com.badlogic.gdx.math.Vector3

/**
 * One edge of a mesh's silhouette relative to a light source.
 * An edge shared by one front-facing and one back-facing triangle.
 */
data class SilhouetteEdge(
    val v0: Vector3,
    val v1: Vector3
) {
    init {
        require(v0.dst(v1) > 1e-6f) { "Degenerate silhouette edge: v0 == v1" }
    }
}

