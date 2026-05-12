package com.roguelike.serialization

import java.util.ArrayList

data class WorldData(
    val width: Int = 0, 
    val height: Int = 0, 
    val depth: Int = 0, 
    val nodes: ArrayList<NodeData> = ArrayList(),
    val associations: ArrayList<AssociationData> = ArrayList(),
    val props: ArrayList<PropData> = ArrayList()
)

data class NodeData(
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val tags: ArrayList<String> = ArrayList(),
    val tiles: ArrayList<TileData> = ArrayList(),
    val items: ArrayList<ItemData> = ArrayList(),
    /** Wall slots tagged as doors (e.g. "WALL_NORTH"). */
    val doorSlots: ArrayList<String> = ArrayList(),
    /** Wall slots tagged as manual-interact doors. */
    val manualDoorSlots: ArrayList<String> = ArrayList(),
    /** Wall slots tagged as sockets (for map generation). */
    val socketSlots: ArrayList<String> = ArrayList(),
    /** Wall slots tagged as ladders. */
    val ladderSlots: ArrayList<String> = ArrayList()
)

data class TileData(
    var type: String = "",
    var slot: String = "",
    var rotX: Float = 0f,
    var rotY: Float = 0f,
    var rotZ: Float = 0f
) : com.badlogic.gdx.utils.Json.Serializable {
    override fun write(json: com.badlogic.gdx.utils.Json) {
        json.writeValue("type", type)
        json.writeValue("slot", slot)
        if (rotX != 0f) json.writeValue("rotX", rotX)
        if (rotY != 0f) json.writeValue("rotY", rotY)
        if (rotZ != 0f) json.writeValue("rotZ", rotZ)
    }

    override fun read(json: com.badlogic.gdx.utils.Json, jsonData: com.badlogic.gdx.utils.JsonValue) {
        if (jsonData.isString) {
            type = jsonData.asString()
        } else {
            type = jsonData.getString("type", "")
            slot = jsonData.getString("slot", "")
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
    val name: String = "",
    /** Per-instance tags such as `light_source_lit`. */
    val tags: ArrayList<String> = ArrayList()
)

data class PropData(
    val id: String = "",
    val modelPath: String = "",
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val rotationY: Float = 0f,
    val scale: Float = 1f,
    val collisionHalfSize: Float = 0.25f,
    val collisionHalfSizeX: Float? = null,
    val collisionHalfSizeY: Float? = null
)

data class AssociationData(
    val sourceX: Int = 0, val sourceY: Int = 0, val sourceZ: Int = 0,
    val targetX: Int = 0, val targetY: Int = 0, val targetZ: Int = 0,
    val type: String = "", // e.g. "key" or "toggle"
    val data: String? = null
)
