package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*

/**
 * Moves actors through the world, enforcing box-based collision.
 * No LibGDX dependency.
 */
class MovementSystem(private val world: World) {

    fun move(actor: Actor, moveDir: Vec3, delta: Float, speed: Float) {
        if (moveDir.isZero) return

        val norDir = Vec3(moveDir).nor()
        val scaledMove = Vec3(norDir).scl(delta * speed)

        // Update facing direction when the player moves
        actor.facingDirection.set(norDir.x, norDir.y, 0f)

        val nextX = actor.position.x + scaledMove.x
        val nextY = actor.position.y + scaledMove.y

        if (canMove(nextX, nextY, actor.position.z, actor.collisionSize)) {
            actor.position.x = nextX
            actor.position.y = nextY
        } else {
            if (canMove(nextX, actor.position.y, actor.position.z, actor.collisionSize)) {
                actor.position.x = nextX
            } else if (canMove(actor.position.x, nextY, actor.position.z, actor.collisionSize)) {
                actor.position.y = nextY
            }
        }
    }

    private fun canMove(targetX: Float, targetY: Float, targetZ: Float, size: Float): Boolean {
        val corners = arrayOf(
            targetX - size to targetY - size,
            targetX + size to targetY - size,
            targetX - size to targetY + size,
            targetX + size to targetY + size
        )
        for (corner in corners) {
            val cx = Math.round(corner.first)
            val cy = Math.round(corner.second)
            val cz = Math.round(targetZ)

            if (cx < 0 || cx >= world.width || cy < 0 || cy >= world.height) return false
            val node = world.getNode(cx, cy, cz)
            if (node == null || node.tiles.isEmpty() || node.tiles.any { it.isBlocking() }) return false
        }
        return true
    }
}
