package com.roguelike.core.model

import com.roguelike.core.math.Vec3

/**
 * Base class for all moving entities.
 * No LibGDX dependency — visual representation is managed by the view layer.
 */
abstract class Actor {
    val position = Vec3()
    var collisionSize: Float = 0.3f
    val inventory = mutableListOf<Item>()

    open fun update(delta: Float) {}
}
