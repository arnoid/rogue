package com.roguelike.generation

import com.roguelike.core.model.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlacedSubmapTest {

    private fun minimalTemplate(w: Int, h: Int, d: Int): SubmapTemplate {
        val world = World(w, h, d)
        return SubmapTemplate("test", Vector3Int(w, h, d), emptyList(), world)
    }

    @Test
    fun `occupiedBaseUnits count equals product of baseUnit footprint dimensions`() {
        val template = minimalTemplate(3, 3, 3) // 1x1x1 base units
        val placed = PlacedSubmap(template, Vector3Int(0, 0, 0), emptyList())
        val cells = placed.occupiedBaseUnits()
        assertEquals(1, cells.size) // 1*1*1
    }

    @Test
    fun `occupiedBaseUnits for 9x9x3 template occupies 9 base units`() {
        val template = minimalTemplate(9, 9, 3) // 3x3x1 = 9 base units
        val placed = PlacedSubmap(template, Vector3Int(0, 0, 0), emptyList())
        assertEquals(9, placed.occupiedBaseUnits().size)
    }

    @Test
    fun `all occupied cells fall within footprint bounds`() {
        val template = minimalTemplate(6, 6, 3) // 2x2x1 = 4 base units
        val origin = Vector3Int(0, 0, 0)
        val placed = PlacedSubmap(template, origin, emptyList())
        val bu = template.baseUnitFootprint
        val cells = placed.occupiedBaseUnits()
        assertEquals(4, cells.size)
        cells.forEach { cell ->
            assertTrue(cell.x in 0 until bu.x, "x=${cell.x} out of bounds [0,${bu.x})")
            assertTrue(cell.y in 0 until bu.y, "y=${cell.y} out of bounds [0,${bu.y})")
            assertTrue(cell.z in 0 until bu.z, "z=${cell.z} out of bounds [0,${bu.z})")
        }
    }

    @Test
    fun `absoluteSocketPosition adds origin to local position`() {
        val template = minimalTemplate(3, 3, 3)
        val socket = Socket(Vector3Int(0, 0, 1), Vector3Int.NORTH, "corridor")
        val placed = PlacedSubmap(template, Vector3Int(3, 3, 0), listOf(socket))
        val absPos = placed.absoluteSocketPosition(socket)
        assertEquals(Vector3Int(3, 3, 1), absPos)
    }
}
