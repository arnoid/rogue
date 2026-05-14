package com.roguelike.rendering

import org.joml.Vector3f

/**
 * Caches computed silhouette and extruded geometry for a static mesh/light pair.
 * Invalidated when light or mesh moves.
 */
class SilhouetteCache(
    val meshId: Int,
    val lightId: Int,
    val lightPos: Vector3f,
    val edges: List<SilhouetteEdge>,
    val shadowVolume: ShadowVolumeMesh,
    var valid: Boolean = true
) {
    /** Check if cache is still valid for the given light position. */
    fun isValidFor(currentLightPos: Vector3f): Boolean =
        valid && lightPos.distance(currentLightPos) < 0.001f
}
