package com.roguelike.generation

import com.roguelike.core.model.LightSource

/**
 * A placed submap instance in the generated world. Conceptually this **is**
 * a "Room": a discrete cell of the dungeon with its own sockets (the
 * doorways/openings to neighbouring rooms) and its own light sources.
 *
 * Properties:
 *  - [template] : the prefab this instance was created from.
 *  - [origin]   : absolute voxel coordinate of the template's local (0,0,0).
 *  - [sockets]  : socket instances owned by this room. Mutable [Socket.state]
 *                 tracks whether each opening is OPEN, CONNECTED, or SEALED.
 *  - [lightSources] : the room's lights translated into world space. Owned
 *                 by the room so it can be cleanly added/removed from the
 *                 active world if needed.
 */
data class PlacedSubmap(
    val template: SubmapTemplate,
    val origin: Vector3Int,
    val sockets: List<Socket>,
    val lightSources: List<LightSource> = emptyList()
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

/**
 * Domain alias: every placed submap **is** a Room. Use [Room] in new code
 * for clarity; existing call sites continue to work via [PlacedSubmap].
 */
typealias Room = PlacedSubmap

