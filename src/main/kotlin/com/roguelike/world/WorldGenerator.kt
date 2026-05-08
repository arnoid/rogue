package com.roguelike.world

import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.utils.ModelLoader

/**
 * Handles the initial generation and population of the game world.
 */
class WorldGenerator(private val world: World, private val modelLoader: ModelLoader) {

    /**
     * Generates the world layout, including floor, walls, and spawn points.
     */
    fun generate() {
        // Player spawn point
        world.getNode(2, 2, 0)?.let { spawnNode ->
            world.addTag(spawnNode, NodeTags.PLAYER_SPAWN)
        }

        // Fill the ground floor with floor tiles
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                world.getNode(x, y, 0)?.let { node ->
                    node.setTile(modelLoader.createFloorTile())
                }
            }
        }

        // Add corner walls
        world.getNode(0, 0, 0)?.let { corner ->
            corner.setTile(modelLoader.createCornerSWTile())
        }
        world.getNode(world.width - 1, 0, 0)?.let { corner ->
            corner.setTile(modelLoader.createCornerSETile())
        }
        world.getNode(0, world.height - 1, 0)?.let { corner ->
            corner.setTile(modelLoader.createCornerNWTile())
        }
        world.getNode(world.width - 1, world.height - 1, 0)?.let { corner ->
            corner.setTile(modelLoader.createCornerNETile())
        }

    }
}
