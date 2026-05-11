package com.roguelike.generation

import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode

/**
 * Represents a loaded submap template (prefab).
 * Dimensions must be divisible by 3.
 * Sockets are derived from `node_connector` wall tags on outer boundaries.
 */
data class SubmapTemplate(
    val name: String,
    val footprint: Vector3Int,
    val sockets: List<Socket>,
    val worldData: World
) {
    val baseUnitFootprint: Vector3Int
        get() = Vector3Int(footprint.x / 3, footprint.y / 3, footprint.z / 3)

    companion object {
        fun fromWorld(name: String, world: World): SubmapTemplate {
            val footprint = Vector3Int(world.width, world.height, world.depth)
            val sockets = mutableListOf<Socket>()

            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        for (slot in node.connectorSlots) {
                            val direction = slotToDirection(slot)
                            val tag = deriveSocketTag(node)
                            sockets.add(Socket(
                                localPosition = Vector3Int(x, y, z),
                                direction = direction,
                                tag = tag
                            ))
                        }
                    }
                }
            }

            return SubmapTemplate(name, footprint, sockets, world)
        }

        fun slotToDirection(slot: TileSlot): Vector3Int = when (slot) {
            TileSlot.WALL_NORTH -> Vector3Int.NORTH
            TileSlot.WALL_SOUTH -> Vector3Int.SOUTH
            TileSlot.WALL_EAST -> Vector3Int.EAST
            TileSlot.WALL_WEST -> Vector3Int.WEST
            else -> Vector3Int.ZERO
        }

        private fun deriveSocketTag(node: WorldNode): String {
            val customTag = node.tags.firstOrNull { it.startsWith("socket:") }
            return customTag?.removePrefix("socket:") ?: "default"
        }
    }
}

