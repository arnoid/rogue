package com.roguelike.systems

import com.roguelike.core.math.Vec3
import com.roguelike.rendering.Camera

class CameraManager(
    val camera: Camera,
    var cameraPitch: Float = 0f,
    var cameraYaw: Float = 0f,
    var cameraDistance: Float = 20f,
    private val minDistance: Float = 5f,
    private val maxDistance: Float = 50f
) {
    /** Orbit the camera over the target position. Accepts core Vec3. */
    fun update(target: Vec3) {
        camera.position.set(target.x, target.y, target.z + cameraDistance)
        camera.up.set(0f, 1f, 0f)
        camera.direction.set(target.x - camera.position.x, target.y - camera.position.y, target.z - camera.position.z).normalize()
        camera.update()
    }

    /** Adjust zoom distance, clamped to [minDistance, maxDistance]. */
    fun zoom(amount: Float) {
        cameraDistance = (cameraDistance + amount).coerceIn(minDistance, maxDistance)
    }
}
