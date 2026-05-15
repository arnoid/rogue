package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*
import com.roguelike.world.LadderTile
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

    /** Returns true if the node at (x, y, z+1) has a floor tile. */
    private fun hasFloorAbove(x: Int, y: Int, z: Int): Boolean {
        val above = world.getNode(x, y, z + 1) ?: return false
        return above.hasFloor
    }

    /** Returns true if the actor is currently on a stairs, ladder tile, or near a ladder edge. */
    private fun isOnStairs(actor: Actor): Boolean {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()
        for (checkZ in intArrayOf(z, z + 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val tile = node.getTile(TileSlot.STAIRS)
            if (tile is StairsTile || tile is LadderTile) return true
            if (node.ladderSlots.isNotEmpty()) return true
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
            // When on stairs/ladders, the actor spans two Z levels.
            // Must check collision at BOTH floor(z) and ceil(z) -- block if EITHER level blocks.
            val onStairs = isOnStairs(actor)
            val zLow = floor(actor.position.z).toInt()
            val zHigh = kotlin.math.ceil(actor.position.z.toDouble()).toInt()
            val z = if (onStairs) zLow else kotlin.math.round(actor.position.z).toInt()
            val size = actor.collisionSize

            val actorNodeX = floor(actor.position.x).toInt()
            val actorNodeY = floor(actor.position.y).toInt()

            var canMove = canMoveTo(nextX, nextY, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
            // On stairs, also check the upper Z level -- block if wall exists there
            if (canMove && onStairs && zHigh != zLow) {
                canMove = canMoveTo(nextX, nextY, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
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
                // On stairs, also check upper Z for slides
                if (onStairs && zHigh != zLow) {
                    if (canX) canX = canMoveTo(nextX, actor.position.y, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                    if (canY) canY = canMoveTo(actor.position.x, nextY, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
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

        // Apply stairs ramp, ladder climb, or ladder edge climb (adjusts Z smoothly)
        val ladderClimb = applyLadderClimb(actor, moveDir, delta)
        if (!ladderClimb) {
            val ladderEdgeClimb = applyLadderEdgeClimb(actor, moveDir, delta)
            if (!ladderEdgeClimb) {
                if (!applyStairsRamp(actor)) {
                    // Only apply gravity if not clinging to a ladder
                    val onLadder = isOnLadder(actor, moveDir)
                    if (!onLadder) {
                        applyGravity(actor)
                    } else if (logCooldown <= 0f) {
                        println("[$LOG_TAG] LADDER CLING: pos=(${actor.position.x}, ${actor.position.y}, ${actor.position.z}) moveDir=(${moveDir.x}, ${moveDir.y}) â€” gravity suspended")
                    }
                }
            } else if (logCooldown <= 0f) {
                println("[$LOG_TAG] LADDER EDGE CLIMB: pos=(${actor.position.x}, ${actor.position.y}, ${actor.position.z})")
            }
        } else if (logCooldown <= 0f) {
            println("[$LOG_TAG] LADDER TILE CLIMB: pos=(${actor.position.x}, ${actor.position.y}, ${actor.position.z})")
        }
    }

    /**
     * If the actor is standing on a stairs tile, interpolate their Z position
     * based on how far along the tile they are in the facing direction.
     * Returns true if the actor is on stairs.
     */
    private fun applyStairsRamp(actor: Actor): Boolean {
        // Use floor() for correct tile lookup â€” the tile at index n spans [n, n+1)
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()

        // Check current node and one below (for descending onto stairs from above)
        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val stairsTile = node.getTile(TileSlot.STAIRS) as? StairsTile ?: continue

            val facing = stairsTile.facingDirection()
            val baseZ = checkZ.toFloat()

            // Calculate progress along the stairs (0 = bottom/entry, 1 = top/exit)
            // With floor-based lookup, position within tile is (actor.pos - nx) in [0, 1)
            val progress = when (facing) {
                TileSlot.WALL_NORTH -> actor.position.y - ny.toFloat()
                TileSlot.WALL_SOUTH -> 1f - (actor.position.y - ny.toFloat())
                TileSlot.WALL_EAST  -> actor.position.x - nx.toFloat()
                TileSlot.WALL_WEST  -> 1f - (actor.position.x - nx.toFloat())
                else -> 0.5f
            }.coerceIn(0f, 1f)

            val targetZ = baseZ + progress
            // Prevent teleportation: only apply ramp if the Z change is gradual
            // (i.e., the actor's current Z is close to the target Z)
            val zDiff = kotlin.math.abs(targetZ - actor.position.z)
            if (zDiff > 0.5f) continue  // Skip this stairs tile â€” would be a teleport
            actor.position.z = targetZ
            return true
        }
        return false
    }

    /**
     * If the actor is standing on a ladder tile and moving into the ladder's facing direction,
     * move them straight up to the next Z level.
     * Returns true if the actor is on a ladder and climbing.
     */
    private fun applyLadderClimb(actor: Actor, moveDir: Vec3, delta: Float): Boolean {
        if (moveDir.isZero) return false

        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()

         // Check current node and one below
        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val ladderTile = node.getTile(TileSlot.STAIRS) as? LadderTile ?: continue

            // Only climb if actor is near the wall the ladder faces
            val facing = ladderTile.facingDirection()
            val size = actor.collisionSize
            val localX = actor.position.x - nx  // [0, 1) with floor-based lookup
            val localY = actor.position.y - ny
            val nearLadderWall = when (facing) {
                TileSlot.WALL_NORTH -> localY + size > 0.85f
                TileSlot.WALL_SOUTH -> localY - size < 0.15f
                TileSlot.WALL_EAST  -> localX + size > 0.85f
                TileSlot.WALL_WEST  -> localX - size < 0.15f
                else -> false
            }
            if (!nearLadderWall) continue

            // If there's a floor above the ladder, don't climb â€” ladder acts as wall
            if (hasFloorAbove(nx, ny, checkZ)) continue

            // Only climb if actor is moving into the ladder's facing direction
            val movingIntoLadder = when (facing) {
                TileSlot.WALL_NORTH -> moveDir.y > 0f
                TileSlot.WALL_SOUTH -> moveDir.y < 0f
                TileSlot.WALL_EAST  -> moveDir.x > 0f
                TileSlot.WALL_WEST  -> moveDir.x < 0f
                else -> false
            }
            if (!movingIntoLadder) continue

            val baseZ = checkZ.toFloat()
            val targetZ = baseZ + 1f // Ladder always lifts one full Z level

            // Smoothly move up: interpolate toward the target
            val climbSpeed = 2.0f // Z units per second â€” will feel instant with small delta
            val diff = targetZ - actor.position.z
            if (diff > 0.01f) {
                // Still climbing
                actor.position.z = (actor.position.z + climbSpeed * delta)
                    .coerceAtMost(targetZ)
            } else {
                actor.position.z = targetZ
            }
            return true
        }
        return false
    }

    /**
     * If the actor is adjacent to a ladder-tagged edge and actively moving into it,
     * move them straight up along Z axis.
     * Returns true if the actor is climbing a ladder edge.
     */
    private fun applyLadderEdgeClimb(actor: Actor, moveDir: Vec3, delta: Float): Boolean {
        if (moveDir.isZero) return false

        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()
        val size = actor.collisionSize

        val node = world.getNode(nx, ny, z) ?: return false

        // Check if the actor's collision box is pressing against a ladder-tagged edge
        // AND the actor is moving in the direction of that edge
        val localX = actor.position.x - nx
        val localY = actor.position.y - ny

        val nearEdge = when {
            localY + size > 0.85f && node.isLadder(TileSlot.WALL_NORTH) && moveDir.y > 0f -> true
            localY - size < 0.15f && node.isLadder(TileSlot.WALL_SOUTH) && moveDir.y < 0f -> true
            localX + size > 0.85f && node.isLadder(TileSlot.WALL_EAST) && moveDir.x > 0f -> true
            localX - size < 0.15f && node.isLadder(TileSlot.WALL_WEST) && moveDir.x < 0f -> true
            else -> false
        }

        if (!nearEdge) return false

        // If there's a floor above, don't climb â€” ladder acts as wall
        if (hasFloorAbove(nx, ny, z)) return false

        val climbSpeed = 2.0f
        actor.position.z += climbSpeed * delta
        return true
    }

    /**
     * Returns true if the actor is currently on/near a ladder (tile or edge) and should not fall.
     * The actor falls only if actively moving away from the ladder.
     */
    private fun isOnLadder(actor: Actor, moveDir: Vec3): Boolean {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()
        val size = actor.collisionSize

        // Check ladder tile â€” only if player is near the wall the ladder faces and no floor above
        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val ladderTile = node.getTile(TileSlot.STAIRS) as? LadderTile ?: continue
            if (hasFloorAbove(nx, ny, checkZ)) continue
            val facing = ladderTile.facingDirection()
            val localX = actor.position.x - nx
            val localY = actor.position.y - ny
            val nearLadderWall = when (facing) {
                TileSlot.WALL_NORTH -> localY + size > 0.35f
                TileSlot.WALL_SOUTH -> localY - size < -0.35f
                TileSlot.WALL_EAST  -> localX + size > 0.35f
                TileSlot.WALL_WEST  -> localX - size < -0.35f
                else -> false
            }
            if (!nearLadderWall) continue
            if (moveDir.isZero) {
                if (logCooldown <= 0f) println("[$LOG_TAG] isOnLadder=true (tile at node($nx,$ny,$checkZ) facing=$facing, not moving)")
                return true
            }
            val movingAway = when (facing) {
                TileSlot.WALL_NORTH -> moveDir.y < 0f
                TileSlot.WALL_SOUTH -> moveDir.y > 0f
                TileSlot.WALL_EAST  -> moveDir.x < 0f
                TileSlot.WALL_WEST  -> moveDir.x > 0f
                else -> false
            }
            if (!movingAway) {
                if (logCooldown <= 0f) println("[$LOG_TAG] isOnLadder=true (tile at node($nx,$ny,$checkZ) facing=$facing, not moving away)")
                return true
            }
        }

        // Check ladder edge â€” only if no floor above
        val node = world.getNode(nx, ny, z)
        if (node != null && !hasFloorAbove(nx, ny, z)) {
            val localX = actor.position.x - nx  // [0, 1) with floor-based lookup
            val localY = actor.position.y - ny
            val nearNorth = localY + size > 0.85f && node.isLadder(TileSlot.WALL_NORTH)
            val nearSouth = localY - size < 0.15f && node.isLadder(TileSlot.WALL_SOUTH)
            val nearEast  = localX + size > 0.85f && node.isLadder(TileSlot.WALL_EAST)
            val nearWest  = localX - size < 0.15f && node.isLadder(TileSlot.WALL_WEST)

            if (nearNorth || nearSouth || nearEast || nearWest) {
                if (moveDir.isZero) {
                    if (logCooldown <= 0f) println("[$LOG_TAG] isOnLadder=true (edge at node($nx,$ny,$z) N=$nearNorth S=$nearSouth E=$nearEast W=$nearWest, not moving)")
                    return true
                }
                val movingAway = when {
                    nearNorth -> moveDir.y < 0f
                    nearSouth -> moveDir.y > 0f
                    nearEast  -> moveDir.x < 0f
                    nearWest  -> moveDir.x > 0f
                    else -> false
                }
                if (!movingAway) {
                    if (logCooldown <= 0f) println("[$LOG_TAG] isOnLadder=true (edge at node($nx,$ny,$z) N=$nearNorth S=$nearSouth E=$nearEast W=$nearWest, not moving away)")
                    return true
                }
            }
        }

        return false
    }

    /**
     * Check if the actor's bounding box at (tx, ty) would cross any blocking wall edge.
     *
     * Walls sit at integer boundaries (e.g. x=3 is the boundary between node x=2 and x=3).
     * The rendering convention places tile n centered at (n+0.5, n+0.5), so edges are
     * at integer world coordinates. We check every integer boundary the bounding box
     * spans and see if a wall exists there.
     */
    private fun canMoveTo(tx: Float, ty: Float, z: Int, size: Float, actorNodeX: Int, actorNodeY: Int, log: Boolean = false): Boolean {
        val left   = tx - size
        val right  = tx + size
        val bottom = ty - size
        val top    = ty + size

        // Out of world bounds
        if (left < 0f || right > world.width.toFloat() ||
            bottom < 0f || top > world.height.toFloat()) {
            if (log) println("[$LOG_TAG]   BLOCKED by world bounds: left=$left right=$right bottom=$bottom top=$top")
            return false
        }

        // Check all vertical wall boundaries (X = integer) that the box spans
        val minNodeX = floor(left).toInt()
        val maxNodeX = floor(right).toInt()
        for (ix in minNodeX until maxNodeX) {
            val boundary = (ix + 1).toFloat()
            if (left < boundary && right > boundary) {
                val minY = floor(bottom).toInt().coerceAtLeast(0)
                val maxY = floor(top).toInt().coerceAtMost(world.height - 1)
                for (iy in minY..maxY) {
                    val leftNode = world.getNode(ix, iy, z)
                    if (leftNode != null && leftNode.isWallBlocking(TileSlot.WALL_EAST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by EAST wall at node($ix,$iy,$z)")
                        return false
                    }
                    if (leftNode != null && leftNode.isLadder(TileSlot.WALL_EAST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by EAST ladder at node($ix,$iy,$z)")
                        return false
                    }
                    val rightNode = world.getNode(ix + 1, iy, z)
                    if (rightNode != null && rightNode.isWallBlocking(TileSlot.WALL_WEST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by WEST wall at node(${ix+1},$iy,$z)")
                        return false
                    }
                    if (rightNode != null && rightNode.isLadder(TileSlot.WALL_WEST)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by WEST ladder at node(${ix+1},$iy,$z)")
                        return false
                    }
                }
            }
        }

        // Check all horizontal wall boundaries (Y = integer) that the box spans
        val minNodeY = floor(bottom).toInt()
        val maxNodeY = floor(top).toInt()
        for (iy in minNodeY until maxNodeY) {
            val boundary = (iy + 1).toFloat()
            if (bottom < boundary && top > boundary) {
                val minX = floor(left).toInt().coerceAtLeast(0)
                val maxX = floor(right).toInt().coerceAtMost(world.width - 1)
                for (ix in minX..maxX) {
                    val belowNode = world.getNode(ix, iy, z)
                    if (belowNode != null && belowNode.isWallBlocking(TileSlot.WALL_NORTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by NORTH wall at node($ix,$iy,$z)")
                        return false
                    }
                    if (belowNode != null && belowNode.isLadder(TileSlot.WALL_NORTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by NORTH ladder at node($ix,$iy,$z)")
                        return false
                    }
                    val aboveNode = world.getNode(ix, iy + 1, z)
                    if (aboveNode != null && aboveNode.isWallBlocking(TileSlot.WALL_SOUTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by SOUTH wall at node($ix,${iy+1},$z)")
                        return false
                    }
                    if (aboveNode != null && aboveNode.isLadder(TileSlot.WALL_SOUTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by SOUTH ladder at node($ix,${iy+1},$z)")
                        return false
                    }
                }
            }
        }

        // Check stairs blocking: when the actor's center enters a stairs node from a blocked side
        val targetNodeX = floor(tx).toInt()
        val targetNodeY = floor(ty).toInt()
        if (targetNodeX != actorNodeX || targetNodeY != actorNodeY) {
            if (targetNodeX != actorNodeX) {
                val side = if (targetNodeX > actorNodeX) TileSlot.WALL_WEST else TileSlot.WALL_EAST
                if (isStairsSideBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by stairs $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
                if (isLadderFacingBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by ladder $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
            }
            if (targetNodeY != actorNodeY) {
                val side = if (targetNodeY > actorNodeY) TileSlot.WALL_SOUTH else TileSlot.WALL_NORTH
                if (isStairsSideBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by stairs $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
                if (isLadderFacingBlocked(targetNodeX, targetNodeY, z, side, actorNodeX, actorNodeY)) {
                    if (log) println("[$LOG_TAG]   BLOCKED by ladder $side side at node($targetNodeX,$targetNodeY,$z)")
                    return false
                }
            }
        }


        // Check ladder model collision (ladder is a thin obstacle within its node)
        // Only blocks when there's a floor above (ladder acts as wall); otherwise climbing handles it
        val ladderCheckX = floor(tx).toInt()
        val ladderCheckY = floor(ty).toInt()
        val ladderNode = world.getNode(ladderCheckX, ladderCheckY, z)
        if (ladderNode != null) {
            val ladderTile = ladderNode.getTile(TileSlot.STAIRS) as? LadderTile
            if (ladderTile != null && hasFloorAbove(ladderCheckX, ladderCheckY, z)) {
                val facing = ladderTile.facingDirection()
                val localX = tx - ladderCheckX  // [0, 1) with floor-based lookup
                val localY = ty - ladderCheckY
                val modelPos = 0.85f  // wall position within [0,1) tile space
                val blocked = when (facing) {
                    TileSlot.WALL_NORTH -> localY + size > modelPos
                    TileSlot.WALL_SOUTH -> localY - size < (1f - modelPos)
                    TileSlot.WALL_EAST  -> localX + size > modelPos
                    TileSlot.WALL_WEST  -> localX - size < (1f - modelPos)
                    else -> false
                }
                if (blocked) {
                    if (log) println("[$LOG_TAG]   BLOCKED by ladder model at node($ladderCheckX,$ladderCheckY,$z) facing=$facing")
                    return false
                }
            }
        }

        // Check prop collision (decorations/furniture with bounding boxes)
        if (log) println("[$LOG_TAG]   PROP COLLISION CHECK: actorBox=[$left..$right, $bottom..$top] z=$z propCount=${world.props.size}")
        for (prop in world.props) {
            val zDist = kotlin.math.abs(z.toFloat() - prop.z)
            val (hsX, hsY) = prop.rotatedHalfSizes()
            if (log) println("[$LOG_TAG]     prop '${prop.name}' pos=(${prop.x},${prop.y},${prop.z}) hsX=$hsX hsY=$hsY rot=${prop.rotationY} rawHs=(${prop.collisionHalfSizeX},${prop.collisionHalfSizeY}) zDist=$zDist " +
                "propBox=[${prop.x - hsX}..${prop.x + hsX}, ${prop.y - hsY}..${prop.y + hsY}] " +
                "overlapX=${left < prop.x + hsX && right > prop.x - hsX} overlapY=${bottom < prop.y + hsY && top > prop.y - hsY} " +
                "zInRange=${zDist <= 1f}")
            if (zDist > 1f) continue
            if (hsX <= 0f && hsY <= 0f) {
                if (log) println("[$LOG_TAG]     SKIPPED prop '${prop.name}': collisionHalfSize is zero or negative")
                continue
            }
            if (left < prop.x + hsX && right > prop.x - hsX &&
                bottom < prop.y + hsY && top > prop.y - hsY) {
                if (log) println("[$LOG_TAG]   BLOCKED by prop '${prop.name}' at (${prop.x},${prop.y},${prop.z}) hs=($hsX,$hsY)")
                return false
            }
        }

        return true
    }

    /**
     * Returns true if a ladder tile at (nx, ny, z) blocks entry from the given side.
     * Ladder does not block node entry â€” collision is handled internally.
     */
    private fun isLadderFacingBlocked(nx: Int, ny: Int, z: Int, entrySide: TileSlot, actorNodeX: Int, actorNodeY: Int): Boolean {
        return false
    }

    /**
     * Returns true if the stairs at (nx, ny, z) blocks entry from the given side.
     * Stairs allow entry from the opposite-of-facing direction (bottom/entry) at same Z.
     * Stairs block entry from the facing direction (top/exit) at same Z — the player
     * can only come from Z+1 to descend. Side entry is always blocked.
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
        // Only allow entry from the opposite (bottom) side at same Z level
        return entrySide != oppositeFacing
    }

    /**
     * Drop the actor to the highest floor below their current position.
     * If no floor is found, the actor stays at z=0.
     */
    private fun applyGravity(actor: Actor) {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        // Use round for Z so that z=0.9 (top of stairs) checks Z=1 floor first
        var z = kotlin.math.round(actor.position.z).toInt()

        while (z >= 0) {
            val node = world.getNode(nx, ny, z)
            if (node != null && (node.hasFloor || node.hasTile(TileSlot.STAIRS))) {
                actor.position.z = z.toFloat()
                return
            }
            // Check if the node below has a ladder tile (ladder top is walkable only above the model)
            val below = world.getNode(nx, ny, z - 1)
            if (below != null) {
                val ladderTile = below.getTile(TileSlot.STAIRS) as? LadderTile
                if (ladderTile != null) {
                    val facing = ladderTile.facingDirection()
                    val localX = actor.position.x - nx  // [0, 1) with floor-based lookup
                    val localY = actor.position.y - ny
                    val onLadderTop = when (facing) {
                        TileSlot.WALL_NORTH -> localY > 0.7f
                        TileSlot.WALL_SOUTH -> localY < 0.3f
                        TileSlot.WALL_EAST  -> localX > 0.7f
                        TileSlot.WALL_WEST  -> localX < 0.3f
                        else -> false
                    }
                    if (onLadderTop) {
                        actor.position.z = z.toFloat()
                        return
                    }
                }
            }
            z--
        }

        actor.position.z = 0f
    }
}
