package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.*

/**
 * Handles player ↔ world interactions (item pick-up, doors, toggles).
 * No LibGDX dependency — logging is delegated to an injected [GameLogger].
 */
class InteractionSystem(
    private val world: World,
    private val logger: GameLogger = GameLogger.NOOP
) {

    fun interact(actor: Actor, cameraDir: Vec3) {
        val nx = Math.round(actor.position.x)
        val ny = Math.round(actor.position.y)
        val nz = Math.round(actor.position.z)
        // Also check the level below — the actor may be standing on top of walls
        // at z+1 while the interactive tile is at z
        val nzBelow = nz - 1

        // 0. Check if actor is standing on a node with items — pick up, no facing needed
        val currentNode = world.getNode(nx, ny, nz) ?: world.getNode(nx, ny, nzBelow)
        if (currentNode != null && currentNode.items.isNotEmpty()) {
            val item = currentNode.items.removeAt(0)
            actor.inventory.add(item)
            logger.log("Interaction", "Picked up: ${item.name}")

            if (item is KeyItem && currentNode.items.none { it is KeyItem }) {
                currentNode.tags.remove(WorldNode.Tags.ITEM_KEY)
            }
            return
        }

        // 1. Check if actor is standing on a toggle — no facing needed
        //    Check both current Z and level below
        val toggleNode = when {
            currentNode != null && currentNode.tags.contains(WorldNode.Tags.TOGGLE) -> currentNode
            else -> {
                val below = world.getNode(nx, ny, nzBelow)
                if (below != null && below.tags.contains(WorldNode.Tags.TOGGLE)) below else null
            }
        }
        if (toggleNode != null) {
            handleToggleInteraction(toggleNode)
            return
        }

        // 2. Check if actor is standing on a door node — interact ignoring facing
        val doorNode = when {
            currentNode != null && currentNode.tiles.any { isDoorTile(it) } -> currentNode
            else -> {
                val below = world.getNode(nx, ny, nzBelow)
                if (below != null && below.tiles.any { isDoorTile(it) }) below else null
            }
        }
        if (doorNode != null) {
            handleDoorInteraction(actor, doorNode)
            return
        }

        // 3. Check facing node at current Z and level below
        val facingNode = getFacingNode(actor, cameraDir, nz)
            ?: getFacingNode(actor, cameraDir, nzBelow)
            ?: return

        val hasDoorTile = facingNode.tiles.any { isDoorTile(it) }
        if (hasDoorTile) {
            handleDoorInteraction(actor, facingNode)
            return
        }

        if (facingNode.tags.contains(WorldNode.Tags.TOGGLE)) {
            handleToggleInteraction(facingNode)
        }
    }

    private fun getFacingNode(actor: Actor, cameraDir: Vec3, z: Int): WorldNode? {
        val dir = Vec3(cameraDir.x, cameraDir.y, 0f).nor()

        val targetX = if (Math.abs(dir.x) > Math.abs(dir.y)) {
            actor.position.x + dir.signX()
        } else {
            actor.position.x
        }

        val targetY = if (Math.abs(dir.y) >= Math.abs(dir.x)) {
            actor.position.y + dir.signY()
        } else {
            actor.position.y
        }

        val node = world.getNode(Math.round(targetX), Math.round(targetY), z)
        if (node != null && (node.tiles.isNotEmpty() || node.tags.isNotEmpty())) {
            return node
        }
        return null
    }

    /**
     * Check if a tile is a door tile by examining if it blocks when "closed"
     * and supports onInteract toggling. Uses duck-typing via the Tile interface
     * properties to avoid importing world-layer classes.
     */
    private fun isDoorTile(tile: Tile): Boolean {
        // Match only actual door tiles (DoorHorizontalTile / DoorVerticalTile),
        // NOT wall doorways (WallDoorwayHorizontalTile / WallDoorwayVerticalTile)
        return tile.slot == TileSlot.DOOR
    }

    private fun handleDoorInteraction(actor: Actor, node: WorldNode) {
        val doorTile = node.tiles.firstOrNull { isDoorTile(it) } ?: return

        // Doors tagged as toggle-only cannot be opened directly
        if (node.tags.contains(WorldNode.Tags.DOOR_TOGGLE)) {
            logger.log("Interaction", "This door can only be opened by a toggle.")
            return
        }

        // Doors tagged as key-locked: check if actor has the linked key in inventory
        if (node.tags.contains(WorldNode.Tags.DOOR_KEY)) {
            val keyAssocs = world.associations.filter { it.source == node && it.type == "key" }
            if (keyAssocs.isNotEmpty()) {
                val hasAllKeys = keyAssocs.all { assoc ->
                    val requiredName = assoc.data ?: "Key"
                    actor.inventory.any { it.name == requiredName || (requiredName == "Key" && it is KeyItem) }
                }
                if (hasAllKeys) {
                    doorTile.onInteract()
                    syncAdjacentDoors(node, doorTile.isBlocking())
                } else {
                    val missing = keyAssocs.firstOrNull { assoc ->
                        val requiredName = assoc.data ?: "Key"
                        actor.inventory.none { it.name == requiredName || (requiredName == "Key" && it is KeyItem) }
                    }
                    logger.log("Interaction", "Locked! Missing key: ${missing?.data ?: "Key"}")
                }
            } else {
                logger.log("Interaction", "This door requires a key but none is linked.")
            }
            return
        }

        val doorIsOpen = !doorTile.isBlocking()

        if (doorIsOpen) {
            doorTile.onInteract() // close it
            syncAdjacentDoors(node, doorTile.isBlocking())
            return
        }

        // Default: manual door — just open it
        doorTile.onInteract()
        syncAdjacentDoors(node, doorTile.isBlocking())
    }

    private fun syncAdjacentDoors(node: WorldNode, shouldBlock: Boolean) {
        val neighbors = listOf(
            world.getNode(node.x + 1, node.y, node.z),
            world.getNode(node.x - 1, node.y, node.z),
            world.getNode(node.x, node.y + 1, node.z),
            world.getNode(node.x, node.y - 1, node.z)
        )

        neighbors.forEach { neighbor ->
            neighbor?.tiles?.filter { isDoorTile(it) }?.forEach { adjDoor ->
                // If adjacent door's blocking state doesn't match, toggle it
                if (adjDoor.isBlocking() != shouldBlock) adjDoor.onInteract()
            }
        }
    }

    private fun handleToggleInteraction(node: WorldNode) {
        val matchingAssocs = world.associations.filter { it.target == node && it.type == "toggle" }
        logger.log("Interaction", "Toggle at (${node.x},${node.y},${node.z}): found ${matchingAssocs.size} associations (total: ${world.associations.size})")
        matchingAssocs.forEach { assoc ->
            val doorTile = assoc.source.tiles.firstOrNull { isDoorTile(it) }
            logger.log("Interaction", "  → door at (${assoc.source.x},${assoc.source.y},${assoc.source.z}): tile=$doorTile")
            if (doorTile != null) {
                doorTile.onInteract()
                syncAdjacentDoors(assoc.source, doorTile.isBlocking())
            }
        }
    }
}

