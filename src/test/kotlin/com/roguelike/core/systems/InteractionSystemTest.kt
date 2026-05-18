package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Player
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.world.DoorEastTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InteractionSystemTest {

    /**
     * Build a 9×9×3 world with a floor under the player and a single door
     * on the east wall of node (4,4,1). The door starts closed.
     *
     * @param manual whether the door should be tagged as DOOR_MANUAL
     */
    private fun buildWorldWithDoor(manual: Boolean): Triple<World, DoorEastTile, Player> {
        val world = World(9, 9, 3)
        // Ensure floors exist so the player doesn't fall and so adjacency is real.
        for (x in 3..5) for (y in 3..5) {
            world.getNode(x, y, 1)!!.let { n ->
                // (floor not strictly required for these tests but keeps things sane)
                // We rely on movement-blocking, not gravity, so we skip placing FloorTile
                // to avoid pulling in extra tile types.
                Unit
            }
        }
        val doorNode = world.getNode(4, 4, 1)!!
        val door = DoorEastTile(isOpen = false)
        doorNode.setTile(door)
        doorNode.tagAsDoor(TileSlot.WALL_EAST)
        if (manual) doorNode.tagAsManualDoor(TileSlot.WALL_EAST)

        val player = Player().apply { position.set(4.5f, 4.5f, 1f) }
        return Triple(world, door, player)
    }

    @Test
    fun manualDoor_startsClosed_andBlocksMovement() {
        val (world, door, _) = buildWorldWithDoor(manual = true)
        assertFalse(door.isOpen)
        assertTrue(door.isBlocking())
        // World should report the wall as blocking through the WALL_EAST face.
        assertTrue(world.getNode(4, 4, 1)!!.isWallBlocking(TileSlot.WALL_EAST))
    }

    @Test
    fun manualDoor_isToggledByInteract() {
        val (world, door, player) = buildWorldWithDoor(manual = true)
        val sys = InteractionSystem(world)

        sys.interact(player, player.facingDirection)
        assertTrue(door.isOpen, "door should be opened by interact()")
        assertFalse(door.isBlocking())
        assertFalse(world.getNode(4, 4, 1)!!.isWallBlocking(TileSlot.WALL_EAST))

        sys.interact(player, player.facingDirection)
        assertFalse(door.isOpen, "door should close again on second interact()")
        assertTrue(door.isBlocking())
    }

    @Test
    fun manualDoor_isNotAutoOpenedByMovement() {
        val (world, door, player) = buildWorldWithDoor(manual = true)
        val move = MovementSystem(world)
        // Push east into the door
        move.move(player, Vec3(1f, 0f, 0f), 0.1f, 3f)
        assertFalse(door.isOpen, "manual doors must not auto-open from movement")
    }

    @Test
    fun nonManualDoor_isNotAutoOpenedByMovement() {
        val (world, door, player) = buildWorldWithDoor(manual = false)
        val move = MovementSystem(world)
        assertFalse(door.isOpen)
        move.move(player, Vec3(1f, 0f, 0f), 0.1f, 3f)
        assertFalse(door.isOpen, "doors must never auto-open from movement; explicit interaction is required")
    }

    @Test
    fun nonManualDoor_isOpenedByInteract() {
        val (world, door, player) = buildWorldWithDoor(manual = false)
        val sys = InteractionSystem(world)
        assertFalse(door.isOpen)
        sys.interact(player, player.facingDirection)
        assertTrue(door.isOpen, "any door (manual or not) should be opened by interact()")
    }

    @Test
    fun autoDoor_isNotOpenedWhenWalkingAway() {
        val (world, door, player) = buildWorldWithDoor(manual = false)
        val move = MovementSystem(world)
        // Walk west, away from the east door
        move.move(player, Vec3(-1f, 0f, 0f), 0.1f, 3f)
        assertFalse(door.isOpen, "door should not open when actor walks away from it")
    }

    @Test
    fun tryOpenAdjacentManualDoor_opensClosedManualDoor() {
        val (world, door, player) = buildWorldWithDoor(manual = true)
        val sys = InteractionSystem(world)
        assertTrue(sys.tryOpenAdjacentManualDoor(player))
        assertTrue(door.isOpen)
        // Already open -> no further action
        assertFalse(sys.tryOpenAdjacentManualDoor(player))
    }
}



