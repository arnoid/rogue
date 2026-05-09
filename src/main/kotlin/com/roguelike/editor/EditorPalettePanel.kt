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
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.*
import com.roguelike.core.model.Tile
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.rendering.TileRenderer
import com.roguelike.utils.ModelLoader
import com.roguelike.world.*

/**
 * Unified palette selection — only one item (tile, item, or tag) can be active at a time.
 */
sealed class PaletteSelection {
    data class TileSel(val type: String) : PaletteSelection()
    data class ItemSel(val name: String, val colorHex: String) : PaletteSelection()
    data class TagSel(val tag: String) : PaletteSelection()
}

/**
 * Extracted palette panel for the map editor.
 * Builds the tile/item/tag selection UI and manages selection state.
 */
class EditorPalettePanel(
    private val modelLoader: ModelLoader,
    private val tileRenderer: TileRenderer,
    private val modelBatch: ModelBatch,
    private val stage: com.badlogic.gdx.scenes.scene2d.Stage
) {
    var paletteSelection: PaletteSelection? = null
        private set

    private val tileContainers = HashMap<String, VisTable>()
    private val itemContainers = HashMap<String, VisTable>()
    private val tagButtons = HashMap<String, TextButton>()

    private val previewEnvironment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        add(DirectionalLight().set(1.0f, 1.0f, 1.0f, -1f, -0.8f, -0.2f))
    }
    private val previewCamera = com.badlogic.gdx.graphics.PerspectiveCamera(67f, 100f, 100f).apply {
        position.set(1.2f, 1.2f, 1.2f)
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

        // Tiles
        content.add(VisLabel("TILES")).pad(10f).row()
        val tileGroups = listOf(
            "Floors"      to listOf(FloorTile.TYPE),
            "Walls"       to listOf(WallHorizontalTile.TYPE, WallVerticalTile.TYPE,
                                    WallArchedTile.TYPE, WallCrossingTile.TYPE,
                                    WallTsplitNTile.TYPE, WallTsplitETile.TYPE,
                                    WallTsplitSTile.TYPE, WallTsplitWTile.TYPE,
                                    CornerNETile.TYPE, CornerSETile.TYPE,
                                    CornerSWTile.TYPE, CornerNWTile.TYPE),
            "Doors"       to listOf(DoorHorizontalTile.TYPE, DoorVerticalTile.TYPE),
            "Interaction" to listOf(ToggleTile.TYPE)
        )
        tileGroups.forEach { (name, types) -> addTileGroup(content, name, types) }

        // Items
        content.addSeparator().padTop(10f).padBottom(4f)
        content.add(VisLabel("ITEMS")).pad(10f).row()
        val itemsGrid = VisTable()
        content.add(itemsGrid).fillX().expandX().row()
        val items = listOf(
            Triple(Color.BLUE.toString(), "Blue Key", "Key"),
            Triple(Color.GREEN.toString(), "Green Key", "Key"),
            Triple(Color.RED.toString(), "Red Key", "Key")
        )
        items.forEachIndexed { index, (colorHex, name, _) ->
            val container = SelectionBorderGroup { paletteSelection.let { it is PaletteSelection.ItemSel && it.name == name } }
            val preview = Image(VisUI.getSkin().getDrawable("white"))
            preview.color = Color.valueOf(colorHex)
            container.add(preview).size(32f).pad(5f).row()
            container.add(VisLabel(name)).expandX().center()
            container.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.ItemSel(name, colorHex))
                }
            })
            itemsGrid.add(container).pad(5f)
            if ((index + 1) % 3 == 0) itemsGrid.row()
            itemContainers[name] = container
        }

        // Tags
        content.addSeparator().padTop(10f).padBottom(4f)
        content.add(VisLabel("TAGS")).pad(10f).row()
        val nodeTags = listOf(
            NodeTags.PLAYER_SPAWN, NodeTags.ENEMY_SPAWN,
            NodeTags.ITEM_SPAWN, NodeTags.EXIT,
            NodeTags.TOGGLE
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

    private fun addTileGroup(content: VisTable, groupName: String, types: List<String>) {
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel(groupName)).padLeft(8f).padBottom(2f).left().row()
        val grid = VisTable()
        content.add(grid).fillX().expandX().row()
        types.forEachIndexed { index, type ->
            val tile = modelLoader.createTile(type)!!
            val container = SelectionBorderGroup { paletteSelection.let { it is PaletteSelection.TileSel && it.type == type } }
            container.add(TilePreviewActor(tile)).size(64f).pad(5f).row()
            container.add(VisLabel(type.removeSuffix("Tile"))).expandX().center()
            container.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.TileSel(type))
                }
            })
            grid.add(container).pad(5f)
            if ((index + 1) % 3 == 0) grid.row()
            tileContainers[type] = container
        }
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
        val darkGray = VisUI.getSkin().newDrawable("white", Color.DARK_GRAY)
        tileContainers.forEach { (type, table) ->
            table.background = if (node.hasTileType(type) &&
                !(sel is PaletteSelection.TileSel && sel.type == type)) darkGray
            else null
        }
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = (sel is PaletteSelection.TagSel && sel.tag == tag) || node.tags.contains(tag)
        }
    }

    inner class TilePreviewActor(val tile: Tile) : Actor() {
        init { touchable = Touchable.disabled }
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            val scissorWasEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
            val scissorBox = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)
            Gdx.gl20.glGetIntegerv(GL20.GL_SCISSOR_BOX, scissorBox)
            batch.end()
            val screenPos = localToStageCoordinates(Vector2(0f, 0f))
            val bx = screenPos.x * (Gdx.graphics.backBufferWidth.toFloat() / stage.width)
            val by = screenPos.y * (Gdx.graphics.backBufferHeight.toFloat() / stage.height)
            val bw = width * (Gdx.graphics.backBufferWidth.toFloat() / stage.width)
            val bh = height * (Gdx.graphics.backBufferHeight.toFloat() / stage.height)
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glViewport(bx.toInt(), by.toInt(), bw.toInt(), bh.toInt())
            modelBatch.begin(previewCamera)
            tileRenderer.render(tile, modelBatch, previewEnvironment, 0f, 0f, 0f, ignoreYRotation = true)
            modelBatch.end()
            Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            if (scissorWasEnabled) {
                Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
                Gdx.gl.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            } else {
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            }
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

