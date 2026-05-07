package com.roguelike.systems

import com.badlogic.gdx.math.Vector3
import com.roguelike.world.Actor
import com.roguelike.world.World

class MovementSystem(private val world: World) {

    fun move(actor: Actor, moveDir: Vector3, delta: Float, speed: Float) {
        if (moveDir.isZero) return

        val scaledMove = Vector3(moveDir).nor().scl(delta * speed)
        
        val nextX = actor.position.x + scaledMove.x
        val nextY = actor.position.y + scaledMove.y

        if (canMove(nextX, nextY, actor.position.z, actor.collisionSize)) {
            actor.position.x = nextX
            actor.position.y = nextY
        } else {
            // Sliding collision
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
