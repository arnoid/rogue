package com.roguelike.systems

import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.math.Vector3

class CameraManager(
    val camera: PerspectiveCamera,
    var cameraPitch: Float = 0f,
    var cameraYaw: Float = 0f,
    val cameraDistance: Float = 20f
) {
    fun update(target: Vector3) {
        // Camera at Z+, looking at target at Z=0
        camera.position.set(target.x, target.y, target.z + cameraDistance)
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(target)
        camera.update()
    }
}


