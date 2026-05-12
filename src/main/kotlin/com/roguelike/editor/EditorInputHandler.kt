package com.roguelike.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.Prop
import com.roguelike.utils.ModelLoader
import com.roguelike.world.BaseTile

/**
 * Handles input for the map editor.
 *
 * Camera controls:
 *  - Middle mouse drag → pan camera
 *  - Right mouse drag → rotate camera
 *  - Scroll wheel → zoom in/out
 *
 * Editing:
 *  - Left click on node → select node
 *  - Left click on edge → select edge (for wall/door placement)
 *  - Left click with palette selection → paint
 *  - Ctrl + Left click → erase
 */
class EditorInputHandler(
    private val getWorld: () -> World,
    private val modelLoader: ModelLoader,
    private val palette: EditorPalettePanel,
    private val onCameraOrbit: (dx: Float, dy: Float) -> Unit,
    private val onCameraPan: (dx: Float, dy: Float) -> Unit,
    private val onCameraZoom: (amount: Float) -> Unit,
    private val onUpdatePaletteHighlights: () -> Unit
) {
    var selectedX = -1
    var selectedY = -1
    var selectedZ = -1

    /** Currently selected edge (wall slot) on the selected node, or null for whole-node selection. */
    var selectedEdge: TileSlot? = null

    /** Currently selected prop for movement/deletion. */
    var selectedProp: Prop? = null
    private var isDraggingProp = false

    /** Active building tool mode. */
    var toolMode: EditorToolMode = EditorToolMode.NONE

    /** Room tool drag state. */
    var roomDragStartX = -1
    var roomDragStartY = -1
    var roomDragEndX = -1
    var roomDragEndY = -1
    var isRoomDragging = false
    /** True if Ctrl was held when the room drag started (subtraction mode). */
    var isRoomSubtract = false

    private var lastPaintX = -1
    private var lastPaintY = -1
    private var lastPaintZ = -1

    fun handleInput(
        delta: Float,
        hoveredX: Int, hoveredY: Int, hoveredZ: Int,
        hoveredEdge: TileSlot?
    ) {
        val dragging = Math.abs(Gdx.input.deltaX) > 1 || Math.abs(Gdx.input.deltaY) > 1

        // Middle mouse drag → pan camera
        if (Gdx.input.isButtonPressed(Input.Buttons.MIDDLE) && dragging) {
            onCameraPan(Gdx.input.deltaX.toFloat(), Gdx.input.deltaY.toFloat())
            return
        }

        // Right mouse drag → rotate camera
        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT) && dragging) {
            onCameraOrbit(Gdx.input.deltaX.toFloat(), Gdx.input.deltaY.toFloat())
            return
        }

        val isCtrlHeld = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
        val world = getWorld()

        // ── Fill tool (requires floor tile selected in palette) ──────────
        if (toolMode == EditorToolMode.FILL && palette.paletteSelection is PaletteSelection.FloorSel
            && Gdx.input.justTouched()
            && Gdx.input.isButtonPressed(Input.Buttons.LEFT) && hoveredX != -1) {
            if (isCtrlHeld) {
                floodErase(world, hoveredX, hoveredY, hoveredZ)
            } else {
                floodFill(world, hoveredX, hoveredY, hoveredZ)
            }
            return
        }

        // ── Room tool (requires wall tile selected in palette) ────────────
        // Normal drag = Addition/Merge, Ctrl+drag = Subtraction/Carve
        if (toolMode == EditorToolMode.ROOM && palette.paletteSelection is PaletteSelection.WallSel && hoveredX != -1) {
            if (Gdx.input.justTouched() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                roomDragStartX = hoveredX
                roomDragStartY = hoveredY
                roomDragEndX = hoveredX
                roomDragEndY = hoveredY
                isRoomDragging = true
                isRoomSubtract = isCtrlHeld
                return
            }
            if (isRoomDragging && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                roomDragEndX = hoveredX
                roomDragEndY = hoveredY
                return
            }
            if (isRoomDragging && !Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                // Mouse released — execute room tool
                roomDragEndX = hoveredX
                roomDragEndY = hoveredY
                if (isRoomSubtract) {
                    executeRoomSubtract(world, hoveredZ)
                } else {
                    executeRoomAdd(world, hoveredZ)
                }
                isRoomDragging = false
                isRoomSubtract = false
                roomDragStartX = -1; roomDragStartY = -1
                roomDragEndX = -1; roomDragEndY = -1
                return
            }
        }

        // ── Ctrl + LMB: erase ──────────────────────────────────────────────
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isCtrlHeld
            && palette.paletteSelection != null && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ)
            when (val sel = palette.paletteSelection) {
                is PaletteSelection.FloorSel -> {
                    node?.removeTile(TileSlot.FLOOR)
                }
                is PaletteSelection.WallSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                        node?.removeTile(hoveredEdge)
                        node?.untagDoor(hoveredEdge)
                    }
                }
                is PaletteSelection.DoorSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                        node?.removeTile(hoveredEdge)
                        node?.untagDoor(hoveredEdge)
                    }
                }
                is PaletteSelection.StairsSel -> {
                    val oldTile = node?.removeTile(TileSlot.STAIRS)
                    if (oldTile != null) modelLoader.renderRegistry.remove(oldTile)
                }
                is PaletteSelection.LadderSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                        val oldTile = node?.removeTile(TileSlot.STAIRS)
                        if (oldTile != null) modelLoader.renderRegistry.remove(oldTile)
                    }
                }
                is PaletteSelection.TagSel -> {
                    val n = world.getNode(hoveredX, hoveredY, hoveredZ)
                    if (n != null) {
                        if (sel.tag == com.roguelike.core.model.WorldNode.Tags.DOOR_MANUAL) {
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                                n.untagManualDoor(hoveredEdge)
                            }
                        } else if (sel.tag == com.roguelike.core.model.WorldNode.Tags.NODE_CONNECTOR) {
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                                n.untagConnector(hoveredEdge)
                            }
                        } else if (sel.tag == com.roguelike.core.model.WorldNode.Tags.LADDER) {
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                                n.untagLadder(hoveredEdge)
                            }
                        } else {
                            world.removeTag(n, sel.tag)
                        }
                        onUpdatePaletteHighlights()
                    }
                }
                null -> {}
                is PaletteSelection.DecorationSel -> {
                    val prop = findPropAt(world, hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
                    if (prop != null) {
                        world.props.remove(prop)
                        if (selectedProp == prop) selectedProp = null
                    }
                }
            }
            return
        }

        // ── Ctrl+LMB with no selection: delete prop if hovered ──────────────
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isCtrlHeld && hoveredX != -1) {
            val prop = findPropAt(world, hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
            if (prop != null) {
                world.props.remove(prop)
                if (selectedProp == prop) selectedProp = null
                return
            }
        }

        // ── Normal LMB: paint / select ─────────────────────────────────────
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ) ?: return
            val isNewNode = hoveredX != lastPaintX || hoveredY != lastPaintY || hoveredZ != lastPaintZ

            when (val sel = palette.paletteSelection) {
                is PaletteSelection.FloorSel -> {
                    if (!node.hasTile(TileSlot.FLOOR)) {
                        val tile = modelLoader.createTile(FloorTile_TYPE) ?: return
                        node.setTile(tile)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.WallSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR && !node.hasTile(hoveredEdge)) {
                        val wallType = wallTypeForSlot(hoveredEdge) ?: return
                        val tile = modelLoader.createTile(wallType) ?: return
                        node.setTile(tile)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.DoorSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                        // Remove existing wall tile on this edge before placing door
                        val oldTile = node.removeTile(hoveredEdge)
                        if (oldTile != null) {
                            modelLoader.renderRegistry.remove(oldTile)
                        }
                        val doorType = doorTypeForSlot(hoveredEdge) ?: return
                        val tile = modelLoader.createTile(doorType) ?: return
                        node.setTile(tile)
                        node.tagAsDoor(hoveredEdge)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.StairsSel -> {
                    if (!node.hasTile(TileSlot.STAIRS)) {
                        val tile = modelLoader.createTile("StairsTile") ?: return
                        node.setTile(tile)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.LadderSel -> {
                    if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR && !node.hasTile(TileSlot.STAIRS)) {
                        val tile = modelLoader.createTile("LadderTile") ?: return
                        (tile as BaseTile).rotationY = ladderRotationForEdge(hoveredEdge)
                        node.setTile(tile)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.TagSel -> {
                    if (isNewNode) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        selectedEdge = null
                        if (sel.tag == com.roguelike.core.model.WorldNode.Tags.DOOR_MANUAL) {
                            // door_manual is per-edge: require a hovered edge that is a door
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR && node.isDoor(hoveredEdge)) {
                                if (!node.isManualDoor(hoveredEdge)) {
                                    node.tagAsManualDoor(hoveredEdge)
                                } else if (Gdx.input.justTouched()) {
                                    node.untagManualDoor(hoveredEdge)
                                }
                            }
                        } else if (sel.tag == com.roguelike.core.model.WorldNode.Tags.NODE_CONNECTOR) {
                            // node_connector is per-edge: only allowed on outer world boundary walls
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                                val w = world
                                val isOuterEdge = when (hoveredEdge) {
                                    TileSlot.WALL_NORTH -> hoveredY == w.height - 1
                                    TileSlot.WALL_SOUTH -> hoveredY == 0
                                    TileSlot.WALL_EAST  -> hoveredX == w.width - 1
                                    TileSlot.WALL_WEST  -> hoveredX == 0
                                    else -> false
                                }
                                if (isOuterEdge) {
                                    if (!node.isConnector(hoveredEdge)) {
                                        node.tagAsConnector(hoveredEdge)
                                    } else if (Gdx.input.justTouched()) {
                                        node.untagConnector(hoveredEdge)
                                    }
                                }
                            }
                        } else if (sel.tag == com.roguelike.core.model.WorldNode.Tags.LADDER) {
                            // ladder is per-edge
                            if (hoveredEdge != null && hoveredEdge != TileSlot.FLOOR) {
                                if (!node.isLadder(hoveredEdge)) {
                                    node.tagAsLadder(hoveredEdge)
                                } else if (Gdx.input.justTouched()) {
                                    node.untagLadder(hoveredEdge)
                                }
                            }
                        } else if (!node.tags.contains(sel.tag)) {
                            world.addTag(node, sel.tag)
                        } else if (Gdx.input.justTouched()) {
                            world.removeTag(node, sel.tag)
                        }
                        onUpdatePaletteHighlights()
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                null -> {
                    // No palette selection → select prop or node/edge
                    if (Gdx.input.justTouched()) {
                        val prop = findPropAt(world, hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
                        if (prop != null) {
                            selectedProp = prop
                        } else {
                            selectedProp = null
                            selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                            selectedEdge = hoveredEdge
                        }
                        onUpdatePaletteHighlights()
                    }
                }
                is PaletteSelection.DecorationSel -> {
                    if (Gdx.input.justTouched() && hoveredX != -1) {
                        // Place a new prop at the hovered position
                        val prop = Prop(
                            modelPath = sel.modelPath,
                            name = sel.name,
                            x = hoveredX.toFloat(),
                            y = hoveredY.toFloat(),
                            z = hoveredZ.toFloat()
                        )
                        world.props.add(prop)
                        selectedProp = prop
                    }
                }
            }
        } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            lastPaintX = -1; lastPaintY = -1; lastPaintZ = -1
        }

        // ── Q / E: rotate stairs on hovered node ────────────────────────────
        if (hoveredX != -1) {
            val rotNode = world.getNode(hoveredX, hoveredY, hoveredZ)
            val stairsTile = rotNode?.getTile(TileSlot.STAIRS)
            if (stairsTile is com.roguelike.world.BaseTile) {
                if (Gdx.input.isKeyJustPressed(Input.Keys.Q)) {
                    stairsTile.rotationY = (stairsTile.rotationY - 90f) % 360f
                }
                if (Gdx.input.isKeyJustPressed(Input.Keys.E)) {
                    stairsTile.rotationY = (stairsTile.rotationY + 90f) % 360f
                }
            }
        }

        // ── WASD micro-movement & Q/E rotation for selected prop ─────────
        val prop = selectedProp
        if (prop != null) {
            val microStep = 0.05f
            if (Gdx.input.isKeyJustPressed(Input.Keys.W)) prop.y += microStep
            if (Gdx.input.isKeyJustPressed(Input.Keys.S)) prop.y -= microStep
            if (Gdx.input.isKeyJustPressed(Input.Keys.A)) prop.x -= microStep
            if (Gdx.input.isKeyJustPressed(Input.Keys.D)) prop.x += microStep
            if (Gdx.input.isKeyJustPressed(Input.Keys.Q)) prop.rotationY -= 15f
            if (Gdx.input.isKeyJustPressed(Input.Keys.E)) prop.rotationY += 15f
            if (Gdx.input.isKeyJustPressed(Input.Keys.Z)) prop.scale = (prop.scale - 0.05f).coerceAtLeast(0.05f)
            if (Gdx.input.isKeyJustPressed(Input.Keys.X)) prop.scale += 0.05f
            if (Gdx.input.isKeyJustPressed(Input.Keys.R)) prop.z += microStep
            if (Gdx.input.isKeyJustPressed(Input.Keys.F)) prop.z -= microStep
        }
    }

    // ── Fill tool: flood-fill floors bounded by walls ─────────────────────

    private fun floodFill(world: World, startX: Int, startY: Int, z: Int) {
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun key(x: Int, y: Int): Long = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)

        queue.add(startX to startY)
        visited.add(key(startX, startY))

        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val node = world.getNode(cx, cy, z) ?: continue

            // Place floor if not already present
            if (!node.hasTile(TileSlot.FLOOR)) {
                val tile = modelLoader.createTile(FloorTile_TYPE) ?: continue
                node.setTile(tile)
            }

            // Try spreading in 4 directions, blocked by walls
            trySpread(world, cx, cy, z, 0, 1, TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH, visited, queue)
            trySpread(world, cx, cy, z, 0, -1, TileSlot.WALL_SOUTH, TileSlot.WALL_NORTH, visited, queue)
            trySpread(world, cx, cy, z, 1, 0, TileSlot.WALL_EAST, TileSlot.WALL_WEST, visited, queue)
            trySpread(world, cx, cy, z, -1, 0, TileSlot.WALL_WEST, TileSlot.WALL_EAST, visited, queue)
        }
    }

    private fun trySpread(
        world: World, cx: Int, cy: Int, z: Int,
        dx: Int, dy: Int,
        currentWall: TileSlot, neighborWall: TileSlot,
        visited: MutableSet<Long>, queue: ArrayDeque<Pair<Int, Int>>
    ) {
        fun key(x: Int, y: Int): Long = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)
        val nx = cx + dx
        val ny = cy + dy
        if (nx < 0 || nx >= world.width || ny < 0 || ny >= world.height) return
        if (visited.contains(key(nx, ny))) return

        // Check wall on current node's outgoing edge
        val currentNode = world.getNode(cx, cy, z)
        if (currentNode != null && currentNode.hasTile(currentWall)) return

        // Check wall on neighbor's incoming edge
        val neighborNode = world.getNode(nx, ny, z)
        if (neighborNode != null && neighborNode.hasTile(neighborWall)) return

        visited.add(key(nx, ny))
        queue.add(nx to ny)
    }

    /**
     * Flood-erase: remove floor tiles in a connected region bounded by walls.
     * Same spread logic as floodFill but removes floors instead of placing them.
     * Only spreads to nodes that have a floor tile.
     */
    private fun floodErase(world: World, startX: Int, startY: Int, z: Int) {
        // Only start if the starting node has a floor
        val startNode = world.getNode(startX, startY, z) ?: return
        if (!startNode.hasTile(TileSlot.FLOOR)) return

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun key(x: Int, y: Int): Long = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)

        queue.add(startX to startY)
        visited.add(key(startX, startY))

        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val node = world.getNode(cx, cy, z) ?: continue

            // Remove floor
            node.removeTile(TileSlot.FLOOR)

            // Spread to neighbors that have floors, blocked by walls
            data class Dir(val dx: Int, val dy: Int, val curWall: TileSlot, val nbrWall: TileSlot)
            val dirs = listOf(
                Dir(0, 1, TileSlot.WALL_NORTH, TileSlot.WALL_SOUTH),
                Dir(0, -1, TileSlot.WALL_SOUTH, TileSlot.WALL_NORTH),
                Dir(1, 0, TileSlot.WALL_EAST, TileSlot.WALL_WEST),
                Dir(-1, 0, TileSlot.WALL_WEST, TileSlot.WALL_EAST)
            )
            for (dir in dirs) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx < 0 || nx >= world.width || ny < 0 || ny >= world.height) continue
                if (visited.contains(key(nx, ny))) continue
                // Blocked by walls
                if (node.hasTile(dir.curWall)) continue
                val neighbor = world.getNode(nx, ny, z) ?: continue
                if (neighbor.hasTile(dir.nbrWall)) continue
                // Only spread to nodes that have a floor
                if (!neighbor.hasTile(TileSlot.FLOOR)) continue
                visited.add(key(nx, ny))
                queue.add(nx to ny)
            }
        }
    }

    // ── Room tool: Addition / Merge ─────────────────────────────────────

    /**
     * Addition mode: draw a room rectangle.
     * 1. Clear all internal walls inside the rectangle (hollow it out).
     * 2. Place perimeter walls facing outward, UNLESS the adjacent node outside
     *    the rectangle is already a room interior (has a floor), in which case
     *    we merge by leaving the edge open.
     */
    private fun executeRoomAdd(world: World, z: Int) {
        val minX = minOf(roomDragStartX, roomDragEndX).coerceIn(0, world.width - 1)
        val maxX = maxOf(roomDragStartX, roomDragEndX).coerceIn(0, world.width - 1)
        val minY = minOf(roomDragStartY, roomDragEndY).coerceIn(0, world.height - 1)
        val maxY = maxOf(roomDragStartY, roomDragEndY).coerceIn(0, world.height - 1)

        // 1. Clear internal walls: remove walls on edges shared between two nodes
        //    that are both inside the rectangle.
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val node = world.getNode(x, y, z) ?: continue
                // Remove north wall if neighbor to the north is also inside
                if (y < maxY) {
                    removeWall(node, TileSlot.WALL_NORTH)
                    world.getNode(x, y + 1, z)?.let { removeWall(it, TileSlot.WALL_SOUTH) }
                }
                // Remove east wall if neighbor to the east is also inside
                if (x < maxX) {
                    removeWall(node, TileSlot.WALL_EAST)
                    world.getNode(x + 1, y, z)?.let { removeWall(it, TileSlot.WALL_WEST) }
                }
            }
        }

        // 2. Draw perimeter walls, skipping edges where the outside neighbor
        //    is already a room interior (has floor tile) — merge instead.
        // Top row (maxY): North walls
        for (x in minX..maxX) {
            val outside = world.getNode(x, maxY + 1, z)
            if (outside == null || !outside.hasFloor) {
                placeWallIfAbsent(world, x, maxY, z, TileSlot.WALL_NORTH)
            } else {
                // Merge: remove any wall between the two rooms
                removeWall(world.getNode(x, maxY, z), TileSlot.WALL_NORTH)
                removeWall(outside, TileSlot.WALL_SOUTH)
            }
        }
        // Bottom row (minY): South walls
        for (x in minX..maxX) {
            val outside = world.getNode(x, minY - 1, z)
            if (outside == null || !outside.hasFloor) {
                placeWallIfAbsent(world, x, minY, z, TileSlot.WALL_SOUTH)
            } else {
                removeWall(world.getNode(x, minY, z), TileSlot.WALL_SOUTH)
                removeWall(outside, TileSlot.WALL_NORTH)
            }
        }
        // Right column (maxX): East walls
        for (y in minY..maxY) {
            val outside = world.getNode(maxX + 1, y, z)
            if (outside == null || !outside.hasFloor) {
                placeWallIfAbsent(world, maxX, y, z, TileSlot.WALL_EAST)
            } else {
                removeWall(world.getNode(maxX, y, z), TileSlot.WALL_EAST)
                removeWall(outside, TileSlot.WALL_WEST)
            }
        }
        // Left column (minX): West walls
        for (y in minY..maxY) {
            val outside = world.getNode(minX - 1, y, z)
            if (outside == null || !outside.hasFloor) {
                placeWallIfAbsent(world, minX, y, z, TileSlot.WALL_WEST)
            } else {
                removeWall(world.getNode(minX, y, z), TileSlot.WALL_WEST)
                removeWall(outside, TileSlot.WALL_EAST)
            }
        }
    }

    // ── Room tool: Subtraction / Carve ───────────────────────────────────

    /**
     * Subtraction mode: carve out a rectangle from existing rooms.
     * 1. Delete ALL walls (interior + perimeter) inside the rectangle.
     * 2. Delete floor tiles inside the rectangle.
     * 3. Seal the cut: for each perimeter edge, if the node just OUTSIDE the
     *    rectangle is part of a room (has floor), place a wall on that outside
     *    node facing inward toward the carved space.
     */
    private fun executeRoomSubtract(world: World, z: Int) {
        val minX = minOf(roomDragStartX, roomDragEndX).coerceIn(0, world.width - 1)
        val maxX = maxOf(roomDragStartX, roomDragEndX).coerceIn(0, world.width - 1)
        val minY = minOf(roomDragStartY, roomDragEndY).coerceIn(0, world.height - 1)
        val maxY = maxOf(roomDragStartY, roomDragEndY).coerceIn(0, world.height - 1)

        // 1 & 2. Clear all walls and floors inside the rectangle
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val node = world.getNode(x, y, z) ?: continue
                node.removeTile(TileSlot.WALL_NORTH)
                node.removeTile(TileSlot.WALL_SOUTH)
                node.removeTile(TileSlot.WALL_EAST)
                node.removeTile(TileSlot.WALL_WEST)
                node.removeTile(TileSlot.FLOOR)
                // Also clean up door tags on removed walls
                node.untagDoor(TileSlot.WALL_NORTH)
                node.untagDoor(TileSlot.WALL_SOUTH)
                node.untagDoor(TileSlot.WALL_EAST)
                node.untagDoor(TileSlot.WALL_WEST)
            }
        }

        // Also remove walls on outside neighbors that face into the carved area
        // (the "other side" of shared edges)
        for (x in minX..maxX) {
            world.getNode(x, maxY + 1, z)?.let { removeWall(it, TileSlot.WALL_SOUTH) }
            world.getNode(x, minY - 1, z)?.let { removeWall(it, TileSlot.WALL_NORTH) }
        }
        for (y in minY..maxY) {
            world.getNode(maxX + 1, y, z)?.let { removeWall(it, TileSlot.WALL_WEST) }
            world.getNode(minX - 1, y, z)?.let { removeWall(it, TileSlot.WALL_EAST) }
        }

        // 3. Seal the cut: place walls on outside nodes that have floors,
        //    facing inward toward the carved space
        // Above the top row
        for (x in minX..maxX) {
            val outside = world.getNode(x, maxY + 1, z)
            if (outside != null && outside.hasFloor) {
                placeWallIfAbsent(world, x, maxY + 1, z, TileSlot.WALL_SOUTH)
            }
        }
        // Below the bottom row
        for (x in minX..maxX) {
            val outside = world.getNode(x, minY - 1, z)
            if (outside != null && outside.hasFloor) {
                placeWallIfAbsent(world, x, minY - 1, z, TileSlot.WALL_NORTH)
            }
        }
        // Right of the right column
        for (y in minY..maxY) {
            val outside = world.getNode(maxX + 1, y, z)
            if (outside != null && outside.hasFloor) {
                placeWallIfAbsent(world, maxX + 1, y, z, TileSlot.WALL_WEST)
            }
        }
        // Left of the left column
        for (y in minY..maxY) {
            val outside = world.getNode(minX - 1, y, z)
            if (outside != null && outside.hasFloor) {
                placeWallIfAbsent(world, minX - 1, y, z, TileSlot.WALL_EAST)
            }
        }
    }

    // ── Wall helpers ─────────────────────────────────────────────────────

    private fun removeWall(node: com.roguelike.core.model.WorldNode?, slot: TileSlot) {
        if (node == null) return
        node.removeTile(slot)
        node.untagDoor(slot)
    }

    private fun placeWallIfAbsent(world: World, x: Int, y: Int, z: Int, slot: TileSlot) {
        val node = world.getNode(x, y, z) ?: return
        if (node.hasTile(slot)) return
        val wallType = wallTypeForSlot(slot) ?: return
        val tile = modelLoader.createTile(wallType) ?: return
        node.setTile(tile)
    }

    companion object {
        private const val FloorTile_TYPE = "FloorTile"

        fun findPropAt(world: World, x: Float, y: Float, z: Float): Prop? {
            return world.props.find { prop ->
                val dx = x - prop.x
                val dy = y - prop.y
                val dz = z - prop.z
                val (hsX, hsY) = prop.rotatedHalfSizes()
                kotlin.math.abs(dx) < hsX + 0.5f &&
                kotlin.math.abs(dy) < hsY + 0.5f &&
                kotlin.math.abs(dz) < 1f
            }
        }

        fun wallTypeForSlot(slot: TileSlot): String? = when (slot) {
            TileSlot.WALL_NORTH -> "WallNorthTile"
            TileSlot.WALL_SOUTH -> "WallSouthTile"
            TileSlot.WALL_EAST  -> "WallEastTile"
            TileSlot.WALL_WEST  -> "WallWestTile"
            else -> null
        }

        fun doorTypeForSlot(slot: TileSlot): String? = when (slot) {
            TileSlot.WALL_NORTH -> "DoorNorthTile"
            TileSlot.WALL_SOUTH -> "DoorSouthTile"
            TileSlot.WALL_EAST  -> "DoorEastTile"
            TileSlot.WALL_WEST  -> "DoorWestTile"
            else -> null
        }

        fun ladderRotationForEdge(slot: TileSlot): Float = when (slot) {
            TileSlot.WALL_NORTH -> 0f
            TileSlot.WALL_EAST  -> 90f
            TileSlot.WALL_SOUTH -> 180f
            TileSlot.WALL_WEST  -> 270f
            else -> 0f
        }
    }
}













