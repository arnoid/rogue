package com.roguelike.rendering

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * JOML-based camera with Vulkan clip space (Y-flip).
 * Replaces libGDX PerspectiveCamera.
 */
class Camera(
    var fov: Float = 67f,
    var aspectRatio: Float = 16f / 9f,
    var near: Float = 0.1f,
    var far: Float = 1000f
) {
    val position = Vector3f(0f, 0f, 0f)
    val direction = Vector3f(0f, 0f, -1f)
    val up = Vector3f(0f, 1f, 0f)

    val viewMatrix = Matrix4f()
    val projectionMatrix = Matrix4f()
    val viewProjection = Matrix4f()

    // Frustum planes for culling (simplified)
    private val tmpVec = Vector3f()

    fun update() {
        // View matrix: lookAt(position, position+direction, up)
        tmpVec.set(position).add(direction)
        viewMatrix.setLookAt(position, tmpVec, up)

        // Projection matrix with Vulkan Y-flip
        projectionMatrix.setPerspective(
            Math.toRadians(fov.toDouble()).toFloat(),
            aspectRatio,
            near,
            far
        )
        // Vulkan clip space: Y is inverted compared to OpenGL
        projectionMatrix.m11(projectionMatrix.m11() * -1f)

        // Combined VP
        projectionMatrix.mul(viewMatrix, viewProjection)
    }

    fun resize(width: Int, height: Int) {
        if (height > 0) {
            aspectRatio = width.toFloat() / height.toFloat()
            update()
        }
    }

    /**
     * Project world position to screen coordinates.
     */
    fun project(worldPos: Vector3f, viewportWidth: Float, viewportHeight: Float): Vector3f {
        val clipPos = Vector4f(worldPos.x, worldPos.y, worldPos.z, 1f)
        clipPos.mul(viewProjection)

        if (clipPos.w == 0f) return Vector3f(0f, 0f, -1f)

        val ndcX = clipPos.x / clipPos.w
        val ndcY = clipPos.y / clipPos.w
        val ndcZ = clipPos.z / clipPos.w

        return Vector3f(
            (ndcX * 0.5f + 0.5f) * viewportWidth,
            (ndcY * 0.5f + 0.5f) * viewportHeight,
            ndcZ * 0.5f + 0.5f
        )
    }

    /**
     * Unproject screen coordinates to world ray.
     */
    fun unproject(screenPos: Vector3f, viewportWidth: Float, viewportHeight: Float): Vector3f {
        val ndcX = screenPos.x / viewportWidth * 2f - 1f
        val ndcY = screenPos.y / viewportHeight * 2f - 1f
        val ndcZ = screenPos.z * 2f - 1f

        val invVP = Matrix4f(viewProjection).invert()
        val worldPos = Vector4f(ndcX, ndcY, ndcZ, 1f)
        worldPos.mul(invVP)

        if (worldPos.w == 0f) return Vector3f()
        return Vector3f(worldPos.x / worldPos.w, worldPos.y / worldPos.w, worldPos.z / worldPos.w)
    }

    /**
     * Simple frustum check (bounding box vs frustum planes).
     * Returns true if the box might be visible.
     */
    fun isBoxInFrustum(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        // Simplified check using the combined VP matrix
        // For a full implementation, extract frustum planes and test
        // For now, always return true (no frustum culling)
        return true
    }
}

