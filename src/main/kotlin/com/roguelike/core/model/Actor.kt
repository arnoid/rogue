package com.roguelike.core.model

import com.roguelike.core.math.Vec3

/**
 * Base class for all moving entities.
 * No LibGDX dependency — visual representation is managed by the view layer.
 */
abstract class Actor {
    val position = Vec3()
    /** The direction the actor is facing (updated on movement). Defaults to +Y (north). */
    val facingDirection = Vec3(0f, 1f, 0f)
    var collisionSize: Float = 0.3f
    val inventory = mutableListOf<Item>()

    open fun update(delta: Float) {}
}
