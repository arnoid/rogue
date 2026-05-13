package com.roguelike.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
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
import java.io.File

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
    /** Place a ladder on a node center. */
    object LadderSel : PaletteSelection()
    /** Toggle a tag on a node. */
    data class TagSel(val tag: String) : PaletteSelection()
    /** Place a decoration prop. */
    data class DecorationSel(val modelPath: String, val name: String) : PaletteSelection()
    /** Place a light source. */
    object LightSourceSel : PaletteSelection()
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

    /** Persisted list of decoration model paths available in the palette. */
    val decorationModels = mutableListOf<DecorationEntry>()
    private var decorationsContainer: VisTable? = null

    data class DecorationEntry(val modelPath: String, val name: String)

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
        val root = VisTable()
        root.top()

        // ── Tab buttons ─────────────────────────────────────────────────────
        val tabBar = VisTable()
        val tilesTabBtn = VisTextButton("Tiles")
        val propsTabBtn = VisTextButton("Props")
        tabBar.add(tilesTabBtn).expandX().fillX().pad(2f)
        tabBar.add(propsTabBtn).expandX().fillX().pad(2f)
        root.add(tabBar).fillX().row()
        root.addSeparator().padBottom(4f)

        // ── Tiles content ───────────────────────────────────────────────────
        val tilesContent = buildTilesContent()

        // ── Props content ───────────────────────────────────────────────────
        val propsContent = VisTable()
        propsContent.top()
        val decoContainer = VisTable()
        decoContainer.top()
        decorationsContainer = decoContainer
        propsContent.add(decoContainer).fillX().expandX().row()
        loadDecorationConfig()
        rebuildDecorationsUI()

        // ── Tab container ───────────────────────────────────────────────────
        val tabContent = VisTable()
        tabContent.top()
        tabContent.add(tilesContent).fill().expand().row()

        fun showTab(tiles: Boolean) {
            tabContent.clear()
            if (tiles) {
                tabContent.add(tilesContent).fill().expand().row()
                tilesTabBtn.color = com.badlogic.gdx.graphics.Color.CYAN
                propsTabBtn.color = com.badlogic.gdx.graphics.Color.WHITE
            } else {
                tabContent.add(propsContent).fill().expand().row()
                propsTabBtn.color = com.badlogic.gdx.graphics.Color.CYAN
                tilesTabBtn.color = com.badlogic.gdx.graphics.Color.WHITE
                // Clear tile selection when switching to props
                paletteSelection = null
                refreshHighlights()
            }
        }

        tilesTabBtn.color = com.badlogic.gdx.graphics.Color.CYAN
        tilesTabBtn.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                showTab(true)
            }
        })
        propsTabBtn.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                showTab(false)
            }
        })

        root.add(tabContent).fill().expand().row()
        return root
    }

    private fun buildTilesContent(): VisTable {
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

        // ── Ladder ──────────────────────────────────────────────────────────
        content.addSeparator().padTop(6f).padBottom(2f)
        content.add(VisLabel("Ladder")).padLeft(8f).padBottom(2f).left().row()

        val ladderTile = modelLoader.createTile("LadderTile")
        if (ladderTile != null) {
            val ladderContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.LadderSel }
            ladderContainer.add(TilePreviewActor(ladderTile)).size(64f).pad(5f).row()
            ladderContainer.add(VisLabel("Ladder")).expandX().center()
            ladderContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleSelection(PaletteSelection.LadderSel)
                }
            })
            content.add(ladderContainer).pad(5f).row()
            selectionButtons["ladder"] = ladderContainer
        }

        // ── Light Source ────────────────────────────────────────────────────
        val lightContainer = SelectionBorderGroup { paletteSelection is PaletteSelection.LightSourceSel }
        lightContainer.add(VisLabel("💡")).size(64f).pad(5f).row()
        lightContainer.add(VisLabel("Light Source")).expandX().center()
        lightContainer.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                toggleSelection(PaletteSelection.LightSourceSel)
            }
        })
        content.add(lightContainer).pad(5f).row()
        selectionButtons["lightsource"] = lightContainer

        // ── Tags ─────────────────────────────────────────────────────────────
        content.addSeparator().padTop(10f).padBottom(4f)
        content.add(VisLabel("TAGS")).pad(10f).row()
        val nodeTags = listOf(
            NodeTags.PLAYER_SPAWN, NodeTags.ENEMY_SPAWN,
            NodeTags.ITEM_SPAWN, NodeTags.EXIT,
            NodeTags.DOOR_MANUAL,
            NodeTags.SOCKET,
            NodeTags.LADDER
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

    // ── Decorations persistence ──────────────────────────────────────────

    private val configFile = File(System.getProperty("user.home"), ".roguelike-editor-decorations.json")

    fun loadDecorationConfig() {
        if (!configFile.exists()) return
        try {
            val json = com.badlogic.gdx.utils.Json()
            val entries = json.fromJson(Array<DecorationEntry>::class.java, configFile.readText())
            if (entries != null) {
                decorationModels.clear()
                decorationModels.addAll(entries)
            }
        } catch (e: Exception) {
            println("[EditorPalettePanel] Failed to load decoration config: ${e.message}")
        }
    }

    fun saveDecorationConfig() {
        try {
            val json = com.badlogic.gdx.utils.Json()
            configFile.writeText(json.prettyPrint(decorationModels.toTypedArray()))
        } catch (e: Exception) {
            println("[EditorPalettePanel] Failed to save decoration config: ${e.message}")
        }
    }

    fun addDecoration(modelPath: String) {
        val name = modelPath.substringAfterLast("/").substringAfterLast("\\").substringBeforeLast(".")
        if (decorationModels.any { it.modelPath == modelPath }) return
        decorationModels.add(DecorationEntry(modelPath, name))
        saveDecorationConfig()
        rebuildDecorationsUI()
    }

    /**
     * Ensures all model paths used by props in the given world are listed in the decoration palette.
     * Call this after loading a submap file.
     */
    fun syncDecorationsFromWorld(world: com.roguelike.core.model.World) {
        var changed = false
        for (prop in world.props) {
            if (decorationModels.none { it.modelPath == prop.modelPath }) {
                val name = prop.name.ifBlank {
                    prop.modelPath.substringAfterLast("/").substringAfterLast("\\").substringBeforeLast(".")
                }
                decorationModels.add(DecorationEntry(prop.modelPath, name))
                changed = true
            }
        }
        if (changed) {
            saveDecorationConfig()
            rebuildDecorationsUI()
        }
    }

    fun removeDecoration(modelPath: String) {
        decorationModels.removeAll { it.modelPath == modelPath }
        saveDecorationConfig()
        if (paletteSelection is PaletteSelection.DecorationSel &&
            (paletteSelection as PaletteSelection.DecorationSel).modelPath == modelPath) {
            paletteSelection = null
        }
        rebuildDecorationsUI()
    }

    private fun rebuildDecorationsUI() {
        val container = decorationsContainer ?: return
        container.clear()
        container.add(VisLabel("DECORATIONS")).pad(10f).row()
        val addBtn = VisTextButton("+ Add Model")
        addBtn.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                val chooser = java.awt.FileDialog(null as java.awt.Frame?, "Select 3D Model", java.awt.FileDialog.LOAD)
                chooser.setFilenameFilter { _, name -> name.endsWith(".obj") || name.endsWith(".g3db") || name.endsWith(".g3dj") }
                chooser.isVisible = true
                val dir = chooser.directory
                val file = chooser.file
                if (dir != null && file != null) {
                    addDecoration(dir + file)
                }
            }
        })
        container.add(addBtn).fillX().pad(4f).row()

        for (entry in decorationModels) {
            val entryContainer = SelectionBorderGroup {
                paletteSelection is PaletteSelection.DecorationSel &&
                (paletteSelection as PaletteSelection.DecorationSel).modelPath == entry.modelPath
            }
            val entryContent = VisTable()
            entryContent.add(PropPreviewActor(entry.modelPath)).size(64f).pad(5f).row()
            entryContent.add(VisLabel(entry.name)).expandX().center()
            entryContainer.add(entryContent).fillX()
            entryContainer.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    if (event.button == Input.Buttons.LEFT) {
                        toggleSelection(PaletteSelection.DecorationSel(entry.modelPath, entry.name))
                    }
                }
            })
            // Right-click context menu for deletion
            entryContainer.addListener(object : ClickListener(Input.Buttons.RIGHT) {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    val menu = com.kotcrab.vis.ui.widget.PopupMenu()
                    val deleteItem = com.kotcrab.vis.ui.widget.MenuItem("Delete")
                    deleteItem.addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent, x: Float, y: Float) {
                            removeDecoration(entry.modelPath)
                        }
                    })
                    menu.addItem(deleteItem)
                    menu.showMenu(stage, Gdx.input.x.toFloat(), Gdx.graphics.height - Gdx.input.y.toFloat())
                }
            })
            container.add(entryContainer).fillX().pad(2f).row()
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
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = if (tag == NodeTags.DOOR_MANUAL) {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.manualDoorSlots.isNotEmpty()
            } else if (tag == NodeTags.SOCKET) {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.socketSlots.isNotEmpty()
            } else if (tag == NodeTags.LADDER) {
                (sel is PaletteSelection.TagSel && sel.tag == tag) || node.ladderSlots.isNotEmpty()
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

    inner class PropPreviewActor(private val modelPath: String) : Actor() {
        private var instance: ModelInstance? = null
        private var previewScale = 1f
        private var previewCenter = Vector3()

        init {
            touchable = Touchable.disabled
            try {
                val model = modelLoader.assetLoader.loadModel("prop_$modelPath", modelPath)
                val box = BoundingBox()
                model.calculateBoundingBox(box)
                val maxDim = maxOf(box.width, maxOf(box.height, box.depth))
                previewScale = if (maxDim > 0f) 1f / maxDim else 1f
                box.getCenter(previewCenter)
                instance = ModelInstance(model)
            } catch (e: Exception) {
                println("[PropPreviewActor] Failed to load: $modelPath - ${e.message}")
            }
        }

        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            val inst = instance ?: return
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

            inst.transform.setToTranslation(0f, 0f, 0f)
            inst.transform.scale(previewScale, previewScale, previewScale)
            inst.transform.rotate(Vector3.X, -90f)
            inst.transform.rotate(Vector3.Z, 180f)
            inst.transform.translate(-previewCenter.x, -previewCenter.y, -previewCenter.z)

            modelBatch.begin(previewCamera)
            modelBatch.render(inst, previewEnvironment)
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
