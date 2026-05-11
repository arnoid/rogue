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
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.*
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.WorldNode
import com.roguelike.editor.*
import com.roguelike.rendering.*
import com.roguelike.serialization.WorldIO
import com.roguelike.utils.*
import com.roguelike.world.*
import com.roguelike.generation.SubmapTemplate
import com.roguelike.generation.RotatedTileRef

class MapEditor(private val game: Game) : Screen {
    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var shapeRenderer: ShapeRenderer

    private lateinit var assetLoader: AssetLoader
    private lateinit var modelLoader: ModelLoader
    private lateinit var world: World

    private lateinit var stage: Stage

    private lateinit var tileRenderer: TileRenderer
    private lateinit var worldRenderer: WorldRenderer
    private lateinit var itemRenderer: ItemRenderer

    private var showFrames = true
    private lateinit var frameModel: Model
    private lateinit var frameInstance: ModelInstance
    private lateinit var hoverFrameInstance: ModelInstance
    private lateinit var selectedFrameInstance: ModelInstance
    private lateinit var tagSphereModel: Model
    private lateinit var tagSphereInstance: ModelInstance
    private lateinit var tagFont: BitmapFont
    private lateinit var tagSpriteBatch: com.badlogic.gdx.graphics.g2d.SpriteBatch

    private var isDialogActive = false
    private var currentFilePath: String? = null

    private val recentFiles = mutableListOf<String>()
    private lateinit var fileMenu: Menu
    private val maxRecentFiles = 5

    private var maxRenderZ = 0
    private var hoveredX = -1
    private var hoveredY = -1
    private var hoveredZ = -1
    private var hoveredEdge: TileSlot? = null

    // Camera control
    private var cameraDistance = 20f
    private val cameraTarget = Vector3(0f, 0f, 0f)
    private var cameraPitch = 60f
    private var cameraYaw = 180f

    private lateinit var palette: EditorPalettePanel
    private lateinit var statusBar: EditorStatusBar
    private lateinit var inputHandler: EditorInputHandler
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
        world = World(6, 6, 3)
        maxRenderZ = world.depth - 1
        cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)

        if (!VisUI.isLoaded()) VisUI.load()

        camera = PerspectiveCamera(67f, 1f, 1f)
        camera.near = 0.1f
        camera.far = 1000f
        updateCamera()

        environment = Environment()
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        environment.add(DirectionalLight().set(1.0f, 1.0f, 1.0f, -1f, -0.8f, -0.2f))

        stage = Stage(ScreenViewport())

        // Scroll wheel handler — zoom or delegate to UI scroll panes
        val scrollHandler = object : com.badlogic.gdx.InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val stageX = Gdx.input.x.toFloat()
                val stageY = (Gdx.graphics.height - Gdx.input.y).toFloat()
                val hitActor = stage.hit(stageX, stageY, true)
                if (hitActor != null) {
                    var actor: com.badlogic.gdx.scenes.scene2d.Actor? = hitActor
                    while (actor != null) {
                        if (actor is com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) {
                            stage.scrolled(amountX, amountY)
                            return true
                        }
                        actor = actor.parent
                    }
                }
                val moveAmount = amountY * 1.5f
                val forward = camera.direction.cpy().nor().scl(-moveAmount)
                cameraTarget.add(forward)
                updateCamera()
                scrolledThisFrame = true
                return true
            }
        }
        Gdx.input.inputProcessor = com.badlogic.gdx.InputMultiplexer(scrollHandler, stage)

        palette = EditorPalettePanel(modelLoader, tileRenderer, modelBatch, stage)
        statusBar = EditorStatusBar({ world }, ::resizeWorld)
        statusBar.maxRenderZ = maxRenderZ
        inputHandler = EditorInputHandler(
            { world }, modelLoader, palette,
            onCameraOrbit = { dx, dy ->
                val oldYaw = cameraYaw
                val oldPitch = cameraPitch
                cameraYaw = wrapAngle(cameraYaw - dx * 0.5f)
                cameraPitch = (cameraPitch + dy * 0.5f).coerceIn(-89f, 90f)

                // Rotate cameraTarget around the world center so the orbit
                // pivots on the world, not on the (possibly panned) target.
                val worldCenter = com.badlogic.gdx.math.Vector3(
                    world.width / 2f, world.height / 2f, world.depth / 2f
                )
                val deltaYaw = cameraYaw - oldYaw
                val deltaPitch = cameraPitch - oldPitch
                val offset = cameraTarget.cpy().sub(worldCenter)
                val rotMatrix = com.badlogic.gdx.math.Matrix4()
                rotMatrix.rotate(com.badlogic.gdx.math.Vector3.Z, -deltaYaw)
                // Compute the horizontal axis for pitch rotation
                val yawRad = Math.toRadians(cameraYaw.toDouble())
                val pitchAxis = com.badlogic.gdx.math.Vector3(
                    Math.cos(yawRad).toFloat(), -Math.sin(yawRad).toFloat(), 0f
                )
                rotMatrix.rotate(pitchAxis, deltaPitch)
                offset.mul(rotMatrix)
                cameraTarget.set(worldCenter).add(offset)

                updateCamera()
            },
            onCameraPan = { dx, dy ->
                val sensitivity = cameraDistance / 800f
                val camRight = camera.direction.cpy().crs(camera.up).nor()
                val camUp = camRight.cpy().crs(camera.direction).nor()
                cameraTarget.add(camRight.scl(-dx * sensitivity))
                cameraTarget.add(camUp.scl(dy * sensitivity))
                updateCamera()
            },
            onCameraZoom = { amount ->
                val forward = camera.direction.cpy().nor().scl(-amount * 1.5f)
                cameraTarget.add(forward)
                updateCamera()
            },
            onUpdatePaletteHighlights = {
                val node = world.getNode(inputHandler.selectedX, inputHandler.selectedY, inputHandler.selectedZ)
                palette.updateHighlightsForNode(node)
            }
        )

        createUI()
        createFrameModel()

        orientationGizmo = OrientationGizmo(camera, modelBatch, shapeRenderer) {
            cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
            cameraPitch = 60f; cameraYaw = 180f
            updateCamera()
        }
        viewportArea.add(orientationGizmo).size(100f).top().left().pad(10f).expand().top().left()
    }

    private fun createFrameModel() {
        val modelBuilder = ModelBuilder()
        modelBuilder.begin()
        val part = modelBuilder.part(
            "frame", GL20.GL_LINES,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(),
            Material()
        )
        val s = 0.5f
        part.setColor(Color.WHITE)
        part.line(-s, -s, -s, s, -s, -s); part.line(s, -s, -s, s, -s, s)
        part.line(s, -s, s, -s, -s, s); part.line(-s, -s, s, -s, -s, -s)
        part.line(-s, s, -s, s, s, -s); part.line(s, s, -s, s, s, s)
        part.line(s, s, s, -s, s, s); part.line(-s, s, s, -s, s, -s)
        part.line(-s, -s, -s, -s, s, -s); part.line(s, -s, -s, s, s, -s)
        part.line(s, -s, s, s, s, s); part.line(-s, -s, s, -s, s, s)
        frameModel = modelBuilder.end()
        frameInstance = ModelInstance(frameModel)

        hoverFrameInstance = ModelInstance(frameModel)
        hoverFrameInstance.materials.get(0).set(ColorAttribute.createDiffuse(Color.YELLOW))

        selectedFrameInstance = ModelInstance(frameModel)
        selectedFrameInstance.materials.get(0).set(ColorAttribute.createDiffuse(Color.CYAN))

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

        // ── Menu Bar ──────────────────────────────────────────────────────
        val menuBar = MenuBar()
        rootTable.add(menuBar.table).fillX().expandX().top().row()
        fileMenu = Menu("File")
        menuBar.addMenu(fileMenu)
        loadRecentFiles()
        rebuildFileMenu()

        // ── Main Area: toolbar | viewport | palette ─────────────────────────
        val mainRow = VisTable()
        rootTable.add(mainRow).fill().expand().row()

        // ── Left toolbar column ─────────────────────────────────────────
        val toolbar = VisTable()
        toolbar.top().pad(4f)
        toolbar.background = VisUI.getSkin().newDrawable("white", Color(0.15f, 0.15f, 0.15f, 1f))

        try {
            val gridTex = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("icons/view-grid-outline.png"))
            val gridDrawable = com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(
                com.badlogic.gdx.graphics.g2d.TextureRegion(gridTex)
            )
            val gridBtn = VisImageButton(gridDrawable)
            gridBtn.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float) {
                    showFrames = !showFrames
                }
            })
            toolbar.add(gridBtn).size(32f).pad(2f).row()

            val ccwTex = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("icons/rotate-counter-clockwise.png"))
            val ccwDrawable = com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(
                com.badlogic.gdx.graphics.g2d.TextureRegion(ccwTex)
            )
            val ccwBtn = VisImageButton(ccwDrawable)
            ccwBtn.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float) {
                    rotateWorld(clockwise = false)
                }
            })
            toolbar.add(ccwBtn).size(32f).pad(2f).row()

            val cwTex = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("icons/rotate-clockwise.png"))
            val cwDrawable = com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(
                com.badlogic.gdx.graphics.g2d.TextureRegion(cwTex)
            )
            val cwBtn = VisImageButton(cwDrawable)
            cwBtn.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float) {
                    rotateWorld(clockwise = true)
                }
            })
            toolbar.add(cwBtn).size(32f).pad(2f).row()
        } catch (e: Exception) {
            toolbar.add(VisLabel("!")).row()
        }

        mainRow.add(toolbar).fillY().expandY().width(40f)

        viewportArea = VisTable()

        // Gizmo will be added after orientationGizmo is created (in show())

        val paletteContent = palette.buildContent()
        paletteContent.pad(0f, 8f, 0f, 8f)
        paletteContent.background = VisUI.getSkin().newDrawable("white", Color(0.2f, 0.2f, 0.2f, 1f))
        paletteScroll = VisScrollPane(paletteContent)
        paletteScroll.setFadeScrollBars(false)
        paletteScroll.setScrollingDisabled(true, false)
        paletteScroll.setCancelTouchFocus(false)

        val splitPane = VisSplitPane(viewportArea, paletteScroll, false)
        splitPane.setSplitAmount(0.8f)
        splitPane.setMinSplitAmount(0.5f)
        splitPane.setMaxSplitAmount(0.92f)
        mainRow.add(splitPane).fill().expand()

        // ── Status Bar ────────────────────────────────────────────────────
        rootTable.add(statusBar.build()).fillX().height(40f).row()
    }

    private fun resizeWorld(nx: Int, ny: Int, nz: Int) {
        val w = nx.coerceAtLeast(3)
        val h = ny.coerceAtLeast(3)
        val d = nz.coerceAtLeast(3)
        // Round up to nearest multiple of 3
        val aw = ((w + 2) / 3) * 3
        val ah = ((h + 2) / 3) * 3
        val ad = ((d + 2) / 3) * 3

        val oldWorld = world
        world = World(aw, ah, ad)
        for (x in 0 until minOf(oldWorld.width, world.width)) {
            for (y in 0 until minOf(oldWorld.height, world.height)) {
                for (z in 0 until minOf(oldWorld.depth, world.depth)) {
                    val oldNode = oldWorld.getNode(x, y, z)!!
                    val newNode = world.getNode(x, y, z)!!
                    oldNode.tiles.forEach { newNode.setTile(it) }
                    oldNode.tags.forEach { world.addTag(newNode, it) }
                    oldNode.doorSlots.forEach { newNode.tagAsDoor(it) }
                    oldNode.manualDoorSlots.forEach { newNode.tagAsManualDoor(it) }
                    oldNode.connectorSlots.forEach { newNode.tagAsConnector(it) }
                }
            }
        }
        maxRenderZ = world.depth - 1
        statusBar.refresh(world)
        updateCamera()
    }

    /**
     * Rotates the entire world 90° around the Z axis.
     * Uses the procedural generation rotation to structurally rearrange nodes,
     * then recreates proper tiles via the tile factory.
     */
    private fun rotateWorld(clockwise: Boolean) {
        val template = SubmapTemplate.fromWorld("editor", world)
        // CW = one CW rotation, CCW = three CW rotations
        val steps = if (clockwise) 1 else 3
        var rotated = template
        repeat(steps) { rotated = rotated.rotatedCW90() }

        val rotatedWorldData = rotated.worldData
        // Create a new world and stamp with proper tiles from factory
        val newWorld = World(rotatedWorldData.width, rotatedWorldData.height, rotatedWorldData.depth)
        for (x in 0 until rotatedWorldData.width) {
            for (y in 0 until rotatedWorldData.height) {
                for (z in 0 until rotatedWorldData.depth) {
                    val srcNode = rotatedWorldData.getNode(x, y, z) ?: continue
                    val dstNode = newWorld.getNode(x, y, z) ?: continue

                    for (tile in srcNode.tiles) {
                        if (tile is RotatedTileRef) {
                            val newTile = modelLoader.createTile(tile.rotatedType)
                            if (newTile != null) {
                                if (newTile is BaseTile) {
                                    if (tile.useFactoryDefaults) {
                                        // Wall/door: factory already set correct rotation/offset
                                        if (tile.originalTile is BaseTile) {
                                            newTile.zOffset = tile.originalTile.zOffset
                                        }
                                    } else if (tile.originalTile is BaseTile) {
                                        // Non-directional tile (floor, stairs): copy + add rotation
                                        val orig = tile.originalTile
                                        newTile.rotationX = orig.rotationX
                                        newTile.rotationY = orig.rotationY + tile.additionalRotY
                                        newTile.rotationZ = orig.rotationZ
                                        newTile.xOffset = orig.xOffset
                                        newTile.yOffset = orig.yOffset
                                        newTile.zOffset = orig.zOffset
                                    }
                                }
                                dstNode.setTile(newTile)
                            }
                        } else {
                            val newTile = modelLoader.createTile(tile.type)
                            if (newTile != null) {
                                if (tile is BaseTile && newTile is BaseTile) {
                                    newTile.rotationX = tile.rotationX
                                    newTile.rotationY = tile.rotationY
                                    newTile.rotationZ = tile.rotationZ
                                    newTile.xOffset = tile.xOffset
                                    newTile.yOffset = tile.yOffset
                                    newTile.zOffset = tile.zOffset
                                }
                                dstNode.setTile(newTile)
                            }
                        }
                    }

                    for (slot in srcNode.doorSlots) dstNode.tagAsDoor(slot)
                    for (slot in srcNode.manualDoorSlots) dstNode.tagAsManualDoor(slot)
                    for (slot in srcNode.connectorSlots) dstNode.tagAsConnector(slot)
                    for (tag in srcNode.tags) newWorld.addTag(dstNode, tag)
                    for (item in srcNode.items) dstNode.items.add(item)
                }
            }
        }

        world = newWorld
        maxRenderZ = world.depth - 1
        statusBar.refresh(world)
        // Only re-center the camera target, keep perspective/zoom/pitch/yaw unchanged
        cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
        updateCamera()
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    private fun updateCamera() {
        val pitchRad = Math.toRadians(cameraPitch.toDouble())
        val yawRad = Math.toRadians(cameraYaw.toDouble())

        val cosPitch = Math.cos(pitchRad).toFloat()
        val sinPitch = Math.sin(pitchRad).toFloat()
        val sinYaw = Math.sin(yawRad).toFloat()
        val cosYaw = Math.cos(yawRad).toFloat()

        camera.position.set(
            cameraTarget.x + cameraDistance * cosPitch * sinYaw,
            cameraTarget.y + cameraDistance * cosPitch * cosYaw,
            cameraTarget.z + cameraDistance * sinPitch
        )
        val upX = -sinPitch * sinYaw
        val upY = -sinPitch * cosYaw
        val upZ = cosPitch
        camera.up.set(upX, upY, upZ).nor()
        camera.lookAt(cameraTarget)
        camera.update()
    }

    // ── File operations ──────────────────────────────────────────────────

    private fun newWorld() {
        world = World(6, 6, 3)
        maxRenderZ = world.depth - 1
        currentFilePath = null
        inputHandler.selectedX = -1; inputHandler.selectedY = -1; inputHandler.selectedZ = -1
        cameraPitch = 60f; cameraYaw = 180f
        cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
        statusBar.refresh(world)
        updateCamera()
    }

    private fun rebuildFileMenu() {
        fileMenu.clear()
        fileMenu.addItem(MenuItem("New").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { newWorld() } }) })
        fileMenu.addItem(MenuItem("Open").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { openWorld() } }) })
        if (recentFiles.isNotEmpty()) {
            fileMenu.addSeparator()
            recentFiles.forEach { path ->
                val displayName = java.io.File(path).name
                fileMenu.addItem(MenuItem(displayName).apply {
                    addListener(object : ChangeListener() {
                        override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { loadWorldFromPath(path) }
                    })
                })
            }
            fileMenu.addSeparator()
        }
        fileMenu.addItem(MenuItem("Save").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { saveWorld() } }) })
        fileMenu.addItem(MenuItem("Save As...").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { saveWorldAs() } }) })
        fileMenu.addItem(MenuItem("Exit").apply { addListener(object : ChangeListener() { override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { game.screen = MainMenuScreen(game) } }) })
    }

    private fun addRecentFile(path: String) {
        recentFiles.remove(path)
        recentFiles.add(0, path)
        if (recentFiles.size > maxRecentFiles) recentFiles.removeAt(recentFiles.lastIndex)
        saveRecentFiles()
        rebuildFileMenu()
    }

    private fun loadRecentFiles() {
        recentFiles.clear()
        val prefs = Gdx.app.getPreferences("MapEditorPrefs")
        for (i in 0 until maxRecentFiles) {
            val path = prefs.getString("recent_$i", "")
            if (path.isNotEmpty()) recentFiles.add(path)
        }
    }

    private fun saveRecentFiles() {
        val prefs = Gdx.app.getPreferences("MapEditorPrefs")
        for (i in 0 until maxRecentFiles) prefs.putString("recent_$i", if (i < recentFiles.size) recentFiles[i] else "")
        prefs.flush()
    }

    private fun loadWorldFromPath(filePath: String) {
        val loadedWorld = WorldIO.loadWorld(filePath, { w, h, d -> World(w, h, d) }, { type -> modelLoader.createTile(type) })
        if (loadedWorld != null) {
            world = loadedWorld
            currentFilePath = filePath
            maxRenderZ = world.depth - 1
            statusBar.refresh(world)
            cameraPitch = 60f; cameraYaw = 180f
            cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
            updateCamera()
            addRecentFile(filePath)
        }
    }

    private fun openWorld() {
        if (isDialogActive) return
        isDialogActive = true
        Thread {
            try {
                val path = PlatformUtils.chooseFile("wld")
                path?.let { Gdx.app.postRunnable { loadWorldFromPath(it) } }
            } finally { isDialogActive = false }
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
                path?.let {
                    var finalPath = it
                    if (!finalPath.endsWith(".wld")) finalPath += ".wld"
                    WorldIO.saveWorld(finalPath, world)
                    Gdx.app.postRunnable { currentFilePath = finalPath }
                }
            } finally { isDialogActive = false }
        }.start()
    }

    // ── Render ────────────────────────────────────────────────────────────

    override fun render(delta: Float) {
        updateHover()
        inputHandler.handleInput(delta, hoveredX, hoveredY, hoveredZ, hoveredEdge)
        maxRenderZ = statusBar.maxRenderZ

        val bw = Gdx.graphics.backBufferWidth.toFloat()
        val bh = Gdx.graphics.backBufferHeight.toFloat()
        val ratioX = bw / stage.width
        val ratioY = bh / stage.height

        val screenPos = viewportArea.localToStageCoordinates(Vector2(0f, 0f))
        val viewX = (screenPos.x * ratioX).toInt()
        val viewY = (screenPos.y * ratioY).toInt()
        val viewW = (viewportArea.width * ratioX).toInt()
        val viewH = (viewportArea.height * ratioY).toInt()
        lastViewX = viewX; lastViewY = viewY; lastViewW = viewW; lastViewH = viewH

        Gdx.gl.glViewport(0, 0, bw.toInt(), bh.toInt())
        Gdx.gl.glClearColor(0.15f, 0.15f, 0.15f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        if (viewW > 0 && viewH > 0) {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(viewX, viewY, viewW, viewH)
            Gdx.gl.glViewport(viewX, viewY, viewW, viewH)
            Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

            camera.viewportWidth = viewW.toFloat()
            camera.viewportHeight = viewH.toFloat()
            camera.update()

            // ── World tiles ─────────────────────────────────────────────────
            modelBatch.begin(camera)
            worldRenderer.render(world, modelBatch, environment, maxRenderZ)

            // ── Grid frames ─────────────────────────────────────────────────
            if (showFrames) {
                for (x in 0 until world.width) {
                    for (y in 0 until world.height) {
                        for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                            if (x == inputHandler.selectedX && y == inputHandler.selectedY && z == inputHandler.selectedZ) continue
                            if (x == hoveredX && y == hoveredY && z == hoveredZ) continue
                            frameInstance.transform.setToTranslation(x.toFloat(), y.toFloat(), z.toFloat())
                            modelBatch.render(frameInstance)
                        }
                    }
                }
            }
            modelBatch.end()

            // ── Hover + selection ───────────────────────────────────────────
            Gdx.gl.glLineWidth(3f)
            modelBatch.begin(camera)
            if (hoveredX != -1) {
                hoverFrameInstance.transform.setToTranslation(hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
                modelBatch.render(hoverFrameInstance)
            }
            if (inputHandler.selectedX != -1) {
                selectedFrameInstance.transform.setToTranslation(
                    inputHandler.selectedX.toFloat(), inputHandler.selectedY.toFloat(), inputHandler.selectedZ.toFloat()
                )
                modelBatch.render(selectedFrameInstance)
            }
            modelBatch.end()
            Gdx.gl.glLineWidth(1f)

            // ── Edge highlight (hovered edge) ───────────────────────────────
            if (hoveredEdge != null && hoveredX != -1) {
                Gdx.gl.glLineWidth(4f)
                shapeRenderer.projectionMatrix = camera.combined
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
                shapeRenderer.color = Color.ORANGE
                drawEdge(hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat(), hoveredEdge!!)
                shapeRenderer.end()
                Gdx.gl.glLineWidth(1f)
            }

            // ── Selected edge highlight ─────────────────────────────────────
            if (inputHandler.selectedEdge != null && inputHandler.selectedX != -1) {
                Gdx.gl.glLineWidth(4f)
                shapeRenderer.projectionMatrix = camera.combined
                shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
                shapeRenderer.color = Color.CYAN
                drawEdge(inputHandler.selectedX.toFloat(), inputHandler.selectedY.toFloat(), inputHandler.selectedZ.toFloat(), inputHandler.selectedEdge!!)
                shapeRenderer.end()
                Gdx.gl.glLineWidth(1f)
            }

            // ── Door edge highlights (green) ───────────────────────────────
            Gdx.gl.glLineWidth(3f)
            shapeRenderer.projectionMatrix = camera.combined
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color.GREEN
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        for (doorSlot in node.doorSlots) {
                            drawEdge(x.toFloat(), y.toFloat(), z.toFloat(), doorSlot)
                        }
                    }
                }
            }
            shapeRenderer.end()
            Gdx.gl.glLineWidth(1f)

            // ── Stairs direction arrows (light blue) ──────────────────────────
            Gdx.gl.glLineWidth(3f)
            shapeRenderer.projectionMatrix = camera.combined
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color(0.5f, 0.8f, 1f, 1f) // light blue
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        val stairsTile = node.getTile(com.roguelike.core.model.TileSlot.STAIRS)
                        if (stairsTile is com.roguelike.world.StairsTile) {
                            drawStairsArrow(x.toFloat(), y.toFloat(), z.toFloat(), stairsTile)
                        }
                    }
                }
            }
            shapeRenderer.end()
            Gdx.gl.glLineWidth(1f)

            // ── Tag spheres + labels ────────────────────────────────────────
            modelBatch.begin(camera)
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isNotEmpty()) {
                            tagSphereInstance.transform.setToTranslation(x.toFloat(), y.toFloat(), z.toFloat())
                            modelBatch.render(tagSphereInstance, environment)
                        }
                        // Render door_manual spheres at each manual door edge
                        for (slot in node.manualDoorSlots) {
                            val offset = edgeOffset(slot)
                            tagSphereInstance.transform.setToTranslation(x + offset.x, y + offset.y, z + offset.z)
                            modelBatch.render(tagSphereInstance, environment)
                        }
                        // Render node_connector spheres at each connector edge
                        for (slot in node.connectorSlots) {
                            val offset = edgeOffset(slot)
                            tagSphereInstance.transform.setToTranslation(x + offset.x, y + offset.y, z + offset.z)
                            modelBatch.render(tagSphereInstance, environment)
                        }
                    }
                }
            }
            modelBatch.end()

            val projPos = Vector3()
            tagSpriteBatch.setProjectionMatrix(
                com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW.toFloat(), viewH.toFloat())
            )
            tagSpriteBatch.begin()
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0..maxRenderZ.coerceAtMost(world.depth - 1)) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isNotEmpty()) {
                            projPos.set(x.toFloat(), y.toFloat() + 0.45f, z.toFloat())
                            camera.project(projPos, 0f, 0f, viewW.toFloat(), viewH.toFloat())
                            if (projPos.z in 0f..1f) {
                                tagFont.draw(tagSpriteBatch, node.tags.joinToString("\n"), projPos.x - 30f, projPos.y + 4f)
                            }
                        }
                        // Render door_manual labels at each manual door edge
                        for (slot in node.manualDoorSlots) {
                            val offset = edgeOffset(slot)
                            projPos.set(x + offset.x, y + offset.y + 0.45f, z + offset.z)
                            camera.project(projPos, 0f, 0f, viewW.toFloat(), viewH.toFloat())
                            if (projPos.z in 0f..1f) {
                                tagFont.draw(tagSpriteBatch, WorldNode.Tags.DOOR_MANUAL, projPos.x - 30f, projPos.y + 4f)
                            }
                        }
                        // Render node_connector labels at each connector edge
                        for (slot in node.connectorSlots) {
                            val offset = edgeOffset(slot)
                            projPos.set(x + offset.x, y + offset.y + 0.45f, z + offset.z)
                            camera.project(projPos, 0f, 0f, viewW.toFloat(), viewH.toFloat())
                            if (projPos.z in 0f..1f) {
                                tagFont.draw(tagSpriteBatch, WorldNode.Tags.NODE_CONNECTOR, projPos.x - 30f, projPos.y + 4f)
                            }
                        }
                    }
                }
            }
            tagSpriteBatch.end()

            // ── Crosshair at viewport centre ────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            val ortho = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW.toFloat(), viewH.toFloat())
            shapeRenderer.projectionMatrix = ortho
            shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
            shapeRenderer.color = Color(1f, 1f, 1f, 0.5f)
            val cx = viewW / 2f; val cy = viewH / 2f; val cs = 12f
            shapeRenderer.line(cx - cs, cy, cx + cs, cy)
            shapeRenderer.line(cx, cy - cs, cx, cy + cs)
            shapeRenderer.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)

            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glViewport(0, 0, bw.toInt(), bh.toInt())
        }

        stage.act(delta)
        stage.draw()
        scrolledThisFrame = false
    }

    /**
     * Draw a thick line on the edge of a node for the given wall slot.
     * The edge is drawn as 4 lines forming a rectangle on the face of the node cube.
     */
    private fun edgeOffset(slot: TileSlot): Vector3 = when (slot) {
        TileSlot.WALL_NORTH -> Vector3(0f, 0.5f, 0f)
        TileSlot.WALL_SOUTH -> Vector3(0f, -0.5f, 0f)
        TileSlot.WALL_EAST  -> Vector3(0.5f, 0f, 0f)
        TileSlot.WALL_WEST  -> Vector3(-0.5f, 0f, 0f)
        else -> Vector3(0f, 0f, 0f)
    }

    private fun drawStairsArrow(x: Float, y: Float, z: Float, stairsTile: com.roguelike.world.StairsTile) {
        val facing = stairsTile.facingDirection()
        // Direction vector for the arrow
        val dx: Float; val dy: Float
        when (facing) {
            TileSlot.WALL_NORTH -> { dx = 0f; dy = 1f }
            TileSlot.WALL_SOUTH -> { dx = 0f; dy = -1f }
            TileSlot.WALL_EAST  -> { dx = 1f; dy = 0f }
            TileSlot.WALL_WEST  -> { dx = -1f; dy = 0f }
            else -> return
        }
        val len = 0.4f
        val headLen = 0.15f
        val tipX = x + dx * len
        val tipY = y + dy * len
        // Arrow shaft
        shapeRenderer.line(x - dx * len, y - dy * len, z, tipX, tipY, z)
        // Arrowhead wings (perpendicular)
        shapeRenderer.line(tipX, tipY, z, tipX - dx * headLen + dy * headLen, tipY - dy * headLen - dx * headLen, z)
        shapeRenderer.line(tipX, tipY, z, tipX - dx * headLen - dy * headLen, tipY - dy * headLen + dx * headLen, z)
    }

    private fun drawEdge(x: Float, y: Float, z: Float, slot: TileSlot) {
        val s = 0.5f
        when (slot) {
            TileSlot.WALL_NORTH -> {
                shapeRenderer.line(x - s, y + s, z - s, x + s, y + s, z - s)
                shapeRenderer.line(x - s, y + s, z + s, x + s, y + s, z + s)
                shapeRenderer.line(x - s, y + s, z - s, x - s, y + s, z + s)
                shapeRenderer.line(x + s, y + s, z - s, x + s, y + s, z + s)
            }
            TileSlot.WALL_SOUTH -> {
                shapeRenderer.line(x - s, y - s, z - s, x + s, y - s, z - s)
                shapeRenderer.line(x - s, y - s, z + s, x + s, y - s, z + s)
                shapeRenderer.line(x - s, y - s, z - s, x - s, y - s, z + s)
                shapeRenderer.line(x + s, y - s, z - s, x + s, y - s, z + s)
            }
            TileSlot.WALL_EAST -> {
                shapeRenderer.line(x + s, y - s, z - s, x + s, y + s, z - s)
                shapeRenderer.line(x + s, y - s, z + s, x + s, y + s, z + s)
                shapeRenderer.line(x + s, y - s, z - s, x + s, y - s, z + s)
                shapeRenderer.line(x + s, y + s, z - s, x + s, y + s, z + s)
            }
            TileSlot.WALL_WEST -> {
                shapeRenderer.line(x - s, y - s, z - s, x - s, y + s, z - s)
                shapeRenderer.line(x - s, y - s, z + s, x - s, y + s, z + s)
                shapeRenderer.line(x - s, y - s, z - s, x - s, y - s, z + s)
                shapeRenderer.line(x - s, y + s, z - s, x - s, y + s, z + s)
            }
            else -> {}
        }
    }

    // ── Hover detection ──────────────────────────────────────────────────

    private fun updateHover() {
        val scaleX = Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width
        val scaleY = Gdx.graphics.backBufferHeight.toFloat() / Gdx.graphics.height
        val mouseXPx = Gdx.input.x * scaleX
        val mouseYPx = (Gdx.graphics.height - Gdx.input.y) * scaleY

        if (lastViewW <= 0 || lastViewH <= 0 ||
            mouseXPx < lastViewX || mouseXPx > lastViewX + lastViewW ||
            mouseYPx < lastViewY || mouseYPx > lastViewY + lastViewH
        ) {
            hoveredX = -1; hoveredEdge = null; return
        }

        val ray = camera.getPickRay(
            Gdx.input.x.toFloat(), Gdx.input.y.toFloat(),
            lastViewX / scaleX, lastViewY / scaleY,
            lastViewW / scaleX, lastViewH / scaleY
        )

        var bestT = Float.MAX_VALUE
        var bestEmptyT = Float.MAX_VALUE
        var emptyX = -1; var emptyY = -1; var emptyZ = -1
        hoveredX = -1; hoveredEdge = null

        val editZ = maxRenderZ.coerceAtMost(world.depth - 1)

        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                    val z = editZ
                    val hs = 0.5f
                    val minX = x - hs; val maxX = x + hs
                    val minY = y - hs; val maxY = y + hs
                    val minZ = z - hs; val maxZ = z + hs
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

                    val node = world.getNode(x, y, z)
                    val hasContent = node != null && (node.tiles.isNotEmpty() || node.items.isNotEmpty() || node.tags.isNotEmpty() || node.manualDoorSlots.isNotEmpty() || node.connectorSlots.isNotEmpty())
                    if (hasContent) {
                        if (tmin < bestT) {
                            bestT = tmin; hoveredX = x; hoveredY = y; hoveredZ = z
                        }
                    } else {
                        if (tmin < bestEmptyT) {
                            bestEmptyT = tmin; emptyX = x; emptyY = y; emptyZ = z
                        }
                    }
            }
        }
        if (hoveredX == -1 && emptyX != -1) {
            hoveredX = emptyX; hoveredY = emptyY; hoveredZ = emptyZ
        }

        // Determine which edge (face) of the hovered node the ray hits
        if (hoveredX != -1) {
            hoveredEdge = detectHoveredEdge(ray, hoveredX.toFloat(), hoveredY.toFloat(), hoveredZ.toFloat())
        }
    }

    /**
     * Given that the ray hits the AABB of the node at (nx,ny,nz),
     * determine which face it enters — and map to a TileSlot.
     */
    private fun detectHoveredEdge(ray: com.badlogic.gdx.math.collision.Ray, nx: Float, ny: Float, nz: Float): TileSlot? {
        val s = 0.5f
        // Compute entry t for each face
        data class FaceHit(val slot: TileSlot, val t: Float)
        val hits = mutableListOf<FaceHit>()

        // North face (y + 0.5)
        if (Math.abs(ray.direction.y) > 0.00001f) {
            val t = (ny + s - ray.origin.y) / ray.direction.y
            if (t > 0) {
                val hx = ray.origin.x + t * ray.direction.x
                val hz = ray.origin.z + t * ray.direction.z
                if (hx >= nx - s && hx <= nx + s && hz >= nz - s && hz <= nz + s) hits.add(FaceHit(TileSlot.WALL_NORTH, t))
            }
        }
        // South face (y - 0.5)
        if (Math.abs(ray.direction.y) > 0.00001f) {
            val t = (ny - s - ray.origin.y) / ray.direction.y
            if (t > 0) {
                val hx = ray.origin.x + t * ray.direction.x
                val hz = ray.origin.z + t * ray.direction.z
                if (hx >= nx - s && hx <= nx + s && hz >= nz - s && hz <= nz + s) hits.add(FaceHit(TileSlot.WALL_SOUTH, t))
            }
        }
        // East face (x + 0.5)
        if (Math.abs(ray.direction.x) > 0.00001f) {
            val t = (nx + s - ray.origin.x) / ray.direction.x
            if (t > 0) {
                val hy = ray.origin.y + t * ray.direction.y
                val hz = ray.origin.z + t * ray.direction.z
                if (hy >= ny - s && hy <= ny + s && hz >= nz - s && hz <= nz + s) hits.add(FaceHit(TileSlot.WALL_EAST, t))
            }
        }
        // West face (x - 0.5)
        if (Math.abs(ray.direction.x) > 0.00001f) {
            val t = (nx - s - ray.origin.x) / ray.direction.x
            if (t > 0) {
                val hy = ray.origin.y + t * ray.direction.y
                val hz = ray.origin.z + t * ray.direction.z
                if (hy >= ny - s && hy <= ny + s && hz >= nz - s && hz <= nz + s) hits.add(FaceHit(TileSlot.WALL_WEST, t))
            }
        }

        return hits.minByOrNull { it.t }?.slot
    }

    override fun resize(width: Int, height: Int) {
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
        tagSphereModel.dispose()
        tagFont.dispose()
        tagSpriteBatch.dispose()
        orientationGizmo.dispose()
        VisUI.dispose()
    }
}
