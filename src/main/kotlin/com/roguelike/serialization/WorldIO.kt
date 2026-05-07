package com.roguelike.serialization

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Json
import com.roguelike.world.*
import java.io.File

object WorldIO {
    private val json = Json()

    fun loadWorld(path: String, worldLoader: (Int, Int, Int) -> World, tileFactory: (String) -> Tile?): World? {
        return try {
            val file = File(path)
            val data = json.fromJson(WorldData::class.java, file.readText())
            val world = worldLoader(data.width, data.height, data.depth)
            
            data.nodes.forEach { nodeData ->
                val node = world.getNode(nodeData.x, nodeData.y, nodeData.z)
                if (node != null) {
                    nodeData.tags.forEach { world.addTag(node, it) }
                    nodeData.tiles.forEach { tData ->
                        tileFactory(tData.type)?.let { 
                            if (it is BaseTile) {
                                it.rotationX = tData.rotX
                                it.rotationY = tData.rotY
                                it.rotationZ = tData.rotZ
                            }
                            node.tiles.add(it) 
                        }
                    }

                    nodeData.items.forEach { itemData ->
                        val color = Color.valueOf(itemData.color)
                        node.items.add(KeyItem(itemData.id, itemData.type, color, itemData.name))
                    }
                }
            }

            data.associations.forEach { assocData ->
                val source = world.getNode(assocData.sourceX, assocData.sourceY, assocData.sourceZ)
                val target = world.getNode(assocData.targetX, assocData.targetY, assocData.targetZ)
                if (source != null && target != null) {
                    world.addAssociation(source, target, assocData.type, assocData.data)
                }
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
                width = world.width,
                height = world.height,
                depth = world.depth,
                nodes = ArrayList(),
                associations = ArrayList()
            )

            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z)
                        if (node != null && (node.tags.isNotEmpty() || node.tiles.isNotEmpty() || node.items.isNotEmpty())) {
                            val nodeData = NodeData(
                                x = x, y = y, z = z,
                                tags = ArrayList(node.tags.toList()),
                                tiles = ArrayList(node.tiles.map { 
                                    if (it is BaseTile) {
                                        TileData(it.type, it.rotationX, it.rotationY, it.rotationZ)
                                    } else {
                                        TileData(it.type)
                                    }
                                }),

                                items = ArrayList(node.items.map { 
                                    ItemData(it.id, it.type, it.color.toString(), it.name) 
                                })
                            )
                            data.nodes.add(nodeData)
                        }
                    }
                }
            }

            world.associations.forEach { assoc ->
                data.associations.add(AssociationData(
                    assoc.source.x, assoc.source.y, assoc.source.z,
                    assoc.target.x, assoc.target.y, assoc.target.z,
                    assoc.type, assoc.data
                ))
            }

            val file = File(path)
            file.writeText(json.prettyPrint(data))
            Gdx.app?.log("WorldIO", "World saved to $path")
        } catch (e: Exception) {
            Gdx.app?.error("WorldIO", "Failed to save world", e)
        }
    }
}
