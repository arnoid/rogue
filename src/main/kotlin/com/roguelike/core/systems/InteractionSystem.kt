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
        val facingNode = getFacingNode(actor, cameraDir) ?: return

        // 1. Check for items to pick up
        if (facingNode.items.isNotEmpty()) {
            val item = facingNode.items.removeAt(0)
            actor.inventory.add(item)
            logger.log("Interaction", "Picked up: ${item.name}")
            return
        }

        // 2. Check for door logic (detect door tiles directly)
        val hasDoorTile = facingNode.tiles.any { isDoorTile(it) }
        if (hasDoorTile) {
            handleDoorInteraction(actor, facingNode)
            return
        }

        // 3. Check for toggle logic
        if (facingNode.tags.contains(WorldNode.Tags.TOGGLE)) {
            handleToggleInteraction(facingNode)
        }
    }

    private fun getFacingNode(actor: Actor, cameraDir: Vec3): WorldNode? {
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

        val node = world.getNode(
            Math.round(targetX),
            Math.round(targetY),
            Math.round(actor.position.z)
        )
        logger.log("Interaction",
            "Facing node at: (${Math.round(targetX)}, ${Math.round(targetY)}, ${Math.round(actor.position.z)})")
        return node
    }

    /**
     * Check if a tile is a door tile by examining if it blocks when "closed"
     * and supports onInteract toggling. Uses duck-typing via the Tile interface
     * properties to avoid importing world-layer classes.
     */
    private fun isDoorTile(tile: Tile): Boolean {
        // We detect door tiles by checking the type string prefix
        return tile.type.contains("Door", ignoreCase = true)
    }

    private fun handleDoorInteraction(actor: Actor, node: WorldNode) {
        val doorTile = node.tiles.firstOrNull { isDoorTile(it) } ?: return

        // Doors tagged as toggle-only cannot be opened directly
        if (node.tags.contains(WorldNode.Tags.DOOR_TOGGLE)) {
            logger.log("Interaction", "This door can only be opened by a toggle.")
            return
        }

        val doorIsOpen = !doorTile.isBlocking()

        if (doorIsOpen) {
            doorTile.onInteract() // close it
            syncAdjacentDoors(node, doorTile.isBlocking())
            return
        }

        // Check for key-locked doors via associations
        val requiredAssocs = world.associations.filter { it.source == node && it.type == "key" }
        if (requiredAssocs.isNotEmpty()) {
            val requiredKeyNames = requiredAssocs.map { it.data ?: "Key" }
            val tempInventory = actor.inventory.toMutableList()
            val keysToConsume = mutableListOf<Item>()
            var allPresent = true

            for (keyName in requiredKeyNames) {
                val keyInInv = tempInventory.find { it.name == keyName || (keyName == "Key" && it is KeyItem) }
                if (keyInInv != null) {
                    tempInventory.remove(keyInInv)
                    keysToConsume.add(keyInInv)
                } else {
                    allPresent = false
                    logger.log("Interaction", "Locked! Missing key: $keyName")
                    break
                }
            }

            if (allPresent) {
                keysToConsume.forEach { actor.inventory.remove(it) }
                doorTile.onInteract()
                syncAdjacentDoors(node, doorTile.isBlocking())
            }
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
        world.associations.filter { it.target == node && it.type == "toggle" }.forEach { assoc ->
            val doorTile = assoc.source.tiles.firstOrNull { isDoorTile(it) }
            if (doorTile != null) {
                doorTile.onInteract()
                syncAdjacentDoors(assoc.source, doorTile.isBlocking())
            }
        }
    }
}

