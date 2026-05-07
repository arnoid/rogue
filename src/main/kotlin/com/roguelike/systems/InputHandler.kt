package com.roguelike.systems

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.math.Vector3

class InputHandler {
    fun getMovementDirection(camera: Camera): Vector3 {
        val moveDir = Vector3(0f, 0f, 0f)
        if (Gdx.input.isKeyPressed(Input.Keys.W)) moveDir.y += 1f
        if (Gdx.input.isKeyPressed(Input.Keys.S)) moveDir.y -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.A)) moveDir.x -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.D)) moveDir.x += 1f
        
        return moveDir
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
