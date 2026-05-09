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

        // Determine if actor overlaps a closed door node — if so, ignore that node in collision
        val skipNode = findOverlappingClosedDoorNode(actor, norDir)

        if (canMove(nextX, nextY, actor.position.z, actor.collisionSize, skipNode)) {
            actor.position.x = nextX
            actor.position.y = nextY
        } else {
            if (canMove(nextX, actor.position.y, actor.position.z, actor.collisionSize, skipNode)) {
                actor.position.x = nextX
            } else if (canMove(actor.position.x, nextY, actor.position.z, actor.collisionSize, skipNode)) {
                actor.position.y = nextY
            }
        }
    }

    private fun canMove(targetX: Float, targetY: Float, targetZ: Float, size: Float, skipNode: WorldNode? = null): Boolean {
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
            if (node != null && node === skipNode) continue  // ignore the door node we're escaping from
            if (node == null || node.tiles.isEmpty() || node.tiles.any { it.isBlocking() }) return false
        }
        return true
    }

    /**
     * Finds a closed door node that the actor's collision box overlaps,
     * and checks if the actor is moving away from it along the allowed escape axis.
     * Returns the node to skip in collision, or null.
     */
    private fun findOverlappingClosedDoorNode(actor: Actor, moveDir: Vec3): WorldNode? {
        val size = actor.collisionSize
        val cz = Math.round(actor.position.z)

        // Check all nodes the actor's collision box overlaps
        val minX = Math.round(actor.position.x - size)
        val maxX = Math.round(actor.position.x + size)
        val minY = Math.round(actor.position.y - size)
        val maxY = Math.round(actor.position.y + size)

        for (nx in minX..maxX) {
            for (ny in minY..maxY) {
                val node = world.getNode(nx, ny, cz) ?: continue
                val closedDoor = node.tiles.firstOrNull { it.slot == TileSlot.DOOR && it.isBlocking() } ?: continue
                val doorType = closedDoor.type

                val isHorizontal = doorType.contains("Horizontal", ignoreCase = true)
                val isVertical = doorType.contains("Vertical", ignoreCase = true)

                // Check if actor is moving away from this door node
                val offsetX = actor.position.x - nx.toFloat()
                val offsetY = actor.position.y - ny.toFloat()

                if (isHorizontal) {
                    if ((offsetY >= 0 && moveDir.y > 0) || (offsetY < 0 && moveDir.y < 0)) return node
                }
                if (isVertical) {
                    if ((offsetX >= 0 && moveDir.x > 0) || (offsetX < 0 && moveDir.x < 0)) return node
                }
            }
        }
        return null
    }

    /**
     * Public check used by InteractionSystem — kept for API compatibility.
     */
    fun isEscapingClosedDoor(actor: Actor, moveDir: Vec3): Boolean {
        return findOverlappingClosedDoorNode(actor, moveDir) != null
    }
}
