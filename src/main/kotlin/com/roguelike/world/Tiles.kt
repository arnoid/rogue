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

// ── Floor ────────────────────────────────────────────────────────────────

class FloorTile : BaseTile() {
    companion object { const val TYPE = "FloorTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.FLOOR

    init { zOffset = -0.45f }
}

// ── Ceiling ──────────────────────────────────────────────────────────────

class CeilingTile : BaseTile() {
    companion object { const val TYPE = "CeilingTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.CEILING

    init { zOffset = 0.45f }
}

// ── Walls (one class per cardinal direction) ─────────────────────────────

class WallNorthTile : BaseTile() {
    companion object { const val TYPE = "WallNorthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_NORTH
    override fun isBlocking(): Boolean = true
}

class WallSouthTile : BaseTile() {
    companion object { const val TYPE = "WallSouthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_SOUTH
    override fun isBlocking(): Boolean = true
}

class WallEastTile : BaseTile() {
    companion object { const val TYPE = "WallEastTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_EAST
    override fun isBlocking(): Boolean = true
}

class WallWestTile : BaseTile() {
    companion object { const val TYPE = "WallWestTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_WEST
    override fun isBlocking(): Boolean = true
}

// ── Wall Doorway tiles ───────────────────────────────────────────────────
// A doorway is a wall section with an opening cut into it (the
// `wall_doorway_n.obj` mesh). It looks like a wall with a hole in the
// middle — light and actors can pass through the opening, so these tiles
// report `isBlocking() = false`. They are distinct from Door tiles, which
// have an open/closed state and a swinging panel.

class WallDoorwayNorthTile : BaseTile() {
    companion object { const val TYPE = "WallDoorwayNorthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_NORTH
    override fun isBlocking(): Boolean = false
}

class WallDoorwaySouthTile : BaseTile() {
    companion object { const val TYPE = "WallDoorwaySouthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_SOUTH
    override fun isBlocking(): Boolean = false
}

class WallDoorwayEastTile : BaseTile() {
    companion object { const val TYPE = "WallDoorwayEastTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_EAST
    override fun isBlocking(): Boolean = false
}

class WallDoorwayWestTile : BaseTile() {
    companion object { const val TYPE = "WallDoorwayWestTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_WEST
    override fun isBlocking(): Boolean = false
}

// ── Door tiles (walls with open/closed state — used when a wall is tagged as door) ──

class DoorNorthTile(var isOpen: Boolean = false) : BaseTile() {
    companion object { const val TYPE = "DoorNorthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_NORTH
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

class DoorSouthTile(var isOpen: Boolean = false) : BaseTile() {
    companion object { const val TYPE = "DoorSouthTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_SOUTH
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

class DoorEastTile(var isOpen: Boolean = false) : BaseTile() {
    companion object { const val TYPE = "DoorEastTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_EAST
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

class DoorWestTile(var isOpen: Boolean = false) : BaseTile() {
    companion object { const val TYPE = "DoorWestTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.WALL_WEST
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

// ── Stairs ────────────────────────────────────────────────────────────────

class StairsTile : BaseTile() {
    companion object { const val TYPE = "StairsTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.STAIRS
    override fun isBlocking(): Boolean = false

    /**
     * Returns the cardinal direction the stairs face (the "top" of the stairs).
     * rotationY: 0° = south, 90° = east, 180° = north, 270° = west.
     */
    fun facingDirection(): TileSlot {
        val normalized = ((rotationY % 360f) + 360f) % 360f
        return when {
            normalized < 45f || normalized >= 315f -> TileSlot.WALL_NORTH
            normalized < 135f                      -> TileSlot.WALL_EAST
            normalized < 225f                      -> TileSlot.WALL_SOUTH
            else                                   -> TileSlot.WALL_WEST
        }
    }

    /**
     * Returns the entry edge that makes the player go up.
     * The player goes up when walking in the stairs' facing direction
     * (i.e., entering from the opposite side).
     */
    fun climbEdge(): TileSlot = when (facingDirection()) {
        TileSlot.WALL_NORTH -> TileSlot.WALL_NORTH
        TileSlot.WALL_SOUTH -> TileSlot.WALL_SOUTH
        TileSlot.WALL_EAST  -> TileSlot.WALL_EAST
        TileSlot.WALL_WEST  -> TileSlot.WALL_WEST
        else -> TileSlot.WALL_NORTH
    }
}

// ── Ladder ────────────────────────────────────────────────────────────────

/**
 * A ladder tile that moves the actor straight up when they step onto it.
 * Uses the STAIRS slot (only one vertical-movement tile per node).
 * The ladder faces a wall (like stairs) but lifts the actor vertically
 * instead of ramping them along a horizontal direction.
 */
class LadderTile : BaseTile() {
    companion object { const val TYPE = "LadderTile" }
    override val type: String get() = TYPE
    override val slot: TileSlot get() = TileSlot.STAIRS
    override fun isBlocking(): Boolean = false

    /**
     * Returns the cardinal direction the ladder faces (which wall it's against).
     * Same rotation convention as StairsTile.
     */
    fun facingDirection(): TileSlot {
        val normalized = ((rotationY % 360f) + 360f) % 360f
        return when {
            normalized < 45f || normalized >= 315f -> TileSlot.WALL_NORTH
            normalized < 135f                      -> TileSlot.WALL_EAST
            normalized < 225f                      -> TileSlot.WALL_SOUTH
            else                                   -> TileSlot.WALL_WEST
        }
    }
}

