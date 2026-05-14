package com.roguelike.generation

import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode

/**
 * Represents a loaded submap template (prefab).
 * Dimensions must be divisible by 3.
 * Sockets are derived from `socket` wall tags on outer boundaries.
 */
data class SubmapTemplate(
    val name: String,
    val footprint: Vector3Int,
    val sockets: List<Socket>,
    val worldData: World,
    /** Number of 90° CCW rotations applied (0 = original, 1 = 90°, 2 = 180°, 3 = 270°). */
    val rotation: Int = 0
) {
    val baseUnitFootprint: Vector3Int
        get() = Vector3Int(footprint.x / 3, footprint.y / 3, footprint.z / 3)

    /**
     * Returns a new template rotated 90° clockwise around the Z axis.
     * This performs a **structural** rotation: nodes are rearranged in the grid
     * and wall/door/socket edge slots are remapped to their new cardinal directions.
     * Tile models (stairs, floors) get their visual Z rotation adjusted.
     */
    fun rotatedCW90(): SubmapTemplate {
        val srcW = footprint.x
        val srcH = footprint.y
        val srcD = footprint.z
        // After 90° CW the footprint x/y swap
        val newW = srcH
        val newH = srcW
        val newFootprint = Vector3Int(newW, newH, srcD)

        // Create the rotated world
        val rotatedWorld = World(newW, newH, srcD)

        for (sx in 0 until srcW) {
            for (sy in 0 until srcH) {
                for (sz in 0 until srcD) {
                    val srcNode = worldData.getNode(sx, sy, sz) ?: continue
                    // 90° CW: (x,y) -> (srcH-1-y, x)
                    val dx = srcH - 1 - sy
                    val dy = sx
                    val dstNode = rotatedWorld.getNode(dx, dy, sz) ?: continue

                    // Copy tiles with rotated types (walls/doors change direction)
                    for (tile in srcNode.tiles) {
                        // Unwrap any existing RotatedTileRef to get the true original tile
                        // and accumulate rotation for chained rotations (e.g., CCW = 3× CW)
                        val baseTile: Tile
                        val accumulatedRotY: Float
                        val wasFactoryDefaults: Boolean
                        if (tile is RotatedTileRef) {
                            baseTile = tile.originalTile
                            accumulatedRotY = tile.additionalRotY
                            wasFactoryDefaults = tile.useFactoryDefaults
                        } else {
                            baseTile = tile
                            accumulatedRotY = 0f
                            wasFactoryDefaults = false
                        }

                        val rotatedType = rotateTileType(tile.type)
                        val typeChanged = rotatedType != baseTile.type
                        dstNode.setTile(RotatedTileRef(
                            originalTile = baseTile,
                            rotatedType = rotatedType,
                            rotatedSlot = rotateSlot(tile.slot),
                            useFactoryDefaults = typeChanged,
                            additionalRotY = if (!typeChanged) accumulatedRotY - 90f else 0f
                        ))
                    }

                    // Copy door tags (rotated edge)
                    for (slot in srcNode.doorSlots) {
                        dstNode.tagAsDoor(rotateSlot(slot))
                    }
                    // Copy manual door tags (rotated edge)
                    for (slot in srcNode.manualDoorSlots) {
                        dstNode.tagAsManualDoor(rotateSlot(slot))
                    }
                    // Copy ladder tags (rotated edge)
                    for (slot in srcNode.ladderSlots) {
                        dstNode.tagAsLadder(rotateSlot(slot))
                    }
                    // Mark that this node had sockets (we'll recompute their slots after rotation)
                    val hadSockets = srcNode.socketSlots.isNotEmpty()

                    // Copy general tags
                    for (tag in srcNode.tags) {
                        rotatedWorld.addTag(dstNode, tag)
                    }
                    // Copy items
                    for (item in srcNode.items) {
                        dstNode.items.add(item)
                    }

                    // Recompute socket slots based on new boundary position
                    if (hadSockets) {
                        // Check which edges of the destination node are on the outer boundary
                        if (dx == 0) dstNode.tagAsSocket(TileSlot.WALL_WEST)
                        if (dx == newW - 1) dstNode.tagAsSocket(TileSlot.WALL_EAST)
                        if (dy == 0) dstNode.tagAsSocket(TileSlot.WALL_SOUTH)
                        if (dy == newH - 1) dstNode.tagAsSocket(TileSlot.WALL_NORTH)
                    }
                }
            }
        }

        // Rotate props: 90° CW: (x,y) -> (srcH-1-y, x)
        for (prop in worldData.props) {
            rotatedWorld.props.add(prop.copy(
                x = srcH - 1 - prop.y,
                y = prop.x,
                rotationY = prop.rotationY - 90f
            ))
        }

        // Rotate light sources: 90° CW: (x,y) -> (srcH-1-y, x)
        for (ls in worldData.lightSources) {
            rotatedWorld.lightSources.add(ls.copy(
                x = srcH - 1 - ls.y,
                y = ls.x
            ))
        }

        // Re-derive sockets from the rotated world's socket slots (which are correctly placed on outer boundaries)
        val newSockets = mutableListOf<Socket>()
        for (x in 0 until newW) {
            for (y in 0 until newH) {
                for (z in 0 until srcD) {
                    val node = rotatedWorld.getNode(x, y, z) ?: continue
                    for (slot in node.socketSlots) {
                        val direction = slotToDirection(slot)
                        val tag = deriveSocketTag(node)
                        newSockets.add(Socket(
                            localPosition = Vector3Int(x, y, z),
                            direction = direction,
                            tag = tag
                        ))
                    }
                }
            }
        }
        return SubmapTemplate(name, newFootprint, newSockets, rotatedWorld, (rotation + 1) % 4)
    }

    /**
     * Returns all 4 rotation variants of this template (0°, 90°, 180°, 270° CCW).
     */
    fun allRotations(): List<SubmapTemplate> {
        val r0 = this
        val r1 = r0.rotatedCW90()
        val r2 = r1.rotatedCW90()
        val r3 = r2.rotatedCW90()
        return listOf(r0, r1, r2, r3)
    }

    companion object {
        fun fromWorld(name: String, world: World): SubmapTemplate {
            val footprint = Vector3Int(world.width, world.height, world.depth)
            val sockets = mutableListOf<Socket>()

            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        for (slot in node.socketSlots) {
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

        /**
         * Rotate a TileSlot when performing a 90° CW grid rotation.
         * Wall slots rotate CCW relative to the node because the node itself moved CW.
         */
        fun rotateSlot(slot: TileSlot): TileSlot = when (slot) {
            TileSlot.WALL_NORTH -> TileSlot.WALL_WEST
            TileSlot.WALL_WEST -> TileSlot.WALL_SOUTH
            TileSlot.WALL_SOUTH -> TileSlot.WALL_EAST
            TileSlot.WALL_EAST -> TileSlot.WALL_NORTH
            else -> slot // FLOOR, STAIRS unchanged
        }

        /**
         * Rotate a tile type name when performing a 90° CW grid rotation.
         * Wall/door types rotate CCW relative to the node.
         */
        fun rotateTileType(type: String): String = when (type) {
            "WallNorthTile" -> "WallWestTile"
            "WallWestTile" -> "WallSouthTile"
            "WallSouthTile" -> "WallEastTile"
            "WallEastTile" -> "WallNorthTile"
            "DoorNorthTile" -> "DoorWestTile"
            "DoorWestTile" -> "DoorSouthTile"
            "DoorSouthTile" -> "DoorEastTile"
            "DoorEastTile" -> "DoorNorthTile"
            else -> type
        }
    }
}

/**
 * A lightweight tile reference used in rotated template worlds.
 * Carries the original tile's data plus the rotated type/slot.
 *
 * @param useFactoryDefaults If true, the WorldStamper should use the factory-created tile's
 *        default rotation/offset (for walls/doors whose type changed). If false, the original
 *        tile's properties are copied and [additionalRotY] is added (for floors/stairs).
 * @param additionalRotY Additional Y rotation to apply (for non-directional tiles like stairs).
 */
class RotatedTileRef(
    val originalTile: Tile,
    val rotatedType: String,
    val rotatedSlot: TileSlot,
    val useFactoryDefaults: Boolean,
    val additionalRotY: Float
) : Tile {
    override val type: String get() = rotatedType
    override val slot: TileSlot get() = rotatedSlot
    override fun isBlocking(): Boolean = originalTile.isBlocking()
    override val properties: Map<String, Any> get() = originalTile.properties
}

