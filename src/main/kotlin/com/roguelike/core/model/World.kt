package com.roguelike.core.model

/**
 * The 3-D grid that represents the game world.
 *
 * All three dimensions must be divisible by 3 (e.g. 3×6×12, 6×6×6).
 * No LibGDX dependencies.
 */
class World(val width: Int, val height: Int, val depth: Int) {

    init {
        require(width > 0 && width % 3 == 0)  { "width must be a positive multiple of 3, got $width" }
        require(height > 0 && height % 3 == 0) { "height must be a positive multiple of 3, got $height" }
        require(depth > 0 && depth % 3 == 0)   { "depth must be a positive multiple of 3, got $depth" }
    }

    val nodes = Array(width) { x -> Array(height) { y -> Array(depth) { z -> WorldNode(x, y, z) } } }

    private val nodesByTag = mutableMapOf<String, MutableList<WorldNode>>()
    val associations = mutableListOf<Association>()

    data class Association(
        val source: WorldNode,
        val target: WorldNode,
        val type: String,
        val data: String? = null
    )

    fun addAssociation(source: WorldNode, target: WorldNode, type: String, data: String? = null) {
        associations.add(Association(source, target, type, data))
    }

    fun removeAssociation(source: WorldNode, target: WorldNode) {
        associations.removeIf { it.source == source && it.target == target }
    }

    fun getAssociationsFor(node: WorldNode): List<Association> =
        associations.filter { it.source == node || it.target == node }

    fun getNode(x: Int, y: Int, z: Int): WorldNode? {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) return null
        return nodes[x][y][z]
    }

    fun addTag(node: WorldNode, tag: String) {
        if (node.tags.add(tag)) nodesByTag.getOrPut(tag) { mutableListOf() }.add(node)
    }

    fun removeTag(node: WorldNode, tag: String) {
        if (node.tags.remove(tag)) nodesByTag[tag]?.remove(node)
    }

    fun getNodesWithTag(tag: String): List<WorldNode> = nodesByTag[tag] ?: emptyList()

    /**
     * Checks whether a position is walkable.
     * A node is walkable only if it has a floor.
     */
    fun isWalkable(x: Float, y: Float, z: Float): Boolean {
        val node = getNode(Math.round(x), Math.round(y), Math.round(z)) ?: return false
        return node.hasFloor
    }

    /**
     * Checks whether movement from (fx,fy,fz) towards the given direction is blocked by a wall.
     */
    fun isBlocked(x: Int, y: Int, z: Int, direction: TileSlot): Boolean {
        val node = getNode(x, y, z) ?: return true
        return node.isWallBlocking(direction)
    }
}
