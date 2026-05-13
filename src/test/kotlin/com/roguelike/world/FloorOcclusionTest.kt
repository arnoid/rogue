package com.roguelike.world

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FloorOcclusionTest {

    @Test
    fun `FloorTile blocksLight returns true`() {
        val tile = FloorTile()
        assertTrue(tile.blocksLight(), "FloorTile must block light even though it does not block movement")
    }

    @Test
    fun `FloorTile isBlocking returns false`() {
        val tile = FloorTile()
        assertFalse(tile.isBlocking(), "FloorTile must remain movement-passable")
    }

    @Test
    fun `WallNorthTile blocksLight returns true`() {
        val tile = WallNorthTile()
        assertTrue(tile.blocksLight(), "Walls must block light")
    }

    @Test
    fun `open DoorNorthTile blocksLight returns false`() {
        val tile = DoorNorthTile(isOpen = true)
        assertFalse(tile.blocksLight(), "Open door must not block light")
    }

    @Test
    fun `closed DoorNorthTile blocksLight returns true`() {
        val tile = DoorNorthTile(isOpen = false)
        assertTrue(tile.blocksLight(), "Closed door must block light")
    }

    @Test
    fun `StairsTile blocksLight returns false`() {
        val tile = StairsTile()
        assertFalse(tile.blocksLight(), "Stairs must not block light (same as movement)")
    }
}
