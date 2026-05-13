package com.roguelike.generation

import com.roguelike.core.model.World
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MapGeneratorIntegrationTest {

    /** 3x3x3 corridor: NORTH + SOUTH socket, tag "corridor". */
    private fun corridorTemplate(): SubmapTemplate {
        val world = World(3, 3, 3)
        val sockets = listOf(
            Socket(Vector3Int(0, 2, 0), Vector3Int.NORTH, "corridor"),
            Socket(Vector3Int(0, 0, 0), Vector3Int.SOUTH, "corridor")
        )
        return SubmapTemplate("corridor", Vector3Int(3, 3, 3), sockets, world)
    }

    /** 9x9x3 room: one SOUTH socket on the south face (tag "corridor") and three NORTH sockets on north face. */
    private fun roomTemplate(): SubmapTemplate {
        val world = World(9, 9, 3)
        // South face: 3 sockets (one per base unit column)
        val southSockets = (0 until 3).map { bx ->
            Socket(Vector3Int(bx, 0, 0), Vector3Int.SOUTH, "corridor")
        }
        // North face: 3 sockets
        val northSockets = (0 until 3).map { bx ->
            Socket(Vector3Int(bx, 8, 0), Vector3Int.NORTH, "corridor")
        }
        return SubmapTemplate("room", Vector3Int(9, 9, 3), southSockets + northSockets, world)
    }

    @Test
    fun `collision free and socket compatible after generation`() = runBlocking {
        val corridor = corridorTemplate()
        val room = roomTemplate()
        val gen = MapGenerator(listOf(corridor, room), debugMode = false)
        gen.placeInitial(corridor, Vector3Int(0, 0, 0))

        val start = System.currentTimeMillis()
        gen.generate(maxIterations = 20)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 5000, "Generation took ${elapsed}ms, expected < 5000ms (SC-004)")

        // Collision-free: no base unit occupied by more than one placed submap.
        val allCells = gen.placedSubmaps.flatMap { it.occupiedBaseUnits() }
        assertEquals(allCells.size, allCells.toSet().size,
            "SC-005: No base unit cell may be double-occupied")

        // Socket compatibility: every CONNECTED socket has a matching partner.
        for (placed in gen.placedSubmaps) {
            for (socket in placed.sockets) {
                when (socket.state) {
                    SocketState.CONNECTED -> {
                        // Find the partner submap sharing the adjacent position
                        val absPos = placed.absoluteSocketPosition(socket)
                        val neighborPos = absPos + socket.direction
                        val neighborBaseUnit = Vector3Int(neighborPos.x / 3, neighborPos.y / 3, neighborPos.z / 3)
                        val neighbor = gen.placedSubmaps.firstOrNull { other ->
                            other != placed && neighborBaseUnit in other.occupiedBaseUnits()
                        }
                        assertNotNull(neighbor,
                            "SC-005: CONNECTED socket at $absPos dir=${socket.direction} tag=${socket.tag} has no neighbor submap")
                        neighbor?.let {
                            val matchingSocket = it.sockets.firstOrNull { s ->
                                s.direction == socket.direction.negate() && s.tag == socket.tag
                            }
                            assertNotNull(matchingSocket,
                                "SC-005: No matching socket found in neighbor for tag=${socket.tag} opposite dir=${socket.direction.negate()}")
                        }
                    }
                    SocketState.OPEN -> {} // left-over open sockets are allowed after generate()
                    SocketState.SEALED -> {} // sealed is valid
                }
            }
        }
    }
}
