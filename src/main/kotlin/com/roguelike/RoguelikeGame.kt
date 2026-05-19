package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
import com.roguelike.core.systems.MovementSystem
import com.roguelike.core.systems.InteractionSystem
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

    // Orbital camera following player
    private var azimuth = 0f
    private var elevation = 55f
    private var distance = 12f

    // File dialog
    private val fileDialog = FileDialog(ui, inputSystem)
    private var worldLoaded = false

    // Procedural map manager — drives Arena world generation from socket-based templates.
    private val proceduralManager = ProceduralMapManager(
        tileFactory = ::gameTileFactory,
        worldFactory = { w, h, d -> World(w, h, d) }
    )

    // Rendering Z limit
    private var maxRenderZ = 0

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
        // to draw from before the player picks a starting submap.
        proceduralManager.loadTemplates("src/main/resources/world-submaps/submaps")

        // Open the starting-submap picker. The picked .wld becomes the seed
        // room; the procedural generator grows outward from there.
        val startDir = File("src/main/resources/world-submaps/starting-submaps")
            .takeIf { it.exists() } ?: File("saved-worlds")
        fileDialog.open(FileDialog.Mode.OPEN, startDir) { file ->
            if (file != null) loadInitialSubmap(file)
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
                distance = max(loaded.width, loaded.height).toFloat() * 0.8f
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
                distance = max(loaded.width, loaded.height).toFloat() * 0.8f
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

        // Handle movement (WASD) — only when Shift is NOT held
        val shiftHeld = inputSystem.isKeyPressed(GLFW_KEY_LEFT_SHIFT) || inputSystem.isKeyPressed(GLFW_KEY_RIGHT_SHIFT)
        if (!shiftHeld) {
            val moveDir = input.getMovementDirection()
            if (!moveDir.isZero) {
                moveDir.nor()
                // Rotate movement by camera azimuth so W = forward relative to view
                val azRad = Math.toRadians(azimuth.toDouble()).toFloat()
                val cosA = cos(azRad); val sinA = sin(azRad)
                val fx = -cosA; val fy = -sinA
                val rx = fy; val ry = -fx
                val worldDirX = moveDir.x * rx + moveDir.y * fx
                val worldDirY = moveDir.x * ry + moveDir.y * fy
                moveDir.set(worldDirX, worldDirY, 0f).nor()
                move.move(p, moveDir, delta, 7f)
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

        // Camera orbit controls (Shift + WASD for pitch/zoom, Q/E always for rotation)
        if (shiftHeld) {
            if (inputSystem.isKeyPressed(GLFW_KEY_W)) elevation = (elevation + 60f * delta).coerceAtMost(89f)
            if (inputSystem.isKeyPressed(GLFW_KEY_S)) elevation = (elevation - 60f * delta).coerceAtLeast(10f)
        }
        if (inputSystem.isKeyPressed(GLFW_KEY_Q)) azimuth -= 90f * delta
        if (inputSystem.isKeyPressed(GLFW_KEY_E)) azimuth += 90f * delta

        // Zoom
        val scroll = inputSystem.getScrollDelta()
        if (scroll != 0f) distance = (distance - scroll * 2f).coerceIn(4f, 40f)
        val zoomChange = input.getZoomChange(delta)
        if (zoomChange != 0f) distance = (distance + zoomChange).coerceIn(4f, 40f)

        // Update camera to follow player
        updateOrbitalCamera(p.position)

        // Upload lighting. Lights are pre-filtered by *room distance* so
        // that fixtures sitting more than 3 rooms away from the player
        // never reach the GPU — that cap matches the player's perceptual
        // horizon (you can't directly see into rooms further than a couple
        // of hops anyway) and keeps the shader workload bounded as the
        // procedural world grows.
        val candidateLights = proceduralManager.collectVisibleRoomLights(
            p.position.x, p.position.y, p.position.z,
            maxRoomDistance = 3
        )
        uploadLighting(w, candidateLights)

        // Set VP matrix
        val vpFloats = FloatArray(16)
        camera.viewProjection.get(vpFloats)
        ui.setViewProjection(vpFloats)

        // Render world
        renderWorld(w)

        // Draw player as blue sphere
        val playerDrawX = p.position.x
        val playerDrawY = p.position.y
        val playerDrawZ = p.position.z + 0.4f
        debugRenderer.drawFilledSphere(playerDrawX, playerDrawY, playerDrawZ, 0.2f, camera, 0.2f, 0.4f, 1.0f, 0.9f)
        debugRenderer.drawWireframeSphere(playerDrawX, playerDrawY, playerDrawZ, 0.2f, camera, 0.3f, 0.5f, 1.0f, 0.9f, 10, 1.5f)

        // HUD
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val posStr = "Pos: %.1f, %.1f, %.1f".format(p.position.x, p.position.y, p.position.z)
        ui.drawText(posStr, 10f, 10f, 0.7f, 0.7f, 0.8f, 1f, 1.1f)
        ui.drawText("WASD: Move  Shift+WASD: Camera  Z/X: Zoom  F: Interact  ESC: Menu", 10f, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)

        return true
    }

    private fun updateOrbitalCamera(target: Vec3) {
        val tx = target.x
        val ty = target.y
        val tz = target.z
        val azRad = Math.toRadians(azimuth.toDouble()).toFloat()
        val elRad = Math.toRadians(elevation.toDouble()).toFloat()
        val cosEl = cos(elRad)
        camera.position.set(
            tx + distance * cosEl * cos(azRad),
            ty + distance * cosEl * sin(azRad),
            tz + distance * sin(elRad)
        )
        camera.direction.set(tx - camera.position.x, ty - camera.position.y, tz - camera.position.z).normalize()
        camera.up.set(0f, 0f, 1f)
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
            ui.updateShadowTriangles(FloatArray(0))
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
        val occupancy = IntArray(gridW * gridH * gridD)
        val shadowTriangles = mutableListOf<Float>()

        // Per-cell triangle index lists are also sized to the window only.
        val perCellTris = Array(gridW * gridH * gridD) { mutableListOf<Int>() }
        fun cellIdx(cx: Int, cy: Int, cz: Int): Int? {
            // cx/cy/cz are absolute world voxels; translate into window-local.
            val lx = cx - originX
            val ly = cy - originY
            val lz = cz - originZ
            if (lx < 0 || lx >= gridW || ly < 0 || ly >= gridH || lz < 0 || lz >= gridD) return null
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
                    val wIdx = cellIdx(x, y, z) ?: continue
                    occupancy[wIdx] = flags

                    if (!cellTouchedByAnyLight(x, y, z)) continue

                    if (node.hasTile(TileSlot.STAIRS)) {
                        val tile = node.getTile(TileSlot.STAIRS)
                        if (tile is StairsTile) {
                            stairsMesh?.let {
                                val first = shadowTriangles.size / 9
                                val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), 0f, 0f, 0f, tile.rotationY + 180f, shadowTriangles)
                                for (k in 0 until n) perCellTris[wIdx].add(first + k)
                            }
                        } else if (tile is LadderTile) {
                            val rotY = tile.rotationY
                            val offX = when (rotY) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                            val offY = when (rotY) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                            ladderMesh?.let {
                                val first = shadowTriangles.size / 9
                                val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, 0f, rotY, shadowTriangles)
                                for (k in 0 until n) perCellTris[wIdx].add(first + k)
                            }
                        }
                    }

                    collectDoorShadowTriangles(node, x, y, z, shadowTriangles) { slot, firstTri, n ->
                        for (k in 0 until n) perCellTris[wIdx].add(firstTri + k)
                        val (nx, ny) = when (slot) {
                            TileSlot.WALL_NORTH -> x to (y + 1)
                            TileSlot.WALL_SOUTH -> x to (y - 1)
                            TileSlot.WALL_EAST  -> (x + 1) to y
                            TileSlot.WALL_WEST  -> (x - 1) to y
                            else -> return@collectDoorShadowTriangles
                        }
                        val nbrIdx = cellIdx(nx, ny, z) ?: return@collectDoorShadowTriangles
                        for (k in 0 until n) perCellTris[nbrIdx].add(firstTri + k)
                    }
                }
            }
        }

        // Pack per-cell triangle lists into the cell-flag bits. Bits 7-15
        // hold the per-cell triangle count, bits 16-31 the start index.
        // We now KNOW we'll never overflow the 16-bit start index because
        // the window contains at most a handful of rooms, but we still
        // bail out defensively if a degenerate case would overflow.
        val expandedTris = mutableListOf<Float>()
        for (cz in 0 until gridD) {
            for (cy in 0 until gridH) {
                for (cx in 0 until gridW) {
                    val idx = cz * gridW * gridH + cy * gridW + cx
                    val list = perCellTris[idx]
                    if (list.isEmpty()) continue
                    val start = expandedTris.size / 9
                    if (start > 0xFFFF) {
                        // Hard ceiling — should be unreachable given the
                        // window size, but better to drop a few shadows
                        // than to corrupt every cell's triangle range.
                        continue
                    }
                    val count = list.size.coerceAtMost(0x1FF)
                    for (i in 0 until count) {
                        val src = list[i] * 9
                        for (k in 0 until 9) expandedTris.add(shadowTriangles[src + k])
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
        ui.updateLighting(
            lights, occupancy, gridW, gridH, gridD,
            ambient = 0f,
            gridOriginX = originX, gridOriginY = originY, gridOriginZ = originZ
        )
        val triArray = FloatArray(expandedTris.size)
        for (i in expandedTris.indices) triArray[i] = expandedTris[i]
        ui.updateShadowTriangles(triArray)

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
        logWindowDebug(originX, originY, originZ, endX, endY, endZ, expandedTris.size / 9, pInWindow, px, py, pz)
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
        out: MutableList<Float>,
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
                val first = out.size / 9
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
                val first = out.size / 9
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
                val first = out.size / 9
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
        rotationYDeg: Float, out: MutableList<Float>
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
        val playerZ = kotlin.math.floor(player?.position?.z ?: 0f).toInt()

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
        val topZ = playerZ.coerceIn(0, (w.depth - 1).coerceAtLeast(0))
        for (z in 0..topZ) {
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
                    val hasCeiling = node.hasTile(TileSlot.CEILING) && z < playerZ
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
                            stairsMesh?.let { drawModelAtNode(it, tbx, tby, tbz, rotationYDeg = tile.rotationY + 180f, r = 0.45f, g = 0.40f, b = 0.35f) }
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
