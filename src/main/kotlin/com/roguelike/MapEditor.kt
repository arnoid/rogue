package com.roguelike

import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode
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
 * World map editor with orbital camera and File menu bar.
 *
 * Controls:
 *  W/A/S/D – pan orbit centre (forward/left/back/right)
 *  Shift+A/D – rotate map around Z axis (azimuth)
 *  Shift+W/S – pitch camera (elevation)
 *  Shift+Q/E – zoom in/out (dolly)
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
    private var doorMesh: MeshData? = null
    private var ladderMesh: MeshData? = null
    private var stairsMesh: MeshData? = null

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
    private enum class EditorMode { NORMAL, GRID_TOGGLE, LIGHTS, GPU_RENDER, ROOM }
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

    // Middle mouse drag for orbit (replicates Shift+WASD rotation)
    private var middleDragging = false
    private var middleDragLastX = 0f
    private var middleDragLastY = 0f

    // Editor cursor (tile coordinates)
    private var cursorX = 0
    private var cursorY = 0

    // Current tool (selected from the tools palette)
    private var currentTool: EditorTool? = EditorTool.FLOOR
    private var lastFrameTime: Long = System.nanoTime()

    enum class EditorTool { FLOOR, CEILING, WALL, WALL_DOORWAY, DOOR, LADDER, STAIRS, LIGHT }

    /** Current stairs rotation in degrees (0=N, 90=E, 180=S, 270=W). */
    private var stairsRotation = 0f
    private var ladderRotation = 0f

    /** Tools palette tab selection. */
    private enum class PaletteTab { WORLD, STRUCTURES, LIGHTS, TAGS }
    private var selectedPaletteTab = PaletteTab.STRUCTURES

    /** Horizontal scroll offset (pixels) for the tools-palette tab strip. */
    private var paletteTabsScrollX: Float = 0f

    /** Which world-size slider (X/Y/Z) is currently being dragged, or null. */
    private var draggingWorldSlider: Char? = null

    // ── Room mode drag state ─────────────────────────────────────────────
    // EditorMode.ROOM: pressing the left mouse button over the viewport
    // starts a rectangle selection on the cursor's Z layer. While dragging,
    // the Z / X keys grow / shrink the box's Z extent (X adds another
    // layer on top, Z removes one) so the user can build multi-storey
    // rooms without releasing the mouse. On release the box's interior
    // tiles are wiped and the bottom/top/perimeter cells are populated
    // with floor / ceiling / wall tiles.
    private var roomDragActive = false
    private var roomAnchorX = 0
    private var roomAnchorY = 0
    private var roomAnchorZ = 0
    /** Extra Z layers above [roomAnchorZ] included in the box (0 = single layer). */
    private var roomZExtent = 0

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

    // --- Tooltip state ---
    private var tooltipText: String? = null
    private var tooltipX = 0f
    private var tooltipY = 0f

    // --- Tag editing state ---
    private var selectedTag: String? = null

    fun show() {
        // Load structure model meshes
        try { floorMesh = assetLoader.loadModel("floor", "models/vox/floor/floor.obj") } catch (_: Exception) {}
        try { ceilingMesh = assetLoader.loadModel("ceiling", "models/vox/ceiling/ceiling.obj") } catch (_: Exception) {}
        try { wallMesh = assetLoader.loadModel("wall", "models/vox/wall/wall.obj") } catch (_: Exception) {}
        try { doorMesh = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj") } catch (_: Exception) {}
        try { ladderMesh = assetLoader.loadModel("ladder", "models/vox/stairs/ladder_vertical_n.obj") } catch (_: Exception) {}
        try { stairsMesh = assetLoader.loadModel("stairs", "models/vox/stairs/stairs_n.obj") } catch (_: Exception) {}

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
        world = World(3, 3, 3)
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
            } else {
                newWorld()
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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

            if (!shiftHeld) {
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

            if (shiftHeld) {
                if (inputSystem.isKeyPressed(GLFW_KEY_Q)) distance = (distance - zoomSpeed).coerceAtLeast(3f)
                if (inputSystem.isKeyPressed(GLFW_KEY_E)) distance = (distance + zoomSpeed).coerceAtMost(200f)
                if (inputSystem.isKeyPressed(GLFW_KEY_W)) elevation = (elevation + rotSpeed).coerceAtMost(89f)
                if (inputSystem.isKeyPressed(GLFW_KEY_S)) elevation = (elevation - rotSpeed).coerceAtLeast(5f)
            }

            if (inputSystem.isKeyJustPressed(GLFW_KEY_Z)) {
                if (roomDragActive) {
                    // Shrink the room's Z extent by one layer. Once it hits
                    // zero (single layer at the anchor), further presses
                    // extend the box DOWNWARD along the Z axis (negative
                    // extent), clamped to layer 0.
                    val minExtent = -roomAnchorZ
                    roomZExtent = (roomZExtent - 1).coerceAtLeast(minExtent)
                } else {
                    currentZ = (currentZ - 1).coerceAtLeast(0)
                }
            }
            if (inputSystem.isKeyJustPressed(GLFW_KEY_X)) {
                if (roomDragActive) {
                    // Grow the room's Z extent by one layer. If the extent
                    // is currently negative (the box was extended downward),
                    // X pulls the bottom back up before continuing upward.
                    val maxExtent = ((w.depth - 1) - roomAnchorZ).coerceAtLeast(0)
                    roomZExtent = (roomZExtent + 1).coerceAtMost(maxExtent)
                } else {
                    currentZ = (currentZ + 1).coerceAtMost(w.depth - 1)
                }
            }

            val scroll = inputSystem.getScrollDelta()
            if (scroll != 0f) {
                distance = (distance - scroll * 2f).coerceIn(3f, 200f)
            }

            // Middle mouse button drag → orbit rotation (same as Shift+A/D/W/S)
            if (inputSystem.isMouseButtonJustPressed(2)) {
                middleDragging = true
                middleDragLastX = mx
                middleDragLastY = my
            }
            if (middleDragging) {
                if (inputSystem.isMouseButtonPressed(2)) {
                    val dx = mx - middleDragLastX
                    val dy = my - middleDragLastY
                    val sensitivity = 0.3f
                    azimuth -= dx * sensitivity
                    elevation = (elevation + dy * sensitivity).coerceIn(5f, 89f)
                    middleDragLastX = mx
                    middleDragLastY = my
                } else {
                    middleDragging = false
                }
            }

            // Tool selection via keyboard
            if (inputSystem.isKeyJustPressed(GLFW_KEY_1)) currentTool = EditorTool.FLOOR
            if (inputSystem.isKeyJustPressed(GLFW_KEY_2)) currentTool = EditorTool.CEILING
            if (inputSystem.isKeyJustPressed(GLFW_KEY_3)) currentTool = EditorTool.WALL
            if (inputSystem.isKeyJustPressed(GLFW_KEY_4)) currentTool = EditorTool.LIGHT
            if (inputSystem.isKeyJustPressed(GLFW_KEY_5)) currentTool = EditorTool.LADDER
            if (inputSystem.isKeyJustPressed(GLFW_KEY_6)) currentTool = EditorTool.STAIRS
            if (currentTool == EditorTool.STAIRS && inputSystem.isKeyJustPressed(GLFW_KEY_R)) {
                stairsRotation = (stairsRotation + 90f) % 360f
            }
            if (currentTool == EditorTool.LADDER && inputSystem.isKeyJustPressed(GLFW_KEY_R)) {
                ladderRotation = (ladderRotation + 90f) % 360f
            }

            // Cursor
            updateCursorFromMouse(w)

            // Update face/edge highlighting based on current tool
            updateHoveredFace(w)

            // Handle Ctrl+Click deletion (suppressed in Room mode — there
            // Ctrl modifies the room-drag commit, not single-cell deletion).
            if (ctrlHeld && selectedEditorMode != EditorMode.ROOM && inputSystem.isMouseButtonJustPressed(0)) {
                when (selectedPaletteTab) {
                    PaletteTab.STRUCTURES -> {
                        if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                            val node = w.getNode(cursorX, cursorY, currentZ)
                            if (node != null) {
                                when (currentTool) {
                                    EditorTool.LADDER -> {
                                        node.removeTile(TileSlot.STAIRS)
                                        // Also remove ladder edge tag from hovered edge
                                        when (hoveredFace) {
                                            HoveredFace.EDGE_NORTH -> node.untagLadder(TileSlot.WALL_NORTH)
                                            HoveredFace.EDGE_SOUTH -> node.untagLadder(TileSlot.WALL_SOUTH)
                                            HoveredFace.EDGE_EAST -> node.untagLadder(TileSlot.WALL_EAST)
                                            HoveredFace.EDGE_WEST -> node.untagLadder(TileSlot.WALL_WEST)
                                            else -> {
                                                // Remove all ladder tags if no edge hovered
                                                for (s in listOf(TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, TileSlot.WALL_EAST, TileSlot.WALL_WEST))
                                                    node.untagLadder(s)
                                            }
                                        }
                                    }
                                    EditorTool.STAIRS -> node.removeTile(TileSlot.STAIRS)
                                    else -> when (hoveredFace) {
                                        HoveredFace.BOTTOM -> node.removeTile(TileSlot.FLOOR)
                                        HoveredFace.TOP -> node.removeTile(TileSlot.CEILING)
                                        HoveredFace.EDGE_NORTH -> node.removeTile(TileSlot.WALL_NORTH)
                                        HoveredFace.EDGE_SOUTH -> node.removeTile(TileSlot.WALL_SOUTH)
                                        HoveredFace.EDGE_EAST -> node.removeTile(TileSlot.WALL_EAST)
                                        HoveredFace.EDGE_WEST -> node.removeTile(TileSlot.WALL_WEST)
                                        // No specific face was detected by the
                                        // ray-cast (typical for tools whose
                                        // hover detection doesn't return a
                                        // face, e.g. FLOOR / CEILING when the
                                        // ray hits the cell from inside).
                                        // Fall back to removing the single
                                        // tile that matches the active tool —
                                        // never wipe the whole node.
                                        HoveredFace.NONE -> when (currentTool) {
                                            EditorTool.FLOOR        -> node.removeTile(TileSlot.FLOOR)
                                            EditorTool.CEILING      -> node.removeTile(TileSlot.CEILING)
                                            EditorTool.WALL,
                                            EditorTool.WALL_DOORWAY,
                                            EditorTool.DOOR,
                                            EditorTool.LADDER,
                                            EditorTool.STAIRS,
                                            EditorTool.LIGHT,
                                            null                    -> { /* no-op */ }
                                        }
                                    }
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
                    PaletteTab.WORLD -> { /* No viewport interactions for the World tab. */ }
                    PaletteTab.TAGS -> {
                        // Ctrl+Click removes the selected tag (or all tags if none selected)
                        if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                            val node = w.getNode(cursorX, cursorY, currentZ)
                            if (node != null) {
                                if (selectedTag == WorldNode.Tags.LADDER) {
                                    // Remove ladder tag from hovered edge
                                    val slot = when (hoveredFace) {
                                        HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                        HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                        HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                        HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                        else -> null
                                    }
                                    if (slot != null) node.untagLadder(slot)
                                } else if (selectedTag == WorldNode.Tags.DOOR_MANUAL) {
                                    // Remove manual door tag from hovered edge (and adjacent node's opposite wall)
                                    val slot = when (hoveredFace) {
                                        HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                        HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                        HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                        HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                        else -> null
                                    }
                                    if (slot != null) {
                                        node.untagManualDoor(slot)
                                        val (adjX, adjY, oppSlot) = when (slot) {
                                            TileSlot.WALL_NORTH -> Triple(cursorX, cursorY + 1, TileSlot.WALL_SOUTH)
                                            TileSlot.WALL_SOUTH -> Triple(cursorX, cursorY - 1, TileSlot.WALL_NORTH)
                                            TileSlot.WALL_EAST -> Triple(cursorX + 1, cursorY, TileSlot.WALL_WEST)
                                            TileSlot.WALL_WEST -> Triple(cursorX - 1, cursorY, TileSlot.WALL_EAST)
                                            else -> Triple(-1, -1, slot)
                                        }
                                        if (adjX in 0 until w.width && adjY in 0 until w.height) {
                                            w.getNode(adjX, adjY, currentZ)?.untagManualDoor(oppSlot)
                                        }
                                    }
                                } else if (selectedTag == WorldNode.Tags.SOCKET) {
                                    // Remove socket tag from hovered edge.
                                    val slot = when (hoveredFace) {
                                        HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                        HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                        HoveredFace.EDGE_EAST  -> TileSlot.WALL_EAST
                                        HoveredFace.EDGE_WEST  -> TileSlot.WALL_WEST
                                        else -> null
                                    }
                                    if (slot != null) {
                                        node.untagSocket(slot)
                                    } else {
                                        // No edge hovered → clear all socket slots on this node.
                                        for (s in listOf(TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, TileSlot.WALL_EAST, TileSlot.WALL_WEST)) {
                                            node.untagSocket(s)
                                        }
                                    }
                                } else if (selectedTag != null) {
                                    node.tags.remove(selectedTag)
                                } else {
                                    node.tags.clear()
                                    // Also clear all ladder edge tags
                                    for (s in listOf(TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, TileSlot.WALL_EAST, TileSlot.WALL_WEST)) {
                                        node.untagLadder(s)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Room mode: drag-select a rectangle on the cursor's Z layer.
            // While the drag is in progress, Z / X grow/shrink the box's Z
            // extent (X adds another layer on top, Z removes one). On release
            // the box is committed: every node inside the box is cleared
            // first, then floors go on the bottom Z layer, ceilings on the
            // top Z layer, and outer-perimeter cells get the appropriate
            // wall tile facing outward.
            var roomConsumedMouse = false
            if (selectedEditorMode == EditorMode.ROOM && !uiBlocking) {
                if (!roomDragActive && inputSystem.isMouseButtonJustPressed(0) &&
                    cursorX in 0 until w.width && cursorY in 0 until w.height) {
                    roomDragActive = true
                    roomAnchorX = cursorX
                    roomAnchorY = cursorY
                    roomAnchorZ = currentZ
                    roomZExtent = 0
                }
                if (roomDragActive) {
                    if (!inputSystem.isMouseButtonPressed(0)) {
                        val endX = cursorX.coerceIn(0, w.width - 1)
                        val endY = cursorY.coerceIn(0, w.height - 1)
                        val otherZ = (roomAnchorZ + roomZExtent).coerceIn(0, w.depth - 1)
                        val zLo = minOf(roomAnchorZ, otherZ)
                        val zHi = maxOf(roomAnchorZ, otherZ)
                        val xLo = minOf(roomAnchorX, endX); val xHi = maxOf(roomAnchorX, endX)
                        val yLo = minOf(roomAnchorY, endY); val yHi = maxOf(roomAnchorY, endY)
                        if (ctrlHeld) {
                            // Ctrl held on release → strip every node + light
                            // source inside the selection box (room removal).
                            clearRoom(w, xLo, xHi, yLo, yHi, zLo, zHi)
                        } else {
                            buildRoom(w, xLo, xHi, yLo, yHi, zLo, zHi)
                        }
                        roomDragActive = false
                        roomZExtent = 0
                    }
                    roomConsumedMouse = true
                } else if (inputSystem.isMouseButtonPressed(0)) {
                    // Drag began outside bounds — still suppress regular placement.
                    roomConsumedMouse = true
                }
            }

            // Place with left mouse (non-Ctrl)
            if (!roomConsumedMouse && !ctrlHeld && inputSystem.isMouseButtonPressed(0)) {
                if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
                    val node = w.getNode(cursorX, cursorY, currentZ)
                    if (node != null) {
                        // Tag placement when Tags tab is active.
                        // When on the TAGS palette we ONLY mutate tags — never
                        // run the currentTool action below (which would e.g.
                        // place walls/floors as a side-effect of clicking).
                        val onTagsPalette = selectedPaletteTab == PaletteTab.TAGS && selectedTag != null
                        if (onTagsPalette && inputSystem.isMouseButtonJustPressed(0)) {
                            val tag = selectedTag!!
                            if (tag == WorldNode.Tags.LADDER) {
                                // Ladder tag: only place on edge that has a LadderTile
                                val slot = when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                    HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                    HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                    HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                    else -> null
                                }
                                if (slot != null && node.getTile(TileSlot.STAIRS) is LadderTile) {
                                    // Verify ladder faces this direction
                                    val ladderTile = node.getTile(TileSlot.STAIRS) as LadderTile
                                    val facing = ladderTile.facingDirection()
                                    if (facing == slot) {
                                        node.tagAsLadder(slot)
                                    }
                                }
                            } else if (tag == WorldNode.Tags.STAIRS) {
                                // Stairs tag: only place if node has a StairsTile
                                if (node.getTile(TileSlot.STAIRS) is StairsTile) {
                                    if (!node.tags.contains(tag)) {
                                        node.tags.add(tag)
                                    }
                                }
                            } else if (tag == WorldNode.Tags.DOOR_MANUAL) {
                                // Door manual tag: only place on edge that has a door tile
                                val slot = when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                    HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                    HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                    HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                    else -> null
                                }
                                System.out.println("[DOOR_MANUAL] click: pos=($cursorX,$cursorY,$currentZ) hoveredFace=$hoveredFace slot=$slot")
                                if (slot != null) {
                                    val tile = node.getTile(slot)
                                    val hasDoor = node.isDoor(slot) || tile is DoorNorthTile || tile is DoorSouthTile || tile is DoorEastTile || tile is DoorWestTile
                                    System.out.println("[DOOR_MANUAL]   tile=${tile?.javaClass?.simpleName} isDoor=${node.isDoor(slot)} hasDoor=$hasDoor tiles=${node.tiles.map { "${it.slot}=${it.javaClass.simpleName}" }} doorSlots=${node.doorSlots} manualDoorSlots=${node.manualDoorSlots}")
                                    if (hasDoor) {
                                        node.tagAsDoor(slot)
                                        node.tagAsManualDoor(slot)
                                        System.out.println("[DOOR_MANUAL]   SUCCESS -> doorSlots=${node.doorSlots} manualDoorSlots=${node.manualDoorSlots}")
                                    } else {
                                        // Check adjacent node's opposite wall (a door on east of (7,5) is also west of (8,5))
                                        val (adjX, adjY, oppSlot) = when (slot) {
                                            TileSlot.WALL_NORTH -> Triple(cursorX, cursorY + 1, TileSlot.WALL_SOUTH)
                                            TileSlot.WALL_SOUTH -> Triple(cursorX, cursorY - 1, TileSlot.WALL_NORTH)
                                            TileSlot.WALL_EAST -> Triple(cursorX + 1, cursorY, TileSlot.WALL_WEST)
                                            TileSlot.WALL_WEST -> Triple(cursorX - 1, cursorY, TileSlot.WALL_EAST)
                                            else -> Triple(-1, -1, slot)
                                        }
                                        val adjNode = if (adjX in 0 until w.width && adjY in 0 until w.height) w.getNode(adjX, adjY, currentZ) else null
                                        val adjTile = adjNode?.getTile(oppSlot)
                                        val adjHasDoor = adjNode != null && (adjNode.isDoor(oppSlot) || adjTile is DoorNorthTile || adjTile is DoorSouthTile || adjTile is DoorEastTile || adjTile is DoorWestTile)
                                        System.out.println("[DOOR_MANUAL]   checking adj($adjX,$adjY) oppSlot=$oppSlot adjTile=${adjTile?.javaClass?.simpleName} adjHasDoor=$adjHasDoor")
                                        if (adjHasDoor) {
                                            // Tag both sides
                                            adjNode!!.tagAsDoor(oppSlot)
                                            adjNode.tagAsManualDoor(oppSlot)
                                            node.tagAsDoor(slot)
                                            node.tagAsManualDoor(slot)
                                            System.out.println("[DOOR_MANUAL]   SUCCESS (via adj) -> node doorSlots=${node.doorSlots} manualDoorSlots=${node.manualDoorSlots}, adj doorSlots=${adjNode.doorSlots} manualDoorSlots=${adjNode.manualDoorSlots}")
                                        } else {
                                            System.out.println("[DOOR_MANUAL]   FAIL: no door at slot or adjacent")
                                        }
                                    }
                                } else {
                                    System.out.println("[DOOR_MANUAL]   FAIL: slot=null (hoveredFace=$hoveredFace not an edge)")
                                }
                            } else if (tag == WorldNode.Tags.SOCKET) {
                                // Socket tag: only valid on a wall slot that
                                // faces the OUTER edge of the world. The
                                // hovered edge must match the cell's world-
                                // boundary side (e.g. WALL_NORTH only on
                                // y == world.height - 1).
                                val slot = when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                    HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                    HoveredFace.EDGE_EAST  -> TileSlot.WALL_EAST
                                    HoveredFace.EDGE_WEST  -> TileSlot.WALL_WEST
                                    else -> null
                                }
                                val onOuterEdge = slot != null && when (slot) {
                                    TileSlot.WALL_NORTH -> cursorY == w.height - 1
                                    TileSlot.WALL_SOUTH -> cursorY == 0
                                    TileSlot.WALL_EAST  -> cursorX == w.width - 1
                                    TileSlot.WALL_WEST  -> cursorX == 0
                                    else -> false
                                }
                                if (slot != null && onOuterEdge) {
                                    node.tagAsSocket(slot)
                                }
                            } else {
                                if (!node.tags.contains(tag)) {
                                    node.tags.add(tag)
                                }
                            }
                        }
                        if (!onTagsPalette) {
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
                                    else -> {}
                                }
                            }
                            EditorTool.WALL_DOORWAY -> {
                                // Place a doorway-wall (wall with an opening) along the hovered edge.
                                // Acts as a non-blocking wall variant — distinct from a Door tile.
                                when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> node.setTile(WallDoorwayNorthTile())
                                    HoveredFace.EDGE_SOUTH -> node.setTile(WallDoorwaySouthTile())
                                    HoveredFace.EDGE_EAST  -> node.setTile(WallDoorwayEastTile())
                                    HoveredFace.EDGE_WEST  -> node.setTile(WallDoorwayWestTile())
                                    else -> {}
                                }
                            }
                            EditorTool.DOOR -> {
                                // Place door (doorway + door tile) along the hovered edge
                                val slot = when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                    HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                    HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                    HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                    else -> null
                                }
                                if (slot != null) {
                                    val doorTile = when (slot) {
                                        TileSlot.WALL_NORTH -> DoorNorthTile()
                                        TileSlot.WALL_SOUTH -> DoorSouthTile()
                                        TileSlot.WALL_EAST -> DoorEastTile()
                                        TileSlot.WALL_WEST -> DoorWestTile()
                                        else -> DoorNorthTile()
                                    }
                                    node.setTile(doorTile)
                                    node.tagAsDoor(slot)
                                    System.out.println("[DOOR_TOOL] placed: pos=($cursorX,$cursorY,$currentZ) hoveredFace=$hoveredFace slot=$slot tile=${doorTile.javaClass.simpleName} doorSlots=${node.doorSlots} manualDoorSlots=${node.manualDoorSlots}")
                                } else {
                                    System.out.println("[DOOR_TOOL] FAIL: hoveredFace=$hoveredFace not an edge")
                                }
                            }
                            EditorTool.LADDER -> {
                                // Place ladder on the hovered edge (like walls)
                                val rot = when (hoveredFace) {
                                    HoveredFace.EDGE_NORTH -> 0f
                                    HoveredFace.EDGE_SOUTH -> 180f
                                    HoveredFace.EDGE_EAST -> 90f
                                    HoveredFace.EDGE_WEST -> 270f
                                    else -> null
                                }
                                if (rot != null) {
                                    val t = LadderTile(); t.rotationY = rot; node.setTile(t)
                                    // Also tag the edge as ladder for navigation
                                    val slot = when (hoveredFace) {
                                        HoveredFace.EDGE_NORTH -> TileSlot.WALL_NORTH
                                        HoveredFace.EDGE_SOUTH -> TileSlot.WALL_SOUTH
                                        HoveredFace.EDGE_EAST -> TileSlot.WALL_EAST
                                        HoveredFace.EDGE_WEST -> TileSlot.WALL_WEST
                                        else -> null
                                    }
                                    slot?.let { node.tagAsLadder(it) }
                                }
                            }
                            EditorTool.STAIRS -> {
                                val t = StairsTile(); t.rotationY = stairsRotation; node.setTile(t)
                            }
                            EditorTool.LIGHT -> {} // handled below
                            null -> {} // no tool selected
                        }
                        } // end if (!onTagsPalette)
                    }
                }
            }

            // Light selection, dragging, and placement
            if (selectedEditorMode != EditorMode.ROOM && currentTool == EditorTool.LIGHT && !ctrlHeld && inputSystem.isMouseButtonJustPressed(0)) {
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

        // Draw tooltip last (on top of everything)
        drawTooltip()

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

        // Also detect edges when placing ladder tags
        val needsEdgeDetection = currentTool == EditorTool.WALL || currentTool == EditorTool.WALL_DOORWAY || currentTool == EditorTool.DOOR || currentTool == EditorTool.LADDER ||
            (selectedPaletteTab == PaletteTab.TAGS && (selectedTag == WorldNode.Tags.LADDER || selectedTag == WorldNode.Tags.DOOR_MANUAL || selectedTag == WorldNode.Tags.SOCKET))

        when {
            needsEdgeDetection -> {
                // Determine which inner edge of the cursor node the mouse is closest to.
                // Use the ray-floor intersection point to find the local position within the tile,
                // then pick the nearest edge.
                val planeZ = currentZ.toFloat()
                if (abs(dir.z) > 1e-6f) {
                    val t = (planeZ - nearWorld.z) / dir.z
                    if (t > 0) {
                        val hitX = nearWorld.x + dir.x * t
                        val hitY = nearWorld.y + dir.y * t
                        val localX = hitX - bx  // [0, 1) within tile
                        val localY = hitY - by
                        // Find which edge is closest
                        val distN = 1f - localY
                        val distS = localY
                        val distE = 1f - localX
                        val distW = localX
                        val minDist = minOf(distN, distS, distE, distW)
                        hoveredFace = when (minDist) {
                            distN -> HoveredFace.EDGE_NORTH
                            distS -> HoveredFace.EDGE_SOUTH
                            distE -> HoveredFace.EDGE_EAST
                            else -> HoveredFace.EDGE_WEST
                        }
                    }
                }
            }
            currentTool == EditorTool.FLOOR -> {
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
            currentTool == EditorTool.CEILING -> {
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
     * Collect world-space shadow triangles from a mesh at a given node position.
     * Applies the same transform as drawModelAtNode (center, scale, Y↔Z swap, rotation, translate).
     * Appends 9 floats per triangle (v0xyz, v1xyz, v2xyz) to the output list.
     * Returns the number of triangles appended.
     */
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

    /**
     * Add a synthetic shadow occluder for stairs.
     *
     * Add a synthetic shadow occluder box for stairs.
     *
     * Creates a full box enclosing the staircase cell (all 4 sides + top cap).
     * The bottom is omitted because the floor covers it.
     *
     * This works because the origin-cell skip is disabled in the shader:
     * fragments on stair surfaces have a normal offset (0.15) that pushes
     * the ray origin past the thin model geometry, so self-intersection
     * doesn't occur.  Fragments near the cell boundary (e.g., bottom step
     * with offset along +Y) get pushed into the adjacent cell, so the DDA
     * starts there and tests the stairs cell's shadow mesh normally.
     */
    private fun addStairsBackWall(
        nodeX: Float, nodeY: Float, nodeZ: Float,
        @Suppress("UNUSED_PARAMETER") rotationYDeg: Float,
        out: MutableList<Float>
    ): Int {
        val x0 = nodeX;       val y0 = nodeY
        val x1 = nodeX + 1f;  val y1 = nodeY + 1f
        val zBot = nodeZ
        val zTop = nodeZ + 1f

        fun quad(ax: Float, ay: Float, az: Float,
                 bx: Float, by: Float, bz: Float,
                 cx: Float, cy: Float, cz: Float,
                 dx: Float, dy: Float, dz: Float) {
            out.add(ax); out.add(ay); out.add(az)
            out.add(bx); out.add(by); out.add(bz)
            out.add(cx); out.add(cy); out.add(cz)
            out.add(ax); out.add(ay); out.add(az)
            out.add(cx); out.add(cy); out.add(cz)
            out.add(dx); out.add(dy); out.add(dz)
        }

        // North wall (Y+ face)
        quad(x0, y1, zBot, x1, y1, zBot, x1, y1, zTop, x0, y1, zTop)
        // South wall (Y- face)
        quad(x0, y0, zBot, x1, y0, zBot, x1, y0, zTop, x0, y0, zTop)
        // East wall (X+ face)
        quad(x1, y0, zBot, x1, y1, zBot, x1, y1, zTop, x1, y0, zTop)
        // West wall (X- face)
        quad(x0, y0, zBot, x0, y1, zBot, x0, y1, zTop, x0, y0, zTop)
        // Top cap (Z+ face)
        quad(x0, y0, zTop, x1, y0, zTop, x1, y1, zTop, x0, y1, zTop)

        return 10 // 5 quads × 2 triangles each
    }

    /**
     * Draw a loaded mesh model at a specific grid node position using GPU triangles.
     * The model is scaled to fit within a 1x1x1 node and centered, then translated
     * to the node position with the given offsets.
     * @param rotationYDeg rotation around the Y axis in degrees (for wall orientation)
     */
    private var modelDrawLogCount = 0
    private var renderLogFrames = 0

    /**
     * Draw a 3D direction arrow on top of a stair/ladder tile.
     * The arrow points in the facing direction (north = +Y at rotation 0°).
     * Rendered as a projected line with an arrowhead.
     */
    private fun drawDirectionArrow(nodeX: Float, nodeY: Float, nodeZ: Float, rotationYDeg: Float) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val cx = nodeX + 0.5f
        val cy = nodeY + 0.5f
        val cz = nodeZ + 1.05f // slightly above the cell top

        // Direction vector based on rotation (matches facingDirection mapping)
        val normalized = ((rotationYDeg % 360f) + 360f) % 360f
        val dx: Float; val dy: Float
        when {
            normalized < 45f || normalized >= 315f -> { dx = 0f; dy = 0.35f }   // North (+Y)
            normalized < 135f                      -> { dx = 0.35f; dy = 0f }   // East (+X)
            normalized < 225f                      -> { dx = 0f; dy = -0.35f }  // South (-Y)
            else                                   -> { dx = -0.35f; dy = 0f }  // West (-X)
        }

        // Arrow shaft: from center-back to center-front
        val tailX = cx - dx * 0.8f; val tailY = cy - dy * 0.8f
        val tipX = cx + dx * 0.8f;  val tipY = cy + dy * 0.8f

        val pTail = camera.project(org.joml.Vector3f(tailX, tailY, cz), sw, sh)
        val pTip  = camera.project(org.joml.Vector3f(tipX, tipY, cz), sw, sh)

        // Arrowhead wings (perpendicular to direction)
        val backX = tipX - dx * 0.6f; val backY = tipY - dy * 0.6f
        // Perpendicular: rotate (dx,dy) by 90°
        val perpX = -dy; val perpY = dx
        val pWing1 = camera.project(org.joml.Vector3f(backX + perpX * 0.4f, backY + perpY * 0.4f, cz), sw, sh)
        val pWing2 = camera.project(org.joml.Vector3f(backX - perpX * 0.4f, backY - perpY * 0.4f, cz), sw, sh)

        val r = 1f; val g = 1f; val b = 0f; val a = 0.9f; val t = 2.5f
        debugRenderer.drawLine(pTail.x, pTail.y, pTip.x, pTip.y, r, g, b, a, t)
        debugRenderer.drawLine(pTip.x, pTip.y, pWing1.x, pWing1.y, r, g, b, a, t)
        debugRenderer.drawLine(pTip.x, pTip.y, pWing2.x, pWing2.y, r, g, b, a, t)
    }

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
            if (indices.isNotEmpty()) {
                val vi0 = (indices[0].toInt() and 0xFFFF) * 6
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
            val idx0 = indices[i].toInt() and 0xFFFF
            val idx1 = indices[i + 1].toInt() and 0xFFFF
            val idx2 = indices[i + 2].toInt() and 0xFFFF
            val v0 = xform(idx0)
            val v1 = xform(idx1)
            val v2 = xform(idx2)

            // Use per-vertex colors from palette texture if available, otherwise use flat color
            val colors = mesh.colors
            if (colors != null) {
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
            // Collect shadow mesh triangles for stairs/ladders
            val shadowTriangles = mutableListOf<Float>()
                for (z in 0 until gridD) {
                    for (y in 0 until gridH) {
                        for (x in 0 until gridW) {
                            val node = w.getNode(x, y, z) ?: continue
                            // Hybrid shadow occlusion:
                            // - Walls/floors/ceilings use boundary flags (bits 0-5) checked
                            //   at DDA cell crossings — accurate for planar structures.
                            // - Stairs/ladders use model-based shadow triangles (bits 7-31)
                            //   tested via ray-triangle intersection within cells.
                            var flags = 0
                            if (node.hasTile(TileSlot.WALL_NORTH)) flags = flags or 1
                            if (node.hasTile(TileSlot.WALL_SOUTH)) flags = flags or 2
                            if (node.hasTile(TileSlot.WALL_EAST))  flags = flags or 4
                            if (node.hasTile(TileSlot.WALL_WEST))  flags = flags or 8
                            if (node.hasTile(TileSlot.FLOOR))      flags = flags or 16
                            if (node.hasTile(TileSlot.CEILING))    flags = flags or 32

                            var cellTriStart = shadowTriangles.size / 9
                            var cellTriCount = 0

                            // Collect shadow triangles for stairs/ladders
                            if (node.hasTile(TileSlot.STAIRS)) {
                                val tile = node.getTile(TileSlot.STAIRS)
                                if (tile is StairsTile) {
                                    stairsMesh?.let { cellTriCount += collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), 0f, 0f, 0f, stairsRenderRotation(tile.rotationY), shadowTriangles) }
                                } else if (tile is LadderTile) {
                                    val rotY = tile.rotationY
                                    val offX = when (rotY) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                                    val offY = when (rotY) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                                    ladderMesh?.let { cellTriCount += collectShadowTriangles(it, x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, 0f, rotY, shadowTriangles) }
                                }
                            }

                            // Encode shadow triangle range into flags
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
            // Upload shadow triangles
            val triArray = FloatArray(shadowTriangles.size)
            for (i in shadowTriangles.indices) triArray[i] = shadowTriangles[i]
            ui.updateShadowTriangles(triArray)
        } else {
            ui.updateLighting(emptyList(), IntArray(1), 1, 1, 1)
            ui.updateShadowTriangles(FloatArray(0))
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
                    val hasStairs = node.hasTile(TileSlot.STAIRS)
                    if (!(hasFloor || hasCeiling || hasWallN || hasWallS || hasWallE || hasWallW || hasStairs)) continue
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
                        val tile = node.getTile(TileSlot.WALL_NORTH)
                        val isDoorway = tile is WallDoorwayNorthTile
                        val isDoor = node.isDoor(TileSlot.WALL_NORTH) || isDoorway
                        val mesh = if (isDoor) doorMesh else wallMesh
                        val r = if (node.isManualDoor(TileSlot.WALL_NORTH)) 0.3f else wallR
                        val g = if (node.isManualDoor(TileSlot.WALL_NORTH)) 0.6f else wallG
                        val b = if (node.isManualDoor(TileSlot.WALL_NORTH)) 0.3f else wallB
                        mesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = 0.5f, rotationYDeg = 0f, r = r, g = g, b = b) }
                    }
                    if (hasWallS) {
                        val tile = node.getTile(TileSlot.WALL_SOUTH)
                        val isDoorway = tile is WallDoorwaySouthTile
                        val isDoor = node.isDoor(TileSlot.WALL_SOUTH) || isDoorway
                        val mesh = if (isDoor) doorMesh else wallMesh
                        val r = if (node.isManualDoor(TileSlot.WALL_SOUTH)) 0.3f else wallR
                        val g = if (node.isManualDoor(TileSlot.WALL_SOUTH)) 0.6f else wallG
                        val b = if (node.isManualDoor(TileSlot.WALL_SOUTH)) 0.3f else wallB
                        mesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetY = -0.5f, rotationYDeg = 0f, r = r, g = g, b = b) }
                    }
                    if (hasWallE) {
                        val tile = node.getTile(TileSlot.WALL_EAST)
                        val isDoorway = tile is WallDoorwayEastTile
                        val isDoor = node.isDoor(TileSlot.WALL_EAST) || isDoorway
                        val mesh = if (isDoor) doorMesh else wallMesh
                        val r = if (node.isManualDoor(TileSlot.WALL_EAST)) 0.3f else wallR
                        val g = if (node.isManualDoor(TileSlot.WALL_EAST)) 0.6f else wallG
                        val b = if (node.isManualDoor(TileSlot.WALL_EAST)) 0.3f else wallB
                        mesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = 0.5f, rotationYDeg = 90f, r = r, g = g, b = b) }
                    }
                    if (hasWallW) {
                        val tile = node.getTile(TileSlot.WALL_WEST)
                        val isDoorway = tile is WallDoorwayWestTile
                        val isDoor = node.isDoor(TileSlot.WALL_WEST) || isDoorway
                        val mesh = if (isDoor) doorMesh else wallMesh
                        val r = if (node.isManualDoor(TileSlot.WALL_WEST)) 0.3f else wallR
                        val g = if (node.isManualDoor(TileSlot.WALL_WEST)) 0.6f else wallG
                        val b = if (node.isManualDoor(TileSlot.WALL_WEST)) 0.3f else wallB
                        mesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = -0.5f, rotationYDeg = 90f, r = r, g = g, b = b) }
                    }
                    if (hasStairs) {
                        val tile = node.getTile(TileSlot.STAIRS)
                        if (tile is StairsTile) {
                            stairsMesh?.let { drawModelAtNode(it, tbx, tby, tbz, rotationYDeg = stairsRenderRotation(tile.rotationY), r = 0.45f, g = 0.40f, b = 0.35f) }
                            drawDirectionArrow(tbx, tby, tbz, tile.rotationY)
                        } else if (tile is LadderTile) {
                            val rot = tile.rotationY
                            val offX = when (rot) { 90f -> 0.5f; 270f -> -0.5f; else -> 0f }
                            val offY = when (rot) { 0f -> 0.5f; 180f -> -0.5f; else -> 0f }
                            ladderMesh?.let { drawModelAtNode(it, tbx, tby, tbz, offsetX = offX, offsetY = offY, rotationYDeg = rot, r = 0.50f, g = 0.38f, b = 0.25f) }
                            drawDirectionArrow(tbx, tby, tbz, rot)
                        }
                    }
                }
            }
        }

        if (renderLogFrames < 3) {
            renderLogFrames++
        }
        // Draw tag indicators on nodes (small spheres + labels) — always visible
        for (z in 0..currentZ) {
            for (x in 0 until w.width) {
                for (y in 0 until w.height) {
                    val node = w.getNode(x, y, z) ?: continue
                    var offsetZ = 0f

                    // Draw regular tags
                    for (tag in node.tags) {
                        val wx = x + 0.5f
                        val wy = y + 0.5f
                        val wz = z + 0.85f + offsetZ
                        debugRenderer.drawFilledSphere(wx, wy, wz, 0.12f, camera, 0.9f, 0.8f, 0.2f, 0.85f)
                        debugRenderer.drawWireframeSphere(wx, wy, wz, 0.12f, camera, 1f, 1f, 0.4f, 0.9f, 8, 1.5f)
                        val worldPos = org.joml.Vector3f(wx, wy, wz + 0.15f)
                        val screenPos = camera.project(worldPos, sw, sh)
                        if (screenPos.z in 0f..1f) {
                            val label = tag.replace('_', ' ').uppercase()
                            val tw = ui.textWidth(label) * 1.2f
                            ui.drawRect(screenPos.x - tw / 2f - 3f, screenPos.y - 2f, tw + 6f, 17f, 0f, 0f, 0f, 0.6f)
                            ui.drawText(label, screenPos.x - tw / 2f, screenPos.y, 1f, 0.95f, 0.3f, 1f, 1.2f)
                        }
                        offsetZ += 0.3f
                    }

                    // Draw ladder edge tags (green spheres on edges)
                    for (slot in node.ladderSlots) {
                        val wx = x + 0.5f + when (slot) { TileSlot.WALL_EAST -> 0.45f; TileSlot.WALL_WEST -> -0.45f; else -> 0f }
                        val wy = y + 0.5f + when (slot) { TileSlot.WALL_NORTH -> 0.45f; TileSlot.WALL_SOUTH -> -0.45f; else -> 0f }
                        val wz = z + 0.5f
                        debugRenderer.drawFilledSphere(wx, wy, wz, 0.08f, camera, 0.3f, 0.9f, 0.4f, 0.9f)
                        debugRenderer.drawWireframeSphere(wx, wy, wz, 0.08f, camera, 0.4f, 1f, 0.5f, 0.9f, 6, 1.5f)
                        val worldPos = org.joml.Vector3f(wx, wy, wz + 0.12f)
                        val screenPos = camera.project(worldPos, sw, sh)
                        if (screenPos.z in 0f..1f) {
                            val label = "LADDER ${slot.name.removePrefix("WALL_")}"
                            val tw = ui.textWidth(label) * 1.0f
                            ui.drawRect(screenPos.x - tw / 2f - 2f, screenPos.y - 1f, tw + 4f, 14f, 0f, 0f, 0f, 0.5f)
                            ui.drawText(label, screenPos.x - tw / 2f, screenPos.y, 0.3f, 0.95f, 0.4f, 1f, 1.0f)
                        }
                    }

                    // Draw manual door edge tags (orange spheres on edges)
                    for (slot in node.manualDoorSlots) {
                        val wx = x + 0.5f + when (slot) { TileSlot.WALL_EAST -> 0.45f; TileSlot.WALL_WEST -> -0.45f; else -> 0f }
                        val wy = y + 0.5f + when (slot) { TileSlot.WALL_NORTH -> 0.45f; TileSlot.WALL_SOUTH -> -0.45f; else -> 0f }
                        val wz = z + 0.7f
                        debugRenderer.drawFilledSphere(wx, wy, wz, 0.09f, camera, 1f, 0.55f, 0.1f, 0.9f)
                        debugRenderer.drawWireframeSphere(wx, wy, wz, 0.09f, camera, 1f, 0.7f, 0.2f, 0.9f, 6, 1.5f)
                        val worldPos = org.joml.Vector3f(wx, wy, wz + 0.14f)
                        val screenPos = camera.project(worldPos, sw, sh)
                        if (screenPos.z in 0f..1f) {
                            val label = "MANUAL ${slot.name.removePrefix("WALL_")}"
                            val tw = ui.textWidth(label) * 1.0f
                            ui.drawRect(screenPos.x - tw / 2f - 2f, screenPos.y - 1f, tw + 4f, 14f, 0f, 0f, 0f, 0.5f)
                            ui.drawText(label, screenPos.x - tw / 2f, screenPos.y, 1f, 0.7f, 0.2f, 1f, 1.0f)
                        }
                    }

                    // Draw socket edge tags (cyan spheres on edges)
                    for (slot in node.socketSlots) {
                        val wx = x + 0.5f + when (slot) { TileSlot.WALL_EAST -> 0.48f; TileSlot.WALL_WEST -> -0.48f; else -> 0f }
                        val wy = y + 0.5f + when (slot) { TileSlot.WALL_NORTH -> 0.48f; TileSlot.WALL_SOUTH -> -0.48f; else -> 0f }
                        val wz = z + 0.5f
                        debugRenderer.drawFilledSphere(wx, wy, wz, 0.10f, camera, 0.2f, 0.8f, 1f, 0.9f)
                        debugRenderer.drawWireframeSphere(wx, wy, wz, 0.10f, camera, 0.4f, 0.9f, 1f, 0.95f, 8, 1.5f)
                        val worldPos = org.joml.Vector3f(wx, wy, wz + 0.16f)
                        val screenPos = camera.project(worldPos, sw, sh)
                        if (screenPos.z in 0f..1f) {
                            val label = "SOCKET ${slot.name.removePrefix("WALL_")}"
                            val tw = ui.textWidth(label) * 1.0f
                            ui.drawRect(screenPos.x - tw / 2f - 2f, screenPos.y - 1f, tw + 4f, 14f, 0f, 0f, 0f, 0.5f)
                            ui.drawText(label, screenPos.x - tw / 2f, screenPos.y, 0.4f, 0.9f, 1f, 1f, 1.0f)
                        }
                    }
                }
            }
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
            drawRoomDragPreview(w)

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
            val toolName = if (selectedPaletteTab == PaletteTab.TAGS) "TAG: ${selectedTag ?: "none"}" else (currentTool?.name ?: "NONE")
            ui.drawText("Tool: $toolName  Layer: $currentZ  [GPU]", hudX, hudY, 0.7f, 0.7f, 0.8f, 1f, 1f)
            val helpStr = "WASD: Pan  Shift+A/D: Rotate  Shift+W/S: Pitch  Shift+Q/E: Zoom  Z/X: Layer  Ctrl+Click: Erase  Ctrl+S: Save"
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

            // Draw wireframe edges on top of model faces (only when wireframes are enabled)
            if (showWireframes) {
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
        drawRoomDragPreview(w)

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
        val helpStr = "WASD: Pan  Shift+A/D: Rotate  Shift+W/S: Pitch  Shift+Q/E: Zoom  Z/X: Layer  Ctrl+Click: Erase  Ctrl+S: Save"
        ui.drawText(helpStr, hudX, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)
        val fileLabel = if (currentFilePath != null) File(currentFilePath!!).name else "[unsaved]"
        val azStr = "Az: ${azimuth.toInt()}  El: ${elevation.toInt()}  Dist: ${distance.toInt()}"
        val coordStr = "$fileLabel  Cursor: $cursorX, $cursorY  ${w.width}x${w.height}x${w.depth}  $azStr"
        ui.drawText(coordStr, viewportRight - ui.textWidth(coordStr), hudY, 0.6f, 0.6f, 0.7f, 0.8f, 1f)
    }

    // ---- Tooltip rendering ----

    private fun drawTooltip() {
        val text = tooltipText ?: return
        val tw = ui.textWidth(text) + 12f
        val th = 22f
        val tx = (tooltipX + 12f).coerceAtMost(ui.screenWidth - tw - 4f)
        val ty = (tooltipY - 4f).coerceAtLeast(0f)
        // Background
        ui.drawRect(tx, ty, tw, th, 0.08f, 0.08f, 0.12f, 0.92f)
        // Border
        ui.drawRect(tx, ty, tw, 1f, 0.4f, 0.45f, 0.55f, 0.8f)
        ui.drawRect(tx, ty + th - 1f, tw, 1f, 0.4f, 0.45f, 0.55f, 0.8f)
        ui.drawRect(tx, ty, 1f, th, 0.4f, 0.45f, 0.55f, 0.8f)
        ui.drawRect(tx + tw - 1f, ty, 1f, th, 0.4f, 0.45f, 0.55f, 0.8f)
        // Text
        ui.drawText(text, tx + 6f, ty + 4f, 0.9f, 0.9f, 0.95f, 1f, 1f)
        tooltipText = null
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
            tooltip = "Normal",
            iconDrawer = { x, y, s -> drawCursorIcon(x, y, s) }
        ) {
            selectedEditorMode = EditorMode.NORMAL
        }
        by += btnSize + btnPad

        // --- Show/hide grid wireframes (view-grid-outline icon) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.GRID_TOGGLE, mx, my,
            tooltip = "Toggle Grid",
            iconDrawer = { x, y, s -> drawGridIcon(x, y, s, showWireframes) }
        ) {
            selectedEditorMode = EditorMode.GRID_TOGGLE
            showWireframes = !showWireframes
        }
        by += btnSize + btnPad

        // --- Lights mode (lightbulb-on-outline icon) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.LIGHTS, mx, my,
            tooltip = "Lights Preview",
            iconDrawer = { x, y, s -> drawLightbulbIcon(x, y, s, selectedEditorMode == EditorMode.LIGHTS) }
        ) {
            selectedEditorMode = if (selectedEditorMode == EditorMode.LIGHTS) EditorMode.NORMAL else EditorMode.LIGHTS
        }
        by += btnSize + btnPad

        // --- GPU render mode (cube icon with "3D" label) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.GPU_RENDER, mx, my,
            tooltip = "GPU Render",
            iconDrawer = { x, y, s -> drawGpuIcon(x, y, s) }
        ) {
            selectedEditorMode = if (selectedEditorMode == EditorMode.GPU_RENDER) EditorMode.NORMAL else EditorMode.GPU_RENDER
        }
        by += btnSize + btnPad

        // --- Room mode (drag → cleared box with floor + ceiling + walls; Z/X resize Z extent during drag) ---
        drawModeButton(btnPad, by, btnSize, selectedEditorMode == EditorMode.ROOM, mx, my,
            tooltip = "Room (drag; Z/X during drag = fewer/more Z layers)",
            iconDrawer = { x, y, s -> drawRoomIcon(x, y, s) }
        ) {
            selectedEditorMode = if (selectedEditorMode == EditorMode.ROOM) EditorMode.NORMAL else EditorMode.ROOM
            roomDragActive = false
            roomZExtent = 0
        }
        by += btnSize + btnPad

        // --- Show/hide ceilings toggle ---
        drawModeButton(btnPad, by, btnSize, showCeilings, mx, my,
            tooltip = "Toggle Ceilings",
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
        tooltip: String = "",
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

        if (hovered) {
            if (tooltip.isNotEmpty()) {
                tooltipText = tooltip
                tooltipX = x + size
                tooltipY = y
            }
            if (inputSystem.isMouseButtonJustPressed(0)) {
                onClick()
            }
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

    /** Icon for the Room mode: a small rectangular room outline with a diagonal hint. */
    private fun drawRoomIcon(x: Float, y: Float, s: Float) {
        val on = selectedEditorMode == EditorMode.ROOM
        val col = if (on) 0.9f else 0.55f
        val a   = if (on) 0.95f else 0.7f
        val pad = s * 0.18f
        val x0 = x + pad; val y0 = y + pad
        val x1 = x + s - pad; val y1 = y + s - pad
        debugRenderer.drawLine(x0, y0, x1, y0, col, col, col + 0.05f, a, 1.6f)
        debugRenderer.drawLine(x1, y0, x1, y1, col, col, col + 0.05f, a, 1.6f)
        debugRenderer.drawLine(x1, y1, x0, y1, col, col, col + 0.05f, a, 1.6f)
        debugRenderer.drawLine(x0, y1, x0, y0, col, col, col + 0.05f, a, 1.6f)
        debugRenderer.drawLine(x0 + 2f, y0 + 2f, x1 - 2f, y1 - 2f, col * 0.75f, col * 0.8f, col * 0.9f, a * 0.7f, 1f)
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

        // --- Tab bar (horizontally scrollable, auto-sized per label) ---
        val tabY = barH + 32f
        val tabH = 24f
        val stripX = palX + 8f
        val stripW = toolsPaletteWidth - 16f
        val tabPadX = 12f            // horizontal padding inside each tab
        val tabGap  = 2f             // gap between tabs
        val textScale = 1f

        // Tab definitions: (label, tab enum, onSelect)
        data class TabDef(val label: String, val tab: PaletteTab, val onSelect: () -> Unit)
        val tabs = listOf(
            TabDef("World", PaletteTab.WORLD) {
                selectedPaletteTab = PaletteTab.WORLD
                currentTool = null
            },
            TabDef("Structures", PaletteTab.STRUCTURES) {
                selectedPaletteTab = PaletteTab.STRUCTURES
                currentTool = EditorTool.FLOOR
            },
            TabDef("Lights", PaletteTab.LIGHTS) {
                selectedPaletteTab = PaletteTab.LIGHTS
                currentTool = EditorTool.LIGHT
            },
            TabDef("Tags", PaletteTab.TAGS) {
                selectedPaletteTab = PaletteTab.TAGS
                currentTool = null
            }
        )

        // Compute each tab's width from its label width plus padding.
        val tabWidths = FloatArray(tabs.size)
        var totalWidth = 0f
        for (i in tabs.indices) {
            val w = ui.textWidth(tabs[i].label, textScale) + tabPadX * 2f
            tabWidths[i] = w
            totalWidth += w
            if (i < tabs.size - 1) totalWidth += tabGap
        }

        // Clamp horizontal scroll to the strip.
        val maxScroll = (totalWidth - stripW).coerceAtLeast(0f)
        // Mouse-wheel horizontal scrolling when the cursor is over the tab strip.
        val overStrip = mx >= stripX && mx < stripX + stripW && my >= tabY && my < tabY + tabH
        if (overStrip) {
            val scroll = inputSystem.getScrollDelta()
            if (scroll != 0f) paletteTabsScrollX -= scroll * 30f
        }
        paletteTabsScrollX = paletteTabsScrollX.coerceIn(0f, maxScroll)

        // Render tabs, hard-clipped to the strip extents. Tabs fully outside
        // the strip are skipped; tabs that straddle a strip edge have their
        // background quad clamped to the visible region and their label is
        // only drawn when it fits entirely inside the strip (avoids text
        // bleeding past the palette border onto the viewport).
        var cursorX = stripX - paletteTabsScrollX
        for (i in tabs.indices) {
            val def = tabs[i]
            val w = tabWidths[i]
            val rightEdge = cursorX + w
            // Skip fully off-screen tabs
            if (rightEdge <= stripX || cursorX >= stripX + stripW) {
                cursorX = rightEdge + tabGap
                continue
            }

            val active = selectedPaletteTab == def.tab
            // Hit test is clipped to the visible strip so clicks outside don't register.
            val visibleLeft  = maxOf(cursorX, stripX)
            val visibleRight = minOf(rightEdge, stripX + stripW)
            val hovered = mx >= visibleLeft && mx < visibleRight && my >= tabY && my < tabY + tabH

            val bgR: Float; val bgG: Float; val bgB: Float; val bgA: Float
            when {
                active  -> { bgR = 0.25f; bgG = 0.35f; bgB = 0.55f; bgA = 0.9f }
                hovered -> { bgR = 0.20f; bgG = 0.26f; bgB = 0.38f; bgA = 0.7f }
                else    -> { bgR = 0.15f; bgG = 0.17f; bgB = 0.22f; bgA = 0.6f }
            }
            // Draw only the clipped portion of the tab's background.
            ui.drawRect(visibleLeft, tabY, visibleRight - visibleLeft, tabH, bgR, bgG, bgB, bgA)

            // Draw the label only when the tab is fully visible (no scissor
            // available to clip text glyphs).
            val labelX = cursorX + tabPadX
            val labelW = ui.textWidth(def.label, textScale)
            if (labelX >= stripX && labelX + labelW <= stripX + stripW) {
                ui.drawText(def.label, labelX, tabY + 5f, 0.82f, 0.82f, 0.9f, 1f, textScale)
            }

            if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
                def.onSelect()
            }
            cursorX = rightEdge + tabGap
        }

        // Tab underline (full strip)
        ui.drawRect(stripX, tabY + tabH, stripW, 1f, 0.3f, 0.35f, 0.45f, 0.7f)

        // Scroll indicator chevrons when content overflows
        if (maxScroll > 0f) {
            val chevColor = 0.55f
            if (paletteTabsScrollX > 0.5f) {
                ui.drawText("<", stripX, tabY + 5f, chevColor, chevColor, chevColor + 0.1f, 1f, 1f)
            }
            if (paletteTabsScrollX < maxScroll - 0.5f) {
                ui.drawText(">", stripX + stripW - 8f, tabY + 5f, chevColor, chevColor, chevColor + 0.1f, 1f, 1f)
            }
        }

        var by = tabY + tabH + 8f

        when (selectedPaletteTab) {
            PaletteTab.WORLD -> {
                ui.drawText("World size", palX + 10f, by, 0.7f, 0.75f, 0.85f, 1f, 1.1f)
                by += 22f
                // Three sliders. Values are integer in [1, 255]; whenever the
                // user moves a slider we snap to the nearest positive multiple
                // of 3 (the World class requires this) and call setSize on the
                // world. Sliders are draggable; releasing the mouse button or
                // moving the cursor off any slider commits the new size.
                val w0 = world
                if (w0 != null) {
                    by = drawWorldSizeSlider("X", 'X', w0.width,  palX, by, btnW, mx, my)
                    by += 4f
                    by = drawWorldSizeSlider("Y", 'Y', w0.height, palX, by, btnW, mx, my)
                    by += 4f
                    by = drawWorldSizeSlider("Z", 'Z', w0.depth,  palX, by, btnW, mx, my)
                }
                // Release the drag when the mouse button is released anywhere.
                if (!inputSystem.isMouseButtonPressed(0)) draggingWorldSlider = null

                by += 8f
                ui.drawText("Range: 1 - 255 (snapped to 3)", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 0.9f)
                by += 16f
            }
            PaletteTab.STRUCTURES -> {
                val previewSize = 64f
                val previewPad = 8f

                // --- Floor section ---
                ui.drawText("Floor", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Floor model preview button
                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, floorMesh,
                    EditorTool.FLOOR, "Floor", 0.25f, 0.30f, 0.40f, mx, my)
                by += previewSize + previewPad

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Ceiling section ---
                ui.drawText("Ceiling", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Ceiling model preview button
                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, ceilingMesh,
                    EditorTool.CEILING, "Ceiling", 0.25f, 0.30f, 0.40f, mx, my)
                by += previewSize + previewPad

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Walls section ---
                ui.drawText("Walls", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                // Wall model preview button
                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, wallMesh,
                    EditorTool.WALL, "Wall", 0.55f, 0.42f, 0.30f, mx, my)
                by += previewSize + previewPad

                // Wall-doorway preview button (a wall with an opening cut into it).
                // Placed exactly like a normal wall (on hovered edge) but is
                // non-blocking so actors can walk through the opening.
                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, doorMesh,
                    EditorTool.WALL_DOORWAY, "Doorway", 0.55f, 0.42f, 0.30f, mx, my)
                by += previewSize + previewPad

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Door section ---
                ui.drawText("Door", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, doorMesh,
                    EditorTool.DOOR, "Door", 0.45f, 0.50f, 0.35f, mx, my)
                by += previewSize + previewPad

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Ladder section ---
                ui.drawText("Ladder", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, ladderMesh,
                    EditorTool.LADDER, "Ladder", 0.50f, 0.38f, 0.25f, mx, my)
                by += previewSize + previewPad

                // Ladder rotation control (only when ladder tool is selected)
                if (currentTool == EditorTool.LADDER) {
                    val rotLabel = when (ladderRotation) {
                        0f -> "North"; 90f -> "East"; 180f -> "South"; 270f -> "West"; else -> "${ladderRotation.toInt()}°"
                    }
                    ui.drawText("Facing: $rotLabel", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1f)
                    by += 18f

                    val rotBtnW = 50f
                    val rotBtnH = 22f
                    val rotBtnX = palX + 14f
                    val rotHovered = mx >= rotBtnX && mx < rotBtnX + rotBtnW && my >= by && my < by + rotBtnH
                    if (rotHovered) {
                        ui.drawRect(rotBtnX, by, rotBtnW, rotBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                    } else {
                        ui.drawRect(rotBtnX, by, rotBtnW, rotBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                    }
                    ui.drawText("Rotate", rotBtnX + 4f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1f)
                    if (rotHovered && inputSystem.isMouseButtonJustPressed(0)) {
                        ladderRotation = (ladderRotation + 90f) % 360f
                    }
                    by += rotBtnH + 4f

                    ui.drawText("R key to rotate", palX + 14f, by, 0.45f, 0.5f, 0.6f, 0.6f, 0.9f)
                    by += 16f
                }

                // Separator
                ui.drawRect(palX + 8f, by, btnW, 1f, 0.3f, 0.35f, 0.45f, 0.4f)
                by += 8f

                // --- Stairs section ---
                ui.drawText("Stairs", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
                by += 18f

                drawModelPreviewButton(palX + 8f, by, btnW.coerceAtMost(previewSize + 16f), previewSize, stairsMesh,
                    EditorTool.STAIRS, "Stairs", 0.45f, 0.40f, 0.35f, mx, my)
                by += previewSize + previewPad

                // Stairs rotation control (only when stairs tool is selected)
                if (currentTool == EditorTool.STAIRS) {
                    val rotLabel = when (stairsRotation) {
                        0f -> "North"; 90f -> "East"; 180f -> "South"; 270f -> "West"; else -> "${stairsRotation.toInt()}°"
                    }
                    ui.drawText("Facing: $rotLabel", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1f)
                    by += 18f

                    val rotBtnW = 50f
                    val rotBtnH = 22f
                    val rotBtnX = palX + 14f
                    val rotHovered = mx >= rotBtnX && mx < rotBtnX + rotBtnW && my >= by && my < by + rotBtnH
                    if (rotHovered) {
                        ui.drawRect(rotBtnX, by, rotBtnW, rotBtnH, 0.25f, 0.28f, 0.4f, 0.8f)
                    } else {
                        ui.drawRect(rotBtnX, by, rotBtnW, rotBtnH, 0.18f, 0.2f, 0.28f, 0.7f)
                    }
                    ui.drawText("Rotate", rotBtnX + 4f, by + 4f, 0.9f, 0.9f, 0.95f, 1f, 1f)
                    if (rotHovered && inputSystem.isMouseButtonJustPressed(0)) {
                        stairsRotation = (stairsRotation + 90f) % 360f
                    }
                    by += rotBtnH + 4f

                    ui.drawText("R key to rotate", palX + 14f, by, 0.45f, 0.5f, 0.6f, 0.6f, 0.9f)
                    by += 16f
                }

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

                val editingLight = if (selectedLightIndex in 0 until w.lightSources.size) {
                    w.lightSources[selectedLightIndex]
                } else null

                val displayRadius = editingLight?.radius ?: defaultLightRadius
                val displayIntensity = editingLight?.intensity ?: defaultLightIntensity

                ui.drawText("Radius: ${"%.1f".format(displayRadius)}", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1.1f)
                by += 20f

                val ctrlBtnW = 36f
                val ctrlBtnH = 22f
                val ctrlSpacing = 4f

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

                ui.drawText("Intensity: ${"%.1f".format(displayIntensity)}", palX + 14f, by, 0.7f, 0.75f, 0.85f, 1f, 1.1f)
                by += 20f

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

                if (editingLight != null) {
                    ui.drawText("Selected: Light #${selectedLightIndex + 1}", palX + 14f, by, 0.55f, 0.6f, 0.7f, 0.8f, 1f)
                } else {
                    ui.drawText("No light selected", palX + 14f, by, 0.45f, 0.5f, 0.6f, 0.6f, 1f)
                }
                by += 20f

                ui.drawText("Lights: ${w.lightSources.size}", palX + 14f, by, 0.55f, 0.6f, 0.7f, 0.8f, 1f)
                by += 20f

                ui.drawText("Ctrl+Click to remove", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 0.9f)
                by += 16f
            }

            PaletteTab.TAGS -> {
                renderTagsTab(w, palX, by, btnW, btnH, mx, my)
                return@renderToolsPalette  // layer info handled inside
            }
        }

        // Layer info
        by += 12f
        ui.drawText("Layer: $currentZ / ${w.depth - 1}", palX + 8f, by, 0.6f, 0.65f, 0.75f, 1f, 1.1f)
        by += 20f
        ui.drawText("Z/X to change layer", palX + 8f, by, 0.45f, 0.5f, 0.6f, 0.7f, 1f)
    }

    /**
     * Render the Tags tab content showing all WorldNode.Tags as toggle buttons.
     * Clicking a tag with a node selected adds/removes the tag from that node.
     */
    private fun renderTagsTab(w: World, palX: Float, startY: Float, btnW: Float, btnH: Float, mx: Float, my: Float) {
        var by = startY

        ui.drawText("Node Tags", palX + 10f, by, 0.6f, 0.65f, 0.8f, 0.9f, 1.1f)
        by += 20f

        // Get current node
        val currentNode = if (cursorX in 0 until w.width && cursorY in 0 until w.height) {
            w.getNode(cursorX, cursorY, currentZ)
        } else null

        // List all available tags from WorldNode.Tags
        val allTags = listOf(
            WorldNode.Tags.PLAYER_SPAWN,
            WorldNode.Tags.ENEMY_SPAWN,
            WorldNode.Tags.ITEM_SPAWN,
            WorldNode.Tags.EXIT,
            WorldNode.Tags.DOOR_MANUAL,
            WorldNode.Tags.SOCKET,
            WorldNode.Tags.LADDER,
            WorldNode.Tags.STAIRS
        )

        ui.drawText("Select tag to place:", palX + 10f, by, 0.5f, 0.55f, 0.65f, 0.8f, 0.9f)
        by += 16f

        for (tag in allTags) {
            val isSelected = selectedTag == tag
            val nodeHasTag = when (tag) {
                WorldNode.Tags.DOOR_MANUAL -> currentNode?.manualDoorSlots?.isNotEmpty() == true
                WorldNode.Tags.LADDER -> currentNode?.ladderSlots?.isNotEmpty() == true
                WorldNode.Tags.SOCKET -> currentNode?.socketSlots?.isNotEmpty() == true
                else -> currentNode?.tags?.contains(tag) == true
            }
            val hovered = mx >= palX + 8f && mx < palX + 8f + btnW && my >= by && my < by + btnH

            // Draw tag button — highlight if selected as active brush
            if (isSelected) {
                ui.drawRect(palX + 8f, by, btnW, btnH, 0.25f, 0.40f, 0.60f, 0.9f)
            } else if (hovered) {
                ui.drawRect(palX + 8f, by, btnW, btnH, 0.2f, 0.26f, 0.38f, 0.7f)
            } else {
                ui.drawRect(palX + 8f, by, btnW, btnH, 0.15f, 0.17f, 0.22f, 0.6f)
            }

            // Display name: convert "player_spawn" → "Player Spawn"
            val displayName = tag.replace('_', ' ').split(' ').joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercaseChar() }
            }
            val marker = if (nodeHasTag) "● " else "  "
            val selMarker = if (isSelected) "▸" else " "
            ui.drawText("$selMarker$marker$displayName", palX + 12f, by + 5f, 0.82f, 0.82f, 0.9f, 1f, 1f)

            if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
                selectedTag = if (isSelected) null else tag
            }

            by += btnH + 4f
        }

        by += 8f
        // Show current node info
        if (currentNode != null) {
            val tagCount = currentNode.tags.size
            ui.drawText("Node ($cursorX, $cursorY, $currentZ)", palX + 10f, by, 0.55f, 0.6f, 0.7f, 0.8f, 1f)
            by += 16f
            ui.drawText("Active tags: $tagCount", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 1f)
        } else {
            ui.drawText("No node selected", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.6f, 1f)
        }
        by += 20f

        ui.drawText("Click tag to toggle", palX + 10f, by, 0.45f, 0.5f, 0.6f, 0.7f, 0.9f)
        by += 24f

        // Layer info
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
            currentTool = if (selected) null else tool
        }
    }

    /**
     * Draw a model preview button in the palette. Renders a small 3D preview of the mesh
     * using projected wireframe within the button bounds.
     */
    /**
     * Draw a labelled horizontal slider for one of the world's three size
     * axes. Shows the current value, supports click-and-drag, and applies
     * any change immediately by calling [World.setSize] (snapping the slider
     * value to a positive multiple of 3). Returns the next `by` cursor y.
     */
    private fun drawWorldSizeSlider(
        label: String, axis: Char, currentValue: Int,
        palX: Float, by: Float, btnW: Float, mx: Float, my: Float
    ): Float {
        val w0 = world ?: return by
        val rowH = 22f
        val labelW = 18f
        val valueW = 36f
        val stepBtnW = 18f
        val stepBtnH = 18f
        val stepBtnY = by + 2f
        val minusX = palX + 10f + labelW + 4f
        val trackX = minusX + stepBtnW + 4f
        val trackY = by + 8f
        // Reserve room for: label, minus btn, track, plus btn, value text
        val trackW = btnW - labelW - valueW - stepBtnW * 2f - 24f
        val plusX = trackX + trackW + 4f
        val trackH = 6f
        val sliderMin = 1
        val sliderMax = 255

        // Snap helper: nearest positive multiple of 3
        fun snapTo3(v: Int): Int = ((v + 1) / 3).coerceAtLeast(1) * 3

        // -- "-" button: decrement by 3 --
        if (ui.button("-", minusX, stepBtnY, stepBtnW, stepBtnH, inputSystem)) {
            val newVal = snapTo3((currentValue - 3).coerceAtLeast(sliderMin))
            val newW = if (axis == 'X') newVal else w0.width
            val newH = if (axis == 'Y') newVal else w0.height
            val newD = if (axis == 'Z') newVal else w0.depth
            try { w0.setSize(newW, newH, newD) } catch (_: Exception) {}
            cursorX = cursorX.coerceAtMost(w0.width - 1)
            cursorY = cursorY.coerceAtMost(w0.height - 1)
            currentZ = currentZ.coerceAtMost(w0.depth - 1)
        }

        // Track background
        ui.drawRect(trackX, trackY, trackW, trackH, 0.12f, 0.14f, 0.18f, 0.9f)
        ui.drawRect(trackX, trackY, trackW, 1f, 0.3f, 0.35f, 0.45f, 0.7f)

        // Compute slider position from current value
        val tCur = ((currentValue - sliderMin).toFloat() / (sliderMax - sliderMin).toFloat()).coerceIn(0f, 1f)
        val handleW = 8f
        val handleX = trackX + tCur * trackW - handleW / 2f
        val handleY = trackY - 5f
        val handleH = trackH + 10f

        // Hit-test the track for click/drag
        val overTrack = mx >= trackX && mx <= trackX + trackW && my >= handleY && my <= handleY + handleH
        if (overTrack && inputSystem.isMouseButtonJustPressed(0)) draggingWorldSlider = axis

        // Compute target value while dragging
        var targetValue = currentValue
        if (draggingWorldSlider == axis && inputSystem.isMouseButtonPressed(0)) {
            val t = ((mx - trackX) / trackW).coerceIn(0f, 1f)
            val raw = sliderMin + (t * (sliderMax - sliderMin)).toInt()
            targetValue = raw.coerceIn(sliderMin, sliderMax)
        }

        // Snap to nearest positive multiple of 3 (World requires this).
        val snapped = snapTo3(targetValue)

        // Apply size change immediately
        if (snapped != currentValue) {
            val newW = if (axis == 'X') snapped else w0.width
            val newH = if (axis == 'Y') snapped else w0.height
            val newD = if (axis == 'Z') snapped else w0.depth
            try { w0.setSize(newW, newH, newD) } catch (_: Exception) { /* invalid, ignore */ }
            // Keep the cursor/camera inside the new bounds
            cursorX = cursorX.coerceAtMost(w0.width - 1)
            cursorY = cursorY.coerceAtMost(w0.height - 1)
            currentZ = currentZ.coerceAtMost(w0.depth - 1)
        }

        // Handle fill (active portion of the track)
        val tNow = ((w0.let { if (axis == 'X') it.width else if (axis == 'Y') it.height else it.depth } - sliderMin)
            .toFloat() / (sliderMax - sliderMin).toFloat()).coerceIn(0f, 1f)
        ui.drawRect(trackX, trackY, tNow * trackW, trackH, 0.30f, 0.45f, 0.70f, 0.9f)

        // Handle thumb
        val handleHovered = mx >= handleX && mx <= handleX + handleW && my >= handleY && my <= handleY + handleH
        val hr = if (handleHovered || draggingWorldSlider == axis) 0.6f else 0.45f
        ui.drawRect(handleX, handleY, handleW, handleH, hr, hr + 0.05f, hr + 0.15f, 1f)

        // -- "+" button: increment by 3 --
        // Read the (possibly updated) current size for this axis so the
        // click reflects what the user sees.
        val postCurrent = when (axis) { 'X' -> w0.width; 'Y' -> w0.height; else -> w0.depth }
        if (ui.button("+", plusX, stepBtnY, stepBtnW, stepBtnH, inputSystem)) {
            val newVal = snapTo3((postCurrent + 3).coerceAtMost(sliderMax))
            val newW = if (axis == 'X') newVal else w0.width
            val newH = if (axis == 'Y') newVal else w0.height
            val newD = if (axis == 'Z') newVal else w0.depth
            try { w0.setSize(newW, newH, newD) } catch (_: Exception) {}
            cursorX = cursorX.coerceAtMost(w0.width - 1)
            cursorY = cursorY.coerceAtMost(w0.height - 1)
            currentZ = currentZ.coerceAtMost(w0.depth - 1)
        }

        // Label and value text
        ui.drawText(label, palX + 10f, by + 4f, 0.85f, 0.85f, 0.9f, 1f, 1f)
        val shown = when (axis) {
            'X' -> w0.width; 'Y' -> w0.height; else -> w0.depth
        }
        ui.drawText(shown.toString(), plusX + stepBtnW + 6f, by + 4f, 0.85f, 0.85f, 0.9f, 1f, 1f)

        return by + rowH
    }

    private fun drawModelPreviewButton(
        x: Float, y: Float, w: Float, h: Float,
        mesh: MeshData?, tool: EditorTool, label: String,
        colorR: Float, colorG: Float, colorB: Float,
        mx: Float, my: Float
    ) {
        val selected = currentTool == tool
        val hovered = mx >= x && mx < x + w && my >= y && my < y + h

        // Background
        if (selected) {
            ui.drawRect(x, y, w, h, 0.3f, 0.42f, 0.65f, 0.9f)
        } else if (hovered) {
            ui.drawRect(x, y, w, h, 0.2f, 0.26f, 0.38f, 0.7f)
        } else {
            ui.drawRect(x, y, w, h, 0.15f, 0.17f, 0.22f, 0.6f)
        }

        // Border
        val bc = if (selected) 0.55f else 0.25f
        ui.drawRect(x, y, w, 1f, bc, bc + 0.1f, bc + 0.15f, 0.8f)
        ui.drawRect(x, y + h - 1f, w, 1f, bc, bc + 0.1f, bc + 0.15f, 0.8f)
        ui.drawRect(x, y, 1f, h, bc, bc + 0.1f, bc + 0.15f, 0.8f)
        ui.drawRect(x + w - 1f, y, 1f, h, bc, bc + 0.1f, bc + 0.15f, 0.8f)

        if (mesh != null) {
            // Draw a small wireframe preview of the model
            drawMeshPreview(mesh, x + 4f, y + 4f, w - 8f, h - 20f, colorR, colorG, colorB)
        }

        // Label below the preview
        ui.drawText(label, x + 6f, y + h - 16f, 0.75f, 0.75f, 0.85f, 0.9f, 0.9f)

        if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
            currentTool = if (selected) null else tool
        }
    }

    /**
     * Render a small wireframe preview of a MeshData within the given screen rectangle.
     * Uses a fixed isometric-like projection to show the model shape.
     */
    private fun drawMeshPreview(
        mesh: MeshData,
        px: Float, py: Float, pw: Float, ph: Float,
        r: Float, g: Float, b: Float
    ) {
        val verts = mesh.vertices
        val indices = mesh.indices
        val scale = mesh.scale
        val cx = mesh.center.x
        val cy = mesh.center.y
        val cz = mesh.center.z

        // Fixed preview rotation angles
        val azRad = Math.toRadians(35.0).toFloat()
        val elRad = Math.toRadians(30.0).toFloat()
        val cosA = cos(azRad).toFloat()
        val sinA = sin(azRad).toFloat()
        val cosE = cos(elRad).toFloat()
        val sinE = sin(elRad).toFloat()

        // Project model vertex to 2D preview space
        fun projectVert(idx: Int): Pair<Float, Float> {
            val vi = idx * 6
            val mx2 = (verts[vi] - cx) * scale
            val my2 = (verts[vi + 1] - cy) * scale
            val mz = (verts[vi + 2] - cz) * scale
            // Swap Y↔Z (model Y-up → world Z-up)
            val wx = mx2; val wy = mz; val wz = my2
            // Rotate
            val rx = wx * cosA + wy * sinA
            val ry = -wx * sinA + wy * cosA
            val fz = ry * sinE + wz * cosE

            val screenX = px + pw / 2f + rx * pw * 0.7f
            val screenY = py + ph / 2f - fz * ph * 0.7f  // Y-up on screen → subtract
            return screenX to screenY
        }

        // Draw triangles as wireframe edges
        val meshColors = mesh.colors
        val drawnEdges = mutableSetOf<Long>()
        var i = 0
        while (i < indices.size - 2) {
            val i0 = indices[i].toInt() and 0xFFFF
            val i1 = indices[i + 1].toInt() and 0xFFFF
            val i2 = indices[i + 2].toInt() and 0xFFFF

            for ((a, b2) in listOf(i0 to i1, i1 to i2, i2 to i0)) {
                val edgeKey = if (a < b2) a.toLong() shl 32 or b2.toLong() else b2.toLong() shl 32 or a.toLong()
                if (drawnEdges.add(edgeKey)) {
                    val (x1, y1) = projectVert(a)
                    val (x2, y2) = projectVert(b2)
                    // Use palette color of first vertex if available, otherwise use flat color
                    val er = if (meshColors != null) meshColors[a * 3] else r + 0.2f
                    val eg = if (meshColors != null) meshColors[a * 3 + 1] else g + 0.2f
                    val eb = if (meshColors != null) meshColors[a * 3 + 2] else b + 0.2f
                    debugRenderer.drawLine(x1, y1, x2, y2, er, eg, eb, 0.7f, 1f)
                }
            }
            i += 3
        }
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

    /**
     * Draw the wireframe preview of the in-progress Room mode selection.
     * Called from both rendering paths (GPU and CPU) right after the cursor
     * highlight so it appears on top of the world.
     */
    private fun drawRoomDragPreview(w: World) {
        if (selectedEditorMode != EditorMode.ROOM || !roomDragActive) return
        val endX = cursorX.coerceIn(0, w.width - 1)
        val endY = cursorY.coerceIn(0, w.height - 1)
        val otherZ = (roomAnchorZ + roomZExtent).coerceIn(0, w.depth - 1)
        val zLoI = minOf(roomAnchorZ, otherZ)
        val zHiI = maxOf(roomAnchorZ, otherZ)
        val xLo = minOf(roomAnchorX, endX).toFloat()
        val xHi = maxOf(roomAnchorX, endX).toFloat() + 1f
        val yLo = minOf(roomAnchorY, endY).toFloat()
        val yHi = maxOf(roomAnchorY, endY).toFloat() + 1f
        val zLo = zLoI.toFloat()
        val zHi = zHiI.toFloat() + 1f
        // Red preview when Ctrl is held → release will REMOVE the box's
        // contents instead of building a room.
        val ctrlHeld = inputSystem.isKeyPressed(GLFW_KEY_LEFT_CONTROL) || inputSystem.isKeyPressed(GLFW_KEY_RIGHT_CONTROL)
        val pr: Float; val pg: Float; val pb: Float
        if (ctrlHeld) { pr = 1.0f; pg = 0.3f; pb = 0.3f } else { pr = 0.2f; pg = 0.9f; pb = 1.0f }
        debugRenderer.drawWireframeBox(
            xLo, yLo, zLo,
            xHi - xLo, yHi - yLo, zHi - zLo,
            camera,
            pr, pg, pb, 0.9f, 2f
        )
        // Footprint outline on the bottom Z of the (possibly extended-down) box
        debugRenderer.drawWireframeBox(
            xLo, yLo, zLo,
            xHi - xLo, yHi - yLo, 0.02f,
            camera,
            pr, pg, pb, 0.55f, 1f
        )
    }

    /**
     * Build a room box in [w] over the inclusive cell range
     * [x0..x1, y0..y1, z0..z1].
     *
     * 1. Every node in the box is fully cleared first (removes any existing
     *    walls, floors, doors, ceilings, ladders, tags, etc.).
     * 2. The bottom Z layer (z == z0) gets floor tiles in every cell.
     * 3. The top Z layer (z == z1) gets ceiling tiles in every cell.
     * 4. Every perimeter cell on the XY rectangle gets the appropriate wall
     *    tile on the slot facing outward.
     */
    private fun buildRoom(
        w: World,
        x0: Int, x1: Int,
        y0: Int, y1: Int,
        z0: Int, z1: Int
    ) {
        val xLo = x0.coerceAtLeast(0); val xHi = x1.coerceAtMost(w.width - 1)
        val yLo = y0.coerceAtLeast(0); val yHi = y1.coerceAtMost(w.height - 1)
        val zLo = z0.coerceAtLeast(0); val zHi = z1.coerceAtMost(w.depth - 1)
        if (xHi < xLo || yHi < yLo || zHi < zLo) return

        // 1) Clear every node in the box first (interior wipe).
        for (z in zLo..zHi) {
            for (x in xLo..xHi) {
                for (y in yLo..yHi) {
                    w.getNode(x, y, z)?.clear()
                }
            }
        }

        // 2) Floor / ceiling / perimeter walls.
        for (z in zLo..zHi) {
            for (x in xLo..xHi) {
                for (y in yLo..yHi) {
                    val node = w.getNode(x, y, z) ?: continue
                    if (z == zLo) node.setTile(FloorTile())
                    if (z == zHi) node.setTile(CeilingTile())
                    if (y == yHi) node.setTile(WallNorthTile())
                    if (y == yLo) node.setTile(WallSouthTile())
                    if (x == xHi) node.setTile(WallEastTile())
                    if (x == xLo) node.setTile(WallWestTile())
                }
            }
        }
    }

    /**
     * Room-removal counterpart to [buildRoom]: clears every node inside the
     * inclusive cell range (removing all tiles, tags, items, ladder/door/
     * socket slots) and removes every light source whose centre falls inside
     * the same world-space AABB. Triggered by releasing the room-mode drag
     * while CTRL is held.
     */
    private fun clearRoom(
        w: World,
        x0: Int, x1: Int,
        y0: Int, y1: Int,
        z0: Int, z1: Int
    ) {
        val xLo = x0.coerceAtLeast(0); val xHi = x1.coerceAtMost(w.width - 1)
        val yLo = y0.coerceAtLeast(0); val yHi = y1.coerceAtMost(w.height - 1)
        val zLo = z0.coerceAtLeast(0); val zHi = z1.coerceAtMost(w.depth - 1)
        if (xHi < xLo || yHi < yLo || zHi < zLo) return

        // 1) Strip all nodes inside the box.
        for (z in zLo..zHi) {
            for (x in xLo..xHi) {
                for (y in yLo..yHi) {
                    w.getNode(x, y, z)?.clear()
                }
            }
        }

        // 2) Remove lights whose centre falls in the world-space AABB. The
        //    box's world extents are [xLo, xHi+1) × [yLo, yHi+1) × [zLo, zHi+1).
        val fxLo = xLo.toFloat();           val fxHi = xHi.toFloat() + 1f
        val fyLo = yLo.toFloat();           val fyHi = yHi.toFloat() + 1f
        val fzLo = zLo.toFloat();           val fzHi = zHi.toFloat() + 1f
        val removed = w.lightSources.removeAll { ls ->
            ls.x in fxLo..fxHi && ls.y in fyLo..fyHi && ls.z in fzLo..fzHi
        }
        if (removed) {
            // Selected-light index may now be invalid; reset.
            selectedLightIndex = -1
        }

        // 3) Drop any associations that referenced cleared nodes (the
        //    associations list stores WorldNode references; cleared nodes
        //    still exist but no longer carry meaningful content, so
        //    associations to/from them become stale).
        w.associations.removeAll { a ->
            (a.source.x in xLo..xHi && a.source.y in yLo..yHi && a.source.z in zLo..zHi) ||
            (a.target.x in xLo..xHi && a.target.y in yLo..yHi && a.target.z in zLo..zHi)
        }

        // 4) Drop props that fall inside the box.
        w.props.removeAll { p ->
            p.x in fxLo..fxHi && p.y in fyLo..fyHi && p.z in fzLo..fzHi
        }
    }

    fun resize(width: Int, height: Int) {
        camera.resize(width, height)
    }

    fun dispose() {
        world = null
    }
}
