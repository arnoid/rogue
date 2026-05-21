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
    /**
     * Half-extent of the actor's collision AABB (so the full footprint is
     * a 2·collisionSize × 2·collisionSize square centred on `position`).
     *
     * Must be > Camera.near (currently 0.05) by a safe margin: otherwise
     * the camera (== player eye in first-person) can be pressed close
     * enough to a wall that the wall geometry enters the view frustum's
     * near clip volume, producing a "see through walls when standing
     * next to them" artefact. 0.20 keeps the eye at least 0.20 units
     * from any wall surface, which leaves 4× headroom over the 0.05
     * near plane.
     */
    var collisionSize: Float = 0.20f
    val inventory = mutableListOf<Item>()


    /** True when the actor is in the CLIMBING state (on a ladder). */
    var isClimbing: Boolean = false
    /** The X,Y position the actor is locked to while climbing (ladder center axis). */
    val climbAnchor = Vec3()

    open fun update(delta: Float) {}
}
