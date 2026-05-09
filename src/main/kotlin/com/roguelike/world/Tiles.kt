package com.roguelike.world

import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot

/**
 * Base for all tile types. Pure data — no LibGDX Model or ModelInstance references.
 * Rendering metadata (Model, scale, center) is managed by TileRenderRegistry in the view layer.
 */
abstract class BaseTile : Tile {
    var rotationX = 0f
    var rotationY = 0f
    var rotationZ = 0f
    var xOffset   = 0f
    var yOffset   = 0f
    var zOffset   = 0f
}

class FloorTile : BaseTile() {
    companion object { const val TYPE = "FloorTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.FLOOR
    override val fixedZ: Float get() = -0.5f
}

abstract class WallTile(
    /** Whether this wall allows passage. Default is false (impassable). */
    val passable: Boolean = false
) : BaseTile() {
    override fun isBlocking(): Boolean = !passable
    override val slot: TileSlot get() = TileSlot.WALL
}

class WallHorizontalTile : WallTile() {
    companion object { const val TYPE = "WallHorizontalTile" }
    override val type: String get() = TYPE
}

class WallVerticalTile : WallTile() {
    companion object { const val TYPE = "WallVerticalTile" }
    override val type: String get() = TYPE
}

/**
 * Door tile. Pure logic — open/close state only.
 * The renderer looks up closed/open Models from TileRenderRegistry.
 */
abstract class DoorTile(var isOpen: Boolean = false) : BaseTile() {
    override val slot: TileSlot get() = TileSlot.DOOR
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

class DoorHorizontalTile(isOpen: Boolean = false) : DoorTile(isOpen) {
    companion object { const val TYPE = "DoorHorizontalTile" }
    override val type: String get() = TYPE
}

class DoorVerticalTile(isOpen: Boolean = false) : DoorTile(isOpen) {
    companion object { const val TYPE = "DoorVerticalTile" }
    override val type: String get() = TYPE
}

class ToggleTile(var linkedDoor: DoorTile? = null) : BaseTile() {
    companion object { const val TYPE = "ToggleTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.INTERACTION
    override fun onInteract() { linkedDoor?.onInteract() }
}

abstract class CornerTile : BaseTile() {
    override fun isBlocking(): Boolean = true
    override val slot: TileSlot get() = TileSlot.WALL
}

class CornerNETile : CornerTile() {
    companion object { const val TYPE = "CornerNETile" }
    override val type: String get() = TYPE
}

class CornerSETile : CornerTile() {
    companion object { const val TYPE = "CornerSETile" }
    override val type: String get() = TYPE
}

class CornerSWTile : CornerTile() {
    companion object { const val TYPE = "CornerSWTile" }
    override val type: String get() = TYPE
}

class CornerNWTile : CornerTile() {
    companion object { const val TYPE = "CornerNWTile" }
    override val type: String get() = TYPE
}


class WallDoorwayHorizontalTile : WallTile(passable = true) {
    companion object { const val TYPE = "WallDoorwayHorizontalTile" }
    override val type: String get() = TYPE
}

class WallDoorwayVerticalTile : WallTile(passable = true) {
    companion object { const val TYPE = "WallDoorwayVerticalTile" }
    override val type: String get() = TYPE
}

class WallCrossingTile : WallTile() {
    companion object { const val TYPE = "WallCrossingTile" }
    override val type: String get() = TYPE
}

class WallTsplitNTile : WallTile() {
    companion object { const val TYPE = "WallTsplitNTile" }
    override val type: String get() = TYPE
}

class WallTsplitETile : WallTile() {
    companion object { const val TYPE = "WallTsplitETile" }
    override val type: String get() = TYPE
}

class WallTsplitSTile : WallTile() {
    companion object { const val TYPE = "WallTsplitSTile" }
    override val type: String get() = TYPE
}

class WallTsplitWTile : WallTile() {
    companion object { const val TYPE = "WallTsplitWTile" }
    override val type: String get() = TYPE
}

class GenericTile(val modelName: String) : BaseTile() {
    override val type: String get() = "Generic:$modelName"
    override val slot: TileSlot get() = TileSlot.FLOOR
}
