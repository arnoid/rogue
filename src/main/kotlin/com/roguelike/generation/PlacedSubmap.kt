package com.roguelike.generation

/**
 * Represents a placed submap instance in the generated world.
 *
 * @param template    The template this instance was created from.
 * @param origin      The absolute grid position (in voxels) where this submap's (0,0,0) is placed.
 * @param sockets     The sockets with updated states for this placed instance.
 */
data class PlacedSubmap(
    val template: SubmapTemplate,
    val origin: Vector3Int,
    val sockets: List<Socket>
) {
    /**
     * Returns the set of base unit coordinates this submap occupies on the global grid.
     * Base units are 3x3x3 blocks.
     */
    fun occupiedBaseUnits(): Set<Vector3Int> {
        val units = mutableSetOf<Vector3Int>()
        val bu = template.baseUnitFootprint
        val baseOrigin = Vector3Int(origin.x / 3, origin.y / 3, origin.z / 3)
        for (bx in 0 until bu.x) {
            for (by in 0 until bu.y) {
                for (bz in 0 until bu.z) {
                    units.add(Vector3Int(baseOrigin.x + bx, baseOrigin.y + by, baseOrigin.z + bz))
                }
            }
        }
        return units
    }

    /**
     * Returns the absolute position of a socket in the global grid.
     */
    fun absoluteSocketPosition(socket: Socket): Vector3Int {
        return origin + socket.localPosition
    }
}

