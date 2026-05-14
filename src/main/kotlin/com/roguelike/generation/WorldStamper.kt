package com.roguelike.generation

import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode

/**
 * Stamps (copies) a SubmapTemplate's world data into a target world at a given offset.
 *
 * The target world must be large enough to contain the stamped data.
 * Tiles are created fresh using a tile factory to ensure proper render registration.
 */
class WorldStamper(
    private val tileFactory: (String) -> Tile?
) {
    /**
     * Stamps the given PlacedSubmap into the target world.
     * Copies tiles, tags, door slots, manual door slots, and items.
     *
     * @param placed The placed submap with origin offset.
     * @param target The target world to stamp into.
     */
    fun stamp(placed: PlacedSubmap, target: World) {
        val source = placed.template.worldData
        val origin = placed.origin

        for (x in 0 until source.width) {
            for (y in 0 until source.height) {
                for (z in 0 until source.depth) {
                    val sourceNode = source.getNode(x, y, z) ?: continue

                    val targetX = origin.x + x
                    val targetY = origin.y + y
                    val targetZ = origin.z + z
                    val targetNode = target.getNode(targetX, targetY, targetZ) ?: continue

                    // Copy tiles (create fresh instances via factory)
                    for (tile in sourceNode.tiles) {
                        val newTile = tileFactory(tile.type)
                        if (newTile != null) {
                            // Copy rotation/offset from source if it's a BaseTile
                            if (newTile is com.roguelike.world.BaseTile) {
                                if (tile is RotatedTileRef) {
                                    if (tile.useFactoryDefaults) {
                                        // Wall/door whose type changed: factory already set correct
                                        // rotation and offset — keep factory defaults, only copy zOffset
                                        if (tile.originalTile is com.roguelike.world.BaseTile) {
                                            newTile.zOffset = tile.originalTile.zOffset
                                        }
                                    } else if (tile.originalTile is com.roguelike.world.BaseTile) {
                                        // Non-directional tile (floor, stairs): copy original + add rotation
                                        val orig = tile.originalTile
                                        newTile.rotationX = orig.rotationX
                                        newTile.rotationY = orig.rotationY + tile.additionalRotY
                                        newTile.rotationZ = orig.rotationZ
                                        newTile.xOffset = orig.xOffset
                                        newTile.yOffset = orig.yOffset
                                        newTile.zOffset = orig.zOffset
                                    }
                                } else if (tile is com.roguelike.world.BaseTile) {
                                    // Non-rotated tile: direct copy
                                    newTile.rotationX = tile.rotationX
                                    newTile.rotationY = tile.rotationY
                                    newTile.rotationZ = tile.rotationZ
                                    newTile.xOffset = tile.xOffset
                                    newTile.yOffset = tile.yOffset
                                    newTile.zOffset = tile.zOffset
                                }
                            }
                            targetNode.setTile(newTile)
                        }
                    }

                    // Copy door tags
                    for (slot in sourceNode.doorSlots) {
                        targetNode.tagAsDoor(slot)
                    }

                    // Copy manual door tags
                    for (slot in sourceNode.manualDoorSlots) {
                        targetNode.tagAsManualDoor(slot)
                    }

                    // Copy socket tags
                    for (slot in sourceNode.socketSlots) {
                        targetNode.tagAsSocket(slot)
                    }

                    // Copy ladder tags
                    for (slot in sourceNode.ladderSlots) {
                        targetNode.tagAsLadder(slot)
                    }

                    // Copy general tags
                    for (tag in sourceNode.tags) {
                        target.addTag(targetNode, tag)
                    }

                    // Copy items
                    for (item in sourceNode.items) {
                        targetNode.items.add(item)
                    }
                }
            }
        }

        // Copy props with offset
        for (prop in source.props) {
            target.props.add(prop.copy(
                x = prop.x + origin.x,
                y = prop.y + origin.y,
                z = prop.z + origin.z
            ))
        }

        // Copy light sources with offset
        for (ls in source.lightSources) {
            target.lightSources.add(ls.copy(
                x = ls.x + origin.x,
                y = ls.y + origin.y,
                z = ls.z + origin.z
            ))
        }
    }

    /**
     * Removes the wall between two connected sockets to allow passage.
     * Called after both submaps are stamped.
     *
     * @param placed The submap containing the source socket.
     * @param socket The connected socket whose wall should be removed.
     * @param target The target world.
     */
    fun openConnection(placed: PlacedSubmap, socket: Socket, target: World) {
        val absPos = placed.absoluteSocketPosition(socket)
        val node = target.getNode(absPos.x, absPos.y, absPos.z) ?: return

        // Remove the wall on the socket's face
        val wallSlot = directionToSlot(socket.direction) ?: return
        node.removeTile(wallSlot)
        node.untagSocket(wallSlot)

        // Also remove the wall on the adjacent node's opposite face
        val neighborPos = absPos + socket.direction
        val neighborNode = target.getNode(neighborPos.x, neighborPos.y, neighborPos.z) ?: return
        val oppositeSlot = directionToSlot(socket.direction.negate()) ?: return
        neighborNode.removeTile(oppositeSlot)
        neighborNode.untagSocket(oppositeSlot)
    }

    /**
     * Places a wall on a sealed socket to close off the opening.
     * Called when no neighbor could be connected to this socket.
     */
    fun sealConnection(placed: PlacedSubmap, socket: Socket, target: World) {
        val absPos = placed.absoluteSocketPosition(socket)
        val node = target.getNode(absPos.x, absPos.y, absPos.z) ?: return
        val wallSlot = directionToSlot(socket.direction) ?: return

        // Only add a wall if there isn't one already
        if (node.hasTile(wallSlot)) return

        val wallType = slotToWallType(wallSlot)
        val wallTile = tileFactory(wallType)
        if (wallTile != null) {
            node.setTile(wallTile)
            node.untagSocket(wallSlot)
        }
    }

    companion object {
        fun directionToSlot(direction: Vector3Int): TileSlot? {
            if (direction == Vector3Int.NORTH) return TileSlot.WALL_NORTH
            if (direction == Vector3Int.SOUTH) return TileSlot.WALL_SOUTH
            if (direction == Vector3Int.EAST) return TileSlot.WALL_EAST
            if (direction == Vector3Int.WEST) return TileSlot.WALL_WEST
            return null
        }

        fun slotToWallType(slot: TileSlot): String = when (slot) {
            TileSlot.WALL_NORTH -> "WallNorthTile"
            TileSlot.WALL_SOUTH -> "WallSouthTile"
            TileSlot.WALL_EAST -> "WallEastTile"
            TileSlot.WALL_WEST -> "WallWestTile"
            else -> "WallNorthTile"
        }
    }
}
