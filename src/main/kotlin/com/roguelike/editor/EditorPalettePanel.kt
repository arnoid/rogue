package com.roguelike.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.*
import com.roguelike.core.model.Tile
import com.roguelike.core.model.WorldNode
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.rendering.TileRenderer
import com.roguelike.utils.ModelLoader

/**
 * Palette selection types for the map editor.
 */
sealed class PaletteSelection {
    /** Place a floor tile on a node. */
    object FloorSel : PaletteSelection()
    /** Place a wall on a node edge. */
    object WallSel : PaletteSelection()
    /** Place a door on a node edge. */
    object DoorSel : PaletteSelection()
    /** Place stairs on a node center. */
    object StairsSel : PaletteSelection()
    /** Toggle a tag on a node. */
    data class TagSel(val tag: String) : PaletteSelection()
}

/**
 * Palette panel for the map editor.
 * Provides floor, wall, door, and tag selections.
 */
class EditorPalettePanel(
    private val modelLoader: ModelLoader,
    private val tileRenderer: TileRenderer,
    private val modelBatch: ModelBatch,
    private val stage: com.badlogic.gdx.scenes.scene2d.Stage
) {
    var paletteSelection: PaletteSelection? = null
        private set

    private val tagButtons = HashMap<String, TextButton>()
    private val selectionButtons = HashMap<String, VisTable>()

    private val previewEnvironment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        add(DirectionalLight().set(1.0f, 1.0f, 1.0f, -1f, -0.8f, -0.2f))
    }
    private val previewCamera = com.badlogic.gdx.graphics.PerspectiveCamera(67f, 100f, 100f).apply {
        position.set(0f, 0f, 2f)
        up.set(0f, 1f, 0f)
        lookAt(0f, 0f, 0f)
        near = 0.1f; far = 100f; update()
    }

    fun setSelection(sel: PaletteSelection?) {
        paletteSelection = sel
        refreshHighlights()
    }

    fun toggleSelection(sel: PaletteSelection) {
        paletteSelection = if (paletteSelection == sel) null else sel
        refreshHighlights()
    }

    fun buildContent(): VisTable {
        val content = VisTable()
        content.top()

        // ── Floor ────────────────────────────────────────────────────────────
        content.add(VisLabel("TILES")).pad(10f).row()
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel("Floor")).padLeft(8f).padBottom(2f).left().row()

        val floorTile = modelLoader.createTile("FloorTile")
        if (floorTile != null) {
            val floorContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.FloorSel }
            floorContainer.add(TilePreviewActor(floorTile)).size(64f).pad(5f).row()
            floorContainer.add(VisLabel("Floor")).expandX().center()
            floorContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.FloorSel)
                }
            })
            content.add(floorContainer).pad(5f).row()
            selectionButtons["floor"] = floorContainer
        }

        // ── Wall ─────────────────────────────────────────────────────────────
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel("Wall")).padLeft(8f).padBottom(2f).left().row()

        val wallTile = modelLoader.createTile("WallNorthTile")
        if (wallTile != null) {
            val wallContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.WallSel }
            wallContainer.add(TilePreviewActor(wallTile)).size(64f).pad(5f).row()
            wallContainer.add(VisLabel("Wall")).expandX().center()
            wallContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.WallSel)
                }
            })
            content.add(wallContainer).pad(5f).row()
            selectionButtons["wall"] = wallContainer
        }

        // ── Door ─────────────────────────────────────────────────────────────
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel("Door")).padLeft(8f).padBottom(2f).left().row()

        val doorTile = modelLoader.createTile("DoorNorthTile")
        if (doorTile != null) {
            val doorContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.DoorSel }
            doorContainer.add(TilePreviewActor(doorTile)).size(64f).pad(5f).row()
            doorContainer.add(VisLabel("Door")).expandX().center()
            doorContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.DoorSel)
                }
            })
            content.add(doorContainer).pad(5f).row()
            selectionButtons["door"] = doorContainer
        }

        // ── Stairs ──────────────────────────────────────────────────────────
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel("Stairs")).padLeft(8f).padBottom(2f).left().row()

        val stairsTile = modelLoader.createTile("StairsTile")
        if (stairsTile != null) {
            val stairsContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.StairsSel }
            stairsContainer.add(TilePreviewActor(stairsTile)).size(64f).pad(5f).row()
            stairsContainer.add(VisLabel("Stairs")).expandX().center()
            stairsContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.StairsSel)
                }
            })
            content.add(stairsContainer).pad(5f).row()
            selectionButtons["stairs"] = stairsContainer
        }

        // ── Tags ─────────────────────────────────────────────────────────────
        content.addSeparator().padTop(10f).padBottom(4f)
        content.add(VisLabel("TAGS")).pad(10f).row()
        val nodeTags = listOf(
            NodeTags.PLAYER_SPAWN, NodeTags.ENEMY_SPAWN,
            NodeTags.ITEM_SPAWN, NodeTags.EXIT,
            NodeTags.DOOR_MANUAL,
            NodeTags.NODE_CONNECTOR
        )
        nodeTags.forEach { tag ->
            val btn = VisTextButton(tag, "toggle")
            btn.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.TagSel(tag))
                }
            })
            val tagContainer = SelectionBorderGroup { paletteSelection.let { it is PaletteSelection.TagSel && it.tag == tag } }
            tagContainer.add(btn).fillX()
            content.add(tagContainer).fillX().pad(2f).row()
            tagButtons[tag] = btn
        }

        return content
    }

    fun refreshHighlights() {
        val sel = paletteSelection
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = sel is PaletteSelection.TagSel && sel.tag == tag
        }
    }

    fun updateHighlightsForNode(node: WorldNode?) {
        if (node == null) { refreshHighlights(); return }
        val sel = paletteSelection
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = if (tag == NodeTags.DOOR_MANUAL) {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.manualDoorSlots.isNotEmpty()
            } else if (tag == NodeTags.NODE_CONNECTOR) {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.connectorSlots.isNotEmpty()
            } else {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.tags.contains(tag)
            }
        }
    }

    inner class TilePreviewActor(val tile: Tile) : Actor() {
        init { touchable = Touchable.disabled }
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            batch.end()

            val screenPos = localToStageCoordinates(Vector2(0f, 0f))
            val scaleX = Gdx.graphics.backBufferWidth.toFloat() / stage.width
            val scaleY = Gdx.graphics.backBufferHeight.toFloat() / stage.height
            val bx = (screenPos.x * scaleX).toInt()
            val by = (screenPos.y * scaleY).toInt()
            val bw = (width * scaleX).toInt()
            val bh = (height * scaleY).toInt()

            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(bx, by, bw, bh)
            Gdx.gl.glViewport(bx, by, bw, bh)

            modelBatch.begin(previewCamera)
            tileRenderer.render(tile, modelBatch, previewEnvironment, 0f, 0f, 0f, ignoreYRotation = false)
            modelBatch.end()

            Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)

            batch.begin()
        }
    }

    inner class SelectionBorderGroup(val isSelected: () -> Boolean) : VisTable() {
        init { touchable = Touchable.enabled }
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            super.draw(batch, parentAlpha)
            if (isSelected()) {
                val d = VisUI.getSkin().getDrawable("white")
                val bord = 3f
                batch.setColor(Color.CYAN)
                d.draw(batch, x, y, width, bord)
                d.draw(batch, x, y + height - bord, width, bord)
                d.draw(batch, x, y, bord, height)
                d.draw(batch, x + width - bord, y, bord, height)
                batch.setColor(Color.WHITE)
            }
        }
    }
}
