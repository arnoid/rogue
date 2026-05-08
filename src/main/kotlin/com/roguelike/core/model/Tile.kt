package com.roguelike.core.model

/**
 * Pure data interface for a tile placed on a WorldNode.
 * No LibGDX dependencies — rendering data lives in the view layer.
 */
interface Tile {
    val type: String
    fun isBlocking(): Boolean = false
    fun onInteract() {}
    val properties: Map<String, Any>
        get() = emptyMap()
}
