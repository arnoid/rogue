package com.roguelike.world

import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.math.Vector3

/** Base class for all moving entities in the game world. */
abstract class Actor(val modelInstance: ModelInstance? = null) {
    val position = Vector3()
    var collisionSize: Float = 0.3f
    val inventory = mutableListOf<Item>()

    init {
        modelInstance?.transform?.getTranslation(position)
    }

    /** Updates the actor's state. */
    open fun update(delta: Float) {
        modelInstance?.transform?.setTranslation(position)
    }
}
