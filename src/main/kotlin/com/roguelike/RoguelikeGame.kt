package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
import com.roguelike.core.systems.MovementSystem
import com.roguelike.core.systems.InteractionSystem
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

        // Open file picker immediately
        fileDialog.open(FileDialog.Mode.OPEN, File("saved-worlds")) { file ->
            if (file != null) loadWorldFile(file)
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
                move.move(p, moveDir, delta, 3f)
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

        // Upload lighting
        uploadLighting(w)

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

    private fun uploadLighting(w: World) {
        if (w.lightSources.isNotEmpty()) {
            val gridW = w.width; val gridH = w.height; val gridD = w.depth
            val occupancy = IntArray(gridW * gridH * gridD)
            val shadowTriangles = mutableListOf<Float>()

            // Per-cell triangle index lists (global triangle index, where one
            // triangle = 9 floats in `shadowTriangles`). A triangle may live in
            // more than one list when the geometry straddles a cell boundary
            // (notably doorway frames and the swung-open door panel), so that
            // the GLSL ray-march finds it regardless of which cell its DDA
            // happens to be marching through.
            val perCellTris = Array(gridW * gridH * gridD) { mutableListOf<Int>() }
            fun cellIdx(cx: Int, cy: Int, cz: Int): Int? {
                if (cx < 0 || cx >= gridW || cy < 0 || cy >= gridH || cz < 0 || cz >= gridD) return null
                return cz * gridW * gridH + cy * gridW + cx
            }

            for (z in 0 until gridD) {
                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val node = w.getNode(x, y, z) ?: continue
                        var flags = 0
                        // A wall slot contributes a solid wall-edge flag only if
                        // it contains a NON-door wall tile. Door slots are
                        // shadow-cast via per-cell mesh triangles below, so that
                        // light can pass through the open part of the doorway.
                        if (isSolidWall(node, TileSlot.WALL_NORTH)) flags = flags or 1
                        if (isSolidWall(node, TileSlot.WALL_SOUTH)) flags = flags or 2
                        if (isSolidWall(node, TileSlot.WALL_EAST))  flags = flags or 4
                        if (isSolidWall(node, TileSlot.WALL_WEST))  flags = flags or 8
                        if (node.hasTile(TileSlot.FLOOR))      flags = flags or 16
                        if (node.hasTile(TileSlot.CEILING))    flags = flags or 32
                        occupancy[z * gridW * gridH + y * gridW + x] = flags

                        // Stairs / ladder shadow geometry — register to owning cell only.
                        val ownIdx = cellIdx(x, y, z) ?: continue
                        if (node.hasTile(TileSlot.STAIRS)) {
                            val tile = node.getTile(TileSlot.STAIRS)
                            if (tile is StairsTile) {
                                stairsMesh?.let {
                                    val first = shadowTriangles.size / 9
                                    val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), 0f, 0f, 0f, tile.rotationY + 180f, shadowTriangles)
                                    for (k in 0 until n) perCellTris[ownIdx].add(first + k)
                                }
                            } else if (tile is LadderTile) {
                                val rotY = tile.rotationY
                                val offX = when (rotY) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                                val offY = when (rotY) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                                ladderMesh?.let {
                                    val first = shadowTriangles.size / 9
                                    val n = collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, 0f, rotY, shadowTriangles)
                                    for (k in 0 until n) perCellTris[ownIdx].add(first + k)
                                }
                            }
                        }

                        // Door-occluder triangles. Registered to BOTH the
                        // owning cell and the cell on the other side of the
                        // wall the door sits on (door frames and the swung
                        // open panel straddle the wall boundary).
                        collectDoorShadowTriangles(node, x, y, z, shadowTriangles) { slot, firstTri, n ->
                            for (k in 0 until n) perCellTris[ownIdx].add(firstTri + k)
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

            // Pack per-cell triangle lists into the cell-flag bits. We may
            // need to remap by appending duplicate references to a contiguous
            // "cell tri index" buffer — but the shader expects a contiguous
            // run of triangles per cell. So we expand the triangle buffer:
            // for each cell that references triangles, append the actual
            // triangle vertex data in run, and store start+count in flags.
            val expandedTris = mutableListOf<Float>()
            for (z in 0 until gridD) {
                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val idx = z * gridW * gridH + y * gridW + x
                        val list = perCellTris[idx]
                        if (list.isEmpty()) continue
                        val start = expandedTris.size / 9
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

            val lights = w.lightSources.map { ls ->
                SimpleUI.LightData(ls.x, ls.y, ls.z, ls.intensity, ls.colorR(), ls.colorG(), ls.colorB(), ls.radius)
            }
            ui.updateLighting(lights, occupancy, gridW, gridH, gridD)
            val triArray = FloatArray(expandedTris.size)
            for (i in expandedTris.indices) triArray[i] = expandedTris[i]
            ui.updateShadowTriangles(triArray)
        } else {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1)
            ui.updateShadowTriangles(FloatArray(0))
        }
    }

    private fun isSolidWall(node: com.roguelike.core.model.WorldNode, slot: TileSlot): Boolean {
        if (!node.hasTile(slot)) return false
        val tile = node.getTile(slot)
        return tile !is DoorNorthTile && tile !is DoorSouthTile && tile !is DoorEastTile && tile !is DoorWestTile
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
            if (!isDoor) continue
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

        for (z in 0..maxRenderZ) {
            for (x in 0 until w.width) {
                for (y in 0 until w.height) {
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
        while (i < indices.size - 2) {
            val v0 = xform(indices[i].toInt() and 0xFFFF)
            val v1 = xform(indices[i + 1].toInt() and 0xFFFF)
            val v2 = xform(indices[i + 2].toInt() and 0xFFFF)
            ui.drawGpuTriangle(
                v0[0], v0[1], v0[2], v0[3], v0[4], v0[5],
                v1[0], v1[1], v1[2], v1[3], v1[4], v1[5],
                v2[0], v2[1], v2[2], v2[3], v2[4], v2[5],
                r, g, b, a
            )
            i += 3
        }
    }

    fun resize(width: Int, height: Int) {
        camera.resize(width, height)
    }

    fun dispose() {
        world = null
        player = null
    }
}
