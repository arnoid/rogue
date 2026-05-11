package com.roguelike.core.model

/**
 * Identifies the role a model plays on a [WorldNode].
 */
enum class TileSlot {
    FLOOR,
    WALL_NORTH,
    WALL_SOUTH,
    WALL_EAST,
    WALL_WEST,
    STAIRS
}

/**
 * Pure data interface for a tile placed on a WorldNode.
 * No LibGDX dependencies — rendering data lives in the view layer.
 */
interface Tile {
    val type: String
    /** Which slot this tile occupies on a WorldNode. */
    val slot: TileSlot
    /** When non-null, the tile renders at this fixed Z instead of the node's grid Z. */
    val fixedZ: Float?
        get() = null
    fun isBlocking(): Boolean = false
    fun onInteract() {}
    val properties: Map<String, Any>
        get() = emptyMap()
}
