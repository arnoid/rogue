package com.roguelike

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.roguelike.core.model.GameLogger
import com.roguelike.core.model.TileSlot
import com.roguelike.core.systems.InteractionSystem
import com.roguelike.core.systems.MovementSystem
import com.roguelike.rendering.InventoryUI
import com.roguelike.rendering.ItemRenderer
import com.roguelike.rendering.TileRenderer
import com.roguelike.rendering.WorldRenderer
import com.roguelike.serialization.WorldIO
import com.roguelike.systems.CameraManager
import com.roguelike.systems.DoorAnimationSystem
import com.roguelike.systems.InputHandler
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.utils.*
import com.roguelike.world.*

class RoguelikeGame(private val game: Game, val worldPath: String? = null) : Screen {
    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment

    // Player logic (pure core) + its visual representation (view layer)
    private lateinit var player: Player
    private lateinit var playerInstance: ModelInstance   // owned by this screen, not by Player
    private val moveSpeed = 5f

    // Systems & Renderers
    private lateinit var world: World
    private lateinit var worldRenderer: WorldRenderer
    private lateinit var itemRenderer: ItemRenderer
    private lateinit var movementSystem: MovementSystem
    private lateinit var interactionSystem: InteractionSystem
    private val inputHandler = InputHandler()
    private lateinit var cameraManager: CameraManager
    private val doorAnimationSystem = DoorAnimationSystem()

    // UI
    private lateinit var stage: Stage
    private lateinit var skin: Skin
    private lateinit var inventoryUI: InventoryUI

    // Assets
    private val assetLoader = AssetLoader()
    private val modelLoader = ModelLoader(assetLoader)

    // Debug
    private var debugMode = false
    private lateinit var axesInstance: ModelInstance
    private lateinit var debugFrameModel: Model
    private lateinit var debugFrameInstance: ModelInstance
    private lateinit var debugShapeRenderer: com.badlogic.gdx.graphics.glutils.ShapeRenderer
    private lateinit var debugSpriteBatch: com.badlogic.gdx.graphics.g2d.SpriteBatch
    private lateinit var debugFont: com.badlogic.gdx.graphics.g2d.BitmapFont

    /** Bridge GameLogger that delegates to LibGDX Gdx.app.log(). */
    private val gdxLogger = GameLogger { tag, msg -> Gdx.app?.log(tag, msg) }

    override fun show() {
        modelBatch = ModelBatch()
        itemRenderer = ItemRenderer(assetLoader)
        val tileRenderer = TileRenderer(modelLoader.renderRegistry, doorAnimationSystem)
        worldRenderer = WorldRenderer(tileRenderer, itemRenderer)

        // --- World Generation or Loading ---
        if (worldPath != null) {
            loadWorld(worldPath)
        } else {
            world = World(10, 10, 1)
            WorldGenerator(world, modelLoader).generate()
        }

        movementSystem = MovementSystem(world)
        interactionSystem = InteractionSystem(world, gdxLogger)

        // Register all existing doors with the animation system
        registerDoorAnimations()

        // Wire ToggleTile.linkedDoor from world associations
        wireToggleLinks()

        camera = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.near = 0.1f
        camera.far = 1000f
        camera.update()

        cameraManager = CameraManager(camera)

        environment = Environment()
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        environment.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -1f, -1f))

        // UI Setup
        stage = Stage(ScreenViewport())
        skin = Skin()
        val font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        skin.add("default", font)
        val pixmap = com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        skin.add("white", com.badlogic.gdx.graphics.Texture(pixmap))
        val labelStyle = com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(font, Color.WHITE)
        skin.add("default", labelStyle)

        inventoryUI = InventoryUI(skin)
        inventoryUI.setFillParent(true)
        stage.addActor(inventoryUI)

        // Player — pure logic object, no LibGDX
        player = Player()

        // Player visual — sphere mesh owned here in the view layer
        val modelBuilder = ModelBuilder()
        val sphereModel = modelBuilder.createSphere(
            0.8f, 0.8f, 0.8f, 20, 20,
            Material(ColorAttribute.createDiffuse(Color.BLUE)),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        )
        playerInstance = ModelInstance(sphereModel)

        // Debug Axes
        modelBuilder.begin()
        val part = modelBuilder.part(
            "axes", GL20.GL_LINES,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material()
        )
        part.setColor(Color.RED);   part.line(0f, 0f, 0f, 2f, 0f, 0f)
        part.setColor(Color.GREEN); part.line(0f, 0f, 0f, 0f, 2f, 0f)
        part.setColor(Color.BLUE);  part.line(0f, 0f, 0f, 0f, 0f, 2f)
        axesInstance = ModelInstance(modelBuilder.end()!!)

        // Debug wireframe box for node frames
        modelBuilder.begin()
        val framePart = modelBuilder.part(
            "frame", GL20.GL_LINES,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material()
        )
        val s = 0.5f
        framePart.setColor(Color.WHITE)
        // Bottom face
        framePart.line(-s, -s, -s, s, -s, -s)
        framePart.line(s, -s, -s, s, s, -s)
        framePart.line(s, s, -s, -s, s, -s)
        framePart.line(-s, s, -s, -s, -s, -s)
        // Top face
        framePart.line(-s, -s, s, s, -s, s)
        framePart.line(s, -s, s, s, s, s)
        framePart.line(s, s, s, -s, s, s)
        framePart.line(-s, s, s, -s, -s, s)
        // Verticals
        framePart.line(-s, -s, -s, -s, -s, s)
        framePart.line(s, -s, -s, s, -s, s)
        framePart.line(s, s, -s, s, s, s)
        framePart.line(-s, s, -s, -s, s, s)
        debugFrameModel = modelBuilder.end()!!
        debugFrameInstance = ModelInstance(debugFrameModel)

        // Debug text and shape renderers
        debugShapeRenderer = com.badlogic.gdx.graphics.glutils.ShapeRenderer()
        debugSpriteBatch = com.badlogic.gdx.graphics.g2d.SpriteBatch()
        debugFont = com.badlogic.gdx.graphics.g2d.BitmapFont()

        // Spawn player at tagged node
        world.getNodesWithTag(NodeTags.PLAYER_SPAWN).firstOrNull()?.let { node ->
            player.position.set(node.x.toFloat(), node.y.toFloat(), node.z.toFloat())
        }
    }

    private fun loadWorld(path: String) {
        val loadedWorld = WorldIO.loadWorld(
            path,
            { w, h, d -> World(w, h, d) },
            { type -> modelLoader.createTile(type) }
        )
        world = loadedWorld ?: run {
            World(10, 10, 1).also { WorldGenerator(it, modelLoader).generate() }
        }
    }

    /** Scans the world grid and registers every DoorTile with the animation system. */
    private fun registerDoorAnimations() {
        doorAnimationSystem.clear()
        for (x in 0 until world.width)
            for (y in 0 until world.height)
                for (z in 0 until world.depth) {
                    val node = world.nodes[x][y][z]
                    node.tiles.filterIsInstance<DoorTile>().forEach { doorAnimationSystem.register(it) }
                }
    }

    /** Wires each ToggleTile's linkedDoor from world associations so rendering can reflect door state. */
    private fun wireToggleLinks() {
        world.associations.filter { it.type == "toggle" }.forEach { assoc ->
            val doorTile = assoc.source.tiles.filterIsInstance<DoorTile>().firstOrNull()
            val toggleTile = assoc.target.tiles.filterIsInstance<ToggleTile>().firstOrNull()
            if (doorTile != null && toggleTile != null) {
                toggleTile.linkedDoor = doorTile
            }
        }
    }

    override fun render(delta: Float) {
        // ── Input & Logic ────────────────────────────────────────────────────
        val moveDir = inputHandler.getMovementDirection()
        movementSystem.move(player, moveDir, delta, moveSpeed)

        cameraManager.cameraYaw += inputHandler.getCameraYawChange(delta)
        cameraManager.zoom(inputHandler.getZoomChange(delta))
        cameraManager.update(player.position)

        if (inputHandler.isDebugToggleJustPressed()) debugMode = !debugMode

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.screen = MainMenuScreen(game)
        }

        if (inputHandler.isInteractionJustPressed()) {
            // Use the player's facing direction (updated by movement) for interaction
            interactionSystem.interact(player, player.facingDirection)
        }

        player.update(delta)
        doorAnimationSystem.update(delta)
        inventoryUI.update(player)

        // ── Rendering ────────────────────────────────────────────────────────
        Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // Sync player visual to logic position
        playerInstance.transform.setTranslation(player.position.x, player.position.y, player.position.z)

        modelBatch.begin(camera)
        val playerZ = Math.ceil(player.position.z.toDouble()).toInt()
        worldRenderer.render(world, modelBatch, environment, maxZ = playerZ)
        modelBatch.render(playerInstance, environment)

        if (debugMode) {
            axesInstance.transform.setTranslation(player.position.x, player.position.y, player.position.z)
            modelBatch.render(axesInstance)
        }
        modelBatch.end()

        if (debugMode) {
            val playerNodeX = Math.round(player.position.x)
            val playerNodeY = Math.round(player.position.y)
            val playerNodeZ = Math.floor(player.position.z.toDouble()).toInt()

            // ── Node frames via ShapeRenderer ─────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            val s = 0.5f
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tiles.isEmpty()) continue

                        val color = when {
                            x == playerNodeX && y == playerNodeY && z == playerNodeZ -> Color.BLUE
                            node.tiles.any { it.slot == TileSlot.DOOR } -> Color.GREEN
                            else -> Color(1f, 1f, 1f, 0.3f)
                        }
                        debugShapeRenderer.color = color
                        val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat()
                        // Bottom
                        debugShapeRenderer.line(fx-s,fy-s,fz-s, fx+s,fy-s,fz-s)
                        debugShapeRenderer.line(fx+s,fy-s,fz-s, fx+s,fy+s,fz-s)
                        debugShapeRenderer.line(fx+s,fy+s,fz-s, fx-s,fy+s,fz-s)
                        debugShapeRenderer.line(fx-s,fy+s,fz-s, fx-s,fy-s,fz-s)
                        // Top
                        debugShapeRenderer.line(fx-s,fy-s,fz+s, fx+s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy-s,fz+s, fx+s,fy+s,fz+s)
                        debugShapeRenderer.line(fx+s,fy+s,fz+s, fx-s,fy+s,fz+s)
                        debugShapeRenderer.line(fx-s,fy+s,fz+s, fx-s,fy-s,fz+s)
                        // Verticals
                        debugShapeRenderer.line(fx-s,fy-s,fz-s, fx-s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy-s,fz-s, fx+s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy+s,fz-s, fx+s,fy+s,fz+s)
                        debugShapeRenderer.line(fx-s,fy+s,fz-s, fx-s,fy+s,fz+s)
                    }
                }
            }
            debugShapeRenderer.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Player facing direction arrow ─────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glLineWidth(3f)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            debugShapeRenderer.color = Color.YELLOW
            val px = player.position.x
            val py = player.position.y
            val pz = player.position.z
            val fd = player.facingDirection
            debugShapeRenderer.line(px, py, pz, px + fd.x * 0.8f, py + fd.y * 0.8f, pz)
            debugShapeRenderer.end()
            Gdx.gl.glLineWidth(1f)
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Tag text labels — projected to screen 2D ──────────────────────
            val viewW = Gdx.graphics.width.toFloat()
            val viewH = Gdx.graphics.height.toFloat()
            val projPos = com.badlogic.gdx.math.Vector3()
            debugSpriteBatch.projectionMatrix = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW, viewH)
            debugSpriteBatch.begin()
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isEmpty()) continue
                        projPos.set(x.toFloat(), y.toFloat() + 0.45f, z.toFloat())
                        camera.project(projPos)
                        if (projPos.z in 0f..1f) {
                            val label = node.tags.joinToString("\n")
                            debugFont.draw(debugSpriteBatch, label, projPos.x - 30f, projPos.y + 4f)
                        }
                    }
                }
            }
            debugSpriteBatch.end()

            // ── Association lines ─────────────────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            world.associations.forEach { assoc ->
                // Skip key associations where the key has been picked up
                if (assoc.type == "key" && assoc.target.items.none { it is com.roguelike.core.model.KeyItem }) return@forEach

                val color = when (assoc.type) {
                    "toggle" -> Color.YELLOW
                    "key"    -> Color.CYAN
                    else     -> Color.WHITE
                }
                debugShapeRenderer.color = color
                debugShapeRenderer.line(
                    assoc.source.x.toFloat(), assoc.source.y.toFloat(), assoc.source.z.toFloat(),
                    assoc.target.x.toFloat(), assoc.target.y.toFloat(), assoc.target.z.toFloat()
                )
            }
            debugShapeRenderer.end()

            // ── Stairs direction arrows ───────────────────────────────────────
            Gdx.gl.glLineWidth(3f)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            debugShapeRenderer.color = Color(0.2f, 0.5f, 1f, 1f)
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        node.tiles.filterIsInstance<StairsTile>().forEach { stair ->
                            val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat() + 0.05f
                            val (dx, dy) = when (stair) {
                                is StairsNTile -> 0f to 1f
                                is StairsSTile -> 0f to -1f
                                is StairsETile -> 1f to 0f
                                is StairsWTile -> -1f to 0f
                                else -> 0f to 0f
                            }
                            val len = 0.35f; val head = 0.12f
                            val ex = fx + dx * len; val ey = fy + dy * len
                            debugShapeRenderer.line(fx, fy, fz, ex, ey, fz)
                            debugShapeRenderer.line(ex, ey, fz, ex - dx * head + dy * head, ey - dy * head + dx * head, fz)
                            debugShapeRenderer.line(ex, ey, fz, ex - dx * head - dy * head, ey - dy * head - dx * head, fz)
                        }
                    }
                }
            }
            debugShapeRenderer.end()
            Gdx.gl.glLineWidth(1f)
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        modelBatch.dispose()
        assetLoader.dispose()
        stage.dispose()
        skin.dispose()
        debugShapeRenderer.dispose()
        debugSpriteBatch.dispose()
        debugFont.dispose()
    }

    override fun hide() {}
    override fun pause() {}
    override fun resume() {}
}
