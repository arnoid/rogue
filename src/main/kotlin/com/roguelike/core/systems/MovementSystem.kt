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
 * ## Climbing State
 * - When the actor moves into a ladder, they enter CLIMBING state.
 * - While climbing: XY is locked to the ladder's face, gravity is disabled,
 *   forward/backward input maps to vertical movement.
 * - Dismount at top: vault onto the floor above. Dismount by moving away: detach and fall.
 */
class MovementSystem(private val world: World) {

    private val LOG_TAG = "MovementSystem"
    private var logCooldown = 0f

    companion object {
        private const val CLIMB_SPEED = 2.5f
        private const val LADDER_FACE_POS = 0.85f
    }

    private fun hasFloorAbove(x: Int, y: Int, z: Int): Boolean {
        val above = world.getNode(x, y, z + 1) ?: return false
        return above.hasFloor
    }

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

        // ─── CLIMBING STATE ───
        if (actor.isClimbing) {
            handleClimbing(actor, moveDir, delta)
            return
        }

        // ─── NORMAL STATE ───
        if (!moveDir.isZero) {
            val dir = Vec3(moveDir).nor()
            val step = Vec3(dir).scl(delta * speed)
            actor.facingDirection.set(dir.x, dir.y, 0f)

            // Doors never auto-open on collision — they must be opened via
            // explicit interaction (see InteractionSystem.interact).

            val nextX = actor.position.x + step.x
            val nextY = actor.position.y + step.y
            val onStairs = isOnStairs(actor)
            val zLow = floor(actor.position.z).toInt()
            val zHigh = kotlin.math.ceil(actor.position.z.toDouble()).toInt()
            val z = if (onStairs) zLow else kotlin.math.round(actor.position.z).toInt()
            val size = actor.collisionSize

            val actorNodeX = floor(actor.position.x).toInt()
            val actorNodeY = floor(actor.position.y).toInt()

            var canMove = canMoveTo(nextX, nextY, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
            if (canMove && onStairs && zHigh != zLow) {
                canMove = canMoveTo(nextX, nextY, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
            }

            if (logCooldown <= 0f) {
                println("[$LOG_TAG] pos=(${actor.position.x}, ${actor.position.y}, ${actor.position.z}) z_col=$z onStairs=$onStairs dir=(${dir.x}, ${dir.y}) next=($nextX, $nextY) size=$size canMove=$canMove")
            }

            if (canMove) {
                actor.position.x = nextX
                actor.position.y = nextY
            } else {
                var canX = canMoveTo(nextX, actor.position.y, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                var canY = canMoveTo(actor.position.x, nextY, z, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                if (onStairs && zHigh != zLow) {
                    if (canX) canX = canMoveTo(nextX, actor.position.y, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                    if (canY) canY = canMoveTo(actor.position.x, nextY, zHigh, size, actorNodeX, actorNodeY, log = logCooldown <= 0f)
                }
                if (logCooldown <= 0f) println("[$LOG_TAG]   BLOCKED full move. canX=$canX canY=$canY")
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

        // ─── Check if actor should enter climbing state ───
        if (tryEnterClimbing(actor, moveDir)) {
            if (logCooldown <= 0f) println("[$LOG_TAG] ENTERED CLIMBING STATE at z=${actor.position.z}")
            return
        }

        // ─── Vertical: stairs ramp or gravity ───
        if (!applyStairsRamp(actor)) {
            applyGravity(actor)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLIMBING STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryEnterClimbing(actor: Actor, moveDir: Vec3): Boolean {
        if (moveDir.isZero) return false

        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()
        val size = actor.collisionSize

        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val ladderTile = node.getTile(TileSlot.STAIRS) as? LadderTile ?: continue
            val facing = ladderTile.facingDirection()

            val localX = actor.position.x - nx
            val localY = actor.position.y - ny
            val nearWall = when (facing) {
                TileSlot.WALL_NORTH -> localY + size > LADDER_FACE_POS
                TileSlot.WALL_SOUTH -> localY - size < (1f - LADDER_FACE_POS)
                TileSlot.WALL_EAST  -> localX + size > LADDER_FACE_POS
                TileSlot.WALL_WEST  -> localX - size < (1f - LADDER_FACE_POS)
                else -> false
            }
            if (!nearWall) continue

            val movingInto = when (facing) {
                TileSlot.WALL_NORTH -> moveDir.y > 0f
                TileSlot.WALL_SOUTH -> moveDir.y < 0f
                TileSlot.WALL_EAST  -> moveDir.x > 0f
                TileSlot.WALL_WEST  -> moveDir.x < 0f
                else -> false
            }
            if (!movingInto) continue

            // Enter climbing state
            actor.isClimbing = true
            when (facing) {
                TileSlot.WALL_NORTH -> actor.position.y = ny + LADDER_FACE_POS
                TileSlot.WALL_SOUTH -> actor.position.y = ny + (1f - LADDER_FACE_POS)
                TileSlot.WALL_EAST  -> actor.position.x = nx + LADDER_FACE_POS
                TileSlot.WALL_WEST  -> actor.position.x = nx + (1f - LADDER_FACE_POS)
                else -> {}
            }
            actor.climbAnchor.set(actor.position.x, actor.position.y, 0f)
            return true
        }

        // Check ladder-tagged edges
        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val localX = actor.position.x - nx
            val localY = actor.position.y - ny

            val ladderEdge = when {
                localY + size > LADDER_FACE_POS && node.isLadder(TileSlot.WALL_NORTH) && moveDir.y > 0f -> TileSlot.WALL_NORTH
                localY - size < (1f - LADDER_FACE_POS) && node.isLadder(TileSlot.WALL_SOUTH) && moveDir.y < 0f -> TileSlot.WALL_SOUTH
                localX + size > LADDER_FACE_POS && node.isLadder(TileSlot.WALL_EAST) && moveDir.x > 0f -> TileSlot.WALL_EAST
                localX - size < (1f - LADDER_FACE_POS) && node.isLadder(TileSlot.WALL_WEST) && moveDir.x < 0f -> TileSlot.WALL_WEST
                else -> null
            } ?: continue

            actor.isClimbing = true
            when (ladderEdge) {
                TileSlot.WALL_NORTH -> actor.position.y = ny + LADDER_FACE_POS
                TileSlot.WALL_SOUTH -> actor.position.y = ny + (1f - LADDER_FACE_POS)
                TileSlot.WALL_EAST  -> actor.position.x = nx + LADDER_FACE_POS
                TileSlot.WALL_WEST  -> actor.position.x = nx + (1f - LADDER_FACE_POS)
                else -> {}
            }
            actor.climbAnchor.set(actor.position.x, actor.position.y, 0f)
            return true
        }

        // ─── Edge-Catch: Top-Down Entry ───
        // If the player is at a floor edge and moving toward it, check if the node
        // in the movement direction (at Z-1) has a ladder facing toward the player.
        val edgeThreshold = 0.2f
        val edgeLocalX = actor.position.x - nx
        val edgeLocalY = actor.position.y - ny

        data class EdgeCheck(val targetNx: Int, val targetNy: Int, val requiredFacing: TileSlot, val nearEdge: Boolean)

        val edgeChecks = listOf(
            EdgeCheck(nx, ny + 1, TileSlot.WALL_SOUTH, edgeLocalY + size > (1f - edgeThreshold) && moveDir.y > 0f),
            EdgeCheck(nx, ny - 1, TileSlot.WALL_NORTH, edgeLocalY - size < edgeThreshold && moveDir.y < 0f),
            EdgeCheck(nx + 1, ny, TileSlot.WALL_WEST, edgeLocalX + size > (1f - edgeThreshold) && moveDir.x > 0f),
            EdgeCheck(nx - 1, ny, TileSlot.WALL_EAST, edgeLocalX - size < edgeThreshold && moveDir.x < 0f)
        )

        for (check in edgeChecks) {
            if (!check.nearEdge) continue
            // Target node must NOT have a floor at current Z (it's an open edge)
            val targetNodeAtZ = world.getNode(check.targetNx, check.targetNy, z)
            if (targetNodeAtZ != null && targetNodeAtZ.hasFloor) continue

            // Check for a ladder below in the target node
            for (ladderZ in intArrayOf(z - 1, z)) {
                val belowNode = world.getNode(check.targetNx, check.targetNy, ladderZ) ?: continue
                val ladderTile = belowNode.getTile(TileSlot.STAIRS) as? LadderTile
                if (ladderTile != null && ladderTile.facingDirection() == check.requiredFacing) {
                    actor.isClimbing = true
                    actor.position.z = z.toFloat()
                    when (check.requiredFacing) {
                        TileSlot.WALL_NORTH -> { actor.position.x = check.targetNx + 0.5f; actor.position.y = check.targetNy + LADDER_FACE_POS }
                        TileSlot.WALL_SOUTH -> { actor.position.x = check.targetNx + 0.5f; actor.position.y = check.targetNy + (1f - LADDER_FACE_POS) }
                        TileSlot.WALL_EAST  -> { actor.position.x = check.targetNx + LADDER_FACE_POS; actor.position.y = check.targetNy + 0.5f }
                        TileSlot.WALL_WEST  -> { actor.position.x = check.targetNx + (1f - LADDER_FACE_POS); actor.position.y = check.targetNy + 0.5f }
                        else -> {}
                    }
                    actor.climbAnchor.set(actor.position.x, actor.position.y, 0f)
                    if (logCooldown <= 0f) println("[$LOG_TAG] EDGE-CATCH: entering ladder at (${check.targetNx},${check.targetNy},$ladderZ)")
                    return true
                }
                if (belowNode.isLadder(check.requiredFacing)) {
                    actor.isClimbing = true
                    actor.position.z = z.toFloat()
                    when (check.requiredFacing) {
                        TileSlot.WALL_NORTH -> { actor.position.x = check.targetNx + 0.5f; actor.position.y = check.targetNy + LADDER_FACE_POS }
                        TileSlot.WALL_SOUTH -> { actor.position.x = check.targetNx + 0.5f; actor.position.y = check.targetNy + (1f - LADDER_FACE_POS) }
                        TileSlot.WALL_EAST  -> { actor.position.x = check.targetNx + LADDER_FACE_POS; actor.position.y = check.targetNy + 0.5f }
                        TileSlot.WALL_WEST  -> { actor.position.x = check.targetNx + (1f - LADDER_FACE_POS); actor.position.y = check.targetNy + 0.5f }
                        else -> {}
                    }
                    actor.climbAnchor.set(actor.position.x, actor.position.y, 0f)
                    if (logCooldown <= 0f) println("[$LOG_TAG] EDGE-CATCH: entering ladder edge at (${check.targetNx},${check.targetNy},$ladderZ)")
                    return true
                }
            }
        }

        return false
    }

    private fun handleClimbing(actor: Actor, moveDir: Vec3, delta: Float) {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()

        // Find the ladder the actor is on
        var ladderFacing: TileSlot? = null

        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val ladderTile = node.getTile(TileSlot.STAIRS) as? LadderTile
            if (ladderTile != null) {
                ladderFacing = ladderTile.facingDirection()
                break
            }
            // Check ladder edge tags
            val slots = node.ladderSlots
            if (slots.isNotEmpty()) {
                ladderFacing = slots.first()
                break
            }
        }

        if (ladderFacing == null) {
            exitClimbing(actor)
            if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB: no ladder found, dismounting at z=${actor.position.z}")
            if (logCooldown <= 0f) logCooldown = 0.3f
            return
        }

        val climbUp = when (ladderFacing) {
            TileSlot.WALL_NORTH -> moveDir.y > 0f
            TileSlot.WALL_SOUTH -> moveDir.y < 0f
            TileSlot.WALL_EAST  -> moveDir.x > 0f
            TileSlot.WALL_WEST  -> moveDir.x < 0f
            else -> false
        }
        val climbDown = when (ladderFacing) {
            TileSlot.WALL_NORTH -> moveDir.y < 0f
            TileSlot.WALL_SOUTH -> moveDir.y > 0f
            TileSlot.WALL_EAST  -> moveDir.x < 0f
            TileSlot.WALL_WEST  -> moveDir.x > 0f
            else -> false
        }

        if (climbUp) {
            val nextZ = z + 1
            val nodeAbove = world.getNode(nx, ny, nextZ)
            val hasLadderAbove = nodeAbove?.getTile(TileSlot.STAIRS) is LadderTile ||
                    (nodeAbove?.ladderSlots?.isNotEmpty() == true)

            // Check for a floor to vault onto — either directly above or in the facing direction
            val vaultNx = when (ladderFacing) {
                TileSlot.WALL_NORTH -> nx
                TileSlot.WALL_SOUTH -> nx
                TileSlot.WALL_EAST  -> nx + 1
                TileSlot.WALL_WEST  -> nx - 1
                else -> nx
            }
            val vaultNy = when (ladderFacing) {
                TileSlot.WALL_NORTH -> ny + 1
                TileSlot.WALL_SOUTH -> ny - 1
                TileSlot.WALL_EAST  -> ny
                TileSlot.WALL_WEST  -> ny
                else -> ny
            }
            val vaultNode = world.getNode(vaultNx, vaultNy, nextZ)
            val hasFloorAbove = (nodeAbove != null && nodeAbove.hasFloor) ||
                    (vaultNode != null && vaultNode.hasFloor)

            if (hasLadderAbove) {
                // Ladder continues above — climb freely
                actor.position.z += CLIMB_SPEED * delta
            } else if (hasFloorAbove) {
                // No ladder above but floor exists — climb up then vault
                val targetZ = nextZ.toFloat()
                if (actor.position.z < targetZ) {
                    actor.position.z = (actor.position.z + CLIMB_SPEED * delta).coerceAtMost(targetZ)
                }
                if (actor.position.z >= targetZ) {
                    // Vault onto floor
                    actor.position.z = targetZ
                    val pushDist = 0.3f
                    when (ladderFacing) {
                        TileSlot.WALL_NORTH -> actor.position.y = (ny + 1f) + pushDist
                        TileSlot.WALL_SOUTH -> actor.position.y = ny.toFloat() - pushDist
                        TileSlot.WALL_EAST  -> actor.position.x = (nx + 1f) + pushDist
                        TileSlot.WALL_WEST  -> actor.position.x = nx.toFloat() - pushDist
                        else -> {}
                    }
                    exitClimbing(actor)
                    if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB: vaulted onto floor at z=${actor.position.z} pos=(${actor.position.x},${actor.position.y})")
                }
            } else {
                // No ladder above, no floor — cap at top
                actor.position.z = (actor.position.z + CLIMB_SPEED * delta).coerceAtMost(z + 1f)
            }
            if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB UP: z=${actor.position.z}")
        } else if (climbDown) {
            val belowZ = z - 1
            val nodeBelow = world.getNode(nx, ny, belowZ)
            // Check for ladder below at same XY, or in the facing direction (for edge transitions)
            val facingNx = when (ladderFacing) {
                TileSlot.WALL_NORTH -> nx; TileSlot.WALL_SOUTH -> nx
                TileSlot.WALL_EAST -> nx + 1; TileSlot.WALL_WEST -> nx - 1; else -> nx
            }
            val facingNy = when (ladderFacing) {
                TileSlot.WALL_NORTH -> ny + 1; TileSlot.WALL_SOUTH -> ny - 1
                TileSlot.WALL_EAST -> ny; TileSlot.WALL_WEST -> ny; else -> ny
            }
            // For ladder edges, the actual ladder tile is in the facing direction at z or z-1
            val facingNode = world.getNode(facingNx, facingNy, z)
            val facingNodeBelow = world.getNode(facingNx, facingNy, belowZ)
            val hasLadderBelow = nodeBelow?.getTile(TileSlot.STAIRS) is LadderTile ||
                    (nodeBelow?.ladderSlots?.isNotEmpty() == true) ||
                    facingNode?.getTile(TileSlot.STAIRS) is LadderTile ||
                    facingNodeBelow?.getTile(TileSlot.STAIRS) is LadderTile
            val floorAtCurrent = world.getNode(nx, ny, z)?.hasFloor == true

            if (hasLadderBelow && !floorAtCurrent) {
                // Transition: move actor into the facing node to continue descending
                actor.position.x = facingNx + 0.5f
                actor.position.y = facingNy + 0.5f
                actor.position.z -= CLIMB_SPEED * delta
                // Re-lock to ladder face in new node
                val facingLadder = (facingNode?.getTile(TileSlot.STAIRS) as? LadderTile)
                    ?: (facingNodeBelow?.getTile(TileSlot.STAIRS) as? LadderTile)
                if (facingLadder != null) {
                    val newFacing = facingLadder.facingDirection()
                    when (newFacing) {
                        TileSlot.WALL_NORTH -> actor.position.y = facingNy + LADDER_FACE_POS
                        TileSlot.WALL_SOUTH -> actor.position.y = facingNy + (1f - LADDER_FACE_POS)
                        TileSlot.WALL_EAST  -> actor.position.x = facingNx + LADDER_FACE_POS
                        TileSlot.WALL_WEST  -> actor.position.x = facingNx + (1f - LADDER_FACE_POS)
                        else -> {}
                    }
                }
            } else {
                actor.position.z -= CLIMB_SPEED * delta

                if (!hasLadderBelow && floorAtCurrent && actor.position.z <= z.toFloat()) {
                    actor.position.z = z.toFloat()
                    exitClimbing(actor)
                    if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB: reached ground at z=${actor.position.z}")
                } else if (!hasLadderBelow && !floorAtCurrent) {
                    val floorZ = findFloorBelow(nx, ny, z)
                    if (actor.position.z <= floorZ) {
                        actor.position.z = floorZ
                        exitClimbing(actor)
                    }
                }
            }
            if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB DOWN: z=${actor.position.z}")
        } else {
            // Cling — no movement
            if (logCooldown <= 0f) println("[$LOG_TAG] CLIMB: clinging at z=${actor.position.z}")
        }

        // Keep XY locked to ladder face
        if (actor.isClimbing) {
            when (ladderFacing) {
                TileSlot.WALL_NORTH -> actor.position.y = ny + LADDER_FACE_POS
                TileSlot.WALL_SOUTH -> actor.position.y = ny + (1f - LADDER_FACE_POS)
                TileSlot.WALL_EAST  -> actor.position.x = nx + LADDER_FACE_POS
                TileSlot.WALL_WEST  -> actor.position.x = nx + (1f - LADDER_FACE_POS)
                else -> {}
            }
        }

        if (logCooldown <= 0f) logCooldown = 0.3f
    }

    private fun exitClimbing(actor: Actor) {
        actor.isClimbing = false
    }

    private fun findFloorBelow(nx: Int, ny: Int, startZ: Int): Float {
        var z = startZ
        while (z >= 0) {
            val node = world.getNode(nx, ny, z)
            if (node != null && (node.hasFloor || node.hasTile(TileSlot.STAIRS))) {
                return z.toFloat()
            }
            z--
        }
        return 0f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAIRS RAMP
    // ═══════════════════════════════════════════════════════════════════════════

    private fun applyStairsRamp(actor: Actor): Boolean {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        val z = floor(actor.position.z).toInt()

        for (checkZ in intArrayOf(z, z - 1)) {
            val node = world.getNode(nx, ny, checkZ) ?: continue
            val stairsTile = node.getTile(TileSlot.STAIRS) as? StairsTile ?: continue

            val facing = stairsTile.facingDirection()
            val baseZ = checkZ.toFloat()

            val progress = when (facing) {
                TileSlot.WALL_NORTH -> actor.position.y - ny.toFloat()
                TileSlot.WALL_SOUTH -> 1f - (actor.position.y - ny.toFloat())
                TileSlot.WALL_EAST  -> actor.position.x - nx.toFloat()
                TileSlot.WALL_WEST  -> 1f - (actor.position.x - nx.toFloat())
                else -> 0.5f
            }.coerceIn(0f, 1f)

            if (progress < 0.01f && checkZ == z) {
                val nodeBelow = world.getNode(nx, ny, checkZ - 1)
                if (nodeBelow?.getTile(TileSlot.STAIRS) is StairsTile) continue
            }

            val targetZ = baseZ + progress
            val zDiff = kotlin.math.abs(targetZ - actor.position.z)
            if (zDiff > 0.5f) continue
            actor.position.z = targetZ
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COLLISION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun canMoveTo(tx: Float, ty: Float, z: Int, size: Float, actorNodeX: Int, actorNodeY: Int, log: Boolean = false): Boolean {
        val left   = tx - size
        val right  = tx + size
        val bottom = ty - size
        val top    = ty + size

        if (left < 0f || right > world.width.toFloat() ||
            bottom < 0f || top > world.height.toFloat()) {
            if (log) println("[$LOG_TAG]   BLOCKED by world bounds")
            return false
        }

        // Vertical wall boundaries (X = integer)
        val minNodeX = floor(left).toInt()
        val maxNodeX = floor(right).toInt()
        for (ix in minNodeX until maxNodeX) {
            val boundary = (ix + 1).toFloat()
            if (left < boundary && right > boundary) {
                val minY = floor(bottom).toInt().coerceAtLeast(0)
                val maxY = floor(top).toInt().coerceAtMost(world.height - 1)
                for (iy in minY..maxY) {
                    val leftNode = world.getNode(ix, iy, z)
                    if (leftNode != null) {
                        if (leftNode.isDoor(TileSlot.WALL_EAST)) {
                            val tile = leftNode.getTile(TileSlot.WALL_EAST)
                            println("[DoorCheck] leftNode($ix,$iy,$z).WALL_EAST isDoor=true tile=${tile?.javaClass?.simpleName} isBlocking=${tile?.isBlocking()} isWallBlocking=${leftNode.isWallBlocking(TileSlot.WALL_EAST)}")
                        }
                        if (leftNode.isWallBlocking(TileSlot.WALL_EAST)) {
                            if (log) println("[$LOG_TAG]   BLOCKED by EAST wall at node($ix,$iy,$z)")
                            return false
                        }
                    }
                    val rightNode = world.getNode(ix + 1, iy, z)
                    if (rightNode != null) {
                        if (rightNode.isDoor(TileSlot.WALL_WEST)) {
                            val tile = rightNode.getTile(TileSlot.WALL_WEST)
                            println("[DoorCheck] rightNode(${ix+1},$iy,$z).WALL_WEST isDoor=true tile=${tile?.javaClass?.simpleName} isBlocking=${tile?.isBlocking()} isWallBlocking=${rightNode.isWallBlocking(TileSlot.WALL_WEST)}")
                        }
                        if (rightNode.isWallBlocking(TileSlot.WALL_WEST)) {
                            if (log) println("[$LOG_TAG]   BLOCKED by WEST wall at node(${ix+1},$iy,$z)")
                            return false
                        }
                    }
                }
            }
        }

        // Horizontal wall boundaries (Y = integer)
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
                    val aboveNode = world.getNode(ix, iy + 1, z)
                    if (aboveNode != null && aboveNode.isWallBlocking(TileSlot.WALL_SOUTH)) {
                        if (log) println("[$LOG_TAG]   BLOCKED by SOUTH wall at node($ix,${iy+1},$z)")
                        return false
                    }
                }
            }
        }

        // Stairs blocking
        val targetNodeX = floor(tx).toInt()
        val targetNodeY = floor(ty).toInt()
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

        // Ladder model collision
        val ladderCheckX = floor(tx).toInt()
        val ladderCheckY = floor(ty).toInt()
        for (ladderZ in intArrayOf(z, z - 1)) {
            val lNode = world.getNode(ladderCheckX, ladderCheckY, ladderZ) ?: continue
            val ladderTile = lNode.getTile(TileSlot.STAIRS) as? LadderTile ?: continue
            val facing = ladderTile.facingDirection()
            val localX = tx - ladderCheckX
            val localY = ty - ladderCheckY
            val modelPos = 0.90f
            val blocked = when (facing) {
                TileSlot.WALL_NORTH -> localY > modelPos
                TileSlot.WALL_SOUTH -> localY < (1f - modelPos)
                TileSlot.WALL_EAST  -> localX > modelPos
                TileSlot.WALL_WEST  -> localX < (1f - modelPos)
                else -> false
            }
            if (blocked) {
                if (log) println("[$LOG_TAG]   BLOCKED by ladder model at node($ladderCheckX,$ladderCheckY,$ladderZ) facing=$facing")
                return false
            }
        }

        // Prop collision
        if (log) println("[$LOG_TAG]   PROP COLLISION CHECK: actorBox=[$left..$right, $bottom..$top] z=$z propCount=${world.props.size}")
        for (prop in world.props) {
            val zDist = kotlin.math.abs(z.toFloat() - prop.z)
            if (zDist > 1f) continue
            val (hsX, hsY) = prop.rotatedHalfSizes()
            if (hsX <= 0f && hsY <= 0f) continue
            if (left < prop.x + hsX && right > prop.x - hsX &&
                bottom < prop.y + hsY && top > prop.y - hsY) {
                if (log) println("[$LOG_TAG]   BLOCKED by prop '${prop.name}' at (${prop.x},${prop.y},${prop.z})")
                return false
            }
        }

        return true
    }

    private fun isStairsSideBlocked(nx: Int, ny: Int, z: Int, entrySide: TileSlot, actorNodeX: Int, actorNodeY: Int): Boolean {
        if (nx == actorNodeX && ny == actorNodeY) return false

        val node = world.getNode(nx, ny, z)
        if (node != null) {
            val stairsTile = node.getTile(TileSlot.STAIRS) as? StairsTile
            if (stairsTile != null) {
                val facing = stairsTile.facingDirection()
                val oppositeFacing = when (facing) {
                    TileSlot.WALL_NORTH -> TileSlot.WALL_SOUTH
                    TileSlot.WALL_SOUTH -> TileSlot.WALL_NORTH
                    TileSlot.WALL_EAST  -> TileSlot.WALL_WEST
                    TileSlot.WALL_WEST  -> TileSlot.WALL_EAST
                    else -> facing
                }
                if (entrySide != oppositeFacing) return true
            }
        }

        val nodeBelow = world.getNode(nx, ny, z - 1)
        if (nodeBelow != null) {
            val stairsTileBelow = nodeBelow.getTile(TileSlot.STAIRS) as? StairsTile
            if (stairsTileBelow != null) {
                val facing = stairsTileBelow.facingDirection()
                if (entrySide != facing) return true
            }
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAVITY
    // ═══════════════════════════════════════════════════════════════════════════

    private fun applyGravity(actor: Actor) {
        val nx = floor(actor.position.x).toInt()
        val ny = floor(actor.position.y).toInt()
        var z = kotlin.math.round(actor.position.z).toInt()

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
