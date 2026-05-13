package com.roguelike.generation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SocketTest {

    @Test
    fun `new socket starts OPEN`() {
        val s = Socket(Vector3Int.ZERO, Vector3Int.NORTH, "corridor")
        assertEquals(SocketState.OPEN, s.state)
    }

    @Test
    fun `socket state is mutable`() {
        val s = Socket(Vector3Int.ZERO, Vector3Int.NORTH, "corridor")
        s.state = SocketState.CONNECTED
        assertEquals(SocketState.CONNECTED, s.state)
        s.state = SocketState.SEALED
        assertEquals(SocketState.SEALED, s.state)
    }

    @Test
    fun `NORTH and SOUTH sockets with matching tags satisfy connection rule`() {
        val north = Socket(Vector3Int.ZERO, Vector3Int.NORTH, "corridor")
        val south = Socket(Vector3Int(0, 1, 0), Vector3Int.SOUTH, "corridor")
        assertTrue(north.tag == south.tag && north.direction == south.direction.negate(),
            "NORTH and SOUTH with same tag should satisfy connection rule")
    }

    @Test
    fun `mismatched tags do not satisfy connection rule`() {
        val a = Socket(Vector3Int.ZERO, Vector3Int.NORTH, "corridor")
        val b = Socket(Vector3Int(0, 1, 0), Vector3Int.SOUTH, "room")
        assertFalse(a.tag == b.tag,
            "Different tags should not satisfy connection rule")
    }

    @Test
    fun `same direction sockets do not satisfy connection rule`() {
        val a = Socket(Vector3Int.ZERO, Vector3Int.NORTH, "corridor")
        val b = Socket(Vector3Int(0, 1, 0), Vector3Int.NORTH, "corridor")
        assertFalse(a.direction == b.direction.negate(),
            "Same direction sockets should not satisfy connection rule (direction not opposing)")
    }
}
