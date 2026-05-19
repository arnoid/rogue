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

    /**
     * Monitor protecting [placedSubmaps] and [occupiedGrid] from
     * concurrent access. The render thread reads these collections every
     * frame (e.g. via [roomsAdjacentTo] / [getSubmapAt]) while background
     * coroutines mutate them inside [generateNeighbors]. Use
     * `synchronized(stateLock) { ... }` around any read that iterates
     * [placedSubmaps] or any write that appends to it.
     */
    private val stateLock = Any()

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
     * Recursive variant of [generateNeighbors]: after each child submap is
     * placed, queue it for its own neighbour expansion. The traversal is
     * breadth-first so the world grows in concentric rings around [target].
     *
     * @param target Submap whose open sockets seed the expansion.
     * @param maxDepth How many rings outward to grow. `0` = no expansion,
     *                 `1` = same as [generateNeighbors], `2` = also expand
     *                 each freshly-placed neighbour once, etc.
     */
    suspend fun generateNeighborsRecursive(target: PlacedSubmap, maxDepth: Int) {
        if (maxDepth <= 0) return
        // BFS queue of (submap, depth) — process the seed first, then everything
        // we placed while expanding it, and so on. Each PlacedSubmap is added at
        // most once thanks to the `seen` set keyed on the unique origin.
        val queue: ArrayDeque<Pair<PlacedSubmap, Int>> = ArrayDeque()
        val seen = HashSet<Vector3Int>()
        queue.addLast(target to 0)
        seen.add(target.origin)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue

            // Snapshot the placed-submaps count before expansion so we can
            // pick up every newly-placed child below.
            val placedBefore = placedSubmaps.size
            generateNeighbors(current)

            // Anything appended to placedSubmaps during the call above is a
            // direct child of `current` — enqueue them for further growth.
            for (i in placedBefore until placedSubmaps.size) {
                val child = placedSubmaps[i]
                if (seen.add(child.origin)) {
                    queue.addLast(child to (depth + 1))
                }
            }
        }
    }

    /**
     * After placing a new submap, checks if any of its OPEN sockets are adjacent to
     * existing submaps' OPEN sockets. If so, with (1 - adjacentSealProbability) chance
     * they are connected; otherwise both are sealed.
     */
    private fun resolveAdjacentSockets(newlyPlaced: PlacedSubmap) {
        // Snapshot under the lock so a concurrent placeSubmap can't mutate
        // the list while we iterate it. Socket state mutations inside the
        // body operate on objects already present in the snapshot, so they
        // remain visible to subsequent reads.
        val others = synchronized(stateLock) { placedSubmaps.toList() }
        for (socket in newlyPlaced.sockets) {
            if (socket.state != SocketState.OPEN) continue

            val absPos = newlyPlaced.absoluteSocketPosition(socket)
            val neighborNodePos = absPos + socket.direction
            val oppositeDir = socket.direction.negate()

            // Find an existing placed submap (not this one) that has an OPEN socket
            // at the neighbor position facing back toward us
            for (other in others) {
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
        synchronized(stateLock) {
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
        }
        return true
    }

    private fun placeSubmap(template: SubmapTemplate, origin: Vector3Int): PlacedSubmap {
        val freshSockets = template.sockets.map { it.copy(state = SocketState.OPEN) }
        // Bake the template's lights into world-space coordinates and hand
        // them to the room so callers can address "this room's lights"
        // without scanning the template again.
        val roomLights = template.worldData.lightSources.map { ls ->
            ls.copy(
                x = ls.x + origin.x,
                y = ls.y + origin.y,
                z = ls.z + origin.z
            )
        }
        val placed = PlacedSubmap(template, origin, freshSockets, roomLights)
        synchronized(stateLock) {
            occupiedGrid.addAll(placed.occupiedBaseUnits())
            placedSubmaps.add(placed)
        }
        return placed
    }

    /**
     * Returns every room directly connected to [room] through a CONNECTED
     * socket (i.e. one room "hop" away along the placed-room socket graph).
     * Used by the procedural manager to decide which rooms should be
     * expanded based on the player's current location.
     */
    fun roomsAdjacentTo(room: PlacedSubmap): List<PlacedSubmap> {
        val snapshot = synchronized(stateLock) { placedSubmaps.toList() }
        val out = mutableListOf<PlacedSubmap>()
        for (socket in room.sockets) {
            if (socket.state != SocketState.CONNECTED) continue
            val neighborPos = room.absoluteSocketPosition(socket) + socket.direction
            val other = snapshot.firstOrNull { it !== room && containsAbsolute(it, neighborPos) }
            if (other != null && other !in out) out.add(other)
        }
        return out
    }

    private fun containsAbsolute(room: PlacedSubmap, pos: Vector3Int): Boolean {
        val o = room.origin
        val f = room.template.footprint
        return pos.x in o.x until (o.x + f.x) &&
               pos.y in o.y until (o.y + f.y) &&
               pos.z in o.z until (o.z + f.z)
    }

    private fun findNextOpenSocket(): Pair<PlacedSubmap, Socket>? {
        val snapshot = synchronized(stateLock) { placedSubmaps.toList() }
        for (placed in snapshot) {
            for (socket in placed.sockets) {
                if (socket.state == SocketState.OPEN) return placed to socket
            }
        }
        return null
    }

    fun getSubmapAt(absolutePosition: Vector3Int): PlacedSubmap? {
        val snapshot = synchronized(stateLock) { placedSubmaps.toList() }
        return snapshot.find { placed ->
            val origin = placed.origin
            val foot = placed.template.footprint
            absolutePosition.x in origin.x until (origin.x + foot.x) &&
            absolutePosition.y in origin.y until (origin.y + foot.y) &&
            absolutePosition.z in origin.z until (origin.z + foot.z)
        }
    }

    /**
     * Thread-safe snapshot of [placedSubmaps] for external readers (the
     * render thread, the procedural manager's stamping pass, etc.). Always
     * use this instead of touching `placedSubmaps` directly when not
     * already holding `stateLock`.
     */
    fun placedSubmapsSnapshot(): List<PlacedSubmap> =
        synchronized(stateLock) { placedSubmaps.toList() }
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

