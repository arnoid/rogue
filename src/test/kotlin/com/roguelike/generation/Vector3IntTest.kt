package com.roguelike.generation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Vector3IntTest {

    @Test
    fun `plus adds components`() {
        val a = Vector3Int(1, 2, 3)
        val b = Vector3Int(4, 5, 6)
        assertEquals(Vector3Int(5, 7, 9), a + b)
    }

    @Test
    fun `minus subtracts components`() {
        assertEquals(Vector3Int(1, 1, 1), Vector3Int(3, 3, 3) - Vector3Int(2, 2, 2))
    }

    @Test
    fun `times scales all components`() {
        assertEquals(Vector3Int(6, 9, 12), Vector3Int(2, 3, 4) * 3)
    }

    @Test
    fun `negate flips sign of all components`() {
        assertEquals(Vector3Int(-1, 2, -3), Vector3Int(1, -2, 3).negate())
    }

    @Test
    fun `ZERO is origin`() {
        assertEquals(Vector3Int(0, 0, 0), Vector3Int.ZERO)
    }

    @Test
    fun `companion direction constants are unit vectors`() {
        assertEquals(Vector3Int(0, 1, 0), Vector3Int.NORTH)
        assertEquals(Vector3Int(0, -1, 0), Vector3Int.SOUTH)
        assertEquals(Vector3Int(1, 0, 0), Vector3Int.EAST)
        assertEquals(Vector3Int(-1, 0, 0), Vector3Int.WEST)
        assertEquals(Vector3Int(0, 0, 1), Vector3Int.UP)
        assertEquals(Vector3Int(0, 0, -1), Vector3Int.DOWN)
    }

    @Test
    fun `NORTH and SOUTH are negations of each other`() {
        assertEquals(Vector3Int.NORTH, Vector3Int.SOUTH.negate())
    }

    @Test
    fun `rotateCW90 of NORTH gives EAST`() {
        // NORTH=(0,1,0) rotated 90° CW around Z: (x,y) -> (y,-x) = (1,0) = EAST
        val rotated = Vector3Int.NORTH.rotateCW90()
        assertEquals(Vector3Int.EAST, rotated)
    }

    @Test
    fun `rotateCW90 four times is identity`() {
        val start = Vector3Int(1, 2, 3)
        val result = start.rotateCW90().rotateCW90().rotateCW90().rotateCW90()
        assertEquals(start, result)
    }
}
