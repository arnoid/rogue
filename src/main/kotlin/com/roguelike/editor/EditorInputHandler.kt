package com.roguelike.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.utils.ModelLoader

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
                        } else {
                            world.removeTag(n, sel.tag)
                        }
                        onUpdatePaletteHighlights()
                    }
                }
                null -> {}
            }
            return
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
                    // No palette selection → just select node/edge
                    if (Gdx.input.justTouched()) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        selectedEdge = hoveredEdge
                        onUpdatePaletteHighlights()
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
    }

    companion object {
        private const val FloorTile_TYPE = "FloorTile"

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
    }
}

