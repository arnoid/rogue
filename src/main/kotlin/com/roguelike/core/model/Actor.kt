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
    var collisionSize: Float = 0.15f
    val inventory = mutableListOf<Item>()

    /** True when the actor is in the CLIMBING state (on a ladder). */
    var isClimbing: Boolean = false
    /** The X,Y position the actor is locked to while climbing (ladder center axis). */
    val climbAnchor = Vec3()

    open fun update(delta: Float) {}
}
