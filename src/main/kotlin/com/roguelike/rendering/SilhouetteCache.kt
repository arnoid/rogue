package com.roguelike.rendering

import com.badlogic.gdx.math.Vector3

/**
 * Caches computed silhouette and extruded geometry for a static mesh/light pair.
 * Invalidated when light or mesh moves.
 */
class SilhouetteCache(
    val meshId: Int,
    val lightId: Int,
    val lightPos: Vector3,
    val edges: List<SilhouetteEdge>,
    val shadowVolume: ShadowVolumeMesh,
    var valid: Boolean = true
) {
    /** Check if cache is still valid for the given light position. */
    fun isValidFor(currentLightPos: Vector3): Boolean =
        valid && lightPos.dst(currentLightPos) < 0.001f
}

