package com.roguelike.serialization

import com.badlogic.gdx.Gdx
import com.roguelike.core.model.*
import com.roguelike.world.BaseTile
import java.io.File

object WorldIO {
    private val json = com.badlogic.gdx.utils.Json()

    /** Returns the resource root directory (the libGDX working directory). */
    private fun resourceRoot(): File {
        val localRoot = Gdx.files?.local("")?.file()?.absoluteFile
        return localRoot ?: File(".").absoluteFile
    }

    /** Converts an absolute model path to a path relative to the resource root. */
    private fun toRelativeModelPath(absolutePath: String): String {
        val root = resourceRoot().canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        val normalized = absolutePath.replace('\\', '/')
        return if (normalized.startsWith(root, ignoreCase = true)) {
            normalized.removePrefix(root)
        } else {
            // Try common resource folder patterns
            val marker = "/src/main/resources/"
            val idx = normalized.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                normalized.substring(idx + marker.length)
            } else {
                absolutePath // keep as-is if we can't relativize
            }
        }
    }

    /** Resolves a potentially relative model path to an absolute path. */
    private fun toAbsoluteModelPath(path: String): String {
        if (File(path).isAbsolute) return path
        val resolved = File(resourceRoot(), path)
        return if (resolved.exists()) resolved.canonicalPath else path
    }

    fun loadWorld(
        path: String,
        worldLoader: (Int, Int, Int) -> World,
        tileFactory: (String) -> Tile?
    ): World? {
        return try {
            val file = File(path)
            val data = json.fromJson(WorldData::class.java, file.readText())
            val world = worldLoader(data.width, data.height, data.depth)

            data.nodes.forEach { nodeData ->
                val node = world.getNode(nodeData.x, nodeData.y, nodeData.z) ?: return@forEach
                nodeData.tags.forEach { world.addTag(node, it) }
                nodeData.tiles.forEach { tData ->
                    tileFactory(tData.type)?.let { tile ->
                        if (tile is BaseTile) {
                            tile.rotationX = tData.rotX
                            tile.rotationY = tData.rotY
                            tile.rotationZ = tData.rotZ
                        }
                        node.setTile(tile)
                    }
                }
                nodeData.doorSlots.forEach { slotName ->
                    try {
                        node.tagAsDoor(TileSlot.valueOf(slotName))
                    } catch (_: IllegalArgumentException) { }
                }
                nodeData.manualDoorSlots.forEach { slotName ->
                    try {
                        node.tagAsManualDoor(TileSlot.valueOf(slotName))
                    } catch (_: IllegalArgumentException) { }
                }
                nodeData.socketSlots.forEach { slotName ->
                    try {
                        node.tagAsSocket(TileSlot.valueOf(slotName))
                    } catch (_: IllegalArgumentException) { }
                }
                nodeData.ladderSlots.forEach { slotName ->
                    try {
                        node.tagAsLadder(TileSlot.valueOf(slotName))
                    } catch (_: IllegalArgumentException) { }
                }
                nodeData.items.forEach { itemData ->
                    node.items.add(
                        KeyItem(
                            id       = itemData.id,
                            type     = itemData.type,
                            colorHex = itemData.color,
                            name     = itemData.name
                        )
                    )
                }
            }

            data.associations.forEach { assocData ->
                val source = world.getNode(assocData.sourceX, assocData.sourceY, assocData.sourceZ)
                val target = world.getNode(assocData.targetX, assocData.targetY, assocData.targetZ)
                if (source != null && target != null) {
                    world.addAssociation(source, target, assocData.type, assocData.data)
                }
            }

            data.props.forEach { propData ->
                world.props.add(
                    Prop(
                        id = propData.id,
                        modelPath = toAbsoluteModelPath(propData.modelPath),
                        name = propData.name,
                        x = propData.x,
                        y = propData.y,
                        z = propData.z,
                        rotationY = propData.rotationY,
                        scale = propData.scale,
                        collisionHalfSizeX = propData.collisionHalfSizeX ?: propData.collisionHalfSize,
                        collisionHalfSizeY = propData.collisionHalfSizeY ?: propData.collisionHalfSize
                    )
                )
            }

            Gdx.app?.log("WorldIO", "World loaded from $path")
            world
        } catch (e: Exception) {
            Gdx.app?.error("WorldIO", "Failed to load world", e)
            null
        }
    }

    fun saveWorld(path: String, world: World) {
        try {
            val data = WorldData(
                width  = world.width,
                height = world.height,
                depth  = world.depth,
                nodes  = ArrayList(),
                associations = ArrayList()
            )

            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isEmpty() && node.tiles.isEmpty() && node.items.isEmpty() && node.doorSlots.isEmpty() && node.manualDoorSlots.isEmpty() && node.socketSlots.isEmpty() && node.ladderSlots.isEmpty()) continue

                        val nodeData = NodeData(
                            x = x, y = y, z = z,
                            tags  = ArrayList(node.tags.toList()),
                            tiles = ArrayList(node.tiles.map { tile ->
                                if (tile is BaseTile) {
                                    TileData(tile.type, tile.slot.name, tile.rotationX, tile.rotationY, tile.rotationZ)
                                } else {
                                    TileData(tile.type, tile.slot.name)
                                }
                            }),
                            items = ArrayList(node.items.map { item ->
                                ItemData(item.id, item.type, item.colorHex, item.name)
                            }),
                            doorSlots = ArrayList(node.doorSlots.map { it.name }),
                            manualDoorSlots = ArrayList(node.manualDoorSlots.map { it.name }),
                            socketSlots = ArrayList(node.socketSlots.map { it.name }),
                            ladderSlots = ArrayList(node.ladderSlots.map { it.name })
                        )
                        data.nodes.add(nodeData)
                    }
                }
            }

            world.associations.forEach { assoc ->
                data.associations.add(
                    AssociationData(
                        assoc.source.x, assoc.source.y, assoc.source.z,
                        assoc.target.x, assoc.target.y, assoc.target.z,
                        assoc.type, assoc.data
                    )
                )
            }

            world.props.forEach { prop ->
                data.props.add(
                    PropData(
                        id = prop.id,
                        modelPath = toRelativeModelPath(prop.modelPath),
                        name = prop.name,
                        x = prop.x,
                        y = prop.y,
                        z = prop.z,
                        rotationY = prop.rotationY,
                        scale = prop.scale,
                        collisionHalfSize = prop.collisionHalfSize,
                        collisionHalfSizeX = prop.collisionHalfSizeX,
                        collisionHalfSizeY = prop.collisionHalfSizeY
                    )
                )
            }

            File(path).writeText(json.prettyPrint(data))
            Gdx.app?.log("WorldIO", "World saved to $path")
        } catch (e: Exception) {
            Gdx.app?.error("WorldIO", "Failed to save world", e)
        }
    }
}
