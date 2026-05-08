package com.roguelike.systems

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.roguelike.core.math.Vec3

/** Translates raw keyboard state into abstract movement/action intents. */
class InputHandler {

    /** Returns a direction vector based on WASD keys. No LibGDX Camera needed. */
    fun getMovementDirection(): Vec3 {
        val dir = Vec3()
        if (Gdx.input.isKeyPressed(Input.Keys.W)) dir.y += 1f
        if (Gdx.input.isKeyPressed(Input.Keys.S)) dir.y -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.A)) dir.x -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.D)) dir.x += 1f
        return dir
    }

    fun isInteractionJustPressed(): Boolean = Gdx.input.isKeyJustPressed(Input.Keys.F)
    fun isDebugToggleJustPressed(): Boolean = Gdx.input.isKeyJustPressed(Input.Keys.F3)

    fun getCameraYawChange(delta: Float): Float {
        var change = 0f
        if (Gdx.input.isKeyPressed(Input.Keys.Q)) change -= 90f * delta
        if (Gdx.input.isKeyPressed(Input.Keys.E)) change += 90f * delta
        return change
    }
}
