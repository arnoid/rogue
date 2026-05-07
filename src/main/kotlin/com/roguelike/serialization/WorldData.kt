package com.roguelike.serialization

import java.util.ArrayList

data class WorldData(
    val width: Int = 0, 
    val height: Int = 0, 
    val depth: Int = 0, 
    val nodes: ArrayList<NodeData> = ArrayList(),
    val associations: ArrayList<AssociationData> = ArrayList()
)

data class NodeData(
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val tags: ArrayList<String> = ArrayList(),
    val tiles: ArrayList<TileData> = ArrayList(),
    val items: ArrayList<ItemData> = ArrayList()
)

data class TileData(
    var type: String = "",
    var rotX: Float = 0f,
    var rotY: Float = 0f,
    var rotZ: Float = 0f
) : com.badlogic.gdx.utils.Json.Serializable {
    override fun write(json: com.badlogic.gdx.utils.Json) {
        json.writeValue("type", type)
        if (rotX != 0f) json.writeValue("rotX", rotX)
        if (rotY != 0f) json.writeValue("rotY", rotY)
        if (rotZ != 0f) json.writeValue("rotZ", rotZ)
    }

    override fun read(json: com.badlogic.gdx.utils.Json, jsonData: com.badlogic.gdx.utils.JsonValue) {
        if (jsonData.isString) {
            type = jsonData.asString()
        } else {
            type = jsonData.getString("type", "")
            rotX = jsonData.getFloat("rotX", 0f)
            rotY = jsonData.getFloat("rotY", 0f)
            rotZ = jsonData.getFloat("rotZ", 0f)
        }
    }
}



data class ItemData(
    val id: String = "",
    val type: String = "",
    val color: String = "FFFFFFFF", // RGBA8888 hex
    val name: String = ""
)

data class AssociationData(
    val sourceX: Int = 0, val sourceY: Int = 0, val sourceZ: Int = 0,
    val targetX: Int = 0, val targetY: Int = 0, val targetZ: Int = 0,
    val type: String = "", // e.g. "key" or "toggle"
    val data: String? = null
)
