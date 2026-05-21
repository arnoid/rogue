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
    // Near plane is intentionally tight (5 cm) so that wall geometry doesn't
    // clip when the player presses against a wall and looks parallel to it.
    // Worst case: the camera (== player eye) is at the wall corner; with
    // Actor.collisionSize ≥ 0.20 the wall vertices closest to the camera
    // sit ~0.20 units away along the camera's right axis. As long as
    // `near` < that distance projected onto the view direction, the wall
    // never enters the clip volume — which manifests visually as "I can see
    // through walls when I touch them" if the inequality is violated.
    // 0.05 gives ~10× depth-buffer headroom against the 1000-unit far plane
    // (well above the precision needed for cell-scale geometry).
    var near: Float = 0.05f,
    var far: Float = 1000f
) {
    val position = Vector3f(0f, 0f, 0f)
    val direction = Vector3f(0f, 0f, -1f)
    val up = Vector3f(0f, 1f, 0f)

    val viewMatrix = Matrix4f()
    val projectionMatrix = Matrix4f()
    val viewProjection = Matrix4f()

    // Frustum planes for culling — extracted from `viewProjection` each `update()`.
    // Layout: 6 planes × (a, b, c, d) where ax+by+cz+d >= 0 means "inside or on the plane".
    // Order: LEFT, RIGHT, BOTTOM, TOP, NEAR, FAR.
    private val frustumPlanes = FloatArray(6 * 4)

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

        extractFrustumPlanes()
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
     * Extract the six frustum planes from the current view-projection matrix
     * using the Gribb–Hartmann method. Planes are stored normalised so that
     * `a*x + b*y + c*z + d` gives the signed distance from the point to the
     * plane, with positive values on the inside half-space.
     */
    private fun extractFrustumPlanes() {
        val m = FloatArray(16)
        viewProjection.get(m) // column-major: m[col*4 + row]
        // Helper accessors for the row-major rows of a column-major float[16].
        // row r = (m[0*4+r], m[1*4+r], m[2*4+r], m[3*4+r])
        fun row(r: Int, c: Int) = m[c * 4 + r]

        fun setPlane(i: Int, a: Float, b: Float, c: Float, d: Float) {
            val invLen = 1f / kotlin.math.sqrt(a * a + b * b + c * c).coerceAtLeast(1e-20f)
            frustumPlanes[i * 4 + 0] = a * invLen
            frustumPlanes[i * 4 + 1] = b * invLen
            frustumPlanes[i * 4 + 2] = c * invLen
            frustumPlanes[i * 4 + 3] = d * invLen
        }

        // LEFT  = row(3) + row(0)
        setPlane(0, row(3, 0) + row(0, 0), row(3, 1) + row(0, 1), row(3, 2) + row(0, 2), row(3, 3) + row(0, 3))
        // RIGHT = row(3) - row(0)
        setPlane(1, row(3, 0) - row(0, 0), row(3, 1) - row(0, 1), row(3, 2) - row(0, 2), row(3, 3) - row(0, 3))
        // BOTTOM = row(3) + row(1)
        setPlane(2, row(3, 0) + row(1, 0), row(3, 1) + row(1, 1), row(3, 2) + row(1, 2), row(3, 3) + row(1, 3))
        // TOP   = row(3) - row(1)
        setPlane(3, row(3, 0) - row(1, 0), row(3, 1) - row(1, 1), row(3, 2) - row(1, 2), row(3, 3) - row(1, 3))
        // NEAR  = row(3) + row(2)  (Vulkan/D3D depth 0..1 → use row(2))
        setPlane(4, row(3, 0) + row(2, 0), row(3, 1) + row(2, 1), row(3, 2) + row(2, 2), row(3, 3) + row(2, 3))
        // FAR   = row(3) - row(2)
        setPlane(5, row(3, 0) - row(2, 0), row(3, 1) - row(2, 1), row(3, 2) - row(2, 2), row(3, 3) - row(2, 3))
    }

    /**
     * Returns true if any part of the axis-aligned bounding box might be
     * inside the frustum. Uses the standard p-vertex test against all six
     * planes — conservative (may keep some boxes that are actually outside
     * near a frustum edge, never culls a visible box).
     */
    fun isBoxInFrustum(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        for (i in 0 until 6) {
            val a = frustumPlanes[i * 4 + 0]
            val b = frustumPlanes[i * 4 + 1]
            val c = frustumPlanes[i * 4 + 2]
            val d = frustumPlanes[i * 4 + 3]
            // p-vertex: corner farthest in the direction of the plane normal.
            val px = if (a >= 0f) maxX else minX
            val py = if (b >= 0f) maxY else minY
            val pz = if (c >= 0f) maxZ else minZ
            if (a * px + b * py + c * pz + d < 0f) return false
        }
        return true
    }

    /**
     * Returns true if the sphere (`cx,cy,cz`, `radius`) might intersect the
     * frustum. Used by the lighting upload to skip light sources whose
     * illumination volume can't touch any on-screen geometry.
     */
    fun isSphereInFrustum(cx: Float, cy: Float, cz: Float, radius: Float): Boolean {
        for (i in 0 until 6) {
            val a = frustumPlanes[i * 4 + 0]
            val b = frustumPlanes[i * 4 + 1]
            val c = frustumPlanes[i * 4 + 2]
            val d = frustumPlanes[i * 4 + 3]
            if (a * cx + b * cy + c * cz + d < -radius) return false
        }
        return true
    }
}

