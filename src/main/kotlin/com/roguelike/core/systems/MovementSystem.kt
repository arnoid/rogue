package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*
import com.roguelike.world.StairsTile
import kotlin.math.floor
import kotlin.math.round

/**
 * Moves actors through the world, enforcing wall-based collision and gravity.
 *
 * ## Collision model
 * - Walls sit on node edges (boundaries between adjacent nodes).
 * - A wall blocks when the actor's collision box crosses that edge.
 * - A wall tagged as a door does NOT block.
 * - The actor's collision box is a square of half-size [Actor.collisionSize].
 *
 * ## Gravity
 * - Actor falls until a node with a floor is found below.
 *
 * ## Stairs
 * - Stairs act as ramps: the actor's Z is interpolated as they walk across.
 */
class MovementSystem(private val world: World) {

    private val LOG_TAG = "MovementSystem"
    private var logCooldown = 0f

    /** Returns true if the actor is currently on a stairs tile. */
    private fun isOnStairs(actor: Actor): Boolean {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()
        for (checkZ in intArrayOf(z, z + 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            if (node.getTile(TileSlot.STAIRS) is StairsTile) return true
        }
        return false
    }

    fun move(actor: Actor, moveDir: Vec3, delta: Float, speed: Float) {
        logCooldown -= delta

        if (!moveDir.isZero) {
            val dir = Vec3(moveDir).nor()
            val step = Vec3(dir).scl(delta * speed)
            actor.facingDirection.set(dir.x, dir.y, 0f)

            val nextX = actor.position.x + step.x
            val nextY = actor.position.y + step.y
            // When on stairs, check both floor(z) and round(z) — only block if BOTH levels block.
            // This prevents getting stuck at the top of stairs (where floor(z) is the lower level
            // with walls, but round(z) is the upper open level).
            val onStairs = isOnStairs(actor)
            val zFloor = floor(actor.position.z).toInt()
            val zRound = round(actor.position.z).toInt()
            val z = if (onStairs) zFloor else zRound
            val size = actor.collisionSize

            val actorNodeX = round(actor.position.x).toInt()
            val actorNodeY = round(actor.position.y).toInt()

            var canMove = canMoveTo(nextX, nextY, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
            // If blocked on stairs and floor/round differ, try the other z level
            if (!canMove && onStairs && zFloor != zRound) {
                canMove = canMoveTo(nextX, nextY, zRound, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
            }

            if (logCooldown <= 0f) {
                println("[$LOG_TAG] pos=(${actor.position.x}, ${actor.position.y}, ${actor.position.z}) z_col=$z onStairs=$onStairs dir=(${dir.x}, ${dir.y}) next=($nextX, $nextY) size=$size canMove=$canMove")
            }

            // Try full move, then X-slide, then Y-slide
            if (canMove) {
                actor.position.x = nextX
                actor.position.y = nextY
            } else {
                var canX = canMoveTo(nextX, actor.position.y, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                var canY = canMoveTo(actor.position.x, nextY, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                // Try alternate z level on stairs for slides too
                if (onStairs && zFloor != zRound) {
                    if (!canX) canX = canMoveTo(nextX, actor.position.y, zRound, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                    if (!canY) canY = canMoveTo(actor.position.x, nextY, zRound, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                }
                if (logCooldown <= 0f) {
                    println("[$LOG_TAG]   BLOCKED full move. canX=$canX canY=$canY")
                }
                if (canX) {
                    actor.position.x = nextX
                } else if (canY) {
                    actor.position.y = nextY
                } else if (logCooldown <= 0f) {
                    println("[$LOG_TAG]   STUCK! Cannot move in any direction.")
                }
            }

            if (logCooldown <= 0f) logCooldown = 0.3f
        }

        // Apply stairs ramp (adjusts Z smoothly based on position on stairs tile)
        if (!applyStairsRamp(actor)) {
            applyGravity(actor)
        }
    }

    /**
     * If the actor is standing on a stairs tile, interpolate their Z position
     * based on how far along the tile they are in the facing direction.
     * Returns true if the actor is on stairs.
     */
    private fun applyStairsRamp(actor: Actor): Boolean {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        val z = round(actor.position.z).toInt()

        // Check current node and one below (for descending onto stairs from above)
        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val stairsTile = node.getTile(TileSlot.STAIRS) as? StairsTile ?: continue

            val facing = stairsTile.facingDirection()
            val baseZ = checkZ.toFloat()

            // Calculate progress along the stairs (0 = bottom/entry, 1 = top/exit)
            // The actor enters from the opposite side of facing and exits in the facing direction.
            // Position within the tile: local offset from tile center is -0.5 to +0.5
            val progress = when (facing) {
                TileSlot.WALL_NORTH -> (actor.position.y - ny.toFloat()) + 0.5f  // -0.5..+0.5 -> 0..1
                TileSlot.WALL_SOUTH -> -(actor.position.y - ny.toFloat()) + 0.5f
                TileSlot.WALL_EAST  -> (actor.position.x - nx.toFloat()) + 0.5f
                TileSlot.WALL_WEST  -> -(actor.position.x - nx.toFloat()) + 0.5f
                else -> 0.5f
            }.coerceIn(0f, 1f)

            val targetZ = baseZ + progress
            // Prevent teleportation: only apply ramp if the Z change is gradual
            // (i.e., the actor's current Z is close to the target Z)
            val zDiff = kotlin.math.abs(targetZ - actor.position.z)
            if (zDiff > 0.5f) continue  // Skip this stairs tile — would be a teleport
            actor.position.z = targetZ
            return true
        }
        return false
    }

    /**
     * Check if the actor's bounding box at (tx, ty) would cross any blocking wall edge.
     *
     * Walls sit at integer boundaries (e.g. x=1.5 is the boundary between node x=1 and x=2).
     * We check every integer boundary that the bounding box spans and see if a wall exists there.
     */
    private fun canMoveTo(tx: Float, ty: Float, z: Int, size: Float, actorNodeX: Int, actorNodeY: Int, log: Boolean = false): Boolean {
        val left   = tx - size
        val right  = tx + size
        val bottom = ty - size
        val top    = ty + size

        // Out of world bounds
        if (left < -0.5f || right > world.width - 0.5f ||
            bottom < -0.5f || top > world.height - 0.5f) {
            if (log) println("[$LOG_TAG]   BLOCKED by world bounds: left=$left right=$right bottom=$bottom top=$top")
            return false
        }

        // Check all vertical wall boundaries (X = integer + 0.5) that the box spans
        val minNodeX = floor(left + 0.5f).toInt()
        val maxNodeX = floor(right + 0.5f).toInt()
        for (ix in minNodeX until maxNodeX) {
            val boundary = ix.toFloat() + 0.5f
            if (left < boundary && right > boundary) {
                val minY = floor(bottom + 0.5f).toInt().coerceAtLeast(0)
                val maxY = floor(top + 0.5f).toInt().coerceAtMost(world.height - 1)
                for (iy in minY..maxY) {
                    val leftNode = world.getNode(ix, iy, z)
                    if (leftNode != null && leftNode.isWallBlocking(TileSlot.WALL_EAST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by EAST wall at node($ix,$iy,$z)")
                        return false
                    }
                    val rightNode = world.getNode(ix + 1, iy, z)
                    if (rightNode != null && rightNode.isWallBlocking(TileSlot.WALL_WEST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by WEST wall at node(${ix+1},$iy,$z)")
                        return false
                    }
                }
            }
        }

        // Check all horizontal wall boundaries (Y = integer + 0.5) that the box spans
        val minNodeY = floor(bottom + 0.5f).toInt()
        val maxNodeY = floor(top + 0.5f).toInt()
        for (iy in minNodeY until maxNodeY) {
            val boundary = iy.toFloat() + 0.5f
            if (bottom < boundary && top > boundary) {
                val minX = floor(left + 0.5f).toInt().coerceAtLeast(0)
                val maxX = floor(right + 0.5f).toInt().coerceAtMost(world.width - 1)
                for (ix in minX..maxX) {
                    val belowNode = world.getNode(ix, iy, z)
                    if (belowNode != null && belowNode.isWallBlocking(TileSlot.WALL_NORTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by NORTH wall at node($ix,$iy,$z)")
                        return false
                    }
                    val aboveNode = world.getNode(ix, iy + 1, z)
                    if (aboveNode != null && aboveNode.isWallBlocking(TileSlot.WALL_SOUTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by SOUTH wall at node($ix,${iy+1},$z)")
                        return false
                    }
                }
            }
        }

        // Check stairs blocking: when the actor's center enters a stairs node from a blocked side
        val targetNodeX = round(tx).toInt()
        val targetNodeY = round(ty).toInt()
        if (targetNodeX != actorNodeX || targetNodeY != actorNodeY) {
            if (targetNodeX != actorNodeX) {
                val side = if (targetNodeX > actorNodeX) TileSlot.WALL_WEST else TileSlot.WALL_EAST
                if (isStairsSideBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by stairs $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
            }
            if (targetNodeY != actorNodeY) {
                val side = if (targetNodeY > actorNodeY) TileSlot.WALL_SOUTH else TileSlot.WALL_NORTH
                if (isStairsSideBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by stairs $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
            }
        }

        return true
    }

    /**
     * Returns true if the stairs at (nx, ny, z) blocks entry from the given side.
     * Stairs allow entry only from the facing direction and its opposite.
     * Skips blocking if the actor is already in that node.
     */
    private fun isStairsSideBlocked(nx: Int, ny: Int, z: Int, entrySide: TileSlot, actorNodeX: Int, actorNodeY: Int): Boolean {
        if (nx == actorNodeX && ny == actorNodeY) return false
        val node = world.getNode(nx, ny, z) ?: return false
        val stairsTile = node.getTile(TileSlot.STAIRS) as? StairsTile ?: return false
        val facing = stairsTile.facingDirection()
        val oppositeFacing = when (facing) {
            TileSlot.WALL_NORTH -> TileSlot.WALL_SOUTH
            TileSlot.WALL_SOUTH -> TileSlot.WALL_NORTH
            TileSlot.WALL_EAST  -> TileSlot.WALL_WEST
            TileSlot.WALL_WEST  -> TileSlot.WALL_EAST
            else -> facing
        }
        return entrySide != facing && entrySide != oppositeFacing
    }

    /**
     * Drop the actor to the highest floor below their current position.
     * If no floor is found, the actor stays at z=0.
     */
    private fun applyGravity(actor: Actor) {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        var z = round(actor.position.z).toInt()

        while (z >= 0) {
            val node = world.getNode(nx, ny, z)
            if (node != null && (node.hasFloor || node.hasTile(TileSlot.STAIRS))) {
                actor.position.z = z.toFloat()
                return
            }
            z--
        }

        actor.position.z = 0f
    }
}
