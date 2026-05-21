package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
import com.roguelike.core.perf.PerfFlags
import com.roguelike.core.perf.WindowShiftHysteresis
import com.roguelike.core.systems.MovementSystem
import com.roguelike.core.systems.InteractionSystem
import com.roguelike.generation.BiomeDefinition
import com.roguelike.generation.ProceduralMapManager
import com.roguelike.input.InputSystem
import com.roguelike.rendering.Camera
import com.roguelike.rendering.DebugRenderer
import com.roguelike.serialization.WorldIO
import com.roguelike.systems.InputHandler
import com.roguelike.ui.FileDialog
import com.roguelike.ui.SimpleUI
import com.roguelike.utils.AssetLoader
import com.roguelike.utils.MeshData
import com.roguelike.world.*
import org.lwjgl.glfw.GLFW.*
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private fun gameTileFactory(type: String): Tile? = when (type) {
    FloorTile.TYPE -> FloorTile()
    CeilingTile.TYPE -> CeilingTile()
    WallNorthTile.TYPE -> WallNorthTile()
    WallSouthTile.TYPE -> WallSouthTile()
    WallEastTile.TYPE -> WallEastTile()
    WallWestTile.TYPE -> WallWestTile()
    WallDoorwayNorthTile.TYPE -> WallDoorwayNorthTile()
    WallDoorwaySouthTile.TYPE -> WallDoorwaySouthTile()
    WallDoorwayEastTile.TYPE -> WallDoorwayEastTile()
    WallDoorwayWestTile.TYPE -> WallDoorwayWestTile()
    DoorNorthTile.TYPE -> DoorNorthTile()
    DoorSouthTile.TYPE -> DoorSouthTile()
    DoorEastTile.TYPE -> DoorEastTile()
    DoorWestTile.TYPE -> DoorWestTile()
    StairsTile.TYPE -> StairsTile()
    LadderTile.TYPE -> LadderTile()
    else -> null
}

/**
 * Game screen — loads a world file via file picker and lets the player
 * navigate it in 3D with GPU-accelerated lighting.
 * Player is rendered as a blue sphere.
 */
class RoguelikeGame(
    private val inputSystem: InputSystem,
    private val camera: Camera,
    private val ui: SimpleUI
) {
    private var world: World? = null
    private var player: Player? = null
    private var movementSystem: MovementSystem? = null
    private var interactionSystem: InteractionSystem? = null
    private var inputHandler: InputHandler? = null
    private var lastFrameTime: Long = System.nanoTime()

    // Smoothed framerate (exponential moving average) for top-left HUD indicator.
    private var smoothedFps: Float = 0f

    // ── Per-frame phase profiler ─────────────────────────────────────────
    // Each phase records a running EMA of its CPU wall-clock cost in
    // milliseconds. Displayed on the HUD and dumped to stdout once per
    // second so we have hard numbers to chase.
    private val phaseMs = HashMap<String, Float>()
    private var phaseDumpAt = System.nanoTime()
    private var lastFrameTrisRendered = 0
    private var lastFrameShadowTris = 0
    private var lastFrameLights = 0

    // spec 008 / US2 (T035 + T037): per-frame shadow-cell-cache counters
    // used by PerfHud.classify to label cache_miss frames. These are NOT
    // accumulated forever — they are zeroed at the top of every
    // uploadLighting() so the HUD's hit-rate reflects "this frame" not
    // "since launch". `CacheCounterResetTest` pins that invariant.
    @Volatile private var shadowCellCacheMissCount = 0
    @Volatile private var shadowCellsTouchedCount = 0
    /** Test/diagnostic accessor — used by `CacheCounterResetTest` to assert
     *  the per-frame reset behaviour without exposing the mutable fields. */
    internal fun debugReadCacheCounters(): Pair<Int, Int> =
        shadowCellCacheMissCount to shadowCellsTouchedCount
    private inline fun <R> timed(name: String, block: () -> R): R {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000f
        val prev = phaseMs[name] ?: ms
        phaseMs[name] = prev * 0.9f + ms * 0.1f
        return r
    }
    private fun recordSubPhase(name: String, ms: Float) {
        val prev = phaseMs[name] ?: ms
        phaseMs[name] = prev * 0.9f + ms * 0.1f
    }

    // ── Reusable scratch for uploadLighting ──────────────────────────────
    // Avoid 580 KB/frame of IntArray and per-frame List allocations.
    private class IntScratch {
        private var arr: IntArray = IntArray(0)
        fun ensure(n: Int): IntArray {
            if (arr.size < n) arr = IntArray(n + (n ushr 1))
            return arr
        }
    }
    private class LongScratch {
        private var arr: LongArray = LongArray(0)
        fun ensure(n: Int): LongArray {
            if (arr.size < n) arr = LongArray(n + (n ushr 1))
            return arr
        }
    }
    private val occupancyScratch = IntScratch()
    private val needShadowScratch = LongScratch()
    private val reusableLightData = ArrayList<SimpleUI.LightData>(SimpleUI.MAX_LIGHTS)

    // ── Per-cell shadow geometry cache ───────────────────────────────────
    // For each (worldX, worldY, worldZ) we cache:
    //   key    : a content hash of the cell's shadow-relevant tile state
    //            (stair rotation, door open/closed, etc).
    //   tris   : the packed shadow-triangle floats (9 per triangle) that
    //            collectShadowTriangles + collectDoorShadowTriangles would
    //            emit for this cell. Already transformed to world space —
    //            independent of camera/light, so safe to reuse.
    //   borrow : for door frames straddling a wall the original code
    //            registered the triangle batch twice (owning cell + cell
    //            on the other side). We replicate that here as a small
    //            list of (dx,dy,dz, offsetFloat, count) entries telling
    //            the cache-replay where to also stamp the same tri range.
    //
    // Cells without stair/door/ladder content cache an empty result (key
    // matches → zero-cost skip).  When a cell falls outside the next
    // frame's window we keep the cache entry — re-entry on a backtrack
    // re-uses it. We evict the LRU half of the map only when it exceeds
    // a hard cap to bound memory.
    private class ShadowCacheEntry(
        var key: Long = 0L,
        var tris: FloatArray = FloatArray(0),
        var triCount: Int = 0,
        // Borrow offsets: 4 ints per borrow (dx, dy, dz, firstTriOffset),
        // count entries packed at the start.
        var borrows: IntArray = IntArray(0),
        var borrowCount: Int = 0
    )
    private val shadowCellCache = HashMap<Long, ShadowCacheEntry>(4096)
    private fun packCellKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0xFFFFFL) shl 40) or ((y.toLong() and 0xFFFFFL) shl 20) or (z.toLong() and 0xFFFFFL)

    /**
     * Build a content key for a cell. Mirrors what the producer code
     * actually reads: STAIRS rotation, LADDER rotation, door-slot state
     * (closed/open + which slot). Doesn't include occupancy bits — those
     * don't affect the shadow tri set.
     */
    private fun shadowKeyFor(node: com.roguelike.core.model.WorldNode): Long {
        var k = 1469598103934665603L
        fun mix(v: Int) { k = (k xor v.toLong()) * 1099511628211L }
        val stairsT = node.getTile(TileSlot.STAIRS)
        when (stairsT) {
            is StairsTile -> { mix(1); mix(stairsT.rotationY.toRawBits()) }
            is LadderTile -> { mix(2); mix(stairsT.rotationY.toRawBits()) }
            null -> mix(0)
            else -> mix(3)
        }
        // Door / doorway state per slot. Plain walls are also folded into
        // the key (per slot) because they now contribute their own shadow
        // triangles via the wall-mesh occluder block in uploadLighting —
        // a cached cell without those triangles would otherwise stick
        // around after a wall is added by the editor / procedural gen.
        val slots = arrayOf(TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, TileSlot.WALL_EAST, TileSlot.WALL_WEST)
        for ((i, slot) in slots.withIndex()) {
            val t = node.getTile(slot)
            val code: Int = when (t) {
                is DoorNorthTile -> if (t.isOpen) 10 + i else 20 + i
                is DoorSouthTile -> if (t.isOpen) 10 + i else 20 + i
                is DoorEastTile  -> if (t.isOpen) 10 + i else 20 + i
                is DoorWestTile  -> if (t.isOpen) 10 + i else 20 + i
                is WallDoorwayNorthTile, is WallDoorwaySouthTile,
                is WallDoorwayEastTile, is WallDoorwayWestTile -> 30 + i
                null -> 0
                else -> 40 + i  // plain wall (or any other non-special wall tile): unique per slot
            }
            if (code != 0) mix(code)
        }
        return k
    }

    // Asset loading
    private val assetLoader = AssetLoader()
    private var floorMesh: MeshData? = null
    private var ceilingMesh: MeshData? = null
    private var wallMesh: MeshData? = null
    private var ladderMesh: MeshData? = null
    private var stairsMesh: MeshData? = null
    private var doorClosedMesh: MeshData? = null
    private var doorOpenMesh: MeshData? = null
    private var doorFrameMesh: MeshData? = null

    // Debug renderer for spheres/wireframes
    private val debugRenderer = DebugRenderer(ui)

    // First-person camera state.
    //
    // `yaw` is the horizontal rotation in degrees, measured CCW from the
    // world +X axis (so yaw = 90° points along +Y). `pitch` is the
    // vertical rotation in degrees, clamped to ±89° to avoid gimbal flip
    // when looking straight up or down. The camera is positioned at the
    // player's eye height above the floor cell they stand on.
    private var yaw: Float = 90f
    private var pitch: Float = 0f
    private var fov: Float = 67f

    private companion object {
        /** Camera offset above the player's floor cell. */
        const val EYE_HEIGHT: Float = 0.7f
        /** Degrees of yaw/pitch produced per pixel of raw mouse motion. */
        const val MOUSE_SENSITIVITY: Float = 0.12f
        /** Degrees per second turn rate for the Q/E keyboard fallback. */
        const val KEYBOARD_TURN_RATE: Float = 120f
        /**
         * Minimum ambient floor applied to lit geometry in the Arena.
         *
         * Set to a small non-zero value so fragments not reached by any
         * light still render with a faint base colour instead of pure
         * black. Without this floor, every fragment whose tile bin has
         * no surviving light — or whose top-K lights all fail the
         * shadow ray-march — collapses to RGB (0,0,0), producing flat
         * geometric voids that destroy depth perception (see the
         * "massive black void carving across the bottom half" repro
         * screenshot). The swap-chain clear colour remains pure black,
         * so the *background* outside any geometry still reads as true
         * black; this constant only affects shaded fragments on actual
         * world meshes.
         *
         * Value chosen empirically (~6% grey) to keep the lighting
         * dramatic while preventing total information loss in shadowed
         * regions. The editor uses [SimpleUI.updateLighting]'s default
         * of 0.15f, which is brighter than appropriate for the Arena.
         */
        const val ARENA_AMBIENT: Float = 0.06f

        /**
         * spec 008: cells outside the view frustum that are still
         * emitted so their wall meshes can be borrowed into in-frustum
         * neighbours. 1 cell is enough because wall borrow-into only
         * reaches the immediate neighbour (see uploadLighting's "borrow"
         * comment). Behind PerfFlags.enabled.
         */
        const val FRUSTUM_SKIRT_CELLS: Int = 1
    }

    // File dialog
    private val fileDialog = FileDialog(ui, inputSystem)
    private var worldLoaded = false

    /**
     * Active biome chosen in the [BiomePickerScreen] before entering the
     * arena. When non-null, [show] loads templates exclusively from this
     * biome's `submaps` list and opens the starting-submap picker on this
     * biome's `submaps-entry` list. When null, the legacy behaviour kicks
     * in: every `.wld` under `submaps/` is loaded and the starting picker
     * defaults to the `starting-submaps/` folder.
     */
    var biome: BiomeDefinition? = null

    // Procedural map manager — drives Arena world generation from socket-based templates.
    private val proceduralManager = ProceduralMapManager(
        tileFactory = ::gameTileFactory,
        worldFactory = { w, h, d -> World(w, h, d) }
    )

    // Rendering Z limit
    private var maxRenderZ = 0

    // ── Reusable lighting/shadow upload scratch buffers ──────────────────
    //
    // Previously rebuilt each frame via `mutableListOf<Float>()` and
    // `Array(...) { mutableListOf<Int>() }`, which boxed every Float
    // (millions of allocations + GC pressure per second when several
    // lights were active). These buffers are now grown on demand and
    // simply reset at the top of each `uploadLighting` call.

    /** Raw triangle floats (9 per triangle) emitted by mesh collectors. */
    private val shadowTriBuf = FloatBuf(SimpleUI.MAX_SHADOW_TRIANGLES * 9)

    /** Final triangle floats packed in per-cell order for the SSBO. */
    private val expandedTriBuf = FloatBuf(SimpleUI.MAX_SHADOW_TRIANGLES * 9)

    /** Per-cell lists of indices into [shadowTriBuf]. Outer array is
     *  grown only when the active occupancy grid window enlarges; inner
     *  IntArrays are grown only when a single cell's triangle count
     *  exceeds the previous high-water mark. Counts are reset to zero
     *  each frame rather than re-allocating. */
    private var perCellTris: Array<IntArray> = emptyArray()
    private var perCellTriCount: IntArray = IntArray(0)

    /** One-shot warning latch: too many shadow triangles in one frame. */
    private var shadowTriOverflowWarned = false
    // spec 008 (FR-008): one-shot warning for the per-cell triangle
    // cap. We don't want a warning spam if the asset that breaches
    // the cap is on screen continuously.
    private var triPerCellCapWarned = false

    // ── Forward+ per-tile light bin scratch buffers ──────────────────────
    //
    // Sized once at the SimpleUI cap. The `compute` step zeroes
    // `tileLightCount` for the active tile range each frame, then walks
    // each visible light and pushes its index into every tile whose
    // screen-space AABB it overlaps. Capped at MAX_LIGHTS_PER_TILE per
    // tile — overflow lights are silently dropped (rare in practice
    // because the top-K shader pass would have culled them anyway).
    private val tileLightCount = IntArray(SimpleUI.MAX_LIGHT_TILES)
    private val tileLightIndices = IntArray(SimpleUI.MAX_LIGHT_TILES * SimpleUI.MAX_LIGHTS_PER_TILE)
    // spec 008: reusable per-tile shadow-quality byte array. Allocated
    // once at MAX_LIGHT_TILES so the hot path stays alloc-free; only the
    // prefix [0, tileCount) is rewritten each frame and SimpleUI zeroes
    // the tail. See contracts/tile-quality-ssbo.md.
    private val tileQualityBuf = ByteArray(SimpleUI.MAX_LIGHT_TILES)
    // spec 008: window-shift hysteresis. Pure-logic state machine that
    // re-anchors the lighting grid window only when the desired origin
    // has moved ≥ 4 cells AND ≥ 8 frames have passed since the last
    // shift. Override (forceShift) when the per-frame visible-light
    // count jumps by > 20 % so a new room can't go dark for 8 frames.
    // See data-model.md §3 and contracts/perf-flags.md.
    private val windowHysteresis = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)
    private var tileBinOverflowWarned = false

    /**
     * Tiny FloatArray accumulator with an `add` cap so the per-frame
     * shadow upload can never overflow the persistent SSBO. Replaces
     * `mutableListOf<Float>()` (boxed Floats + reallocation churn).
     */
    private class FloatBuf(capacity: Int) {
        val data: FloatArray = FloatArray(capacity)
        var size: Int = 0
        val triCount: Int get() = size / 9
        fun reset() { size = 0 }
        fun add(v: Float): Boolean {
            if (size >= data.size) return false
            data[size++] = v
            return true
        }
        /**
         * Bulk-append [len] floats from [src] starting at [srcOff].
         * Returns false (and copies nothing) if the buffer would
         * overflow. Backed by `System.arraycopy` — ~50× faster than
         * looping `add()` for the per-cell cache replay.
         */
        fun addAll(src: FloatArray, srcOff: Int, len: Int): Boolean {
            if (size + len > data.size) return false
            System.arraycopy(src, srcOff, data, size, len)
            size += len
            return true
        }
    }

    private fun ensurePerCellCapacity(cellCount: Int) {
        if (cellCount > perCellTris.size) {
            // Grow the outer array; reuse existing IntArrays where present
            // so the per-cell capacity learned in previous frames is kept.
            val grown = Array(cellCount) { i ->
                if (i < perCellTris.size) perCellTris[i] else IntArray(4)
            }
            perCellTris = grown
            perCellTriCount = IntArray(cellCount)
        } else {
            for (i in 0 until cellCount) perCellTriCount[i] = 0
        }
    }

    private fun addPerCellTri(cellIdx: Int, triIdx: Int) {
        var list = perCellTris[cellIdx]
        val count = perCellTriCount[cellIdx]
        if (count >= list.size) {
            // Geometric growth — most cells stay tiny, but stair / door
            // cells can hit 30+ triangles each, so we let the high-water
            // mark stabilise after the first few frames.
            val grown = IntArray(list.size * 2)
            System.arraycopy(list, 0, grown, 0, count)
            perCellTris[cellIdx] = grown
            list = grown
        }
        list[count] = triIdx
        perCellTriCount[cellIdx] = count + 1
    }

    fun show() {
        // Load meshes
        try { floorMesh = assetLoader.loadModel("floor", "models/vox/floor/floor.obj") } catch (_: Exception) {}
        try { ceilingMesh = assetLoader.loadModel("ceiling", "models/vox/ceiling/ceiling.obj") } catch (_: Exception) {}
        try { wallMesh = assetLoader.loadModel("wall", "models/vox/wall/wall.obj") } catch (_: Exception) {}
        try { ladderMesh = assetLoader.loadModel("ladder", "models/vox/stairs/ladder_vertical_n.obj") } catch (_: Exception) {}
        try { stairsMesh = assetLoader.loadModel("stairs", "models/vox/stairs/stairs_n.obj") } catch (_: Exception) {}
        try { doorClosedMesh = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj") } catch (_: Exception) {}
        try { doorOpenMesh = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj") } catch (_: Exception) {}
        try { doorFrameMesh = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj") } catch (_: Exception) {}

        inputHandler = InputHandler(inputSystem)
        lastFrameTime = System.nanoTime()

        // Pre-load every reusable submap template so the generator has a pool
        // to draw from before the player picks a starting submap. When a
        // biome is active we restrict the pool to that biome's `submaps`
        // section; otherwise we walk the full default templates folder.
        val activeBiome = biome
        if (activeBiome != null) {
            println("[Game] Using biome '${activeBiome.entry.name}' (type=${activeBiome.entry.type}): " +
                    "${activeBiome.submaps.size} pool submap(s), ${activeBiome.startingSubmaps.size} starting submap(s)")
            proceduralManager.loadTemplateFiles(activeBiome.submaps.map { it.worldFile })
        } else {
            proceduralManager.loadTemplates("src/main/resources/world-submaps/submaps")
        }

        // Open the starting-submap picker. The picked .wld becomes the seed
        // room; the procedural generator grows outward from there. When a
        // biome is active, pick a RANDOM entry from its `submaps-entry`
        // section and skip the file dialog entirely — the biome already
        // declares which submaps are valid spawn seeds, so there's nothing
        // for the player to choose.
        val startingSubmaps = activeBiome?.startingSubmaps.orEmpty()
        if (startingSubmaps.isNotEmpty()) {
            val pick = startingSubmaps.random()
            println("[Game] Randomly picked starting submap '${pick.name}' from biome '${activeBiome?.entry?.name}' " +
                    "(${startingSubmaps.size} candidate(s))")
            loadInitialSubmap(pick.worldFile)
        } else {
            val startDir = File("src/main/resources/world-submaps/starting-submaps")
                .takeIf { it.exists() } ?: File("saved-worlds")
            fileDialog.open(FileDialog.Mode.OPEN, startDir) { file ->
                if (file != null) loadInitialSubmap(file)
            }
        }
    }

    /**
     * Initialise the procedural map manager from a starting submap. The
     * returned world is the live, grow-on-demand Arena world.
     */
    private fun loadInitialSubmap(file: File) {
        try {
            val loaded = proceduralManager.initialize(file.path)
            if (loaded != null) {
                world = loaded
                movementSystem = MovementSystem(loaded)
                interactionSystem = InteractionSystem(loaded) { tag, msg -> println("[$tag] $msg") }

                // Find player_spawn tag inside the freshly-stamped initial submap.
                var spawnX = loaded.width / 2f
                var spawnY = loaded.height / 2f
                var spawnZ = 0f
                outer@ for (z in 0 until loaded.depth) {
                    for (x in 0 until loaded.width) {
                        for (y in 0 until loaded.height) {
                            val node = loaded.getNode(x, y, z) ?: continue
                            if (node.tags.contains(WorldNode.Tags.PLAYER_SPAWN)) {
                                spawnX = x + 0.5f
                                spawnY = y + 0.5f
                                spawnZ = z.toFloat()
                                break@outer
                            }
                        }
                    }
                }

                player = Player().apply { position.set(spawnX, spawnY, spawnZ) }
                interactionSystem?.actors?.add(player!!)
                maxRenderZ = loaded.depth - 1
                worldLoaded = true
                println("[Game] Procedural arena initialised from ${file.path} (world=${loaded.width}x${loaded.height}x${loaded.depth})")
            } else {
                // Fall back to plain world loading (e.g. user picked a saved
                // world without a player_spawn or no templates loaded).
                println("[Game] Procedural init failed, falling back to raw world load")
                loadWorldFile(file)
            }
        } catch (e: Exception) {
            println("[Game] Failed to initialise procedural arena: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadWorldFile(file: File) {
        try {
            val loaded = WorldIO.loadWorld(file.path, { w, h, d -> World(w, h, d) }, ::gameTileFactory)
            if (loaded != null) {
                world = loaded
                movementSystem = MovementSystem(loaded)
                interactionSystem = InteractionSystem(loaded) { tag, msg -> println("[$tag] $msg") }

                // Find player_spawn tag or default to center
                var spawnX = loaded.width / 2f
                var spawnY = loaded.height / 2f
                var spawnZ = 0f
                outer@ for (z in 0 until loaded.depth) {
                    for (x in 0 until loaded.width) {
                        for (y in 0 until loaded.height) {
                            val node = loaded.getNode(x, y, z) ?: continue
                            if (node.tags.contains(WorldNode.Tags.PLAYER_SPAWN)) {
                                spawnX = x + 0.5f
                                spawnY = y + 0.5f
                                spawnZ = z.toFloat()
                                break@outer
                            }
                        }
                    }
                }

                player = Player().apply { position.set(spawnX, spawnY, spawnZ) }
                interactionSystem?.actors?.add(player!!)
                maxRenderZ = loaded.depth - 1
                worldLoaded = true
                println("[Game] Loaded world: ${file.path} (${loaded.width}x${loaded.height}x${loaded.depth})")
            }
        } catch (e: Exception) {
            println("[Game] Failed to load: ${e.message}")
        }
    }

    fun render(): Boolean {
        // File dialog phase
        if (fileDialog.isOpen) {
            fileDialog.render()
            return true
        }

        if (!worldLoaded) {
            ui.drawText("No world loaded. Press ESC to return to menu.", 20f, 20f, 0.8f, 0.8f, 0.9f, 1f, 1.2f)
            return true
        }

        val w = world ?: return true
        val p = player ?: return true
        val move = movementSystem ?: return true
        val input = inputHandler ?: return true

        // Delta time
        val now = System.nanoTime()
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastFrameTime = now

        // Handle movement (WASD). In first-person mode the camera and the
        // player share the same yaw, so movement is always taken relative
        // to where the player is looking — no Shift gate needed.
        run {
            val moveDir = input.getMovementDirection()
            if (!moveDir.isZero) {
                moveDir.nor()
                // moveDir.y > 0 (W) → forward along the yaw vector.
                // moveDir.x > 0 (D) → strafe right, 90° CW from forward.
                // Both stay in the XY plane so vertical look doesn't slow
                // horizontal travel.
                val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
                val cosY = cos(yawRad); val sinY = sin(yawRad)
                val fx = cosY;  val fy = sinY        // forward
                val rx = sinY;  val ry = -cosY       // right (90° CW of forward)
                val worldDirX = moveDir.x * rx + moveDir.y * fx
                val worldDirY = moveDir.x * ry + moveDir.y * fy
                moveDir.set(worldDirX, worldDirY, 0f).nor()
                // Substep movement so that on long frames (e.g. a hitch
                // caused by a heavy lighting upload) the player still cannot
                // tunnel through walls/props. With max speed 5 units/s and
                // a collision radius of ~0.15, each substep must cover less
                // than ~0.02s of travel to be safe. We cap substep duration
                // at 0.02s and run as many as needed to consume `delta`.
                val moveSpeed = 5f
                val maxSubstep = 0.02f
                var remaining = delta
                while (remaining > 0f) {
                    val sub = if (remaining > maxSubstep) maxSubstep else remaining
                    move.move(p, moveDir, sub, moveSpeed)
                    remaining -= sub
                    // If the actor entered climbing during this substep, the
                    // remaining horizontal movement no longer applies (climb
                    // logic owns the position).
                    if (p.isClimbing) break
                }
            }
        }

        // Interaction (F): toggle nearby manual doors / pick up items
        if (input.isInteractionJustPressed()) {
            val pos = p.position
            println("[Interaction] F pressed at pos=(${pos.x},${pos.y},${pos.z}) facing=${p.facingDirection} interactionSystem=${interactionSystem != null}")
            val result = interactionSystem?.interact(p, p.facingDirection)
            println("[Interaction] interact() result=$result")
        }

        // Per-frame interaction tick: rescue trapped actors, process deferred door closes
        timed("interaction") { interactionSystem?.update(delta) }

        // Notify procedural manager so it can lazily generate adjacent submaps
        // when the player crosses into a new region. New submaps are stamped
        // into `w` in-place, which may also grow the world's dimensions.
        timed("procedural") { proceduralManager.onPlayerMove(p.position.x, p.position.y, p.position.z) }
        if (w.depth - 1 > maxRenderZ) maxRenderZ = w.depth - 1

        // First-person camera controls.
        //
        // Mouse motion drives yaw (horizontal) and pitch (vertical). The
        // cursor is captured while the game is active so the OS pointer
        // can't drift off-window and motion is delivered as raw deltas.
        // Q/E act as keyboard fallbacks for turning. The scroll wheel
        // adjusts FOV (zoom). Pitch is clamped to ±89° to avoid the view
        // flipping when looking straight up or down.
        if (!inputSystem.isCursorCaptured()) inputSystem.setCursorCaptured(true)
        yaw -= inputSystem.getMouseDeltaX() * MOUSE_SENSITIVITY
        pitch = (pitch - inputSystem.getMouseDeltaY() * MOUSE_SENSITIVITY).coerceIn(-89f, 89f)
        if (inputSystem.isKeyPressed(GLFW_KEY_Q)) yaw += KEYBOARD_TURN_RATE * delta
        if (inputSystem.isKeyPressed(GLFW_KEY_E)) yaw -= KEYBOARD_TURN_RATE * delta

        val scroll = inputSystem.getScrollDelta()
        if (scroll != 0f) fov = (fov - scroll * 2f).coerceIn(40f, 100f)
        val zoomChange = input.getZoomChange(delta)
        if (zoomChange != 0f) fov = (fov + zoomChange).coerceIn(40f, 100f)

        // Sync the player's logical facing direction with the camera yaw
        // so the interaction system raycasts in the same direction the
        // player is looking.
        run {
            val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
            p.facingDirection.set(cos(yawRad), sin(yawRad), 0f)
        }

        // Update camera position + orientation to the player's eye.
        updateFirstPersonCamera(p.position)

        // Upload every light reachable through the room graph — no
        // distance cap. With the renderer no longer culling by player
        // distance we want every visible fixture to participate in
        // shading, regardless of how many rooms away it is.
        val candidateLights = timed("collectLights") {
            proceduralManager.collectVisibleRoomLights(
                p.position.x, p.position.y, p.position.z,
                maxRoomDistance = 1_000_000
            )
        }
        timed("uploadLighting") { uploadLighting(w, candidateLights) }

        // Set VP matrix
        val vpFloats = FloatArray(16)
        camera.viewProjection.get(vpFloats)
        ui.setViewProjection(vpFloats)

        // Render world
        timed("renderWorld") { renderWorld(w) }

        // In first-person mode the camera sits inside the player, so we
        // intentionally don't draw the player avatar — it would just
        // fill the screen with sphere mesh. A future cosmetic pass could
        // add view-model hands / weapons here.

        // HUD
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        // Framerate indicator (top-left). Smoothed via EMA to avoid jitter.
        val instantFps = if (delta > 0f) 1f / delta else 0f
        smoothedFps = if (smoothedFps <= 0f) instantFps else smoothedFps * 0.9f + instantFps * 0.1f
        ui.drawText("FPS: %.0f  (%.1f ms)".format(smoothedFps, 1000f / smoothedFps.coerceAtLeast(0.01f)), 10f, 10f, 1f, 1f, 0.4f, 1f, 1.2f)
        val posStr = "Pos: %.1f, %.1f, %.1f".format(p.position.x, p.position.y, p.position.z)
        ui.drawText(posStr, 10f, 32f, 0.7f, 0.7f, 0.8f, 1f, 1.1f)

        // Per-phase timings (top-left, below pos). Cheap; lets us watch
        // the bottleneck live without attaching a profiler.
        var hudY = 54f
        val phases = arrayOf(
            "interaction", "procedural", "collectLights", "uploadLighting", "renderWorld",
            "ul.cull", "ul.alloc", "ul.stamp", "ul.collect", "ul.pack", "ul.tiles+light", "ul.upload"
        )
        for (name in phases) {
            val ms = phaseMs[name] ?: continue
            ui.drawText("%-15s %5.1f ms".format(name, ms), 10f, hudY, 0.85f, 0.85f, 0.95f, 1f, 1f)
            hudY += 16f
        }
        ui.drawText("lights=$lastFrameLights  shadowTris=$lastFrameShadowTris", 10f, hudY, 0.7f, 0.85f, 0.7f, 1f, 1f)
        hudY += 16f
        ui.drawText("worldSize=${w.width}x${w.height}x${w.depth}", 10f, hudY, 0.6f, 0.6f, 0.7f, 1f, 1f)
        hudY += 16f
        // spec 008 / US2 (T035, T041): show the derived perf label so a
        // human can confirm at a glance whether F11 is doing what they
        // think it is doing. `disabled` IFF PerfFlags.enabled is false.
        run {
            val frameMs = 1000f / smoothedFps.coerceAtLeast(0.01f)
            val cpuPhases =
                (phaseMs["interaction"] ?: 0f) +
                (phaseMs["procedural"] ?: 0f) +
                (phaseMs["collectLights"] ?: 0f) +
                (phaseMs["uploadLighting"] ?: 0f) +
                (phaseMs["renderWorld"] ?: 0f)
            val touched = shadowCellsTouchedCount
            val misses = shadowCellCacheMissCount
            val cacheHit = if (touched == 0) 1f else 1f - (misses.toFloat() / touched.toFloat())
            val driver = com.roguelike.core.perf.PerfHud.classify(
                frameMs = frameMs,
                cpuPhases = cpuPhases,
                uploadMs = phaseMs["uploadLighting"] ?: 0f,
                cacheHitRate = cacheHit,
                flagEnabled = com.roguelike.core.perf.PerfFlags.enabled
            )
            ui.drawText(
                "driver=%s  cache_hit=%.0f%%  gpu_ms=%.1f".format(driver, cacheHit * 100f, (frameMs - cpuPhases).coerceAtLeast(0f)),
                10f, hudY, 0.95f, 0.8f, 0.5f, 1f, 1f
            )
        }

        // Once per second, dump the same numbers to stdout so we have a
        // copy-pasteable record.
        val nowNs = System.nanoTime()
        if (nowNs - phaseDumpAt > 1_000_000_000L) {
            phaseDumpAt = nowNs
            val frameMs = 1000f / smoothedFps.coerceAtLeast(0.01f)
            val sb = StringBuilder("[Profile] fps=%.1f frame=%.1fms".format(smoothedFps, frameMs))
            for (name in phases) phaseMs[name]?.let { sb.append("  $name=%.1f".format(it)) }
            sb.append("  lights=$lastFrameLights shadowTris=$lastFrameShadowTris world=${w.width}x${w.height}x${w.depth}")

            // spec 008 / US2 (T035, T036, T041): derived perf-skeptic
            // labels. `gpu_ms` is what's left of the frame after the
            // recorded CPU phases; `cache_hit` reads from the
            // per-frame counters wired above; `driver` calls into
            // `PerfHud.classify` so the single source of truth lives
            // there (see PerfHudTest / PerfHudClassifierTest). When
            // `PerfFlags.enabled == false`, classify() returns
            // `disabled` regardless of the measured numbers — that's
            // the contract the A/B capture relies on.
            val cpuPhases =
                (phaseMs["interaction"] ?: 0f) +
                (phaseMs["procedural"] ?: 0f) +
                (phaseMs["collectLights"] ?: 0f) +
                (phaseMs["uploadLighting"] ?: 0f) +
                (phaseMs["renderWorld"] ?: 0f)
            val gpuMs = (frameMs - cpuPhases).coerceAtLeast(0f)
            val touched = shadowCellsTouchedCount
            val misses = shadowCellCacheMissCount
            val cacheHit = if (touched == 0) 1f else 1f - (misses.toFloat() / touched.toFloat())
            val uploadMs = phaseMs["uploadLighting"] ?: 0f
            val driver = com.roguelike.core.perf.PerfHud.classify(
                frameMs = frameMs,
                cpuPhases = cpuPhases,
                uploadMs = uploadMs,
                cacheHitRate = cacheHit,
                flagEnabled = com.roguelike.core.perf.PerfFlags.enabled
            )
            sb.append("  gpu_ms=%.1f cache_hit=%.0f%% driver=%s".format(gpuMs, cacheHit * 100f, driver))
            println(sb.toString())
        }

        // Simple crosshair (two short bars centred on screen).
        val cx = sw * 0.5f; val cy = sh * 0.5f
        ui.drawRect(cx - 6f, cy - 1f, 12f, 2f, 1f, 1f, 1f, 0.8f)
        ui.drawRect(cx - 1f, cy - 6f, 2f, 12f, 1f, 1f, 1f, 0.8f)
        ui.drawText("Mouse: Look  WASD: Move  Q/E: Turn  Scroll: Zoom  F: Interact  ESC: Menu", 10f, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)

        return true
    }

    /**
     * Place the camera at the player's eye position and orient it from
     * [yaw] / [pitch]. World convention: +X east, +Y north, +Z up. Yaw
     * is measured CCW from +X (so yaw = 0° looks east, 90° looks north).
     */
    private fun updateFirstPersonCamera(target: Vec3) {
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        val cosP = cos(pitchRad)
        val dirX = cos(yawRad) * cosP
        val dirY = sin(yawRad) * cosP
        val dirZ = sin(pitchRad)
        camera.position.set(target.x, target.y, target.z + EYE_HEIGHT)
        camera.direction.set(dirX, dirY, dirZ).normalize()
        camera.up.set(0f, 0f, 1f)
        camera.fov = fov
        camera.update()
    }

    private fun uploadLighting(w: World, candidateLights: List<com.roguelike.core.model.LightSource>) {
        val t0 = System.nanoTime()

        // spec 008 / US2 (T035, T037): zero per-frame cache counters BEFORE
        // any cell work so the HUD's cache-hit-rate measures only this
        // frame. Pinned by `CacheCounterResetTest`.
        shadowCellCacheMissCount = 0
        shadowCellsTouchedCount = 0

        // ── 1. Frustum-cull light sources ──────────────────────────────────
        // Each light's influence is a sphere (centre = position, radius =
        // light radius). Skip uploading any light whose sphere can't touch
        // the view frustum — these can never contribute a visible photon and
        // would otherwise waste one of the UBO slots, causing later rooms
        // (whose lights would be culled in-shader to nothing) to go dark.
        // The input set is already room-distance-bounded by the caller.
        val frustumLights = candidateLights.filter { ls ->
            camera.isSphereInFrustum(ls.x, ls.y, ls.z, ls.radius)
        }

        // ── 2. Prioritise lights by room distance, then by Euclidean ─────
        // `candidateLights` is already supplied in room-priority order: the
        // player's current room first, then 1-hop rooms, then 2-hop, etc.,
        // with each ring internally sorted by distance to the player. So if
        // we need to truncate we simply keep the prefix — that guarantees a
        // light inside the player's room can never be displaced by a light
        // two rooms away just because the latter happens to be a few
        // metres closer through walls.
        val visibleLights = if (frustumLights.size <= SimpleUI.MAX_LIGHTS) {
            frustumLights
        } else {
            frustumLights.subList(0, SimpleUI.MAX_LIGHTS)
        }

        // Diagnostic trace: how many lights make it through each stage.
        // Throttled by fingerprint so it only prints when something actually
        // changes (counts or first-light identity). Logs even when zero
        // lights survive, so we can tell apart "no candidates" vs
        // "everything frustum-culled" vs "MAX_LIGHTS truncation".
        logUploadLighting(candidateLights, frustumLights, visibleLights)

        val tCull = System.nanoTime()

        if (visibleLights.isEmpty()) {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1, ambient = ARENA_AMBIENT)
            ui.updateShadowTriangles(expandedTriBuf.data, 0)
            lastFrameLights = 0
            lastFrameShadowTris = 0
            // Zero the per-tile bins so the shader's Forward+ path sees
            // an empty list this frame instead of stale indices.
            uploadLightTiles(emptyList())
            return
        }

        // ── 3. Build a window-around-player occupancy grid ────────────────
        // Up to now the occupancy/shadow grid was sized to the FULL world.
        // Once the procedural world grows past ~120×120 cells the per-frame
        // allocations alone become punitive, AND the per-cell triangle
        // start index (packed into bits 16-31 of each cell flag word) only
        // has 16 bits — once `expandedTris` accumulates >65k triangles the
        // start index silently overflows and every shadow cell reads
        // garbage triangles, which makes `isOccluded` return true for almost
        // every ray. The visible symptom is "lights stop projecting" after
        // walking far enough through a generated world.
        //
        // The fix: only build the grid for a tight AABB around the
        // currently-visible lights, anchored at `gridOrigin` in absolute
        // world coordinates. The shader subtracts that origin before
        // indexing, so the same DDA logic works unchanged.
        val worldW = w.width; val worldH = w.height; val worldD = w.depth
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (ls in visibleLights) {
            val r = kotlin.math.ceil(ls.radius).toInt() + 1
            val lxi = ls.x.toInt(); val lyi = ls.y.toInt(); val lzi = ls.z.toInt()
            if (lxi - r < minX) minX = lxi - r
            if (lyi - r < minY) minY = lyi - r
            if (lzi - r < minZ) minZ = lzi - r
            if (lxi + r > maxX) maxX = lxi + r
            if (lyi + r > maxY) maxY = lyi + r
            if (lzi + r > maxZ) maxZ = lzi + r
        }
        val desiredOriginX = minX.coerceIn(0, worldW - 1)
        val desiredOriginY = minY.coerceIn(0, worldH - 1)
        val desiredOriginZ = minZ.coerceIn(0, worldD - 1)
        // spec 008: window-shift hysteresis. The desired origin (above)
        // is what the spec-007 code would use; hysteresis may hold the
        // previous origin if the player has only drifted a few cells.
        // forceShift overrides hysteresis when the per-frame visible
        // light count jumps by > 20 % (a new room popped into view).
        // The end* clamps below stay derived from the desired (max+1),
        // so the window grows to cover lights even when origin is held;
        // only the *origin* anchor moves on a hysteresis-allowed shift.
        val (rx, ry, rz) = if (PerfFlags.enabled) {
            val prev = if (lastFrameLights == 0) 1 else lastFrameLights
            val jump = kotlin.math.abs(visibleLights.size - lastFrameLights).toFloat() / prev
            val force = jump > 0.20f
            val resolved = windowHysteresis.resolve(
                Triple(desiredOriginX, desiredOriginY, desiredOriginZ),
                forceShift = force
            )
            Triple(resolved.first, resolved.second, resolved.third)
        } else {
            // PerfFlags.enabled = false → pixel-identical to spec 007:
            // skip hysteresis entirely. (Hysteresis state goes stale
            // until next enable — that's fine, the next call to
            // resolve() re-anchors on its first invocation.)
            Triple(desiredOriginX, desiredOriginY, desiredOriginZ)
        }
        val originX = rx.coerceIn(0, worldW - 1)
        val originY = ry.coerceIn(0, worldH - 1)
        val originZ = rz.coerceIn(0, worldD - 1)
        val endX = (maxX + 1).coerceIn(originX + 1, worldW)
        val endY = (maxY + 1).coerceIn(originY + 1, worldH)
        val endZ = (maxZ + 1).coerceIn(originZ + 1, worldD)
        val gridW = endX - originX
        val gridH = endY - originY
        val gridD = endZ - originZ
        val cellCount = gridW * gridH * gridD

        // Reusable occupancy + "needs shadow tris" bitset (allocated once,
        // grown on demand). Avoids 580 KB of GC pressure per frame on a
        // 145k-cell window.
        val occupancy = occupancyScratch.ensure(cellCount)
        java.util.Arrays.fill(occupancy, 0, cellCount, 0)
        val needShadow = needShadowScratch.ensure((cellCount + 63) ushr 6)
        java.util.Arrays.fill(needShadow, 0, (cellCount + 63) ushr 6, 0L)

        // Reset scratch buffers — outer arrays are reused across frames.
        shadowTriBuf.reset()
        expandedTriBuf.reset()
        ensurePerCellCapacity(cellCount)

        val tAlloc = System.nanoTime()

        // ── 3a. Stamp each light's bounding box into the bitset ──────
        //
        // The bitset just answers "does any light reach this cell?" — the
        // shader does precise per-pixel falloff anyway. So we just write
        // the cell-AABB of the light (no per-cell distance test), and
        // burst-set whole spans of bits per Y-row instead of one bit per
        // cell. That collapses the per-light inner work to ~rows × 1
        // span-set rather than `(2r+1)³` distance tests.
        //
        // Shadow-emission radius cap: triangles further than
        // SHADOW_EMIT_RADIUS units from a light can't contribute a
        // visually-meaningful shadow given typical light intensities, and
        // emitting them blows MAX_SHADOW_TRIANGLES (the "shadow buffer
        // full" warning the user has been seeing). Clamp here.
        val SHADOW_EMIT_RADIUS = 10f
        val lightCount = visibleLights.size
        for (li in 0 until lightCount) {
            val ls = visibleLights[li]
            val r = kotlin.math.min(ls.radius, SHADOW_EMIT_RADIUS)
            val lxi = kotlin.math.floor(ls.x - r).toInt().coerceAtLeast(originX)
            val lyi = kotlin.math.floor(ls.y - r).toInt().coerceAtLeast(originY)
            val lzi = kotlin.math.floor(ls.z - r).toInt().coerceAtLeast(originZ)
            val lxe = kotlin.math.ceil(ls.x + r).toInt().coerceAtMost(endX - 1)
            val lye = kotlin.math.ceil(ls.y + r).toInt().coerceAtMost(endY - 1)
            val lze = kotlin.math.ceil(ls.z + r).toInt().coerceAtMost(endZ - 1)
            if (lxe < lxi || lye < lyi || lze < lzi) continue
            // Local-cell X range (window-relative).
            val lx0 = lxi - originX
            val lx1 = lxe - originX
            for (cz in lzi..lze) {
                val lz = cz - originZ
                for (cy in lyi..lye) {
                    val ly = cy - originY
                    val rowBase = lz * gridW * gridH + ly * gridW
                    // Set bits [rowBase+lx0 .. rowBase+lx1] inclusive.
                    val from = rowBase + lx0
                    val to = rowBase + lx1
                    // Fast burst-set across long-word boundaries.
                    var w0 = from ushr 6
                    val w1 = to ushr 6
                    val fromBit = from and 63
                    val toBit = to and 63
                    if (w0 == w1) {
                        val mask = ((-1L ushr (63 - (toBit - fromBit))) shl fromBit)
                        needShadow[w0] = needShadow[w0] or mask
                    } else {
                        needShadow[w0] = needShadow[w0] or (-1L shl fromBit)
                        w0++
                        while (w0 < w1) { needShadow[w0] = -1L; w0++ }
                        needShadow[w1] = needShadow[w1] or (-1L ushr (63 - toBit))
                    }
                }
            }
        }

        val tStamp = System.nanoTime()

        // Iterate ONLY the non-empty cells inside the window — the sparse
        // per-Z index in World gives us O(populated cells) instead of
        // O(window volume). At ~93×81×18 = 135k cells, even a quick
        // node.tiles.isEmpty() guard on every cell costs several ms per
        // frame; the index drops that to the actual ~few-thousand
        // populated cells the dungeon contains.
        w.forEachNonEmptyInWindow(originX, originY, originZ, endX, endY, endZ) { node ->
            val x = node.x; val y = node.y; val z = node.z
            val wIdx = (z - originZ) * gridW * gridH + (y - originY) * gridW + (x - originX)
            val isLit = (needShadow[wIdx ushr 6] and (1L shl (wIdx and 63))) != 0L
            var flags = 0
            if (isSolidWall(node, TileSlot.WALL_NORTH)) flags = flags or 1
            if (isSolidWall(node, TileSlot.WALL_SOUTH)) flags = flags or 2
            if (isSolidWall(node, TileSlot.WALL_EAST))  flags = flags or 4
            if (isSolidWall(node, TileSlot.WALL_WEST))  flags = flags or 8
            if (node.hasTile(TileSlot.FLOOR))      flags = flags or 16
            if (node.hasTile(TileSlot.CEILING))    flags = flags or 32
            occupancy[wIdx] = flags

            if (!isLit) return@forEachNonEmptyInWindow

            // Per-cell shadow geometry cache — most cells'
            // shadow tris are stable frame-to-frame. We
            // recompute only on cache miss (door toggled, new
            // submap stamped, first visit).
            val cellKey = packCellKey(x, y, z)
            val contentKey = shadowKeyFor(node)
            val cached = shadowCellCache[cellKey]
            // spec 008 / US2 (T035): count every lit cell we touch and
            // every cache miss. The HUD derives cache_hit_rate from these.
            shadowCellsTouchedCount++
            if (cached != null && cached.key == contentKey) {
                // Cache hit: bulk-copy the cell's pre-computed
                // triangle floats into shadowTriBuf and register
                // the per-cell tri indices.
                val n = cached.triCount
                if (n > 0) {
                    val first = shadowTriBuf.triCount
                    if (!shadowTriBuf.addAll(cached.tris, 0, n * 9)) {
                        // Buffer full — skip the remaining
                        // cached batches this frame. The
                        // existing shadowTriOverflowWarned
                        // branch elsewhere will fire.
                        return@forEachNonEmptyInWindow
                    }
                    for (k in 0 until n) addPerCellTri(wIdx, first + k)
                    // Replay borrows (door frames & panels straddling
                    // walls register into the neighbour cell).
                    // Borrow record stride = 4 ints: [dx, dy, triOff, count].
                    val bc = cached.borrowCount
                    val brs = cached.borrows
                    for (bi in 0 until bc) {
                        val base = bi * 4
                        val dx = brs[base]
                        val dy = brs[base + 1]
                        val triOff = brs[base + 2]
                        val borrowCount = brs[base + 3]
                        val nx = x + dx; val ny = y + dy
                        if (nx < originX || nx >= endX || ny < originY || ny >= endY) continue
                        val nbrIdx = (z - originZ) * gridW * gridH + (ny - originY) * gridW + (nx - originX)
                        for (k in 0 until borrowCount) addPerCellTri(nbrIdx, first + triOff + k)
                    }
                }
                return@forEachNonEmptyInWindow
            }

            // Cache miss: run the producers, then snapshot the
            // emitted floats + borrows into the cache entry.
            shadowCellCacheMissCount++
            val cellFirstTri = shadowTriBuf.triCount
            val cellBorrows = ArrayList<Int>(8)

            if (node.hasTile(TileSlot.STAIRS)) {
                val tile = node.getTile(TileSlot.STAIRS)
                if (tile is StairsTile) {
                    stairsMesh?.let {
                        val first = shadowTriBuf.triCount
                        val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), 0f, 0f, 0f, stairsRenderRotation(tile.rotationY), shadowTriBuf)
                        for (k in 0 until n) addPerCellTri(wIdx, first + k)
                    }
                } else if (tile is LadderTile) {
                    val rotY = tile.rotationY
                    val offX = when (rotY) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                    val offY = when (rotY) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                    ladderMesh?.let {
                        val first = shadowTriBuf.triCount
                        val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, 0f, rotY, shadowTriBuf)
                        for (k in 0 until n) addPerCellTri(wIdx, first + k)
                    }
                }
            }

            // ── Wall meshes as shadow occluders ──────────────────────────
            //
            // Walls were previously represented in the shadow system *only*
            // by per-cell bit flags (bits 0-3 in occupancy[]). That bit-flag
            // DDA correctly blocks rays that *cross* a cell boundary at a
            // wall plane, but it produces no occlusion for rays whose
            // entire span sits inside one cell — exactly the case that
            // dominates floor/ceiling lighting. A wall standing on the
            // east face of cell (5,5,0) sits at world x = 6.0; a floor
            // fragment at world (5.9, 5.5, 0) and a light at world (5.1,
            // 5.5, 0.5) are both in cell (5,5,0), so the DDA never crosses
            // a boundary and the wall flag is never tested — the floor
            // lights up *as if the wall were not there*, which is the
            // exact symptom the user reported ("floors and ceilings are
            // lit as if there are no walls").
            //
            // Adding the wall *mesh* (12 triangles) to the per-cell shadow
            // triangle buffer makes the per-pixel ray-march test the ray
            // against the actual wall geometry via Möller-Trumbore. Because
            // a wall mesh straddles the boundary between two cells, we
            // borrow it into the neighbour cell too (same mechanism the
            // door producer already uses) so a fragment on either side
            // sees the wall as an occluder during its same-cell test.
            //
            // Cost: ≤ 4 × 12 = 48 extra triangles per cell with all four
            // walls. The per-cell count field is 8 bits (max 255), so a
            // full-walled cell with stairs + doors still stays under the
            // limit. Realistic per-window total grows from ~8 k to ~30 k,
            // well under MAX_SHADOW_TRIANGLES (131 071).
            //
            // We skip slots that already produced shadow geometry via
            // [collectDoorShadowTriangles] (doors and doorway-walls) so we
            // don't double up the occluder for the same physical mesh.
            val wm = wallMesh
            if (wm != null) {
                for (slotIdx in 0 until 4) {
                    val slot = when (slotIdx) {
                        0 -> TileSlot.WALL_NORTH
                        1 -> TileSlot.WALL_SOUTH
                        2 -> TileSlot.WALL_EAST
                        else -> TileSlot.WALL_WEST
                    }
                    if (!isSolidWall(node, slot)) continue // door/doorway already produced geometry
                    val (offX, offY, rotDeg) = when (slot) {
                        TileSlot.WALL_NORTH -> Triple( 0.0f,  0.5f,   0f)
                        TileSlot.WALL_SOUTH -> Triple( 0.0f, -0.5f, 180f)
                        TileSlot.WALL_EAST  -> Triple( 0.5f,  0.0f,  90f)
                        else                -> Triple(-0.5f,  0.0f, 270f) // WALL_WEST
                    }
                    val first = shadowTriBuf.triCount
                    val n = collectShadowTriangles(
                        wm, x.toFloat(), y.toFloat(), z.toFloat(),
                        offX, offY, 0f, rotDeg, shadowTriBuf
                    )
                    if (n == 0) continue
                    for (k in 0 until n) addPerCellTri(wIdx, first + k)
                    // Borrow into the neighbour cell on the other side of
                    // the wall so its floor/ceiling fragments also see the
                    // wall as an occluder when the ray stays inside one
                    // cell (same idea as the existing door-borrow logic).
                    val (nx, ny) = when (slot) {
                        TileSlot.WALL_NORTH -> x to (y + 1)
                        TileSlot.WALL_SOUTH -> x to (y - 1)
                        TileSlot.WALL_EAST  -> (x + 1) to y
                        else                -> (x - 1) to y
                    }
                    if (nx in originX until endX && ny in originY until endY) {
                        val nbrIdx = (z - originZ) * gridW * gridH + (ny - originY) * gridW + (nx - originX)
                        for (k in 0 until n) addPerCellTri(nbrIdx, first + k)
                        cellBorrows.add(nx - x)
                        cellBorrows.add(ny - y)
                        cellBorrows.add(first - cellFirstTri)
                        cellBorrows.add(n)
                    }
                }
            }

            collectDoorShadowTriangles(node, x, y, z, shadowTriBuf) { slot, firstTri, n ->
                for (k in 0 until n) addPerCellTri(wIdx, firstTri + k)
                val (nx, ny) = when (slot) {
                    TileSlot.WALL_NORTH -> x to (y + 1)
                    TileSlot.WALL_SOUTH -> x to (y - 1)
                    TileSlot.WALL_EAST  -> (x + 1) to y
                    TileSlot.WALL_WEST  -> (x - 1) to y
                    else -> return@collectDoorShadowTriangles
                }
                if (nx < originX || nx >= endX || ny < originY || ny >= endY) return@collectDoorShadowTriangles
                val nbrIdx = (z - originZ) * gridW * gridH + (ny - originY) * gridW + (nx - originX)
                for (k in 0 until n) addPerCellTri(nbrIdx, firstTri + k)
                // Record the borrow for the cache. Stride = 4 ints:
                //   [dx, dy, triOffsetInCellSnapshot, count]
                // Previously the count slot held an unused 0 and the
                // replay path registered only ONE triangle per borrow,
                // silently losing the rest of each batch (a door slot
                // emits up to 2 batches: frame + panel, each ~12 tris).
                // The visible symptom was missing occluder triangles on
                // neighbour cells across a door — light bleed past
                // closed doors, plus "overlapping black square shadow"
                // artefacts where the cell saw a partial set of stray
                // triangles at the wrong positions.
                cellBorrows.add(nx - x)
                cellBorrows.add(ny - y)
                cellBorrows.add(firstTri - cellFirstTri)
                cellBorrows.add(n)
            }

            // Snapshot this cell's triangle range into the cache.
            val cellTriCount = shadowTriBuf.triCount - cellFirstTri
            val entry = cached ?: ShadowCacheEntry().also { shadowCellCache[cellKey] = it }
            entry.key = contentKey
            entry.triCount = cellTriCount
            val needFloats = cellTriCount * 9
            if (entry.tris.size < needFloats) entry.tris = FloatArray(needFloats)
            if (cellTriCount > 0) System.arraycopy(shadowTriBuf.data, cellFirstTri * 9, entry.tris, 0, needFloats)
            val bc = cellBorrows.size / 4
            entry.borrowCount = bc
            if (entry.borrows.size < bc * 4) entry.borrows = IntArray(bc * 4)
            for (i in 0 until bc * 4) entry.borrows[i] = cellBorrows[i]
        }
        // Bound cache size — modest cap; geometry footprint per entry
        // is a few hundred bytes worst-case. Evict half by clearing the
        // map; rebuild on demand. (LRU would be nicer but this is rare.)
        if (shadowCellCache.size > 32768) shadowCellCache.clear()

        val tCollect = System.nanoTime()

        // Pack per-cell triangle lists into the cell-flag bits. Layout:
        //   bits 0-6   : wall/floor/ceiling flags
        //   bits 7-14  : per-cell triangle count  (8 bits → max 255)
        //   bits 15-31 : per-cell triangle start  (17 bits → max 131071)
        //
        // The shader's matching unpack lives in
        // shaders/world_lit.frag.glsl::getShadowTriRange — keep the two
        // in sync. The 17-bit start field doubled the safe shadow-tri
        // budget; previously a 16-bit start would silently wrap once
        // expandedTris > 65k on large dungeons, making cells past that
        // point read garbage triangle ranges. The visible symptom was a
        // square-checkerboard of cells that stopped shadowing in the
        // middle of an otherwise-shadowed area.
        //
        // spec 008: when PerfFlags.enabled, skip emitting triangles for
        // cells whose world AABB (expanded by FRUSTUM_SKIRT_CELLS) is
        // outside the view frustum. The wall-flag bits (0-6) stay
        // intact so out-of-frustum cells on a ray's path still occlude
        // correctly; only the per-cell shadow-tri (start, count)
        // packing is skipped. The skirt keeps wall-borrow geometry
        // from neighbouring in-frustum cells valid. Producer-side
        // (shadowCellCache) stays frustum-agnostic by design so the
        // cache hit-rate doesn't tank when the player pans the camera.
        val cullByFrustum = PerfFlags.enabled
        val skirt = FRUSTUM_SKIRT_CELLS.toFloat()
        for (cz in 0 until gridD) {
            for (cy in 0 until gridH) {
                for (cx in 0 until gridW) {
                    val idx = cz * gridW * gridH + cy * gridW + cx
                    val listCount = perCellTriCount[idx]
                    if (listCount == 0) continue
                    if (cullByFrustum) {
                        val wx = (originX + cx).toFloat()
                        val wy = (originY + cy).toFloat()
                        val wz = (originZ + cz).toFloat()
                        if (!camera.isBoxInFrustum(
                                wx - skirt, wy - skirt, wz - skirt,
                                wx + 1f + skirt, wy + 1f + skirt, wz + 1f + skirt
                            )
                        ) {
                            // Out-of-frustum: leave occupancy[idx]'s
                            // (start, count) at 0 so the shader's
                            // getShadowTriRange returns an empty range.
                            // Wall bits 0-6 are already set above.
                            continue
                        }
                    }
                    val list = perCellTris[idx]
                    val start = expandedTriBuf.triCount
                    if (start > 0x1FFFF) {
                        // Hard ceiling — should be unreachable because
                        // MAX_SHADOW_TRIANGLES is sized to exactly fill
                        // the 17-bit start field. If we got here the
                        // upstream collector emitted too many triangles
                        // for the SSBO; drop the rest of the cells' tris
                        // rather than corrupt their start indices.
                        continue
                    }
                    // spec 008 (FR-008): cap per-cell triangle count at
                    // 24 when PerfFlags.enabled. The shader's hot loop
                    // runs O(count) ray-triangle tests per fragment, so
                    // a single 200-triangle cell dominates the slowest
                    // frame. 24 was chosen as the smallest cap that
                    // still resolves "stairs + adjacent wall corner"
                    // worst-case borrow geometry without visible loss.
                    // A one-shot warning names the offending cell so
                    // an asset author can investigate.
                    val cellCap = if (PerfFlags.enabled) 24 else 0xFF
                    if (PerfFlags.enabled && listCount > cellCap && !triPerCellCapWarned) {
                        triPerCellCapWarned = true
                        val wx = originX + cx
                        val wy = originY + cy
                        val wz = originZ + cz
                        System.err.println(
                            "[RoguelikeGame] spec 008: per-cell shadow-tri cap " +
                                "(${cellCap}) hit at cell=($wx,$wy,$wz); had $listCount tris. " +
                                "Tighten the asset's collision geometry or raise the cap if intentional."
                        )
                    }
                    val count = listCount.coerceAtMost(cellCap)
                    var droppedHere = false
                    for (i in 0 until count) {
                        val src = list[i] * 9
                        for (k in 0 until 9) {
                            if (!expandedTriBuf.add(shadowTriBuf.data[src + k])) {
                                droppedHere = true
                            }
                        }
                    }
                    if (droppedHere && !shadowTriOverflowWarned) {
                        shadowTriOverflowWarned = true
                        System.err.println(
                            "[RoguelikeGame] shadow triangle buffer full " +
                                "(cap=${SimpleUI.MAX_SHADOW_TRIANGLES}); dropping triangles. " +
                                "Tighten the lighting window or raise MAX_SHADOW_TRIANGLES."
                        )
                    }
                    var f = occupancy[idx]
                    f = f or ((count and 0xFF) shl 7)
                    f = f or ((start and 0x1FFFF) shl 15)
                    occupancy[idx] = f
                }
            }
        }

        val tPack = System.nanoTime()

        // Forward+ tile binning — call FIRST so it stamps the cached
        // screen/tile params into SimpleUI before updateLighting embeds
        // them in the UBO this same frame (otherwise the shader reads
        // last frame's screen dims, which manifests as misaligned tile
        // lookups after a window resize).
        uploadLightTiles(visibleLights)
        // updateLighting accepts a List<LightData> — recycle a reusable
        // ArrayList so we don't allocate 128 boxed objects per frame.
        reusableLightData.clear()
        reusableLightData.ensureCapacity(lightCount)
        for (li in 0 until lightCount) {
            val ls = visibleLights[li]
            reusableLightData.add(SimpleUI.LightData(ls.x, ls.y, ls.z, ls.intensity, ls.colorR(), ls.colorG(), ls.colorB(), ls.radius))
        }
        ui.updateLighting(
            reusableLightData, occupancy, gridW, gridH, gridD,
            ambient = ARENA_AMBIENT,
            gridOriginX = originX, gridOriginY = originY, gridOriginZ = originZ
        )

        val tTiles = System.nanoTime()

        // updateShadowTriangles reads from the start of the supplied
        // FloatArray up to `triCount * 9` floats, so we can hand it the
        // backing buffer directly — no allocation per frame.
        // Compute a content hash so SimpleUI can skip the GPU upload
        // when the window contents haven't changed (player standing
        // still: hash is stable → zero per-frame SSBO writes).
        val triCount = expandedTriBuf.triCount
        var hash = (triCount.toLong() * 2654435761L) xor 0x9E3779B97F4A7C15UL.toLong()
        if (triCount > 0) {
            val data = expandedTriBuf.data
            // Sample at a stride so we don't read every float — 256
            // probe points are plenty to detect any geometry change
            // (door opens, new submap stamped, light window shifted).
            val step = maxOf(1, (triCount * 9) / 256)
            var i = 0
            val n = triCount * 9
            while (i < n) {
                hash = (hash * 1099511628211L) xor java.lang.Float.floatToRawIntBits(data[i]).toLong()
                i += step
            }
        }
        ui.updateShadowTriangles(expandedTriBuf.data, triCount, hash)
        lastFrameLights = visibleLights.size
        lastFrameShadowTris = triCount

        val tUpload = System.nanoTime()

        // Sub-phase timings (EMA into phaseMs for the HUD).
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000f
        recordSubPhase("ul.cull",    ms(t0, tCull))
        recordSubPhase("ul.alloc",   ms(tCull, tAlloc))
        recordSubPhase("ul.stamp",   ms(tAlloc, tStamp))
        recordSubPhase("ul.collect", ms(tStamp, tCollect))
        recordSubPhase("ul.pack",    ms(tCollect, tPack))
        recordSubPhase("ul.tiles+light", ms(tPack, tTiles))
        recordSubPhase("ul.upload",  ms(tTiles, tUpload))

        // Window + shadow trace. Helps diagnose "fragment looks dark"
        // bugs by recording whether the player's cell sits inside the
        // shadow window, how big the window is, and how many shadow
        // triangles fit into it. Throttled the same way as the upload
        // log so it only prints when something materially changes.
        val p = player?.position
        val px = p?.x ?: 0f; val py = p?.y ?: 0f; val pz = p?.z ?: 0f
        val pInWindow = px.toInt() in originX until endX &&
                py.toInt() in originY until endY &&
                pz.toInt() in originZ until endZ
        logWindowDebug(originX, originY, originZ, endX, endY, endZ, expandedTriBuf.triCount, pInWindow, px, py, pz)
    }

    /**
     * Build per-tile light bins for Forward+ shading and hand them to
     * the renderer. Tiles are screen-aligned squares of
     * [SimpleUI.LIGHT_TILE_SIZE] pixels; each light's screen-space
     * bounding sphere is projected to a screen-space AABB, then
     * appended to every tile that AABB touches. The fragment shader
     * iterates only the lights for the fragment's own tile.
     *
     * Cost: O(numLights × averageTilesPerLight). With 64 lights of
     * radius 20 in a typical room and a 1080p viewport, ~50 µs per
     * frame on modern CPUs — negligible next to the GPU savings of
     * not iterating 128 lights per fragment.
     */
    private fun uploadLightTiles(lights: List<com.roguelike.core.model.LightSource>) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        if (sw <= 0f || sh <= 0f) return
        val tileSize = SimpleUI.LIGHT_TILE_SIZE
        val tilesX = ((sw + tileSize - 1) / tileSize).toInt()
        val tilesY = ((sh + tileSize - 1) / tileSize).toInt()
        val tileCount = tilesX * tilesY
        if (tileCount <= 0 || tileCount > SimpleUI.MAX_LIGHT_TILES) return

        // Zero only the active tile range — cheaper than wiping the
        // full 16 384-tile capacity every frame.
        java.util.Arrays.fill(tileLightCount, 0, tileCount, 0)

        // Focal length in pixels for the camera's vertical FoV. Used to
        // convert world-space radius → pixel radius at a given view
        // depth via `pxRadius = radius * focalPx / |zView|`.
        val focalPx = (sh * 0.5f / kotlin.math.tan(Math.toRadians(camera.fov.toDouble() * 0.5).toFloat()))

        val vp = camera.viewProjection
        // Reuse a single Vector4f via direct math — JOML allocations
        // here would be a hot-path leak. We do the projection manually:
        //   clip = VP * (x,y,z,1)
        //   if (clip.w <= near) → behind camera → mark all tiles
        //   else screenX = (clip.x/clip.w * 0.5 + 0.5) * sw, ditto Y
        val m00 = vp.m00(); val m10 = vp.m10(); val m20 = vp.m20(); val m30 = vp.m30()
        val m01 = vp.m01(); val m11 = vp.m11(); val m21 = vp.m21(); val m31 = vp.m31()
        // Row 2 (z) unused for tile bins. Row 3 (w) controls perspective divide.
        val m03 = vp.m03(); val m13 = vp.m13(); val m23 = vp.m23(); val m33 = vp.m33()
        // View-space Z for radius-to-pixels: take the camera's forward
        // axis (negated direction) and dot against light position
        // relative to camera origin.
        val camPx = camera.position.x; val camPy = camera.position.y; val camPz = camera.position.z
        val dirX = camera.direction.x; val dirY = camera.direction.y; val dirZ = camera.direction.z

        for (li in lights.indices) {
            val l = lights[li]
            val zView = (l.x - camPx) * dirX + (l.y - camPy) * dirY + (l.z - camPz) * dirZ
            // For lights very close to or behind the near plane we
            // conservatively mark every tile — guarantees correctness
            // (the shader will still cull via radius/NdotL) at the cost
            // of one big bin upload. Cheap because it's rare.
            val pxRadius: Float
            val cx: Float; val cy: Float
            if (zView <= 0.5f) {
                // Mark every tile.
                appendToAllTiles(li, tileCount)
                continue
            } else {
                pxRadius = l.radius * focalPx / zView
                val cw = m03 * l.x + m13 * l.y + m23 * l.z + m33
                if (cw <= 0f) { appendToAllTiles(li, tileCount); continue }
                val ccx = m00 * l.x + m10 * l.y + m20 * l.z + m30
                val ccy = m01 * l.x + m11 * l.y + m21 * l.z + m31
                val invW = 1f / cw
                cx = (ccx * invW * 0.5f + 0.5f) * sw
                // Vulkan Y-flip (Camera projectionMatrix.m11 *= -1) means
                // NDC Y already matches "down = +1" pixel convention.
                cy = (ccy * invW * 0.5f + 0.5f) * sh
            }

            val minPxX = (cx - pxRadius).coerceAtLeast(0f)
            val minPxY = (cy - pxRadius).coerceAtLeast(0f)
            val maxPxX = (cx + pxRadius).coerceAtMost(sw - 1f)
            val maxPxY = (cy + pxRadius).coerceAtMost(sh - 1f)
            if (maxPxX < minPxX || maxPxY < minPxY) continue

            val tx0 = (minPxX.toInt() / tileSize).coerceIn(0, tilesX - 1)
            val ty0 = (minPxY.toInt() / tileSize).coerceIn(0, tilesY - 1)
            val tx1 = (maxPxX.toInt() / tileSize).coerceIn(0, tilesX - 1)
            val ty1 = (maxPxY.toInt() / tileSize).coerceIn(0, tilesY - 1)

            for (ty in ty0..ty1) {
                val rowBase = ty * tilesX
                for (tx in tx0..tx1) {
                    val tIdx = rowBase + tx
                    val cnt = tileLightCount[tIdx]
                    if (cnt >= SimpleUI.MAX_LIGHTS_PER_TILE) {
                        if (!tileBinOverflowWarned) {
                            tileBinOverflowWarned = true
                            System.err.println(
                                "[RoguelikeGame] tile ($tx,$ty) saturated at " +
                                    "${SimpleUI.MAX_LIGHTS_PER_TILE} lights — dropping. " +
                                    "Raise MAX_LIGHTS_PER_TILE if this becomes visible."
                            )
                        }
                        continue
                    }
                    tileLightIndices[tIdx * SimpleUI.MAX_LIGHTS_PER_TILE + cnt] = li
                    tileLightCount[tIdx] = cnt + 1
                }
            }
        }

        ui.updateLightTiles(tileLightCount, tileLightIndices, tilesX, tilesY, sw, sh)

        // ── spec 008: per-tile shadow-quality byte ────────────────────
        // Compute one byte per Forward+ tile and ship it to the shader
        // via SimpleUI.updateTileQuality (binding 5).
        //   q=0 — empty tile (no lights touched it). Shader skips the
        //         entire lighting loop and paints ambient-only.
        //   q=1 — peripheral tile (centre distance > R). 1-tap shadow
        //         visibility, top-K cap of MAX_PER_PIXEL_LIGHTS_LOW (3).
        //   q=2 — central tile with lights. Full spec-007 behaviour.
        // When PerfFlags.enabled is false EVERY tile gets q=2 so the
        // shader's branch reduces to today's code path; this is the
        // pixel-identity guarantee mandated by the perf-flags contract.
        // Contract: specs/008-fps-fov-shadow-culling/contracts/tile-quality-ssbo.md
        val flagOn = PerfFlags.enabled
        if (!flagOn) {
            // Fast path: blanket the prefix with `2`. Tail is zeroed by
            // SimpleUI.updateTileQuality so a window shrink can't leak
            // stale labels.
            java.util.Arrays.fill(tileQualityBuf, 0, tileCount, 2.toByte())
        } else {
            // Centre/periphery split, measured in tiles.
            val cx = (tilesX - 1) * 0.5f
            val cy = (tilesY - 1) * 0.5f
            val centreFrac = PerfFlags.centreFraction
            val minDim = if (sw < sh) sw else sh
            // R is the radius in tiles of the high-quality centre region.
            val rTiles = centreFrac * minDim * 0.5f / tileSize
            val rTilesSq = rTiles * rTiles
            for (ty in 0 until tilesY) {
                val dy = ty.toFloat() - cy
                val dySq = dy * dy
                val rowBase = ty * tilesX
                for (tx in 0 until tilesX) {
                    val t = rowBase + tx
                    val q = if (tileLightCount[t] == 0) 0
                    else {
                        val dx = tx.toFloat() - cx
                        if (dx * dx + dySq > rTilesSq) 1 else 2
                    }
                    tileQualityBuf[t] = q.toByte()
                }
            }
        }
        ui.updateTileQuality(tileQualityBuf, tileCount)
    }

    /** Mark every active tile as touched by [lightIdx]. Used as the
     *  fallback path for lights at/behind the near plane. */
    private fun appendToAllTiles(lightIdx: Int, tileCount: Int) {
        for (t in 0 until tileCount) {
            val cnt = tileLightCount[t]
            if (cnt >= SimpleUI.MAX_LIGHTS_PER_TILE) continue
            tileLightIndices[t * SimpleUI.MAX_LIGHTS_PER_TILE + cnt] = lightIdx
            tileLightCount[t] = cnt + 1
        }
    }

    private var lastWindowDebugKey: String? = null

    private fun logWindowDebug(
        ox: Int, oy: Int, oz: Int,
        ex: Int, ey: Int, ez: Int,
        triCount: Int,
        playerInWindow: Boolean,
        px: Float, py: Float, pz: Float
    ) {
        val key = "$ox,$oy,$oz|$ex,$ey,$ez|$triCount|$playerInWindow"
        if (key == lastWindowDebugKey) return
        lastWindowDebugKey = key
        println(
            "[LightWindow] " +
                "player=(%.2f,%.2f,%.2f) ".format(px, py, pz) +
                "playerInWindow=$playerInWindow " +
                "origin=($ox,$oy,$oz) end=($ex,$ey,$ez) " +
                "size=(${ex - ox}x${ey - oy}x${ez - oz}) " +
                "shadowTris=$triCount"
        )
    }

    // Throttled diagnostic for the lighting pipeline. Tracks the most
    // recent stage fingerprints so that we only print when something
    // visibly changes — otherwise the per-frame call would flood the log.
    private var lastUploadLightingKey: String? = null

    private fun logUploadLighting(
        candidate: List<com.roguelike.core.model.LightSource>,
        frustum: List<com.roguelike.core.model.LightSource>,
        visible: List<com.roguelike.core.model.LightSource>
    ) {
        val firstId = visible.firstOrNull()?.let { "(%.1f,%.1f,%.1f)".format(it.x, it.y, it.z) } ?: "none"
        val key = "${candidate.size}|${frustum.size}|${visible.size}|$firstId"
        if (key == lastUploadLightingKey) return
        lastUploadLightingKey = key

        val p = player?.position
        val playerStr = if (p != null) "(%.2f,%.2f,%.2f)".format(p.x, p.y, p.z) else "?"
        println(
            "[LightUpload] player=$playerStr" +
                " candidates=${candidate.size}" +
                " afterFrustum=${frustum.size}" +
                " uploaded=${visible.size}" +
                " (cap=${SimpleUI.MAX_LIGHTS})" +
                " firstUploaded=$firstId"
        )
    }

    private fun isSolidWall(node: com.roguelike.core.model.WorldNode, slot: TileSlot): Boolean {
        if (!node.hasTile(slot)) return false
        val tile = node.getTile(slot)
        // Doors and doorway-wall variants are NOT solid wall edges — their
        // shadow geometry is contributed via mesh triangles in
        // collectDoorShadowTriangles so light passes through the opening.
        if (tile is DoorNorthTile || tile is DoorSouthTile || tile is DoorEastTile || tile is DoorWestTile) return false
        if (tile is WallDoorwayNorthTile || tile is WallDoorwaySouthTile || tile is WallDoorwayEastTile || tile is WallDoorwayWestTile) return false
        return true
    }

    /**
     * Emits shadow-occluder triangles for any door slots on [node]. For each
     * door slot we emit:
     *   1) The doorway-frame mesh (a wall with an opening cut into it).
     *   2) The door panel — either the closed slab in its rest pose, or the
     *      same closed slab swung ~95° around its narrow vertical edge for
     *      the open state. The transforms used here match those in
     *      [drawWallOrDoor] so the shadow geometry tracks the visible geometry.
     *
     * For every batch of triangles appended to [out] the [onBatch] callback
     * is invoked with the slot that produced them, the global index of the
     * first triangle in the batch, and the batch's triangle count. This lets
     * the caller register the same triangles in multiple cells (e.g. the
     * owning cell and the cell on the other side of the wall).
     */
    private fun collectDoorShadowTriangles(
        node: com.roguelike.core.model.WorldNode,
        x: Int, y: Int, z: Int,
        out: FloatBuf,
        onBatch: (slot: TileSlot, firstTri: Int, count: Int) -> Unit
    ) {
        for (slot in arrayOf(TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, TileSlot.WALL_EAST, TileSlot.WALL_WEST)) {
            val tile = node.getTile(slot) ?: continue
            val (isDoor, isOpen) = when (tile) {
                is DoorNorthTile -> true to tile.isOpen
                is DoorSouthTile -> true to tile.isOpen
                is DoorEastTile  -> true to tile.isOpen
                is DoorWestTile  -> true to tile.isOpen
                else -> false to false
            }
            val isDoorwayWall = tile is WallDoorwayNorthTile || tile is WallDoorwaySouthTile ||
                                tile is WallDoorwayEastTile  || tile is WallDoorwayWestTile
            if (!isDoor && !isDoorwayWall) continue
            val (offsetX, offsetY, rotationYDeg) = when (slot) {
                TileSlot.WALL_NORTH -> Triple( 0.0f,  0.5f,   0f)
                TileSlot.WALL_SOUTH -> Triple( 0.0f, -0.5f, 180f)
                TileSlot.WALL_EAST  -> Triple( 0.5f,  0.0f,  90f)
                TileSlot.WALL_WEST  -> Triple(-0.5f,  0.0f, 270f)
                else                -> Triple( 0.0f,  0.0f,   0f)
            }

            doorFrameMesh?.let {
                val first = out.triCount
                val n = collectShadowTriangles(
                    it, x.toFloat(), y.toFloat(), z.toFloat(),
                    offsetX, offsetY, 0f, rotationYDeg, out
                )
                if (n > 0) onBatch(slot, first, n)
            }

            // Doorway walls have no swinging door panel — just the frame above.
            if (!isDoor) continue

            val panel = doorClosedMesh ?: continue
            if (!isOpen) {
                val first = out.triCount
                val n = collectShadowTriangles(
                    panel, x.toFloat(), y.toFloat(), z.toFloat(),
                    offsetX, offsetY, 0f, rotationYDeg, out
                )
                if (n > 0) onBatch(slot, first, n)
            } else {
                val openExtraDeg = 95f
                val scale = panel.scale
                val hingeOffsetLocalX = (1.1f - panel.center.x) * scale
                val radClosed = Math.toRadians(rotationYDeg.toDouble()).toFloat()
                val hingeClosedX = hingeOffsetLocalX * cos(radClosed) + offsetX
                val hingeClosedY = hingeOffsetLocalX * sin(radClosed) + offsetY
                val totalDeg = rotationYDeg + openExtraDeg
                val radOpen = Math.toRadians(totalDeg.toDouble()).toFloat()
                val openOffX = hingeClosedX - hingeOffsetLocalX * cos(radOpen)
                val openOffY = hingeClosedY - hingeOffsetLocalX * sin(radOpen)
                val first = out.triCount
                val n = collectShadowTriangles(
                    panel, x.toFloat(), y.toFloat(), z.toFloat(),
                    openOffX, openOffY, 0f, totalDeg, out
                )
                if (n > 0) onBatch(slot, first, n)
            }
        }
    }

    private fun collectShadowTriangles(
        mesh: MeshData, nodeX: Float, nodeY: Float, nodeZ: Float,
        offsetX: Float, offsetY: Float, offsetZ: Float,
        rotationYDeg: Float, out: FloatBuf
    ): Int {
        // Fast path: hand the mesh's index buffer to SimpleUI's allocation-free
        // collector. The collector writes 9 floats per triangle directly into
        // out.data starting at the current write head, returns the count
        // actually written (clamped to remaining capacity), and we advance
        // out.size in lock-step. No per-vertex FloatArray allocations.
        val capacityTris = out.data.size / 9
        val firstTri = out.size / 9
        val emitted = ui.collectMeshShadowTriangles(
            mesh.vertices, mesh.indices,
            mesh.center.x, mesh.center.y, mesh.center.z, mesh.scale,
            nodeX, nodeY, nodeZ,
            offsetX, offsetY, offsetZ,
            rotationYDeg,
            out.data, firstTri, capacityTris
        )
        out.size = (firstTri + emitted) * 9
        return emitted
    }

    private fun renderWorld(w: World) {
        val floorR = 0.25f; val floorG = 0.30f; val floorB = 0.40f
        val wallR  = 0.55f; val wallG  = 0.42f; val wallB  = 0.30f

        // Sparse traversal: iterate ONLY non-empty nodes via the per-Z
        // index instead of walking the entire 3-D grid. On large worlds
        // (~170k cells, ~5-8k populated) this is ~20× fewer cells touched
        // and removes the per-cell frustum test that used to spike
        // renderWorld to 20-30ms whenever a big chunk passed the chunk
        // frustum test.
        //
        // Frustum culling is still applied per cell, but only to the
        // populated subset, so the worst case is bounded by content size
        // rather than world volume.
        w.forEachNonEmptyInWindow(0, 0, 0, w.width, w.height, w.depth) { node ->
            val x = node.x; val y = node.y; val z = node.z
            if (!camera.isBoxInFrustum(
                    x.toFloat() - 0.1f, y.toFloat() - 0.1f, z.toFloat() - 0.1f,
                    x.toFloat() + 1.1f, y.toFloat() + 1.1f, z.toFloat() + 1.1f
                )
            ) return@forEachNonEmptyInWindow

            val hasFloor = node.hasTile(TileSlot.FLOOR)
            val hasCeiling = node.hasTile(TileSlot.CEILING)
            val hasWallN = node.hasTile(TileSlot.WALL_NORTH)
            val hasWallS = node.hasTile(TileSlot.WALL_SOUTH)
            val hasWallE = node.hasTile(TileSlot.WALL_EAST)
            val hasWallW = node.hasTile(TileSlot.WALL_WEST)
            val hasStairs = node.hasTile(TileSlot.STAIRS)
            // Non-empty index guarantees at least one tile, but a node may
            // hold only props/lights — skip if no renderable slot is set.
            if (!(hasFloor || hasCeiling || hasWallN || hasWallS || hasWallE || hasWallW || hasStairs)) return@forEachNonEmptyInWindow

            val tbx = x.toFloat(); val tby = y.toFloat(); val tbz = z.toFloat()

            if (hasFloor) floorMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetZ = -0.5f, r = floorR, g = floorG, b = floorB) }
            if (hasCeiling) ceilingMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetZ = 0.5f, r = floorR, g = floorG, b = floorB) }
            if (hasWallN) drawWallOrDoor(node, TileSlot.WALL_NORTH, tbx, tby, tbz, offsetY = 0.5f, rotationYDeg = 0f, r = wallR, g = wallG, b = wallB)
            if (hasWallS) drawWallOrDoor(node, TileSlot.WALL_SOUTH, tbx, tby, tbz, offsetY = -0.5f, rotationYDeg = 180f, r = wallR, g = wallG, b = wallB)
            if (hasWallE) drawWallOrDoor(node, TileSlot.WALL_EAST, tbx, tby, tbz, offsetX = 0.5f, rotationYDeg = 90f, r = wallR, g = wallG, b = wallB)
            if (hasWallW) drawWallOrDoor(node, TileSlot.WALL_WEST, tbx, tby, tbz, offsetX = -0.5f, rotationYDeg = 270f, r = wallR, g = wallG, b = wallB)
            if (hasStairs) {
                val tile = node.getTile(TileSlot.STAIRS)
                if (tile is StairsTile) {
                    stairsMesh?.let { drawModelAtNode(it, tbx, tby, tbz, rotationYDeg = stairsRenderRotation(tile.rotationY), r = 0.45f, g = 0.40f, b = 0.35f) }
                } else if (tile is LadderTile) {
                    val rot = tile.rotationY
                    val offX = when (rot) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                    val offY = when (rot) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                    ladderMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = offX, offsetY = offY, rotationYDeg = rot, r = 0.50f, g = 0.38f, b = 0.25f) }
                }
            }
        }
    }

    /**
     * Draws either a wall mesh, or a doorway frame + door panel (closed or open)
     * if the wall slot has a door tile.
     */
    private fun drawWallOrDoor(
        node: com.roguelike.core.model.WorldNode,
        slot: TileSlot,
        tbx: Float, tby: Float, tbz: Float,
        offsetX: Float = 0f, offsetY: Float = 0f,
        rotationYDeg: Float,
        r: Float, g: Float, b: Float
    ) {
        val tile = node.getTile(slot)
        val isDoor = tile is DoorNorthTile || tile is DoorSouthTile || tile is DoorEastTile || tile is DoorWestTile
        val isDoorwayWall = tile is WallDoorwayNorthTile || tile is WallDoorwaySouthTile || tile is WallDoorwayEastTile || tile is WallDoorwayWestTile
        if (isDoorwayWall) {
            // Render the doorway frame mesh in place of a normal wall slab —
            // no swinging door panel.
            doorFrameMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = offsetX, offsetY = offsetY, rotationYDeg = rotationYDeg, r = r, g = g, b = b) }
            return
        }
        if (!isDoor) {
            wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = offsetX, offsetY = offsetY, rotationYDeg = rotationYDeg, r = r, g = g, b = b) }
            return
        }
        val isOpen = when (tile) {
            is DoorNorthTile -> tile.isOpen
            is DoorSouthTile -> tile.isOpen
            is DoorEastTile  -> tile.isOpen
            is DoorWestTile  -> tile.isOpen
            else -> false
        }
        // Always draw the doorway frame (centered on the wall like a normal wall slab)
        doorFrameMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = offsetX, offsetY = offsetY, rotationYDeg = rotationYDeg, r = r, g = g, b = b) }

        // Door panel rendering.
        //
        // The closed door uses the exact same offset and rotation as the
        // doorway frame, so the closed slab sits flush inside the doorway.
        //
        // The "open" state is produced by taking the SAME closed mesh and
        // swinging it ~95° around one of its narrow vertical edges. The narrow
        // edge in the source mesh (door_n_*) lives at local X = +1.1 (right
        // edge of the slab when looking along −Z). To keep that hinge fixed
        // in world space while we add an extra rotation, we compensate the
        // draw offset so the hinge of the rotated mesh lands at the same
        // world point it had in the closed state.
        val panelMesh = doorClosedMesh ?: return
        if (!isOpen) {
            drawModelAtNode(
                panelMesh, tbx, tby, tbz,
                offsetX = offsetX, offsetY = offsetY,
                rotationYDeg = rotationYDeg,
                r = 0.55f, g = 0.35f, b = 0.25f
            )
            return
        }

        // --- open state: swing around the narrow +X edge ---
        val openExtraDeg = 95f
        val scale = panelMesh.scale
        // Hinge offset from the mesh's bbox centre, in local model X (planar).
        // The mesh's Y/Z are swapped on draw, so the X axis stays as the planar
        // axis perpendicular to the wall normal. Distance from centre to the
        // narrow edge = (maxX - cx) * scale.
        val hingeOffsetLocalX = (1.1f - panelMesh.center.x) * scale  // ≈ +0.393

        // Closed-state hinge world position (relative to cell centre):
        //   rotate(hingeOffsetLocalX, 0, by rotationYDeg) + (offsetX, offsetY)
        val radClosed = Math.toRadians(rotationYDeg.toDouble()).toFloat()
        val hingeClosedX = hingeOffsetLocalX * cos(radClosed) + offsetX
        val hingeClosedY = hingeOffsetLocalX * sin(radClosed) + offsetY

        // Open-state hinge world position with extra rotation applied:
        //   rotate(hingeOffsetLocalX, 0, by rotationYDeg + openExtraDeg) + (openOffX, openOffY)
        // Solve for (openOffX, openOffY) such that hingeOpen == hingeClosed.
        val radOpen = Math.toRadians((rotationYDeg + openExtraDeg).toDouble()).toFloat()
        val openOffX = hingeClosedX - hingeOffsetLocalX * cos(radOpen)
        val openOffY = hingeClosedY - hingeOffsetLocalX * sin(radOpen)

        drawModelAtNode(
            panelMesh, tbx, tby, tbz,
            offsetX = openOffX, offsetY = openOffY,
            rotationYDeg = rotationYDeg + openExtraDeg,
            r = 0.35f, g = 0.55f, b = 0.25f
        )
    }

    private fun drawModelAtNode(
        mesh: MeshData, nodeX: Float, nodeY: Float, nodeZ: Float,
        offsetX: Float = 0f, offsetY: Float = 0f, offsetZ: Float = 0f,
        rotationYDeg: Float = 0f,
        r: Float, g: Float, b: Float, a: Float = 1f,
        pivotLocalX: Float? = null, pivotLocalY: Float? = null, pivotLocalZ: Float? = null
    ) {
        val cx = pivotLocalX ?: mesh.center.x
        val cy = pivotLocalY ?: mesh.center.y
        val cz = pivotLocalZ ?: mesh.center.z
        val colors = mesh.colors
        if (colors != null) {
            ui.drawMeshPerVertexColor(
                mesh.vertices, colors, mesh.indices,
                cx, cy, cz, mesh.scale,
                nodeX, nodeY, nodeZ,
                offsetX, offsetY, offsetZ,
                rotationYDeg, a
            )
        } else {
            ui.drawMeshSolid(
                mesh.vertices, mesh.indices,
                cx, cy, cz, mesh.scale,
                nodeX, nodeY, nodeZ,
                offsetX, offsetY, offsetZ,
                rotationYDeg,
                r, g, b, a
            )
        }
    }

    fun resize(width: Int, height: Int) {
        camera.resize(width, height)
    }

    fun dispose() {
        proceduralManager.dispose()
        world = null
        player = null
    }
}

/**
 * Maps a stairs tile's logical [tileRotationY] (0°=N, 90°=E, 180°=S,
 * 270°=W — same convention as the in-world arrow indicator) to the
 * world-space Y rotation we have to apply to the `stairs_n.obj` mesh
 * so its visible ramp ascends in the indicated direction.
 *
 * Why this isn't just `+ 180f`: the source mesh ascends toward −obj-Z,
 * which becomes world −Y (south) after the renderer's axis swap. For
 * N/S tiles we add 180° so the mesh's ascent ends up aligned with the
 * arrow indicator. For E/W tiles the tile rotation already swings the
 * mesh 90°/270° around Z; adding another 180° on top would land the
 * ramp facing the opposite cardinal, so we leave it alone.
 */
internal fun stairsRenderRotation(tileRotationY: Float): Float {
    val normalized = ((tileRotationY % 360f) + 360f) % 360f
    val isNorthSouth = normalized < 45f || normalized >= 315f ||
            (normalized in 135f..225f)
    return if (isNorthSouth) tileRotationY + 180f else tileRotationY
}
