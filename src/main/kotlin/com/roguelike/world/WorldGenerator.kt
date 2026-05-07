package com.roguelike.world

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
            world.addTag(spawnNode, WorldNode.Tags.PLAYER_SPAWN)
        }

        // Fill the ground floor with floor tiles
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                world.getNode(x, y, 0)?.let { node ->
                    node.tiles.add(modelLoader.createFloorTile())
                }
            }
        }

        // Add corner walls
        world.getNode(0, 0, 0)?.let { corner ->
            corner.tiles.add(modelLoader.createCornerSWTile())
        }
        world.getNode(world.width - 1, 0, 0)?.let { corner ->
            corner.tiles.add(modelLoader.createCornerESTile())
        }
        world.getNode(0, world.height - 1, 0)?.let { corner ->
            corner.tiles.add(modelLoader.createCornerWNTile())
        }
        world.getNode(world.width - 1, world.height - 1, 0)?.let { corner ->
            corner.tiles.add(modelLoader.createCornerNETile())
        }

    }
}
