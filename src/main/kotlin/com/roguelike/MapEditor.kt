package com.roguelike

import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.input.InputSystem
import com.roguelike.rendering.Camera
import com.roguelike.rendering.DebugRenderer
import com.roguelike.serialization.WorldIO
import com.roguelike.ui.*
import com.roguelike.ui.SimpleUI
import com.roguelike.utils.AssetLoader
import com.roguelike.utils.MeshData
import com.roguelike.world.*
import org.lwjgl.glfw.GLFW.*
import java.io.File
import kotlin.math.*

private fun defaultTileFactory(type: String): com.roguelike.core.model.Tile? = when (type) {
    FloorTile.TYPE -> FloorTile()
    CeilingTile.TYPE -> CeilingTile()
    WallNorthTile.TYPE -> WallNorthTile()
    WallSouthTile.TYPE -> WallSouthTile()
    WallEastTile.TYPE -> WallEastTile()
    WallWestTile.TYPE -> WallWestTile()
    else -> null
}

/**
 * World map editor with orbital camera and File menu bar.
 *
 * Controls:
 *  A/D – rotate map around Z axis (azimuth) around world centre
 *  Shift+A/D – strafe orbit centre left/right
 *  W/S – move camera closer / further from map (dolly)
 *  Q/E – rotate around camera-local X axis (elevation / pitch)
 *  1-6 – select tool   Ctrl+S – save   ESC – menu
 *
 * A gimbal orientation cube is drawn in the top-right corner to
 * indicate the current projection.
 */
class MapEditor(
    private val inputSystem: InputSystem,
    private val camera: Camera,
    private val ui: SimpleUI
) {
    private var world: World? = null

    // Debug rendering for wireframe overlays on empty cubes, models, etc.
    private val debugRenderer = DebugRenderer(ui)

    // Asset loader for structure models
    private val assetLoader = AssetLoader()
    private var floorMesh: MeshData? = null
    private var ceilingMesh: MeshData? = null
    private var wallMesh: MeshData? = null

    // Menu bar & file management
    private val menuBar = MenuBar(ui)
    private val fileDialog = FileDialog(ui, inputSystem)
    private val recentFiles = RecentFiles()
    private var currentFilePath: String? = null  // path of loaded/saved file
    var exitRequested = false
        private set

    // --- Layout ---
    private val editorModesWidth = 40f       // left editor modes column width
    private var toolsPaletteWidth = 200f     // right tools palette pane width (draggable)
    private val toolsPaletteMinWidth = 120f
    private val toolsPaletteMaxWidth = 500f
    private val toolsPaletteHandleWidth = 6f // draggable splitter handle
    private var draggingPaletteHandle = false

    // --- Tool toggles ---
    private var showWireframes = true        // grid wireframe visibility
    private var showCeilings = true          // ceiling visibility toggle

    /** Editor modes selection group — only one can be active at a time. */
    private enum class EditorMode { NORMAL, GRID_TOGGLE, LIGHTS, GPU_RENDER }
    private var selectedEditorMode = EditorMode.NORMAL

    /**
     * When true, light sources project dynamic light with shadow volumes.
     * This depends ONLY on whether the LIGHTS editor mode is active,
     * not on the tools palette selection.
     */
    private var lightPreviewEnabled = false

    /** When true, uses GPU depth-buffered rasterization instead of CPU painter's algorithm. */
    private var gpuRenderingEnabled = false

    // Orbital camera parameters
    private var azimuth = 0f
    private var elevation = 60f
    private var distance = 20f
    private var orbitCenterX = 6f
    private var orbitCenterY = 6f
    private var orbitCenterZ = 0f
    private var currentZ = 0

    // Editor cursor (tile coordinates)
    private var cursorX = 0
    private var cursorY = 0

    // Current tool (selected from the tools palette)
    private var currentTool: EditorTool? = EditorTool.FLOOR
    private var lastFrameTime: Long = System.nanoTime()

    enum class EditorTool { FLOOR, CEILING, WALL, LIGHT }

    /** Tools palette tab selection. */
    private enum class PaletteTab { STRUCTURES, LIGHTS }
    private var selectedPaletteTab = PaletteTab.STRUCTURES

    /** Index of the currently selected light source in the world (for editing radius/intensity). */
    private var selectedLightIndex: Int = -1

    /** Whether we are currently dragging a light source. */
    private var draggingLight = false

    /** Default radius for newly placed lights. */
    private var defaultLightRadius = 5f
    /** Default intensity for newly placed lights. */
    private var defaultLightIntensity = 5f

    // --- Face/Edge highlighting ---

    /** Which face of a cube the mouse is hovering over. */
    private enum class HoveredFace { NONE, BOTTOM, TOP, EDGE_NORTH, EDGE_SOUTH, EDGE_EAST, EDGE_WEST }
    private var hoveredFace = HoveredFace.NONE
    private var hoveredNodeX = -1
    private var hoveredNodeY = -1

    fun show() {
        // Load structure model meshes
         try {
            floorMesh = assetLoader.loadModel("floor", "models/vox/floor/floor.obj")
            println("[MapEditor] Loaded floor model: ${floorMesh!!.vertices.size / 6} verts, ${floorMesh!!.indices.size} indices, scale=${floorMesh!!.scale}, center=${floorMesh!!.center}")
        } catch (e: Exception) { println("[MapEditor] Failed to load floor model: ${e.message}"); e.printStackTrace() }
        try {
            ceilingMesh = assetLoader.loadModel("ceiling", "models/vox/ceiling/ceiling.obj")
            println("[MapEditor] Loaded ceiling model: ${ceilingMesh!!.vertices.size / 6} verts, ${ceilingMesh!!.indices.size} indices, scale=${ceilingMesh!!.scale}, center=${ceilingMesh!!.center}")
        } catch (e: Exception) { println("[MapEditor] Failed to load ceiling model: ${e.message}"); e.printStackTrace() }
        try {
            wallMesh = assetLoader.loadModel("wall", "models/vox/wall/wall.obj")
            println("[MapEditor] Loaded wall model: ${wallMesh!!.vertices.size / 6} verts, ${wallMesh!!.indices.size} indices, scale=${wallMesh!!.scale}, center=${wallMesh!!.center}")
        } catch (e: Exception) { println("[MapEditor] Failed to load wall model: ${e.message}"); e.printStackTrace() }

        // Create or load a world
        val saveFile = File("saved-worlds/world.wld")
        if (saveFile.exists()) {
            loadWorldFromFile(saveFile)
        } else {
            newWorld()
        }
        setupMenuBar()
        lastFrameTime = System.nanoTime()
    }

    private fun newWorld() {
        world = World(12, 12, 3)
        currentFilePath = null
        resetCamera()
    }

    private fun loadWorldFromFile(file: File) {
        try {
            val loaded = WorldIO.loadWorld(
                file.path,
                { w, h, d -> World(w, h, d) },
                ::defaultTileFactory
            )
            if (loaded != null) {
                world = loaded
                currentFilePath = file.canonicalPath
                recentFiles.touch(file.canonicalPath)
                rebuildFileMenu()
                resetCamera()
                println("[MapEditor] Loaded: ${file.path}")
            } else {
                println("[MapEditor] loadWorld returned null")
                newWorld()
            }
        } catch (e: Exception) {
            println("[MapEditor] Failed to load: ${e.message}")
            newWorld()
        }
    }

    private fun saveWorldToFile(file: File) {
        val w = world ?: return
        try {
            file.parentFile?.mkdirs()
            WorldIO.saveWorld(file.path, w)
            currentFilePath = file.canonicalPath
            recentFiles.touch(file.canonicalPath)
            rebuildFileMenu()
            println("[MapEditor] Saved: ${file.path}")
        } catch (e: Exception) {
            println("[MapEditor] Save failed: ${e.message}")
        }
    }

    private fun resetCamera() {
        val w = world ?: return
        orbitCenterX = w.width / 2f
        orbitCenterY = w.height / 2f
        orbitCenterZ = 0f
        distance = max(w.width, w.height).toFloat() * 1.2f
        currentZ = 0
    }

    // ---- Menu Bar Setup ----

    private fun setupMenuBar() {
        rebuildFileMenu()
    }

    private fun rebuildFileMenu() {
        val items = mutableListOf<MenuBar.MenuItem>()
        items.add(MenuBar.MenuItem("New", "file.new"))
        items.add(MenuBar.MenuItem("Load...", "file.load"))
        items.add(MenuBar.MenuItem("Save", "file.save"))
        items.add(MenuBar.MenuItem("Save As...", "file.saveas"))
        items.add(MenuBar.MenuItem("", isDivider = true))

        val recent = recentFiles.list()
        if (recent.isNotEmpty()) {
            for ((idx, path) in recent.withIndex()) {
                val shortName = File(path).name
                items.add(MenuBar.MenuItem("${idx + 1}. $shortName", "file.recent.$idx"))
            }
            items.add(MenuBar.MenuItem("", isDivider = true))
        }

        items.add(MenuBar.MenuItem("Exit", "file.exit"))
        menuBar.updateMenu("File", items)
    }

    init {
        menuBar.addMenu("File", listOf(
            MenuBar.MenuItem("New", "file.new"),
            MenuBar.MenuItem("Exit", "file.exit")
        ))
    }

    // ---- Main Render Loop ----

    fun render(): Boolean {
        val w = world ?: return false

        val now = System.nanoTime()
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastFrameTime = now

        // Handle file dialog first (modal)
        if (fileDialog.isOpen) {
            fileDialog.render()
            return true
        }

        // Check menu state for input blocking (rendered later for correct Z-order)
        val menuActive = menuBar.isMouseOverBar(inputSystem)
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()

        // Compute layout regions
        val barH = menuBar.barHeight
        val sw = ui.screenWidth
        val editorModesRight = editorModesWidth
        val toolsPaletteLeft = sw - toolsPaletteWidth

        // Handle tools palette handle dragging
        val handleX = toolsPaletteLeft - toolsPaletteHandleWidth
        val overHandle = mx >= handleX && mx < toolsPaletteLeft && my > barH
        if (inputSystem.isMouseButtonJustPressed(0) && overHandle) {
            draggingPaletteHandle = true
        }
        if (draggingPaletteHandle) {
            if (inputSystem.isMouseButtonPressed(0)) {
                toolsPaletteWidth = (sw - mx).coerceIn(toolsPaletteMinWidth, toolsPaletteMaxWidth)
            } else {
                draggingPaletteHandle = false
            }
        }

        // Determine if mouse is over a UI panel (not the viewport)
        val overEditorModes = mx < editorModesRight && my > barH
        val overToolsPalette = mx >= toolsPaletteLeft - toolsPaletteHandleWidth && my > barH
        val uiBlocking = menuActive || overEditorModes || overToolsPalette || draggingPaletteHandle

        // Update lightPreviewEnabled based solely on the LIGHTS editor mode
        lightPreviewEnabled = selectedEditorMode == EditorMode.LIGHTS || selectedEditorMode == EditorMode.GPU_RENDER
        gpuRenderingEnabled = selectedEditorMode == EditorMode.GPU_RENDER

        val ctrlHeld = inputSystem.isKeyPressed(GLFW_KEY_LEFT_CONTROL) || inputSystem.isKeyPressed(GLFW_KEY_RIGHT_CONTROL)

        // --- Camera controls (only when mouse not over UI panels) ---
        if (!uiBlocking) {
            val rotSpeed = 90f * delta
            val zoomSpeed = 15f * delta
            val shiftHeld = inputSystem.isKeyPressed(GLFW_KEY_LEFT_SHIFT) || inputSystem.isKeyPressed(GLFW_KEY_RIGHT_SHIFT)

            if (shiftHeld) {
                val azRad = Math.toRadians(azimuth.toDouble()).toFloat()
                val panSpeed = 10f * delta
                val rightX = -sin(azRad)
                val rightY = cos(azRad)
                val fwdX = -cos(azRad)
                val fwdY = -sin(azRad)
                if (inputSystem.isKeyPressed(GLFW_KEY_A)) {
                    orbitCenterX -= rightX * panSpeed
                    orbitCenterY -= rightY * panSpeed
                }
                if (inputSystem.isKeyPressed(GLFW_KEY_D)) {
                    orbitCenterX += rightX * panSpeed
                    orbitCenterY += rightY * panSpeed
                }
                if (inputSystem.isKeyPressed(GLFW_KEY_W)) {
                    orbitCenterX += fwdX * panSpeed
                    orbitCenterY += fwdY * panSpeed
                }
                if (inputSystem.isKeyPressed(GLFW_KEY_S)) {
                    orbitCenterX -= fwdX * panSpeed
                    orbitCenterY -= fwdY * panSpeed
                }
            } else {
                if (inputSystem.isKeyPressed(GLFW_KEY_A)) azimuth -= rotSpeed
                if (inputSystem.isKeyPressed(GLFW_KEY_D)) azimuth += rotSpeed
            }

            if (!shiftHeld) {
                if (inputSystem.isKeyPressed(GLFW_KEY_Q)) distance = (distance - zoomSpeed).coerceAtLeast(3f)
                if (inputSystem.isKeyPressed(GLFW_KEY_E)) distance = (distance + zoomSpeed).coerceAtMost(200f)
                if (inputSystem.isKeyPressed(GLFW_KEY_W)) elevation = (elevation + rotSpeed).coerceAtMost(89f)
                if (inputSystem.isKeyPressed(GLFW_KEY_S)) elevation = (elevation - rotSpeed).coerceAtLeast(5f)
            }

            if (inputSystem.isKeyJustPressed(GLFW_KEY_Z)) currentZ = (currentZ - 1).coerceAtLeast(0)
            if (inputSystem.isKeyJustPressed(GLFW_KEY_X)) currentZ = (currentZ + 1).coerceAtMost(w.depth - 1)

            val scroll = inputSystem.getScrollDelta()
            if (scroll != 0f) {
                distance = (distance - scroll * 2f).coerceIn(3f, 200f)
            }

            // Tool selection via keyboard
            if (inputSystem.isKeyJustPressed(GLFW_KEY_1)) currentTool = EditorTool.FLOOR
            if (inputSystem.isKeyJustPressed(GLFW_KEY_2)) currentTool = EditorTool.CEILING
            if (inputSystem.isKeyJustPressed(GLFW_KEY_3)) currentTool = EditorTool.WALL
            if (inputSystem.isKeyJustPressed(GLFW_KEY_4)) currentTool = EditorTool.LIGHT

            // Cursor
            updateCursorFromMouse(w)

            // Update face/edge highlighting based on current tool
            updateHoveredFace(w)

            // Handle Ctrl+Click deletion
            if (ctrlHeld && inputSystem.isMouseButtonJustPressed(0)) {
                when (selectedPaletteTab) {
                    PaletteTab.STRUCTURES -> {
                        // Remove structure at cursor based on hovered face
                        if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                            val node = w.getNode(cursorX, cursorY, currentZ)
                            if (node != null) {
                                when (hoveredFace) {
                                    HoveredFace.BOTTOM -> node.removeTile(TileSlot.FLOOR)
                                    HoveredFace.TOP -> node.removeTile(TileSlot.CEILING)
                                    HoveredFace.EDGE_NORTH -> node.removeTile(TileSlot.WALL_NORTH)
                                    HoveredFace.EDGE_SOUTH -> node.removeTile(TileSlot.WALL_SOUTH)
                                    HoveredFace.EDGE_EAST -> node.removeTile(TileSlot.WALL_EAST)
                                    HoveredFace.EDGE_WEST -> node.removeTile(TileSlot.WALL_WEST)
                                    HoveredFace.NONE -> node.clear()
                                }
                            }
                        }
                    }
                    PaletteTab.LIGHTS -> {
                        // Remove selected/closest light
                        val clickedIdx = findLightAtMouse(w)
                        if (clickedIdx >= 0) {
                            w.lightSources.removeAt(clickedIdx)
                            if (selectedLightIndex == clickedIdx) selectedLightIndex = -1
                            else if (selectedLightIndex > clickedIdx) selectedLightIndex--
                        }
                    }
                }
            }

            // Place with left mouse (non-Ctrl)
            if (!ctrlHeld && inputSystem.isMouseButtonPressed(0)) {
                if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                    val node = w.getNode(cursorX, cursorY, currentZ)
                    if (node != null) {
                        when (currentTool) {
                            EditorTool.FLOOR -> node.setTile(FloorTile())
                            EditorTool.CEILING -> node.setTile(CeilingTile())
                            EditorTool.WALL -> {
                                // Place wall along the hovered edge
                                when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> node.setTile(WallNorthTile())
                                    HoveredFace.EDGE_SOUTH -> node.setTile(WallSouthTile())
                                    HoveredFace.EDGE_EAST -> node.setTile(WallEastTile())
                                    HoveredFace.EDGE_WEST -> node.setTile(WallWestTile())
                                    else -> {} // No edge hovered, do nothing
                                }
                            }
                            EditorTool.LIGHT -> {} // handled below
                            null -> {} // no tool selected
                        }
                    }
                }
            }

            // Light selection, dragging, and placement
            if (currentTool == EditorTool.LIGHT && !ctrlHeld && inputSystem.isMouseButtonJustPressed(0)) {
                // Try to select an existing light first (check proximity in screen space)
                val clickedLightIdx = findLightAtMouse(w)
                if (clickedLightIdx >= 0) {
                    selectedLightIndex = clickedLightIdx
                    draggingLight = true
                } else if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                    // Place new light
                    w.lightSources.add(com.roguelike.core.model.LightSource(
                        x = cursorX + 0.5f,
                        y = cursorY + 0.5f,
                        z = currentZ + 0.8f, // slightly above floor
                        intensity = defaultLightIntensity,
                        radius = defaultLightRadius,
                        colorHex = "ffcc88"
                    ))
                    // Select the newly placed light
                    selectedLightIndex = w.lightSources.size - 1
                }
            }

            // Drag selected light along XY plane
            if (draggingLight && selectedLightIndex in 0 until w.lightSources.size) {
                if (inputSystem.isMouseButtonPressed(0)) {
                    val hitPos = raycastXYPlane(w.lightSources[selectedLightIndex].z)
                    if (hitPos != null) {
                        w.lightSources[selectedLightIndex].x = hitPos.first
                        w.lightSources[selectedLightIndex].y = hitPos.second
                    }
                } else {
                    draggingLight = false
                }
            }

            // Deselect light if clicking in viewport with non-light tool
            if (currentTool != EditorTool.LIGHT && inputSystem.isMouseButtonJustPressed(0)) {
                selectedLightIndex = -1
            }
        }

        // Ctrl+S shortcut
        if (inputSystem.isKeyPressed(GLFW_KEY_LEFT_CONTROL) && inputSystem.isKeyJustPressed(GLFW_KEY_S)) {
            handleMenuAction("file.save")
        }

        // Update camera
        updateOrbitalCamera()

        // Render grid (main viewport)
        renderGrid(w)

        // Draw gimbal cube overlay
        drawGimbalCube()

        // Draw editor modes (left)
        renderEditorModes()

        // Draw tools palette (right)
        renderToolsPalette(w)

        // Render menu bar last so dropdowns appear on top of all content
        val menuAction = menuBar.render(inputSystem)
        if (menuAction != null) handleMenuAction(menuAction)

        return true
    }

    // ---- Menu Action Handler ----

    private fun handleMenuAction(action: String) {
        when (action) {
            "file.new" -> {
                newWorld()
            }
            "file.load" -> {
                fileDialog.open(FileDialog.Mode.OPEN, File("saved-worlds")) { file ->
                    if (file != null) loadWorldFromFile(file)
                }
            }
            "file.save" -> {
                val path = currentFilePath
                if (path != null) {
                    saveWorldToFile(File(path))
                } else {
                    handleMenuAction("file.saveas")
                }
            }
            "file.saveas" -> {
                fileDialog.open(FileDialog.Mode.SAVE, File("saved-worlds")) { file ->
                    if (file != null) saveWorldToFile(file)
                }
            }
            "file.exit" -> {
                exitRequested = true
            }
            else -> {
                if (action.startsWith("file.recent.")) {
                    val idx = action.removePrefix("file.recent.").toIntOrNull() ?: return
                    val recent = recentFiles.list()
                    if (idx in recent.indices) {
                        val file = File(recent[idx])
                        if (file.exists()) {
                            loadWorldFromFile(file)
                        } else {
                            println("[MapEditor] Recent file not found: ${file.path}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Compute camera position and direction from azimuth, elevation, distance.
     */
    private fun updateOrbitalCamera() {
        val azRad = Math.toRadians(azimuth.toDouble()).toFloat()
        val elRad = Math.toRadians(elevation.toDouble()).toFloat()

        val cosEl = cos(elRad)
        val camOffX = distance * cosEl * cos(azRad)
        val camOffY = distance * cosEl * sin(azRad)
        val camOffZ = distance * sin(elRad)

        camera.position.set(
            orbitCenterX + camOffX,
            orbitCenterY + camOffY,
            orbitCenterZ + camOffZ
        )
        camera.direction.set(
            orbitCenterX - camera.position.x,
            orbitCenterY - camera.position.y,
            orbitCenterZ - camera.position.z
        ).normalize()

        camera.up.set(0f, 0f, 1f)
        camera.update()
    }

    /**
     * Ray-cast from mouse into the Z=currentZ plane to get tile coords.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun updateCursorFromMouse(w: World) {
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        val nearWorld = camera.unproject(
            org.joml.Vector3f(mx, my, 0f), sw, sh
        )
        val farWorld = camera.unproject(
            org.joml.Vector3f(mx, my, 1f), sw, sh
        )
        val dir = org.joml.Vector3f(farWorld).sub(nearWorld)
        val planeZ = currentZ.toFloat()

        if (abs(dir.z) < 1e-6f) return

        val t = (planeZ - nearWorld.z) / dir.z
        if (t < 0) return

        val hitX = nearWorld.x + dir.x * t
        val hitY = nearWorld.y + dir.y * t
        cursorX = floor(hitX.toDouble()).toInt()
        cursorY = floor(hitY.toDouble()).toInt()
    }

    /**
     * Update which face/edge of the hovered node cube is being pointed at,
     * based on the current tool.
     */
    private fun updateHoveredFace(w: World) {
        hoveredFace = HoveredFace.NONE
        hoveredNodeX = cursorX
        hoveredNodeY = cursorY

        if (cursorX !in 0 until w.width || cursorY !in 0 until w.height) return

        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        val nearWorld = camera.unproject(org.joml.Vector3f(mx, my, 0f), sw, sh)
        val farWorld = camera.unproject(org.joml.Vector3f(mx, my, 1f), sw, sh)
        val dir = org.joml.Vector3f(farWorld).sub(nearWorld)

        val bx = cursorX.toFloat()
        val by = cursorY.toFloat()
        val bz = currentZ.toFloat()

        when (currentTool) {
            EditorTool.FLOOR -> {
                // Highlight bottom face (z = bz plane)
                if (abs(dir.z) > 1e-6f) {
                    val t = (bz - nearWorld.z) / dir.z
                    if (t > 0) {
                        val hx = nearWorld.x + dir.x * t
                        val hy = nearWorld.y + dir.y * t
                        if (hx >= bx && hx <= bx + 1f && hy >= by && hy <= by + 1f) {
                            hoveredFace = HoveredFace.BOTTOM
                        }
                    }
                }
            }
            EditorTool.CEILING -> {
                // Highlight top face (z = bz + 1 plane)
                if (abs(dir.z) > 1e-6f) {
                    val t = (bz + 1f - nearWorld.z) / dir.z
                    if (t > 0) {
                        val hx = nearWorld.x + dir.x * t
                        val hy = nearWorld.y + dir.y * t
                        if (hx >= bx && hx <= bx + 1f && hy >= by && hy <= by + 1f) {
                            hoveredFace = HoveredFace.TOP
                        }
                    }
                }
            }
            EditorTool.WALL -> {
                // Find closest edge of the cube the ray is near
                // Test all 4 face planes and find the closest hit
                data class FaceHit(val face: HoveredFace, val t: Float)
                val hits = mutableListOf<FaceHit>()

                // North face (y = by + 1)
                if (abs(dir.y) > 1e-6f) {
                    val t = (by + 1f - nearWorld.y) / dir.y
                    if (t > 0) {
                        val hx = nearWorld.x + dir.x * t
                        val hz = nearWorld.z + dir.z * t
                        if (hx >= bx && hx <= bx + 1f && hz >= bz && hz <= bz + 1f) {
                            hits.add(FaceHit(HoveredFace.EDGE_NORTH, t))
                        }
                    }
                }
                // South face (y = by)
                if (abs(dir.y) > 1e-6f) {
                    val t = (by - nearWorld.y) / dir.y
                    if (t > 0) {
                        val hx = nearWorld.x + dir.x * t
                        val hz = nearWorld.z + dir.z * t
                        if (hx >= bx && hx <= bx + 1f && hz >= bz && hz <= bz + 1f) {
                            hits.add(FaceHit(HoveredFace.EDGE_SOUTH, t))
                        }
                    }
                }
                // East face (x = bx + 1)
                if (abs(dir.x) > 1e-6f) {
                    val t = (bx + 1f - nearWorld.x) / dir.x
                    if (t > 0) {
                        val hy = nearWorld.y + dir.y * t
                        val hz = nearWorld.z + dir.z * t
                        if (hy >= by && hy <= by + 1f && hz >= bz && hz <= bz + 1f) {
                            hits.add(FaceHit(HoveredFace.EDGE_EAST, t))
                        }
                    }
                }
                // West face (x = bx)
                if (abs(dir.x) > 1e-6f) {
                    val t = (bx - nearWorld.x) / dir.x
                    if (t > 0) {
                        val hy = nearWorld.y + dir.y * t
                        val hz = nearWorld.z + dir.z * t
                        if (hy >= by && hy <= by + 1f && hz >= bz && hz <= bz + 1f) {
                            hits.add(FaceHit(HoveredFace.EDGE_WEST, t))
                        }
                    }
                }

                if (hits.isNotEmpty()) {
                    hoveredFace = hits.minByOrNull { it.t }!!.face
                }
            }
            else -> {
                hoveredFace = HoveredFace.NONE
            }
        }
    }

    /**
     * Ray-cast from mouse position to a given Z plane. Returns (x, y) hit or null.
     */
    private fun raycastXYPlane(planeZ: Float): Pair<Float, Float>? {
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        val nearWorld = camera.unproject(org.joml.Vector3f(mx, my, 0f), sw, sh)
        val farWorld = camera.unproject(org.joml.Vector3f(mx, my, 1f), sw, sh)
        val dir = org.joml.Vector3f(farWorld).sub(nearWorld)

        if (abs(dir.z) < 1e-6f) return null
        val t = (planeZ - nearWorld.z) / dir.z
        if (t < 0) return null

        return (nearWorld.x + dir.x * t) to (nearWorld.y + dir.y * t)
    }

    /**
     * Find the light source closest to the mouse click in screen space.
     * Returns the index or -1 if none is within pick radius.
     */
    private fun findLightAtMouse(w: World): Int {
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val pickRadius = 20f // pixels

        var bestIdx = -1
        var bestDist = pickRadius

        for ((idx, ls) in w.lightSources.withIndex()) {
            val screenPos = camera.project(org.joml.Vector3f(ls.x, ls.y, ls.z), sw, sh)
            val dx = screenPos.x - mx
            val dy = screenPos.y - my
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = idx
            }
        }
        return bestIdx
    }

    // ---- Rendering ----

    /** Project a 3D world point to screen pixels. */
    private fun proj(x: Float, y: Float, z: Float): org.joml.Vector3f {
        return camera.project(org.joml.Vector3f(x, y, z), ui.screenWidth, ui.screenHeight)
    }

    private data class Face(
        val c0: Triple<Float,Float,Float>,
        val c1: Triple<Float,Float,Float>,
        val c2: Triple<Float,Float,Float>,
        val c3: Triple<Float,Float,Float>,
        val nx: Float, val ny: Float, val nz: Float,
        val shade: Float
    )

    /**
     * Returns only the faces that correspond to actual structure types present in the node.
     * Floor → horizontal face at bottom facing up, Ceiling → horizontal face at top facing down,
     * Walls → corresponding side faces.
     */
    private fun buildNodeFaces(
        hasFloor: Boolean, hasCeiling: Boolean,
        hasWallN: Boolean, hasWallS: Boolean, hasWallE: Boolean, hasWallW: Boolean,
        allFaces: Array<Face>
    ): List<Face> {
        val result = mutableListOf<Face>()
        // Floor: vertices at z=0, normal pointing UP so visible from above
        if (hasFloor) result.add(Face(
            Triple(0f,0f,0f), Triple(1f,0f,0f), Triple(1f,1f,0f), Triple(0f,1f,0f),
            0f, 0f, 1f, 0.85f
        ))
        // Ceiling: vertices at z=1, normal pointing DOWN so visible from below
        if (hasCeiling) result.add(Face(
            Triple(0f,1f,1f), Triple(1f,1f,1f), Triple(1f,0f,1f), Triple(0f,0f,1f),
            0f, 0f, -1f, 0.75f
        ))
        // Walls use existing side faces
        // allFaces[2] = north (y+), [3] = south (y-), [4] = east (x+), [5] = west (x-)
        if (hasWallN) result.add(allFaces[2])
        if (hasWallS) result.add(allFaces[3])
        if (hasWallE) result.add(allFaces[4])
        if (hasWallW) result.add(allFaces[5])
        return result
    }

    /**
     * Render a MeshData model at a given world position using GPU triangles.
     * The model is scaled to fit within a 1x1x1 node and centered, then translated
     * to the node position with the given offsets.
     * @param rotationYDeg rotation around the Y axis in degrees (for wall orientation)
     */
    private var modelDrawLogCount = 0
    private var renderLogFrames = 0

    private fun drawModelAtNode(
        mesh: MeshData, nodeX: Float, nodeY: Float, nodeZ: Float,
        offsetX: Float = 0f, offsetY: Float = 0f, offsetZ: Float = 0f,
        rotationYDeg: Float = 0f,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        val verts = mesh.vertices   // 6 floats per vertex: px, py, pz, nx, ny, nz
        val indices = mesh.indices
        val scale = mesh.scale
        val cx = mesh.center.x
        val cy = mesh.center.y
        val cz = mesh.center.z

        if (modelDrawLogCount < 5) {
            modelDrawLogCount++
            println("[MapEditor] drawModelAtNode: node=($nodeX,$nodeY,$nodeZ) offset=($offsetX,$offsetY,$offsetZ) rot=$rotationYDeg scale=$scale center=($cx,$cy,$cz) verts=${verts.size/6} indices=${indices.size} triCount=${indices.size/3} color=($r,$g,$b,$a)")
            if (indices.isNotEmpty()) {
                val vi0 = (indices[0].toInt() and 0xFFFF) * 6
                println("[MapEditor]   first vertex raw: pos=(${verts[vi0]},${verts[vi0+1]},${verts[vi0+2]}) normal=(${verts[vi0+3]},${verts[vi0+4]},${verts[vi0+5]})")
            }
        }

        // Precompute rotation
        val radY = Math.toRadians(rotationYDeg.toDouble()).toFloat()
        val cosY = cos(radY)
        val sinY = sin(radY)

        // Transform vertex: center, scale, swap Y↔Z (model Y-up → world Z-up), rotate, translate
        fun xform(idx: Int): FloatArray {
            val vi = idx * 6
            // Center and scale
            val mx = (verts[vi] - cx) * scale
            val my = (verts[vi + 1] - cy) * scale
            val mz = (verts[vi + 2] - cz) * scale
            val mnx = verts[vi + 3]
            val mny = verts[vi + 4]
            val mnz = verts[vi + 5]
            // Swap Y↔Z: model Y-up → world Z-up
            var px = mx
            var py = mz
            var pz = my
            var nx = mnx
            var ny = mnz
            var nz = mny
            // Rotate around world Z axis (vertical)
            if (rotationYDeg != 0f) {
                val rpx = px * cosY - py * sinY
                val rpy = px * sinY + py * cosY
                px = rpx; py = rpy
                val rnx = nx * cosY - ny * sinY
                val rny = nx * sinY + ny * cosY
                nx = rnx; ny = rny
            }
            // Translate to node center + offset
            px += nodeX + 0.5f + offsetX
            py += nodeY + 0.5f + offsetY
            pz += nodeZ + 0.5f + offsetZ
            return floatArrayOf(px, py, pz, nx, ny, nz)
        }

        // Submit triangles (original winding, normals handle lighting direction)
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

    /**
     * Render the world as actual 3D cubes.
     */
    private fun renderGrid(w: World) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        val faces = arrayOf(
            Face(Triple(0f,0f,1f), Triple(1f,0f,1f), Triple(1f,1f,1f), Triple(0f,1f,1f), 0f,0f,1f, 1.0f),
            Face(Triple(0f,1f,0f), Triple(1f,1f,0f), Triple(1f,0f,0f), Triple(0f,0f,0f), 0f,0f,-1f, 0.35f),
            Face(Triple(0f,1f,0f), Triple(0f,1f,1f), Triple(1f,1f,1f), Triple(1f,1f,0f), 0f,1f,0f, 0.7f),
            Face(Triple(1f,0f,0f), Triple(1f,0f,1f), Triple(0f,0f,1f), Triple(0f,0f,0f), 0f,-1f,0f, 0.55f),
            Face(Triple(1f,0f,0f), Triple(1f,1f,0f), Triple(1f,1f,1f), Triple(1f,0f,1f), 1f,0f,0f, 0.6f),
            Face(Triple(0f,1f,0f), Triple(0f,0f,0f), Triple(0f,0f,1f), Triple(0f,1f,1f), -1f,0f,0f, 0.5f)
        )

        val floorR = 0.25f; val floorG = 0.30f; val floorB = 0.40f
        val wallR  = 0.55f; val wallG  = 0.42f; val wallB  = 0.30f

        val camPos = camera.position

        // Upload lighting data to GPU when light preview is enabled
        if (lightPreviewEnabled && w.lightSources.isNotEmpty()) {
            val gridW = w.width; val gridH = w.height; val gridD = w.depth
            val occupancy = IntArray(gridW * gridH * gridD)
                for (z in 0 until gridD) {
                    for (y in 0 until gridH) {
                        for (x in 0 until gridW) {
                            val node = w.getNode(x, y, z) ?: continue
                            // Encode wall edges as bit flags:
                            // bit 0 = north (Y+), bit 1 = south (Y-), bit 2 = east (X+), bit 3 = west (X-)
                            var flags = 0
                            if (node.hasTile(TileSlot.WALL_NORTH)) flags = flags or 1
                            if (node.hasTile(TileSlot.WALL_SOUTH)) flags = flags or 2
                            if (node.hasTile(TileSlot.WALL_EAST))  flags = flags or 4
                            if (node.hasTile(TileSlot.WALL_WEST))  flags = flags or 8
                            occupancy[z * gridW * gridH + y * gridW + x] = flags
                        }
                    }
                }
            val lights = w.lightSources.map { ls ->
                SimpleUI.LightData(ls.x, ls.y, ls.z, ls.intensity, ls.colorR(), ls.colorG(), ls.colorB(), ls.radius)
            }
            ui.updateLighting(lights, occupancy, gridW, gridH, gridD)
        } else {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1)
        }

        // Always set up VP matrix for GPU model rendering
        val vpFloats = FloatArray(16)
        camera.viewProjection.get(vpFloats)
        ui.setViewProjection(vpFloats)

        // Render structure models via GPU triangles (all modes)
        modelDrawLogCount = 0
        var modelNodeCount = 0
        for (z in 0..currentZ) {
            for (x in 0 until w.width) {
                for (y in 0 until w.height) {
                    val node = w.getNode(x, y, z) ?: continue
                    val hasFloor = node.hasTile(TileSlot.FLOOR)
                    val hasCeiling = showCeilings && node.hasTile(TileSlot.CEILING)
                    val hasWallN = node.hasTile(TileSlot.WALL_NORTH)
                    val hasWallS = node.hasTile(TileSlot.WALL_SOUTH)
                    val hasWallE = node.hasTile(TileSlot.WALL_EAST)
                    val hasWallW = node.hasTile(TileSlot.WALL_WEST)
                    if (!(hasFloor || hasCeiling || hasWallN || hasWallS || hasWallE || hasWallW)) continue
                    modelNodeCount++

                    val tbx = x.toFloat()
                    val tby = y.toFloat()
                    val tbz = z.toFloat()

                    if (hasFloor) {
                        floorMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetZ = -0.5f, r = floorR, g = floorG, b = floorB) }
                    }
                    if (hasCeiling) {
                        ceilingMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetZ = 0.5f, r = floorR, g = floorG, b = floorB) }
                    }
                    if (hasWallN) {
                        wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = 0.5f, rotationYDeg = 0f, r = wallR, g = wallG, b = wallB) }
                    }
                    if (hasWallS) {
                        wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = -0.5f, rotationYDeg = 0f, r = wallR, g = wallG, b = wallB) }
                    }
                    if (hasWallE) {
                        wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = 0.5f, rotationYDeg = 90f, r = wallR, g = wallG, b = wallB) }
                    }
                    if (hasWallW) {
                        wallMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = -0.5f, rotationYDeg = 90f, r = wallR, g = wallG, b = wallB) }
                    }
                }
            }
        }

        if (renderLogFrames < 3) {
            renderLogFrames++
            println("[MapEditor] renderGrid: modelNodeCount=$modelNodeCount floorMesh=${floorMesh != null} ceilingMesh=${ceilingMesh != null} wallMesh=${wallMesh != null} gpuRenderingEnabled=$gpuRenderingEnabled")
        }
        // GPU rendering path: models already rendered above, just add overlays
        if (gpuRenderingEnabled) {            // Draw face/edge highlight
            drawFaceEdgeHighlight(w)

            // Draw cursor highlight (still CPU-projected wireframe on top)
            if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                debugRenderer.drawWireframeCube(
                    cursorX.toFloat(), cursorY.toFloat(), currentZ.toFloat(), 1f, camera,
                    1f, 1f, 0f, 0.9f, 2f
                )
            }

            // Draw light sources
            for ((idx, ls) in w.lightSources.withIndex()) {
                val lr = ls.colorR(); val lg = ls.colorG(); val lb = ls.colorB()
                val alpha = if (idx == selectedLightIndex) 0.6f else 0.4f
                debugRenderer.drawFilledSphere(ls.x, ls.y, ls.z, 0.15f, camera, lr, lg, lb, alpha)
                debugRenderer.drawWireframeSphere(ls.x, ls.y, ls.z, 0.15f, camera, lr, lg, lb, 0.8f, 12, 1.5f)
                if (lightPreviewEnabled) {
                    debugRenderer.drawWireframeSphere(ls.x, ls.y, ls.z, ls.radius, camera,
                        lr * 0.5f, lg * 0.5f, lb * 0.5f, 0.15f, 24, 1f)
                }
            }

            // HUD
            val hudX = editorModesWidth + 6f
            val hudY = menuBar.barHeight + 4f
            val viewportRight = sw - toolsPaletteWidth - 10f
            val toolName = currentTool?.name ?: "NONE"
            ui.drawText("Tool: $toolName  Layer: $currentZ  [GPU]", hudX, hudY, 0.7f, 0.7f, 0.8f, 1f, 1f)
            val helpStr = "A/D: Rotate  W/S: Pitch  Q/E: Zoom  Shift: Pan  Z/X: Layer  Ctrl+Click: Erase  Ctrl+S: Save"
            ui.drawText(helpStr, hudX, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)
            val fileLabel = if (currentFilePath != null) File(currentFilePath!!).name else "[unsaved]"
            val azStr = "Az: ${azimuth.toInt()}  El: ${elevation.toInt()}  Dist: ${distance.toInt()}"
            val coordStr = "$fileLabel  Cursor: $cursorX, $cursorY  ${w.width}x${w.height}x${w.depth}  $azStr"
            ui.drawText(coordStr, viewportRight - ui.textWidth(coordStr), hudY, 0.6f, 0.6f, 0.7f, 0.8f, 1f)
            return
        }

        // CPU rendering path (painter's algorithm)

        data class TileEntry(val x: Int, val y: Int, val z: Int, val dist: Float)
        val tiles = ArrayList<TileEntry>(w.width * w.height * (currentZ + 1))
        for (z in 0..currentZ) {
            val bz = z.toFloat()
            for (x in 0 until w.width) {
                for (y in 0 until w.height) {
                    val cx = x + 0.5f
                    val cy = y + 0.5f
                    val cz = bz + 0.5f
                    val dx = cx - camPos.x
                    val dy = cy - camPos.y
                    val dz = cz - camPos.z
                    tiles.add(TileEntry(x, y, z, dx * dx + dy * dy + dz * dz))
                }
            }
        }
        tiles.sortByDescending { it.dist }

        for (tile in tiles) {
            val node = w.getNode(tile.x, tile.y, tile.z) ?: continue
            val hasFloor = node.hasTile(TileSlot.FLOOR)
            val hasCeiling = showCeilings && node.hasTile(TileSlot.CEILING)
            val hasWallN = node.hasTile(TileSlot.WALL_NORTH)
            val hasWallS = node.hasTile(TileSlot.WALL_SOUTH)
            val hasWallE = node.hasTile(TileSlot.WALL_EAST)
            val hasWallW = node.hasTile(TileSlot.WALL_WEST)
            val hasAnyContent = hasFloor || hasCeiling || hasWallN || hasWallS || hasWallE || hasWallW

            val tbx = tile.x.toFloat()
            val tby = tile.y.toFloat()
            val tbz = tile.z.toFloat()
            val isActiveLayer = tile.z == currentZ
            // No artificial darkening — all Z levels render the same
            val layerDim = 1f

            if (!hasAnyContent) {
                if (showWireframes) {
                    val wireAlpha = if (isActiveLayer) 0.25f else 0.12f
                    debugRenderer.drawWireframeCube(tbx, tby, tbz, 1f, camera,
                        0.2f * layerDim, 0.25f * layerDim, 0.3f * layerDim, wireAlpha)
                }
                continue
            }

            // Draw wireframe edges on top of model faces
            if (lightPreviewEnabled && w.lightSources.isNotEmpty()) {
                debugRenderer.drawLitWireframeCube(
                    tbx, tby, tbz, 1f, camera,
                    0.15f * layerDim, 0.18f * layerDim, 0.22f * layerDim, 0.7f, 1.5f
                )
            } else {
                debugRenderer.drawWireframeCube(
                    tbx, tby, tbz, 1f, camera,
                    0.15f * layerDim, 0.18f * layerDim, 0.22f * layerDim, 0.7f
                )
            }
        }

        // Draw face/edge highlight
        drawFaceEdgeHighlight(w)

        // Draw cursor highlight
        if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
            debugRenderer.drawWireframeCube(
                cursorX.toFloat(), cursorY.toFloat(), currentZ.toFloat(), 1f, camera,
                1f, 1f, 0f, 0.9f, 2f
            )
        }

        // Draw light sources
        for ((idx, ls) in w.lightSources.withIndex()) {
            val lr = ls.colorR(); val lg = ls.colorG(); val lb = ls.colorB()
            // Highlight selected light
            val alpha = if (idx == selectedLightIndex) 0.6f else 0.4f
            debugRenderer.drawFilledSphere(ls.x, ls.y, ls.z, 0.15f, camera, lr, lg, lb, alpha)
            debugRenderer.drawWireframeSphere(ls.x, ls.y, ls.z, 0.15f, camera, lr, lg, lb, 0.8f, 12, 1.5f)
            // Light radius indicator (only when light preview enabled)
            if (lightPreviewEnabled) {
                debugRenderer.drawWireframeSphere(ls.x, ls.y, ls.z, ls.radius, camera,
                    lr * 0.5f, lg * 0.5f, lb * 0.5f, 0.15f, 24, 1f)
            }
        }

        // HUD (in the viewport area, between editor modes and tools palette)
        val hudX = editorModesWidth + 6f
        val hudY = menuBar.barHeight + 4f
        val viewportRight = sw - toolsPaletteWidth - 10f
        val toolName = currentTool?.name ?: "NONE"
        ui.drawText("Tool: $toolName  Layer: $currentZ", hudX, hudY, 0.7f, 0.7f, 0.8f, 1f, 1f)
        val helpStr = "A/D: Rotate  W/S: Pitch  Q/E: Zoom  Shift: Pan  Z/X: Layer  Ctrl+Click: Erase  Ctrl+S: Save"
        ui.drawText(helpStr, hudX, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)
        val fileLabel = if (currentFilePath != null) File(currentFilePath!!).name else "[unsaved]"
        val azStr = "Az: ${azimuth.toInt()}  El: ${elevation.toInt()}  Dist: ${distance.toInt()}"
        val coordStr = "$fileLabel  Cursor: $cursorX, $cursorY  ${w.width}x${w.height}x${w.depth}  $azStr"
        ui.drawText(coordStr, viewportRight - ui.textWidth(coordStr), hudY, 0.6f, 0.6f, 0.7f, 0.8f, 1f)
    }

    /**
     * Draw highlight for the hovered face or edge based on the current tool.
     */
    private fun drawFaceEdgeHighlight(w: World) {
        if (hoveredFace == HoveredFace.NONE) return
        if (cursorX !in 0 until w.width || cursorY !in 0 until w.height) return

        val bx = cursorX.toFloat()
        val by = cursorY.toFloat()
        val bz = currentZ.toFloat()
        val highlightR = 0.2f; val highlightG = 0.8f; val highlightB = 1.0f; val highlightA = 0.4f

        when (hoveredFace) {
            HoveredFace.BOTTOM -> {
                // Highlight bottom face (z = bz)
                val p0 = proj(bx, by, bz)
                val p1 = proj(bx + 1f, by, bz)
                val p2 = proj(bx + 1f, by + 1f, bz)
                val p3 = proj(bx, by + 1f, bz)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                // Draw border lines
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2f)
            }
            HoveredFace.TOP -> {
                // Highlight top face (z = bz + 1)
                val p0 = proj(bx, by, bz + 1f)
                val p1 = proj(bx + 1f, by, bz + 1f)
                val p2 = proj(bx + 1f, by + 1f, bz + 1f)
                val p3 = proj(bx, by + 1f, bz + 1f)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2f)
            }
            HoveredFace.EDGE_NORTH -> {
                // Highlight north face (y = by + 1)
                val p0 = proj(bx, by + 1f, bz)
                val p1 = proj(bx + 1f, by + 1f, bz)
                val p2 = proj(bx + 1f, by + 1f, bz + 1f)
                val p3 = proj(bx, by + 1f, bz + 1f)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
            }
            HoveredFace.EDGE_SOUTH -> {
                val p0 = proj(bx, by, bz)
                val p1 = proj(bx + 1f, by, bz)
                val p2 = proj(bx + 1f, by, bz + 1f)
                val p3 = proj(bx, by, bz + 1f)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
            }
            HoveredFace.EDGE_EAST -> {
                val p0 = proj(bx + 1f, by, bz)
                val p1 = proj(bx + 1f, by + 1f, bz)
                val p2 = proj(bx + 1f, by + 1f, bz + 1f)
                val p3 = proj(bx + 1f, by, bz + 1f)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
            }
            HoveredFace.EDGE_WEST -> {
                val p0 = proj(bx, by, bz)
                val p1 = proj(bx, by + 1f, bz)
                val p2 = proj(bx, by + 1f, bz + 1f)
                val p3 = proj(bx, by, bz + 1f)
                ui.drawQuad(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
                    highlightR, highlightG, highlightB, highlightA)
                debugRenderer.drawLine(p0.x, p0.y, p1.x, p1.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p1.x, p1.y, p2.x, p2.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p2.x, p2.y, p3.x, p3.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
                debugRenderer.drawLine(p3.x, p3.y, p0.x, p0.y, highlightR, highlightG, highlightB, 0.8f, 2.5f)
            }
            HoveredFace.NONE -> {}
        }
    }



    // ---- Editor Modes (left side) ----

    private fun renderEditorModes() {
        val barH = menuBar.barHeight
        val sh = ui.screenHeight
        val colW = editorModesWidth
        val btnSize = 32f
        val btnPad = 4f
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()

        // Background
        ui.drawRect(0f, barH, colW, sh - barH, 0.12f, 0.13f, 0.17f, 0.95f)
        // Right border
        ui.drawRect(colW - 1f, barH, 1f, sh - barH, 0.3f, 0.35f, 0.45f, 0.7f)

        var by = barH + btnPad

        // --- Normal / pointer mode (cursor-default-outline icon) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.NORMAL, mx, my,
            iconDrawer = { x, y, s -> drawCursorIcon(x, y, s) }
        ) {
            selectedEditorMode = EditorMode.NORMAL
        }
        by += btnSize + btnPad

        // --- Show/hide grid wireframes (view-grid-outline icon) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.GRID_TOGGLE, mx, my,
            iconDrawer = { x, y, s -> drawGridIcon(x, y, s, showWireframes) }
        ) {
            selectedEditorMode = EditorMode.GRID_TOGGLE
            showWireframes = !showWireframes
        }
        by += btnSize + btnPad

        // --- Lights mode (lightbulb-on-outline icon) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.LIGHTS, mx, my,
            iconDrawer = { x, y, s -> drawLightbulbIcon(x, y, s, selectedEditorMode == EditorMode.LIGHTS) }
        ) {
            selectedEditorMode = if (selectedEditorMode == EditorMode.LIGHTS) EditorMode.NORMAL else EditorMode.LIGHTS
        }
        by += btnSize + btnPad

        // --- GPU render mode (cube icon with "3D" label) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.GPU_RENDER, mx, my,
            iconDrawer = { x, y, s -> drawGpuIcon(x, y, s) }
        ) {
            selectedEditorMode = if (selectedEditorMode == EditorMode.GPU_RENDER) EditorMode.NORMAL else EditorMode.GPU_RENDER
        }
        by += btnSize + btnPad

        // --- Show/hide ceilings toggle ---
        drawModeButton(btnPad, by, btnSize, showCeilings, mx, my,
            iconDrawer = { x, y, s -> drawCeilingIcon(x, y, s, showCeilings) }
        ) {
            showCeilings = !showCeilings
        }
        by += btnSize + btnPad
    }

    /**
     * Draw a square toggle button in the editor modes column with a procedural icon.
     */
    private inline fun drawModeButton(
        x: Float, y: Float, size: Float,
        active: Boolean,
        mx: Float, my: Float,
        iconDrawer: (Float, Float, Float) -> Unit,
        onClick: () -> Unit
    ) {
        val hovered = mx >= x && mx < x + size && my >= y && my < y + size

        if (active) {
            ui.drawRect(x, y, size, size, 0.3f, 0.45f, 0.7f, 0.9f)
        } else if (hovered) {
            ui.drawRect(x, y, size, size, 0.22f, 0.28f, 0.4f, 0.8f)
        } else {
            ui.drawRect(x, y, size, size, 0.16f, 0.18f, 0.24f, 0.85f)
        }

        val bc = if (active) 0.6f else 0.3f
        ui.drawRect(x, y, size, 1f, bc, bc + 0.1f, bc + 0.2f)
        ui.drawRect(x, y + size - 1f, size, 1f, bc, bc + 0.1f, bc + 0.2f)
        ui.drawRect(x, y, 1f, size, bc, bc + 0.1f, bc + 0.2f)
        ui.drawRect(x + size - 1f, y, 1f, size, bc, bc + 0.1f, bc + 0.2f)

        iconDrawer(x + 4f, y + 4f, size - 8f)

        if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
            onClick()
        }
    }

    // ---- Procedural Icons ----

    private fun drawCursorIcon(x: Float, y: Float, s: Float) {
        val col = 0.85f
        val ax = x + s * 0.15f; val ay = y + s * 0.1f
        val bx = x + s * 0.15f; val by2 = y + s * 0.85f
        val cx = x + s * 0.65f; val cy = y + s * 0.6f

        ui.drawQuad(ax, ay, bx, by2, cx, cy, ax, ay, col, col, col + 0.1f, 0.9f)

        val stemX = x + s * 0.35f
        val stemY = y + s * 0.6f
        debugRenderer.drawLine(stemX, stemY, x + s * 0.7f, y + s * 0.9f, col, col, col + 0.1f, 0.9f, 2f)
    }

    private fun drawGridIcon(x: Float, y: Float, s: Float, enabled: Boolean) {
        val col = if (enabled) 0.85f else 0.45f
        val a = if (enabled) 0.9f else 0.5f
        val t = 1.5f

        debugRenderer.drawLine(x, y, x + s, y, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x, y + s, x + s, y + s, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x, y, x, y + s, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x + s, y, x + s, y + s, col, col, col + 0.1f, a, t)

        val third = s / 3f
        debugRenderer.drawLine(x, y + third, x + s, y + third, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x, y + third * 2, x + s, y + third * 2, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x + third, y, x + third, y + s, col, col, col + 0.1f, a, t)
        debugRenderer.drawLine(x + third * 2, y, x + third * 2, y + s, col, col, col + 0.1f, a, t)

        if (!enabled) {
            debugRenderer.drawLine(x, y, x + s, y + s, 0.8f, 0.3f, 0.3f, 0.7f, 2f)
        }
    }

    private fun drawLightbulbIcon(x: Float, y: Float, s: Float, enabled: Boolean) {
        val col = if (enabled) 1f else 0.45f
        val a = if (enabled) 0.9f else 0.5f
        val cx = x + s / 2f
        val cy = y + s * 0.35f
        val r = s * 0.28f

        val segments = 10
        val step = (Math.PI / segments).toFloat()
        for (i in 0 until segments) {
            val a0 = Math.PI.toFloat() + i * step
            val a1 = Math.PI.toFloat() + (i + 1) * step
            val x0 = cx + cos(a0) * r; val y0 = cy + sin(a0) * r
            val x1 = cx + cos(a1) * r; val y1 = cy + sin(a1) * r
            debugRenderer.drawLine(x0, y0, x1, y1, col, col * 0.85f, col * 0.3f, a, 1.5f)
        }
        debugRenderer.drawLine(cx - r * 0.6f, cy + r, cx - r * 0.6f, cy + r + s * 0.15f, col, col * 0.85f, col * 0.3f, a, 1.5f)
        debugRenderer.drawLine(cx + r * 0.6f, cy + r, cx + r * 0.6f, cy + r + s * 0.15f, col, col * 0.85f, col * 0.3f, a, 1.5f)
        val baseY = cy + r + s * 0.15f
        debugRenderer.drawLine(cx - r * 0.6f, baseY, cx + r * 0.6f, baseY, col, col * 0.85f, col * 0.3f, a, 1.5f)
        debugRenderer.drawLine(cx - r * 0.4f, baseY + 3f, cx + r * 0.4f, baseY + 3f, col, col * 0.85f, col * 0.3f, a, 1.5f)

        if (enabled) {
            val rayLen = s * 0.12f
            for (i in 0 until 5) {
                val angle = (-Math.PI.toFloat() * 0.8f + i * Math.PI.toFloat() * 0.4f)
                val rx = cx + cos(angle) * (r + 2f); val ry = cy + sin(angle) * (r + 2f)
                val rx2 = cx + cos(angle) * (r + 2f + rayLen); val ry2 = cy + sin(angle) * (r + 2f + rayLen)
                debugRenderer.drawLine(rx, ry, rx2, ry2, col, col * 0.9f, col * 0.4f, a * 0.7f, 1f)
            }
        }
    }

    /** Draw a simple "3D" cube icon for GPU render mode. */
    private fun drawGpuIcon(x: Float, y: Float, s: Float) {
        val col = if (gpuRenderingEnabled) 0.9f else 0.5f
        val a = if (gpuRenderingEnabled) 0.9f else 0.5f
        // Draw a small 3D cube outline
        val cx = x + s * 0.5f
        val cy = y + s * 0.5f
        val hs = s * 0.3f
        val ox = hs * 0.4f; val oy = hs * 0.3f
        // Front face
        debugRenderer.drawLine(cx - hs, cy - hs, cx + hs, cy - hs, col, col * 0.7f, col * 0.3f, a, 1.5f)
        debugRenderer.drawLine(cx + hs, cy - hs, cx + hs, cy + hs, col, col * 0.7f, col * 0.3f, a, 1.5f)
        debugRenderer.drawLine(cx + hs, cy + hs, cx - hs, cy + hs, col, col * 0.7f, col * 0.3f, a, 1.5f)
        debugRenderer.drawLine(cx - hs, cy + hs, cx - hs, cy - hs, col, col * 0.7f, col * 0.3f, a, 1.5f)
        // Back face (offset)
        debugRenderer.drawLine(cx - hs + ox, cy - hs - oy, cx + hs + ox, cy - hs - oy, col * 0.6f, col * 0.5f, col * 0.2f, a * 0.7f, 1f)
        debugRenderer.drawLine(cx + hs + ox, cy - hs - oy, cx + hs + ox, cy + hs - oy, col * 0.6f, col * 0.5f, col * 0.2f, a * 0.7f, 1f)
        // Connecting edges
        debugRenderer.drawLine(cx + hs, cy - hs, cx + hs + ox, cy - hs - oy, col * 0.6f, col * 0.5f, col * 0.2f, a * 0.7f, 1f)
        debugRenderer.drawLine(cx + hs, cy + hs, cx + hs + ox, cy + hs - oy, col * 0.6f, col * 0.5f, col * 0.2f, a * 0.7f, 1f)
        debugRenderer.drawLine(cx - hs, cy - hs, cx - hs + ox, cy - hs - oy, col * 0.6f, col * 0.5f, col * 0.2f, a * 0.7f, 1f)
    }

    /** Draw a ceiling toggle icon (horizontal line with downward-facing surface). */
    private fun drawCeilingIcon(x: Float, y: Float, s: Float, enabled: Boolean) {
        val col = if (enabled) 0.85f else 0.45f
        val a = if (enabled) 0.9f else 0.5f

        // Draw a flat rectangle representing a ceiling panel
        val pad = s * 0.15f
        debugRenderer.drawLine(x + pad, y + pad, x + s - pad, y + pad, col, col * 0.8f, col * 0.5f, a, 2f)
        debugRenderer.drawLine(x + s - pad, y + pad, x + s - pad, y + s * 0.45f, col, col * 0.8f, col * 0.5f, a, 2f)
        debugRenderer.drawLine(x + s - pad, y + s * 0.45f, x + pad, y + s * 0.45f, col, col * 0.8f, col * 0.5f, a, 2f)
        debugRenderer.drawLine(x + pad, y + s * 0.45f, x + pad, y + pad, col, col * 0.8f, col * 0.5f, a, 2f)

        // Hatching lines inside to suggest a surface
        debugRenderer.drawLine(x + pad + 2f, y + s * 0.45f, x + pad + s * 0.2f, y + pad, col * 0.6f, col * 0.5f, col * 0.3f, a * 0.6f, 1f)
        debugRenderer.drawLine(x + pad + s * 0.3f, y + s * 0.45f, x + pad + s * 0.5f, y + pad, col * 0.6f, col * 0.5f, col * 0.3f, a * 0.6f, 1f)

        // "C" label below
        ui.drawText("C", x + s * 0.35f, y + s * 0.55f, col, col * 0.8f, col * 0.5f, a, 0.9f)

        if (!enabled) {
            debugRenderer.drawLine(x, y, x + s, y + s, 0.8f, 0.3f, 0.3f, 0.7f, 2f)
        }
    }

    // ---- Tools Palette (right side) ----

    private fun renderToolsPalette(w: World) {
        val barH = menuBar.barHeight
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val palX = sw - toolsPaletteWidth

        // Background
        ui.drawRect(palX, barH, toolsPaletteWidth, sh - barH, 0.12f, 0.13f, 0.17f, 0.95f)
        // Left border
        ui.drawRect(palX, barH, 1f, sh - barH, 0.3f, 0.35f, 0.45f, 0.7f)

        // Draggable handle
        val handleX = palX - toolsPaletteHandleWidth
        val overHandle = inputSystem.getMouseX() >= handleX && inputSystem.getMouseX() < palX && inputSystem.getMouseY() > barH
        val handleColor = if (draggingPaletteHandle || overHandle) 0.5f else 0.25f
        ui.drawRect(handleX, barH, toolsPaletteHandleWidth, sh - barH, handleColor, handleColor + 0.05f, handleColor + 0.15f, 0.8f)

        // Tools palette header
        ui.drawText("TOOLS PALETTE", palX + 8f, barH + 8f, 0.7f, 0.75f, 0.85f, 1f, 1.3f)

        val btnW = toolsPaletteWidth - 16f
        val btnH = 24f
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()

        // --- Tab bar ---
        var tabY = barH + 32f
        val tabW = (toolsPaletteWidth - 16f) / 2f
        val tabH = 24f

        // Structures tab
        val structActive = selectedPaletteTab == PaletteTab.STRUCTURES
        val structHovered = mx >= palX + 8f && mx < palX + 8f + tabW && my >= tabY && my < tabY + tabH
        if (structActive) {
            ui.drawRect(palX + 8f, tabY, tabW, tabH, 0.25f, 0.35f, 0.55f, 0.9f)
        } else if (structHovered) {
            ui.drawRect(palX + 8f, tabY, tabW, tabH, 0.2f, 0.26f, 0.38f, 0.7f)
        } else {
            ui.drawRect(palX + 8f, tabY, tabW, tabH, 0.15f, 0.17f, 0.22f, 0.6f)
        }
        ui.drawText("Structures", palX + 12f, tabY + 5f, 0.82f, 0.82f, 0.9f, 1f, 1f)
        if (structHovered && inputSystem.isMouseButtonJustPressed(0)) {
            selectedPaletteTab = PaletteTab.STRUCTURES
            currentTool = EditorTool.FLOOR
        }

        // Lights tab
        val lightsActive = selectedPaletteTab == PaletteTab.LIGHTS
        val lightsHovered = mx >= palX + 8f + tabW && mx < palX + 8f + tabW * 2f && my >= tabY && my < tabY + tabH
        if (lightsActive) {
            ui.drawRect(palX + 8f + tabW, tabY, tabW, tabH, 0.25f, 0.35f, 0.55f, 0.9f)
        } else if (lightsHovered) {
            ui.drawRect(palX + 8f + tabW, tabY, tabW, tabH, 0.2f, 0.26f, 0.38f, 0.7f)
        } else {
            ui.drawRect(palX + 8f + tabW, tabY, tabW, tabH, 0.15f, 0.17f, 0.22f, 0.6f)
        }
        ui.drawText("Lights", palX + 12f + tabW, tabY + 5f, 0.82f, 0.82f, 0.9f, 1f, 1f)
        if (lightsHovered && inputSystem.isMouseButtonJustPressed(0)) {
            selectedPaletteTab = PaletteTab.LIGHTS
            currentTool = EditorTool.LIGHT
        }

        // Tab underline
        ui.drawRect(palX + 8f, tabY + tabH, toolsPaletteWidth - 16f, 1f, 0.3f, 0.35f, 0.45f, 0.7f)

        var by = tabY + tabH + 8f

        when (selectedPaletteTab) {
            PaletteTab.STRUCTURES -> {
                // --- Floor section ---
                ui.drawText("Floor", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Floor model button (models\vox\floor\floor.obj)
                drawToolButton(palX + 8f, by, btnW, btnH, "Floor Model", EditorTool.FLOOR, mx, my)
                by += btnH + 8f

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Ceiling section ---
                ui.drawText("Ceiling", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Ceiling model button (models\vox\ceiling\ceiling.obj)
                drawToolButton(palX + 8f, by, btnW, btnH, "Ceiling Model", EditorTool.CEILING, mx, my)
                by += btnH + 8f

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Walls section ---
                ui.drawText("Walls", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Wall model button (models\vox\wall\wall.obj)
                drawToolButton(palX + 8f, by, btnW, btnH, "Wall Model", EditorTool.WALL, mx, my)
                by += btnH + 8f

                // Info text
                by += 4f
                ui.drawText("Ctrl+Click to erase", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 0.9f)
                by += 16f
            }

            PaletteTab.LIGHTS -> {
                // Light tool button
                drawToolButton(palX + 8f, by, btnW, btnH, "Place Light", EditorTool.LIGHT, mx, my)
                by += btnH + 12f

                // --- Light property controls ---
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.5f)
                by += 8f

                // Determine the light source to edit: selected light or defaults
                val editingLight = if (selectedLightIndex in 0 until w.lightSources.size) {
                    w.lightSources[selectedLightIndex]
                } else null

                val displayRadius = editingLight?.radius ?: defaultLightRadius
                val displayIntensity = editingLight?.intensity ?: defaultLightIntensity

                // Light Radius controls
                ui.drawText("Radius: ${"%.1f".format(displayRadius)}", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1.1f)
                by += 20f

                val ctrlBtnW = 36f
                val ctrlBtnH = 22f
                val ctrlSpacing = 4f

                // [-] radius button
                val rMinusX = palX + 14f
                val rMinusHovered = mx >= rMinusX && mx < rMinusX + ctrlBtnW && my >= by && my < by + ctrlBtnH
                if (rMinusHovered) {
                    ui.drawRect(rMinusX, by, ctrlBtnW, ctrlBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                } else {
                    ui.drawRect(rMinusX, by, ctrlBtnW, ctrlBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                }
                ui.drawText(" -", rMinusX + 8f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1.1f)
                if (rMinusHovered && inputSystem.isMouseButtonJustPressed(0)) {
                    val newVal = (displayRadius - 0.5f).coerceAtLeast(0.5f)
                    if (editingLight != null) editingLight.radius = newVal
                    defaultLightRadius = newVal
                }

                // [+] radius button
                val rPlusX = rMinusX + ctrlBtnW + ctrlSpacing
                val rPlusHovered = mx >= rPlusX && mx < rPlusX + ctrlBtnW && my >= by && my < by + ctrlBtnH
                if (rPlusHovered) {
                    ui.drawRect(rPlusX, by, ctrlBtnW, ctrlBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                } else {
                    ui.drawRect(rPlusX, by, ctrlBtnW, ctrlBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                }
                ui.drawText(" +", rPlusX + 8f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1.1f)
                if (rPlusHovered && inputSystem.isMouseButtonJustPressed(0)) {
                    val newVal = (displayRadius + 0.5f).coerceAtMost(50f)
                    if (editingLight != null) editingLight.radius = newVal
                    defaultLightRadius = newVal
                }
                by += ctrlBtnH + 12f

                // Light Intensity controls
                ui.drawText("Intensity: ${"%.1f".format(displayIntensity)}", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1.1f)
                by += 20f

                // [-] intensity button
                val iMinusX = palX + 14f
                val iMinusHovered = mx >= iMinusX && mx < iMinusX + ctrlBtnW && my >= by && my < by + ctrlBtnH
                if (iMinusHovered) {
                    ui.drawRect(iMinusX, by, ctrlBtnW, ctrlBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                } else {
                    ui.drawRect(iMinusX, by, ctrlBtnW, ctrlBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                }
                ui.drawText(" -", iMinusX + 8f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1.1f)
                if (iMinusHovered && inputSystem.isMouseButtonJustPressed(0)) {
                    val newVal = (displayIntensity - 0.5f).coerceAtLeast(0.5f)
                    if (editingLight != null) editingLight.intensity = newVal
                    defaultLightIntensity = newVal
                }

                // [+] intensity button
                val iPlusX = iMinusX + ctrlBtnW + ctrlSpacing
                val iPlusHovered = mx >= iPlusX && mx < iPlusX + ctrlBtnW && my >= by && my < by + ctrlBtnH
                if (iPlusHovered) {
                    ui.drawRect(iPlusX, by, ctrlBtnW, ctrlBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                } else {
                    ui.drawRect(iPlusX, by, ctrlBtnW, ctrlBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                }
                ui.drawText(" +", iPlusX + 8f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1.1f)
                if (iPlusHovered && inputSystem.isMouseButtonJustPressed(0)) {
                    val newVal = (displayIntensity + 0.5f).coerceAtMost(50f)
                    if (editingLight != null) editingLight.intensity = newVal
                    defaultLightIntensity = newVal
                }
                by += ctrlBtnH + 12f

                // Show selected light info
                if (editingLight != null) {
                    ui.drawText("Selected: Light #${selectedLightIndex + 1}", palX + 14f, by, 0.55f, 0.6f, 0.7f, 0.8f, 1f)
                } else {
                    ui.drawText("No light selected", palX + 14f, by, 0.45f, 0.5f, 0.6f, 0.6f, 1f)
                }
                by += 20f

                // Light count
                ui.drawText("Lights: ${w.lightSources.size}", palX + 14f, by, 0.55f, 0.6f, 0.7f, 0.8f, 1f)
                by += 20f

                // Info text
                ui.drawText("Ctrl+Click to remove", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 0.9f)
                by += 16f
            }
        }

        // Layer info
        by += 12f
        ui.drawText("Layer: $currentZ / ${w.depth - 1}", palX + 8f, by, 0.6f, 0.65f, 0.75f, 1f, 1.1f)
        by += 20f
        ui.drawText("Z/X to change layer", palX + 8f, by, 0.45f, 0.5f, 0.6f, 0.7f, 1f)
    }

    /**
     * Draw a tool button that can be deselected (clicking a selected tool deselects it).
     */
    private fun drawToolButton(
        x: Float, y: Float, w: Float, h: Float,
        label: String, tool: EditorTool,
        mx: Float, my: Float
    ) {
        val selected = currentTool == tool
        val hovered = mx >= x && mx < x + w && my >= y && my < y + h

        if (selected) {
            ui.drawRect(x, y, w, h, 0.3f, 0.42f, 0.65f, 0.9f)
        } else if (hovered) {
            ui.drawRect(x, y, w, h, 0.2f, 0.26f, 0.38f, 0.7f)
        } else {
            ui.drawRect(x, y, w, h, 0.15f, 0.17f, 0.22f, 0.6f)
        }

        ui.drawText(label, x + 6f, y + 5f, 0.82f, 0.82f, 0.9f, 1f, 1.1f)

        if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
            // Toggle: clicking selected tool deselects it
            currentTool = if (selected) null else tool
        }
    }

    // ---- Gimbal Orientation Cube ----

    private fun drawGimbalCube() {
        val sw = ui.screenWidth
        val cubeSize = 40f
        val margin = 20f
        val cx = sw - toolsPaletteWidth - margin - cubeSize
        val cy = margin + cubeSize

        val azRad = Math.toRadians(azimuth.toDouble())
        val elRad = Math.toRadians(elevation.toDouble())
        val cosA = cos(azRad).toFloat()
        val sinA = sin(azRad).toFloat()
        val cosE = cos(elRad).toFloat()
        val sinE = sin(elRad).toFloat()

        // Project a unit-cube vertex into the small widget space.
        // We use an isometric-style projection matching the camera angles.
        fun proj(lx: Float, ly: Float, lz: Float): Pair<Float, Float> {
            // Rotate around Z by -azimuth, then tilt by elevation
            val rx = lx * cosA + ly * sinA
            val ry = -lx * sinA + ly * cosA
            val rz = lz

            // Pitch (elevation): rotate around the new X axis
            val fz = ry * sinE + rz * cosE

            val screenX = cx + rx * cubeSize
            val screenY = cy - fz * cubeSize  // Y-up on screen → subtract
            return screenX to screenY
        }

        // 8 cube vertices in local space (±0.5 on each axis)
        val v = arrayOf(
            Triple(-0.5f, -0.5f, -0.5f), Triple(0.5f, -0.5f, -0.5f),
            Triple(0.5f,  0.5f, -0.5f),  Triple(-0.5f,  0.5f, -0.5f),
            Triple(-0.5f, -0.5f,  0.5f), Triple(0.5f, -0.5f,  0.5f),
            Triple(0.5f,  0.5f,  0.5f),  Triple(-0.5f,  0.5f,  0.5f)
        )

        // 12 edges
        val edges = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )

        // Draw edges as thin rectangles
        for ((a, b) in edges) {
            val (x1, y1) = proj(v[a].first, v[a].second, v[a].third)
            val (x2, y2) = proj(v[b].first, v[b].second, v[b].third)
            drawLine(x1, y1, x2, y2, 0.4f, 0.45f, 0.6f, 0.6f)
        }

        // Colour the three edges emanating from the (-0.5, -0.5, -0.5) corner
        // as axis indicators: X=red, Y=green, Z=blue.
        // Vertex 0 = (-0.5,-0.5,-0.5)
        // Edge 0→1 = X axis, Edge 0→3 = Y axis, Edge 0→4 = Z axis
        val (ox, oy) = proj(v[0].first, v[0].second, v[0].third)
        val (xxEnd, xyEnd) = proj(v[1].first, v[1].second, v[1].third) // +X
        val (yxEnd, yyEnd) = proj(v[3].first, v[3].second, v[3].third) // +Y
        val (zxEnd, zyEnd) = proj(v[4].first, v[4].second, v[4].third) // +Z

        drawLine(ox, oy, xxEnd, xyEnd, 1f, 0.3f, 0.3f, 1f)
        drawLine(ox, oy, yxEnd, yyEnd, 0.3f, 1f, 0.3f, 1f)
        drawLine(ox, oy, zxEnd, zyEnd, 0.3f, 0.5f, 1f, 1f)

        // Axis labels at the ends of the coloured edges
        ui.drawText("X", xxEnd - 3f, xyEnd - 6f, 1f, 0.3f, 0.3f, 1f, 0.8f)
        ui.drawText("Y", yxEnd - 3f, yyEnd - 6f, 0.3f, 1f, 0.3f, 1f, 0.8f)
        ui.drawText("Z", zxEnd - 3f, zyEnd - 6f, 0.3f, 0.5f, 1f, 1f, 0.8f)
    }

    /**
     * Draw a line between two screen-space points as a thin rotated rectangle.
     */
    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float,
                         r: Float, g: Float, b: Float, a: Float, thickness: Float = 2f) {
        debugRenderer.drawLine(x1, y1, x2, y2, r, g, b, a, thickness)
    }

    fun resize(width: Int, height: Int) {
        camera.resize(width, height)
    }

    fun dispose() {
        world = null
    }
}
