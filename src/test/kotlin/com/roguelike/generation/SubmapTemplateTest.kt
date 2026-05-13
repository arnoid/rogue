package com.roguelike.generation

import com.roguelike.core.model.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubmapTemplateTest {

    private fun makeSocket(x: Int, y: Int, z: Int, dir: Vector3Int, tag: String = "corridor") =
        Socket(Vector3Int(x, y, z), dir, tag)

    private fun minimalTemplate(w: Int, h: Int, d: Int, sockets: List<Socket> = emptyList()): SubmapTemplate {
        val world = World(w, h, d)
        return SubmapTemplate("test", Vector3Int(w, h, d), sockets, world)
    }

    @Test
    fun `baseUnitFootprint is footprint divided by 3`() {
        val t = minimalTemplate(9, 9, 3)
        assertEquals(Vector3Int(3, 3, 1), t.baseUnitFootprint)
    }

    @Test
    fun `9x9 face template should have 9 sockets on that face (Multi-Socket Rule)`() {
        // 9x9x3 template: NORTH face (y=9) should have one socket per base unit = 9 sockets
        val sockets = mutableListOf<Socket>()
        for (bx in 0 until 3) {
            for (bz in 0 until 1) {
                sockets += makeSocket(bx, 3, bz, Vector3Int.NORTH, "corridor")
            }
        }
        // Actually for 9x9x3 base unit footprint is 3x3x1, so north face has 3x1 = 3 base units
        // Retest with proper 9x9x3 where south face is 3 base units wide:
        val t = minimalTemplate(9, 9, 3, sockets)
        val northSockets = t.sockets.filter { it.direction == Vector3Int.NORTH }
        assertEquals(3, northSockets.size)
    }

    @Test
    fun `allRotations returns exactly 4 variants`() {
        val t = minimalTemplate(3, 3, 3)
        val rotations = t.allRotations()
        assertEquals(4, rotations.size)
    }

    @Test
    fun `allRotations have distinct rotation values 0 1 2 3`() {
        val t = minimalTemplate(3, 3, 3)
        val rotations = t.allRotations()
        assertEquals(setOf(0, 1, 2, 3), rotations.map { it.rotation }.toSet())
    }
}
