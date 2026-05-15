package com.roguelike.core.model

/**
 * Represents a single cell in the 3-D game world grid.
 *
 * Each node can have:
 *  - An optional floor tile (if absent, actors fall through along Z).
 *  - Up to four wall tiles (north, south, east, west), each impassable by default.
 *  - Any wall can be tagged as a door, which replaces its model with a "doorway"
 *    model that has a hole so actors can pass through.
 */
class WorldNode(val x: Int, val y: Int, val z: Int) {

    object Tags {
        const val PLAYER_SPAWN = "player_spawn"
        const val ENEMY_SPAWN  = "enemy_spawn"
        const val ITEM_SPAWN   = "item_spawn"
        const val EXIT         = "exit"
        const val DOOR_MANUAL  = "door_manual"
        const val SOCKET = "socket"
        const val LADDER = "ladder"
        const val STAIRS = "stairs"
    }

    // ── Tile storage (floor + 4 walls) ──────────────────────────────────

    private val tileSlots = mutableMapOf<TileSlot, Tile>()

    /** Read-only view of all tiles currently placed on this node. */
    val tiles: Collection<Tile> get() = tileSlots.values

    fun setTile(tile: Tile): Tile? = tileSlots.put(tile.slot, tile)
    fun getTile(slot: TileSlot): Tile? = tileSlots[slot]
    fun removeTile(slot: TileSlot): Tile? = tileSlots.remove(slot)
    fun hasTile(slot: TileSlot): Boolean = tileSlots.containsKey(slot)

    // ── Floor helpers ───────────────────────────────────────────────────

    /** True when this node has a floor; false means actors fall through. */
    val hasFloor: Boolean get() = hasTile(TileSlot.FLOOR)

    // ── Door tags ───────────────────────────────────────────────────────

    private val _doorSlots = mutableSetOf<TileSlot>()

    /** Wall slots that are tagged as doors. */
    val doorSlots: Set<TileSlot> get() = _doorSlots

    /** Wall slots that are tagged as manual-interact doors. */
    private val _manualDoorSlots = mutableSetOf<TileSlot>()
    val manualDoorSlots: Set<TileSlot> get() = _manualDoorSlots

    /**
     * Tag a wall slot as a door.  Only wall slots (WALL_NORTH/SOUTH/EAST/WEST) are accepted.
     */
    fun tagAsDoor(slot: TileSlot) {
        require(slot != TileSlot.FLOOR) { "Only wall slots can be tagged as doors" }
        _doorSlots.add(slot)
    }

    fun untagDoor(slot: TileSlot) { _doorSlots.remove(slot); _manualDoorSlots.remove(slot) }

    fun isDoor(slot: TileSlot): Boolean = slot in _doorSlots

    fun tagAsManualDoor(slot: TileSlot) {
        require(slot != TileSlot.FLOOR) { "Only wall slots can be tagged as manual doors" }
        _manualDoorSlots.add(slot)
    }

    fun untagManualDoor(slot: TileSlot) { _manualDoorSlots.remove(slot) }

    fun isManualDoor(slot: TileSlot): Boolean = slot in _manualDoorSlots

    // ── Socket tags (per-edge, outer walls only) ────────────────────────

    private val _socketSlots = mutableSetOf<TileSlot>()
    val socketSlots: Set<TileSlot> get() = _socketSlots

    fun tagAsSocket(slot: TileSlot) {
        require(slot != TileSlot.FLOOR) { "Only wall slots can be tagged as sockets" }
        _socketSlots.add(slot)
    }

    fun untagSocket(slot: TileSlot) { _socketSlots.remove(slot) }

    fun isSocket(slot: TileSlot): Boolean = slot in _socketSlots

    // ── Ladder tags (per-edge) ──────────────────────────────────────────

    private val _ladderSlots = mutableSetOf<TileSlot>()
    val ladderSlots: Set<TileSlot> get() = _ladderSlots

    fun tagAsLadder(slot: TileSlot) {
        require(slot != TileSlot.FLOOR) { "Only wall slots can be tagged as ladders" }
        _ladderSlots.add(slot)
    }

    fun untagLadder(slot: TileSlot) { _ladderSlots.remove(slot) }

    fun isLadder(slot: TileSlot): Boolean = slot in _ladderSlots

    // ── Items & general tags ────────────────────────────────────────────

    val items = mutableListOf<Item>()
    val tags = mutableSetOf<String>()

    // ── Collision ───────────────────────────────────────────────────────

    /**
     * Returns true if movement through this node's wall in the given direction is blocked.
     * A wall blocks unless it is tagged as a door AND the door tile is open.
     */
    fun isWallBlocking(slot: TileSlot): Boolean {
        if (slot == TileSlot.FLOOR) return false
        val wall = tileSlots[slot] ?: return false
        if (isDoor(slot)) {
            // Door tile's isBlocking() returns false when open, true when closed
            return wall.isBlocking()
        }
        return true
    }

    // ── Reset ───────────────────────────────────────────────────────────

    fun clear() {
        tileSlots.clear()
        _doorSlots.clear()
        _manualDoorSlots.clear()
        _socketSlots.clear()
        _ladderSlots.clear()
        items.clear()
        tags.clear()
    }
}
