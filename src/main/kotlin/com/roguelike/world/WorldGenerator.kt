package com.roguelike.world

import com.roguelike.core.model.WorldNode.Tags as NodeTags

/**
 * Handles the initial generation and population of the game world.
 */
class WorldGenerator(private val world: World) {

    /**
     * Generates a basic world layout: floors on z=0, boundary walls, and a player spawn.
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
                    node.setTile(FloorTile())
                }
            }
        }

        // Add boundary walls
        for (x in 0 until world.width) {
            for (z in 0 until world.depth) {
                // South boundary (y=0)
                world.getNode(x, 0, z)?.setTile(WallSouthTile())
                // North boundary (y=height-1)
                world.getNode(x, world.height - 1, z)?.setTile(WallNorthTile())
            }
        }
        for (y in 0 until world.height) {
            for (z in 0 until world.depth) {
                // West boundary (x=0)
                world.getNode(0, y, z)?.setTile(WallWestTile())
                // East boundary (x=width-1)
                world.getNode(world.width - 1, y, z)?.setTile(WallEastTile())
            }
        }
    }
}
