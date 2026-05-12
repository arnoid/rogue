package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*
import kotlin.math.abs
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

            // Check each door slot on this node
            for (slot in node.doorSlots) {
                if (!node.isManualDoor(slot)) continue
                val tile = node.getTile(slot) ?: continue
                // Calculate distance from actor to this edge
                val edgeDist = distanceToEdge(ax, ay, x, y, slot)
                if (edgeDist <= doorInteractRange) {
                    candidates.add(DoorCandidate(node, slot, edgeDist))
                }
            }
        }

        // Toggle the closest door
        val closest = candidates.minByOrNull { it.dist } ?: return false
        val tile = closest.node.getTile(closest.slot) ?: return false
        tile.onInteract()
        val state = if (tile.isBlocking()) "closed" else "opened"
        logger.log("Interaction", "Door $state at (${closest.node.x},${closest.node.y},${closest.node.z}) ${closest.slot}")
        return true
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
