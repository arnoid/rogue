package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Moves actors through the world, enforcing box-based collision
 * with support for multi-level stairs and gravity.
 *
 * Implements the rules defined in `game_rules.md`.
 *
 * ## Z model
 * - Each integer Z level is one "storey".
 * - Stairs occupy a node at their *base* Z and smoothly interpolate the
 *   actor's Z from `baseZ` to `baseZ + 1` depending on XY position.
 * - Gravity snaps the actor down to the highest solid surface at or below
 *   their current Z when they are **not** on stairs.
 *
 * ## Collision model
 * - The actor has a square collision box of half-size [Actor.collisionSize].
 * - Each of the four corners is mapped to a grid node via `round()`.
 * - A corner is blocked when **any** tile on its node is blocking, checked
 *   at both `zFloor` and `zCeil` when Z is fractional.
 *
 * ## Gravity model
 * - Walkable node (floor, stairs, open door) → actor stands on it.
 * - Blocking node (wall, corner, closed door) → actor stands *on top* (z + 1).
 * - Empty node → actor keeps falling.
 * - Gravity runs every frame, even when the actor is not moving.
 */
class MovementSystem(private val world: World) {

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Move [actor] by [moveDir] (unit-ish direction), scaled by [delta] × [speed].
     * Resolves XY collision, then updates Z (stairs / gravity).
     *
     * Gravity and stair interpolation run even when [moveDir] is zero,
     * so the actor falls correctly when idle.
     *
     * See game_rules.md §2 — Movement Logic
     */
    fun move(actor: Actor, moveDir: Vec3, delta: Float, speed: Float) {
        val prevPos = Vec3(actor.position)

        // ── XY movement (only when there is input) ──────────────────────
        if (!moveDir.isZero) {
            val dir = Vec3(moveDir).nor()
            val step = Vec3(dir).scl(delta * speed)

            actor.facingDirection.set(dir.x, dir.y, 0f)

            val nextX = actor.position.x + step.x
            val nextY = actor.position.y + step.y

            // §5.2 — Door escape: skip a closed door the actor overlaps
            //        if moving away from it
            val skipNode = findOverlappingClosedDoorNode(actor, dir)

            // §2 step 6 — Full move → X slide → Y slide → blocked
            if (canMove(nextX, nextY, actor.position.z, actor.collisionSize, skipNode)) {
                actor.position.x = nextX
                actor.position.y = nextY
            } else if (canMove(nextX, actor.position.y, actor.position.z, actor.collisionSize, skipNode)) {
                actor.position.x = nextX
            } else if (canMove(actor.position.x, nextY, actor.position.z, actor.collisionSize, skipNode)) {
                actor.position.y = nextY
            } else {
                println("[Move BLOCKED] pos=(${actor.position.x},${actor.position.y},${actor.position.z}) dir=(${dir.x},${dir.y}) nextXY=($nextX,$nextY)")
            }
        }

        // ── Z resolution (always runs — §4.1, §7 step 2d) ──────────────
        resolveZ(actor)

        // Log significant Z changes
        if (actor.position.z != prevPos.z) {
            val nx = round(actor.position.x).toInt()
            val ny = round(actor.position.y).toInt()
            val node = world.getNode(nx, ny, floor(actor.position.z.toDouble()).toInt())
            val tiles = node?.tiles?.map { it.type } ?: emptyList()
            println("[ResolveZ] pos=(${actor.position.x},${actor.position.y}) z: ${prevPos.z} -> ${actor.position.z} node($nx,$ny,${floor(actor.position.z.toDouble()).toInt()}) tiles=$tiles")
        }
    }

    /**
     * Public helper used by InteractionSystem.
     * See game_rules.md §5.2 — Door Escape Rule
     */
    fun isEscapingClosedDoor(actor: Actor, moveDir: Vec3): Boolean {
        return findOverlappingClosedDoorNode(actor, moveDir) != null
    }

    // ------------------------------------------------------------------ //
    //  Z resolution: stairs + gravity  (§4.1)                             //
    // ------------------------------------------------------------------ //

    /**
     * Determines the actor's Z after an XY move:
     * 1. If the actor is on a stairs node at `floor(z)` → interpolate Z, skip gravity.
     * 2. Otherwise → apply gravity to find solid ground.
     * 3. If gravity landed on stairs → apply stair interpolation.
     *
     * See game_rules.md §4.1 — Resolution Order
     */
    private fun resolveZ(actor: Actor) {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()

        // PRIORITY 1: already on stairs at floor(z) → stair interpolation owns Z
        val baseZ = floor(actor.position.z.toDouble()).toInt()
        if (hasStairs(nx, ny, baseZ)) {
            applyStairs(actor, nx, ny, baseZ)
            return
        }

        // PRIORITY 1b: entering stairs from the top — actor at integer Z,
        // stairs one level below connect up to this Z
        if (actor.position.z == baseZ.toFloat() && hasStairs(nx, ny, baseZ - 1)) {
            applyStairs(actor, nx, ny, baseZ - 1)
            return
        }

        // PRIORITY 2: apply gravity
        applyGravity(actor, nx, ny)

        // PRIORITY 3: if gravity landed us on stairs, interpolate
        val landedZ = floor(actor.position.z.toDouble()).toInt()
        if (hasStairs(nx, ny, landedZ)) {
            applyStairs(actor, nx, ny, landedZ)
            val snappedZ = round(actor.position.z).toInt()
            if (snappedZ > landedZ) {
                actor.position.z = snappedZ.toFloat()
            }
        }
    }

    /** See game_rules.md §3 — Stair Interaction */
    private fun hasStairs(nx: Int, ny: Int, z: Int): Boolean {
        val node = world.getNode(nx, ny, z) ?: return false
        return node.tiles.any { it.type.startsWith("Stairs") }
    }

    /**
     * Interpolate actor Z based on position within the stair tile.
     * `t` goes from 0 (bottom / low edge) to 1 (top / high edge).
     *
     * See game_rules.md §3.1 — Stair Model
     */
    private fun applyStairs(actor: Actor, nx: Int, ny: Int, stairBaseZ: Int) {
        val node = world.getNode(nx, ny, stairBaseZ) ?: return
        val stair = node.tiles.firstOrNull { it.type.startsWith("Stairs") } ?: return

        val ox = actor.position.x - nx.toFloat()
        val oy = actor.position.y - ny.toFloat()

        val t = when (stair.type) {
            "StairsNTile" -> oy + 0.5f   // south edge = bottom, north edge = top
            "StairsSTile" -> 0.5f - oy   // north edge = bottom, south edge = top
            "StairsETile" -> ox + 0.5f   // west edge = bottom, east edge = top
            "StairsWTile" -> 0.5f - ox   // east edge = bottom, west edge = top
            else -> 0f
        }.coerceIn(0f, 1f)

        actor.position.z = stairBaseZ.toFloat() + t
    }

    /**
     * Drop the actor to the correct Z level.
     *
     * See game_rules.md §4.2 — Gravity Scan
     * See game_rules.md §4.3 — Gravity Rules Summary
     */
    private fun applyGravity(actor: Actor, nx: Int, ny: Int) {
        var z = round(actor.position.z).toInt()

        while (z >= 0) {
            val node = world.getNode(nx, ny, z)
            if (node != null && node.tiles.isNotEmpty()) {
                if (node.tiles.none { it.isBlocking() }) {
                    actor.position.z = z.toFloat()
                    return
                } else {
                    actor.position.z = (z + 1).toFloat()
                    return
                }
            }
            z--
        }

        actor.position.z = 0f
    }

    // ------------------------------------------------------------------ //
    //  Collision  (§2.1, §2.2)                                            //
    // ------------------------------------------------------------------ //

    /**
     * Can the actor's collision box at (targetX, targetY, targetZ) fit
     * without overlapping any blocking node?
     *
     * Checks all four corners of the actor's box. For each corner, checks
     * the node at `zFloor` (with stairs bypass) and, if Z is fractional,
     * also the node at `zCeil` (without stairs bypass).
     *
     * See game_rules.md §2.1 — Collision Check
     */
    private fun canMove(
        targetX: Float, targetY: Float, targetZ: Float,
        size: Float, skipNode: WorldNode? = null
    ): Boolean {
        val corners = arrayOf(
            targetX - size to targetY - size,   // SW
            targetX + size to targetY - size,   // SE
            targetX - size to targetY + size,   // NW
            targetX + size to targetY + size    // NE
        )

        val zFloor = floor(targetZ.toDouble()).toInt()
        val zCeil = ceil(targetZ.toDouble()).toInt()
        val onStairs = zFloor != zCeil

        // When on stairs (fractional Z), only check the relevant Z level:
        // - Upper half (z > base+0.5) → check zCeil only (player is effectively at the upper level)
        // - Lower half (z ≤ base+0.5) → check zFloor only (player is effectively at the lower level)
        val checkZ = if (onStairs) {
            if (targetZ - zFloor > 0.5f) zCeil else zFloor
        } else {
            zFloor
        }

        for ((fx, fy) in corners) {
            val cx = round(fx).toInt()
            val cy = round(fy).toInt()

            // §6.3 — Out-of-bounds check
            if (cx < 0 || cx >= world.width || cy < 0 || cy >= world.height) {
                println("[canMove BLOCKED] OOB cx=$cx cy=$cy target=($targetX,$targetY,$targetZ)")
                return false
            }

            if (!isPassable(cx, cy, checkZ, skipNode)) {
                val node = world.getNode(cx, cy, checkZ)
                println("[canMove BLOCKED] notPassable($cx,$cy,$checkZ) tiles=${node?.tiles?.map{it.type}} target=($targetX,$targetY,$targetZ)")
                return false
            }
        }

        // Ground check: verify the actor's center has a solid surface.
        // Skip if the center node has stairs (actor is on stairs = has ground).
        val centerX = round(targetX).toInt()
        val centerY = round(targetY).toInt()
        val centerNode = world.getNode(centerX, centerY, zFloor)
        val onStairsNode = centerNode != null && centerNode.tiles.any { it.type.startsWith("Stairs") }
        if (!onStairsNode && !hasSolidGround(centerX, centerY, checkZ, targetX, targetY)) {
            println("[canMove BLOCKED] noGround($centerX,$centerY,$checkZ) target=($targetX,$targetY,$targetZ)")
            return false
        }

        return true
    }

    /**
     * Returns `true` if there is a walkable surface that supports the actor at level [z].
     * - Walkable node at z (floor, stairs, open door) → supported
     * - Blocking node at z-1 (wall) → actor stands on top = z → supported
     * - Stairs at z-1, but only if actor is at the HIGH end (t > 0.5) → supported
     */
    private fun hasSolidGround(x: Int, y: Int, z: Int, actorX: Float, actorY: Float): Boolean {
        // Walkable surface at z
        val node = world.getNode(x, y, z)
        if (node != null && node.tiles.isNotEmpty() && node.tiles.none { it.isBlocking() }) return true
        // Standing on top of blocking surface at z-1
        val below = world.getNode(x, y, z - 1)
        if (below != null && below.tiles.isNotEmpty()) {
            if (below.tiles.any { it.isBlocking() }) return true
            // Stairs at z-1: only allow if actor is at the high end (t > 0.5)
            val stair = below.tiles.firstOrNull { it.type.startsWith("Stairs") }
            if (stair != null) {
                val ox = actorX - x.toFloat()
                val oy = actorY - y.toFloat()
                val t = when (stair.type) {
                    "StairsNTile" -> oy + 0.5f
                    "StairsSTile" -> 0.5f - oy
                    "StairsETile" -> ox + 0.5f
                    "StairsWTile" -> 0.5f - ox
                    else -> 0f
                }.coerceIn(0f, 1f)
                if (t > 0.5f) return true
            }
        }
        return false
    }

    /**
     * Returns `true` when the node at (cx, cy, z) does **not** block the actor.
     *
     * See game_rules.md §2.2 — isPassable Summary
     */
    private fun isPassable(cx: Int, cy: Int, z: Int, skipNode: WorldNode?): Boolean {
        val node = world.getNode(cx, cy, z) ?: return true          // open air
        if (node === skipNode) return true                           // door escape
        if (node.tiles.isEmpty()) return true                       // empty node
        return node.tiles.none { it.isBlocking() }                  // blocked if any tile blocks
    }

    // ------------------------------------------------------------------ //
    //  Closed-door escape  (§5.2)                                         //
    // ------------------------------------------------------------------ //

    /**
     * If the actor's collision box overlaps a closed-door node and the actor
     * is moving *away* from that door, return the node so collision can skip it.
     *
     * See game_rules.md §5.2 — Door Escape Rule
     * See game_rules.md §6.6 — Edge Case: Door Closes on Actor
     */
    private fun findOverlappingClosedDoorNode(actor: Actor, moveDir: Vec3): WorldNode? {
        val size = actor.collisionSize
        val cz = round(actor.position.z).toInt()

        val minX = round(actor.position.x - size).toInt()
        val maxX = round(actor.position.x + size).toInt()
        val minY = round(actor.position.y - size).toInt()
        val maxY = round(actor.position.y + size).toInt()

        for (nx in minX..maxX) {
            for (ny in minY..maxY) {
                val node = world.getNode(nx, ny, cz) ?: continue
                val door = node.tiles.firstOrNull { it.slot == TileSlot.DOOR && it.isBlocking() } ?: continue

                val isH = door.type.contains("Horizontal", ignoreCase = true)
                val isV = door.type.contains("Vertical", ignoreCase = true)

                val ox = actor.position.x - nx.toFloat()
                val oy = actor.position.y - ny.toFloat()

                // "Moving away" = actor on positive side moving positive, or negative side moving negative
                if (isH && ((oy >= 0 && moveDir.y > 0) || (oy < 0 && moveDir.y < 0))) return node
                if (isV && ((ox >= 0 && moveDir.x > 0) || (ox < 0 && moveDir.x < 0))) return node
            }
        }
        return null
    }
}
