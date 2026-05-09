package com.roguelike.core.model

/**
 * Represents a single node (cell) in the 3-D game world grid.
 * No LibGDX dependencies.
 *
 * Each node holds at most one [Tile] per [TileSlot] (floor, wall, door, interaction).
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
        const val ITEM_KEY     = "item_key"
    }

    /** Slot-based tile storage: at most one tile per [TileSlot]. */
    private val tileSlots = mutableMapOf<TileSlot, Tile>()

    /** Read-only view of all tiles currently placed on this node. */
    val tiles: Collection<Tile> get() = tileSlots.values

    /** Sets a tile into its slot, replacing any existing tile in that slot. Returns the previous tile or null. */
    fun setTile(tile: Tile): Tile? = tileSlots.put(tile.slot, tile)

    /** Gets the tile in the given slot, or null. */
    fun getTile(slot: TileSlot): Tile? = tileSlots[slot]

    /** Removes the tile in the given slot. Returns the removed tile or null. */
    fun removeTile(slot: TileSlot): Tile? = tileSlots.remove(slot)

    /** Removes a specific tile (by identity). Returns true if removed. */
    fun removeTile(tile: Tile): Boolean = if (tileSlots[tile.slot] === tile) { tileSlots.remove(tile.slot); true } else false

    /** Returns true if the given slot has a tile. */
    fun hasTile(slot: TileSlot): Boolean = tileSlots.containsKey(slot)

    /** Returns true if any tile of the given type string is present. */
    fun hasTileType(type: String): Boolean = tileSlots.values.any { it.type == type }

    /** Removes tiles matching the given type. Returns true if any were removed. */
    fun removeTileByType(type: String): Boolean {
        val keys = tileSlots.entries.filter { it.value.type == type }.map { it.key }
        keys.forEach { tileSlots.remove(it) }
        return keys.isNotEmpty()
    }

    val items = mutableListOf<Item>()

    /** Metadata tags for this node. */
    val tags = mutableSetOf<String>()

    /** Resets the node to an empty state. */
    fun clear() {
        tileSlots.clear()
        items.clear()
        tags.clear()
    }
}
