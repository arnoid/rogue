package com.roguelike.systems

import com.roguelike.core.math.Vec3
import com.roguelike.input.InputSystem
import org.lwjgl.glfw.GLFW.*

/** Translates raw keyboard state into abstract movement/action intents. */
class InputHandler(private val inputSystem: InputSystem? = null) {

    /** Returns a direction vector based on WASD keys. No LibGDX Camera needed. */
    fun getMovementDirection(): Vec3 {
        val input = inputSystem ?: return Vec3()
        val dir = Vec3()
        if (input.isKeyPressed(GLFW_KEY_W)) dir.y += 1f
        if (input.isKeyPressed(GLFW_KEY_S)) dir.y -= 1f
        if (input.isKeyPressed(GLFW_KEY_A)) dir.x -= 1f
        if (input.isKeyPressed(GLFW_KEY_D)) dir.x += 1f
        return dir
    }

    fun isInteractionJustPressed(): Boolean = inputSystem?.isKeyJustPressed(GLFW_KEY_F) ?: false
    fun isDebugToggleJustPressed(): Boolean = inputSystem?.isKeyJustPressed(GLFW_KEY_F3) ?: false

    fun getCameraYawChange(delta: Float): Float {
        val input = inputSystem ?: return 0f
        var change = 0f
        if (input.isKeyPressed(GLFW_KEY_Q)) change -= 90f * delta
        if (input.isKeyPressed(GLFW_KEY_E)) change += 90f * delta
        return change
    }

    /** Returns zoom change: negative = zoom in, positive = zoom out. */
    fun getZoomChange(delta: Float): Float {
        val input = inputSystem ?: return 0f
        var change = 0f
        if (input.isKeyPressed(GLFW_KEY_Z)) change -= 10f * delta
        if (input.isKeyPressed(GLFW_KEY_X)) change += 10f * delta
        return change
    }
}
