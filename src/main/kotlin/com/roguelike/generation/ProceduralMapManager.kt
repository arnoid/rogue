package com.roguelike.generation


import com.roguelike.core.model.Tile
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode
import com.roguelike.serialization.WorldIO
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manages the procedural map generation lifecycle for the Arena mode.
 *
 * The active World starts as the initial submap and grows dynamically
 * as new submaps are connected via sockets.
 */
class ProceduralMapManager(
    private val tileFactory: (String) -> Tile?,
    private val worldFactory: (Int, Int, Int) -> World
) {
    /** The active game world that grows as submaps are added. */
    var activeWorld: World? = null
        private set

    /** The map generator instance. */
    var generator: MapGenerator? = null
        private set

    private val stamper = WorldStamper(tileFactory)
    private val loadedTemplates = mutableListOf<SubmapTemplate>()
    private val generationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Serializes all generator-mutating coroutines. `MapGenerator` keeps
     * shared mutable state (`placedSubmaps`, `occupiedGrid`, socket
     * states); when two `scheduleExpansionFrom` ticks ran concurrently the
     * inner BFS of `resolveAdjacentSockets` iterated `placedSubmaps` while
     * the other worker was appending to it, raising
     * ConcurrentModificationException. Wrapping every `generateNeighbors`
     * call (and the planning that reads generator state) in this mutex
     * makes mutation single-threaded without blocking the GL thread.
     */
    private val generationMutex = Mutex()

    /** Whether debug step-through is enabled. */
    var debugEnabled = false

    /** Callback for debug UI. */
    var debugCallback: DebugUICallback? = null

    /**
     * Rooms whose open sockets we've already tried to expand (i.e. fed to
     * [MapGenerator.generateNeighbors]). Each room expands at most once;
     * after that its sockets are either CONNECTED or SEALED for good.
     * Keyed on the room's origin (unique per placement).
     */
    private val expandedRooms = mutableSetOf<Vector3Int>()

    /** Set of submap origins already stamped into the active world. */
    private val stampedSubmaps = mutableSetOf<Vector3Int>()

    /**
     * How many rooms ahead of the player to keep procedurally populated.
     * `1` keeps just the immediate neighbours of the player's room; `3` (the
     * default) keeps the player's room, its neighbours, the neighbours'
     * neighbours, and one more ring on top of that — so the player can see
     * three rooms in any direction without ever stepping into an
     * un-generated socket.
     */
    var roomGenerationDistance: Int = 3

    /**
     * Loads all .wld template files from the given directory.
     */
    fun loadTemplates(directory: String) {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            println("[ProceduralMapManager] " + "Template directory not found: $directory")
            return
        }

        loadTemplateFiles(dir.walkTopDown().filter { it.isFile && it.extension == "wld" }.toList())
    }

    /**
     * Loads a curated list of .wld template files (e.g. the set referenced
     * by a biome definition). Mirrors [loadTemplates] but skips directory
     * walking — the caller has already decided which files belong to the
     * active biome's pool.
     */
    fun loadTemplateFiles(files: Iterable<File>) {
        for (file in files) {
            val world = WorldIO.loadWorld(
                file.absolutePath,
                worldFactory,
                tileFactory
            )
            if (world != null) {
                val template = SubmapTemplate.fromWorld(file.nameWithoutExtension, world)
                if (template.sockets.isNotEmpty()) {
                    loadedTemplates.add(template)
                    println("[ProceduralMapManager] " + "Loaded template: ${file.name} with ${template.sockets.size} sockets")
                } else {
                    println("[ProceduralMapManager] " + "Skipped template with no sockets: ${file.name}")
                }
            }
        }

        println("[ProceduralMapManager] " + "Loaded ${loadedTemplates.size} templates total")
    }

    /**
     * Loads the initial submap from a specific file and starts generation.
     * The world starts exactly as the initial submap (at origin 0,0,0) and grows from there.
     *
     * @param initialPath Path to the initial submap .wld file.
     * @return The active World ready for gameplay, or null on failure.
     */
    fun initialize(initialPath: String): World? {
        val initialWorld = WorldIO.loadWorld(initialPath, worldFactory, tileFactory)
        if (initialWorld == null) {
            System.err.println("[ProceduralMapManager] " + "Failed to load initial submap: $initialPath")
            return null
        }

        // Verify it has player_spawn
        val hasSpawn = (0 until initialWorld.width).any { x ->
            (0 until initialWorld.height).any { y ->
                (0 until initialWorld.depth).any { z ->
                    initialWorld.getNode(x, y, z)?.tags?.contains(WorldNode.Tags.PLAYER_SPAWN) == true
                }
            }
        }
        if (!hasSpawn) {
            System.err.println("[ProceduralMapManager] " + "Initial submap has no player_spawn tag!")
            return null
        }

        val initialTemplate = SubmapTemplate.fromWorld("initial", initialWorld)

        // Place initial submap at an offset so there's room to grow in all directions
        val initialOffset = Vector3Int(30, 30, 0)

        // Create a world large enough to hold the offset + initial submap + room to grow
        val startWidth = initialOffset.x + initialTemplate.footprint.x + 30
        val startHeight = initialOffset.y + initialTemplate.footprint.y + 30
        val startDepth = maxOf(initialTemplate.footprint.z + 6, 6)
        // Round up to multiples of 3
        val w = ((startWidth + 2) / 3) * 3
        val h = ((startHeight + 2) / 3) * 3
        val d = ((startDepth + 2) / 3) * 3
        val world = worldFactory(w, h, d)
        activeWorld = world

        // Initialize generator with loaded templates only (starting submap is NOT used as random template)
        generator = MapGenerator(loadedTemplates, debugEnabled)

        // If debug is enabled, launch a coroutine that bridges the generator's
        // debug channels to the UI callback
        if (debugEnabled && debugCallback != null) {
            generationScope.launch {
                val gen = generator!!
                while (isActive) {
                    val candidate = gen.debugChannel.receive()
                    val decision = CompletableDeferred<DebugDecision>()
                    /* postRunnable */ run {
                        debugCallback?.showCandidate(
                            candidate,
                            onConfirm = { decision.complete(DebugDecision.CONFIRM) },
                            onReject = { decision.complete(DebugDecision.REJECT) }
                        )
                    }
                    val result = decision.await()
                    gen.decisionChannel.send(result)
                }
            }
        }

        // Place initial at offset and stamp it into the world
        val placed = generator!!.placeInitial(initialTemplate, initialOffset)
        stampedSubmaps.add(initialOffset)
        stamper.stamp(placed, world)
        publishRoomLights(placed)

        // Open any pre-connected sockets on the initial submap
        for (socket in placed.sockets) {
            if (socket.state == SocketState.CONNECTED) {
                stamper.openConnection(placed, socket, world)
            }
        }

        println("[ProceduralMapManager] " + "Initial room at $initialOffset, size=${initialTemplate.footprint}, sockets=${placed.sockets.size}, world=${world.width}x${world.height}x${world.depth}")

        // Spawn the first ring of rooms: the player starts inside [placed]
        // and is therefore zero rooms away from every socket on it — so we
        // expand the initial room itself (which adds rooms through each of
        // its open sockets). The next ring is added on demand as the player
        // crosses into a freshly-stamped neighbour (see [onPlayerMove]).
        scheduleExpansionFrom(placed)

        return activeWorld
    }

    /**
     * Called each frame by the gameplay loop with the player's current
     * world-space position. The procedural generator grows rooms that the
     * player is at most [roomGenerationDistance] rooms away from, so the
     * world always has at least that many already-stamped rooms ahead of
     * the player in every direction. Each room is expanded at most once
     * (see [expandedRooms]).
     */
    fun onPlayerMove(playerX: Float, playerY: Float, playerZ: Float) {
        val gen = generator ?: return

        val absPos = Vector3Int(playerX.toInt(), playerY.toInt(), playerZ.toInt())
        val currentRoom = gen.getSubmapAt(absPos) ?: return
        scheduleExpansionFrom(currentRoom)
    }

    /**
     * Expand every not-yet-expanded room within [roomGenerationDistance]
     * hops of [seed] along the CONNECTED room socket graph. The expansion
     * runs on a background coroutine; once it finishes, stamping happens
     * on the GL thread and we recursively schedule another expansion from
     * [seed] so the newly-placed rooms (which have now lengthened the
     * graph by one hop) get a chance to feed further generation, until the
     * full depth-[roomGenerationDistance] frontier is populated.
     */
    private fun scheduleExpansionFrom(seed: PlacedSubmap) {
        val gen = generator ?: return

        // Run the whole planning + generation + stamping pipeline on a
        // worker, serialized through [generationMutex]. The mutex matters
        // because multiple player-move ticks (and the recursive
        // re-schedule below) can otherwise produce overlapping coroutines
        // that mutate `gen.placedSubmaps` while another worker iterates
        // it — that race triggered the ConcurrentModificationException
        // we used to crash on inside `resolveAdjacentSockets`.
        generationScope.launch {
            generationMutex.withLock {
                // BFS over currently-placed rooms up to roomGenerationDistance hops.
                // Anything in that radius that still has OPEN sockets and hasn't
                // been expanded yet becomes a fresh generation seed.
                val maxDepth = roomGenerationDistance.coerceAtLeast(0)
                val visited = HashSet<Vector3Int>()
                visited.add(seed.origin)
                val roomsToExpand = mutableListOf<PlacedSubmap>()
                if (seed.origin !in expandedRooms && seed.sockets.any { it.state == SocketState.OPEN }) {
                    roomsToExpand.add(seed)
                }
                var frontier: List<PlacedSubmap> = listOf(seed)
                var depth = 0
                while (depth < maxDepth && frontier.isNotEmpty()) {
                    val next = mutableListOf<PlacedSubmap>()
                    for (room in frontier) {
                        for (neighbour in gen.roomsAdjacentTo(room)) {
                            if (!visited.add(neighbour.origin)) continue
                            next.add(neighbour)
                            if (neighbour.origin in expandedRooms) continue
                            if (neighbour.sockets.none { it.state == SocketState.OPEN }) continue
                            if (roomsToExpand.none { it === neighbour }) roomsToExpand.add(neighbour)
                        }
                    }
                    frontier = next
                    depth++
                }
                if (roomsToExpand.isEmpty()) return@withLock

                // Reserve the slots immediately so concurrent player-move
                // ticks queued behind us don't double-schedule the same room.
                for (room in roomsToExpand) expandedRooms.add(room.origin)

                for (room in roomsToExpand) {
                    println("[ProceduralMapManager] " + "Expanding room '${room.template.name}' at ${room.origin}")
                    gen.generateNeighbors(room)
                }
                stampNewSubmaps()
            }

            // Newly placed rooms may have extended the reachable graph
            // beyond the previous frontier — re-schedule from the same
            // seed (after releasing the mutex) so the new rooms also get
            // expanded if they fall within the depth budget. The
            // expandedRooms guard ensures already-processed rooms are
            // skipped, so this terminates.
            scheduleExpansionFrom(seed)
        }
    }

    /**
     * Stamps any newly placed submaps into the active world, growing it as needed.
     */
    @Synchronized
    private fun stampNewSubmaps() {
        val world = activeWorld ?: return
        val gen = generator ?: return

        // Snapshot under the generator's state lock so we iterate a stable
        // view even when the caller is the render thread or another
        // background tick races us.
        val snapshot = gen.placedSubmapsSnapshot()

        for (placed in snapshot) {
            if (placed.origin !in stampedSubmaps) {
                stampedSubmaps.add(placed.origin)

                // Grow the world to fit this submap
                val needed = placed.origin + placed.template.footprint
                world.ensureSize(needed.x, needed.y, needed.z)

                println("[ProceduralMapManager] " + "Stamping '${placed.template.name}' rot=${placed.template.rotation} at ${placed.origin}, world now ${world.width}x${world.height}x${world.depth}")
                stamper.stamp(placed, world)
                publishRoomLights(placed)

                // Open connections between connected sockets, seal dead ends
                for (socket in placed.sockets) {
                    if (socket.state == SocketState.CONNECTED) {
                        stamper.openConnection(placed, socket, world)
                        println("[ProceduralMapManager] " + "  Opened connection at ${socket.localPosition} dir=${socket.direction}")
                    } else if (socket.state == SocketState.SEALED) {
                        stamper.sealConnection(placed, socket, world)
                        println("[ProceduralMapManager] " + "  Sealed socket at ${socket.localPosition} dir=${socket.direction}")
                    }
                }
            }
        }

        // Seal any newly-sealed sockets on already-stamped submaps (e.g. initial submap)
        for (placed in snapshot) {
            for (socket in placed.sockets) {
                if (socket.state == SocketState.SEALED) {
                    stamper.sealConnection(placed, socket, world)
                }
            }
        }
    }

    /**
     * Surface a room's owned light sources into the live world's global
     * light list so the lighting upload pipeline can see them. Each room
     * is published exactly once (alongside its stamp).
     */
    private fun publishRoomLights(room: PlacedSubmap) {
        val world = activeWorld ?: return
        if (room.lightSources.isEmpty()) return
        world.lightSources.addAll(room.lightSources)
        println("[ProceduralMapManager] " + "  Published ${room.lightSources.size} room lights from '${room.template.name}'")
    }

    /**
     * Returns every light source owned by a room within [maxRoomDistance]
     * hops of the room currently containing the player. "Hop" means a
     * CONNECTED socket — the same notion of room adjacency used by the
     * generator's expansion logic. `maxRoomDistance == 0` returns just the
     * lights inside the player's current room; `maxRoomDistance == 2` adds
     * the player's room, its neighbours, and the neighbours' neighbours.
     *
     * **Ordering.** The returned list is grouped by hop distance — all
     * lights from the player's room first, then all lights one hop away,
     * then two hops, and so on. Within a single ring, lights are sorted
     * by Euclidean distance to the player. Downstream consumers can take a
     * prefix of this list (e.g. the first `MAX_LIGHTS`) and be sure they
     * always retain the most relevant lights: nearer rooms outrank farther
     * rooms unconditionally, and within a room the closest fixtures win.
     *
     * If the player isn't inside any tracked room (e.g. they're standing
     * in a brief untracked gap such as a doorway voxel or floating above a
     * floor between Z layers), this falls back to the **closest** placed
     * room as the BFS seed — that keeps the result bounded to the same
     * `maxRoomDistance` horizon instead of dumping the entire world's
     * light list, so distant lights still get culled and the priority
     * order keeps updating as the player moves.
     */
    fun collectVisibleRoomLights(
        playerX: Float, playerY: Float, playerZ: Float,
        maxRoomDistance: Int
    ): List<com.roguelike.core.model.LightSource> {
        val gen = generator ?: run {
            lightDebugLog("no generator yet, returning empty list", playerX, playerY, playerZ, null, emptyList())
            return emptyList()
        }
        val absPos = Vector3Int(playerX.toInt(), playerY.toInt(), playerZ.toInt())
        val containing = gen.getSubmapAt(absPos)
        val seedRoom: PlacedSubmap = containing
            ?: closestRoomTo(playerX, playerY, playerZ, gen)
            ?: run {
                lightDebugLog("no placed rooms at all, returning empty list", playerX, playerY, playerZ, null, emptyList())
                return emptyList()
            }

        // BFS over the room socket graph, capped at `maxRoomDistance` hops.
        // Each ring's lights are appended in distance-to-player order, so
        // the final list is "ring 0 sorted, ring 1 sorted, ring 2 sorted…".
        fun sortedByDistance(lights: List<com.roguelike.core.model.LightSource>): List<com.roguelike.core.model.LightSource> {
            if (lights.size <= 1) return lights
            return lights.sortedBy { ls ->
                val dx = ls.x - playerX; val dy = ls.y - playerY; val dz = ls.z - playerZ
                dx * dx + dy * dy + dz * dz
            }
        }

        val visited = HashSet<Vector3Int>()
        visited.add(seedRoom.origin)
        val out = ArrayList<com.roguelike.core.model.LightSource>()
        out.addAll(sortedByDistance(seedRoom.lightSources))

        // Per-ring trace: how many rooms walked, how many lights gathered.
        // Kept compact so it can be flushed every frame without flooding.
        val ringTrace = StringBuilder()
        ringTrace.append("seed='${seedRoom.template.name}'@${seedRoom.origin}")
        ringTrace.append(" containing=${containing != null}")
        ringTrace.append(" seedLights=${seedRoom.lightSources.size}")

        // If the player happens to be in a stretch of lightless rooms, the
        // strict `maxRoomDistance` horizon may produce zero lights and the
        // scene goes pitch black. In that case keep walking the room graph
        // outward (up to [maxExtendedSearch]) until at least one light is
        // found, so the player always has something to see by. The cheap
        // BFS only revisits unvisited rooms, so this is bounded.
        val maxExtendedSearch = (maxRoomDistance * 4).coerceAtLeast(maxRoomDistance + 6)

        var frontier: List<PlacedSubmap> = listOf(seedRoom)
        var depth = 0
        while (frontier.isNotEmpty()) {
            // Always honour the strict horizon. Beyond it we keep walking
            // ONLY if we still haven't found any lights at all.
            if (depth >= maxRoomDistance && out.isNotEmpty()) break
            if (depth >= maxExtendedSearch) break

            val nextFrontier = ArrayList<PlacedSubmap>()
            val ringLights = ArrayList<com.roguelike.core.model.LightSource>()
            for (room in frontier) {
                for (neighbour in gen.roomsAdjacentTo(room)) {
                    if (visited.add(neighbour.origin)) {
                        ringLights.addAll(neighbour.lightSources)
                        nextFrontier.add(neighbour)
                    }
                }
            }
            out.addAll(sortedByDistance(ringLights))
            ringTrace.append(" | ring${depth + 1}: rooms=${nextFrontier.size} lights=${ringLights.size}")
            frontier = nextFrontier
            depth++
        }

        lightDebugLog(ringTrace.toString(), playerX, playerY, playerZ, seedRoom, out)
        return out
    }

    // Last log fingerprint, so we only print when the situation actually
    // changes (room boundary crossed, light count changed, or seed
    // identity changed). Otherwise the per-frame call would flood stdout.
    private var lastLightDebugKey: String? = null

    private fun lightDebugLog(
        msg: String,
        px: Float, py: Float, pz: Float,
        seed: PlacedSubmap?,
        lights: List<com.roguelike.core.model.LightSource>
    ) {
        val seedKey = seed?.origin?.toString() ?: "none"
        val key = "$seedKey|${lights.size}|${msg.hashCode()}"
        if (key == lastLightDebugKey) return
        lastLightDebugKey = key

        val sample = lights.take(3).joinToString(", ") { ls ->
            val dx = ls.x - px; val dy = ls.y - py; val dz = ls.z - pz
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            "(%.1f,%.1f,%.1f r=%.1f d=%.1f)".format(ls.x, ls.y, ls.z, ls.radius, d)
        }
        println(
            "[LightSelect] " +
                "player=(%.2f,%.2f,%.2f) ".format(px, py, pz) +
                msg +
                " | total=${lights.size}" +
                (if (sample.isNotEmpty()) " | nearest=[$sample]" else "")
        )
    }

    /**
     * Returns the placed room whose centre is nearest to the given world
     * position. Used as a fallback seed for [collectVisibleRoomLights]
     * when the player is briefly outside any room's AABB (doorways,
     * between-floor gaps, etc.) so the lighting horizon doesn't snap to
     * the entire world's light list.
     */
    private fun closestRoomTo(px: Float, py: Float, pz: Float, gen: MapGenerator): PlacedSubmap? {
        var best: PlacedSubmap? = null
        var bestDist = Float.MAX_VALUE
        // Snapshot the placed list — the render thread calls us every frame
        // while background coroutines may be appending new rooms.
        for (room in gen.placedSubmapsSnapshot()) {
            val o = room.origin; val f = room.template.footprint
            val cx = o.x + f.x * 0.5f; val cy = o.y + f.y * 0.5f; val cz = o.z + f.z * 0.5f
            val dx = cx - px; val dy = cy - py; val dz = cz - pz
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestDist) { bestDist = d; best = room }
        }
        return best
    }

    fun dispose() {
        generationScope.cancel()
    }
}

/**
 * Callback interface for the debug step-through UI.
 */
interface DebugUICallback {
    fun showCandidate(candidate: DebugCandidate, onConfirm: () -> Unit, onReject: () -> Unit)
    fun hideDebugUI()
}

