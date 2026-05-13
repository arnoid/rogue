package com.roguelike.rendering

/**
 * The extruded shadow volume geometry for one occluder mesh from one light.
 * Position-only vertices (x,y,z per vertex), indexed triangle list.
 */
data class ShadowVolumeMesh(
    val vertices: FloatArray,
    val indices: ShortArray,
    val vertexCount: Int,
    val indexCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowVolumeMesh) return false
        return vertices.contentEquals(other.vertices) &&
               indices.contentEquals(other.indices) &&
               vertexCount == other.vertexCount &&
               indexCount == other.indexCount
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + vertexCount
        result = 31 * result + indexCount
        return result
    }
}

