package com.roguelike.generation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.random.Random

/**
 * Socket-Based 3D Procedural Map Generator.
 */
class MapGenerator(
    templates: List<SubmapTemplate>,
    private val debugMode: Boolean = false,
    /** Probability (0..1) that an adjacent bonus connection is sealed instead of opened. */
    val adjacentSealProbability: Float = 0.25f
) {
    /** All templates expanded to include all 4 rotation variants. */
    private val allTemplates: List<SubmapTemplate> = templates.flatMap { it.allRotations() }.distinctBy {
        Triple(it.name, it.rotation, it.sockets.map { s -> s.localPosition to s.direction })
    }
    private val occupiedGrid = mutableSetOf<Vector3Int>()
    val placedSubmaps = mutableListOf<PlacedSubmap>()
    val debugChannel = Channel<DebugCandidate>(Channel.RENDEZVOUS)
    val decisionChannel = Channel<DebugDecision>(Channel.RENDEZVOUS)
    var listener: GenerationListener? = null

    fun placeInitial(template: SubmapTemplate, origin: Vector3Int = Vector3Int.ZERO): PlacedSubmap {
        val placed = placeSubmap(template, origin)
        listener?.onSubmapPlaced(placed)
        return placed
    }

    suspend fun generate(maxIterations: Int = 1000) {
        var iterations = 0
        while (iterations < maxIterations) {
            iterations++
            val openSocket = findNextOpenSocket() ?: break
            val (placed, socket) = openSocket
            val absolutePos = placed.absoluteSocketPosition(socket)
            val oppositeDir = socket.direction.negate()

            val candidates = allTemplates.filter { template ->
                template.sockets.any { s -> s.direction == oppositeDir && s.tag == socket.tag }
            }.shuffled()

            var connected = false
            for (candidate in candidates) {
                val matchingSockets = candidate.sockets.filter { s ->
                    s.direction == oppositeDir && s.tag == socket.tag
                }.shuffled()
                for (matchSocket in matchingSockets) {
                    val candidateOrigin = absolutePos + socket.direction - matchSocket.localPosition
                    if (!canPlace(candidate, candidateOrigin)) continue

                    if (debugMode) {
                        debugChannel.send(DebugCandidate(candidate, candidateOrigin, socket, matchSocket))
                        val decision = decisionChannel.receive()
                        if (decision == DebugDecision.REJECT) continue
                    }

                    val placedCandidate = placeSubmap(candidate, candidateOrigin)
                    socket.state = SocketState.CONNECTED
                    placedCandidate.sockets.find {
                        it.localPosition == matchSocket.localPosition && it.direction == matchSocket.direction
                    }?.state = SocketState.CONNECTED

                    listener?.onSubmapPlaced(placedCandidate)
                    resolveAdjacentSockets(placedCandidate)
                    connected = true
                    break
                }
                if (connected) break
            }

            if (!connected) {
                socket.state = SocketState.SEALED
                listener?.onSocketSealed(placed, socket)
            }
        }
        listener?.onGenerationComplete()
    }

    suspend fun generateNeighbors(target: PlacedSubmap) {
        println("[MapGenerator] generateNeighbors for '${target.template.name}' at ${target.origin}, open sockets: ${target.sockets.count { it.state == SocketState.OPEN }}")
        for (socket in target.sockets) {
            if (socket.state != SocketState.OPEN) continue
            val absolutePos = target.absoluteSocketPosition(socket)
            val oppositeDir = socket.direction.negate()

            val candidates = allTemplates.filter { template ->
                template.sockets.any { s -> s.direction == oppositeDir && s.tag == socket.tag }
            }.shuffled()

            println("[MapGenerator]   Socket at ${socket.localPosition} dir=${socket.direction} tag='${socket.tag}' -> ${candidates.size} candidate templates")

            var connected = false
            for (candidate in candidates) {
                val matchingSockets = candidate.sockets.filter { s ->
                    s.direction == oppositeDir && s.tag == socket.tag
                }.shuffled()
                for (matchSocket in matchingSockets) {
                    val candidateOrigin = absolutePos + socket.direction - matchSocket.localPosition
                    if (!canPlace(candidate, candidateOrigin)) continue

                    if (debugMode) {
                        debugChannel.send(DebugCandidate(candidate, candidateOrigin, socket, matchSocket))
                        val decision = decisionChannel.receive()
                        if (decision == DebugDecision.REJECT) continue
                    }

                    val placedCandidate = placeSubmap(candidate, candidateOrigin)
                    socket.state = SocketState.CONNECTED
                    placedCandidate.sockets.find {
                        it.localPosition == matchSocket.localPosition && it.direction == matchSocket.direction
                    }?.state = SocketState.CONNECTED

                    listener?.onSubmapPlaced(placedCandidate)
                    resolveAdjacentSockets(placedCandidate)
                    println("[MapGenerator]     CONNECTED '${candidate.name}' rot=${candidate.rotation} at $candidateOrigin")
                    connected = true
                    break
                }
                if (connected) break
            }

            if (!connected) {
                socket.state = SocketState.SEALED
                println("[MapGenerator]   Socket SEALED (no candidate fit)")
                listener?.onSocketSealed(target, socket)
            }
        }
    }

    /**
     * After placing a new submap, checks if any of its OPEN sockets are adjacent to
     * existing submaps' OPEN sockets. If so, with (1 - adjacentSealProbability) chance
     * they are connected; otherwise both are sealed.
     */
    private fun resolveAdjacentSockets(newlyPlaced: PlacedSubmap) {
        for (socket in newlyPlaced.sockets) {
            if (socket.state != SocketState.OPEN) continue

            val absPos = newlyPlaced.absoluteSocketPosition(socket)
            val neighborNodePos = absPos + socket.direction
            val oppositeDir = socket.direction.negate()

            // Find an existing placed submap (not this one) that has an OPEN socket
            // at the neighbor position facing back toward us
            for (other in placedSubmaps) {
                if (other === newlyPlaced) continue
                for (otherSocket in other.sockets) {
                    if (otherSocket.state != SocketState.OPEN) continue
                    val otherAbsPos = other.absoluteSocketPosition(otherSocket)
                    if (otherAbsPos == neighborNodePos && otherSocket.direction == oppositeDir) {
                        // Found an adjacent matching socket pair
                        if (Random.nextFloat() < adjacentSealProbability) {
                            // Seal both sides
                            socket.state = SocketState.SEALED
                            otherSocket.state = SocketState.SEALED
                            println("[MapGenerator]     Adjacent socket SEALED (probability): ${socket.localPosition} dir=${socket.direction} <-> ${otherSocket.localPosition} dir=${otherSocket.direction}")
                            listener?.onSocketSealed(newlyPlaced, socket)
                            listener?.onSocketSealed(other, otherSocket)
                        } else {
                            // Connect both sides
                            socket.state = SocketState.CONNECTED
                            otherSocket.state = SocketState.CONNECTED
                            println("[MapGenerator]     Adjacent socket CONNECTED: ${socket.localPosition} dir=${socket.direction} <-> ${otherSocket.localPosition} dir=${otherSocket.direction}")
                        }
                    }
                }
            }
        }
    }

    private fun canPlace(template: SubmapTemplate, origin: Vector3Int): Boolean {
        // Prevent negative origins (world can only grow in positive direction)
        if (origin.x < 0 || origin.y < 0 || origin.z < 0) {
            println("[MapGenerator]     canPlace REJECTED '${template.name}' at $origin: negative origin")
            return false
        }
        val bu = template.baseUnitFootprint
        val baseOrigin = Vector3Int(origin.x / 3, origin.y / 3, origin.z / 3)
        for (bx in 0 until bu.x) {
            for (by in 0 until bu.y) {
                for (bz in 0 until bu.z) {
                    if (Vector3Int(baseOrigin.x + bx, baseOrigin.y + by, baseOrigin.z + bz) in occupiedGrid) {
                        println("[MapGenerator]     canPlace REJECTED '${template.name}' at $origin: occupied at base unit (${baseOrigin.x + bx},${baseOrigin.y + by},${baseOrigin.z + bz})")
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun placeSubmap(template: SubmapTemplate, origin: Vector3Int): PlacedSubmap {
        val freshSockets = template.sockets.map { it.copy(state = SocketState.OPEN) }
        val placed = PlacedSubmap(template, origin, freshSockets)
        occupiedGrid.addAll(placed.occupiedBaseUnits())
        placedSubmaps.add(placed)
        return placed
    }

    private fun findNextOpenSocket(): Pair<PlacedSubmap, Socket>? {
        for (placed in placedSubmaps) {
            for (socket in placed.sockets) {
                if (socket.state == SocketState.OPEN) return placed to socket
            }
        }
        return null
    }

    fun getSubmapAt(absolutePosition: Vector3Int): PlacedSubmap? {
        return placedSubmaps.find { placed ->
            val origin = placed.origin
            val foot = placed.template.footprint
            absolutePosition.x in origin.x until (origin.x + foot.x) &&
            absolutePosition.y in origin.y until (origin.y + foot.y) &&
            absolutePosition.z in origin.z until (origin.z + foot.z)
        }
    }
}

data class DebugCandidate(
    val template: SubmapTemplate,
    val origin: Vector3Int,
    val sourceSocket: Socket,
    val targetSocket: Socket
)

enum class DebugDecision { CONFIRM, REJECT }

interface GenerationListener {
    fun onSubmapPlaced(placed: PlacedSubmap) {}
    fun onSocketSealed(placed: PlacedSubmap, socket: Socket) {}
    fun onGenerationComplete() {}
}

