package com.roguelike.core.model

/**
 * The 3-D grid that represents the game world.
 *
 * All three dimensions must be divisible by 3 (e.g. 3×6×12, 6×6×6).
 * No LibGDX dependencies.
 */
class World(width: Int, height: Int, depth: Int) {

    init {
        require(width > 0 && width % 3 == 0)  { "width must be a positive multiple of 3, got $width" }
        require(height > 0 && height % 3 == 0) { "height must be a positive multiple of 3, got $height" }
        require(depth > 0 && depth % 3 == 0)   { "depth must be a positive multiple of 3, got $depth" }
    }

    var width: Int = width
        private set
    var height: Int = height
        private set
    var depth: Int = depth
        private set

    private var nodes = Array(width) { x -> Array(height) { y -> Array(depth) { z -> WorldNode(x, y, z).also { it.world = this } } } }

    private val nodesByTag = mutableMapOf<String, MutableList<WorldNode>>()
    val associations = mutableListOf<Association>()
    val props = mutableListOf<Prop>()
    val lightSources = mutableListOf<LightSource>()

    // ── Sparse index of non-empty nodes, bucketed by Z ──────────────────
    // Maintained by WorldNode.setTile / removeTile / clear notifications.
    // Hot per-frame loops (e.g. lighting / shadow collection) can iterate
    // just the populated cells inside a window AABB instead of walking the
    // entire grid volume — for a 93×81×18 world that's ~10× fewer cell
    // probes when most layers are sparse.
    private var nonEmptyByZ: Array<ArrayList<WorldNode>> = Array(depth) { ArrayList() }

    internal fun onNodeBecameNonEmpty(node: WorldNode) {
        if (node.z in nonEmptyByZ.indices) nonEmptyByZ[node.z].add(node)
    }

    internal fun onNodeBecameEmpty(node: WorldNode) {
        if (node.z in nonEmptyByZ.indices) nonEmptyByZ[node.z].remove(node)
    }

    /**
     * Invokes [block] for every non-empty node whose coordinates lie within
     * the inclusive lower / exclusive upper bounds (`originX..endX-1` etc.).
     * Iterates the sparse per-Z index — O(populated cells in window) rather
     * than O(window volume).
     */
    fun forEachNonEmptyInWindow(
        originX: Int, originY: Int, originZ: Int,
        endX: Int, endY: Int, endZ: Int,
        block: (WorldNode) -> Unit
    ) {
        val zLo = originZ.coerceAtLeast(0)
        val zHi = endZ.coerceAtMost(nonEmptyByZ.size)
        for (z in zLo until zHi) {
            val list = nonEmptyByZ[z]
            for (i in list.indices) {
                val n = list[i]
                if (n.x in originX until endX && n.y in originY until endY) block(n)
            }
        }
    }

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
     * Checks whether movement from (fx,fy,fz) towards the given direction is blocked by a wall.I *
     * Handles the door asymmetry: a door tile is only placed on ONE side of a wall,
     * but the door SLOT may be tagged on both adjacent nodes (via the MapEditor's
     * two-sided tagging). When the current node has the slot tagged as a door but
     * no tile in that slot, we check the adjacent node's opposite slot for the
     * actual door tile.
     */
    fun isBlocked(x: Int, y: Int, z: Int, direction: TileSlot): Boolean {
        val node = getNode(x, y, z) ?: return true
        if (node.isWallBlocking(direction)) return true
        // If this node tagged the slot as a door but has no tile, defer to the adjacent node.
        if (node.isDoor(direction) && node.getTile(direction) == null) {
            val (ax, ay, opp) = when (direction) {
                TileSlot.WALL_NORTH -> Triple(x, y + 1, TileSlot.WALL_SOUTH)
                TileSlot.WALL_SOUTH -> Triple(x, y - 1, TileSlot.WALL_NORTH)
                TileSlot.WALL_EAST  -> Triple(x + 1, y, TileSlot.WALL_WEST)
                TileSlot.WALL_WEST  -> Triple(x - 1, y, TileSlot.WALL_EAST)
                else -> return false
            }
            val adj = getNode(ax, ay, z) ?: return false
            return adj.isWallBlocking(opp)
        }
        return false
    }

    /**
     * Ensures the world is large enough to contain coordinates up to (maxX-1, maxY-1, maxZ-1).
     * If the world is already large enough, this is a no-op.
     * New dimensions are rounded up to the nearest multiple of 3.
     */
    fun ensureSize(needWidth: Int, needHeight: Int, needDepth: Int) {
        val newW = ((maxOf(width, needWidth) + 2) / 3) * 3
        val newH = ((maxOf(height, needHeight) + 2) / 3) * 3
        val newD = ((maxOf(depth, needDepth) + 2) / 3) * 3
        if (newW <= width && newH <= height && newD <= depth) return
        expand(newW, newH, newD)
    }

    private fun expand(newWidth: Int, newHeight: Int, newDepth: Int) {
        val newNodes = Array(newWidth) { x ->
            Array(newHeight) { y ->
                Array(newDepth) { z ->
                    if (x < width && y < height && z < depth) nodes[x][y][z]
                    else WorldNode(x, y, z).also { it.world = this }
                }
            }
        }
        nodes = newNodes
        // Grow the per-Z index (preserve existing buckets, append empty ones).
        if (newDepth > nonEmptyByZ.size) {
            nonEmptyByZ = Array(newDepth) { z -> if (z < nonEmptyByZ.size) nonEmptyByZ[z] else ArrayList() }
        }
        width = newWidth
        height = newHeight
        depth = newDepth
    }

    /**
     * Resize the world to the exact dimensions [newWidth] × [newHeight] × [newDepth].
     * All dimensions must be positive multiples of 3.
     *
     * Existing nodes inside the new extents are preserved; nodes outside the
     * new extents (and any lights, props or associations referring to them)
     * are discarded. Use this — instead of [ensureSize] — when the editor
     * needs to shrink the world.
     */
    fun setSize(newWidth: Int, newHeight: Int, newDepth: Int) {
        require(newWidth > 0 && newWidth % 3 == 0)   { "width must be a positive multiple of 3, got $newWidth" }
        require(newHeight > 0 && newHeight % 3 == 0) { "height must be a positive multiple of 3, got $newHeight" }
        require(newDepth > 0 && newDepth % 3 == 0)   { "depth must be a positive multiple of 3, got $newDepth" }
        if (newWidth == width && newHeight == height && newDepth == depth) return

        val newNodes = Array(newWidth) { x ->
            Array(newHeight) { y ->
                Array(newDepth) { z ->
                    if (x < width && y < height && z < depth) nodes[x][y][z]
                    else WorldNode(x, y, z).also { it.world = this }
                }
            }
        }

        // Drop tag-index entries pointing to nodes outside the new extents.
        for ((_, list) in nodesByTag) {
            list.removeAll { it.x >= newWidth || it.y >= newHeight || it.z >= newDepth }
        }

        // Drop lights / props / associations that reference removed nodes
        // or coordinates outside the new world.
        lightSources.removeAll { it.x >= newWidth.toFloat() || it.y >= newHeight.toFloat() || it.z >= newDepth.toFloat() }
        props.removeAll { it.x >= newWidth.toFloat() || it.y >= newHeight.toFloat() || it.z >= newDepth.toFloat() }
        associations.removeAll { a ->
            a.source.x >= newWidth || a.source.y >= newHeight || a.source.z >= newDepth ||
            a.target.x >= newWidth || a.target.y >= newHeight || a.target.z >= newDepth
        }

        // Resize the sparse non-empty index and drop entries outside extents.
        val rebuilt = Array(newDepth) { ArrayList<WorldNode>() }
        val zMax = minOf(newDepth, nonEmptyByZ.size)
        for (z in 0 until zMax) {
            val src = nonEmptyByZ[z]
            val dst = rebuilt[z]
            for (i in src.indices) {
                val n = src[i]
                if (n.x < newWidth && n.y < newHeight) dst.add(n)
            }
        }
        nonEmptyByZ = rebuilt

        nodes = newNodes
        width = newWidth
        height = newHeight
        depth = newDepth
    }
}
