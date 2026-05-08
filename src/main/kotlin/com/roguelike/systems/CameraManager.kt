package com.roguelike.systems

import com.badlogic.gdx.graphics.PerspectiveCamera
import com.roguelike.core.math.Vec3

class CameraManager(
    val camera: PerspectiveCamera,
    var cameraPitch: Float = 0f,
    var cameraYaw: Float = 0f,
    val cameraDistance: Float = 20f
) {
    /** Orbit the camera over the target position. Accepts core Vec3. */
    fun update(target: Vec3) {
        camera.position.set(target.x, target.y, target.z + cameraDistance)
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(target.x, target.y, target.z)
        camera.update()
    }
}
