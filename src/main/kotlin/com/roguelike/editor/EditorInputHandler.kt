package com.roguelike.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.roguelike.core.model.KeyItem
import com.roguelike.core.model.World
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.utils.ModelLoader

/**
 * Extracted input handler for the map editor.
 * Handles paint, erase, selection, camera orbit/pan, and association creation.
 */
class EditorInputHandler(
    private val getWorld: () -> World,
    private val modelLoader: ModelLoader,
    private val palette: EditorPalettePanel,
    private val onCameraOrbit: (dx: Float, dy: Float) -> Unit,
    private val onCameraPan: (dx: Float, dy: Float) -> Unit,
    private val onUpdatePaletteHighlights: () -> Unit
) {
    var selectedX = -1
    var selectedY = -1
    var selectedZ = -1

    private var lastPaintX = -1
    private var lastPaintY = -1
    private var lastPaintZ = -1

    private var lastEraseX = -1
    private var lastEraseY = -1
    private var lastEraseZ = -1

    fun handleInput(delta: Float, hoveredX: Int, hoveredY: Int, hoveredZ: Int) {
        val dragging = Math.abs(Gdx.input.deltaX) > 1 || Math.abs(Gdx.input.deltaY) > 1
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && dragging) {
            val dx = Gdx.input.deltaX.toFloat()
            val dy = Gdx.input.deltaY.toFloat()
            val isShift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
            val isAlt   = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)   || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)

            when {
                isAlt   -> onCameraOrbit(dx, dy)
                isShift -> onCameraPan(dx, dy)
            }
        }

        val isShiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
        val isAltHeld   = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)   || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)
        val isCtrlHeld  = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
        val noModifier  = !isShiftHeld && !isAltHeld && !isCtrlHeld

        val world = getWorld()

        // Ctrl + LMB: erase
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isCtrlHeld && !isShiftHeld && !isAltHeld
            && palette.paletteSelection != null && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ)
            val isNewEraseNode = hoveredX != lastEraseX || hoveredY != lastEraseY || hoveredZ != lastEraseZ
            when (val sel = palette.paletteSelection) {
                is PaletteSelection.TileSel -> {
                    if (node != null && node.removeTileByType(sel.type)) {
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                is PaletteSelection.ItemSel -> {
                    if (node != null && isNewEraseNode) {
                        node.items.removeIf { it is KeyItem && it.name == sel.name }
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                is PaletteSelection.TagSel -> {
                    if (isNewEraseNode) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        val n = world.getNode(hoveredX, hoveredY, hoveredZ)
                        if (n != null) { world.removeTag(n, sel.tag); onUpdatePaletteHighlights() }
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                null -> {}
            }
        } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            lastEraseX = -1; lastEraseY = -1; lastEraseZ = -1
        }

        // Normal LMB: paint / select
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && noModifier && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ)
            val isNewNode = hoveredX != lastPaintX || hoveredY != lastPaintY || hoveredZ != lastPaintZ

            when (val sel = palette.paletteSelection) {
                is PaletteSelection.TileSel -> {
                    if (node != null && !node.hasTileType(sel.type)) {
                        node.setTile(modelLoader.createTile(sel.type)!!)
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.ItemSel -> {
                    if (node != null && isNewNode) {
                        node.items.removeIf { it is KeyItem }
                        node.items.add(KeyItem(colorHex = sel.colorHex, name = sel.name))
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.TagSel -> {
                    if (isNewNode) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        val n = world.getNode(hoveredX, hoveredY, hoveredZ)
                        if (n != null && !n.tags.contains(sel.tag)) {
                            world.addTag(n, sel.tag)
                            onUpdatePaletteHighlights()
                        } else if (n != null && Gdx.input.justTouched()) {
                            n.tags.remove(sel.tag)
                            onUpdatePaletteHighlights()
                        }
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                null -> {
                    if (Gdx.input.justTouched()) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        onUpdatePaletteHighlights()
                    }
                }
            }
        } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            lastPaintX = -1; lastPaintY = -1; lastPaintZ = -1
        }

        // Right-click: create association
        if (Gdx.input.justTouched() && noModifier && Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            if (selectedX != -1 && hoveredX != -1) {
                val source = world.getNode(selectedX, selectedY, selectedZ)
                val target = world.getNode(hoveredX, hoveredY, hoveredZ)
                if (source != null && target != null) {
                    var assocData: String? = null
                    val type = if (target.items.any { it is KeyItem }) {
                        assocData = target.items.firstOrNull { it is KeyItem }?.name; "key"
                    } else if (target.tags.contains(NodeTags.TOGGLE)) "toggle" else null
                    if (type != null) world.addAssociation(source, target, type, assocData)
                }
            }
        }
    }
}

