package com.roguelike.core.model

/**
 * Categories of tiles that can occupy a single world node.
 * Each node holds at most one tile per slot.
 */
enum class TileSlot {
    FLOOR,
    WALL,
    DOOR,
    INTERACTION
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
