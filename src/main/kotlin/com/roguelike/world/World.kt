package com.roguelike.world

import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch

class World(val width: Int, val height: Int, val depth: Int) {
    val nodes =
            Array(width) { x -> Array(height) { y -> Array(depth) { z -> WorldNode(x, y, z) } } }

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

    fun getAssociationsFor(node: WorldNode): List<Association> {
        return associations.filter { it.source == node || it.target == node }
    }



    /** Gets a node at the specified coordinates. */
    fun getNode(x: Int, y: Int, z: Int): WorldNode? {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) return null
        return nodes[x][y][z]
    }

    /** Adds a tag to a node and updates the tag map. */
    fun addTag(node: WorldNode, tag: String) {
        if (node.tags.add(tag)) {
            nodesByTag.getOrPut(tag) { mutableListOf() }.add(node)
        }
    }

    /** Removes a tag from a node and updates the tag map. */
    fun removeTag(node: WorldNode, tag: String) {
        if (node.tags.remove(tag)) {
            nodesByTag[tag]?.remove(node)
        }
    }

    /** Gets all nodes associated with a specific tag. */
    fun getNodesWithTag(tag: String): List<WorldNode> = nodesByTag[tag] ?: emptyList()

    /** Checks if a location is walkable. */
    fun isWalkable(x: Float, y: Float, z: Float): Boolean {
        val ix = Math.round(x)
        val iy = Math.round(y)
        val iz = Math.round(z)
        val node = getNode(ix, iy, iz) ?: return false
        return node.tiles.none { it.isBlocking() }
    }
}
