package com.roguelike.generation


import com.roguelike.core.model.Tile
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode
import com.roguelike.serialization.WorldIO
import kotlinx.coroutines.*
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

    /** Whether debug step-through is enabled. */
    var debugEnabled = false

    /** Callback for debug UI. */
    var debugCallback: DebugUICallback? = null

    /** Set of submap origins whose neighbors have already been generated. */
    private val neighborsGenerated = mutableSetOf<Vector3Int>()

    /** Set of submap origins already stamped into the active world. */
    private val stampedSubmaps = mutableSetOf<Vector3Int>()

    /**
     * Loads all .wld template files from the given directory.
     */
    fun loadTemplates(directory: String) {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            println("[ProceduralMapManager] " + "Template directory not found: $directory")
            return
        }

        dir.walkTopDown().filter { it.isFile && it.extension == "wld" }.forEach { file ->
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

        // Open any pre-connected sockets on the initial submap
        for (socket in placed.sockets) {
            if (socket.state == SocketState.CONNECTED) {
                stamper.openConnection(placed, socket, world)
            }
        }

        neighborsGenerated.add(initialOffset)

        println("[ProceduralMapManager] " + "Initial submap at $initialOffset, size=${initialTemplate.footprint}, sockets=${placed.sockets.size}, world=${world.width}x${world.height}x${world.depth}")

        // Immediately generate adjacent submaps for seamless player experience
        generationScope.launch {
            println("[ProceduralMapManager] " + "Starting neighbor generation for initial submap...")
            generator!!.generateNeighbors(placed)
            println("[ProceduralMapManager] " + "Neighbor generation done. Total placed: ${generator!!.placedSubmaps.size}")
            /* postRunnable */ run { stampNewSubmaps() }
        }

        return activeWorld
    }

    /**
     * Called when the player moves into a new position.
     * Checks if they're entering a new submap region and triggers generation if needed.
     */
    fun onPlayerMove(playerX: Float, playerY: Float, playerZ: Float) {
        val gen = generator ?: return

        val absPos = Vector3Int(playerX.toInt(), playerY.toInt(), playerZ.toInt())
        val currentSubmap = gen.getSubmapAt(absPos)

        if (currentSubmap != null) {
            val hasOpenSockets = currentSubmap.sockets.any { it.state == SocketState.OPEN }
            if (hasOpenSockets && currentSubmap.origin !in neighborsGenerated) {
                neighborsGenerated.add(currentSubmap.origin)
                generationScope.launch {
                    gen.generateNeighbors(currentSubmap)
                    /* postRunnable */ run { stampNewSubmaps() }
                }
            }
        }
    }

    /**
     * Stamps any newly placed submaps into the active world, growing it as needed.
     */
    @Synchronized
    private fun stampNewSubmaps() {
        val world = activeWorld ?: return
        val gen = generator ?: return

        for (placed in gen.placedSubmaps) {
            if (placed.origin !in stampedSubmaps) {
                stampedSubmaps.add(placed.origin)

                // Grow the world to fit this submap
                val needed = placed.origin + placed.template.footprint
                world.ensureSize(needed.x, needed.y, needed.z)

                println("[ProceduralMapManager] " + "Stamping '${placed.template.name}' rot=${placed.template.rotation} at ${placed.origin}, world now ${world.width}x${world.height}x${world.depth}")
                stamper.stamp(placed, world)

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
        for (placed in gen.placedSubmaps) {
            for (socket in placed.sockets) {
                if (socket.state == SocketState.SEALED) {
                    stamper.sealConnection(placed, socket, world)
                }
            }
        }
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

