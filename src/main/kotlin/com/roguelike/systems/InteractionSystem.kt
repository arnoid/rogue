package com.roguelike.systems

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.math.Vector3
import com.roguelike.world.*

class InteractionSystem(private val world: World) {

    fun interact(actor: Actor, camera: Camera) {
        val facingNode = getFacingNode(actor, camera) ?: return
        
        // 1. Check for items to pick up
        if (facingNode.items.isNotEmpty()) {
            val item = facingNode.items.removeAt(0)
            actor.inventory.add(item)
            com.badlogic.gdx.Gdx.app.log("Interaction", "Picked up: ${item.name}")
            return
        }

        // 2. Check for door logic
        val hasDoorTag = facingNode.tags.any { it == WorldNode.Tags.DOOR_MANUAL || it == WorldNode.Tags.DOOR_KEY || it == WorldNode.Tags.DOOR_TOGGLE }
        if (hasDoorTag) {
            handleDoorInteraction(actor, facingNode)
            return
        }

        // 3. Check for toggle logic
        if (facingNode.tags.contains(WorldNode.Tags.TOGGLE)) {
            handleToggleInteraction(facingNode)
        }
    }

    private fun getFacingNode(actor: Actor, camera: Camera): WorldNode? {
        // Find which direction on X-Y plane the camera is facing
        val dir = Vector3(camera.direction).set(camera.direction.x, camera.direction.y, 0f).nor()
        
        // Find which cardinal direction we are mostly facing
        val targetX = if (Math.abs(dir.x) > Math.abs(dir.y)) {
            actor.position.x + Math.signum(dir.x)
        } else {
            actor.position.x
        }
        
        val targetY = if (Math.abs(dir.y) >= Math.abs(dir.x)) {
            actor.position.y + Math.signum(dir.y)
        } else {
            actor.position.y
        }

        val node = world.getNode(Math.round(targetX), Math.round(targetY), Math.round(actor.position.z))
        com.badlogic.gdx.Gdx.app.log("Interaction", "Facing node at: (${Math.round(targetX)}, ${Math.round(targetY)}, ${Math.round(actor.position.z)})")
        return node
    }

    private fun handleDoorInteraction(actor: Actor, node: WorldNode) {
        val doorTile = node.tiles.filterIsInstance<DoorTile>().firstOrNull() ?: return

        if (doorTile.isOpen) {
            toggleDoorWithSync(node, doorTile)
            return
        }

        if (node.tags.contains(WorldNode.Tags.DOOR_KEY)) {
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
                        com.badlogic.gdx.Gdx.app.log("Interaction", "Locked! Missing key: $keyName")
                        break
                    }
                }

                if (allPresent) {
                    keysToConsume.forEach { actor.inventory.remove(it) }
                    toggleDoorWithSync(node, doorTile)
                }
                return
            }
        }

        if (node.tags.contains(WorldNode.Tags.DOOR_MANUAL)) {
            toggleDoorWithSync(node, doorTile)
        }
    }

    private fun toggleDoorWithSync(node: WorldNode, doorTile: DoorTile) {
        val newState = !doorTile.isOpen
        doorTile.isOpen = newState
        
        // Sync with adjacent doors in X-Y plane
        val neighbors = listOf(
            world.getNode(node.x + 1, node.y, node.z),
            world.getNode(node.x - 1, node.y, node.z),
            world.getNode(node.x, node.y + 1, node.z),
            world.getNode(node.x, node.y - 1, node.z)
        )
        
        neighbors.forEach { neighbor ->
            neighbor?.tiles?.filterIsInstance<DoorTile>()?.forEach { adjDoor ->
                if (adjDoor.isOpen != newState) {
                    adjDoor.isOpen = newState
                }
            }
        }
    }

    private fun handleToggleInteraction(node: WorldNode) {
        world.associations.filter { it.target == node && it.type == "toggle" }.forEach { assoc ->
            val doorTile = assoc.source.tiles.filterIsInstance<DoorTile>().firstOrNull()
            if (doorTile != null) {
                toggleDoorWithSync(assoc.source, doorTile)
            }
        }
    }
}
