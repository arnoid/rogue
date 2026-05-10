package com.roguelike.systems

import com.badlogic.gdx.math.Quaternion
import com.roguelike.world.DoorTile

/**
 * Manages smooth door swing animations using quaternion slerp interpolation.
 *
 * Each tracked [DoorTile] has a current rotation quaternion that is interpolated
 * toward the target (open or closed) orientation every frame. The interpolation
 * is frame-rate independent via `delta` time.
 */
class DoorAnimationSystem(
    /** Degrees the door swings when opening (typically 90). */
    private val swingAngleDeg: Float = 90f,
    /** Speed multiplier for the slerp interpolation. Higher = faster swing. */
    private val swingSpeed: Float = 4f
) {
    /**
     * Per-door animation state.
     * [current] is continuously slerped toward [target] each frame.
     */
    private data class DoorAnim(
        val current: Quaternion = Quaternion(),
        val target: Quaternion = Quaternion(),
        var progress: Float = 1f // 1 = animation complete
    )

    private val closedQuat = Quaternion()  // identity – no extra rotation
    private val openQuat   = Quaternion().setFromAxis(0f, 0f, 1f, -swingAngleDeg)

    private val anims = mutableMapOf<DoorTile, DoorAnim>()

    private fun quatEquals(a: Quaternion, b: Quaternion): Boolean {
        val eps = 0.001f
        return Math.abs(a.x - b.x) < eps && Math.abs(a.y - b.y) < eps &&
               Math.abs(a.z - b.z) < eps && Math.abs(a.w - b.w) < eps
    }

    /** Ensure a door is tracked. Call when doors are first created / loaded. */
    fun register(door: DoorTile) {
        val initial = if (door.isOpen) Quaternion(openQuat) else Quaternion(closedQuat)
        anims[door] = DoorAnim(current = Quaternion(initial), target = Quaternion(initial), progress = 1f)
    }

    /** Remove tracking for a door (e.g. when unloading a level). */
    fun unregister(door: DoorTile) { anims.remove(door) }

    fun clear() { anims.clear() }

    /**
     * Called every frame. Advances all door animations toward their targets.
     * Automatically detects when a door's [DoorTile.isOpen] has changed and
     * sets a new target quaternion.
     */
    fun update(delta: Float) {
        for ((door, anim) in anims) {
            val desiredTarget = if (door.isOpen) openQuat else closedQuat

            // Detect state change → start new animation
            if (!quatEquals(anim.target, desiredTarget)) {
                anim.target.set(desiredTarget)
                anim.progress = 0f
            }

            // Advance slerp
            if (anim.progress < 1f) {
                anim.progress = (anim.progress + delta * swingSpeed).coerceAtMost(1f)
                anim.current.slerp(anim.target, (delta * swingSpeed).coerceAtMost(1f))

                // Snap when close enough
                if (anim.progress >= 1f) {
                    anim.current.set(anim.target)
                }
            }
        }
    }

    /**
     * Returns the current interpolated rotation quaternion for the given door,
     * or `null` if the door is not tracked.
     */
    fun getCurrentRotation(door: DoorTile): Quaternion? = anims[door]?.current

    /**
     * Returns true if the door's animation is still in progress (not yet reached target).
     */
    fun isAnimating(door: DoorTile): Boolean = anims[door]?.let { it.progress < 1f } ?: false
}



