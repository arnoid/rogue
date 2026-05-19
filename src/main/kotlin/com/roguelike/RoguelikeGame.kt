package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
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
        interactionSystem?.update(delta)

        // Notify procedural manager so it can lazily generate adjacent submaps
        // when the player crosses into a new region. New submaps are stamped
        // into `w` in-place, which may also grow the world's dimensions.
        proceduralManager.onPlayerMove(p.position.x, p.position.y, p.position.z)
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
        val candidateLights = proceduralManager.collectVisibleRoomLights(
            p.position.x, p.position.y, p.position.z,
            maxRoomDistance = 1_000_000
        )
        uploadLighting(w, candidateLights)

        // Set VP matrix
        val vpFloats = FloatArray(16)
        camera.viewProjection.get(vpFloats)
        ui.setViewProjection(vpFloats)

        // Render world
        renderWorld(w)

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
        ui.drawText("FPS: %.0f".format(smoothedFps), 10f, 10f, 1f, 1f, 0.4f, 1f, 1.2f)
        val posStr = "Pos: %.1f, %.1f, %.1f".format(p.position.x, p.position.y, p.position.z)
        ui.drawText(posStr, 10f, 32f, 0.7f, 0.7f, 0.8f, 1f, 1.1f)
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

        if (visibleLights.isEmpty()) {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1, ambient = 0f)
            ui.updateShadowTriangles(expandedTriBuf.data, 0)
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
        val originX = minX.coerceIn(0, worldW - 1)
        val originY = minY.coerceIn(0, worldH - 1)
        val originZ = minZ.coerceIn(0, worldD - 1)
        val endX = (maxX + 1).coerceIn(originX + 1, worldW)
        val endY = (maxY + 1).coerceIn(originY + 1, worldH)
        val endZ = (maxZ + 1).coerceIn(originZ + 1, worldD)
        val gridW = endX - originX
        val gridH = endY - originY
        val gridD = endZ - originZ
        val cellCount = gridW * gridH * gridD
        val occupancy = IntArray(cellCount)
        // Reset scratch buffers — outer arrays are reused across frames.
        shadowTriBuf.reset()
        expandedTriBuf.reset()
        ensurePerCellCapacity(cellCount)
        fun cellIdx(cx: Int, cy: Int, cz: Int): Int {
            // cx/cy/cz are absolute world voxels; translate into window-local.
            val lx = cx - originX
            val ly = cy - originY
            val lz = cz - originZ
            if (lx < 0 || lx >= gridW || ly < 0 || ly >= gridH || lz < 0 || lz >= gridD) return -1
            return lz * gridW * gridH + ly * gridW + lx
        }

        // Pre-compute light sphere bounds for cheap per-cell relevance tests.
        // A cell only needs shadow triangles if it sits inside at least one
        // light's radius (otherwise nothing will ever ray-march through it).
        val lightCount = visibleLights.size
        val lx = FloatArray(lightCount)
        val ly = FloatArray(lightCount)
        val lz = FloatArray(lightCount)
        val lr2 = FloatArray(lightCount)
        for (i in 0 until lightCount) {
            val ls = visibleLights[i]
            lx[i] = ls.x; ly[i] = ls.y; lz[i] = ls.z
            lr2[i] = ls.radius * ls.radius
        }
        fun cellTouchedByAnyLight(x: Int, y: Int, z: Int): Boolean {
            val cx0 = x.toFloat(); val cy0 = y.toFloat(); val cz0 = z.toFloat()
            val cx1 = cx0 + 1f;    val cy1 = cy0 + 1f;    val cz1 = cz0 + 1f
            for (i in 0 until lightCount) {
                val px = lx[i].coerceIn(cx0, cx1)
                val py = ly[i].coerceIn(cy0, cy1)
                val pz = lz[i].coerceIn(cz0, cz1)
                val dx = px - lx[i]; val dy = py - ly[i]; val dz = pz - lz[i]
                if (dx * dx + dy * dy + dz * dz <= lr2[i]) return true
            }
            return false
        }

        // Iterate ONLY the window — not the entire grown world.
        for (z in originZ until endZ) {
            for (y in originY until endY) {
                for (x in originX until endX) {
                    val node = w.getNode(x, y, z) ?: continue
                    var flags = 0
                    if (isSolidWall(node, TileSlot.WALL_NORTH)) flags = flags or 1
                    if (isSolidWall(node, TileSlot.WALL_SOUTH)) flags = flags or 2
                    if (isSolidWall(node, TileSlot.WALL_EAST))  flags = flags or 4
                    if (isSolidWall(node, TileSlot.WALL_WEST))  flags = flags or 8
                    if (node.hasTile(TileSlot.FLOOR))      flags = flags or 16
                    if (node.hasTile(TileSlot.CEILING))    flags = flags or 32
                    val wIdx = cellIdx(x, y, z); if (wIdx < 0) continue
                    occupancy[wIdx] = flags

                    if (!cellTouchedByAnyLight(x, y, z)) continue

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

                    collectDoorShadowTriangles(node, x, y, z, shadowTriBuf) { slot, firstTri, n ->
                        for (k in 0 until n) addPerCellTri(wIdx, firstTri + k)
                        val (nx, ny) = when (slot) {
                            TileSlot.WALL_NORTH -> x to (y + 1)
                            TileSlot.WALL_SOUTH -> x to (y - 1)
                            TileSlot.WALL_EAST  -> (x + 1) to y
                            TileSlot.WALL_WEST  -> (x - 1) to y
                            else -> return@collectDoorShadowTriangles
                        }
                        val nbrIdx = cellIdx(nx, ny, z); if (nbrIdx < 0) return@collectDoorShadowTriangles
                        for (k in 0 until n) addPerCellTri(nbrIdx, firstTri + k)
                    }
                }
            }
        }

        // Pack per-cell triangle lists into the cell-flag bits. Bits 7-15
        // hold the per-cell triangle count, bits 16-31 the start index.
        // We now KNOW we'll never overflow the 16-bit start index because
        // the window contains at most a handful of rooms, but we still
        // bail out defensively if a degenerate case would overflow.
        for (cz in 0 until gridD) {
            for (cy in 0 until gridH) {
                for (cx in 0 until gridW) {
                    val idx = cz * gridW * gridH + cy * gridW + cx
                    val listCount = perCellTriCount[idx]
                    if (listCount == 0) continue
                    val list = perCellTris[idx]
                    val start = expandedTriBuf.triCount
                    if (start > 0xFFFF) {
                        // Hard ceiling — should be unreachable given the
                        // window size, but better to drop a few shadows
                        // than to corrupt every cell's triangle range.
                        continue
                    }
                    val count = listCount.coerceAtMost(0x1FF)
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
                    f = f or ((count and 0x1FF) shl 7)
                    f = f or ((start and 0xFFFF) shl 16)
                    occupancy[idx] = f
                }
            }
        }

        val lights = visibleLights.map { ls ->
            SimpleUI.LightData(ls.x, ls.y, ls.z, ls.intensity, ls.colorR(), ls.colorG(), ls.colorB(), ls.radius)
        }
        // Forward+ tile binning — call FIRST so it stamps the cached
        // screen/tile params into SimpleUI before updateLighting embeds
        // them in the UBO this same frame (otherwise the shader reads
        // last frame's screen dims, which manifests as misaligned tile
        // lookups after a window resize).
        uploadLightTiles(visibleLights)
        ui.updateLighting(
            lights, occupancy, gridW, gridH, gridD,
            ambient = 0f,
            gridOriginX = originX, gridOriginY = originY, gridOriginZ = originZ
        )
        // updateShadowTriangles reads from the start of the supplied
        // FloatArray up to `triCount * 9` floats, so we can hand it the
        // backing buffer directly — no allocation per frame.
        ui.updateShadowTriangles(expandedTriBuf.data, expandedTriBuf.triCount)

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
        val verts = mesh.vertices
        val indices = mesh.indices
        val scale = mesh.scale
        val cx = mesh.center.x; val cy = mesh.center.y; val cz = mesh.center.z
        val radY = Math.toRadians(rotationYDeg.toDouble()).toFloat()
        val cosY = cos(radY); val sinY = sin(radY)

        fun xformPos(idx: Int): FloatArray {
            val vi = idx * 6
            val mx = (verts[vi] - cx) * scale
            val my = (verts[vi + 1] - cy) * scale
            val mz = (verts[vi + 2] - cz) * scale
            var px = mx; var py = mz; var pz = my
            if (rotationYDeg != 0f) {
                val rpx = px * cosY - py * sinY
                val rpy = px * sinY + py * cosY
                px = rpx; py = rpy
            }
            px += nodeX + 0.5f + offsetX
            py += nodeY + 0.5f + offsetY
            pz += nodeZ + 0.5f + offsetZ
            return floatArrayOf(px, py, pz)
        }

        var triCount = 0
        var i = 0
        while (i < indices.size - 2) {
            val v0 = xformPos(indices[i].toInt() and 0xFFFF)
            val v1 = xformPos(indices[i + 1].toInt() and 0xFFFF)
            val v2 = xformPos(indices[i + 2].toInt() and 0xFFFF)
            // Atomically append the 9 floats; if the buffer is full,
            // skip the triangle entirely to keep the buffer self-consistent.
            if (out.size + 9 > out.data.size) {
                i += 3
                continue
            }
            out.add(v0[0]); out.add(v0[1]); out.add(v0[2])
            out.add(v1[0]); out.add(v1[1]); out.add(v1[2])
            out.add(v2[0]); out.add(v2[1]); out.add(v2[2])
            triCount++
            i += 3
        }
        return triCount
    }

    private fun renderWorld(w: World) {
        val floorR = 0.25f; val floorG = 0.30f; val floorB = 0.40f
        val wallR  = 0.55f; val wallG  = 0.42f; val wallB  = 0.30f

        // Render the player's current Z layer and every layer beneath it.
        // Anything above the player is hidden so upper floors never occlude
        // the view down into the level they're standing on. Clamp into the
        // world's actual depth so freshly-grown worlds don't read past the
        // grid bounds.
        //
        // CRITICAL: iterate only the cells that actually fall inside the
        // camera frustum. As the procedural world expands past ~60×60 cells,
        // iterating the full grid floods `drawGpuTriangle` with vertices,
        // hits `maxGpuVertices` mid-frame, and the remaining triangles are
        // silently dropped — producing the "lighting breaks the further you
        // walk" symptom. Frustum culling keeps the per-frame vertex count
        // bounded to roughly what's actually visible.
        // Render every Z layer in the world — no player-relative cap.
        // Frustum culling alone decides what's drawn.
        for (z in 0 until w.depth) {
            for (x in 0 until w.width) {
                for (y in 0 until w.height) {
                    // Cell AABB in world space is (x..x+1, y..y+1, z..z+1).
                    // Inflate slightly so meshes that overhang the cell
                    // (e.g. doorframes, ladders) aren't false-culled.
                    if (!camera.isBoxInFrustum(
                            x.toFloat() - 0.1f, y.toFloat() - 0.1f, z.toFloat() - 0.1f,
                            x.toFloat() + 1.1f, y.toFloat() + 1.1f, z.toFloat() + 1.1f
                        )
                    ) continue
                    val node = w.getNode(x, y, z) ?: continue
                    val hasFloor = node.hasTile(TileSlot.FLOOR)
                    val hasCeiling = node.hasTile(TileSlot.CEILING)
                    val hasWallN = node.hasTile(TileSlot.WALL_NORTH)
                    val hasWallS = node.hasTile(TileSlot.WALL_SOUTH)
                    val hasWallE = node.hasTile(TileSlot.WALL_EAST)
                    val hasWallW = node.hasTile(TileSlot.WALL_WEST)
                    val hasStairs = node.hasTile(TileSlot.STAIRS)
                    if (!(hasFloor || hasCeiling || hasWallN || hasWallS || hasWallE || hasWallW || hasStairs)) continue

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
        val verts = mesh.vertices
        val indices = mesh.indices
        val scale = mesh.scale
        val cx = pivotLocalX ?: mesh.center.x
        val cy = pivotLocalY ?: mesh.center.y
        val cz = pivotLocalZ ?: mesh.center.z
        val radY = Math.toRadians(rotationYDeg.toDouble()).toFloat()
        val cosY = cos(radY); val sinY = sin(radY)

        fun xform(idx: Int): FloatArray {
            val vi = idx * 6
            val mx = (verts[vi] - cx) * scale
            val my = (verts[vi + 1] - cy) * scale
            val mz = (verts[vi + 2] - cz) * scale
            val mnx = verts[vi + 3]; val mny = verts[vi + 4]; val mnz = verts[vi + 5]
            var px = mx; var py = mz; var pz = my
            var nx = mnx; var ny = mnz; var nz = mny
            if (rotationYDeg != 0f) {
                val rpx = px * cosY - py * sinY; val rpy = px * sinY + py * cosY; px = rpx; py = rpy
                val rnx = nx * cosY - ny * sinY; val rny = nx * sinY + ny * cosY; nx = rnx; ny = rny
            }
            px += nodeX + 0.5f + offsetX
            py += nodeY + 0.5f + offsetY
            pz += nodeZ + 0.5f + offsetZ
            return floatArrayOf(px, py, pz, nx, ny, nz)
        }

        var i = 0
        val colors = mesh.colors
        while (i < indices.size - 2) {
            val idx0 = indices[i].toInt() and 0xFFFF
            val idx1 = indices[i + 1].toInt() and 0xFFFF
            val idx2 = indices[i + 2].toInt() and 0xFFFF
            val v0 = xform(idx0)
            val v1 = xform(idx1)
            val v2 = xform(idx2)
            if (colors != null) {
                // Use per-vertex palette colors sampled from the model's PNG texture
                val cr0 = colors[idx0 * 3]; val cg0 = colors[idx0 * 3 + 1]; val cb0 = colors[idx0 * 3 + 2]
                val cr1 = colors[idx1 * 3]; val cg1 = colors[idx1 * 3 + 1]; val cb1 = colors[idx1 * 3 + 2]
                val cr2 = colors[idx2 * 3]; val cg2 = colors[idx2 * 3 + 1]; val cb2 = colors[idx2 * 3 + 2]
                ui.drawGpuTrianglePerVertexColor(
                    v0[0], v0[1], v0[2], v0[3], v0[4], v0[5], cr0, cg0, cb0, a,
                    v1[0], v1[1], v1[2], v1[3], v1[4], v1[5], cr1, cg1, cb1, a,
                    v2[0], v2[1], v2[2], v2[3], v2[4], v2[5], cr2, cg2, cb2, a
                )
            } else {
                ui.drawGpuTriangle(
                    v0[0], v0[1], v0[2], v0[3], v0[4], v0[5],
                    v1[0], v1[1], v1[2], v1[3], v1[4], v1[5],
                    v2[0], v2[1], v2[2], v2[3], v2[4], v2[5],
                    r, g, b, a
                )
            }
            i += 3
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
