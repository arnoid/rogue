package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
import com.roguelike.core.systems.MovementSystem
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
    private var inputHandler: InputHandler? = null
    private var lastFrameTime: Long = System.nanoTime()

    // Asset loading
    private val assetLoader = AssetLoader()
    private var floorMesh: MeshData? = null
    private var ceilingMesh: MeshData? = null
    private var wallMesh: MeshData? = null
    private var ladderMesh: MeshData? = null
    private var stairsMesh: MeshData? = null

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
        ui.drawText("WASD: Move  Shift+WASD: Camera  Z/X: Zoom  ESC: Menu", 10f, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)

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
            for (z in 0 until gridD) {
                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val node = w.getNode(x, y, z) ?: continue
                        var flags = 0
                        if (node.hasTile(TileSlot.WALL_NORTH)) flags = flags or 1
                        if (node.hasTile(TileSlot.WALL_SOUTH)) flags = flags or 2
                        if (node.hasTile(TileSlot.WALL_EAST))  flags = flags or 4
                        if (node.hasTile(TileSlot.WALL_WEST))  flags = flags or 8
                        if (node.hasTile(TileSlot.FLOOR))      flags = flags or 16
                        if (node.hasTile(TileSlot.CEILING))    flags = flags or 32

                        var cellTriStart = shadowTriangles.size / 9
                        var cellTriCount = 0

                        if (node.hasTile(TileSlot.STAIRS)) {
                            val tile = node.getTile(TileSlot.STAIRS)
                            if (tile is StairsTile) {
                                stairsMesh?.let { cellTriCount += collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), 0f, 0f, 0f, tile.rotationY + 180f, shadowTriangles) }
                            } else if (tile is LadderTile) {
                                val rotY = tile.rotationY
                                val offX = when (rotY) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                                val offY = when (rotY) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                                ladderMesh?.let { cellTriCount += collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, 0f, rotY, shadowTriangles) }
                            }
                        }

                        if (cellTriCount > 0) {
                            flags = flags or ((cellTriCount and 0x1FF) shl 7)
                            flags = flags or ((cellTriStart and 0xFFFF) shl 16)
                        }
                        occupancy[z * gridW * gridH + y * gridW + x] = flags
                    }
                }
            }
            val lights = w.lightSources.map { ls ->
                SimpleUI.LightData(ls.x, ls.y, ls.z, ls.intensity, ls.colorR(), ls.colorG(), ls.colorB(), ls.radius)
            }
            ui.updateLighting(lights, occupancy, gridW, gridH, gridD)
            val triArray = FloatArray(shadowTriangles.size)
            for (i in shadowTriangles.indices) triArray[i] = shadowTriangles[i]
            ui.updateShadowTriangles(triArray)
        } else {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1)
            ui.updateShadowTriangles(FloatArray(0))
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
                    if (hasWallN) wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = 0.5f, rotationYDeg = 0f, r = wallR, g = wallG, b = wallB) }
                    if (hasWallS) wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = -0.5f, rotationYDeg = 0f, r = wallR, g = wallG, b = wallB) }
                    if (hasWallE) wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = 0.5f, rotationYDeg = 90f, r = wallR, g = wallG, b = wallB) }
                    if (hasWallW) wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = -0.5f, rotationYDeg = 90f, r = wallR, g = wallG, b = wallB) }
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

    private fun drawModelAtNode(
        mesh: MeshData, nodeX: Float, nodeY: Float, nodeZ: Float,
        offsetX: Float = 0f, offsetY: Float = 0f, offsetZ: Float = 0f,
        rotationYDeg: Float = 0f,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        val verts = mesh.vertices
        val indices = mesh.indices
        val scale = mesh.scale
        val cx = mesh.center.x; val cy = mesh.center.y; val cz = mesh.center.z
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
