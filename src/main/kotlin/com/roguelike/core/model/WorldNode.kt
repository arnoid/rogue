package com.roguelike.core.model

/**
 * Represents a single node (cell) in the 3-D game world grid.
 * No LibGDX dependencies.
 */
class WorldNode(val x: Int, val y: Int, val z: Int) {
    object Tags {
        const val PLAYER_SPAWN = "player_spawn"
        const val ENEMY_SPAWN  = "enemy_spawn"
        const val ITEM_SPAWN   = "item_spawn"
        const val EXIT         = "exit"
        const val DOOR_MANUAL  = "door_manual"
        const val DOOR_KEY     = "door_key"
        const val DOOR_TOGGLE  = "door_toggle"
        const val TOGGLE       = "toggle"
    }

    val tiles = mutableListOf<Tile>()
    val items = mutableListOf<Item>()

    /** Metadata tags for this node. */
    val tags = mutableSetOf<String>()

    /** Resets the node to an empty state. */
    fun clear() {
        tiles.clear()
        items.clear()
        tags.clear()
    }
}
