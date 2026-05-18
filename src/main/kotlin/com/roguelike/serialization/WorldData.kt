package com.roguelike.serialization

import java.util.ArrayList

data class WorldData(
    val width: Int = 0, 
    val height: Int = 0, 
    val depth: Int = 0, 
    val nodes: ArrayList<NodeData> = ArrayList(),
    val associations: ArrayList<AssociationData> = ArrayList(),
    val props: ArrayList<PropData> = ArrayList(),
    val lightSources: ArrayList<LightSourceData> = ArrayList()
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
    var rotZ: Float = 0f,
    /** For door tiles: whether they are currently open. Null = not applicable / default. */
    var isOpen: Boolean? = null
)



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
    val type: String = "",
    val data: String? = null
)

data class LightSourceData(
    val id: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val intensity: Float = 5f,
    val radius: Float = 5f,
    val colorHex: String = "ffcc88"
)

