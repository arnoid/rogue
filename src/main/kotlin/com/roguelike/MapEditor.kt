package com.roguelike

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Actor as S2DActor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.*
import com.roguelike.core.model.Tile
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.rendering.*
import com.roguelike.serialization.WorldIO
import com.roguelike.utils.*
import com.roguelike.world.*
import ktx.scene2d.*

class MapEditor(private val game: Game) : Screen {
    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var shapeRenderer: ShapeRenderer

    private lateinit var assetLoader: AssetLoader
    private lateinit var modelLoader: ModelLoader
    private lateinit var world: World

    private lateinit var stage: Stage
    private lateinit var skin: Skin

    private lateinit var tileRenderer: TileRenderer
    private lateinit var worldRenderer: WorldRenderer
    private lateinit var itemRenderer: ItemRenderer

    private var showFrames = true
    private lateinit var frameModel: Model
    private lateinit var frameInstance: ModelInstance
    private lateinit var hoverFrameInstance: ModelInstance
    private lateinit var selectedFrameInstance: ModelInstance
    private lateinit var centerSphereModel: Model
    private lateinit var centerSphereInstance: ModelInstance
    private lateinit var tagSphereModel: Model
    private lateinit var tagSphereInstance: ModelInstance
    private lateinit var tagFont: BitmapFont
    private lateinit var tagSpriteBatch: com.badlogic.gdx.graphics.g2d.SpriteBatch

    private var isDialogActive = false
    private var currentFilePath: String? = null

    private var maxRenderZ = 0
    private var hoveredX = -1
    private var hoveredY = -1
    private var hoveredZ = -1

    private var selectedX = -1
    private var selectedY = -1
    private var selectedZ = -1
    // Tracks the last node painted in the current LMB press; resets on button release
    private var lastPaintX = -1
    private var lastPaintY = -1
    private var lastPaintZ = -1
    // Tracks the last node erased in the current Ctrl+LMB press; resets on button release
    private var lastEraseX = -1
    private var lastEraseY = -1
    private var lastEraseZ = -1

    // Unified palette selection — only one item (tile, item, or tag) can be active at a time
    private sealed class PaletteSelection {
        data class Tile(val type: String) : PaletteSelection()
        data class Item(val name: String, val colorHex: String) : PaletteSelection()
        data class Tag(val tag: String) : PaletteSelection()
    }
    private var paletteSelection: PaletteSelection? = null

    // Camera control
    private var cameraDistance = 20f

    private val cameraTarget = Vector3(0f, 0f, 0f)

    private val previewEnvironment =
            Environment().apply {
                set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
                add(DirectionalLight().set(1.0f, 1.0f, 1.0f, -1f, -0.8f, -0.2f))
            }
    private val previewCamera =
            PerspectiveCamera(67f, 100f, 100f).apply {
                position.set(1.2f, 1.2f, 1.2f)
                lookAt(0f, 0f, 0f)
                near = 0.1f
                far = 100f
                update()
            }

    private lateinit var xLabel: Label
    private lateinit var yLabel: Label
    private lateinit var zLabel: Label
    private lateinit var layerLabel: Label

    private val tileContainers = HashMap<String, Table>()
    private val itemContainers = HashMap<String, Table>()  // key = item name

    /** Tracks tile group grids and their items for dynamic column re-layout. */
    private val tileGrids = mutableListOf<Pair<VisTable, List<Table>>>()  // grid → ordered containers
    private val itemGrid = mutableListOf<Table>()  // ordered item containers
    private lateinit var itemGridTable: VisTable
    private var lastPaletteWidth = -1f
    private val tilePreviewSize = 64f
    private val tileCellPad = 5f

    private val tagButtons = HashMap<String, TextButton>()
    private var cameraPitch = 45f
    private var cameraYaw = 0f
    private lateinit var orientationGizmo: OrientationGizmo
    private lateinit var rootTable: VisTable
    private lateinit var viewportArea: VisTable
    private lateinit var paletteScroll: VisScrollPane
    private var lastViewX = 0
    private var lastViewY = 0
    private var lastViewW = 0
    private var lastViewH = 0
    private var scrolledThisFrame = false

    override fun show() {
        modelBatch = ModelBatch()
        shapeRenderer = ShapeRenderer()
        assetLoader = AssetLoader()
        modelLoader = ModelLoader(assetLoader)
        itemRenderer = ItemRenderer(assetLoader)
        tileRenderer = TileRenderer(modelLoader.renderRegistry)
        worldRenderer = WorldRenderer(tileRenderer, itemRenderer)
        world = World(1, 1, 1)
        maxRenderZ = (world.depth - 1).coerceAtLeast(0)
        cameraTarget.set(
                (world.width / 2).toFloat(),
                (world.height / 2).toFloat(),
                (world.depth / 2).toFloat()
        )

        if (!VisUI.isLoaded()) VisUI.load()
        
        camera = PerspectiveCamera(67f, 1f, 1f)
        camera.near = 0.1f
        camera.far = 1000f
        updateCamera()

        orientationGizmo = OrientationGizmo(camera, modelBatch, shapeRenderer) {
            cameraTarget.set((world.width / 2).toFloat(), (world.height / 2).toFloat(), (world.depth / 2).toFloat())
            updateCamera()
        }

        environment = Environment()
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        environment.add(DirectionalLight().set(1.0f, 1.0f, 1.0f, -1f, -0.8f, -0.2f))

        stage = Stage(ScreenViewport())
        val scrollHandler = object : com.badlogic.gdx.InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                cameraDistance = (cameraDistance + amountY * 1.5f).coerceIn(2f, 100f)
                updateCamera()
                scrolledThisFrame = true
                return true
            }
        }
        Gdx.input.inputProcessor = com.badlogic.gdx.InputMultiplexer(scrollHandler, stage)

        createUI()
        updatePaletteHighlights()
        createFrameModel()
    }

    private fun createFrameModel() {
        val modelBuilder = ModelBuilder()
        modelBuilder.begin()
        val part =
                modelBuilder.part(
                        "frame",
                        GL20.GL_LINES,
                        (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked)
                                .toLong(),
                        Material()
                )

        val size = 0.5f
        part.setColor(Color.WHITE)
        // Bottom square
        part.line(-size, -size, -size, size, -size, -size)
        part.line(size, -size, -size, size, -size, size)
        part.line(size, -size, size, -size, -size, size)
        part.line(-size, -size, size, -size, -size, -size)

        // Top square
        part.line(-size, size, -size, size, size, -size)
        part.line(size, size, -size, size, size, size)
        part.line(size, size, size, -size, size, size)
        part.line(-size, size, size, -size, size, -size)

        // Vertical pillars
        part.line(-size, -size, -size, -size, size, -size)
        part.line(size, -size, -size, size, size, -size)
        part.line(size, -size, size, size, size, size)
        part.line(-size, -size, size, -size, size, size)

        frameModel = modelBuilder.end()
        frameInstance = ModelInstance(frameModel)

        hoverFrameInstance = ModelInstance(frameModel)
        hoverFrameInstance.materials.get(0).set(ColorAttribute.createDiffuse(Color.YELLOW))

        selectedFrameInstance = ModelInstance(frameModel)
        selectedFrameInstance.materials.get(0).set(ColorAttribute.createDiffuse(Color.CYAN))

        val sphereSize = 0.15f
        centerSphereModel =
                modelBuilder.createSphere(
                        sphereSize,
                        sphereSize,
                        sphereSize,
                        16,
                        16,
                        Material(ColorAttribute.createDiffuse(Color.RED)),
                        (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
                )
        centerSphereInstance = ModelInstance(centerSphereModel)

        // Tag indicator sphere (small white sphere rendered at node centre when tags are present)
        val tagSphereSize = 0.22f
        tagSphereModel = modelBuilder.createSphere(
            tagSphereSize, tagSphereSize, tagSphereSize, 12, 12,
            Material(ColorAttribute.createDiffuse(Color.WHITE)),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        )
        tagSphereInstance = ModelInstance(tagSphereModel)

        tagFont = BitmapFont()
        tagFont.color = Color.WHITE
        tagSpriteBatch = com.badlogic.gdx.graphics.g2d.SpriteBatch()
    }

    private fun createUI() {
        rootTable = VisTable()
        rootTable.setFillParent(true)
        stage.addActor(rootTable)

        // ── Row 1: Top Menu Bar ──────────────────────────────────────────────
        val menuBar = MenuBar()
        rootTable.add(menuBar.table).fillX().expandX().top().row()

        val fileMenu = Menu("File")
        menuBar.addMenu(fileMenu)
        fileMenu.addItem(MenuItem("New").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { newWorld() } }) })
        fileMenu.addItem(MenuItem("Open").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { openWorld() } }) })
        fileMenu.addItem(MenuItem("Save").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { saveWorld() } }) })
        fileMenu.addItem(MenuItem("Save As...").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { saveWorldAs() } }) })
        fileMenu.addItem(MenuItem("Exit").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { game.screen = MainMenuScreen(game) } }) })

        // ── Row 2: Main Area ─────────────────────────────────────────────────
        val mainRow = VisTable()
        rootTable.add(mainRow).fill().expand().row()

        // ── Left Tool Column ─────────────────────────────────────────────────
        val toolColumn = VisTable()
        toolColumn.background = VisUI.getSkin().getDrawable("window-bg")
        toolColumn.top()
        mainRow.add(toolColumn).width(60f).fillY()

        val gridIconPath = "/Users/sarnaut/work/workspace/roguelike/src/main/resources/icons/view-grid-outline.png"
        val gridIconFile = Gdx.files.absolute(gridIconPath)
        val gridToggleStyle = ImageButton.ImageButtonStyle(VisUI.getSkin().get(ImageButton.ImageButtonStyle::class.java))
        gridToggleStyle.up = VisUI.getSkin().newDrawable("white", Color.DARK_GRAY)
        gridToggleStyle.checked = VisUI.getSkin().newDrawable("white", Color.valueOf("4444FF"))
        if (gridIconFile.exists()) {
            val gridTex = Texture(gridIconFile)
            val d = TextureRegionDrawable(com.badlogic.gdx.graphics.g2d.TextureRegion(gridTex))
            gridToggleStyle.imageUp = d; gridToggleStyle.imageChecked = d
        }
        val gridToggle = ImageButton(gridToggleStyle)
        gridToggle.isChecked = showFrames
        gridToggle.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: com.badlogic.gdx.scenes.scene2d.Actor) {
                showFrames = gridToggle.isChecked
            }
        })
        toolColumn.add(gridToggle).size(48f).pad(8f).row()

        // ── Viewport | Palette: split pane with draggable handle ─────────

        // Left: viewport placeholder — 3D rendering fills this area via GL viewport
        viewportArea = VisTable()
        viewportArea.add(orientationGizmo).size(100f).top().left().pad(10f).expand().top().left()

        // Right: scrollable palette with dynamic column layout
        val paletteContent = VisTable()
        paletteContent.top()
        paletteScroll = VisScrollPane(paletteContent)
        paletteScroll.setFadeScrollBars(false)
        paletteScroll.setScrollingDisabled(true, false)
        paletteScroll.setCancelTouchFocus(false)  // allow child ClickListeners to receive touchUp

        val splitPane = VisSplitPane(viewportArea, paletteScroll, false)
        splitPane.setSplitAmount(0.75f)   // 75% viewport, 25% palette initially
        splitPane.setMinSplitAmount(0.5f)
        splitPane.setMaxSplitAmount(0.92f)
        mainRow.add(splitPane).fill().expand()

        // ── Palette: Tiles (grouped) ─────────────────────────────────────────
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

        fun addTileGroup(groupName: String, types: List<String>) {
            paletteContent.addSeparator().padTop(6f).padBottom(2f)
            paletteContent.add(VisLabel(groupName)).padLeft(8f).padBottom(2f).left().row()
            val grid = VisTable()
            paletteContent.add(grid).fillX().expandX().row()
            val containers = mutableListOf<Table>()
            types.forEach { type ->
                val tile = modelLoader.createTile(type)!!
                val container = SelectionBorderGroup {
                    paletteSelection.let { it is PaletteSelection.Tile && it.type == type }
                }
                container.add(TilePreviewActor(tile)).size(tilePreviewSize).pad(tileCellPad).row()
                container.add(VisLabel(type.removeSuffix("Tile"))).expandX().center()
                container.addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent, x: Float, y: Float) {
                        val sel = PaletteSelection.Tile(type)
                        paletteSelection = if (paletteSelection == sel) null else sel
                        refreshPaletteHighlights()
                        val state = if (paletteSelection != null) "selected" else "deselected"
                        Gdx.app.log("Palette", "Tile $state: $type")
                    }
                })
                containers.add(container)
                tileContainers[type] = container
            }
            tileGrids.add(grid to containers)
        }

        paletteContent.add(VisLabel("TILES")).pad(10f).row()
        tileGroups.forEach { (name, types) -> addTileGroup(name, types) }

        // ── Palette: Items ───────────────────────────────────────────────────
        paletteContent.addSeparator().padTop(10f).padBottom(4f)
        paletteContent.add(VisLabel("ITEMS")).pad(10f).row()
        itemGridTable = VisTable()
        paletteContent.add(itemGridTable).fillX().expandX().row()

        val items = listOf(
            Triple(Color.BLUE.toString(),  "Blue Key",  "Key"),
            Triple(Color.GREEN.toString(), "Green Key", "Key"),
            Triple(Color.RED.toString(),   "Red Key",   "Key")
        )
        items.forEach { (colorHex, name, _) ->
            val container = SelectionBorderGroup {
                paletteSelection.let { it is PaletteSelection.Item && it.name == name }
            }
            val preview = Image(VisUI.getSkin().getDrawable("white"))
            preview.color = Color.valueOf(colorHex)
            container.add(preview).size(32f).pad(5f).row()
            container.add(VisLabel(name)).expandX().center()
            container.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    val sel = PaletteSelection.Item(name, colorHex)
                    paletteSelection = if (paletteSelection == sel) null else sel
                    refreshPaletteHighlights()
                    val state = if (paletteSelection != null) "selected" else "deselected"
                    Gdx.app.log("Palette", "Item $state: $name")
                }
            })
            itemGrid.add(container)
            itemContainers[name] = container
        }

        // ── Palette: Tags ────────────────────────────────────────────────────
        paletteContent.addSeparator().padTop(10f).padBottom(4f)
        paletteContent.add(VisLabel("TAGS")).pad(10f).row()
        val nodeTags = listOf(
            NodeTags.PLAYER_SPAWN, NodeTags.ENEMY_SPAWN,
            NodeTags.ITEM_SPAWN,   NodeTags.EXIT,
            NodeTags.DOOR_MANUAL,  NodeTags.DOOR_KEY,
            NodeTags.DOOR_TOGGLE,  NodeTags.TOGGLE
        )
        nodeTags.forEach { tag ->
            val btn = VisTextButton(tag, "toggle")
            btn.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    val sel = PaletteSelection.Tag(tag)
                    paletteSelection = if (paletteSelection == sel) null else sel
                    refreshPaletteHighlights()
                    val state = if (paletteSelection != null) "selected" else "deselected"
                    Gdx.app.log("Palette", "Tag $state: $tag")
                }
            })
            val tagContainer = SelectionBorderGroup {
                paletteSelection.let { it is PaletteSelection.Tag && it.tag == tag }
            }
            tagContainer.add(btn).fillX()
            paletteContent.add(tagContainer).fillX().pad(2f).row()
            tagButtons[tag] = btn
        }

        // ── Row 3: Bottom Status Bar ─────────────────────────────────────────
        val bottomBar = VisTable()
        bottomBar.background = VisUI.getSkin().getDrawable("window-bg")
        rootTable.add(bottomBar).fillX().height(40f).row()

        bottomBar.add(VisLabel("X:")).padLeft(10f)
        xLabel = VisLabel(world.width.toString())
        yLabel = VisLabel(world.height.toString())
        zLabel = VisLabel(world.depth.toString())

        fun mkBtn(label: String, action: () -> Unit) = VisTextButton(label).also {
            it.addListener(object : ChangeListener() {
                override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { action() }
            })
        }

        bottomBar.add(mkBtn("-") { resize(world.width - 1, world.height, world.depth) }).width(28f)
        bottomBar.add(xLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { resize(world.width + 1, world.height, world.depth) }).width(28f)

        bottomBar.add(VisLabel("  Y:")).padLeft(10f)
        bottomBar.add(mkBtn("-") { resize(world.width, world.height - 1, world.depth) }).width(28f)
        bottomBar.add(yLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { resize(world.width, world.height + 1, world.depth) }).width(28f)

        bottomBar.add(VisLabel("  Z:")).padLeft(10f)
        bottomBar.add(mkBtn("-") { resize(world.width, world.height, world.depth - 1) }).width(28f)
        bottomBar.add(zLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { resize(world.width, world.height, world.depth + 1) }).width(28f)

        bottomBar.add(VisLabel("  Layer:")).padLeft(20f)
        layerLabel = VisLabel(maxRenderZ.toString())
        bottomBar.add(mkBtn("-") {
            maxRenderZ = (maxRenderZ - 1).coerceAtLeast(0)
            layerLabel.setText(maxRenderZ.toString())
        }).width(28f)
        bottomBar.add(layerLabel).width(28f).center()
        bottomBar.add(mkBtn("+") {
            maxRenderZ = (maxRenderZ + 1).coerceAtMost(world.depth - 1)
            layerLabel.setText(maxRenderZ.toString())
        }).width(28f)
    }

    private fun resize(nx: Int, ny: Int, nz: Int) {
        val oldWorld = world
        world = World(nx.coerceAtLeast(1), ny.coerceAtLeast(1), nz.coerceAtLeast(1))
        for (x in 0 until minOf(oldWorld.width, world.width)) {
            for (y in 0 until minOf(oldWorld.height, world.height)) {
                for (z in 0 until minOf(oldWorld.depth, world.depth)) {
                    val oldNode = oldWorld.getNode(x, y, z)!!
                    val newNode = world.getNode(x, y, z)!!
                    oldNode.tiles.forEach { newNode.setTile(it) }
                    oldNode.tags.forEach { world.addTag(newNode, it) }
                }
            }
        }
        maxRenderZ = world.depth - 1
        xLabel.setText(world.width.toString())
        yLabel.setText(world.height.toString())
        zLabel.setText(world.depth.toString())
        layerLabel.setText(maxRenderZ.toString())
        updateCamera()
    }

    private fun toggleTag(tag: String) {
        val node = world.getNode(selectedX, selectedY, selectedZ) ?: return
        if (node.tags.contains(tag)) {
            world.removeTag(node, tag)
        } else {
            world.addTag(node, tag)
        }
        updatePaletteHighlights()
    }

    // Refresh palette border highlights to reflect the current paletteSelection (radio-style)
    private fun refreshPaletteHighlights() {
        // SelectionBorderGroup instances draw their own cyan border via isSelected lambda.
        // Only tag button checked-state needs explicit updating here.
        val sel = paletteSelection
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = sel is PaletteSelection.Tag && sel.tag == tag
        }
    }

    // Update palette highlights based on the SELECTED WORLD NODE's content (dark-gray = node has it)
    private fun updatePaletteHighlights() {
        val node = world.getNode(selectedX, selectedY, selectedZ)
        if (node == null) { refreshPaletteHighlights(); return }
        val sel = paletteSelection
        // SelectionBorderGroup draws cyan border automatically; only set dark-gray for "node has tile" state
        val darkGray = VisUI.getSkin().newDrawable("white", Color.DARK_GRAY)
        tileContainers.forEach { (type, table) ->
            table.background = if (node.hasTileType(type) &&
                                   !(sel is PaletteSelection.Tile && sel.type == type)) darkGray
                               else null
        }
        // Tag button checked = node has tag OR tag is the active selection
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = (sel is PaletteSelection.Tag && sel.tag == tag) || node.tags.contains(tag)
        }
    }

    /**
     * Re-lays out tile and item grids based on the current palette width.
     * Computes how many columns fit and rebuilds each grid table.
     */
    private fun relayoutPaletteGrids(paletteWidth: Float) {
        if (paletteWidth == lastPaletteWidth || paletteWidth <= 0f) return
        lastPaletteWidth = paletteWidth

        val cellWidth = tilePreviewSize + tileCellPad * 2  // size + pad on each side
        val cols = maxOf(1, (paletteWidth / cellWidth).toInt())

        // Re-layout tile grids
        for ((grid, containers) in tileGrids) {
            grid.clearChildren()
            containers.forEachIndexed { index, container ->
                grid.add(container).pad(tileCellPad)
                if ((index + 1) % cols == 0) grid.row()
            }
        }

        // Re-layout item grid
        val itemCellWidth = 32f + tileCellPad * 2
        val itemCols = maxOf(1, (paletteWidth / itemCellWidth).toInt())
        itemGridTable.clearChildren()
        itemGrid.forEachIndexed { index, container ->
            itemGridTable.add(container).pad(tileCellPad)
            if ((index + 1) % itemCols == 0) itemGridTable.row()
        }
    }

    inner class TilePreviewActor(val tile: com.roguelike.core.model.Tile) : S2DActor() {
        init { touchable = Touchable.disabled }  // clicks fall through to the container's listener
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            // Save scissor state so the ScrollPane's clipping is preserved after our GL work
            val scissorWasEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
            val scissorBox = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)  // must be direct for glGetIntegerv
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
            // Restore scissor
            if (scissorWasEnabled) {
                Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
                Gdx.gl.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            } else {
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            }
            batch.begin()
        }
    }

    /**
     * A VisTable wrapper that draws a 3-pixel cyan border frame around its entire bounds
     * after all children have been drawn. The border appears on top of any 3D GL content
     * rendered by child TilePreviewActors because it draws via the sprite batch after
     * super.draw() returns.
     */
    inner class SelectionBorderGroup(val isSelected: () -> Boolean) : VisTable() {
        init { touchable = Touchable.enabled }
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            super.draw(batch, parentAlpha)  // draws background + all children (including 3D previews)
            if (isSelected()) {
                val d = VisUI.getSkin().getDrawable("white")
                val bord = 3f
                batch.setColor(Color.CYAN)
                d.draw(batch, x, y,              width, bord)           // bottom edge
                d.draw(batch, x, y + height - bord, width, bord)       // top edge
                d.draw(batch, x, y,              bord,  height)          // left edge
                d.draw(batch, x + width - bord,  y,     bord,  height)   // right edge
                batch.setColor(Color.WHITE)
            }
        }
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle
        while (a <= -180f) a += 360f
        while (a > 180f) a -= 360f
        return a
    }

    private fun updateCamera() {
        // Standard orbit camera: Y is up, pitch is elevation from XZ plane, yaw rotates around Y
        val pitchRad = Math.toRadians(cameraPitch.toDouble())
        val yawRad = Math.toRadians(cameraYaw.toDouble())

        val cosPitch = Math.cos(pitchRad).toFloat()
        val sinPitch = Math.sin(pitchRad).toFloat()
        val sinYaw = Math.sin(yawRad).toFloat()
        val cosYaw = Math.cos(yawRad).toFloat()

        val offsetX = cameraDistance * cosPitch * sinYaw
        val offsetY = cameraDistance * sinPitch
        val offsetZ = cameraDistance * cosPitch * cosYaw

        camera.position.set(
            cameraTarget.x + offsetX,
            cameraTarget.y + offsetY,
            cameraTarget.z + offsetZ
        )
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(cameraTarget)
        camera.update()
    }

    private fun newWorld() {
        world = World(1, 1, 1)
        maxRenderZ = (world.depth - 1).coerceAtLeast(0)
        currentFilePath = null
        selectedX = -1
        selectedY = -1
        selectedZ = -1
        cameraPitch = 45f
        cameraYaw = 0f
        cameraTarget.set((world.width / 2f), (world.height / 2f), (world.depth / 2f))
        updateCamera()
    }

    private fun openWorld() {
        if (isDialogActive) return
        isDialogActive = true
        Thread {
            try {
                val path = PlatformUtils.chooseFile("wld")
                path?.let { filePath ->
                    val loadedWorld = WorldIO.loadWorld(filePath, { w, h, d -> World(w, h, d) }, { type -> modelLoader.createTile(type) })
                    if (loadedWorld != null) {
                        Gdx.app.postRunnable {
                            world = loadedWorld
                            currentFilePath = filePath
                            maxRenderZ = world.depth - 1
                            xLabel.setText(world.width.toString())
                            yLabel.setText(world.height.toString())
                            zLabel.setText(world.depth.toString())
                            layerLabel.setText(maxRenderZ.toString())
                            cameraPitch = 45f
                            cameraYaw = 0f
                            cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
                            updateCamera()
                        }
                    }
                }
            } finally {
                isDialogActive = false
            }
        }.start()
    }

    private fun saveWorld() {
        val path = currentFilePath
        if (path == null) saveWorldAs() else WorldIO.saveWorld(path, world)
    }

    private fun saveWorldAs() {
        if (isDialogActive) return
        isDialogActive = true
        Thread {
            try {
                val path = PlatformUtils.chooseFileName("world.wld")
                path?.let { filePath ->
                    var finalPath = filePath
                    if (!finalPath.endsWith(".wld")) finalPath += ".wld"
                    WorldIO.saveWorld(finalPath, world)
                    Gdx.app.postRunnable { currentFilePath = finalPath }
                }
            } finally {
                isDialogActive = false
            }
        }.start()
    }

    override fun render(delta: Float) {
        handleInput(delta)
        updateHover()
        relayoutPaletteGrids(paletteScroll.width)

        val bw = Gdx.graphics.backBufferWidth.toFloat()
        val bh = Gdx.graphics.backBufferHeight.toFloat()
        val ratioX = bw / stage.width
        val ratioY = bh / stage.height

        // Calculate 3D viewport in backbuffer pixels
        val screenPos = viewportArea.localToStageCoordinates(Vector2(0f, 0f))
        val viewX = (screenPos.x * ratioX).toInt()
        val viewY = (screenPos.y * ratioY).toInt()
        val viewW = (viewportArea.width * ratioX).toInt()
        val viewH = (viewportArea.height * ratioY).toInt()

        lastViewX = viewX; lastViewY = viewY; lastViewW = viewW; lastViewH = viewH

        // ── Clear full screen for UI background ──────────────────────────────
        Gdx.gl.glViewport(0, 0, bw.toInt(), bh.toInt())
        Gdx.gl.glClearColor(0.15f, 0.15f, 0.15f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        if (viewW > 0 && viewH > 0) {
            // ── Scissor + viewport constrained to viewportArea ────────────────
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(viewX, viewY, viewW, viewH)
            Gdx.gl.glViewport(viewX, viewY, viewW, viewH)

            // Clear ONLY the 3D area (colour + depth)
            Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

            // Update camera to match this sub-viewport
            camera.viewportWidth = viewW.toFloat()
            camera.viewportHeight = viewH.toFloat()
            camera.update()

            // ── Render world ──────────────────────────────────────────────────
            modelBatch.begin(camera)
            worldRenderer.render(world, modelBatch, environment, maxRenderZ)

            // ── Full grid — only when grid toggle is on ───────────────────────
            if (showFrames) {
                for (x in 0 until world.width) {
                    for (y in 0 until world.height) {
                        for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                            if (x == selectedX && y == selectedY && z == selectedZ) continue
                            if (x == hoveredX  && y == hoveredY  && z == hoveredZ)  continue
                            frameInstance.transform.setToTranslation(x.toFloat(), y.toFloat(), z.toFloat())
                            modelBatch.render(frameInstance)
                        }
                    }
                }
            }
            modelBatch.end()

            // ── Hover + selection indicators — rendered with thicker lines ─────
            Gdx.gl.glLineWidth(3f)
            modelBatch.begin(camera)
            if (hoveredX != -1) {
                hoverFrameInstance.transform.setToTranslation(hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
                modelBatch.render(hoverFrameInstance)
            }
            if (selectedX != -1) {
                selectedFrameInstance.transform.setToTranslation(selectedX.toFloat(), selectedY.toFloat(), selectedZ.toFloat())
                modelBatch.render(selectedFrameInstance)
            }
            modelBatch.end()
            Gdx.gl.glLineWidth(1f)

            // ── Tag spheres — one per tagged node ────────────────────────────
            modelBatch.begin(camera)
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isEmpty()) continue
                        tagSphereInstance.transform.setToTranslation(x.toFloat(), y.toFloat(), z.toFloat())
                        modelBatch.render(tagSphereInstance, environment)
                    }
                }
            }
            modelBatch.end()

            // ── Tag text labels — projected to viewport 2D ────────────────────
            // Project each tagged node's world position to viewport pixel coords.
            val projPos = Vector3()
            tagSpriteBatch.setProjectionMatrix(
                com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW.toFloat(), viewH.toFloat())
            )
            tagSpriteBatch.begin()
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isEmpty()) continue
                        // Project world position slightly above the sphere
                        projPos.set(x.toFloat(), y.toFloat() + 0.45f, z.toFloat())
                        camera.project(projPos, 0f, 0f, viewW.toFloat(), viewH.toFloat())
                        if (projPos.z in 0f..1f) {
                            val label = node.tags.joinToString("\n")
                            tagFont.draw(tagSpriteBatch, label, projPos.x - 30f, projPos.y + 4f)
                        }
                    }
                }
            }
            tagSpriteBatch.end()

            // ── Association lines ─────────────────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            shapeRenderer.projectionMatrix = camera.combined
            shapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color.CYAN
            world.associations.forEach { assoc ->
                shapeRenderer.line(
                    assoc.source.x.toFloat(), assoc.source.y.toFloat(), assoc.source.z.toFloat(),
                    assoc.target.x.toFloat(), assoc.target.y.toFloat(), assoc.target.z.toFloat()
                )
            }
            shapeRenderer.end()

            // ── 2D crosshair at viewport centre (= rotation pivot) ────────────
            val cx = viewW / 2f
            val cy = viewH / 2f
            val crossSize = 12f
            val ortho = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW.toFloat(), viewH.toFloat())
            shapeRenderer.projectionMatrix = ortho
            shapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color(1f, 1f, 1f, 0.8f)
            shapeRenderer.line(cx - crossSize, cy, cx + crossSize, cy)
            shapeRenderer.line(cx, cy - crossSize, cx, cy + crossSize)
            shapeRenderer.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Done with 3D — remove scissor, restore full viewport ──────────
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glViewport(0, 0, bw.toInt(), bh.toInt())
        }

        // ── Draw Stage UI on top ──────────────────────────────────────────────
        stage.act(delta)
        stage.draw()
    }

    private fun updateHover() {
        val scaleX = Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width
        val scaleY = Gdx.graphics.backBufferHeight.toFloat() / Gdx.graphics.height

        // Bounds check in backbuffer pixels (lastViewX/Y/W/H are in backbuffer space)
        val mouseXPx = Gdx.input.x * scaleX
        val mouseYPx = (Gdx.graphics.height - Gdx.input.y) * scaleY  // GL bottom-left origin

        if (lastViewW <= 0 || lastViewH <= 0 ||
            mouseXPx < lastViewX || mouseXPx > lastViewX + lastViewW ||
            mouseYPx < lastViewY || mouseYPx > lastViewY + lastViewH) {
            hoveredX = -1; return
        }

        // Camera.getPickRay() uses Gdx.graphics.getHeight() (logical) internally for Y-flip.
        // We must pass LOGICAL pixel coordinates, converting the backbuffer viewport bounds back.
        val ray = camera.getPickRay(
            Gdx.input.x.toFloat(),          // logical X from left
            Gdx.input.y.toFloat(),          // logical Y from top (getPickRay flips internally)
            lastViewX / scaleX,             // viewport X in logical pixels
            lastViewY / scaleY,             // viewport Y in logical pixels (from bottom)
            lastViewW / scaleX,             // viewport W in logical pixels
            lastViewH / scaleY              // viewport H in logical pixels
        )

        var bestT = Float.MAX_VALUE
        hoveredX = -1
        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                    val halfSize = 0.5f
                    val minX = x - halfSize; val maxX = x + halfSize
                    val minY = y - halfSize; val maxY = y + halfSize
                    val minZ = z - halfSize; val maxZ = z + halfSize
                    var tmin = Float.NEGATIVE_INFINITY; var tmax = Float.POSITIVE_INFINITY
                    if (Math.abs(ray.direction.x) > 0.00001f) {
                        val t1 = (minX - ray.origin.x) / ray.direction.x; val t2 = (maxX - ray.origin.x) / ray.direction.x
                        tmin = maxOf(tmin, minOf(t1, t2)); tmax = minOf(tmax, maxOf(t1, t2))
                    } else if (ray.origin.x < minX || ray.origin.x > maxX) continue
                    if (Math.abs(ray.direction.y) > 0.00001f) {
                        val t1 = (minY - ray.origin.y) / ray.direction.y; val t2 = (maxY - ray.origin.y) / ray.direction.y
                        tmin = maxOf(tmin, minOf(t1, t2)); tmax = minOf(tmax, maxOf(t1, t2))
                    } else if (ray.origin.y < minY || ray.origin.y > maxY) continue
                    if (Math.abs(ray.direction.z) > 0.00001f) {
                        val t1 = (minZ - ray.origin.z) / ray.direction.z; val t2 = (maxZ - ray.origin.z) / ray.direction.z
                        tmin = maxOf(tmin, minOf(t1, t2)); tmax = minOf(tmax, maxOf(t1, t2))
                    } else if (ray.origin.z < minZ || ray.origin.z > maxZ) continue
                    if (tmax < tmin || tmax < 0) continue
                    if (tmin < bestT) { bestT = tmin; hoveredX = x; hoveredY = y; hoveredZ = z }
                }
            }
        }
    }

    private fun handleInput(delta: Float) {
        val dragging = Math.abs(Gdx.input.deltaX) > 1 || Math.abs(Gdx.input.deltaY) > 1

        // Middle mouse button drag → rotate (skip if scroll wheel just fired to avoid false orbit)
        if (Gdx.input.isButtonPressed(Input.Buttons.MIDDLE) && dragging && !scrolledThisFrame) {
            val dx = Gdx.input.deltaX.toFloat()
            val dy = Gdx.input.deltaY.toFloat()
            cameraYaw   = wrapAngle(cameraYaw   - dx * 0.5f)
            cameraPitch = (cameraPitch + dy * 0.5f).coerceIn(-89f, 89f)
            updateCamera()
        }
        scrolledThisFrame = false

        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && dragging) {
            val dx = Gdx.input.deltaX.toFloat()
            val dy = Gdx.input.deltaY.toFloat()
            val isShift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
            val isAlt   = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)   || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)

            when {
                isAlt -> {
                    // Alt/Option + drag → rotate
                    cameraYaw   = wrapAngle(cameraYaw   - dx * 0.5f)
                    cameraPitch = (cameraPitch + dy * 0.5f).coerceIn(-89f, 89f)
                    updateCamera()
                }
                isShift -> {
                    // Shift + drag → pan
                    val sensitivity = cameraDistance / 800f
                    val camRight = camera.direction.cpy().crs(camera.up).nor()
                    val camUp    = camRight.cpy().crs(camera.direction).nor()
                    cameraTarget.add(camRight.scl(-dx * sensitivity))
                    cameraTarget.add(camUp.scl(dy * sensitivity))
                    updateCamera()
                }
                // plain drag → no action; left click selects (handled below)
            }
        }

        // Selection/paint: only when no camera modifier is held
        val isShiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
        val isAltHeld   = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)   || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)
        val isCtrlHeld  = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
        val noModifier  = !isShiftHeld && !isAltHeld && !isCtrlHeld

        // ── Ctrl + LMB: erase selected palette item from hovered node ─────────
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && isCtrlHeld && !isShiftHeld && !isAltHeld
            && paletteSelection != null && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ)
            val isNewEraseNode = hoveredX != lastEraseX || hoveredY != lastEraseY || hoveredZ != lastEraseZ
            when (val sel = paletteSelection) {
                is PaletteSelection.Tile -> {
                    // Continuous erase: remove tile from every node the cursor passes over
                    if (node != null && node.removeTileByType(sel.type)) {
                        Gdx.app.log("MapEditor", "Erased ${sel.type} from ($hoveredX, $hoveredY, $hoveredZ)")
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                is PaletteSelection.Item -> {
                    // Remove item from each new node entered
                    if (node != null && isNewEraseNode) {
                        node.items.removeIf { it is KeyItem && it.name == sel.name }
                        Gdx.app.log("MapEditor", "Removed ${sel.name} from ($hoveredX, $hoveredY, $hoveredZ)")
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                is PaletteSelection.Tag -> {
                    // Remove tag from each new node entered
                    if (isNewEraseNode) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        val n = world.getNode(hoveredX, hoveredY, hoveredZ)
                        if (n != null && n.tags.remove(sel.tag)) {
                            updatePaletteHighlights()
                            Gdx.app.log("MapEditor", "Removed tag ${sel.tag} from ($hoveredX, $hoveredY, $hoveredZ)")
                        }
                        lastEraseX = hoveredX; lastEraseY = hoveredY; lastEraseZ = hoveredZ
                    }
                }
                null -> { /* nothing selected, nothing to erase */ }
            }
        } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            lastEraseX = -1; lastEraseY = -1; lastEraseZ = -1
        }

        // ── Normal LMB: paint / select ────────────────────────────────────────
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT) && noModifier && hoveredX != -1) {
            val node = world.getNode(hoveredX, hoveredY, hoveredZ)
            // True when mouse has moved to a node it hasn't painted in this press
            val isNewNode = hoveredX != lastPaintX || hoveredY != lastPaintY || hoveredZ != lastPaintZ

            when (val sel = paletteSelection) {
                is PaletteSelection.Tile -> {
                    // Continuous paint: add tile to every node the cursor passes over
                    if (node != null && !node.hasTileType(sel.type)) {
                        node.setTile(modelLoader.createTile(sel.type)!!)
                        Gdx.app.log("MapEditor", "Painted ${sel.type} → ($hoveredX, $hoveredY, $hoveredZ)")
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.Item -> {
                    // Apply to each new node the cursor enters while LMB is held
                    if (node != null && isNewNode) {
                        node.items.removeIf { it is KeyItem }
                        node.items.add(KeyItem(colorHex = sel.colorHex, name = sel.name))
                        Gdx.app.log("MapEditor", "Placed ${sel.name} → ($hoveredX, $hoveredY, $hoveredZ)")
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                is PaletteSelection.Tag -> {
                    // Add tag to each new node entered; toggle (remove) only on single click of same node
                    if (isNewNode) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        val n = world.getNode(hoveredX, hoveredY, hoveredZ)
                        if (n != null && !n.tags.contains(sel.tag)) {
                            world.addTag(n, sel.tag)
                            updatePaletteHighlights()
                            Gdx.app.log("MapEditor", "Added tag ${sel.tag} → ($hoveredX, $hoveredY, $hoveredZ)")
                        } else if (n != null && Gdx.input.justTouched()) {
                            // Single click on a node that already has the tag → toggle off
                            n.tags.remove(sel.tag)
                            updatePaletteHighlights()
                            Gdx.app.log("MapEditor", "Removed tag ${sel.tag} from ($hoveredX, $hoveredY, $hoveredZ)")
                        }
                        lastPaintX = hoveredX; lastPaintY = hoveredY; lastPaintZ = hoveredZ
                    }
                }
                null -> {
                    // Nothing selected — plain click selects the node
                    if (Gdx.input.justTouched()) {
                        selectedX = hoveredX; selectedY = hoveredY; selectedZ = hoveredZ
                        Gdx.app.log("MapEditor", "Selected node: ($selectedX, $selectedY, $selectedZ)")
                        updatePaletteHighlights()
                    }
                }
            }
        } else if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            // Reset paint tracker when LMB is released
            lastPaintX = -1; lastPaintY = -1; lastPaintZ = -1
        }

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

    override fun resize(width: Int, height: Int) {
        // Camera viewport is managed per-frame from the sub-viewport bounds.
        // Only update the Stage viewport here.
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}

    override fun dispose() {
        modelBatch.dispose()
        shapeRenderer.dispose()
        assetLoader.dispose()
        frameModel.dispose()
        centerSphereModel.dispose()
        tagSphereModel.dispose()
        tagFont.dispose()
        tagSpriteBatch.dispose()
        orientationGizmo.dispose()
        VisUI.dispose()
    }
}
