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
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.roguelike.utils.AssetLoader
import com.roguelike.rendering.*
import com.roguelike.serialization.WorldIO
import com.roguelike.utils.*
import com.roguelike.world.*
import java.io.File
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.SerializationException
import com.badlogic.gdx.scenes.scene2d.Actor as S2DActor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Array as GdxArray
import java.util.ArrayList

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
    
    private val tileRenderer = TileRenderer()
    private lateinit var worldRenderer: WorldRenderer
    private lateinit var itemRenderer: ItemRenderer
    
    private var showFrames = true
    private lateinit var frameModel: Model
    private lateinit var frameInstance: ModelInstance
    private lateinit var hoverFrameInstance: ModelInstance
    private lateinit var selectedFrameInstance: ModelInstance
    
    private var isDialogActive = false
    private var currentFilePath: String? = null
    
    private var maxRenderY = 0
    private var hoveredX = -1
    private var hoveredY = -1
    private var hoveredZ = -1
    
    private var selectedX = -1
    private var selectedY = -1
    private var selectedZ = -1
    
    // Camera control
    private var cameraPitch = 0f
    private var cameraYaw = 0f
    private var cameraRoll = 0f
    private var cameraDistance = 25f

    private val cameraTarget = Vector3(5f, 5f, 0f)
    
    private val previewEnvironment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
        add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f))
    }
    private val previewCamera = PerspectiveCamera(67f, 100f, 100f).apply {
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

    private val tagButtons = HashMap<String, TextButton>()

    override fun show() {
        modelBatch = ModelBatch()
        shapeRenderer = ShapeRenderer()
        assetLoader = AssetLoader()
        modelLoader = ModelLoader(assetLoader)
        itemRenderer = ItemRenderer(assetLoader)
        worldRenderer = WorldRenderer(tileRenderer, itemRenderer)
        world = World(5, 1, 5)
        WorldGenerator(world, modelLoader).generate()
        maxRenderY = world.height - 1
        
        camera = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.near = 0.1f
        camera.far = 1000f
        updateCamera()

        environment = Environment()
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1f))
        environment.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -0.8f, -0.2f))

        createUI()
        createFrameModel()
    }

    private fun createFrameModel() {
        val modelBuilder = ModelBuilder()
        modelBuilder.begin()
        val part = modelBuilder.part("frame", GL20.GL_LINES, (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material())
        
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
    }

    private fun createUI() {
        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = stage
        
        skin = Skin()
        val font = BitmapFont()
        skin.add("default", font)
        
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        val texture = Texture(pixmap)
        skin.add("white", texture)
        
        val labelStyle = Label.LabelStyle(font, Color.WHITE)
        skin.add("default", labelStyle)
        val textFieldStyle = TextField.TextFieldStyle(font, Color.BLACK, null, null, null)
        textFieldStyle.background = skin.newDrawable("white", Color.DARK_GRAY)
        
        val textButtonStyle = TextButton.TextButtonStyle()
        textButtonStyle.font = font
        textButtonStyle.fontColor = Color.WHITE
        textButtonStyle.up = skin.newDrawable("white", Color.GRAY)
        textButtonStyle.over = skin.newDrawable("white", Color.LIGHT_GRAY)
        textButtonStyle.down = skin.newDrawable("white", Color.DARK_GRAY)
        textButtonStyle.checked = skin.newDrawable("white", Color.DARK_GRAY)
        skin.add("default", textButtonStyle)
        
        val windowStyle = Window.WindowStyle(font, Color.WHITE, skin.newDrawable("white", Color.BLACK))
        skin.add("default", windowStyle)
        
        val listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, Color.YELLOW, Color.WHITE, skin.newDrawable("white", Color.BLACK))
        skin.add("default", listStyle)
        
        val scrollPaneStyle = com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle(skin.newDrawable("white", Color.DARK_GRAY), null, null, null, null)
        skin.add("default", scrollPaneStyle)

        // Menu Bar Table
        val menuBar = Table()
        menuBar.setFillParent(true)
        menuBar.touchable = Touchable.childrenOnly
        menuBar.top().left()

        val newBtn = TextButton("New", textButtonStyle)
        newBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                newWorld()
            }
        })
        
        val openBtn = TextButton("Open", textButtonStyle)
        openBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                openWorld()
            }
        })
        
        val saveBtn = TextButton("Save", textButtonStyle)
        saveBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                saveWorld()
            }
        })

        val saveAsBtn = TextButton("Save As", textButtonStyle)
        saveAsBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                saveWorldAs()
            }
        })
        
        menuBar.add(Label(" FILE ", labelStyle)).pad(5f)
        menuBar.add(newBtn).pad(2f)
        menuBar.add(openBtn).pad(2f)
        menuBar.add(saveBtn).pad(2f)
        menuBar.add(saveAsBtn).pad(2f)
        
        // Dimensions
        menuBar.add(Label(" | DIMENSIONS: ", labelStyle)).padLeft(20f)
        
        xLabel = Label(world.width.toString(), labelStyle)
        yLabel = Label(world.height.toString(), labelStyle)
        zLabel = Label(world.depth.toString(), labelStyle)
        layerLabel = Label(maxRenderY.toString(), labelStyle)

        // Removed local updateUI
        
        fun resize(nx: Int, ny: Int, nz: Int) {
            val oldWorld = world
            world = World(nx.coerceAtLeast(1), ny.coerceAtLeast(1), nz.coerceAtLeast(1))
            
            // Copy nodes from old world to new world
            for (x in 0 until minOf(oldWorld.width, world.width)) {
                for (y in 0 until minOf(oldWorld.height, world.height)) {
                    for (z in 0 until minOf(oldWorld.depth, world.depth)) {
                        val oldNode = oldWorld.getNode(x, y, z)!!
                        val newNode = world.getNode(x, y, z)!!
                        
                        // Copy tiles
                        newNode.tiles.addAll(oldNode.tiles)
                        
                        // Copy tags
                        oldNode.tags.forEach { world.addTag(newNode, it) }
                    }
                }
            }

            maxRenderY = world.height - 1
            cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
            updateCamera()
            updateUI()
        }

        // X
        menuBar.add(Label("X:", labelStyle))
        val xMinus = TextButton("-", textButtonStyle)
        xMinus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width - 1, world.height, world.depth)
            }
        })
        val xPlus = TextButton("+", textButtonStyle)
        xPlus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width + 1, world.height, world.depth)
            }
        })
        menuBar.add(xMinus).width(30f)
        menuBar.add(xLabel).width(30f).center()
        menuBar.add(xPlus).width(30f)

        // Y
        menuBar.add(Label(" Y:", labelStyle))
        val yMinus = TextButton("-", textButtonStyle)
        yMinus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width, world.height - 1, world.depth)
            }
        })
        val yPlus = TextButton("+", textButtonStyle)
        yPlus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width, world.height + 1, world.depth)
            }
        })
        menuBar.add(yMinus).width(30f)
        menuBar.add(yLabel).width(30f).center()
        menuBar.add(yPlus).width(30f)

        // Z
        menuBar.add(Label(" Z:", labelStyle))
        val zMinus = TextButton("-", textButtonStyle)
        zMinus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width, world.height, world.depth - 1)
            }
        })
        val zPlus = TextButton("+", textButtonStyle)
        zPlus.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                resize(world.width, world.height, world.depth + 1)
            }
        })
        menuBar.add(zMinus).width(30f)
        menuBar.add(zLabel).width(30f).center()
        menuBar.add(zPlus).width(30f)
        
        // Layer Picker
        menuBar.add(Label(" | LAYER (Y): ", labelStyle)).padLeft(20f)
        
        val minusBtn = TextButton("-", textButtonStyle)
        minusBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                maxRenderY = (maxRenderY - 1).coerceAtLeast(0)
                layerLabel.setText(maxRenderY.toString())
            }
        })
        
        val plusBtn = TextButton("+", textButtonStyle)
        plusBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                maxRenderY = (maxRenderY + 1).coerceAtMost(world.height - 1)
                layerLabel.setText(maxRenderY.toString())
            }
        })
        
        menuBar.add(minusBtn).width(30f)
        menuBar.add(layerLabel).width(30f).center()
        menuBar.add(plusBtn).width(30f)
        
        // Toggle Frames
        val frameBtn = TextButton("Toggle Frames", textButtonStyle)
        frameBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                showFrames = !showFrames
            }
        })
        menuBar.add(frameBtn).padLeft(20f)

        val exitBtn = TextButton("Exit", textButtonStyle)
        exitBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                game.screen = MainMenuScreen(game)
            }
        })
        menuBar.add(exitBtn).padLeft(20f)
        
        stage.addActor(menuBar)
        
        createSidePanel()
    }


    private fun createSidePanel() {
        val root = Table()
        root.setFillParent(true)
        root.right()
        
        val panel = Table()
        panel.background = skin.newDrawable("white", Color.BLACK)
        panel.pad(10f)
        
        val scrollTable = Table()
        val scrollPane = ScrollPane(scrollTable, skin)
        panel.add(scrollPane).width(200f).fillY().expandY()
        
        root.add(panel).fillY().expandY()
        stage.addActor(root)

        // Palette tabs
        scrollTable.add(Label("TILES", skin)).pad(10f).row()

        val tileTypes = listOf(
            FloorTile.TYPE,
            WallHorizontalTile.TYPE,
            WallVerticalTile.TYPE,
            DoorHorizontalTile.TYPE,
            DoorVerticalTile.TYPE,
            ToggleTile.TYPE,
            CornerNETile.TYPE,
            CornerESTile.TYPE,
            CornerSWTile.TYPE,
            CornerWNTile.TYPE
        )

        tileTypes.forEach { type ->
            val tile = modelLoader.createTile(type)!!
            val container = Table()
            container.add(TilePreviewActor(tile)).size(64f).pad(5f)
            container.add(Label(type.removeSuffix("Tile"), skin))
            
            container.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    applyTileToSelection(tile)
                }
            })
            
            scrollTable.add(container).fillX().row()
            tileContainers[type] = container
        }

        scrollTable.add(Label("ITEMS", skin)).pad(10f).row()
        val items = listOf(
            Triple(Color.BLUE, "Blue Key", "Key"),
            Triple(Color.GREEN, "Green Key", "Key"),
            Triple(Color.RED, "Red Key", "Key")
        )
        
        items.forEach { (color, name, type) ->
            val container = Table()
            // Using a simple color preview for items in editor palette for now
            val preview = Image(skin.getDrawable("white"))
            preview.color = color
            container.add(preview).size(32f).pad(5f)
            container.add(Label(name, skin))
            
            container.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    val node = world.getNode(selectedX, selectedY, selectedZ)
                    if (tapCount >= 2) {
                        node?.items?.removeIf { it is KeyItem }
                        world.associations.removeIf { it.target == node && it.type == "key" }
                        Gdx.app.log("Editor", "Removed keys and associations from ($selectedX, $selectedY, $selectedZ)")
                    } else {
                        node?.items?.clear()
                        node?.items?.add(KeyItem(color = color, name = name))
                        Gdx.app.log("Editor", "Added $name to ($selectedX, $selectedY, $selectedZ)")
                    }
                }
            })
            scrollTable.add(container).fillX().row()
        }

        scrollTable.add(Label("TAGS", skin)).pad(10f).row()
        val tags = listOf(
            WorldNode.Tags.PLAYER_SPAWN,
            WorldNode.Tags.ENEMY_SPAWN,
            WorldNode.Tags.ITEM_SPAWN,
            WorldNode.Tags.EXIT,
            WorldNode.Tags.DOOR_MANUAL,
            WorldNode.Tags.DOOR_KEY,
            WorldNode.Tags.DOOR_TOGGLE,
            WorldNode.Tags.TOGGLE
        )

        tags.forEach { tag ->
            val btn = TextButton(tag, skin)
            btn.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    toggleTag(tag)
                }
            })
            scrollTable.add(btn).fillX().pad(2f).row()
            tagButtons[tag] = btn
        }
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

    private fun updatePaletteHighlights() {
        val node = world.getNode(selectedX, selectedY, selectedZ) ?: return
        
        tileContainers.forEach { (type, table) ->
            val hasTile = node.tiles.any { it.type == type }
            table.background = if (hasTile) skin.newDrawable("white", Color.DARK_GRAY) else null
        }
        
        tagButtons.forEach { (tag, btn) ->
            btn.isChecked = node.tags.contains(tag)
        }
    }

    private fun applyTileToSelection(tile: Tile) {
        val node = world.getNode(selectedX, selectedY, selectedZ) ?: return
        
        // Toggle logic: if already has it, remove it. If different of same category, replace.
        val existing = node.tiles.find { it.type == tile.type }
        if (existing != null) {
            node.tiles.remove(existing)
        } else {
            // Remove other tiles of same "base" type if desired? 
            // For now just add multiple.
            node.tiles.add(modelLoader.createTile(tile.type)!!)
        }
        updatePaletteHighlights()
    }

    inner class TilePreviewActor(val tile: Tile) : S2DActor() {
        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            batch.end()
            
            val screenPos = localToStageCoordinates(Vector2(0f, 0f))
            // Correct for stage scale and backbuffer (Retina/High-DPI)
            val stage = stage
            val x = screenPos.x * (Gdx.graphics.backBufferWidth.toFloat() / stage.width)
            val y = screenPos.y * (Gdx.graphics.backBufferHeight.toFloat() / stage.height)
            val w = width * (Gdx.graphics.backBufferWidth.toFloat() / stage.width)
            val h = height * (Gdx.graphics.backBufferHeight.toFloat() / stage.height)

            Gdx.gl.glViewport(x.toInt(), y.toInt(), w.toInt(), h.toInt())
            
            modelBatch.begin(previewCamera)
            tileRenderer.render(tile, modelBatch, previewEnvironment, 0f, 0f, 0f, ignoreYRotation = true)
            modelBatch.end()
            
            Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            
            batch.begin()
        }
    }

    private fun updateUI() {
        if (!::xLabel.isInitialized) return
        xLabel.setText(world.width.toString())
        yLabel.setText(world.height.toString())
        zLabel.setText(world.depth.toString())
        layerLabel.setText(maxRenderY.toString())
    }

    private fun updateCamera() {
        camera.position.set(cameraTarget.x, cameraTarget.y, cameraTarget.z + cameraDistance)
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(cameraTarget)
        camera.update()
    }

    private fun newWorld() {
        world = World(10, 1, 10)
        maxRenderY = 0
        currentFilePath = null
        cameraTarget.set(5f, 0f, 5f)
        updateCamera()
        updateUI()
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
                            maxRenderY = world.height - 1
                            cameraTarget.set(world.width / 2f, world.height / 2f, world.depth / 2f)
                            updateCamera()
                            updateUI()
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
        if (path == null) {
            saveWorldAs()
        } else {
            WorldIO.saveWorld(path, world)
        }
    }

    private fun saveWorldAs() {
        if (isDialogActive) return
        isDialogActive = true
        Thread {
            try {
                val path = PlatformUtils.chooseFileName("world.wld")
                
                path?.let { filePath ->
                    var finalPath = filePath
                    if (!finalPath.endsWith(".wld")) {
                        finalPath += ".wld"
                    }
                    WorldIO.saveWorld(finalPath, world)
                    Gdx.app.postRunnable {
                        currentFilePath = finalPath
                    }
                }
            } finally {
                isDialogActive = false
            }
        }.start()
    }

    override fun render(delta: Float) {
        handleInput(delta)
        updateHover()
        
        Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        
        modelBatch.begin(camera)
        worldRenderer.render(world, modelBatch, environment, maxRenderY)
        
        if (showFrames) {
            // Render wireframe boxes for each node
            for (x in 0 until world.width) {
                for (y in 0..maxRenderY.coerceAtMost(world.height - 1)) {
                    for (z in 0 until world.depth) {
                        val isSelected = x == selectedX && y == selectedY && z == selectedZ
                        val isHovered = x == hoveredX && y == hoveredY && z == hoveredZ
                        
                        val instance = when {
                            isSelected -> selectedFrameInstance
                            isHovered -> hoverFrameInstance
                            else -> frameInstance
                        }
                        
                        instance.transform.setToTranslation(x.toFloat(), y.toFloat(), z.toFloat())
                        
                        if (isSelected || isHovered) {
                            modelBatch.flush()
                            Gdx.gl.glLineWidth(3f)
                            modelBatch.render(instance, environment)
                            modelBatch.flush()
                            Gdx.gl.glLineWidth(1f)
                        } else {
                            modelBatch.render(instance, environment)
                        }
                    }
                }
            }
        }
        modelBatch.end()

        // Draw association lines
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.YELLOW
        world.associations.forEach { assoc ->
            shapeRenderer.line(
                assoc.source.x.toFloat(), assoc.source.y.toFloat(), assoc.source.z.toFloat(),
                assoc.target.x.toFloat(), assoc.target.y.toFloat(), assoc.target.z.toFloat()
            )
        }
        shapeRenderer.end()
        
        stage.act(delta)
        stage.draw()
    }

    private fun updateHover() {
        if (stage.hit(Gdx.input.x.toFloat(), (Gdx.graphics.height - Gdx.input.y).toFloat(), true) != null) {
            hoveredX = -1
            return
        }

        val ray = camera.getPickRay(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
        var bestDist = Float.MAX_VALUE
        hoveredX = -1
        
        for (x in 0 until world.width) {
            for (y in 0..maxRenderY) {
                for (z in 0 until world.depth) {
                    val dist = ray.origin.dst(x.toFloat(), y.toFloat(), z.toFloat())
                    if (dist < bestDist) {
                        // Check if ray hits box
                        val halfSize = 0.5f
                        val minX = x - halfSize; val maxX = x + halfSize
                        val minY = y - halfSize; val maxY = y + halfSize
                        val minZ = z - halfSize; val maxZ = z + halfSize
                        
                        // Simple ray-AABB intersection (Slabs method)
                        var tmin = Float.NEGATIVE_INFINITY
                        var tmax = Float.POSITIVE_INFINITY

                        // X slab
                        if (Math.abs(ray.direction.x) > 0.00001f) {
                            var t1 = (minX - ray.origin.x) / ray.direction.x
                            var t2 = (maxX - ray.origin.x) / ray.direction.x
                            tmin = Math.max(tmin, Math.min(t1, t2))
                            tmax = Math.min(tmax, Math.max(t1, t2))
                        } else if (ray.origin.x < minX || ray.origin.x > maxX) continue

                        // Y slab
                        if (Math.abs(ray.direction.y) > 0.00001f) {
                            var t1 = (minY - ray.origin.y) / ray.direction.y
                            var t2 = (maxY - ray.origin.y) / ray.direction.y
                            tmin = Math.max(tmin, Math.min(t1, t2))
                            tmax = Math.min(tmax, Math.max(t1, t2))
                        } else if (ray.origin.y < minY || ray.origin.y > maxY) continue

                        // Z slab
                        if (Math.abs(ray.direction.z) > 0.00001f) {
                            var t1 = (minZ - ray.origin.z) / ray.direction.z
                            var t2 = (maxZ - ray.origin.z) / ray.direction.z
                            tmin = Math.max(tmin, Math.min(t1, t2))
                            tmax = Math.min(tmax, Math.max(t1, t2))
                        } else if (ray.origin.z < minZ || ray.origin.z > maxZ) continue

                        if (tmax < tmin || tmax < 0) continue

                        
                        bestDist = dist
                        hoveredX = x
                        hoveredY = y
                        hoveredZ = z
                    }
                }
            }
        }
    }

    private fun handleInput(delta: Float) {
        if (Gdx.input.justTouched()) {
            if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                if (hoveredX != -1) {
                    selectedX = hoveredX
                    selectedY = hoveredY
                    selectedZ = hoveredZ
                    Gdx.app.log("Editor", "Selected node: ($selectedX, $selectedY, $selectedZ)")
                    updatePaletteHighlights()
                } else if (hoveredX == -1 && stage.hit(Gdx.input.x.toFloat(), (Gdx.graphics.height - Gdx.input.y).toFloat(), true) == null) {
                    Gdx.app.log("Editor", "Click ignored: No node hovered")
                }
            } else if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
                // Association logic
                if (selectedX != -1 && hoveredX != -1) {
                    val source = world.getNode(selectedX, selectedY, selectedZ)
                    val target = world.getNode(hoveredX, hoveredY, hoveredZ)
                    if (source != null && target != null) {
                        // Determine association type
                        var assocData: String? = null
                        val type = if (target.items.any { it is KeyItem }) {
                            assocData = target.items.firstOrNull { it is KeyItem }?.name
                            "key"
                        } else if (target.tags.contains(WorldNode.Tags.TOGGLE)) {
                            "toggle"
                        } else {
                            null
                        }
                        
                        if (type != null) {
                            world.addAssociation(source, target, type, assocData)
                            Gdx.app.log("Editor", "Added $type association from ($selectedX,$selectedY,$selectedZ) to ($hoveredX,$hoveredY,$hoveredZ) with data: $assocData")
                        }
                    }
                }
            }
        }

        if (stage.keyboardFocus == null) {
            // Camera rotation removed for top-down view
            
            updateCamera()
        }
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}

    override fun dispose() {
        modelBatch.dispose()
        shapeRenderer.dispose()
        assetLoader.dispose()
        stage.dispose()
        skin.dispose()
        frameModel.dispose()
    }
}
