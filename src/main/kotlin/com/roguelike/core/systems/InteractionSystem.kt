package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Handles player ↔ world interactions (item pick-up, door toggle).
 * No LibGDX dependency — logging is delegated to an injected [GameLogger].
 */
class InteractionSystem(
    private val world: World,
    private val logger: GameLogger = GameLogger.NOOP
) {
    /** All actors in the world. */
    val actors = mutableListOf<Actor>()

    /** How close (in world units) the actor must be to an edge to interact with a door. */
    private val doorInteractRange = 1.2f

    /**
     * Doors whose close was deferred because an actor was in the way. They
     * stay open and are re-checked each [update] tick; once no actor overlaps
     * the wall line, the door is closed automatically.
     */
    private val pendingCloses = mutableSetOf<Pair<WorldNode, TileSlot>>()

    fun interact(actor: Actor, cameraDir: Vec3) {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        val nz = round(actor.position.z).toInt()

        // 1. Check if actor is standing on a node with items — pick up
        val currentNode = world.getNode(nx, ny, nz)
        if (currentNode != null && currentNode.items.isNotEmpty()) {
            val item = currentNode.items.removeAt(0)
            actor.inventory.add(item)
            logger.log("Interaction", "Picked up: ${item.name}")
            return
        }

        // 2. Check adjacent nodes (4-neighbour, same Z) — pick up the closest item
        val adjacent = listOf(
            nx - 1 to ny,
            nx + 1 to ny,
            nx to ny - 1,
            nx to ny + 1
        )
        for ((ax, ay) in adjacent) {
            val node = world.getNode(ax, ay, nz) ?: continue
            if (node.items.isEmpty()) continue
            val item = node.items.removeAt(0)
            actor.inventory.add(item)
            logger.log("Interaction", "Picked up (adjacent): ${item.name}")
            return
        }

        // 3. Check nearby edges for door_manual doors and toggle them
        if (tryInteractDoor(actor, nx, ny, nz)) return
    }

    /**
     * Drops [item] from the actor's inventory onto the actor's current grid cell.
     * The item's facing direction is set to the actor's current facing so that
     * directional light cones point where the actor was looking when dropped.
     *
     * @return true if the item was successfully placed; false if the actor isn't
     *         on a valid node or doesn't own the item.
     */
    fun drop(actor: Actor, item: Item): Boolean {
        if (item !in actor.inventory) return false
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        val nz = round(actor.position.z).toInt()
        val node = world.getNode(nx, ny, nz) ?: return false

        // Capture facing direction (planar) so re-picked-up items keep
        // a sensible default when dropped again.
        val f = actor.facingDirection
        val fx = f.x; val fy = f.y
        val len = kotlin.math.sqrt((fx * fx + fy * fy).toDouble()).toFloat()
        if (len > 0f) {
            item.facingX = fx / len
            item.facingY = fy / len
        } else {
            item.facingX = 0f
            item.facingY = 1f
        }

        actor.inventory.remove(item)
        node.items.add(item)
        logger.log("Interaction", "Dropped: ${item.name} at ($nx,$ny,$nz) facing=(${item.facingX},${item.facingY})")
        return true
    }

    /**
     * Scan the current node and adjacent nodes for door_manual tagged edges
     * within interaction range of the actor. Toggle the closest one.
     *
     * A manual door tag may live on either side of the wall (because the
     * MapEditor tags both sides when possible), but the actual [Tile]
     * instance lives on only one of the two nodes. We look on this node's
     * slot first, then on the adjacent node's opposite slot.
     */
    private fun tryInteractDoor(actor: Actor, nx: Int, ny: Int, nz: Int): Boolean {
        val ax = actor.position.x
        val ay = actor.position.y

        data class DoorCandidate(val node: WorldNode, val slot: TileSlot, val dist: Float)
        val candidates = mutableListOf<DoorCandidate>()

        // Check all nodes the actor could be near (current + 4 neighbors)
        val nodesToCheck = listOf(
            nx to ny,
            nx - 1 to ny,
            nx + 1 to ny,
            nx to ny - 1,
            nx to ny + 1
        )

        for ((x, y) in nodesToCheck) {
            val node = world.getNode(x, y, nz) ?: continue
            if (node.doorSlots.isNotEmpty()) {
                logger.log("Interaction", "  node($x,$y,$nz) doorSlots=${node.doorSlots} manualDoorSlots=${node.manualDoorSlots}")
            }
            // Check each door slot on this node
            for (slot in node.doorSlots) {
                // Must be able to find the door tile on either this slot or
                // the matching adjacent node's opposite slot.
                if (findDoorTile(node, slot) == null) {
                    logger.log("Interaction", "    slot=$slot no door tile found, skipping")
                    continue
                }
                val edgeDist = distanceToEdge(ax, ay, x, y, slot)
                logger.log("Interaction", "    slot=$slot edgeDist=$edgeDist range=$doorInteractRange")
                if (edgeDist <= doorInteractRange) {
                    candidates.add(DoorCandidate(node, slot, edgeDist))
                }
            }
        }

        logger.log("Interaction", "  candidates=${candidates.size}")
        // Toggle the closest door
        val closest = candidates.minByOrNull { it.dist } ?: return false
        return toggleDoor(closest.node, closest.slot)
    }

    /**
     * Toggles a door's open/closed state. Handles the case where the door
     * tile lives on the adjacent node's opposite wall slot (since door tiles
     * are placed on only one side, but tags may be on both). Returns true
     * if a door tile was successfully toggled.
     */
    fun toggleDoor(node: WorldNode, slot: TileSlot): Boolean {
        val tile = findDoorTile(node, slot) ?: return false
        if (tile.isBlocking()) {
            // Currently closed -> open it. Also cancel any pending close so the
            // door doesn't immediately try to slam shut again.
            tile.onInteract()
            pendingCloses.remove(node to slot)
            logger.log("Interaction", "Door opened at (${node.x},${node.y},${node.z}) $slot")
            return true
        }
        // Currently open -> requested to close.
        if (wouldCloseTrapActor(node, slot)) {
            // Defer: leave the door open and auto-close it as soon as the
            // actor moves out of the wall line.
            pendingCloses.add(node to slot)
            logger.log("Interaction", "Door close deferred at (${node.x},${node.y},${node.z}) $slot — actor in the way; will auto-close")
            return false
        }
        tile.onInteract()
        pendingCloses.remove(node to slot)
        logger.log("Interaction", "Door closed at (${node.x},${node.y},${node.z}) $slot")
        return true
    }

    /**
     * Per-frame tick. Performs two safety/QoL checks:
     *  1. Auto-opens any closed door whose wall line currently overlaps an
     *     actor's collision AABB (rescues a "stuck in door" actor).
     *  2. Auto-closes any door previously queued via a deferred close, once
     *     no actor overlaps its wall line anymore.
     */
    fun update(@Suppress("UNUSED_PARAMETER") dt: Float) {
        // 1. Rescue actors trapped inside a closed door.
        for (a in actors) {
            val nz = round(a.position.z).toInt()
            val nx = floor(a.position.x).toInt()
            val ny = floor(a.position.y).toInt()
            // Check the 4 walls of the actor's cell + the matching outer walls
            // of the 4 neighbors. We only need to look at door-tagged slots.
            val toCheck = listOf(
                Triple(nx, ny, TileSlot.WALL_EAST),
                Triple(nx, ny, TileSlot.WALL_WEST),
                Triple(nx, ny, TileSlot.WALL_NORTH),
                Triple(nx, ny, TileSlot.WALL_SOUTH),
                Triple(nx - 1, ny, TileSlot.WALL_EAST),
                Triple(nx + 1, ny, TileSlot.WALL_WEST),
                Triple(nx, ny - 1, TileSlot.WALL_NORTH),
                Triple(nx, ny + 1, TileSlot.WALL_SOUTH),
            )
            for ((cx, cy, slot) in toCheck) {
                val node = world.getNode(cx, cy, nz) ?: continue
                if (!node.isDoor(slot)) continue
                val tile = findDoorTile(node, slot) ?: continue
                if (!tile.isBlocking()) continue
                if (wouldCloseTrapActor(node, slot)) {
                    tile.onInteract() // open
                    pendingCloses.add(node to slot) // re-close once clear
                    logger.log(
                        "Interaction",
                        "Door auto-opened (actor trapped) at (${node.x},${node.y},${node.z}) $slot — will auto-close"
                    )
                }
            }
        }

        // 2. Process pending closes.
        val it = pendingCloses.iterator()
        while (it.hasNext()) {
            val (node, slot) = it.next()
            val tile = findDoorTile(node, slot)
            if (tile == null) { it.remove(); continue }
            if (tile.isBlocking()) {
                // Already closed by some other path — drop from queue.
                it.remove(); continue
            }
            if (!wouldCloseTrapActor(node, slot)) {
                tile.onInteract() // close
                logger.log(
                    "Interaction",
                    "Door auto-closed (path clear) at (${node.x},${node.y},${node.z}) $slot"
                )
                it.remove()
            }
        }
    }

    /**
     * Returns true if closing the door on [node]/[slot] would intersect any
     * actor's collision AABB. The door occupies the wall line between two
     * adjacent cells; an actor's AABB straddles that line when its box
     * crosses the boundary AND overlaps the door's 1-unit extent.
     */
    private fun wouldCloseTrapActor(node: WorldNode, slot: TileSlot): Boolean {
        // Wall line coordinate and the perpendicular extent [lo..hi] of the door.
        // For WALL_EAST of node (nx,ny): vertical line at x = nx+1, extent y in [ny..ny+1]?
        // Cells are 1×1 with integer corners; cell (nx,ny) spans x in [nx..nx+1], y in [ny..ny+1].
        // Actually MovementSystem treats node coords as cell origins (floor(tx) -> ix), so a
        // wall on WALL_EAST of (ix,iy) is at x = ix+1, y in [iy..iy+1].
        val z = node.z
        when (slot) {
            TileSlot.WALL_EAST, TileSlot.WALL_WEST -> {
                val lineX = if (slot == TileSlot.WALL_EAST) (node.x + 1).toFloat() else node.x.toFloat()
                val loY = node.y.toFloat()
                val hiY = (node.y + 1).toFloat()
                for (a in actors) {
                    if (kotlin.math.abs(a.position.z - z) > 0.5f) continue
                    val s = a.collisionSize
                    val l = a.position.x - s; val r = a.position.x + s
                    val b = a.position.y - s; val t = a.position.y + s
                    if (l < lineX && r > lineX && t > loY && b < hiY) return true
                }
            }
            TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH -> {
                val lineY = if (slot == TileSlot.WALL_NORTH) (node.y + 1).toFloat() else node.y.toFloat()
                val loX = node.x.toFloat()
                val hiX = (node.x + 1).toFloat()
                for (a in actors) {
                    if (kotlin.math.abs(a.position.z - z) > 0.5f) continue
                    val s = a.collisionSize
                    val l = a.position.x - s; val r = a.position.x + s
                    val b = a.position.y - s; val t = a.position.y + s
                    if (b < lineY && t > lineY && r > loX && l < hiX) return true
                }
            }
            else -> return false
        }
        return false
    }

    /**
     * Try to open any closed manual door adjacent to [actor] (used by enemy
     * AI when pathfinding is blocked by a closed manual door). Returns true
     * if a door was opened.
     */
    fun tryOpenAdjacentManualDoor(actor: Actor): Boolean {
        val nx = round(actor.position.x).toInt()
        val ny = round(actor.position.y).toInt()
        val nz = round(actor.position.z).toInt()
        for ((x, y) in listOf(nx to ny, nx - 1 to ny, nx + 1 to ny, nx to ny - 1, nx to ny + 1)) {
            val node = world.getNode(x, y, nz) ?: continue
            for (slot in node.doorSlots) {
                if (!node.isManualDoor(slot)) continue
                val tile = findDoorTile(node, slot) ?: continue
                if (tile.isBlocking()) {
                    toggleDoor(node, slot)
                    return true
                }
            }
        }
        return false
    }

    /** Returns the door tile for [slot] on [node], or on the adjacent node's opposite slot. */
    private fun findDoorTile(node: WorldNode, slot: TileSlot): Tile? {
        node.getTile(slot)?.let { return it }
        val (ax, ay, opp) = when (slot) {
            TileSlot.WALL_NORTH -> Triple(node.x, node.y + 1, TileSlot.WALL_SOUTH)
            TileSlot.WALL_SOUTH -> Triple(node.x, node.y - 1, TileSlot.WALL_NORTH)
            TileSlot.WALL_EAST  -> Triple(node.x + 1, node.y, TileSlot.WALL_WEST)
            TileSlot.WALL_WEST  -> Triple(node.x - 1, node.y, TileSlot.WALL_EAST)
            else -> return null
        }
        return world.getNode(ax, ay, node.z)?.getTile(opp)
    }

    /**
     * Calculate the distance from actor position (ax, ay) to a wall edge on node (nx, ny).
     */
    private fun distanceToEdge(ax: Float, ay: Float, nx: Int, ny: Int, slot: TileSlot): Float {
        return when (slot) {
            TileSlot.WALL_NORTH -> abs(ay - (ny + 0.5f))
            TileSlot.WALL_SOUTH -> abs(ay - (ny - 0.5f))
            TileSlot.WALL_EAST  -> abs(ax - (nx + 0.5f))
            TileSlot.WALL_WEST  -> abs(ax - (nx - 0.5f))
            else -> Float.MAX_VALUE
        }
    }
}
